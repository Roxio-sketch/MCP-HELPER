package xyz.langyo.minecraft.mcp.common.selection;

import net.minecraft.network.FriendlyByteBuf;

/**
 * 服务端 → 客户端 的预览模式包（方案第六、七节）。
 *
 * <p>预览模式本身是客户端设置，服务端不维护这份状态；命令层只把“切成某种模式”
 * 或“报告当前模式”翻译成一个包发给执行命令的那名玩家自己，不广播。</p>
 *
 * <ul>
 *   <li>{@code mode != null} —— 切到该模式（wireframe / filled）；</li>
 *   <li>{@code mode == null} —— 查询，由客户端把当前模式打到聊天栏。</li>
 * </ul>
 */
public final class SelectionPreviewPacket {

    private final String mode;

    public SelectionPreviewPacket(String mode) {
        this.mode = mode;
    }

    public SelectionPreviewPacket(FriendlyByteBuf buf) {
        this.mode = buf.readBoolean() ? buf.readUtf(16) : null;
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeBoolean(mode != null);
        if (mode != null) buf.writeUtf(mode);
    }

    /** 目标模式名，或 {@code null} 表示查询当前模式。 */
    public String mode() {
        return mode;
    }
}
