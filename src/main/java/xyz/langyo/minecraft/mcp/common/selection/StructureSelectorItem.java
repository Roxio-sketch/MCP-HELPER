package xyz.langyo.minecraft.mcp.common.selection;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/**
 * Structure Selector / 结构选择器。
 *
 * <p>框选行为本身由 {@code SelectionEvents} 的鼠标事件处理（左键没有物品回调），
 * 这里只补“Shift + 右键空气 → Undo”这一条：右键方块会先被 PlayerInteractEvent
 * 拦下，真正走到这里的只有空气。</p>
 *
 * <p>物品不消耗、无耐久，堆叠数 1。清空选区按方案第六节交给命令
 * {@code /mcp selection clear}，避免与 Undo 抢同一个手势。</p>
 */
public class StructureSelectorItem extends Item {

    public StructureSelectorItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (!player.isShiftKeyDown()) {
            // 右键空气（非 Shift）：无操作。
            return InteractionResultHolder.pass(stack);
        }
        if (!level.isClientSide && player instanceof ServerPlayer serverPlayer) {
            SelectionEvents.performUndo(serverPlayer);
        }
        return InteractionResultHolder.success(stack);
    }
}
