package xyz.langyo.minecraft.mcp.mod;

import xyz.langyo.minecraft.mcp.common.*;
import xyz.langyo.minecraft.mcp.common.selection.*;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.loading.FMLEnvironment;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;
import org.lwjgl.glfw.GLFW;

@Mod("mcpmod")
public class ModDevMcpMod {
    private static final Logger LOGGER = LogUtils.getLogger();
    public static ModDevMcpMod INSTANCE;
    private McpHttpServer httpServer;
    private boolean resumeSoundOnNextTick;
    private boolean prewarmed;

    /**
     * 打开 MCP 连接状态界面的键位（默认 F9，可在 选项 → 控制 里改绑）。
     * 右上角的悬浮按钮已移除，改由这个键位打开界面。
     */
    private KeyMapping statusKey;

    /**
     * 切换结构选区预览模式的键位（默认 V，可在 选项 → 控制 里改绑）：
     * Wireframe ↔ Filled Bounds。只在手持结构选择器且身处世界时生效，避免抢按键。
     */
    private KeyMapping previewKey;

    public ModDevMcpMod() {
        INSTANCE = this;
        LOGGER.info("[MCP-HELPER] ModDevMcpMod constructor reached");
        IEventBus modBus = net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext.get().getModEventBus();
        modBus.addListener(this::setup);
        modBus.addListener(this::onRegisterKeyMappings);
        // 结构选择器：物品注册、创造栏都挂 mod event bus；服务端交互事件挂 Forge bus。
        McpItems.ITEMS.register(modBus);
        modBus.addListener(McpItems::onBuildCreativeTab);
        // 选区同步通道：只发 服务端→客户端 的轻量包。在构造器里注册，早于网络注册表加锁。
        SelectionNetwork.register();
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.register(new SelectionEvents());
        // 客户端专属：世界线框 + HUD Overlay + 退出世界时清缓存。
        if (FMLEnvironment.dist.isClient()) {
            PhotonDiagnostics.install();
            modBus.addListener(SelectionHudOverlay::registerOverlays);
            net.minecraftforge.common.MinecraftForge.EVENT_BUS.register(new SelectionRenderer());
            net.minecraftforge.common.MinecraftForge.EVENT_BUS.register(new PhotonEditorBridge());
            net.minecraftforge.common.MinecraftForge.EVENT_BUS.register(new PhotonCaptureBridge());
        }
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.register(this);
    }

    private void setup(final FMLCommonSetupEvent event) {
        LOGGER.info("[MCP-HELPER] FMLCommonSetupEvent received; preparing HTTP server");
        exposeModIdentity();
        // 把 Forge 的 config 目录交给 McpConfig，端口配置就落在 config/mcpmod.properties。
        try {
            McpConfig.setConfigDirectory(net.minecraftforge.fml.loading.FMLPaths.CONFIGDIR.get());
            McpConfig.ensureConfigFile();
        } catch (Throwable e) {
            LOGGER.error("[MCP-HELPER] Failed to initialize MCP config", e);
        }
        // 预览模式是客户端设置，从 config/mcpmod.properties 读回上次的选择。
        if (FMLEnvironment.dist.isClient()) {
            try {
                SelectionPreview.load();
            } catch (Throwable ignored) {}
        }
        ReflectedInputHandler handler = new ReflectedInputHandler(ReflectedInputHandler::executeOnRenderThread);
        // 0 表示用户没配置端口：McpHttpServer 会从默认 9876 起向下找空闲端口。
        int port = McpConfig.getConfiguredPort();
        LOGGER.info("[MCP-HELPER] Configured HTTP port: {}", port);
        httpServer = new McpHttpServer(handler, port);
        // 立即起服务：原先固定 sleep 5 秒才 bind 端口，是"连上很慢"最直接的原因。
        Thread serverThread = new Thread(() -> {
            try {
                httpServer.start();
                LOGGER.info("[MCP-HELPER] HTTP server listening on {}", httpServer.getPort());
            } catch (Throwable e) {
                LOGGER.error("[MCP-HELPER] HTTP server startup failed", e);
            }
        }, "MCP-HTTP");
        serverThread.setDaemon(true);
        serverThread.start();
        LOGGER.info("[MCP-HELPER] HTTP startup thread launched");
    }

    /** 把版本/加载器写进系统属性，让 /api/status 与桥接端能立刻识别本模组。 */
    private static void exposeModIdentity() {
        try {
            ModList.get().getModContainerById("mcpmod").ifPresent(container -> {
                System.setProperty("mcp.mod.version", container.getModInfo().getVersion().toString());
                System.setProperty("mcp.mod.loader", "forge");
            });
        } catch (Throwable ignored) {}
    }

    private void onRegisterKeyMappings(RegisterKeyMappingsEvent event) {
        statusKey = new KeyMapping("key.mcpmod.status", InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_F9, "key.categories.mcpmod");
        event.register(statusKey);
        previewKey = new KeyMapping("key.mcpmod.preview", InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_V, "key.categories.mcpmod");
        event.register(previewKey);
    }

    private void openStatusScreen() {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) return;
        if (mc.screen instanceof McpStatusScreen) {
            mc.screen.onClose();
        } else {
            // F9 打开状态界面的同时，把独立身体 MCP-HELPER 传送到玩家所在维度、所在位置。
            try { HelperPlayerManager.ensureAndTeleportToPlayer(mc); } catch (Throwable ignored) {}
            mc.setScreen(new McpStatusScreen());
        }
    }

    /** F9 打开/关闭状态界面；F8 在"MCP 控制模式"和"手动控制"之间切换。 */
    @net.minecraftforge.eventbus.api.SubscribeEvent
    public void onKeyInput(InputEvent.Key event) {
        try {
            if (event.getAction() != GLFW.GLFW_PRESS) return;
            Minecraft mc = Minecraft.getInstance();
            if (mc == null) return;
            if (statusKey != null && statusKey.matches(event.getKey(), event.getScanCode())) {
                openStatusScreen();
                return;
            }
            // V：切换结构选区预览模式（只在没有界面、手持结构选择器时生效），切换后动作栏提示。
            if (previewKey != null && previewKey.matches(event.getKey(), event.getScanCode())) {
                if (mc.screen != null) return;
                if (mc.level == null || mc.player == null) return;
                if (!SelectionRenderer.isHolding(mc.player)) return;
                SelectionPreview.Mode mode = SelectionPreview.toggle();
                mc.player.displayClientMessage(
                        Component.translatable("mcpmod.selection.preview.set", mode.text()), true);
                return;
            }
            if (event.getKey() != GLFW.GLFW_KEY_F8) return;
            if (mc.level == null) return;
            if (ControlModeHelper.isMcpControlMode()) {
                ControlModeHelper.exitMcpControlMode(mc);
            } else {
                ControlModeHelper.enterMcpControlMode(mc);
            }
        } catch (Throwable ignored) {}
    }

    @net.minecraftforge.eventbus.api.SubscribeEvent
    public void onClientTick(net.minecraftforge.event.TickEvent.ClientTickEvent event) {
        if (event.phase != net.minecraftforge.event.TickEvent.Phase.END) return;
        tickControlMode();
        PhotonAdvancedBridge.tick();
        try { HelperPlayerManager.tick(Minecraft.getInstance()); } catch (Throwable ignored) {}
        if (!prewarmed) {
            prewarmed = true;
            prewarmReflection();
        }
        if (resumeSoundOnNextTick) {
            resumeSoundOnNextTick = false;
            Minecraft mc = Minecraft.getInstance();
            if (mc.screen instanceof net.minecraft.client.gui.screens.PauseScreen
                    && PauseGuardHelper.shouldBypassPause(mc)) {
                mc.getSoundManager().resume();
            }
        }
    }

    /**
     * 服务器 tick 驱动分批建造：build_structure 提交的任务每个 tick 只放 batch 个方块，
     * 避免数千方块一次性落地造成的卡顿。
     */
    @net.minecraftforge.eventbus.api.SubscribeEvent
    public void onServerTick(net.minecraftforge.event.TickEvent.ServerTickEvent event) {
        if (event.phase != net.minecraftforge.event.TickEvent.Phase.END) return;
        try {
            xyz.langyo.minecraft.mcp.common.WorldEditHelper.tick(event.getServer());
        } catch (Throwable ignored) {}
    }

    /**
     * 局域网世界里新玩家加入后，给它补发 MCP-HELPER 的 PlayerInfo；
     * 否则该客户端靠近 Helper 时，会因缺少 PlayerInfo 而看不到实体。
     */
    @net.minecraftforge.eventbus.api.SubscribeEvent
    public void onPlayerLoggedIn(net.minecraftforge.event.entity.player.PlayerEvent.PlayerLoggedInEvent event) {
        try {
            if (event.getEntity() instanceof net.minecraft.server.level.ServerPlayer sp) {
                xyz.langyo.minecraft.mcp.common.HelperPlayerManager.onPlayerLoggedIn(sp);
            }
        } catch (Throwable ignored) {}
    }

    private void tickControlMode() {
        try {
            Minecraft mc = Minecraft.getInstance();
            xyz.langyo.minecraft.mcp.common.ReflectionHelper.tickMouseRelease(mc);
            xyz.langyo.minecraft.mcp.common.ReflectionHelper.tickMcpControlMode(mc);
        } catch (Exception ignored) {}
    }

    /** 首次 tick 预热反射缓存，避免第一条命令承担类扫描开销。 */
    private void prewarmReflection() {
        try {
            ReflectionHelper.getMinecraftInstance();
        } catch (Throwable ignored) {}
    }

    @net.minecraftforge.eventbus.api.SubscribeEvent
    public void onScreenInit(net.minecraftforge.client.event.ScreenEvent.Init.Post event) {
        if (xyz.langyo.minecraft.mcp.common.ReflectionHelper.isMcpControlMode()) return;
        net.minecraft.client.gui.screens.Screen s = event.getScreen();
        if (!(s instanceof net.minecraft.client.gui.screens.PauseScreen)) return;
        try {
            net.minecraft.client.gui.components.Button button = net.minecraft.client.gui.components.Button.builder(
                    Component.translatable("mcpmod.control.pause_button"),
                    btn -> xyz.langyo.minecraft.mcp.common.ControlModeHelper.enterMcpControlMode(
                            Minecraft.getInstance()))
                    .bounds(s.width - 104, 44, 100, 20).build();
            event.addListener(button);
        } catch (Exception ignored) {}
    }

    /**
     * 单机 ESC 不冻结世界：把原版 PauseScreen 换成 isPauseScreen()=false 的等价菜单。
     * 原理见 PauseGuardHelper。已开局域网的世界本来就不会暂停，这里会自动跳过。
     */
    @net.minecraftforge.eventbus.api.SubscribeEvent
    public void onScreenOpening(net.minecraftforge.client.event.ScreenEvent.Opening event) {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc == null) return;
            net.minecraft.client.gui.screens.Screen newScreen = event.getNewScreen();
            if (newScreen == null) return;
            if (!xyz.langyo.minecraft.mcp.common.PauseGuardHelper.shouldBypassPause(mc)) return;
            net.minecraft.client.gui.screens.Screen wrapped = xyz.langyo.minecraft.mcp.common.PauseGuardHelper.wrap(newScreen);
            if (wrapped != null && wrapped != newScreen) {
                event.setNewScreen(wrapped);
                // pauseGame() pauses sound after setScreen returns.
                resumeSoundOnNextTick = true;
            }
        } catch (Throwable ignored) {}
    }
}
