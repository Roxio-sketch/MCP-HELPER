package xyz.langyo.minecraft.mcp.common.selection;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import xyz.langyo.minecraft.mcp.common.StructureLibrary;
import xyz.langyo.minecraft.mcp.common.ToolParams;

/**
 * 选区状态的唯一入口。
 *
 * <p>权威状态保存在逻辑服务端（单人/局域网里的集成服务器），按玩家 UUID 隔离：
 * 玩家 A 的边界玩家 B 既看不到也改不了，也不向所有人广播（方案第 19 节）。</p>
 *
 * <p>交互模型是“不限次数右键外扩包围盒”：每右键一个外围方块就调
 * {@link #addPoint}，点落在当前盒内则不计入（方案第四、五节）。撤销由
 * {@link SelectionData} 自己维护的极值快照完成，不保存方块列表。</p>
 *
 * <p>客户端只保留“本地玩家自己那一份”的只读缓存，由 {@link SelectionSyncPacket}
 * 更新，供线框渲染与 HUD 使用。选区只在边界变化 / clear / undo / 登录 / 切维度时
 * 同步，不逐帧发包（方案第 24 节）。</p>
 */
public final class SelectionManager {

    /** {@link #addPoint} 的结果，供调用方决定提示文案。 */
    public enum AddOutcome {
        /** 边界真的扩大了。 */
        EXPANDED,
        /** 点落在现有包围盒内部，边界不变。 */
        INSIDE,
        /** 选区在别的维度，按方案第 20 节不清空也不静默重建，要求先 clear。 */
        OTHER_DIMENSION,
        /** 参数无效，什么也没做。 */
        NOTHING
    }

    /** 逻辑服务端的权威选区，按玩家 UUID 隔离。 */
    private static final Map<UUID, SelectionData> SERVER = new ConcurrentHashMap<>();

    /** 客户端只读缓存：本地玩家自己的选区。 */
    private static volatile SelectionData clientData;
    private static volatile SelectionData.Validity clientValidity = SelectionData.Validity.NO_SELECTION;

    private SelectionManager() {}

    // ===================== 服务端权威状态 =====================

    public static SelectionData serverSelection(UUID playerId) {
        return playerId == null ? null : SERVER.get(playerId);
    }

    /**
     * 把一个方块坐标并入当前选区。
     *
     * <p>选区在别的维度时不清空、不重建，返回 {@link AddOutcome#OTHER_DIMENSION}：
     * 世界间坐标没有可比性，静默新建只会让玩家在切回来后看到一个意料之外的盒子。</p>
     */
    public static AddOutcome addPoint(ServerPlayer player, BlockPos pos) {
        if (player == null || pos == null) return AddOutcome.NOTHING;
        ResourceLocation dim = player.serverLevel().dimension().location();
        SelectionData cur = SERVER.get(player.getUUID());
        if (cur != null && !cur.isEmpty() && cur.dimension() != null
                && !dim.equals(cur.dimension())) {
            // 状态没变，也要让客户端重新拿一次（可能刚切维度，界面上还是旧选区）。
            sync(player);
            return AddOutcome.OTHER_DIMENSION;
        }
        if (cur == null || cur.isEmpty()) cur = SelectionData.empty(dim);
        SelectionData.AddResult result = cur.add(pos);
        SERVER.put(player.getUUID(), result.data);
        sync(player);
        return result.changed ? AddOutcome.EXPANDED : AddOutcome.INSIDE;
    }

    /** 撤销上一次有效的边界扩展；没有可撤销的历史时返回 false。 */
    public static boolean undo(ServerPlayer player) {
        if (player == null) return false;
        SelectionData cur = SERVER.get(player.getUUID());
        if (cur == null || !cur.canUndo()) return false;
        SERVER.put(player.getUUID(), cur.undo());
        sync(player);
        return true;
    }

    public static void clear(ServerPlayer player) {
        if (player == null) return;
        SERVER.remove(player.getUUID());
        sync(player);
    }

    public static void remove(UUID playerId) {
        if (playerId != null) SERVER.remove(playerId);
    }

    /** 用玩家当前所处的世界现算选区状态。 */
    public static SelectionData.Validity validate(ServerPlayer player, SelectionData data) {
        if (data == null) return SelectionData.Validity.NO_SELECTION;
        ServerLevel level = player.serverLevel();
        return data.validate(level.dimension().location(),
                level.getMinBuildHeight(), level.getMaxBuildHeight(), StructureLibrary.maxVolume());
    }

    /** 把当前选区同步给该玩家自己（只在状态变化 / 登录 / 切维度时调用）。 */
    public static void sync(ServerPlayer player) {
        if (player == null) return;
        SelectionData data = SERVER.get(player.getUUID());
        SelectionNetwork.sendToPlayer(player, data, validate(player, data));
    }

    // ===================== 客户端只读缓存 =====================

    public static SelectionData clientSelection() {
        return clientData;
    }

    public static SelectionData.Validity clientValidity() {
        return clientValidity;
    }

    public static void applyClientSync(SelectionSyncPacket packet) {
        if (packet == null) {
            clientData = null;
            clientValidity = SelectionData.Validity.NO_SELECTION;
            return;
        }
        clientData = packet.data();
        clientValidity = packet.validity();
    }

    public static void clearClient() {
        clientData = null;
        clientValidity = SelectionData.Validity.NO_SELECTION;
    }

    // ===================== save_structure(use_selection=true) =====================

    /**
     * 供 {@code save_structure} 的 {@code use_selection=true} 使用：参数里没有显式
     * pos1/pos2 时，用本地玩家的当前选区补齐。
     *
     * @return 出错信息；成功（或被显式坐标覆盖）返回 {@code null}
     */
    public static String fillParamsFromSelection(Minecraft mc, Map<String, String> params) {
        if (params == null || !ToolParams.bool(params, "use_selection", false)) return null;
        // 显式坐标优先，只有缺失时才用选区。
        if (ToolParams.vec3(ToolParams.str(params, "pos1", null)) != null
                && ToolParams.vec3(ToolParams.str(params, "pos2", null)) != null) {
            return null;
        }
        if (mc == null || mc.player == null) return "use_selection requires a local player";
        LocalPlayer local = mc.player;
        SelectionData data = SERVER.get(local.getUUID());
        if (data == null || data.isEmpty()) {
            return "no selection (right-click block edges with the Structure Selector, or use /mcp selection add)";
        }
        if (!data.isComplete()) {
            return "selection has fewer than two boundary points (add more edge points)";
        }
        if (mc.level == null) return "use_selection requires a loaded world";
        if (!data.dimension().equals(mc.level.dimension().location())) {
            return "selection is in another dimension: " + data.dimension();
        }
        params.put("dimension", data.dimension().toString());
        params.put("pos1", data.minX() + "," + data.minY() + "," + data.minZ());
        params.put("pos2", data.maxX() + "," + data.maxY() + "," + data.maxZ());
        return null;
    }

    // ===================== /api/status 里的 Selection 区块 =====================

    /**
     * 把本地玩家的选区渲染成一段 JSON（没有时返回 {@code null}）。
     * 只读，不参与任何工具逻辑。
     */
    public static String statusJson(Minecraft mc) {
        try {
            if (mc == null || mc.player == null) return null;
            SelectionData data = SERVER.get(mc.player.getUUID());
            if (data == null) return null;
            StringBuilder sb = new StringBuilder(200);
            sb.append('{');
            sb.append("\"dimension\":\"").append(escape(String.valueOf(data.dimension()))).append('"');
            sb.append(",\"points\":").append(data.points());
            // pos1/pos2 保持旧字段名，值就是包围盒的最小/最大角，调试页与旧脚本不用改。
            sb.append(",\"pos1\":").append(vec(data.pos1()));
            sb.append(",\"pos2\":").append(vec(data.pos2()));
            sb.append(",\"complete\":").append(data.isComplete());
            sb.append(",\"can_undo\":").append(data.canUndo());
            sb.append(",\"size\":");
            if (data.isComplete()) {
                sb.append('[').append(data.sizeX()).append(',').append(data.sizeY())
                        .append(',').append(data.sizeZ()).append(']');
            } else {
                sb.append("null");
            }
            sb.append(",\"volume\":").append(data.volume());
            SelectionData.Validity validity = mc.level == null
                    ? data.validate(data.dimension(), Integer.MIN_VALUE, Integer.MAX_VALUE,
                            StructureLibrary.maxVolume())
                    : data.validate(mc.level.dimension().location(), mc.level.getMinBuildHeight(),
                            mc.level.getMaxBuildHeight(), StructureLibrary.maxVolume());
            sb.append(",\"valid\":").append(validity.isValid());
            sb.append(",\"state\":\"").append(validity.name()).append('"');
            sb.append('}');
            return sb.toString();
        } catch (Throwable t) {
            return null;
        }
    }

    private static String vec(BlockPos pos) {
        if (pos == null) return "null";
        return "[" + pos.getX() + "," + pos.getY() + "," + pos.getZ() + "]";
    }

    private static String escape(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' || c == '\\') sb.append('\\').append(c);
            else if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
            else sb.append(c);
        }
        return sb.toString();
    }
}
