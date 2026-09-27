package xyz.langyo.minecraft.mcp.common;

import com.google.gson.JsonObject;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.repository.PackRepository;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** No-editor Photon particle authoring through MCP, pinned to the exported Photon 1.1.17 template. */
public final class PhotonAutomationBridge {
    private static final String PACK_NAME = "mcpmod-generated-vfx";
    private static final String PACK_ID = "file/" + PACK_NAME;
    private static final ResourceLocation TEMPLATE = ResourceLocation.fromNamespaceAndPath("mcpmod", "photon/default_particle.fx");
    private static final ResourceLocation PAIR_TEMPLATE = ResourceLocation.fromNamespaceAndPath("mcpmod", "photon/two_particles.fx");
    private static final Pattern SAFE_PATH = Pattern.compile("[a-z0-9/._-]{1,128}");
    private static final Pattern SAFE_NAMESPACE = Pattern.compile("[a-z0-9._-]{1,64}");
    private static volatile String reloadStatus = "idle";

    private PhotonAutomationBridge() {}

    public static String dispatch(String method, Map<String, String> params) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) return JsonHelper.error("Minecraft client is unavailable");
        CompletableFuture<String> response = new CompletableFuture<>();
        mc.execute(() -> {
            try {
                String result = switch (method) {
                    case "photon_create_fx" -> create(mc, params, false);
                    case "photon_update_fx" -> create(mc, params, true);
                    case "photon_create_pair_fx" -> createPair(mc, params, false);
                    case "photon_update_pair_fx" -> createPair(mc, params, true);
                    case "photon_inspect_fx" -> inspect(mc, params);
                    case "photon_inspect_pair_fx" -> inspectPair(mc, params);
                    case "photon_clear_fx_cache" -> clearFxCache();
                    case "photon_list_fx" -> list(mc);
                    case "photon_status" -> status(mc);
                    case "photon_play_fx" -> play(mc, params);
                    default -> JsonHelper.error("unknown Photon operation");
                };
                response.complete(result);
            } catch (Throwable e) {
                response.complete(JsonHelper.error(e.getMessage() != null ? e.getMessage() : e.toString()));
            }
        });
        try {
            return response.get(12, TimeUnit.SECONDS);
        } catch (Exception e) {
            return JsonHelper.error("Photon operation timed out: " + e.getMessage());
        }
    }

    private static String create(Minecraft mc, Map<String, String> p, boolean update) throws Exception {
        ResourceLocation id = requiredId(p);
        Path target = effectPath(mc, id);
        if (update && !Files.isRegularFile(target)) return JsonHelper.error("effect does not exist; use photon_create_fx first");
        if (!update && Files.exists(target)) return JsonHelper.error("effect already exists; use photon_update_fx to edit it");

        CompoundTag root;
        if (update) {
            root = NbtIo.readCompressed(target.toFile());
        } else {
            ResourceManager manager = mc.getResourceManager();
            Resource resource = manager.getResource(TEMPLATE)
                    .orElseThrow(() -> new IllegalStateException("bundled Photon template is missing: " + TEMPLATE));
            try (InputStream input = resource.open()) { root = NbtIo.readCompressed(input); }
        }
        CompoundTag object = particle(root);
        CompoundTag config = object.getCompound("config");
        applyParameters(object, config, p, !update);
        validateTemplate(root);

        writeAndReload(mc, target, root);
        return JsonHelper.builder().put("ok", true).put("operation", update ? "updated" : "created")
                .put("resource", id.toString()).put("file", target.toString())
                .put("reload", reloadStatus).putRaw("parameters", parameters(object, config).toString()).build();
    }

    private static String createPair(Minecraft mc, Map<String, String> p, boolean update) throws Exception {
        ResourceLocation id = requiredId(p);
        Path target = effectPath(mc, id);
        if (update && !Files.isRegularFile(target)) return JsonHelper.error("effect not found");
        if (!update && Files.exists(target)) return JsonHelper.error("effect already exists; choose a new id");
        JsonArray emitters;
        try { emitters = JsonParser.parseString(p.getOrDefault("emitters", "")).getAsJsonArray(); }
        catch (Exception e) { throw new IllegalArgumentException("emitters must be a JSON array of two objects"); }
        if (emitters.size() != 2) throw new IllegalArgumentException("emitters must contain exactly two objects");
        Resource resource = mc.getResourceManager().getResource(PAIR_TEMPLATE)
                .orElseThrow(() -> new IllegalStateException("bundled Photon pair template is missing: " + PAIR_TEMPLATE));
        CompoundTag root;
        try (InputStream input = resource.open()) { root = update ? NbtIo.readCompressed(target.toFile()) : NbtIo.readCompressed(input); }
        ListTag objects = root.getCompound("fx").getCompound("mainFX").getList("fxObjects", Tag.TAG_COMPOUND);
        if (objects.size() != 2) throw new IllegalStateException("Photon pair template must have two emitters");
        JsonArray result = new JsonArray();
        for (int i = 0; i < 2; i++) {
            JsonElement element = emitters.get(i);
            if (!element.isJsonObject()) throw new IllegalArgumentException("emitter " + i + " must be an object");
            Map<String, String> fields = new LinkedHashMap<>();
            for (Map.Entry<String, JsonElement> entry : element.getAsJsonObject().entrySet()) {
                if (!entry.getValue().isJsonPrimitive()) throw new IllegalArgumentException("emitter field " + entry.getKey() + " must be scalar");
                if (!List.of("name", "color", "size", "lifetime", "speed", "rate", "duration", "looping", "maxParticles", "x", "y", "z").contains(entry.getKey()))
                    throw new IllegalArgumentException("unknown emitter field: " + entry.getKey());
                fields.put(entry.getKey(), entry.getValue().getAsString());
            }
            CompoundTag object = objects.getCompound(i);
            if (!object.getString("_type").equals("particle")) throw new IllegalArgumentException("pair API requires two particle emitters");
            CompoundTag config = object.getCompound("config");
            applyParameters(object, config, fields, false);
            CompoundTag position = object.getCompound("transform").getCompound("localPosition");
            for (String axis : List.of("x", "y", "z"))
                if (fields.containsKey(axis)) position.putFloat(axis, floatValue(fields, axis, -32f, 32f));
            JsonObject details = parameters(object, config);
            for (String axis : List.of("x", "y", "z")) details.addProperty(axis, position.getFloat(axis));
            result.add(details);
        }
        validateTemplate(root);
        writeAndReload(mc, target, root);
        return JsonHelper.builder().put("ok", true).put("operation", update ? "updated" : "created")
                .put("resource", id.toString()).put("file", target.toString())
                .put("reload", reloadStatus).putRaw("emitters", result.toString()).build();
    }

    private static String inspectPair(Minecraft mc, Map<String, String> p) throws Exception {
        ResourceLocation id = requiredId(p);
        Path file = effectPath(mc, id);
        if (!Files.isRegularFile(file)) return JsonHelper.error("effect not found", "resource", id.toString());
        CompoundTag root = NbtIo.readCompressed(file.toFile());
        ListTag objects = root.getCompound("fx").getCompound("mainFX").getList("fxObjects", Tag.TAG_COMPOUND);
        JsonArray result = new JsonArray();
        for (int i = 0; i < objects.size(); i++) {
            CompoundTag object = objects.getCompound(i);
            JsonObject details = parameters(object, object.getCompound("config"));
            CompoundTag position = object.getCompound("transform").getCompound("localPosition");
            for (String axis : List.of("x", "y", "z")) details.addProperty(axis, position.getFloat(axis));
            result.add(details);
        }
        return JsonHelper.builder().put("ok", true).put("resource", id.toString())
                .put("count", objects.size()).putRaw("emitters", result.toString()).build();
    }

    private static String clearFxCache() throws Exception {
        int removed = (int) Class.forName("com.lowdragmc.photon.client.fx.FXHelper")
                .getMethod("clearCache").invoke(null);
        return JsonHelper.builder().put("ok", true).put("removed", removed).build();
    }

    static void writeAndReload(Minecraft mc, Path target, CompoundTag root) throws Exception {
        Files.createDirectories(target.getParent());
        Path temp = target.resolveSibling(target.getFileName() + ".tmp");
        try {
            NbtIo.writeCompressed(root, temp.toFile());
            try { Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally { Files.deleteIfExists(temp); }
        reloadGenerated(mc);
    }

    static void reloadGenerated(Minecraft mc) throws Exception {
        ensurePackMetadata(mc);
        beginPackReload(mc);
    }

    static void applyParameters(CompoundTag object, CompoundTag config, Map<String, String> p, boolean fresh) {
        if (fresh) object.putString("name", p.getOrDefault("name", "MCP " + object.getString("name")));
        if (p.containsKey("name")) {
            String name = p.get("name").trim();
            if (name.isEmpty() || name.length() > 64 || name.chars().anyMatch(Character::isISOControl))
                throw new IllegalArgumentException("name must be 1-64 printable characters");
            object.putString("name", name);
        }
        if (p.containsKey("color")) config.getCompound("startColor").putInt("number", parseColor(p.get("color")));
        if (p.containsKey("size")) {
            float size = floatValue(p, "size", 0.01f, 8.0f);
            CompoundTag sizeTag = config.getCompound("startSize");
            for (String axis : List.of("x", "y", "z")) sizeTag.getCompound(axis).putFloat("number", size);
        }
        if (p.containsKey("lifetime")) config.getCompound("startLifetime").putInt("number", intValue(p, "lifetime", 1, 1200));
        if (p.containsKey("speed")) config.getCompound("startSpeed").putInt("number", intValue(p, "speed", 0, 32));
        if (p.containsKey("rate")) config.getCompound("emission").getCompound("emissionRate")
                .putFloat("number", floatValue(p, "rate", 0.0f, 1000.0f));
        if (p.containsKey("duration")) config.putInt("duration", intValue(p, "duration", 1, 1200));
        if (p.containsKey("maxParticles")) config.putInt("maxParticles", intValue(p, "maxParticles", 1, 10000));
        if (p.containsKey("looping")) config.putByte("looping", (byte) (boolValue(p, "looping") ? 1 : 0));
    }

    private static String inspect(Minecraft mc, Map<String, String> p) throws Exception {
        ResourceLocation id = requiredId(p);
        Path file = effectPath(mc, id);
        if (!Files.isRegularFile(file)) return JsonHelper.error("effect not found", "resource", id.toString());
        CompoundTag root = NbtIo.readCompressed(file.toFile());
        CompoundTag object = particle(root);
        CompoundTag config = object.getCompound("config");
        return JsonHelper.builder().put("ok", true).put("resource", id.toString()).put("name", object.getString("name"))
                .put("file", file.toString()).putRaw("parameters", parameters(object, config).toString()).build();
    }

    private static String list(Minecraft mc) throws Exception {
        Path root = packRoot(mc).resolve("assets");
        List<String> effects = new ArrayList<>();
        if (Files.isDirectory(root)) {
            try (Stream<Path> paths = Files.walk(root)) {
                paths.filter(Files::isRegularFile).filter(path -> path.toString().endsWith(".fx"))
                        .sorted(Comparator.naturalOrder()).forEach(path -> {
                            Path relative = root.relativize(path);
                            if (relative.getNameCount() >= 3 && relative.getName(1).toString().equals("fx")) {
                                String namespace = relative.getName(0).toString();
                                String effect = relative.subpath(2, relative.getNameCount()).toString()
                                        .replace(File.separatorChar, '/').replaceFirst("\\.fx$", "");
                                effects.add(namespace + ":" + effect);
                            }
                        });
            }
        }
        return JsonHelper.builder().put("ok", true).put("pack", PACK_ID).put("reload", reloadStatus)
                .putRaw("effects", new com.google.gson.Gson().toJson(effects))
                .build();
    }

    private static String status(Minecraft mc) {
        return JsonHelper.builder().put("ok", true).put("pack", PACK_ID).put("reload", reloadStatus)
                .put("selected", mc.getResourcePackRepository().getSelectedIds().contains(PACK_ID))
                .put("screen", mc.screen == null ? "" : mc.screen.getClass().getName()).build();
    }

    private static String play(Minecraft mc, Map<String, String> p) {
        ResourceLocation id = requiredId(p);
        if (!Files.isRegularFile(effectPath(mc, id))) return JsonHelper.error("effect not found", "resource", id.toString());
        if (reloadStatus.equals("running")) return JsonHelper.error("resource reload is still running; check photon_status");
        if (!reloadStatus.equals("complete") || !mc.getResourcePackRepository().getSelectedIds().contains(PACK_ID))
            return JsonHelper.error("generated resource pack is not active; run photon_create_fx or photon_update_fx first");
        if (mc.player == null || mc.level == null) return JsonHelper.error("no active world");
        String player = p.getOrDefault("player", mc.player.getGameProfile().getName()).trim();
        if (!player.matches("[A-Za-z0-9_]{1,16}")) return JsonHelper.error("player must be a Minecraft username");
        String command = "/photon fx " + id + " entity @a[name=" + player + "]";
        return HelperPlayerManager.executeAsHelper(mc, command);
    }

    private static void ensurePackMetadata(Minecraft mc) throws Exception {
        Path root = packRoot(mc);
        Files.createDirectories(root);
        Path metadata = root.resolve("pack.mcmeta");
        if (!Files.exists(metadata)) Files.writeString(metadata,
                "{\"pack\":{\"pack_format\":15,\"description\":\"MCP-HELPER generated Photon effects\"}}\n");
    }

    private static void beginPackReload(Minecraft mc) {
        try {
            PackRepository repository = mc.getResourcePackRepository();
            repository.reload();
            if (!repository.getAvailableIds().contains(PACK_ID)) {
                reloadStatus = "failed: generated pack was not discovered";
                return;
            }
            List<String> selected = new ArrayList<>(repository.getSelectedIds());
            if (!selected.contains(PACK_ID)) selected.add(PACK_ID);
            repository.setSelected(selected);
            reloadStatus = "running";
            mc.reloadResourcePacks().whenComplete((unused, error) ->
                    reloadStatus = error == null ? "complete" : "failed: " + error.getMessage());
        } catch (Throwable e) {
            reloadStatus = "failed: " + e.getMessage();
        }
    }

    private static CompoundTag particle(CompoundTag root) {
        CompoundTag fx = root.getCompound("fx");
        CompoundTag main = fx.getCompound("mainFX");
        ListTag objects = main.getList("fxObjects", Tag.TAG_COMPOUND);
        if (objects.isEmpty()) throw new IllegalStateException("Photon template has no emitter");
        CompoundTag object = objects.getCompound(0);
        if (!object.contains("config", Tag.TAG_COMPOUND)) throw new IllegalStateException("Photon template is missing particle config");
        return object;
    }

    private static void validateTemplate(CompoundTag root) {
        if (!root.contains("fx", Tag.TAG_COMPOUND) || !root.contains("_version", Tag.TAG_INT))
            throw new IllegalStateException("Photon template root does not match the expected exported format");
        CompoundTag config = particle(root).getCompound("config");
        if (!config.contains("emission", Tag.TAG_COMPOUND) || !config.contains("startSize", Tag.TAG_COMPOUND)
                || !config.contains("startLifetime", Tag.TAG_COMPOUND) || !config.contains("startColor", Tag.TAG_COMPOUND))
            throw new IllegalStateException("Photon template is missing required particle fields");
    }

    private static JsonObject parameters(CompoundTag object, CompoundTag config) {
        JsonObject out = new JsonObject();
        out.addProperty("color", String.format("#%08X", config.getCompound("startColor").getInt("number")));
        out.addProperty("size", config.getCompound("startSize").getCompound("x").getFloat("number"));
        out.addProperty("lifetime", config.getCompound("startLifetime").getInt("number"));
        out.addProperty("speed", config.getCompound("startSpeed").getInt("number"));
        out.addProperty("rate", config.getCompound("emission").getCompound("emissionRate").getFloat("number"));
        out.addProperty("duration", config.getInt("duration"));
        out.addProperty("looping", config.getByte("looping") != 0);
        out.addProperty("maxParticles", config.getInt("maxParticles"));
        out.addProperty("name", object.getString("name"));
        return out;
    }

    static ResourceLocation requiredId(Map<String, String> p) {
        String raw = p.get("id");
        if (raw == null) throw new IllegalArgumentException("missing id (namespace:path)");
        ResourceLocation id = ResourceLocation.tryParse(raw.trim());
        if (id == null || !SAFE_NAMESPACE.matcher(id.getNamespace()).matches() || !SAFE_PATH.matcher(id.getPath()).matches()
                || id.getPath().startsWith("/") || id.getPath().endsWith("/") || id.getPath().contains(".."))
            throw new IllegalArgumentException("invalid resource id; use a safe namespace:path");
        return id;
    }

    private static Path packRoot(Minecraft mc) { return mc.gameDirectory.toPath().resolve("resourcepacks").resolve(PACK_NAME); }
    static Path effectPath(Minecraft mc, ResourceLocation id) {
        return packRoot(mc).resolve("assets").resolve(id.getNamespace()).resolve("fx").resolve(id.getPath() + ".fx");
    }

    private static int parseColor(String raw) {
        String value = raw.trim();
        if (value.startsWith("#")) value = value.substring(1);
        if (!value.matches("[0-9a-fA-F]{6}|[0-9a-fA-F]{8}"))
            throw new IllegalArgumentException("color must be #RRGGBB or #AARRGGBB");
        long number = Long.parseLong(value, 16);
        if (value.length() == 6) number |= 0xFF000000L;
        return (int) number;
    }

    private static int intValue(Map<String, String> p, String key, int min, int max) {
        int value;
        try { value = Integer.parseInt(p.get(key).trim()); }
        catch (Exception e) { throw new IllegalArgumentException(key + " must be an integer"); }
        if (value < min || value > max) throw new IllegalArgumentException(key + " must be in " + min + ".." + max);
        return value;
    }

    private static float floatValue(Map<String, String> p, String key, float min, float max) {
        float value;
        try { value = Float.parseFloat(p.get(key).trim()); }
        catch (Exception e) { throw new IllegalArgumentException(key + " must be a number"); }
        if (!Float.isFinite(value) || value < min || value > max)
            throw new IllegalArgumentException(key + " must be finite and in " + min + ".." + max);
        return value;
    }

    private static boolean boolValue(Map<String, String> p, String key) {
        String value = p.get(key).trim().toLowerCase(java.util.Locale.ROOT);
        if (value.equals("true") || value.equals("1")) return true;
        if (value.equals("false") || value.equals("0")) return false;
        throw new IllegalArgumentException(key + " must be true or false");
    }
}
