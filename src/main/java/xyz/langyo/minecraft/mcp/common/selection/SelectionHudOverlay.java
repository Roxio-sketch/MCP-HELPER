package xyz.langyo.minecraft.mcp.common.selection;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.client.event.RegisterGuiOverlaysEvent;
import net.minecraftforge.client.gui.overlay.ForgeGui;
import net.minecraftforge.client.gui.overlay.IGuiOverlay;

/**
 * 结构选择器 HUD（方案第二、十、十一、十六、十七、二十节）。
 *
 * <p>只在手持 Structure Selector 时显示。位置固定在**屏幕左侧、垂直居中**，不再贴左上角：
 * 左上角容易和其他 MOD 的境界 / 血量 / 小地图等 HUD 叠在一起。坐标按当前分辨率现算，
 * 不写死 1080p 数值。</p>
 *
 * <p>只显示持久状态：包围盒三轴范围、尺寸、体积、维度、Last action 与 Preview 模式。
 * “已保存结构 / 保存失败 / 实体保存说明”这类一次性结果走 ActionBar 与聊天栏，不常驻
 * HUD（方案第十六节）。选区非法时用红色标出原因，选区在别的维度时提示维度名并隐藏
 * 世界线框。</p>
 */
public final class SelectionHudOverlay implements IGuiOverlay {

    /** 左侧边距，方案建议 8~12 px。 */
    private static final int MARGIN_X = 10;
    /** 顶部安全边距，防止超小窗口时盒子顶到屏幕外。 */
    private static final int MARGIN_Y = 4;
    private static final int LINE_HEIGHT = 10;
    private static final int COLOR_TITLE = 0xFFFFAA00;
    private static final int COLOR_TEXT = 0xFFDDDDDD;
    private static final int COLOR_ERROR = 0xFFFF5555;

    public static void registerOverlays(RegisterGuiOverlaysEvent event) {
        event.registerAboveAll("mcpmod_structure_selection", new SelectionHudOverlay());
    }

    @Override
    public void render(ForgeGui gui, GuiGraphics graphics, float partialTick, int width, int height) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.player == null || mc.level == null) return;
        if (mc.options.hideGui) return;
        if (!SelectionRenderer.isHolding(mc.player)) return;

        SelectionData data = SelectionManager.clientSelection();
        SelectionData.Validity validity = SelectionManager.clientValidity();

        List<Component> lines = new ArrayList<>();
        lines.add(Component.translatable("mcpmod.selection.hud.title"));

        if (data == null || data.isEmpty()) {
            lines.add(Component.translatable("mcpmod.selection.hud.no_selection"));
            lines.add(Component.translatable("mcpmod.selection.hud.hint"));
        } else {
            lines.add(Component.translatable("mcpmod.selection.hud.axis",
                    "X", data.minX(), data.maxX()));
            lines.add(Component.translatable("mcpmod.selection.hud.axis",
                    "Y", data.minY(), data.maxY()));
            lines.add(Component.translatable("mcpmod.selection.hud.axis",
                    "Z", data.minZ(), data.maxZ()));
            if (data.isComplete()) {
                lines.add(Component.translatable("mcpmod.selection.hud.size",
                        data.sizeX(), data.sizeY(), data.sizeZ()));
                lines.add(Component.translatable("mcpmod.selection.hud.volume",
                        formatVolume(data.volume())));
            } else {
                lines.add(Component.translatable("mcpmod.selection.hud.need_more_points"));
            }
        }

        ResourceLocation dimension = data != null && data.dimension() != null
                ? data.dimension() : mc.level.dimension().location();
        lines.add(Component.translatable("mcpmod.selection.hud.dimension", dimension.toString()));

        if (data != null && !data.isEmpty()) {
            lines.add(Component.translatable("mcpmod.selection.hud.last_action",
                    lastActionText(data)));
        }
        // Preview 模式常显，玩家一眼知道自己现在是线框还是面框（方案第十七节）。
        lines.add(Component.translatable("mcpmod.selection.preview.hud",
                SelectionPreview.mode().text()));

        boolean errorLine = false;
        if (validity == SelectionData.Validity.OTHER_DIMENSION) {
            lines.add(Component.translatable("mcpmod.selection.hud.other_dimension",
                    dimension.toString()));
            errorLine = true;
        } else if (validity.isError()) {
            lines.add(Component.translatable("mcpmod.selection.hud.invalid", validity.text()));
            errorLine = true;
        }

        int maxWidth = 0;
        for (Component line : lines) {
            maxWidth = Math.max(maxWidth, mc.font.width(line));
        }
        int boxWidth = maxWidth + 8;
        int boxHeight = lines.size() * LINE_HEIGHT + 6;
        // 左侧 + 垂直居中：top 由屏幕高度现算，任何分辨率都居中。
        int boxLeft = MARGIN_X - 3;
        int boxTop = Math.max(MARGIN_Y, (height - boxHeight) / 2);
        graphics.fill(boxLeft, boxTop, boxLeft + boxWidth, boxTop + boxHeight, 0x90000000);

        int textX = MARGIN_X;
        int textY = boxTop + 3;
        int last = lines.size() - 1;
        for (int i = 0; i < lines.size(); i++) {
            int color = COLOR_TEXT;
            if (i == 0) color = COLOR_TITLE;
            else if (errorLine && i == last) color = COLOR_ERROR;
            graphics.drawString(mc.font, lines.get(i),
                    textX, textY + i * LINE_HEIGHT, color, true);
        }
    }

    /** Last action：扩展过就报“Expanded Y- to -68”，否则用动作自己的文案。 */
    private static Component lastActionText(SelectionData data) {
        if (data.lastAction() == SelectionData.Action.ADDED && data.lastAxis() != null) {
            return Component.translatable("mcpmod.selection.action.expanded",
                    data.lastAxis().label(), data.lastAxisValue());
        }
        return data.lastAction().text();
    }

    /** 体积加千位分隔符，1073741824 这类数字也能一眼读出来。 */
    private static String formatVolume(long volume) {
        return String.format("%,d", volume);
    }
}
