package xyz.langyo.minecraft.mcp.common.selection;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

/**
 * 服务端 → 客户端 的轻量选区同步包。
 *
 * <p>只带包围盒 6 个极值、有效点数、最后点、Last action 与 validity，不传撤销历史
 * （历史只在服务端用）。只在边界变化 / undo / clear / 切维度 / 重新登录时发送，
 * 不逐帧同步（方案第 24 节）。{@code validity} 由服务端现算，客户端不再猜。</p>
 */
public final class SelectionSyncPacket {

    private final SelectionData data;
    private final SelectionData.Validity validity;

    public SelectionSyncPacket(SelectionData data, SelectionData.Validity validity) {
        this.data = data;
        this.validity = validity == null ? SelectionData.Validity.NO_SELECTION : validity;
    }

    public SelectionSyncPacket(FriendlyByteBuf buf) {
        SelectionData decoded = null;
        if (buf.readBoolean()) {
            ResourceLocation dim = buf.readResourceLocation();
            int points = buf.readVarInt();
            int minX = 0, maxX = 0, minY = 0, maxY = 0, minZ = 0, maxZ = 0;
            if (points > 0) {
                minX = buf.readVarInt();
                maxX = buf.readVarInt();
                minY = buf.readVarInt();
                maxY = buf.readVarInt();
                minZ = buf.readVarInt();
                maxZ = buf.readVarInt();
            }
            BlockPos lastPoint = buf.readBoolean() ? buf.readBlockPos() : null;
            SelectionData.Action action = SelectionData.Action.byName(buf.readUtf(24));
            SelectionData.Axis axis = SelectionData.Axis.byName(buf.readUtf(16));
            int axisValue = buf.readVarInt();
            decoded = new SelectionData(dim, minX, maxX, minY, maxY, minZ, maxZ,
                    points, lastPoint, action, axis, axisValue, System.currentTimeMillis());
        }
        this.data = decoded;
        this.validity = SelectionData.Validity.byName(buf.readUtf(32));
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeBoolean(data != null);
        if (data != null) {
            buf.writeResourceLocation(data.dimension());
            buf.writeVarInt(data.points());
            if (data.points() > 0) {
                buf.writeVarInt(data.minX());
                buf.writeVarInt(data.maxX());
                buf.writeVarInt(data.minY());
                buf.writeVarInt(data.maxY());
                buf.writeVarInt(data.minZ());
                buf.writeVarInt(data.maxZ());
            }
            buf.writeBoolean(data.lastPoint() != null);
            if (data.lastPoint() != null) buf.writeBlockPos(data.lastPoint());
            buf.writeUtf(data.lastAction().name());
            buf.writeUtf(data.lastAxis() == null ? "" : data.lastAxis().name());
            buf.writeVarInt(data.lastAxisValue());
        }
        buf.writeUtf(validity.name());
    }

    public SelectionData data() {
        return data;
    }

    public SelectionData.Validity validity() {
        return validity;
    }
}
