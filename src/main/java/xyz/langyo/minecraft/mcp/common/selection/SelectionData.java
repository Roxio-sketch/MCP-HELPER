package xyz.langyo.minecraft.mcp.common.selection;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/**
 * 结构选择器的选区数据（新版 Structure Selector 的数据模型）。
 *
 * <p>这一版不再以 {@code pos1 / pos2} 作为主交互：玩家反复右键结构外围的方块，
 * 每点一次就把该坐标并入包围盒。底层最终仍然只维护 6 个极值，并对外给出
 * {@code min / max}，所以 {@link #pos1()} / {@link #pos2()} 依旧可用，继续喂给
 * 已经验收通过的 {@code StructureLibrary}。</p>
 *
 * <p>为了支持 Undo，除了当前 6 个极值，还保存一串轻量快照
 * {@link Snapshot}：每次有效扩展前先把当时的极值压栈，撤销时弹栈恢复。
 * 快照只含 6 个 int、有效点数与最后点，不保存任何方块列表，内存开销与选区体积无关。</p>
 *
 * <p>本类不可变；所有校验都不落盘，用 {@link #validate} 现算，保证“选区在别的维度”
 * 这类状态永远不会被缓存成过期结果。</p>
 */
public final class SelectionData {

    /** 选区状态：正常 / 未完成 / 各种非法原因。 */
    public enum Validity {
        /** 至少两个边界点、同维度、在高度与体积限制内。 */
        OK("mcpmod.selection.valid", false),
        /** 完全没有选区。 */
        NO_SELECTION("mcpmod.selection.no_selection", false),
        /** 只点了一个边界点（线框显示为蓝色编辑中状态）。 */
        INCOMPLETE("mcpmod.selection.incomplete", false),
        /** 选区在别的维度。 */
        OTHER_DIMENSION("mcpmod.selection.reason.other_dimension", true),
        /** 超出世界建筑高度。 */
        OUTSIDE_HEIGHT("mcpmod.selection.reason.outside_height", true),
        /** 超过 StructureLibrary 的体积上限。 */
        TOO_LARGE("mcpmod.selection.reason.too_large", true);

        private final String key;
        private final boolean error;

        Validity(String key, boolean error) {
            this.key = key;
            this.error = error;
        }

        public String key() {
            return key;
        }

        /** 这是不是“非法”状态（需要红色提示），编辑中/未完成不算。 */
        public boolean isError() {
            return error;
        }

        public boolean isValid() {
            return this == OK;
        }

        public Component text(Object... args) {
            return Component.translatable(key, args);
        }

        /** 网络同步用的稳定名字，未知值一律回退到 NO_SELECTION。 */
        public static Validity byName(String name) {
            if (name == null) return NO_SELECTION;
            try {
                return valueOf(name);
            } catch (IllegalArgumentException e) {
                return NO_SELECTION;
            }
        }
    }

    /** 最近一次有效操作，用于 HUD 的 Last action 行。 */
    public enum Action {
        NONE("mcpmod.selection.action.none"),
        /** 新增一个点并扩展了边界。 */
        ADDED("mcpmod.selection.action.expanded"),
        /** 点落在当前包围盒内部，边界不变。 */
        INSIDE("mcpmod.selection.action.inside"),
        /** 撤销了一次边界变化。 */
        UNDO("mcpmod.selection.action.undo");

        private final String key;

        Action(String key) {
            this.key = key;
        }

        public String key() {
            return key;
        }

        public Component text(Object... args) {
            return Component.translatable(key, args);
        }

        public static Action byName(String name) {
            if (name == null) return NONE;
            try {
                return valueOf(name);
            } catch (IllegalArgumentException e) {
                return NONE;
            }
        }
    }

    /** 6 个极值方向，用来告诉玩家刚刚扩展的是哪条边。 */
    public enum Axis {
        X_MIN("X-"),
        X_MAX("X+"),
        Y_MIN("Y-"),
        Y_MAX("Y+"),
        Z_MIN("Z-"),
        Z_MAX("Z+");

        private final String label;

        Axis(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }

        public static Axis byName(String name) {
            if (name == null || name.isEmpty()) return null;
            try {
                return valueOf(name);
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
    }

    /** 撤销用的轻量快照：只有 6 个极值、有效点数与最后点。 */
    public static final class Snapshot {
        final int minX;
        final int maxX;
        final int minY;
        final int maxY;
        final int minZ;
        final int maxZ;
        final int points;
        final BlockPos lastPoint;

        Snapshot(int minX, int maxX, int minY, int maxY, int minZ, int maxZ,
                 int points, BlockPos lastPoint) {
            this.minX = minX;
            this.maxX = maxX;
            this.minY = minY;
            this.maxY = maxY;
            this.minZ = minZ;
            this.maxZ = maxZ;
            this.points = points;
            this.lastPoint = lastPoint;
        }

        static Snapshot empty() {
            return new Snapshot(0, 0, 0, 0, 0, 0, 0, null);
        }
    }

    /** {@link #add(BlockPos)} 的结果：新状态 + 边界是否真的变了。 */
    public static final class AddResult {
        public final SelectionData data;
        public final boolean changed;

        AddResult(SelectionData data, boolean changed) {
            this.data = data;
            this.changed = changed;
        }
    }

    /** 撤销历史最多保存这么多步，超出后丢最旧的一条。 */
    private static final int MAX_HISTORY = 128;

    private final ResourceLocation dimension;
    private final int minX;
    private final int maxX;
    private final int minY;
    private final int maxY;
    private final int minZ;
    private final int maxZ;
    /** 有效边界点数量；只有真正扩展边界（或第一个点）才 +1。 */
    private final int points;
    private final BlockPos lastPoint;
    private final Action lastAction;
    private final Axis lastAxis;
    private final int lastAxisValue;
    private final List<Snapshot> history;
    private final long lastUpdated;

    /** 完整状态（服务端权威，带撤销历史）。 */
    public SelectionData(ResourceLocation dimension,
                         int minX, int maxX, int minY, int maxY, int minZ, int maxZ,
                         int points, BlockPos lastPoint,
                         Action lastAction, Axis lastAxis, int lastAxisValue,
                         List<Snapshot> history, long lastUpdated) {
        this.dimension = dimension;
        this.minX = minX;
        this.maxX = maxX;
        this.minY = minY;
        this.maxY = maxY;
        this.minZ = minZ;
        this.maxZ = maxZ;
        this.points = Math.max(0, points);
        this.lastPoint = lastPoint;
        this.lastAction = lastAction == null ? Action.NONE : lastAction;
        this.lastAxis = lastAxis;
        this.lastAxisValue = lastAxisValue;
        this.history = history == null ? List.of() : history;
        this.lastUpdated = lastUpdated;
    }

    /** 客户端同步用：不带撤销历史。 */
    public SelectionData(ResourceLocation dimension,
                         int minX, int maxX, int minY, int maxY, int minZ, int maxZ,
                         int points, BlockPos lastPoint,
                         Action lastAction, Axis lastAxis, int lastAxisValue, long lastUpdated) {
        this(dimension, minX, maxX, minY, maxY, minZ, maxZ, points, lastPoint,
                lastAction, lastAxis, lastAxisValue, List.of(), lastUpdated);
    }

    public static SelectionData empty(ResourceLocation dimension) {
        return new SelectionData(dimension, 0, 0, 0, 0, 0, 0, 0, null,
                Action.NONE, null, 0, List.of(), System.currentTimeMillis());
    }

    // ===================== 状态查询 =====================

    public ResourceLocation dimension() {
        return dimension;
    }

    public int points() {
        return points;
    }

    public BlockPos lastPoint() {
        return lastPoint;
    }

    public Action lastAction() {
        return lastAction;
    }

    public Axis lastAxis() {
        return lastAxis;
    }

    public int lastAxisValue() {
        return lastAxisValue;
    }

    public long lastUpdated() {
        return lastUpdated;
    }

    public boolean isEmpty() {
        return points <= 0;
    }

    /** 至少两个边界点才算“可以保存的完整选区”。 */
    public boolean isComplete() {
        return points >= 2;
    }

    public boolean canUndo() {
        return !history.isEmpty();
    }

    // ===================== 边界与尺寸 =====================

    public int minX() { return minX; }
    public int maxX() { return maxX; }
    public int minY() { return minY; }
    public int maxY() { return maxY; }
    public int minZ() { return minZ; }
    public int maxZ() { return maxZ; }

    /** 兼容旧接口 / 调试页：包围盒的最小角。没有点时为 null。 */
    public BlockPos pos1() {
        return isEmpty() ? null : new BlockPos(minX, minY, minZ);
    }

    /** 兼容旧接口 / 调试页：包围盒的最大角。没有点时为 null。 */
    public BlockPos pos2() {
        return isEmpty() ? null : new BlockPos(maxX, maxY, maxZ);
    }

    /** 尺寸：方块范围 {@code [min,max]} 两端都要包含，所以 +1。 */
    public int sizeX() {
        return isEmpty() ? 0 : maxX - minX + 1;
    }

    public int sizeY() {
        return isEmpty() ? 0 : maxY - minY + 1;
    }

    public int sizeZ() {
        return isEmpty() ? 0 : maxZ - minZ + 1;
    }

    /** 用 long 计算，避免极端选区溢出 int。 */
    public long volume() {
        if (isEmpty()) return 0L;
        return (long) sizeX() * (long) sizeY() * (long) sizeZ();
    }

    public String sizeString() {
        return sizeX() + " × " + sizeY() + " × " + sizeZ();
    }

    // ===================== 编辑操作 =====================

    /**
     * 把一个边界点并入包围盒。
     *
     * <p>点完全落在当前包围盒内部时不改变任何极值，只更新 Last action；
     * 否则先压入当前快照再扩展，保证 Undo 能回到上一步。</p>
     */
    public AddResult add(BlockPos pos) {
        if (pos == null) return new AddResult(this, false);

        if (isEmpty()) {
            SelectionData next = new SelectionData(dimension,
                    pos.getX(), pos.getX(), pos.getY(), pos.getY(), pos.getZ(), pos.getZ(),
                    1, pos, Action.ADDED, null, 0, push(Snapshot.empty()),
                    System.currentTimeMillis());
            return new AddResult(next, true);
        }

        boolean inside = pos.getX() >= minX && pos.getX() <= maxX
                && pos.getY() >= minY && pos.getY() <= maxY
                && pos.getZ() >= minZ && pos.getZ() <= maxZ;
        if (inside) {
            SelectionData next = new SelectionData(dimension, minX, maxX, minY, maxY, minZ, maxZ,
                    points, lastPoint, Action.INSIDE, null, 0, history,
                    System.currentTimeMillis());
            return new AddResult(next, false);
        }

        int nMinX = Math.min(minX, pos.getX());
        int nMaxX = Math.max(maxX, pos.getX());
        int nMinY = Math.min(minY, pos.getY());
        int nMaxY = Math.max(maxY, pos.getY());
        int nMinZ = Math.min(minZ, pos.getZ());
        int nMaxZ = Math.max(maxZ, pos.getZ());

        // 提示“扩展了哪条边”：取本次扩张幅度最大的那个方向，平手时按 X-/X+/Y-/Y+/Z-/Z+ 顺序。
        Axis axis = null;
        int axisValue = 0;
        int best = 0;
        if (minX - pos.getX() > best) { axis = Axis.X_MIN; axisValue = nMinX; best = minX - pos.getX(); }
        if (pos.getX() - maxX > best) { axis = Axis.X_MAX; axisValue = nMaxX; best = pos.getX() - maxX; }
        if (minY - pos.getY() > best) { axis = Axis.Y_MIN; axisValue = nMinY; best = minY - pos.getY(); }
        if (pos.getY() - maxY > best) { axis = Axis.Y_MAX; axisValue = nMaxY; best = pos.getY() - maxY; }
        if (minZ - pos.getZ() > best) { axis = Axis.Z_MIN; axisValue = nMinZ; best = minZ - pos.getZ(); }
        if (pos.getZ() - maxZ > best) { axis = Axis.Z_MAX; axisValue = nMaxZ; best = pos.getZ() - maxZ; }

        SelectionData next = new SelectionData(dimension,
                nMinX, nMaxX, nMinY, nMaxY, nMinZ, nMaxZ,
                points + 1, pos, Action.ADDED, axis, axisValue, push(snapshot()),
                System.currentTimeMillis());
        return new AddResult(next, true);
    }

    /** 弹出一层历史，恢复到上一次边界。没有历史时返回 {@code this}。 */
    public SelectionData undo() {
        if (history.isEmpty()) return this;
        Snapshot s = history.get(history.size() - 1);
        List<Snapshot> rest = new ArrayList<>(history.subList(0, history.size() - 1));
        return new SelectionData(dimension, s.minX, s.maxX, s.minY, s.maxY, s.minZ, s.maxZ,
                s.points, s.lastPoint, Action.UNDO, null, 0, rest,
                System.currentTimeMillis());
    }

    private Snapshot snapshot() {
        return new Snapshot(minX, maxX, minY, maxY, minZ, maxZ, points, lastPoint);
    }

    private List<Snapshot> push(Snapshot s) {
        int keep = Math.min(history.size(), MAX_HISTORY - 1);
        List<Snapshot> out = new ArrayList<>(keep + 1);
        for (int i = history.size() - keep; i < history.size(); i++) {
            out.add(history.get(i));
        }
        out.add(s);
        return Collections.unmodifiableList(out);
    }

    // ===================== 校验 =====================

    /**
     * 现算选区状态。
     *
     * @param currentDim 玩家当前所在维度
     * @param minBuild   当前世界最低建筑高度（{@code level.getMinBuildHeight()}）
     * @param maxBuild   当前世界最高建筑高度（{@code level.getMaxBuildHeight()}，开区间）
     * @param maxVolume  StructureLibrary 的体积上限
     */
    public Validity validate(ResourceLocation currentDim, int minBuild, int maxBuild, long maxVolume) {
        if (isEmpty()) return Validity.NO_SELECTION;
        if (!isComplete()) return Validity.INCOMPLETE;
        if (dimension == null || currentDim == null || !dimension.equals(currentDim)) {
            return Validity.OTHER_DIMENSION;
        }
        if (minY < minBuild || maxY >= maxBuild) return Validity.OUTSIDE_HEIGHT;
        if (volume() > maxVolume) return Validity.TOO_LARGE;
        return Validity.OK;
    }

    @Override
    public String toString() {
        return "SelectionData{" + dimension + ", " + sizeString()
                + ", points=" + points + ", min=" + new BlockPos(minX, minY, minZ)
                + ", max=" + new BlockPos(maxX, maxY, maxZ) + "}";
    }
}
