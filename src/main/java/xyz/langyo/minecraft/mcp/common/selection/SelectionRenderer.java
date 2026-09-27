package xyz.langyo.minecraft.mcp.common.selection;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * 选区线框渲染（方案第七 ~ 九、二十三、二十四节）。
 *
 * <p>用客户端 World Render 事件 + AABB 画线，不生成真实方块 / 实体 / 粒子：
 * 无论 10³ 还是 200³ 都只画 12 条边 + 6 个极值标记 + 1 个新点高亮，渲染开销恒定。
 * 选区体积从 1000 涨到 1,000,000，这里画的线数量不变。</p>
 *
 * <p>两种预览模式（{@link SelectionPreview.Mode}）：Wireframe 只画边；Filled 额外画
 * 6 个半透明面，用来判断大盒子有没有完整包住建筑。Filled 也只多 6 个 quad，
 * 复杂度同样恒定，且面走世界深度测试，不会变成 X-Ray。</p>
 *
 * <p>颜色：只有一个边界点 = 蓝色（编辑中），完整有效 = 绿色，非法 = 红色；
 * 刚刚新增的边界点用黄色短暂高亮，X-/X+/Y-/Y+/Z-/Z+ 六个极值位置用黄色小线框标出。</p>
 *
 * <p>坐标严格覆盖整方块：{@code min .. max + 1}，避免线框穿过方块中心或差半格。</p>
 */
public final class SelectionRenderer {

    /** 新点高亮持续时间（毫秒），之后自然淡出。 */
    private static final long HIGHLIGHT_MS = 1500L;

    public SelectionRenderer() {}

    @SubscribeEvent
    public void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.player == null || mc.level == null) return;
        // 没拿工具就不渲染。
        if (!isHolding(mc.player)) return;

        SelectionData data = SelectionManager.clientSelection();
        if (data == null || data.isEmpty()) return;
        SelectionData.Validity validity = SelectionManager.clientValidity();
        // 没有选区 / 选区在别的维度：隐藏预览，HUD 里给文字提示（方案第二十节）。
        if (validity == SelectionData.Validity.NO_SELECTION
                || validity == SelectionData.Validity.OTHER_DIMENSION) {
            return;
        }
        ResourceLocation currentDim = mc.level.dimension().location();
        if (data.dimension() == null || !data.dimension().equals(currentDim)) return;

        boolean complete = data.isComplete();
        float r, g, b, a;
        if (!complete) {
            // 编辑中（只有一个边界点）：蓝色
            r = 0.30f; g = 0.60f; b = 1.00f; a = 0.85f;
        } else if (validity.isError()) {
            // 非法（超体积 / 超建筑高度）：红色
            r = 1.00f; g = 0.20f; b = 0.20f; a = 0.90f;
        } else {
            // 完整有效：绿色
            r = 0.25f; g = 1.00f; b = 0.40f; a = 0.85f;
        }
        // 轻微亮度脉冲，工具化风格，不做大型 shader。
        float pulse = 0.80f + 0.20f * (float) Math.sin(
                (System.currentTimeMillis() % 2000L) / 2000.0 * Math.PI * 2.0);

        PoseStack pose = event.getPoseStack();
        Vec3 camera = event.getCamera().getPosition();
        pose.pushPose();
        pose.translate(-camera.x, -camera.y, -camera.z);
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();

        // 方块范围 [min,max] 覆盖整格：渲染到 max + 1（方案第二十三节）。
        double x0 = data.minX(), y0 = data.minY(), z0 = data.minZ();
        double x1 = data.maxX() + 1.0, y1 = data.maxY() + 1.0, z1 = data.maxZ() + 1.0;

        VertexConsumer vc = buffers.getBuffer(RenderType.lines());
        LevelRenderer.renderLineBox(pose, vc, x0, y0, z0, x1, y1, z1,
                r, g, b, a * pulse);

        // 六个极值标记：只有当选区完整且合法时画，避免与红色报错状态抢视觉。
        if (complete && !validity.isError()) {
            double midX = (x0 + x1) / 2.0;
            double midY = (y0 + y1) / 2.0;
            double midZ = (z0 + z1) / 2.0;
            drawMarker(pose, vc, x0, midY, midZ, pulse);
            drawMarker(pose, vc, x1, midY, midZ, pulse);
            drawMarker(pose, vc, midX, y0, midZ, pulse);
            drawMarker(pose, vc, midX, y1, midZ, pulse);
            drawMarker(pose, vc, midX, midY, z0, pulse);
            drawMarker(pose, vc, midX, midY, z1, pulse);
        }

        // 刚新增的边界点：黄色短暂高亮，1.5 秒内线性淡出。
        BlockPos last = data.lastPoint();
        if (last != null) {
            long age = System.currentTimeMillis() - data.lastUpdated();
            if (age >= 0 && age < HIGHLIGHT_MS) {
                float fade = 1.0f - (float) age / (float) HIGHLIGHT_MS;
                double e = 0.03;
                LevelRenderer.renderLineBox(pose, vc,
                        last.getX() - e, last.getY() - e, last.getZ() - e,
                        last.getX() + 1.0 + e, last.getY() + 1.0 + e, last.getZ() + 1.0 + e,
                        1.00f, 0.85f, 0.20f, 0.95f * fade);
            }
        }

        // 先把线框 flush 出去，再画半透明面：面会写深度，若先画面就会遮住背面的边，
        // 那样“后 / 上 / 下是否包住”反而看不清。后画的 12% 面色只会给 12 条边上一层淡色调。
        buffers.endBatch(RenderType.lines());

        // Filled Bounds（方案第三、四、八节）：只多画 6 个固定 quad，与选区体积无关，
        // 也不会遍历选区里的方块；面走世界深度测试，被建筑挡住的面不会穿透显示。
        if (complete && SelectionPreview.mode() == SelectionPreview.Mode.FILLED) {
            VertexConsumer quads = buffers.getBuffer(RenderType.debugQuads());
            float faceAlpha = validity.isError() ? 0.14f : 0.12f;
            drawFaces(pose, quads, x0, y0, z0, x1, y1, z1, r, g, b, faceAlpha);
            buffers.endBatch(RenderType.debugQuads());
        }
        pose.popPose();
    }

    @SubscribeEvent
    public void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        SelectionManager.clearClient();
    }

    /** 在指定位置画一个小黄色线框，标记 X-/X+/Y-/Y+/Z-/Z+ 极值。 */
    private static void drawMarker(PoseStack pose, VertexConsumer vc,
                                   double x, double y, double z, float pulse) {
        double e = 0.16;
        LevelRenderer.renderLineBox(pose, vc, x - e, y - e, z - e, x + e, y + e, z + e,
                1.00f, 0.85f, 0.20f, 0.55f * pulse);
    }

    /**
     * 画包围盒的 6 个面（Top / Bottom / North / South / East / West）。
     *
     * <p>顶点绕序无所谓：渲染类型关掉了背面剔除，盒子从里往外看也能看到面。
     * 颜色与边框一致——有效淡绿、非法淡红，不给六个面分别配色（方案第四节）。</p>
     */
    private static void drawFaces(PoseStack pose, VertexConsumer vc,
                                  double x0, double y0, double z0,
                                  double x1, double y1, double z1,
                                  float r, float g, float b, float a) {
        // X- / X+
        quad(pose, vc, x0, y0, z0, x0, y0, z1, x0, y1, z1, x0, y1, z0, r, g, b, a);
        quad(pose, vc, x1, y0, z1, x1, y0, z0, x1, y1, z0, x1, y1, z1, r, g, b, a);
        // Y- / Y+
        quad(pose, vc, x0, y0, z1, x0, y0, z0, x1, y0, z0, x1, y0, z1, r, g, b, a);
        quad(pose, vc, x0, y1, z0, x0, y1, z1, x1, y1, z1, x1, y1, z0, r, g, b, a);
        // Z- / Z+
        quad(pose, vc, x1, y0, z0, x0, y0, z0, x0, y1, z0, x1, y1, z0, r, g, b, a);
        quad(pose, vc, x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1, r, g, b, a);
    }

    /** 一个 POSITION_COLOR quad：4 个 vertex → color → endVertex。 */
    private static void quad(PoseStack pose, VertexConsumer vc,
                             double ax, double ay, double az,
                             double bx, double by, double bz,
                             double cx, double cy, double cz,
                             double dx, double dy, double dz,
                             float r, float g, float b, float a) {
        com.mojang.blaze3d.vertex.PoseStack.Pose p = pose.last();
        org.joml.Matrix4f m = p.pose();
        vc.vertex(m, (float) ax, (float) ay, (float) az).color(r, g, b, a).endVertex();
        vc.vertex(m, (float) bx, (float) by, (float) bz).color(r, g, b, a).endVertex();
        vc.vertex(m, (float) cx, (float) cy, (float) cz).color(r, g, b, a).endVertex();
        vc.vertex(m, (float) dx, (float) dy, (float) dz).color(r, g, b, a).endVertex();
    }

    /** 主手或副手持有结构选择器。 */
    public static boolean isHolding(Player player) {
        if (player == null) return false;
        try {
            if (!McpItems.STRUCTURE_SELECTOR.isPresent()) return false;
            Item selector = McpItems.STRUCTURE_SELECTOR.get();
            return player.getMainHandItem().is(selector) || player.getOffhandItem().is(selector);
        } catch (Throwable t) {
            return false;
        }
    }
}
