package xyz.langyo.minecraft.mcp.common;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.commands.CommandRuntimeException;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.GameType;
import net.minecraftforge.common.util.FakePlayer;

/**
 * MCP-HELPER 的独立身体。
 *
 * <p>背景：局域网（LAN）世界里，AI（codex）以前只能借用玩家本人的客户端，
 * 于是"和玩家共用一个身体"。这里在集成服务器上创建一个真正加入世界、
 * 名字固定为 {@link #HELPER_NAME} 的独立 {@link FakePlayer}，创造模式并且拥有 OP
 * （作弊命令）权限；AI 的世界操作可以作用在它身上，玩家自己的角色不受影响。</p>
 *
 * <p>为什么用 {@link FakePlayer}：</p>
 * <ul>
 *   <li>MCP-HELPER 没有真实客户端网络连接。若用 {@code PlayerList#placeNewPlayer}
 *       走正常人玩家登录流程，会因 {@code Connection.channel()} 为 null 在
 *       发送登录/区块包时抛 NullPointerException。Forge 的 {@link FakePlayer}
 *       自带 {@code FakePlayerNetHandler}：tick/send/disconnect 均为空实现，
 *       不需要真实 Netty 通道，也就不会发 keep-alive、不会因"客户端无响应"被踢。</li>
 *   <li>{@link FakePlayer} 拥有位置、维度、背包、权限与通过指令实现的世界交互能力；
 *       通过 {@code ServerLevel#addNewPlayer} 注册进世界并进入实体追踪后，其它客户端
 *       就能看到这个"第二个人"。它不会进入 {@link PlayerList}，因此不影响真人玩家连接与数量。</li>
 *   <li>FakePlayer 与真人玩家的区别在于：它没有真实网络连接。因此 {@code sendPairingData}
 *       虽然会随追踪发送 {@code ClientboundAddPlayerPacket}，却不会发送
 *       {@code ClientboundPlayerInfoUpdatePacket}（PlayerInfo）；而客户端
 *       {@code handleAddPlayer} 在缺少对应 PlayerInfo 时会直接丢弃该生成包，导致不可见。
 *       所以这里在注册前先 {@link #broadcastHelperInfo} 广播 ADD_PLAYER 的 PlayerInfo，
 *       并在局域网新玩家加入时补发，保证客户端能看到这具独立身体。</li>
 *   <li>所有会改动服务端状态的操作都派发到服务器线程执行（{@code server.submit}）。</li>
 * </ul>
 */
public final class HelperPlayerManager {

    /** 独立身体的名字，需求固定为 MCP-HELPER。 */
    public static final String HELPER_NAME = "MCP-HELPER";

    /** 离线 UUID 惯例：OfflinePlayer:&lt;name&gt;，保证每次进世界都是同一个身份。 */
    private static final UUID HELPER_UUID =
            UUID.nameUUIDFromBytes(("OfflinePlayer:" + HELPER_NAME).getBytes(StandardCharsets.UTF_8));

    /**
     * 服务器线程持有的独立身体。用静态引用而不是 {@code PlayerList#getPlayerByName}：
     * FakePlayer 不会进入玩家列表，但仍是同一具身体的本体。
     */
    private static volatile FakePlayer helper;

    /** 状态界面每帧读取的缓存，由服务器线程低频刷新，避免渲染时阻塞服务器线程。 */
    private static volatile String cachedSummary = "not spawned";
    private static volatile boolean cachedPresent = false;
    private static int tickCounter = 0;

    private HelperPlayerManager() {}

    public static GameProfile profile() {
        return new GameProfile(HELPER_UUID, HELPER_NAME);
    }

    /** 服务器线程刷新状态缓存用的节拍；每 20 tick 一次。 */
    public static void tick(Minecraft mc) {
        IntegratedServer server = serverOf(mc);
        if (server == null) {
            cachedPresent = false;
            cachedSummary = "not spawned";
            return;
        }
        if (++tickCounter < 20) return;
        tickCounter = 0;
        try {
            server.submit(() -> {
                try { refreshCache(server); } catch (Throwable ignored) {}
            });
        } catch (Throwable ignored) {}
    }

    /** 状态界面用：一行可读的 MCP-HELPER 概况。 */
    public static String summary() {
        return cachedSummary;
    }

    public static boolean isPresent() {
        return cachedPresent;
    }

    /**
     * 在局域网世界里生成 MCP-HELPER（已存在则直接返回）。
     *
     * @return JSON：{@code {"ok":true,...}} 或 {@code {"error":"..."}}
     */
    public static String spawn(Minecraft mc) {
        IntegratedServer server = serverOf(mc);
        if (server == null) return JsonHelper.error("not a singleplayer world");
        if (!McBridge.isLanPublished(mc)) {
            return JsonHelper.error("LAN world is not published; open it first");
        }
        final UUID owner = mc != null && mc.player != null ? mc.player.getUUID() : null;
        String result = onServer(server, () -> spawnOnServer(server, owner));
        refreshCache(server);
        return result != null ? result : JsonHelper.error("spawn failed on server thread");
    }

    /** 保证存在，然后传送到玩家所在维度、所在位置（F9 打开状态界面时调用）。 */
    public static String ensureAndTeleportToPlayer(Minecraft mc) {
        IntegratedServer server = serverOf(mc);
        if (server == null || !McBridge.isLanPublished(mc)) return null;
        final UUID owner = mc != null && mc.player != null ? mc.player.getUUID() : null;
        if (owner == null) return null;
        String result = onServer(server, () -> {
            if (currentHelper(server) == null) {
                spawnOnServer(server, owner);
            }
            FakePlayer hp = currentHelper(server);
            if (hp == null) return JsonHelper.error("could not create helper");
            return teleportOnServer(server, hp, owner);
        });
        refreshCache(server);
        return result;
    }

    /** 把 MCP-HELPER 传送到玩家当前维度与坐标。 */
    public static String teleportToPlayer(Minecraft mc) {
        IntegratedServer server = serverOf(mc);
        if (server == null) return JsonHelper.error("not a singleplayer world");
        final UUID owner = mc != null && mc.player != null ? mc.player.getUUID() : null;
        if (owner == null) return JsonHelper.error("no local player");
        String result = onServer(server, () -> {
            FakePlayer hp = currentHelper(server);
            if (hp == null) return JsonHelper.error("helper not spawned");
            return teleportOnServer(server, hp, owner);
        });
        refreshCache(server);
        return result != null ? result : JsonHelper.error("teleport failed on server thread");
    }

    /** 相对/绝对移动 MCP-HELPER。参数：dx,dy,dz 或 x,y,z。 */
    public static String move(Minecraft mc, Map<String, String> params) {
        IntegratedServer server = serverOf(mc);
        if (server == null) return JsonHelper.error("not a singleplayer world");
        String result = onServer(server, () -> {
            FakePlayer hp = currentHelper(server);
            if (hp == null) return JsonHelper.error("helper not spawned");
            double x = hp.getX();
            double y = hp.getY();
            double z = hp.getZ();
            boolean absolute = params.containsKey("x") || params.containsKey("y") || params.containsKey("z");
            if (absolute) {
                x = parse(params.get("x"), x);
                y = parse(params.get("y"), y);
                z = parse(params.get("z"), z);
            } else {
                x += parse(params.get("dx"), 0);
                y += parse(params.get("dy"), 0);
                z += parse(params.get("dz"), 0);
            }
            // 注意：FakePlayerNetHandler.teleport 是空实现，ServerPlayer.teleportTo 在
            // 同维度下只委托 connection.teleport(...)，对 FakePlayer 等于 no-op，位置不会更新。
            // 因此这里必须用 moveTo 直接设置实体坐标，才能真正移动独立身体。
            hp.moveTo(x, y, z, hp.getYRot(), hp.getXRot());
            return posJson(hp);
        });
        refreshCache(server);
        return result != null ? result : JsonHelper.error("move failed on server thread");
    }

    /**
     * 以 MCP-HELPER 的身份（OP / 作弊权限）执行一条命令。
     *
     * <p>成败判定不依赖命令返回值：{@code performPrefixedCommand} 返回的是
     * {@code dispatcher.execute(...)} 的结果值——像 {@code time query daytime} 这类查询命令
     * 返回的正是查询到的白天刻数（非零），用它判成败会把成功误判成失败。真正的失败是通过
     * {@link CommandSyntaxException}（未知命令 / 参数错误 / 权限不足）与
     * {@link CommandRuntimeException}（运行期失败）表达的。这里直接 parse + execute，
     * 按是否抛出异常来定 {@code ok}，同时把 {@code status}（命令结果值）与命令输出反馈
     * 一并返回，供 AI 读取结果。</p>
     */
    public static String executeAsHelper(Minecraft mc, String command) {
        IntegratedServer server = serverOf(mc);
        if (server == null) return JsonHelper.error("not a singleplayer world");
        if (command == null || command.trim().isEmpty()) return JsonHelper.error("missing command");
        String result = onServer(server, () -> {
            FakePlayer hp = currentHelper(server);
            if (hp == null) return JsonHelper.error("helper not spawned");
            try {
                // 把命令的 sendSuccess / sendFailure 反馈捕获下来，便于 AI 读取结果文本。
                StringBuilder feedback = new StringBuilder();
                CommandSourceStack base = hp.createCommandSourceStack()
                        .withSource(new CapturingCommandSource(feedback));
                String cmd = command.trim();
                if (cmd.startsWith("/")) cmd = cmd.substring(1);
                CommandDispatcher<CommandSourceStack> dispatcher = server.getCommands().getDispatcher();
                ParseResults<CommandSourceStack> parsed = dispatcher.parse(cmd, base);
                int status;
                try {
                    status = dispatcher.execute(parsed);
                } catch (CommandSyntaxException e) {
                    String msg = e.getMessage() != null ? e.getMessage() : e.toString();
                    return JsonHelper.builder().put("ok", false).put("status", 0)
                            .put("as", HELPER_NAME).put("command", command)
                            .put("error", msg)
                            .put("feedback", feedback.toString().trim()).build();
                } catch (CommandRuntimeException e) {
                    String msg = e.getComponent() != null ? e.getComponent().getString() : e.toString();
                    return JsonHelper.builder().put("ok", false).put("status", 0)
                            .put("as", HELPER_NAME).put("command", command)
                            .put("error", msg)
                            .put("feedback", feedback.toString().trim()).build();
                }
                return JsonHelper.builder().put("ok", true).put("status", status)
                        .put("as", HELPER_NAME).put("command", command)
                        .put("feedback", feedback.toString().trim()).build();
            } catch (Throwable t) {
                return JsonHelper.error("command failed: " + t);
            }
        });
        refreshCache(server);
        return result != null ? result : JsonHelper.error("command failed on server thread");
    }

    /** 一个把 {@code sendSystemMessage} 累计到缓冲区的 {@link CommandSource}，用于捕获命令输出。 */
    private static final class CapturingCommandSource implements CommandSource {
        private final StringBuilder out;

        CapturingCommandSource(StringBuilder out) {
            this.out = out;
        }

        @Override
        public void sendSystemMessage(Component message) {
            if (message == null) return;
            if (out.length() > 0) out.append('\n');
            out.append(message.getString());
        }

        @Override
        public boolean acceptsSuccess() {
            return true;
        }

        @Override
        public boolean acceptsFailure() {
            return true;
        }

        @Override
        public boolean shouldInformAdmins() {
            return false;
        }

        @Override
        public boolean alwaysAccepts() {
            return true;
        }
    }

    /** MCP-HELPER 的详细状态，供 AI 判断自己有没有独立身体。 */
    public static String info(Minecraft mc) {
        IntegratedServer server = serverOf(mc);
        if (server == null) return JsonHelper.error("not a singleplayer world");
        String result = onServer(server, () -> {
            FakePlayer hp = currentHelper(server);
            if (hp == null) {
                return JsonHelper.builder().put("present", false).put("name", HELPER_NAME).build();
            }
            return JsonHelper.builder()
                    .put("present", true)
                    .put("name", HELPER_NAME)
                    .put("uuid", HELPER_UUID.toString())
                    .put("dimension", hp.level().dimension().location().toString())
                    .put("x", hp.getX())
                    .put("y", hp.getY())
                    .put("z", hp.getZ())
                    .put("game_mode", hp.gameMode.getGameModeForPlayer().getName())
                    .put("op", server.getPlayerList().isOp(hp.getGameProfile()))
                    .put("invulnerable", hp.isInvulnerable())
                    .put("independent_body", true)
                    .build();
        });
        return result != null ? result : JsonHelper.error("info failed on server thread");
    }

    /** 移除 MCP-HELPER（例如不再需要独立身体时）。 */
    public static String remove(Minecraft mc) {
        IntegratedServer server = serverOf(mc);
        if (server == null) return JsonHelper.error("not a singleplayer world");
        String result = onServer(server, () -> {
            FakePlayer hp = currentHelper(server);
            if (hp == null) return JsonHelper.error("helper not spawned");
            try {
                ServerLevel lvl = hp.serverLevel();
                if (lvl != null) {
                    try { lvl.removePlayerImmediately(hp, Entity.RemovalReason.KILLED); } catch (Throwable ignored) {}
                }
                try { hp.discard(); } catch (Throwable ignored) {}
                helper = null;
                // 从所有客户端 PlayerInfo / 临时名单里移除 MCP-HELPER。
                try {
                    server.getPlayerList().broadcastAll(
                            new ClientboundPlayerInfoRemovePacket(Collections.singletonList(HELPER_UUID)));
                } catch (Throwable ignored) {}
                return JsonHelper.builder().put("ok", true).put("removed", HELPER_NAME).build();
            } catch (Throwable t) {
                return JsonHelper.error("remove failed: " + t);
            }
        });
        refreshCache(server);
        return result != null ? result : JsonHelper.error("remove failed on server thread");
    }

    // ===== 服务器线程内部实现 =====

    private static String spawnOnServer(MinecraftServer server, UUID ownerUuid) {
        // 当前服务器里已有本身体：直接返回，避免重复生成/重复注册。
        FakePlayer existing = currentHelper(server);
        if (existing != null) {
            return JsonHelper.builder().put("ok", true).put("already_present", true)
                    .put("name", HELPER_NAME).put("dimension", existing.level().dimension().location().toString())
                    .put("x", existing.getX()).put("y", existing.getY()).put("z", existing.getZ())
                    .build();
        }
        ServerPlayer owner = ownerUuid != null ? server.getPlayerList().getPlayer(ownerUuid) : null;
        ServerLevel level = owner != null ? owner.serverLevel() : server.overworld();

        // 用 Forge FakePlayer：自带不碰真实网络通道的连接，避免 placeNewPlayer 的
        // Connection.channel() 为 null 导致的 NullPointerException。
        FakePlayer hp;
        try {
            hp = new FakePlayer(level, profile());
        } catch (Throwable t) {
            return JsonHelper.error("create FakePlayer failed: " + t);
        }

        // 先把身体放到目标位置（真人玩家附近 2~3 格；找不到真人玩家才用世界出生点），
        // 再注册进世界。这样注册时就落在被观察的区块里，客户端能立刻通过实体追踪拿到生成包。
        double[] pos = spawnPosition(level, owner);
        float yaw = owner != null ? owner.getYRot() : 0f;
        float pitch = owner != null ? owner.getXRot() : 0f;
        try {
            hp.moveTo(pos[0], pos[1], pos[2], yaw, pitch);
        } catch (Throwable ignored) {}

        // 先设好游戏模式/权限，再广播 PlayerInfo(ADD_PLAYER)，这样客户端看到的
        // 表格信息里是创造 + OP。位置在这里已设好，configure 不再改动坐标。
        configure(hp, server);

        // 关键：先广播 PlayerInfo(ADD_PLAYER)。否则客户端 handleAddPlayer 会因
        // "没有对应 PlayerInfo" 直接丢弃生成包，FakePlayer 就永远不可见。
        broadcastHelperInfo(server, hp, true);

        // 注册进世界，让其它客户端能看到这具"第二个人"。这一步失败不致命：
        // 身体仍能以独立实体的形式存在，拥有位置/维度/背包/权限，可执行指令并被传送。
        try {
            if (!level.players().contains(hp)) {
                level.addNewPlayer(hp);
            }
        } catch (Throwable t) {
            ReflectionHelper.dbgR("HelperPlayerManager.addNewPlayer: " + t);
        }

        helper = hp;

        return JsonHelper.builder().put("ok", true).put("spawned", HELPER_NAME)
                .put("game_mode", "creative").put("op", true)
                .put("dimension", hp.level().dimension().location().toString())
                .put("x", hp.getX()).put("y", hp.getY()).put("z", hp.getZ())
                .build();
    }

    /**
     * 计算独立身体的首个生成位置：优先放在真人玩家前方 2~3 格的地面上
     * （避免与真人玩家重叠、也避免固定在 (0.5,-59,0.5) 这类出生点），
     * 只有找不到真人玩家时才使用世界出生点 fallback。
     */
    private static double[] spawnPosition(ServerLevel level, ServerPlayer owner) {
        if (owner != null && level != null) {
            double yaw = Math.toRadians(owner.getYRot());
            double fx = -Math.sin(yaw);
            double fz = Math.cos(yaw);
            return new double[]{ owner.getX() + fx * 2.0, owner.getY(), owner.getZ() + fz * 2.0 };
        }
        if (level != null) {
            BlockPos spawn = level.getSharedSpawnPos();
            return new double[]{ spawn.getX() + 0.5, spawn.getY(), spawn.getZ() + 0.5 };
        }
        return new double[]{ 0.5, 0.0, 0.5 };
    }

    /** 给独立身体设置创造 + OP + 无敌（位置已在注册前设好，这里不再改动）。 */
    private static void configure(FakePlayer hp, MinecraftServer server) {
        try { hp.setGameMode(GameType.CREATIVE); } catch (Throwable ignored) {}
        try { hp.setInvulnerable(true); } catch (Throwable ignored) {}
        try { hp.setNoGravity(true); } catch (Throwable ignored) {}
        try { server.getPlayerList().op(profile()); } catch (Throwable ignored) {}
    }

    /** 向所有在线真人玩家广播 MCP-HELPER 的 PlayerInfo（add=true 加入 / false 移除）。 */
    private static void broadcastHelperInfo(MinecraftServer server, ServerPlayer hp, boolean add) {
        try {
            net.minecraft.network.protocol.Packet<?> packet = add
                    ? new ClientboundPlayerInfoUpdatePacket(ClientboundPlayerInfoUpdatePacket.Action.ADD_PLAYER, hp)
                    : new ClientboundPlayerInfoRemovePacket(Collections.singletonList(HELPER_UUID));
            server.getPlayerList().broadcastAll(packet);
        } catch (Throwable ignored) {}
    }

    /**
     * 局域网新玩家加入后补发 MCP-HELPER 的 PlayerInfo，保证他靠近 Helper 时
     * 也能正常同步到实体（否则仍会因缺 PlayerInfo 看不见）。
     */
    public static void onPlayerLoggedIn(ServerPlayer joined) {
        MinecraftServer server = joined == null ? null : joined.getServer();
        if (server == null) return;
        try {
            if (server.isSameThread()) {
                FakePlayer hp = currentHelper(server);
                if (hp != null) {
                    joined.connection.send(new ClientboundPlayerInfoUpdatePacket(
                            ClientboundPlayerInfoUpdatePacket.Action.ADD_PLAYER, hp));
                }
            }
        } catch (Throwable ignored) {}
    }

    private static String teleportOnServer(MinecraftServer server, ServerPlayer helper, UUID ownerUuid) {
        ServerPlayer owner = server.getPlayerList().getPlayer(ownerUuid);
        if (owner == null) return JsonHelper.error("player not found on server");
        ServerLevel level = owner.serverLevel();
        try {
            double yaw = Math.toRadians(owner.getYRot());
            double fx = -Math.sin(yaw);
            double fz = Math.cos(yaw);
            double tx = owner.getX() + fx * 2.0;
            double ty = owner.getY();
            double tz = owner.getZ() + fz * 2.0;
            // 用 moveTo 直接移动实体：FakePlayerNetHandler.teleport 是空实现，
            // ServerPlayer.teleportTo 同维度下只调用 connection.teleport(...)，不会更新坐标。
            placeServerPlayer(helper, level, tx, ty, tz, owner.getYRot(), owner.getXRot());
            // 关键：从同一个 Helper 实体重新读取实际坐标，而不是返回 owner 坐标。
            // 否则会出现"返回目标坐标但 Helper 没动"的假成功。
            double ax = helper.getX();
            double ay = helper.getY();
            double az = helper.getZ();
            double dx = ax - owner.getX();
            double dy = ay - owner.getY();
            double dz = az - owner.getZ();
            double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
            boolean reached = dist <= 3.0;
            return JsonHelper.builder()
                    .put("ok", reached)
                    .put("teleported", HELPER_NAME)
                    .put("to", owner.getGameProfile().getName())
                    .put("dimension", level.dimension().location().toString())
                    .put("x", ax).put("y", ay).put("z", az)
                    .put("distance", dist)
                    .put("reached_player", reached)
                    .build();
        } catch (Throwable t) {
            return JsonHelper.error("teleport failed: " + t);
        }
    }

    /**
     * 把独立身体放到目标维度与坐标。同维度直接 {@code moveTo} 设置坐标即可；
     * 跨维度时先切换 ServerLevel 再注册进目标世界（不调用 removePlayerImmediately，
     * 以免把本类持有的 helper 引用标记为已移除，导致后续 currentHelper 拿不到它）。
     */
    private static void placeServerPlayer(ServerPlayer helper, ServerLevel target,
            double x, double y, double z, float yaw, float pitch) throws Throwable {
        ServerLevel before = helper.serverLevel();
        helper.moveTo(x, y, z, yaw, pitch);
        if (before != null && before != target) {
            try { helper.setServerLevel(target); } catch (Throwable ignored) {}
            try {
                if (!target.players().contains(helper)) target.addNewPlayer(helper);
            } catch (Throwable ignored) {}
        }
    }

    private static void refreshCache(MinecraftServer server) {
        try {
            FakePlayer hp = currentHelper(server);
            if (hp == null) {
                cachedPresent = false;
                cachedSummary = "not spawned";
                return;
            }
            cachedPresent = true;
            cachedSummary = hp.getGameProfile().getName()
                    + "  ·  " + hp.level().dimension().location()
                    + "  ·  " + fmt(hp.getX()) + " " + fmt(hp.getY()) + " " + fmt(hp.getZ())
                    + "  ·  " + hp.gameMode.getGameModeForPlayer().getName()
                    + (server.getPlayerList().isOp(hp.getGameProfile()) ? "  ·  op" : "");
        } catch (Throwable ignored) {}
    }

    private static String posJson(ServerPlayer helper) {
        return JsonHelper.builder().put("ok", true).put("name", HELPER_NAME)
                .put("dimension", helper.level().dimension().location().toString())
                .put("x", helper.getX()).put("y", helper.getY()).put("z", helper.getZ())
                .build();
    }

    /**
     * 返回当前服务器里仍然有效的独立身体；若引用已过期（身体被移除、或属于已停机的旧世界、
     * 或来自别的服务器）则清空引用并返回 null。
     */
    private static FakePlayer currentHelper(MinecraftServer server) {
        FakePlayer hp = helper;
        if (hp == null) return null;
        try {
            if (hp.isRemoved()) {
                helper = null;
                return null;
            }
            ServerLevel lvl = hp.serverLevel();
            if (lvl == null || lvl.getServer() != server) {
                helper = null;
                return null;
            }
            return hp;
        } catch (Throwable t) {
            helper = null;
            return null;
        }
    }

    private static IntegratedServer serverOf(Minecraft mc) {
        return mc != null ? mc.getSingleplayerServer() : null;
    }

    private static double parse(String value, double fallback) {
        if (value == null) return fallback;
        try { return Double.parseDouble(value.trim()); } catch (NumberFormatException e) { return fallback; }
    }

    private static String fmt(double v) {
        return String.format(Locale.ROOT, "%.1f", v);
    }

    /** 所有服务端改动都排队到服务器线程，避免跨线程改玩家列表 / 实体。 */
    private static <T> T onServer(MinecraftServer server, java.util.concurrent.Callable<T> task) {
        if (server == null) return null;
        try {
            if (server.isSameThread()) return task.call();
            CompletableFuture<T> future = server.submit(() -> {
                try { return task.call(); } catch (Throwable t) { return null; }
            });
            return future.get(5, TimeUnit.SECONDS);
        } catch (Throwable t) {
            ReflectionHelper.dbgR("HelperPlayerManager.onServer: " + t);
            return null;
        }
    }
}
