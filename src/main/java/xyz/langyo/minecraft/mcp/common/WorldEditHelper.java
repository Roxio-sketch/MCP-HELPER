package xyz.langyo.minecraft.mcp.common;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.ByteArrayTag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongArrayTag;
import net.minecraft.nbt.NumericTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

/**
 * 高效世界编辑接口（优化文档里的「身体」）。
 *
 * <p>设计目标只有一句话：一个指令完成一批操作，返回尽量少。</p>
 * <ul>
 *   <li>{@code scan_area}：紧凑数组返回，默认跳过空气，按需带方块状态 / 方块实体 / 实体；</li>
 *   <li>{@code build_structure}：一次提交成百上千个方块，按服务器 tick 分批放置，不卡顿；</li>
 *   <li>{@code compare_structure}：只回 missing / wrong / extra 三类差异；</li>
 *   <li>{@code get_build_progress} / {@code cancel_build}：只回 task_id、status、total、done、failed。</li>
 * </ul>
 *
 * <p>建造不解析建筑设计、不做施工计划：拆分批次由外部 AI 决定（{@code batch} 参数只控制
 * 每 tick 放多少块）。</p>
 */
public final class WorldEditHelper {

    /** 单次 scan / compare 允许的最大体积，防止一次调用拖垮游戏。 */
    private static final int MAX_VOLUME = 4_000_000;
    private static final int DEFAULT_SCAN_LIMIT = 20_000;
    private static final int DEFAULT_DIFF_LIMIT = 2_000;
    private static final int DEFAULT_BATCH = 4_096;
    private static final int MAX_BUILD_BLOCKS = 200_000;

    /** 当前建造任务；服务器 tick 驱动。 */
    private static volatile BuildTask activeTask;
    /** 最近一次建造任务，任务结束后 get_build_progress 仍能读到最终结果。 */
    private static volatile BuildTask lastTask;
    private static int taskSeq = 0;

    private WorldEditHelper() {}

    // ===================== scan_area =====================

    /** 紧凑扫描一个长方体区域。 */
    public static String scanArea(Minecraft mc, Map<String, String> p) {
        Level level = resolveLevel(mc, p);
        if (level == null) return JsonHelper.error("no world loaded");
        int[] b = bounds(p);
        if (b == null) return JsonHelper.error("missing pos1/pos2 (or origin + size)");
        int minX = b[0], minY = b[1], minZ = b[2], maxX = b[3], maxY = b[4], maxZ = b[5];
        if (minY < level.getMinBuildHeight() || maxY >= level.getMaxBuildHeight()) {
            return JsonHelper.error("y out of build height (" + level.getMinBuildHeight()
                    + ".." + (level.getMaxBuildHeight() - 1) + ")");
        }
        long volume = (long) (maxX - minX + 1) * (maxY - minY + 1) * (maxZ - minZ + 1);
        if (volume > MAX_VOLUME) {
            return JsonHelper.error("region too large: " + volume + " blocks (max " + MAX_VOLUME + ")");
        }

        boolean includeAir = ToolParams.bool(p, "include_air", false);
        boolean includeState = ToolParams.bool(p, "include_block_state", false);
        boolean includeBlockEntities = ToolParams.bool(p, "include_block_entities", false);
        boolean includeEntities = ToolParams.bool(p, "include_entities", false);
        int limit = ToolParams.intVal(p, "limit", DEFAULT_SCAN_LIMIT);
        if (limit <= 0) limit = DEFAULT_SCAN_LIMIT;
        int offset = Math.max(0, ToolParams.intVal(p, "offset", 0));

        StringBuilder blocks = new StringBuilder(Math.min(limit, 4096) * 24);
        StringBuilder blockEntities = new StringBuilder();
        int seen = 0, emitted = 0;
        boolean truncated = false;
        outer:
        for (int y = minY; y <= maxY; y++) {
            for (int z = minZ; z <= maxZ; z++) {
                for (int x = minX; x <= maxX; x++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    BlockState st = level.getBlockState(pos);
                    if (st.isAir() && !includeAir) continue;
                    if (seen++ < offset) continue;
                    if (emitted >= limit) {
                        truncated = true;
                        break outer;
                    }
                    if (blocks.length() > 0) blocks.append(',');
                    blocks.append('[').append(x - minX).append(',').append(y - minY).append(',')
                            .append(z - minZ).append(",\"")
                            .append(JsonHelper.escape(blockId(st, includeState))).append("\"]");
                    if (includeBlockEntities && st.hasBlockEntity()) {
                        BlockEntity be = level.getBlockEntity(pos);
                        if (be != null) {
                            CompoundTag nbt = be.saveWithoutMetadata();
                            if (blockEntities.length() > 0) blockEntities.append(',');
                            blockEntities.append('[').append(x - minX).append(',').append(y - minY).append(',')
                                    .append(z - minZ)
                                    .append(",\"").append(JsonHelper.escape(
                                            BuiltInRegistries.BLOCK.getKey(st.getBlock()).toString()))
                                    .append("\",")
                                    .append(nbtToJson(nbt).toString())
                                    .append(']');
                        }
                    }
                    emitted++;
                }
            }
        }

        StringBuilder sb = new StringBuilder(blocks.length() + 128);
        sb.append("{\"origin\":[").append(minX).append(',').append(minY).append(',').append(minZ).append(']');
        sb.append(",\"size\":[").append(maxX - minX + 1).append(',').append(maxY - minY + 1)
                .append(',').append(maxZ - minZ + 1).append(']');
        sb.append(",\"count\":").append(emitted);
        if (offset > 0) sb.append(",\"offset\":").append(offset);
        if (truncated) {
            sb.append(",\"truncated\":true,\"next_offset\":").append(offset + emitted);
        }
        sb.append(",\"blocks\":[").append(blocks).append(']');
        // 请求了 include_block_entities 就始终输出该字段（空则为 []），
        // 否则 AI 无法区分「区域内确实没有方块实体」与「参数未被支持」。
        if (includeBlockEntities) {
            sb.append(",\"block_entities\":[").append(blockEntities).append(']');
        }
        if (includeEntities) appendEntities(level, minX, minY, minZ, maxX, maxY, maxZ, sb);
        sb.append('}');
        return sb.toString();
    }

    private static void appendEntities(Level level, int minX, int minY, int minZ,
                                       int maxX, int maxY, int maxZ, StringBuilder sb) {
        AABB box = new AABB(minX, minY, minZ, maxX + 1.0, maxY + 1.0, maxZ + 1.0);
        StringBuilder ents = new StringBuilder();
        for (Entity e : level.getEntitiesOfClass(Entity.class, box)) {
            if (e instanceof Player) continue;
            if (ents.length() > 0) ents.append(',');
            ents.append('[').append(round(e.getX())).append(',').append(round(e.getY())).append(',')
                    .append(round(e.getZ())).append(",\"")
                    .append(JsonHelper.escape(EntityType.getKey(e.getType()).toString())).append("\"]");
        }
        if (ents.length() > 0) sb.append(",\"entities\":[").append(ents).append(']');
    }

    /** 把 NBT 递归转成 Gson JSON，供 {@code scan_area} 的 {@code block_entities} 直接携带方块实体数据。 */
    private static JsonElement nbtToJson(Tag tag) {
        if (tag == null) return JsonNull.INSTANCE;
        if (tag instanceof CompoundTag c) {
            JsonObject o = new JsonObject();
            for (String k : c.getAllKeys()) o.add(k, nbtToJson(c.get(k)));
            return o;
        }
        if (tag instanceof ListTag l) {
            JsonArray a = new JsonArray();
            for (int i = 0; i < l.size(); i++) a.add(nbtToJson(l.get(i)));
            return a;
        }
        if (tag instanceof ByteArrayTag b) {
            JsonArray a = new JsonArray();
            for (byte v : b.getAsByteArray()) a.add(new JsonPrimitive(v));
            return a;
        }
        if (tag instanceof IntArrayTag ia) {
            JsonArray a = new JsonArray();
            for (int v : ia.getAsIntArray()) a.add(new JsonPrimitive(v));
            return a;
        }
        if (tag instanceof LongArrayTag la) {
            JsonArray a = new JsonArray();
            for (long v : la.getAsLongArray()) a.add(new JsonPrimitive(v));
            return a;
        }
        if (tag instanceof StringTag s) return new JsonPrimitive(s.getAsString());
        if (tag instanceof NumericTag n) return new JsonPrimitive(n.getAsNumber());
        return new JsonPrimitive(tag.getAsString());
    }

    // ===================== build_structure =====================

    /** 一次提交一批方块，跨 tick 放置。 */
    public static String buildStructure(Minecraft mc, Map<String, String> p) {
        ServerLevel level = serverLevel(mc, p);
        if (level == null) {
            return JsonHelper.error("build requires the integrated server "
                    + "(host a singleplayer/LAN world; a LAN guest client cannot edit the world)");
        }
        int[] origin = ToolParams.vec3(ToolParams.str(p, "origin", null));
        if (origin == null) return JsonHelper.error("missing origin [x,y,z]");
        String raw = ToolParams.str(p, "blocks", null);
        if (raw == null) return JsonHelper.error("missing blocks");
        String[] entries = ToolParams.blockEntries(raw);
        if (entries == null) {
            return JsonHelper.error("bad blocks format; expected [[dx,dy,dz,\"minecraft:stone\"], ...]");
        }
        if (entries.length == 0) return JsonHelper.error("empty blocks");
        if (entries.length > MAX_BUILD_BLOCKS) {
            return JsonHelper.error("too many blocks: " + entries.length + " (max " + MAX_BUILD_BLOCKS
                    + "); split into several build_structure calls");
        }

        HolderLookup.RegistryLookup<Block> lookup = level.registryAccess().lookupOrThrow(Registries.BLOCK);
        List<Pending> list = new ArrayList<>(entries.length);
        for (int i = 0; i < entries.length; i++) {
            String[] parts = ToolParams.splitEntry(entries[i]);
            if (parts == null) return JsonHelper.error("bad block entry #" + i + ": " + entries[i]);
            int dx, dy, dz;
            try {
                dx = (int) Double.parseDouble(parts[0]);
                dy = (int) Double.parseDouble(parts[1]);
                dz = (int) Double.parseDouble(parts[2]);
            } catch (NumberFormatException e) {
                return JsonHelper.error("bad coords in block entry #" + i + ": " + entries[i]);
            }
            BlockStateParser.BlockResult br;
            try {
                br = BlockStateParser.parseForBlock(lookup, parts[3], true);
            } catch (Exception e) {
                return JsonHelper.error("bad block #" + i + " '" + parts[3] + "': " + e.getMessage());
            }
            BlockPos pos = new BlockPos(origin[0] + dx, origin[1] + dy, origin[2] + dz);
            list.add(new Pending(pos, br.blockState(), br.nbt()));
        }

        int batch = ToolParams.intVal(p, "batch", DEFAULT_BATCH);
        if (batch < 1) batch = 1;
        if (batch > 65_536) batch = 65_536;
        BuildTask task = new BuildTask(nextTaskId(), level, list, batch);
        activeTask = task;
        lastTask = task;
        return task.progressJson();
    }

    /** 由服务器 tick 调用：推进当前建造任务。 */
    public static void tick(MinecraftServer server) {
        BuildTask t = activeTask;
        if (t == null) return;
        try {
            if (!"running".equals(t.status)) {
                activeTask = null;
                return;
            }
            t.processBatch();
            if (!"running".equals(t.status)) activeTask = null;
        } catch (Throwable ignored) {
        }
    }

    /** 只回 task_id / status / total / done / failed。 */
    public static String getBuildProgress(Map<String, String> p) {
        String want = ToolParams.str(p, "task_id", null);
        BuildTask t = activeTask != null ? activeTask : lastTask;
        if (t == null) return "{\"status\":\"idle\"}";
        if (want != null && !want.equals(t.id)) {
            return "{\"task_id\":\"" + JsonHelper.escape(want) + "\",\"status\":\"unknown\"}";
        }
        return t.progressJson();
    }

    /** 取消当前（或指定）建造任务。 */
    public static String cancelBuild(Map<String, String> p) {
        BuildTask t = activeTask;
        if (t == null) return "{\"status\":\"idle\"}";
        String want = ToolParams.str(p, "task_id", null);
        if (want != null && !want.equals(t.id)) {
            return "{\"task_id\":\"" + JsonHelper.escape(want) + "\",\"status\":\"unknown\"}";
        }
        t.status = "cancelled";
        activeTask = null;
        return t.progressJson();
    }

    // ===================== compare_structure =====================

    /**
     * 对比世界与期望结构，只回差异。
     *
     * <p>期望来源二选一：{@code id}（已保存的 Structure NBT）或内联 {@code blocks}。</p>
     */
    public static String compareStructure(Minecraft mc, Map<String, String> p) {
        Level level = resolveLevel(mc, p);
        if (level == null) return JsonHelper.error("no world loaded");
        int[] origin = ToolParams.vec3(ToolParams.str(p, "origin", null));
        if (origin == null) return JsonHelper.error("missing origin [x,y,z]");

        HolderLookup.RegistryLookup<Block> lookup = level.registryAccess().lookupOrThrow(Registries.BLOCK);
        String id = ToolParams.str(p, "id", null);
        String rawBlocks = ToolParams.str(p, "blocks", null);
        if (id == null && rawBlocks == null) return JsonHelper.error("missing id or blocks");

        Map<Long, BlockState> expected = new LinkedHashMap<>();
        int sx = 1, sy = 1, sz = 1;
        String anchor = ToolParams.str(p, "anchor", null);

        if (id != null) {
            ResourceLocation rl = StructureLibrary.parseId(id);
            if (rl == null) return JsonHelper.error("bad id: " + id);
            CompoundTag tag = StructureLibrary.loadNbt(mc, rl.toString());
            if (tag == null) return JsonHelper.error("structure not found: " + rl);
            int[] size = StructureLibrary.nbtSize(tag);
            if (size == null) return JsonHelper.error("malformed structure nbt: " + rl);
            sx = size[0];
            sy = size[1];
            sz = size[2];
            StructureLibrary.forEachBlock(tag, lookup, (x, y, z, state) ->
                    expected.put(BlockPos.asLong(x, y, z), state));
            if (anchor == null) anchor = StructureLibrary.prefabAnchor(mc, rl.toString());
        } else {
            String[] entries = ToolParams.blockEntries(rawBlocks);
            if (entries == null) {
                return JsonHelper.error("bad blocks format; expected [[dx,dy,dz,\"minecraft:stone\"], ...]");
            }
            int maxX = 0, maxY = 0, maxZ = 0;
            for (int i = 0; i < entries.length; i++) {
                String[] parts = ToolParams.splitEntry(entries[i]);
                if (parts == null) return JsonHelper.error("bad block entry #" + i + ": " + entries[i]);
                int dx, dy, dz;
                try {
                    dx = (int) Double.parseDouble(parts[0]);
                    dy = (int) Double.parseDouble(parts[1]);
                    dz = (int) Double.parseDouble(parts[2]);
                } catch (NumberFormatException e) {
                    return JsonHelper.error("bad coords in block entry #" + i + ": " + entries[i]);
                }
                BlockStateParser.BlockResult br;
                try {
                    br = BlockStateParser.parseForBlock(lookup, parts[3], true);
                } catch (Exception e) {
                    return JsonHelper.error("bad block #" + i + " '" + parts[3] + "': " + e.getMessage());
                }
                expected.put(BlockPos.asLong(dx, dy, dz), br.blockState());
                maxX = Math.max(maxX, dx);
                maxY = Math.max(maxY, dy);
                maxZ = Math.max(maxZ, dz);
            }
            sx = maxX + 1;
            sy = maxY + 1;
            sz = maxZ + 1;
        }

        // 锚点：默认最小角，预制建筑可按存储的 anchor 对齐。
        int offX = 0, offY = 0, offZ = 0;
        if (anchor != null) {
            switch (anchor.toLowerCase(Locale.ROOT)) {
                case "bottom_center": offX = -sx / 2; offZ = -sz / 2; break;
                case "center": offX = -sx / 2; offY = -sy / 2; offZ = -sz / 2; break;
                default: break;
            }
        }
        int baseX = origin[0] + offX, baseY = origin[1] + offY, baseZ = origin[2] + offZ;

        boolean includeState = ToolParams.bool(p, "include_block_state", false);
        boolean includeExtra = ToolParams.bool(p, "include_extra", true);
        boolean includeAir = ToolParams.bool(p, "include_air", false);
        int limit = ToolParams.intVal(p, "limit", DEFAULT_DIFF_LIMIT);
        if (limit <= 0) limit = DEFAULT_DIFF_LIMIT;

        StringBuilder missing = new StringBuilder();
        StringBuilder wrong = new StringBuilder();
        StringBuilder extra = new StringBuilder();
        int[] emitted = {0};
        boolean[] truncated = {false};

        for (Map.Entry<Long, BlockState> e : expected.entrySet()) {
            long key = e.getKey();
            int dx = BlockPos.getX(key), dy = BlockPos.getY(key), dz = BlockPos.getZ(key);
            BlockState exp = e.getValue();
            BlockState actual = level.getBlockState(new BlockPos(baseX + dx, baseY + dy, baseZ + dz));
            if (actual.isAir() && !exp.isAir()) {
                if (!addDiff(missing, emitted, truncated, limit,
                        "[" + dx + "," + dy + "," + dz + ",\""
                                + JsonHelper.escape(blockId(exp, includeState)) + "\"]")) break;
            } else if (!sameState(actual, exp, includeState)) {
                if (!addDiff(wrong, emitted, truncated, limit,
                        "[" + dx + "," + dy + "," + dz + ",\""
                                + JsonHelper.escape(blockId(exp, includeState)) + "\",\""
                                + JsonHelper.escape(blockId(actual, includeState)) + "\"]")) break;
            }
        }

        if (includeExtra && !truncated[0]) {
            long volume = (long) sx * sy * sz;
            if (volume <= MAX_VOLUME) {
                outer:
                for (int y = 0; y < sy; y++) {
                    for (int z = 0; z < sz; z++) {
                        for (int x = 0; x < sx; x++) {
                            long key = BlockPos.asLong(x, y, z);
                            if (expected.containsKey(key)) continue;
                            BlockState actual = level.getBlockState(new BlockPos(baseX + x, baseY + y, baseZ + z));
                            if (actual.isAir() && !includeAir) continue;
                            if (!addDiff(extra, emitted, truncated, limit,
                                    "[" + x + "," + y + "," + z + ",\""
                                            + JsonHelper.escape(blockId(actual, includeState)) + "\"]")) break outer;
                        }
                    }
                }
            }
        }

        boolean ok = missing.length() == 0 && wrong.length() == 0 && extra.length() == 0;
        StringBuilder sb = new StringBuilder(missing.length() + wrong.length() + extra.length() + 96);
        sb.append("{\"ok\":").append(ok);
        sb.append(",\"missing\":[").append(missing).append(']');
        sb.append(",\"wrong\":[").append(wrong).append(']');
        sb.append(",\"extra\":[").append(extra).append(']');
        if (truncated[0]) sb.append(",\"truncated\":true");
        sb.append('}');
        return sb.toString();
    }

    private static boolean addDiff(StringBuilder sb, int[] emitted, boolean[] truncated, int limit, String item) {
        if (emitted[0] >= limit) {
            truncated[0] = true;
            return false;
        }
        if (sb.length() > 0) sb.append(',');
        sb.append(item);
        emitted[0]++;
        return true;
    }

    private static boolean sameState(BlockState a, BlockState b, boolean includeState) {
        if (includeState) return a.equals(b);
        return BuiltInRegistries.BLOCK.getKey(a.getBlock()).equals(BuiltInRegistries.BLOCK.getKey(b.getBlock()));
    }

    // ===================== 公共小工具 =====================

    /** 当前参数指定的维度；集成服务器存在时返回 ServerLevel，否则退回客户端 Level。 */
    public static Level resolveLevel(Minecraft mc, Map<String, String> p) {
        if (mc == null) return null;
        ResourceKey<Level> key = dimensionKey(mc, ToolParams.str(p, "dimension", null));
        if (key == null) return null;
        MinecraftServer server = mc.getSingleplayerServer();
        if (server != null) {
            ServerLevel sl = server.getLevel(key);
            return sl != null ? sl : server.overworld();
        }
        return mc.level;
    }

    static ServerLevel serverLevel(Minecraft mc, Map<String, String> p) {
        Level level = resolveLevel(mc, p);
        return level instanceof ServerLevel sl ? sl : null;
    }

    private static ResourceKey<Level> dimensionKey(Minecraft mc, String dim) {
        if (dim == null || dim.isEmpty()) {
            return mc != null && mc.level != null ? mc.level.dimension() : Level.OVERWORLD;
        }
        String d = dim.trim().toLowerCase(Locale.ROOT);
        switch (d) {
            case "overworld":
            case "minecraft:overworld":
                return Level.OVERWORLD;
            case "nether":
            case "the_nether":
            case "minecraft:the_nether":
                return Level.NETHER;
            case "end":
            case "the_end":
            case "minecraft:the_end":
                return Level.END;
            default:
                break;
        }
        ResourceLocation id = ResourceLocation.tryParse(d);
        if (id == null) return null;
        return ResourceKey.create(Registries.DIMENSION, id);
    }

    /** 解析 pos1/pos2（或 origin + size）为 min/max 六个整数，失败返回 null。 */
    private static int[] bounds(Map<String, String> p) {
        int[] a = ToolParams.vec3(ToolParams.str(p, "pos1", ToolParams.str(p, "min", null)));
        int[] b = ToolParams.vec3(ToolParams.str(p, "pos2", ToolParams.str(p, "max", null)));
        if (a == null || b == null) {
            int[] origin = ToolParams.vec3(ToolParams.str(p, "origin", null));
            int[] size = ToolParams.vec3(ToolParams.str(p, "size", null));
            if (origin == null || size == null) return null;
            a = origin;
            b = new int[]{origin[0] + size[0] - 1, origin[1] + size[1] - 1, origin[2] + size[2] - 1};
        }
        return new int[]{
                Math.min(a[0], b[0]), Math.min(a[1], b[1]), Math.min(a[2], b[2]),
                Math.max(a[0], b[0]), Math.max(a[1], b[1]), Math.max(a[2], b[2])
        };
    }

    private static String blockId(BlockState st, boolean includeState) {
        if (includeState) return BlockStateParser.serialize(st);
        return BuiltInRegistries.BLOCK.getKey(st.getBlock()).toString();
    }

    private static String nextTaskId() {
        return "b" + (++taskSeq);
    }

    private static double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    /** 一条待放置的方块。 */
    static final class Pending {
        final BlockPos pos;
        final BlockState state;
        final CompoundTag nbt;

        Pending(BlockPos pos, BlockState state, CompoundTag nbt) {
            this.pos = pos;
            this.state = state;
            this.nbt = nbt;
        }
    }

    /** 分批执行的建造任务；所有字段只在服务器线程写，HTTP 线程只读。 */
    static final class BuildTask {
        final String id;
        final ServerLevel level;
        final List<Pending> list;
        final int batch;
        volatile int cursor;
        volatile int done;
        volatile int failed;
        volatile String status = "running";

        BuildTask(String id, ServerLevel level, List<Pending> list, int batch) {
            this.id = id;
            this.level = level;
            this.list = list;
            this.batch = batch;
        }

        void processBatch() {
            int end = Math.min(list.size(), cursor + batch);
            for (; cursor < end; cursor++) {
                Pending pl = list.get(cursor);
                try {
                    BlockPos pos = pl.pos;
                    if (!level.isInWorldBounds(pos)) {
                        failed++;
                        continue;
                    }
                    level.getChunkAt(pos);
                    level.setBlock(pos, pl.state, Block.UPDATE_ALL);
                    if (pl.nbt != null) {
                        BlockEntity be = level.getBlockEntity(pos);
                        if (be != null) {
                            CompoundTag tag = pl.nbt.copy();
                            tag.putInt("x", pos.getX());
                            tag.putInt("y", pos.getY());
                            tag.putInt("z", pos.getZ());
                            be.load(tag);
                            be.setChanged();
                        }
                    }
                    done++;
                } catch (Throwable t) {
                    failed++;
                }
            }
            if (cursor >= list.size() && "running".equals(status)) {
                status = failed > 0 ? "done_with_failures" : "done";
            }
        }

        String progressJson() {
            return "{\"task_id\":\"" + id + "\",\"status\":\"" + status + "\",\"total\":" + list.size()
                    + ",\"done\":" + done + ",\"failed\":" + failed + "}";
        }
    }

}
