package xyz.langyo.minecraft.mcp.common.selection;

import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import xyz.langyo.minecraft.mcp.common.McpConfig;

/**
 * 结构选区的预览模式（方案第三 ~ 九、十七节）。
 *
 * <p>只有两种模式，不引入第三、第四种：</p>
 * <ul>
 *   <li>{@link Mode#WIREFRAME} —— 只画 12 条边 + 6 个极值标记，最清爽，默认值；</li>
 *   <li>{@link Mode#FILLED} —— 额外画 6 个半透明面（alpha ≈ 0.12），用来判断大盒子
 *       有没有把建筑完整包住。</li>
 * </ul>
 *
 * <p>这是纯客户端设置，不进 Structure NBT、不加 MCP Tool。玩家切换后会写进
 * {@code config/mcpmod.properties} 的 {@code selectionPreviewMode}，下次进游戏沿用。
 * 服务端只负责把命令翻译成一个客户端包（见 {@link SelectionPreviewPacket}），
 * 真正改状态永远发生在客户端。</p>
 */
public final class SelectionPreview {

    /** 预览模式；两个模式的渲染开销都恒定，与选区体积无关。 */
    public enum Mode {
        WIREFRAME,
        FILLED;

        /** 配置文件 / 网络用的稳定小写名字。 */
        public String id() {
            return name().toLowerCase(Locale.ROOT);
        }

        /** 语言文件里的键，HUD 与 ActionBar 共用。 */
        public String translationKey() {
            return "mcpmod.selection.preview." + id();
        }

        public Component text() {
            return Component.translatable(translationKey());
        }

        public Mode next() {
            return this == WIREFRAME ? FILLED : WIREFRAME;
        }

        /** 解析配置 / 网络里的名字；未知值返回 {@code null}，由调用方决定回退。 */
        public static Mode byId(String id) {
            if (id == null) return null;
            try {
                return valueOf(id.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
    }

    /** 客户端配置文件里的键名。 */
    public static final String CONFIG_KEY = "selectionPreviewMode";

    /** 默认 Wireframe：平时更清爽，需要做最后检查时再按 V 切到 Filled。 */
    private static volatile Mode mode = Mode.WIREFRAME;

    private SelectionPreview() {}

    public static Mode mode() {
        return mode;
    }

    /** 从客户端配置读入；找不到或非法时回退到 {@link Mode#WIREFRAME}。 */
    public static void load() {
        Mode m = Mode.byId(McpConfig.getStringProperty(CONFIG_KEY));
        mode = m == null ? Mode.WIREFRAME : m;
    }

    /** 设置并持久化，返回设置后的模式。 */
    public static Mode set(Mode m) {
        if (m == null) return mode;
        mode = m;
        try {
            McpConfig.setStringProperty(CONFIG_KEY, m.id());
        } catch (Throwable ignored) {
            // 落盘失败不影响本次运行，只是下次启动回到默认值。
        }
        return mode;
    }

    /** 在两种模式间切换并持久化。 */
    public static Mode toggle() {
        return set(mode.next());
    }

    /**
     * 处理服务端发来的预览模式包。
     *
     * <p>{@code mode == null} 表示“查询”：服务端不知道客户端当前是哪种模式，
     * 所以让客户端自己把当前值打到聊天栏。指定模式则静默切换，确认文案由命令层
     * 在服务端回执，避免单机下同一条消息出现两遍。</p>
     */
    public static void applyPacket(SelectionPreviewPacket packet) {
        if (packet == null) return;
        Minecraft mc = Minecraft.getInstance();
        if (packet.mode() == null) {
            if (mc != null && mc.player != null) {
                mc.player.displayClientMessage(
                        Component.translatable("mcpmod.selection.preview.current", mode.text()), false);
            }
            return;
        }
        Mode m = Mode.byId(packet.mode());
        if (m != null) set(m);
    }
}
