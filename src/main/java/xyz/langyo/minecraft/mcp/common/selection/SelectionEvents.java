package xyz.langyo.minecraft.mcp.common.selection;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.Event;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * 结构选择器的服务端交互与同步触发（方案第三、四、五、十九、二十、二十六、二十七节）。
 *
 * <p>交互全部收敛到“右键加边界点、Shift + 右键 Undo”：</p>
 * <ul>
 *   <li>右键方块 → 把该方块坐标并入包围盒；</li>
 *   <li>Shift + 右键（方块或空气）→ 撤销上一次有效扩展；</li>
 *   <li>左键 → 不设点也不破坏方块，只取消原版挖掘。</li>
 * </ul>
 *
 * <p>只在逻辑服务端改选区，客户端由同步包更新缓存。左右键事件在两侧都会取消，
 * 保证左键不真的敲掉方块、右键不误开箱子/门/按钮。</p>
 */
public final class SelectionEvents {

    public SelectionEvents() {}

    // ===================== 命令注册 =====================

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        SelectionCommands.register(event.getDispatcher());
    }

    // ===================== 生命周期：重新同步 / 清理 =====================

    @SubscribeEvent
    public void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            SelectionManager.sync(player);
        }
    }

    @SubscribeEvent
    public void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            SelectionManager.remove(player.getUUID());
        }
    }

    @SubscribeEvent
    public void onPlayerChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            SelectionManager.sync(player);
        }
    }

    @SubscribeEvent
    public void onPlayerRespawn(PlayerEvent.PlayerRespawnEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            SelectionManager.sync(player);
        }
    }

    // ===================== 框选交互 =====================

    /**
     * 左键不再承担 Pos1 之类的职责：只屏蔽挖掘，不做任何选区操作（方案第 26 节）。
     * 所有加点统一走右键，交互更简单，也不会误拆结构。
     */
    @SubscribeEvent
    public void onLeftClickBlock(PlayerInteractEvent.LeftClickBlock event) {
        if (!isSelector(event.getItemStack())) return;
        event.setUseBlock(Event.Result.DENY);
        event.setUseItem(Event.Result.DENY);
        event.setCanceled(true);
    }

    /** 右键方块：Shift 时 Undo，否则加边界点。 */
    @SubscribeEvent
    public void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (!isSelector(event.getItemStack())) return;
        // 选择行为优先于箱子 / 门 / 机器 / 按钮的普通交互。
        event.setUseBlock(Event.Result.DENY);
        event.setUseItem(Event.Result.DENY);
        event.setCanceled(true);
        if (event.getLevel().isClientSide()) return;
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (player.isShiftKeyDown()) {
            performUndo(player);
        } else {
            performAdd(player, event.getPos());
        }
    }

    // ===================== 供物品回调复用的动作 =====================

    /** 右键空气只可能是 Shift 场景；非 Shift 时不做任何事。 */
    static void performUndo(ServerPlayer player) {
        if (SelectionManager.undo(player)) {
            player.displayClientMessage(Component.translatable("mcpmod.selection.undo.done"), true);
        } else {
            player.displayClientMessage(Component.translatable("mcpmod.selection.undo.empty"), true);
        }
    }

    static void performAdd(ServerPlayer player, BlockPos pos) {
        SelectionManager.AddOutcome outcome = SelectionManager.addPoint(player, pos);
        switch (outcome) {
            case EXPANDED -> {
                SelectionData data = SelectionManager.serverSelection(player.getUUID());
                if (data != null) {
                    // 只在动作栏报“刚刚扩了哪条边”：包围盒 / 尺寸 / 体积由左上角 HUD 常显，
                    // 再往动作栏塞一行 summary 只会把这一行立刻顶掉。
                    if (data.lastAxis() != null) {
                        player.displayClientMessage(Component.translatable(
                                "mcpmod.selection.action.expanded",
                                data.lastAxis().label(), data.lastAxisValue()), true);
                    } else {
                        player.displayClientMessage(Component.translatable(
                                "mcpmod.selection.point_first",
                                pos.getX(), pos.getY(), pos.getZ()), true);
                    }
                }
            }
            case INSIDE -> player.displayClientMessage(
                    Component.translatable("mcpmod.selection.action.inside"), true);
            case OTHER_DIMENSION -> {
                SelectionData data = SelectionManager.serverSelection(player.getUUID());
                String dim = data != null ? String.valueOf(data.dimension()) : "?";
                player.displayClientMessage(
                        Component.translatable("mcpmod.selection.other_dimension", dim), false);
            }
            default -> {
            }
        }
    }

    private static boolean isSelector(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        try {
            return McpItems.STRUCTURE_SELECTOR.isPresent()
                    && stack.is(McpItems.STRUCTURE_SELECTOR.get());
        } catch (Throwable t) {
            return false;
        }
    }
}
