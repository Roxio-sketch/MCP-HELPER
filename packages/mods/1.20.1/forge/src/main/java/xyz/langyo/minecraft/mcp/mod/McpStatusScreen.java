package xyz.langyo.minecraft.mcp.mod;

import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.Util;
import xyz.langyo.minecraft.mcp.common.ControlModeHelper;
import xyz.langyo.minecraft.mcp.common.HelperPlayerManager;
import xyz.langyo.minecraft.mcp.common.McBridge;
import xyz.langyo.minecraft.mcp.common.McpConfig;
import xyz.langyo.minecraft.mcp.common.McpHttpServer;
import xyz.langyo.minecraft.mcp.common.ReflectionHelper;

/**
 * MCP 连接状态界面（默认按 F9 打开）。
 *
 * <p>替代原来右上角的悬浮按钮：这里一次性展示本地 HTTP 服务、SSE 客户端、
 * 控制模式与局域网（LAN）连接状况，并提供开局域网、切换控制模式、打开调试页的按钮。
 * {@link #isPauseScreen()} 返回 false，所以打开界面不会冻结单机世界。</p>
 */
public class McpStatusScreen extends Screen {

    private static final int ROW_H = 13;
    private static final int LABEL_W = 132;
    private static final int TEXT = 0xFFFFFF;
    private static final int LABEL = 0xA0A0A0;
    private static final int OK = 0x55FF55;
    private static final int WARN = 0xFFAA00;
    private static final int BAD = 0xFF5555;

    /** 端口输入框；init() 时创建，读值用。 */
    private EditBox portBox;
    /** 「应用并重启」的结果提示（空字符串表示不显示）。 */
    private String feedback = "";
    private int feedbackColor = LABEL;

    public McpStatusScreen() {
        super(Component.translatableWithFallback("mcpmod.status.title", "MCP Connection Status"));
    }

    /** 状态行文字的左边界，render() 与 init() 共用，保证按钮和文字对齐。 */
    private int contentX() {
        return Math.max(12, this.width / 2 - 190);
    }

    @Override
    protected void init() {
        int gap = 4;
        int count = 4;
        int buttonW = Math.min(150, (this.width - 20 - gap * (count - 1)) / count);
        int buttonH = 20;
        int totalW = buttonW * count + gap * (count - 1);
        int x = (this.width - totalW) / 2;
        int y = this.height - 28;

        Minecraft mc = this.minecraft;
        boolean singleplayer = mc != null && McBridge.hasSingleplayerServer(mc);
        boolean published = singleplayer && McBridge.isLanPublished(mc);
        boolean control = ControlModeHelper.isMcpControlMode();

        // ===== 端口配置行：输入框 + 应用按钮（位于底部按钮行上方） =====
        int cx = contentX();
        int portRowY = y - 26;
        this.portBox = new EditBox(this.font, cx + 70, portRowY, 64, 18,
                Component.translatableWithFallback("mcpmod.status.port_label", "Port"));
        this.portBox.setMaxLength(5);
        this.portBox.setFilter(s -> s.matches("\\d{0,5}"));
        this.portBox.setValue(Integer.toString(McpConfig.getServerPort()));
        this.addRenderableWidget(this.portBox);

        this.addRenderableWidget(Button.builder(
                Component.translatableWithFallback("mcpmod.status.port_apply", "Apply & restart"),
                b -> applyPort())
                .bounds(cx + 70 + 64 + gap, portRowY, 122, 18).build());

        this.addRenderableWidget(Button.builder(
                Component.translatableWithFallback("mcpmod.status.port_reset", "Use default"),
                b -> applyPort(McpConfig.DEFAULT_PORT))
                .bounds(cx + 70 + 64 + gap + 122 + gap, portRowY, 100, 18).build());

        Button lanButton = Button.builder(
                published
                        ? Component.translatableWithFallback("mcpmod.status.btn.lan_done", "LAN already open")
                        : Component.translatableWithFallback("mcpmod.status.btn.lan", "Open to LAN"),
                b -> openLan())
                .bounds(x, y, buttonW, buttonH).build();
        lanButton.active = singleplayer && !published;
        this.addRenderableWidget(lanButton);

        this.addRenderableWidget(Button.builder(
                control
                        ? Component.translatableWithFallback("mcpmod.status.btn.control_exit", "Exit MCP control")
                        : Component.translatableWithFallback("mcpmod.status.btn.control", "Enter MCP control"),
                b -> toggleControl())
                .bounds(x + buttonW + gap, y, buttonW, buttonH).build());

        Button helperButton = Button.builder(
                Component.translatableWithFallback("mcpmod.status.btn.helper", "Teleport MCP-HELPER"),
                b -> teleportHelper())
                .bounds(x + (buttonW + gap) * 2, y, buttonW, buttonH).build();
        helperButton.active = published;
        this.addRenderableWidget(helperButton);

        Button debugButton = Button.builder(
                Component.translatableWithFallback("mcpmod.status.btn.debug", "Open debug page"),
                b -> openDebugPage())
                .bounds(x + (buttonW + gap) * 3, y, buttonW, buttonH).build();
        debugButton.active = McpHttpServer.getActive() != null;
        this.addRenderableWidget(debugButton);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(graphics);
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.drawCenteredString(this.font, this.title, this.width / 2, 14, TEXT);

        int x = contentX();
        int y = 42;
        McpHttpServer server = McpHttpServer.getActive();
        Minecraft mc = this.minecraft;

        // 端口输入框左侧的标签（输入框本身由 super.render 绘制）。
        if (this.portBox != null) {
            graphics.drawString(this.font, tr("mcpmod.status.port_label", "Port"),
                    x, this.portBox.getY() + 5, LABEL, false);
        }

        if (server == null || !server.isRunning()) {
            row(graphics, x, y, "mcpmod.status.server", "HTTP server",
                    tr("mcpmod.status.stopped", "not started"), BAD);
            y += ROW_H;
            row(graphics, x, y, "mcpmod.status.local_url", "Local URL",
                    "http://127.0.0.1:" + McpConfig.getServerPort(), WARN);
            y += ROW_H * 2;
        } else {
            row(graphics, x, y, "mcpmod.status.server", "HTTP server",
                    tr("mcpmod.status.running", "running") + "  ·  "
                            + tr("mcpmod.status.port", "port") + " " + server.getPort()
                            + "  ·  " + tr("mcpmod.status.listening", "listening") + " " + server.getListenAddress(),
                    OK);
            y += ROW_H;
            row(graphics, x, y, "mcpmod.status.local_url", "Local URL",
                    server.getHttpUrl() + "   (/debug)", TEXT);
            y += ROW_H;
            y = renderLanAddresses(graphics, x, y, server);
            y += ROW_H / 2;
            row(graphics, x, y, "mcpmod.status.clients", "SSE clients",
                    server.getSseClientCount() + " / 4   ·   "
                            + tr("mcpmod.status.requests", "requests") + " " + server.getRequestCount()
                            + "   ·   " + tr("mcpmod.status.idle", "idle") + " " + idleText(server),
                    server.getSseClientCount() > 0 ? OK : LABEL);
            y += ROW_H;
        }

        y += ROW_H / 2;
        row(graphics, x, y, "mcpmod.status.control", "Control mode",
                (ControlModeHelper.isMcpControlMode()
                        ? tr("mcpmod.status.on", "on")
                        : tr("mcpmod.status.off", "off"))
                        + "   ·   " + tr("mcpmod.status.mouse", "mouse") + " "
                        + (ControlModeHelper.isMouseDetached() ? "detached" : "shared")
                        + "   ·   " + tr("mcpmod.status.look", "player can look") + " "
                        + (ControlModeHelper.isMouseDetached() ? tr("mcpmod.status.off", "off") : tr("mcpmod.status.on", "on")),
                ControlModeHelper.isMcpControlMode() ? OK : LABEL);
        y += ROW_H;
        row(graphics, x, y, "mcpmod.status.no_pause", "ESC no-pause",
                (ControlModeHelper.isNoPause() ? tr("mcpmod.status.on", "on") : tr("mcpmod.status.off", "off"))
                        + "   ·   " + tr("mcpmod.status.game_paused", "world paused") + " "
                        + (mc != null && McBridge.isGamePaused(mc) ? tr("mcpmod.status.on", "on") : tr("mcpmod.status.off", "off")),
                TEXT);
        y += ROW_H;

        String lanValue;
        int lanColor;
        if (mc == null || !McBridge.hasSingleplayerServer(mc)) {
            lanValue = tr("mcpmod.status.n_a", "n/a") + " (not a singleplayer world)";
            lanColor = LABEL;
        } else if (McBridge.isLanPublished(mc)) {
            lanValue = tr("mcpmod.status.lan_on", "published") + "   ·   "
                    + tr("mcpmod.status.port", "port") + " " + McBridge.getLanPort(mc);
            lanColor = OK;
        } else {
            lanValue = tr("mcpmod.status.lan_off", "not published");
            lanColor = WARN;
        }
        row(graphics, x, y, "mcpmod.status.lan_world", "LAN world", lanValue, lanColor);
        y += ROW_H;

        String helperValue;
        int helperColor;
        if (mc == null || !McBridge.hasSingleplayerServer(mc)) {
            helperValue = tr("mcpmod.status.n_a", "n/a") + " (not a singleplayer world)";
            helperColor = LABEL;
        } else if (!McBridge.isLanPublished(mc)) {
            helperValue = tr("mcpmod.status.helper_lan_off", "spawns with the LAN world");
            helperColor = WARN;
        } else if (HelperPlayerManager.isPresent()) {
            helperValue = HelperPlayerManager.summary();
            helperColor = OK;
        } else {
            helperValue = tr("mcpmod.status.helper_missing", "not spawned yet");
            helperColor = WARN;
        }
        row(graphics, x, y, "mcpmod.status.helper", "MCP-HELPER", helperValue, helperColor);
        y += ROW_H * 2;

        row(graphics, x, y, "mcpmod.status.port_config", "Port config",
                McpConfig.getServerPort() + "   ·   " + portSourceText()
                        + "   ·   " + tr("mcpmod.status.port_file", "config file") + " "
                        + McpConfig.getConfigFile().getFileName(),
                TEXT);
        y += ROW_H;
        if (feedback != null && !feedback.isEmpty()) {
            graphics.drawString(this.font, feedback, x, y, feedbackColor, false);
            y += ROW_H;
        }

        graphics.drawString(this.font,
                tr("mcpmod.status.hint", "F9 opens this screen and teleports MCP-HELPER · F8 toggles MCP control"),
                x, y, LABEL, false);
    }

    /** 把端口来源翻译成界面文字。 */
    private String portSourceText() {
        String source = McpConfig.getPortSource();
        if ("jvm".equals(source)) return tr("mcpmod.status.port_src.jvm", "JVM arg -Dmcp.port");
        if ("env".equals(source)) return tr("mcpmod.status.port_src.env", "env MC_MCP_PORT");
        if ("file".equals(source)) return tr("mcpmod.status.port_src.file", "config file");
        return tr("mcpmod.status.port_src.default", "default");
    }

    private int renderLanAddresses(GuiGraphics graphics, int x, int y, McpHttpServer server) {
        List<String> addresses = McpHttpServer.getLanAddresses();
        String label = tr("mcpmod.status.lan_urls", "LAN URLs");
        if (addresses.isEmpty()) {
            graphics.drawString(this.font, label, x, y, LABEL, false);
            graphics.drawString(this.font, tr("mcpmod.status.no_lan", "no LAN IPv4 address found"),
                    x + LABEL_W, y, WARN, false);
            return y + ROW_H;
        }
        boolean first = true;
        for (String address : addresses) {
            if (first) {
                graphics.drawString(this.font, label, x, y, LABEL, false);
                first = false;
            }
            graphics.drawString(this.font, "http://" + address + ":" + server.getPort(), x + LABEL_W, y, TEXT, false);
            y += ROW_H;
        }
        return y;
    }

    private String idleText(McpHttpServer server) {
        long idle = server.getIdleMillis();
        if (idle < 0) return "—";
        if (idle < 1000) return idle + " ms";
        return String.format(java.util.Locale.ROOT, "%.1f s", idle / 1000.0);
    }

    private void row(GuiGraphics graphics, int x, int y, String key, String fallback, String value, int color) {
        graphics.drawString(this.font, Component.translatableWithFallback(key, fallback), x, y, LABEL, false);
        graphics.drawString(this.font, value, x + LABEL_W, y, color, false);
    }

    private static String tr(String key, String fallback) {
        return Component.translatableWithFallback(key, fallback).getString();
    }

    private void openLan() {
        Minecraft mc = this.minecraft;
        if (mc == null) return;
        try {
            ReflectionHelper.openToLan(mc, 25565, true);
        } catch (Throwable ignored) {}
        this.rebuildWidgets();
    }

    private void toggleControl() {
        Minecraft mc = this.minecraft;
        if (mc == null) return;
        try {
            if (ControlModeHelper.isMcpControlMode()) {
                ControlModeHelper.exitMcpControlMode(mc);
            } else {
                ControlModeHelper.enterMcpControlMode(mc);
            }
        } catch (Throwable ignored) {}
        this.rebuildWidgets();
    }

    private void openDebugPage() {
        McpHttpServer server = McpHttpServer.getActive();
        if (server == null) return;
        try {
            Util.getPlatform().openUri(server.getHttpUrl() + "/debug");
        } catch (Throwable ignored) {}
    }

    /** 把独立身体 MCP-HELPER 传送到玩家所在维度、所在位置。 */
    private void teleportHelper() {
        Minecraft mc = this.minecraft;
        if (mc == null) return;
        try {
            if (!HelperPlayerManager.isPresent()) {
                HelperPlayerManager.spawn(mc);
            }
            HelperPlayerManager.teleportToPlayer(mc);
        } catch (Throwable ignored) {}
        this.rebuildWidgets();
    }

    /** 读取输入框里的端口并应用。 */
    private void applyPort() {
        if (this.portBox == null) return;
        int port;
        try {
            port = Integer.parseInt(this.portBox.getValue().trim());
        } catch (NumberFormatException e) {
            feedback = tr("mcpmod.status.port_invalid", "Invalid port (1-65535)");
            feedbackColor = BAD;
            return;
        }
        applyPort(port);
    }

    /**
     * 写入配置文件并热重启 HTTP 服务；失败时把原因显示在状态行下方。
     * 端口改完立即生效，不需要重启游戏。
     */
    private void applyPort(int port) {
        if (!McpConfig.isValidPort(port)) {
            feedback = tr("mcpmod.status.port_invalid", "Invalid port (1-65535)");
            feedbackColor = BAD;
            return;
        }
        boolean saved = McpConfig.setConfiguredPort(port);
        try {
            McpHttpServer.restartOnPort(port);
            feedback = tr("mcpmod.status.port_applied", "Applied, port") + " " + port;
            if (!saved) {
                feedback += " " + tr("mcpmod.status.port_save_failed", "(config file not saved)");
            }
            feedbackColor = OK;
        } catch (Exception e) {
            feedback = tr("mcpmod.status.port_failed", "Failed to bind port") + " " + port
                    + ": " + (e.getMessage() == null ? "?" : e.getMessage());
            feedbackColor = BAD;
        }
        this.rebuildWidgets();
    }

    /** 打开本界面时不要冻结单机世界，MCP 操作照常结算。 */
    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
