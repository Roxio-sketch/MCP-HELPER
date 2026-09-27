package xyz.langyo.minecraft.mcp.common;

import com.mojang.authlib.GameProfile;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;
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
 *       通过 {@code ServerLevel#addNewPlayer} 注册进世界后，其它客户端能看到这个
 *       "第二个人"。它不会进入 {@link PlayerList}，因此不影响真人玩家连接与数量。</li>
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
            ServerLevel level = hp.serverLevel();
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
            hp.teleportTo(level, x, y, z, hp.getYRot(), hp.getXRot());
            return posJson(hp);
        });
        refreshCache(server);
        return result != null ? result : JsonHelper.error("move failed on server thread");
    }

    /** 以 MCP-HELPER 的身份（OP / 作弊权限）执行一条命令。 */
    public static String executeAsHelper(Minecraft mc, String command) {
        IntegratedServer server = serverOf(mc);
        if (server == null) return JsonHelper.error("not a singleplayer world");
        if (command == null || command.trim().isEmpty()) return JsonHelper.error("missing command");
        String result = onServer(server, () -> {
            FakePlayer hp = currentHelper(server);
            if (hp == null) return JsonHelper.error("helper not spawned");
            try {
                int status = server.getCommands().performPrefixedCommand(hp.createCommandSourceStack(), command);
                return JsonHelper.builder().put("ok", status == 0).put("status", status)
                        .put("as", HELPER_NAME).put("command", command).build();
            } catch (Throwable t) {
                return JsonHelper.error("command failed: " + t);
            }
        });
        refreshCache(server);
        return result != null ? result : JsonHelper.error("command failed on server thread");
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

        // 注册进世界，让其它客户端能看到这具"第二个人"。这一步失败不致命：
        // 身体仍能以独立实体的形式存在，拥有位置/维度/背包/权限，可执行指令并被传送。
        try {
            if (!level.players().contains(hp)) {
                level.addNewPlayer(hp);
            }
        } catch (Throwable t) {
            ReflectionHelper.dbgR("HelperPlayerManager.addNewPlayer: " + t);
        }

        configure(hp, server, level, owner);
        helper = hp;

        return JsonHelper.builder().put("ok", true).put("spawned", HELPER_NAME)
                .put("game_mode", "creative").put("op", true)
                .put("dimension", hp.level().dimension().location().toString())
                .put("x", hp.getX()).put("y", hp.getY()).put("z", hp.getZ())
                .build();
    }

    /** 给独立身体设置创造 + OP + 无敌，并传送到玩家位置。 */
    private static void configure(FakePlayer hp, MinecraftServer server, ServerLevel level, ServerPlayer owner) {
        try { hp.setGameMode(GameType.CREATIVE); } catch (Throwable ignored) {}
        try { hp.setInvulnerable(true); } catch (Throwable ignored) {}
        try { hp.setNoGravity(true); } catch (Throwable ignored) {}
        try { server.getPlayerList().op(profile()); } catch (Throwable ignored) {}
        if (owner != null) {
            try {
                hp.teleportTo(level, owner.getX(), owner.getY(), owner.getZ(), owner.getYRot(), owner.getXRot());
            } catch (Throwable ignored) {}
        }
    }

    private static String teleportOnServer(MinecraftServer server, ServerPlayer helper, UUID ownerUuid) {
        ServerPlayer owner = server.getPlayerList().getPlayer(ownerUuid);
        if (owner == null) return JsonHelper.error("player not found on server");
        ServerLevel level = owner.serverLevel();
        try {
            helper.teleportTo(level, owner.getX(), owner.getY(), owner.getZ(), owner.getYRot(), owner.getXRot());
        } catch (Throwable t) {
            return JsonHelper.error("teleport failed: " + t);
        }
        return JsonHelper.builder().put("ok", true).put("teleported", HELPER_NAME)
                .put("to", owner.getGameProfile().getName())
                .put("dimension", level.dimension().location().toString())
                .put("x", owner.getX()).put("y", owner.getY()).put("z", owner.getZ())
                .build();
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
