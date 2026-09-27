package xyz.langyo.minecraft.mcp.common;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.File;
import java.io.OutputStream;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.phys.AABB;

/**
 * 结构保存 / 放置 / 列表（优化文档里的「预制建筑」）。
 *
 * <p>只暴露 3 个核心工具，结构本体与预制元数据分开存放：</p>
 * <pre>
 * &lt;游戏目录&gt;/mcp_structures/structures/&lt;命名空间&gt;/&lt;名称&gt;.nbt   // 原生 Structure NBT
 * &lt;游戏目录&gt;/mcp_structures/prefabs/&lt;命名空间&gt;/&lt;名称&gt;.json     // 预制建筑元数据
 * &lt;游戏目录&gt;/mcp_structures/index.json                        // 运行期索引
 * </pre>
 *
 * <p>运行时保存的结构只写入世界存档与 {@code mcp_structures/}，绝不动正在运行的 JAR。
 * 需要变成主 MOD 正式资产时，用 {@code save_structure} 的 {@code export_to} 参数导出成
 * 可直接复制进 {@code src/main/resources} 的目录树。</p>
 */
public final class StructureLibrary {

    private static final Gson PRETTY = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final Object LOCK = new Object();
    /** 结构与 save/compare/place 共用的体积上限。 */
    private static final long MAX_VOLUME = 4_000_000L;

    private StructureLibrary() {}

    /**
     * save / compare / place 与结构选择器共用的体积上限。
     * Structure Selector 直接读这一个值，不另建一套限制。
     */
    public static long maxVolume() {
        return MAX_VOLUME;
    }

    // ===================== save_structure =====================

    public static String saveStructure(Minecraft mc, Map<String, String> p) {
        ServerLevel level = WorldEditHelper.serverLevel(mc, p);
        if (level == null) {
            return JsonHelper.error("save requires the integrated server "
                    + "(host a singleplayer/LAN world)");
        }
        String name = ToolParams.str(p, "name", null);
        if (name == null) return JsonHelper.error("missing name");
        ResourceLocation id = parseId(name);
        if (id == null) return JsonHelper.error("bad name (use lowercase ns:path): " + name);
        int[] a = ToolParams.vec3(ToolParams.str(p, "pos1", null));
        int[] b = ToolParams.vec3(ToolParams.str(p, "pos2", null));
        if (a == null || b == null) {
            int[] origin = ToolParams.vec3(ToolParams.str(p, "origin", null));
            int[] size = ToolParams.vec3(ToolParams.str(p, "size", null));
            if (origin == null || size == null) {
                return JsonHelper.error("missing pos1/pos2 (or origin + size)");
            }
            a = origin;
            b = new int[]{origin[0] + size[0] - 1, origin[1] + size[1] - 1, origin[2] + size[2] - 1};
        }
        int minX = Math.min(a[0], b[0]), minY = Math.min(a[1], b[1]), minZ = Math.min(a[2], b[2]);
        int maxX = Math.max(a[0], b[0]), maxY = Math.max(a[1], b[1]), maxZ = Math.max(a[2], b[2]);
        if (minY < level.getMinBuildHeight() || maxY >= level.getMaxBuildHeight()) {
            return JsonHelper.error("y out of build height (" + level.getMinBuildHeight()
                    + ".." + (level.getMaxBuildHeight() - 1) + ")");
        }
        final int sx = maxX - minX + 1, sy = maxY - minY + 1, sz = maxZ - minZ + 1;
        long volume = (long) sx * sy * sz;
        if (volume > MAX_VOLUME) {
            return JsonHelper.error("region too large: " + volume + " blocks (max " + MAX_VOLUME + ")");
        }

        boolean includeAir = ToolParams.bool(p, "include_air", false);
        boolean includeEntities = ToolParams.bool(p, "include_entities", false);
        boolean prefab = ToolParams.bool(p, "prefab", false);
        String displayName = ToolParams.str(p, "display_name", id.getPath());
        String category = ToolParams.str(p, "category", "");
        String anchor = ToolParams.str(p, "anchor", "bottom_center");

        ListTag palette = new ListTag();
        Map<String, Integer> paletteIndex = new LinkedHashMap<>();
        ListTag blocks = new ListTag();
        for (int y = minY; y <= maxY; y++) {
            for (int z = minZ; z <= maxZ; z++) {
                for (int x = minX; x <= maxX; x++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    BlockState st = level.getBlockState(pos);
                    if (!includeAir && st.isAir()) continue;
                    String key = BlockStateParser.serialize(st);
                    Integer idx = paletteIndex.get(key);
                    if (idx == null) {
                        idx = palette.size();
                        paletteIndex.put(key, idx);
                        palette.add(NbtUtils.writeBlockState(st));
                    }
                    CompoundTag bt = new CompoundTag();
                    bt.put("pos", intList(x - minX, y - minY, z - minZ));
                    bt.putInt("state", idx);
                    if (st.hasBlockEntity()) {
                        BlockEntity be = level.getBlockEntity(pos);
                        if (be != null) bt.put("nbt", be.saveWithoutMetadata());
                    }
                    blocks.add(bt);
                }
            }
        }

        CompoundTag tag = new CompoundTag();
        tag.put("size", intList(sx, sy, sz));
        tag.put("palette", palette);
        tag.put("blocks", blocks);
        if (includeEntities) {
            ListTag ents = collectEntities(level, minX, minY, minZ, maxX, maxY, maxZ);
            if (ents.size() > 0) tag.put("entities", ents);
        }
        NbtUtils.addCurrentDataVersion(tag);

        Path nbtPath = structuresPath(mc, id);
        try {
            writeNbt(tag, nbtPath);
        } catch (Exception e) {
            return JsonHelper.error("write failed: " + e.getMessage());
        }
        // 同步一份到世界 generated 目录，/place template 也能直接使用。
        MinecraftServer server = mc.getSingleplayerServer();
        if (server != null) {
            try {
                Path gen = server.getStructureManager().getPathToGeneratedStructure(id, ".nbt");
                writeNbt(tag, gen);
            } catch (Throwable ignored) {
            }
        }
        if (prefab) writePrefab(mc, id, displayName, category, anchor);
        updateIndex(mc, id, displayName, category, anchor, sx, sy, sz, prefab);

        String exported = null;
        String exportTo = ToolParams.str(p, "export_to", null);
        if (exportTo != null) exported = export(mc, id, exportTo);

        StringBuilder sb = new StringBuilder(160);
        sb.append("{\"id\":\"").append(JsonHelper.escape(id.toString())).append('"');
        sb.append(",\"size\":\"").append(sx).append('x').append(sy).append('x').append(sz).append('"');
        sb.append(",\"prefab\":").append(prefab);
        sb.append(",\"status\":\"saved\"");
        if (exported != null) sb.append(",\"exported\":\"").append(JsonHelper.escape(exported)).append('"');
        sb.append('}');
        return sb.toString();
    }

    // ===================== place_structure =====================

    public static String placeStructure(Minecraft mc, Map<String, String> p) {
        ServerLevel level = WorldEditHelper.serverLevel(mc, p);
        if (level == null) {
            return JsonHelper.error("place requires the integrated server "
                    + "(host a singleplayer/LAN world)");
        }
        String idStr = ToolParams.str(p, "id", null);
        if (idStr == null) return JsonHelper.error("missing id");
        ResourceLocation id = parseId(idStr);
        if (id == null) return JsonHelper.error("bad id: " + idStr);
        CompoundTag tag = loadNbt(mc, id.toString());
        if (tag == null) return JsonHelper.error("structure not found: " + id);
        int[] origin = ToolParams.vec3(ToolParams.str(p, "origin", null));
        if (origin == null) return JsonHelper.error("missing origin [x,y,z]");
        Rotation rotation = rotationOf(ToolParams.str(p, "rotation", "0"));
        if (rotation == null) return JsonHelper.error("bad rotation (0/90/180/270)");
        Mirror mirror = mirrorOf(ToolParams.str(p, "mirror", "none"));
        if (mirror == null) return JsonHelper.error("bad mirror (none/left_right/front_back)");
        String anchor = ToolParams.str(p, "anchor", null);
        if (anchor == null) anchor = prefabAnchor(mc, id.toString());
        if (anchor == null) anchor = "min_corner";
        boolean includeEntities = ToolParams.bool(p, "include_entities", true);
        int flags = ToolParams.intVal(p, "flags", Block.UPDATE_ALL);

        StructureTemplate template = new StructureTemplate();
        try {
            template.load(level.registryAccess().lookupOrThrow(Registries.BLOCK), tag);
        } catch (Throwable t) {
            return JsonHelper.error("failed to load structure: " + t.getMessage());
        }
        Vec3i size = template.getSize(rotation);
        int offX = 0, offY = 0, offZ = 0;
        switch (anchor.toLowerCase(Locale.ROOT)) {
            case "bottom_center":
                offX = -size.getX() / 2;
                offZ = -size.getZ() / 2;
                break;
            case "center":
                offX = -size.getX() / 2;
                offY = -size.getY() / 2;
                offZ = -size.getZ() / 2;
                break;
            default:
                break;
        }
        BlockPos target = new BlockPos(origin[0] + offX, origin[1] + offY, origin[2] + offZ);
        StructurePlaceSettings settings = new StructurePlaceSettings()
                .setRotation(rotation)
                .setMirror(mirror)
                .setIgnoreEntities(!includeEntities);
        boolean ok;
        try {
            BlockPos zero = template.getZeroPositionWithTransform(target, mirror, rotation);
            ok = template.placeInWorld(level, zero, zero, settings,
                    net.minecraft.util.RandomSource.create(), flags);
        } catch (Throwable t) {
            return JsonHelper.error("place failed: " + t.getMessage());
        }
        return "{\"id\":\"" + JsonHelper.escape(id.toString()) + "\",\"status\":\""
                + (ok ? "placed" : "noop") + "\",\"rotation\":" + rotationDegrees(rotation)
                + ",\"mirror\":\"" + mirrorName(mirror) + "\",\"anchor\":\""
                + JsonHelper.escape(anchor) + "\"}";
    }

    // ===================== list_structures =====================

    public static String listStructures(Minecraft mc, Map<String, String> p) {
        String want = ToolParams.str(p, "id", null);
        ResourceLocation wid = want != null ? parseId(want) : null;
        String key = wid != null ? wid.toString() : null;
        JsonArray arr;
        synchronized (LOCK) {
            arr = readIndex(mc);
        }
        StringBuilder sb = new StringBuilder(256);
        sb.append("{\"fields\":[\"id\",\"display_name\",\"size\",\"prefab\"],\"structures\":[");
        boolean first = true;
        for (JsonElement el : arr) {
            if (!el.isJsonObject()) continue;
            JsonObject o = el.getAsJsonObject();
            String id = str(o, "id", "");
            if (key != null && !key.equals(id)) continue;
            if (!first) sb.append(',');
            first = false;
            sb.append('[').append(q(id)).append(',')
                    .append(q(str(o, "display_name", ""))).append(',')
                    .append(q(sizeString(o))).append(',')
                    .append(bool(o, "prefab")).append(']');
        }
        sb.append("]}");
        return sb.toString();
    }

    // ===================== 供 WorldEditHelper 复用 =====================

    /** 结构方块遍历回调：(x, y, z, state)。 */
    @FunctionalInterface
    public interface BlockConsumer {
        void accept(int x, int y, int z, BlockState state);
    }

    /** 解析 {@code name} 或 {@code ns:path}；缺省命名空间为 {@code mcpmod}。 */
    public static ResourceLocation parseId(String name) {
        if (name == null) return null;
        String s = name.trim();
        if (s.isEmpty()) return null;
        if (!s.contains(":")) s = "mcpmod:" + s;
        return ResourceLocation.tryParse(s);
    }

    /** 读取运行时保存的结构 NBT；找不到时退回世界 generated 副本。 */
    public static CompoundTag loadNbt(Minecraft mc, String idStr) {
        ResourceLocation id = ResourceLocation.tryParse(idStr);
        if (id == null) return null;
        File f = structuresPath(mc, id).toFile();
        if (f.isFile()) {
            try {
                return NbtIo.readCompressed(f);
            } catch (Exception e) {
                return null;
            }
        }
        MinecraftServer server = mc != null ? mc.getSingleplayerServer() : null;
        if (server != null) {
            try {
                Path gen = server.getStructureManager().getPathToGeneratedStructure(id, ".nbt");
                if (Files.isRegularFile(gen)) return NbtIo.readCompressed(gen.toFile());
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    /**
     * 该结构是否已经存在（运行期保存目录里有 NBT，或索引里已登记）。
     * 命令层用它做“同名结构未经确认直接拒绝”，不改动 save 本身的覆盖行为。
     */
    public static boolean structureExists(Minecraft mc, ResourceLocation id) {
        if (mc == null || id == null) return false;
        if (structuresPath(mc, id).toFile().isFile()) return true;
        synchronized (LOCK) {
            return findEntry(readIndex(mc), id.toString()) != null;
        }
    }

    /** Structure NBT 的 size；格式异常返回 null。 */
    public static int[] nbtSize(CompoundTag tag) {
        ListTag l = tag.getList("size", 3);
        if (l.size() < 3) return null;
        return new int[]{l.getInt(0), l.getInt(1), l.getInt(2)};
    }

    /** 遍历 Structure NBT 里的所有方块。 */
    public static void forEachBlock(CompoundTag tag, HolderGetter<Block> lookup, BlockConsumer consumer) {
        List<BlockState> states = new ArrayList<>();
        ListTag palettes = tag.contains("palettes", 9) ? tag.getList("palettes", 9) : null;
        if (palettes != null && palettes.size() > 0) {
            ListTag pal = palettes.getList(0);
            for (int i = 0; i < pal.size(); i++) {
                states.add(NbtUtils.readBlockState(lookup, pal.getCompound(i)));
            }
        } else {
            ListTag pal = tag.getList("palette", 10);
            for (int i = 0; i < pal.size(); i++) {
                states.add(NbtUtils.readBlockState(lookup, pal.getCompound(i)));
            }
        }
        ListTag blocks = tag.getList("blocks", 10);
        for (int i = 0; i < blocks.size(); i++) {
            CompoundTag bt = blocks.getCompound(i);
            ListTag pos = bt.getList("pos", 3);
            int idx = bt.getInt("state");
            if (pos.size() < 3 || idx < 0 || idx >= states.size()) continue;
            consumer.accept(pos.getInt(0), pos.getInt(1), pos.getInt(2), states.get(idx));
        }
    }

    /** 索引里记录的预制锚点；没有则返回 null。 */
    public static String prefabAnchor(Minecraft mc, String idStr) {
        synchronized (LOCK) {
            JsonObject o = findEntry(readIndex(mc), idStr);
            return o != null ? str(o, "anchor", null) : null;
        }
    }

    // ===================== 内部实现 =====================

    private static ListTag collectEntities(ServerLevel level, int minX, int minY, int minZ,
                                            int maxX, int maxY, int maxZ) {
        AABB box = new AABB(minX, minY, minZ, maxX + 1.0, maxY + 1.0, maxZ + 1.0);
        ListTag ents = new ListTag();
        for (Entity e : level.getEntitiesOfClass(Entity.class, box)) {
            if (e instanceof Player) continue;
            CompoundTag et = new CompoundTag();
            double rx = e.getX() - minX, ry = e.getY() - minY, rz = e.getZ() - minZ;
            et.put("pos", doubleList(rx, ry, rz));
            et.put("blockPos", intList((int) Math.floor(rx), (int) Math.floor(ry), (int) Math.floor(rz)));
            CompoundTag n = new CompoundTag();
            try {
                e.saveWithoutId(n);
            } catch (Throwable ignored) {
            }
            n.putString("id", EntityType.getKey(e.getType()).toString());
            et.put("nbt", n);
            ents.add(et);
        }
        return ents;
    }

    private static ListTag intList(int... values) {
        ListTag t = new ListTag();
        for (int v : values) t.add(IntTag.valueOf(v));
        return t;
    }

    private static ListTag doubleList(double... values) {
        ListTag t = new ListTag();
        for (double v : values) t.add(DoubleTag.valueOf(v));
        return t;
    }

    private static void writeNbt(CompoundTag tag, Path path) throws Exception {
        Files.createDirectories(path.getParent());
        try (OutputStream os = Files.newOutputStream(path)) {
            NbtIo.writeCompressed(tag, os);
        }
    }

    private static File root(Minecraft mc) {
        File base = mc != null && mc.gameDirectory != null
                ? mc.gameDirectory : new File(System.getProperty("user.dir", "."));
        return new File(base, "mcp_structures");
    }

    /**
     * 运行期结构库根目录 {@code <游戏目录>/mcp_structures/}。
     * 供 {@code /mcp structure folder} 打开目录用；路径始终从当前运行目录现算。
     */
    public static File rootDirectory(Minecraft mc) {
        return root(mc);
    }

    /** {@code <游戏目录>/mcp_structures/structures/}。 */
    public static File structuresDirectory(Minecraft mc) {
        return new File(root(mc), "structures");
    }

    /** {@code <游戏目录>/mcp_structures/prefabs/}。 */
    public static File prefabsDirectory(Minecraft mc) {
        return new File(root(mc), "prefabs");
    }

    private static Path structuresPath(Minecraft mc, ResourceLocation id) {
        return new File(root(mc), "structures/" + id.getNamespace() + "/" + id.getPath() + ".nbt").toPath();
    }

    private static Path prefabPath(Minecraft mc, ResourceLocation id) {
        return new File(root(mc), "prefabs/" + id.getNamespace() + "/" + id.getPath() + ".json").toPath();
    }

    private static JsonArray readIndex(Minecraft mc) {
        File f = new File(root(mc), "index.json");
        if (!f.isFile()) return new JsonArray();
        try (Reader r = Files.newBufferedReader(f.toPath(), StandardCharsets.UTF_8)) {
            JsonElement el = JsonParser.parseReader(r);
            if (el != null && el.isJsonArray()) return el.getAsJsonArray();
        } catch (Exception ignored) {
        }
        return new JsonArray();
    }

    private static void writeIndex(Minecraft mc, JsonArray arr) {
        File f = new File(root(mc), "index.json");
        File parent = f.getParentFile();
        if (parent != null) parent.mkdirs();
        try (Writer w = Files.newBufferedWriter(f.toPath(), StandardCharsets.UTF_8)) {
            PRETTY.toJson(arr, w);
        } catch (Exception ignored) {
        }
    }

    private static JsonObject findEntry(JsonArray arr, String id) {
        for (JsonElement el : arr) {
            if (!el.isJsonObject()) continue;
            JsonObject o = el.getAsJsonObject();
            if (id.equals(str(o, "id", null))) return o;
        }
        return null;
    }

    private static void updateIndex(Minecraft mc, ResourceLocation id, String displayName,
                                    String category, String anchor, int sx, int sy, int sz, boolean prefab) {
        synchronized (LOCK) {
            JsonArray arr = readIndex(mc);
            JsonArray out = new JsonArray();
            for (JsonElement el : arr) {
                if (el.isJsonObject() && id.toString().equals(str(el.getAsJsonObject(), "id", null))) continue;
                out.add(el);
            }
            JsonObject o = new JsonObject();
            o.addProperty("id", id.toString());
            o.addProperty("display_name", displayName);
            o.addProperty("category", category);
            o.addProperty("anchor", anchor);
            JsonArray size = new JsonArray();
            size.add(sx);
            size.add(sy);
            size.add(sz);
            o.add("size", size);
            o.addProperty("prefab", prefab);
            o.addProperty("nbt", "structures/" + id.getNamespace() + "/" + id.getPath() + ".nbt");
            if (prefab) {
                o.addProperty("prefab_file", "prefabs/" + id.getNamespace() + "/" + id.getPath() + ".json");
            }
            o.addProperty("time", System.currentTimeMillis());
            out.add(o);
            writeIndex(mc, out);
        }
    }

    private static void writePrefab(Minecraft mc, ResourceLocation id, String displayName,
                                    String category, String anchor) {
        JsonObject o = new JsonObject();
        o.addProperty("structure", id.toString());
        o.addProperty("display_name", displayName);
        o.addProperty("category", category);
        o.addProperty("anchor", anchor);
        Path p = prefabPath(mc, id);
        try {
            Files.createDirectories(p.getParent());
            Files.writeString(p, PRETTY.toJson(o));
        } catch (Exception ignored) {
        }
    }

    /** 导出成可复制进 {@code src/main/resources} 的目录树，返回目标目录。 */
    private static String export(Minecraft mc, ResourceLocation id, String targetDir) {
        try {
            Path base = new File(targetDir).toPath();
            Path nbtFrom = structuresPath(mc, id);
            if (!Files.isRegularFile(nbtFrom)) return null;
            Path nbtTo = base.resolve("data/" + id.getNamespace() + "/structures/" + id.getPath() + ".nbt");
            Files.createDirectories(nbtTo.getParent());
            Files.copy(nbtFrom, nbtTo, StandardCopyOption.REPLACE_EXISTING);
            Path prefabFrom = prefabPath(mc, id);
            if (Files.isRegularFile(prefabFrom)) {
                Path prefabTo = base.resolve("data/" + id.getNamespace() + "/prefabs/" + id.getPath() + ".json");
                Files.createDirectories(prefabTo.getParent());
                Files.copy(prefabFrom, prefabTo, StandardCopyOption.REPLACE_EXISTING);
            }
            return base.toAbsolutePath().toString();
        } catch (Exception e) {
            return null;
        }
    }

    private static Rotation rotationOf(String s) {
        if (s == null) return Rotation.NONE;
        switch (s.trim().toLowerCase(Locale.ROOT)) {
            case "0":
            case "none":
                return Rotation.NONE;
            case "90":
            case "clockwise_90":
                return Rotation.CLOCKWISE_90;
            case "180":
            case "clockwise_180":
                return Rotation.CLOCKWISE_180;
            case "270":
            case "counterclockwise_90":
                return Rotation.COUNTERCLOCKWISE_90;
            default:
                return null;
        }
    }

    private static int rotationDegrees(Rotation r) {
        switch (r) {
            case CLOCKWISE_90:
                return 90;
            case CLOCKWISE_180:
                return 180;
            case COUNTERCLOCKWISE_90:
                return 270;
            default:
                return 0;
        }
    }

    private static Mirror mirrorOf(String s) {
        if (s == null) return Mirror.NONE;
        switch (s.trim().toLowerCase(Locale.ROOT)) {
            case "none":
            case "":
                return Mirror.NONE;
            case "left_right":
            case "leftright":
                return Mirror.LEFT_RIGHT;
            case "front_back":
            case "frontback":
                return Mirror.FRONT_BACK;
            default:
                return null;
        }
    }

    private static String mirrorName(Mirror m) {
        switch (m) {
            case LEFT_RIGHT:
                return "left_right";
            case FRONT_BACK:
                return "front_back";
            default:
                return "none";
        }
    }

    private static String sizeString(JsonObject o) {
        JsonElement el = o.get("size");
        if (el != null && el.isJsonArray() && el.getAsJsonArray().size() >= 3) {
            JsonArray a = el.getAsJsonArray();
            return a.get(0).getAsInt() + "x" + a.get(1).getAsInt() + "x" + a.get(2).getAsInt();
        }
        return "";
    }

    private static String str(JsonObject o, String key, String def) {
        if (o == null || !o.has(key) || o.get(key).isJsonNull()) return def;
        try {
            return o.get(key).getAsString();
        } catch (Exception e) {
            return def;
        }
    }

    private static boolean bool(JsonObject o, String key) {
        if (o == null || !o.has(key)) return false;
        try {
            return o.get(key).getAsBoolean();
        } catch (Exception e) {
            return false;
        }
    }

    private static String q(String s) {
        return "\"" + JsonHelper.escape(s == null ? "" : s) + "\"";
    }
}
