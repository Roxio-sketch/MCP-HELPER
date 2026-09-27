package xyz.langyo.minecraft.mcp.mod;

import xyz.langyo.minecraft.mcp.common.*;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import org.lwjgl.glfw.GLFW;

@Mod("mcpmod")
public class ModDevMcpMod {
    public static ModDevMcpMod INSTANCE;
    private McpHttpServer httpServer;
    private boolean resumeSoundOnNextTick;
    private boolean prewarmed;

    /**
     * 打开 MCP 连接状态界面的键位（默认 F9，可在 选项 → 控制 里改绑）。
     * 右上角的悬浮按钮已移除，改由这个键位打开界面。
     */
    private KeyMapping statusKey;

    public ModDevMcpMod() {
        INSTANCE = this;
        net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext.get().getModEventBus().addListener(this::setup);
        net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext.get().getModEventBus().addListener(this::onRegisterKeyMappings);
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.register(this);
    }

    private void setup(final FMLCommonSetupEvent event) {
        exposeModIdentity();
        // 把 Forge 的 config 目录交给 McpConfig，端口配置就落在 config/mcpmod.properties。
        try {
            McpConfig.setConfigDirectory(net.minecraftforge.fml.loading.FMLPaths.CONFIGDIR.get());
            McpConfig.ensureConfigFile();
        } catch (Throwable ignored) {}
        ReflectedInputHandler handler = new ReflectedInputHandler(ReflectedInputHandler::executeOnRenderThread);
        // 0 表示用户没配置端口：McpHttpServer 会从默认 9876 起向下找空闲端口。
        int port = McpConfig.getConfiguredPort();
        httpServer = new McpHttpServer(handler, port);
        // 立即起服务：原先固定 sleep 5 秒才 bind 端口，是"连上很慢"最直接的原因。
        Thread serverThread = new Thread(() -> {
            try {
                httpServer.start();
            } catch (Exception e) {
                System.err.println("[MCP-MOD] HTTP server failed: " + e.getMessage());
            }
        }, "MCP-HTTP");
        serverThread.setDaemon(true);
        serverThread.start();
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
