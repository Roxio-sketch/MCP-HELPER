package xyz.langyo.minecraft.mcp.common.selection;

import java.util.Optional;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

/**
 * 选区同步用的网络通道。
 *
 * <p>只有一个 服务端 → 客户端 的包，而且只发给选区所属的那名玩家自己，
 * 不做广播（方案第 24、25 节）。不做每帧同步，只有状态变化时才发。</p>
 */
public final class SelectionNetwork {

    private static final String VERSION = "1";
    private static SimpleChannel channel;

    private SelectionNetwork() {}

    public static void register() {
        if (channel != null) return;
        // acceptMissingOr：对端没有这个通道（原版客户端、没装模组的局域网玩家）也放行，
        // 只有装了模组但版本不一致时才拒绝，避免破坏现有的局域网联机能力。
        channel = NetworkRegistry.newSimpleChannel(
                ResourceLocation.fromNamespaceAndPath("mcpmod", "selection"),
                () -> VERSION,
                NetworkRegistry.acceptMissingOr(VERSION::equals),
                NetworkRegistry.acceptMissingOr(VERSION::equals));
        channel.registerMessage(0, SelectionSyncPacket.class,
                SelectionSyncPacket::encode,
                SelectionSyncPacket::new,
                (SelectionSyncPacket msg, java.util.function.Supplier<NetworkEvent.Context> ctx) -> {
                    ctx.get().enqueueWork(() -> SelectionManager.applyClientSync(msg));
                    ctx.get().setPacketHandled(true);
                },
                Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        // 预览模式是客户端设置，服务端只转发“切换 / 查询”请求，同样只发给本人。
        channel.registerMessage(1, SelectionPreviewPacket.class,
                SelectionPreviewPacket::encode,
                SelectionPreviewPacket::new,
                (SelectionPreviewPacket msg, java.util.function.Supplier<NetworkEvent.Context> ctx) -> {
                    ctx.get().enqueueWork(() -> SelectionPreview.applyPacket(msg));
                    ctx.get().setPacketHandled(true);
                },
                Optional.of(NetworkDirection.PLAY_TO_CLIENT));
    }

    public static void sendToPlayer(ServerPlayer player, SelectionData data,
                                    SelectionData.Validity validity) {
        if (channel == null || player == null) return;
        try {
            channel.send(PacketDistributor.PLAYER.with(() -> player),
                    new SelectionSyncPacket(data, validity));
        } catch (Throwable ignored) {
            // 玩家正在断线/切维度时可能发送失败，选区状态本身不受影响。
        }
    }

    /**
     * 把预览模式请求发给该玩家自己。
     *
     * @param mode 目标模式名；{@code null} 表示查询当前模式
     */
    public static void sendPreviewToPlayer(ServerPlayer player, String mode) {
        if (channel == null || player == null) return;
        try {
            channel.send(PacketDistributor.PLAYER.with(() -> player),
                    new SelectionPreviewPacket(mode));
        } catch (Throwable ignored) {
            // 原版/未装模组的客户端没有这条通道，静默忽略；命令层已给出聊天回执。
        }
    }
}
