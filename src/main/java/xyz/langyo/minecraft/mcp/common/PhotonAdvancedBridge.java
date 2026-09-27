package xyz.langyo.minecraft.mcp.common;

import com.google.gson.*;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import org.joml.Vector3f;

import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Native Photon 1.1.17 serialization and client-owned runtime handles. */
public final class PhotonAdvancedBridge {
    private static final String PREFIX = "com.lowdragmc.photon.client.";
    private static final Map<String, String> TYPES = Map.of(
            "particle", "gameobject.emitter.particle.ParticleEmitter",
            "trail", "gameobject.emitter.trail.TrailEmitter",
            "beam", "gameobject.emitter.beam.BeamEmitter",
            "empty", "gameobject.EmptyFXObject");
    private static final Map<String, Run> RUNS = new LinkedHashMap<>();
    private static final int MAX_RUNS = 64;
    private static long tick;

    private static final class Run {
        String handle, id, state = "playing", error = "";
        Object effect, runtime;
        Level level;
        Vector3f position, velocity;
        long started;
        int lifetime;
        long renderCallbacks;
        final Set<Object> observed = Collections.newSetFromMap(new IdentityHashMap<>());
    }

    private PhotonAdvancedBridge() {}

    public static boolean supports(String method) {
        return Set.of("photon_emitter_template", "photon_write_fx", "photon_read_fx", "photon_diagnose_fx",
                "photon_start_fx", "photon_runtime_status", "photon_stop_fx", "photon_move_fx", "photon_errors").contains(method);
    }

    public static boolean readOnly(String method) {
        return Set.of("photon_emitter_template", "photon_read_fx", "photon_diagnose_fx", "photon_runtime_status", "photon_errors").contains(method);
    }

    public static String dispatch(String method, Map<String, String> p) {
        CompletableFuture<String> response = new CompletableFuture<>();
        Minecraft.getInstance().execute(() -> {
            String stage = "request";
            try {
                JsonObject result;
                switch (method) {
                    case "photon_errors" -> result = PhotonDiagnostics.read(Long.parseLong(p.getOrDefault("after", "0")));
                    case "photon_emitter_template" -> {
                        stage = "native_template";
                        result = ok();
                        result.addProperty("snbt", template(p.getOrDefault("type", "particle")).toString());
                    }
                    case "photon_write_fx" -> { stage = "authoring"; result = write(p); }
                    case "photon_read_fx" -> { stage = "resource_read"; result = read(p); }
                    case "photon_diagnose_fx" -> { result = diagnose(p); }
                    case "photon_start_fx" -> { stage = "runtime_start"; result = start(p); }
                    case "photon_runtime_status" -> { stage = "runtime_status"; result = status(p); }
                    case "photon_stop_fx" -> { stage = "runtime_stop"; result = stop(p); }
                    case "photon_move_fx" -> { stage = "runtime_move"; result = move(p); }
                    default -> throw new IllegalArgumentException("Unknown operation");
                }
                response.complete(result.toString());
            } catch (Throwable error) { response.complete(failure(stage, error).toString()); }
        });
        try { return response.get(12, TimeUnit.SECONDS); }
        catch (Exception error) { return failure("dispatch", error).toString(); }
    }

    static Object invoke(Object target, String name, Object... args) throws Exception {
        if (name.equals("isAlive") && args.length == 0 && target instanceof net.minecraft.client.particle.Particle particle)
            return particle.isAlive();
        Class<?> type = target instanceof Class<?> c ? c : target.getClass();
        for (Method method : type.getMethods()) {
            if (!method.getName().equals(name) || method.getParameterCount() != args.length || method.isBridge()) continue;
            Class<?>[] types = method.getParameterTypes();
            boolean matches = true;
            for (int i = 0; i < types.length; i++) {
                Class<?> boxed = types[i] == boolean.class ? Boolean.class : types[i] == int.class ? Integer.class :
                        types[i] == float.class ? Float.class : types[i];
                if (args[i] != null && !boxed.isInstance(args[i])) { matches = false; break; }
            }
            if (matches) return method.invoke(target instanceof Class<?> ? null : target, args);
        }
        throw new NoSuchMethodException(type.getName() + "." + name);
    }

    private static Class<?> photon(String name) throws ClassNotFoundException { return Class.forName(PREFIX + name); }

    private static CompoundTag template(String type) throws Exception {
        String name = TYPES.get(type);
        if (name == null) throw new IllegalArgumentException("type must be particle, trail, beam or empty");
        Object object = photon(name).getConstructor().newInstance();
        CompoundTag tag = (CompoundTag) invoke(object, "serializeNBT");
        tag.putString("_type", type);
        return tag;
    }

    private static ListTag emitters(JsonArray array) throws Exception {
        if (array.isEmpty() || array.size() > 32) throw new IllegalArgumentException("Each graph needs 1..32 emitters");
        ListTag result = new ListTag();
        for (JsonElement element : array) {
            JsonObject spec = element.getAsJsonObject();
            for (String key : spec.keySet()) {
                if (!Set.of("type", "name", "x", "y", "z", "config", "color", "size", "lifetime", "speed",
                        "rate", "duration", "looping", "maxParticles").contains(key))
                    throw new IllegalArgumentException("Unknown emitter field: " + key);
            }
            String type = spec.has("type") ? spec.get("type").getAsString() : "particle";
            CompoundTag object = template(type);
            if (spec.has("name")) object.putString("name", spec.get("name").getAsString());
            CompoundTag config = object.getCompound("config");
            if (spec.has("config")) {
                CompoundTag patch = TagParser.parseTag(spec.get("config").getAsString());
                for (String key : patch.getAllKeys()) if (!config.contains(key))
                    throw new IllegalArgumentException("Unknown " + type + " config field: " + key);
                config.merge(patch);
            }
            if (type.equals("particle")) {
                Map<String, String> scalars = new LinkedHashMap<>();
                for (String key : List.of("color", "size", "lifetime", "speed", "rate", "duration", "looping", "maxParticles"))
                    if (spec.has(key)) scalars.put(key, spec.get(key).getAsString());
                PhotonAutomationBridge.applyParameters(object, config, scalars, false);
            } else {
                for (String key : List.of("color", "size", "lifetime", "speed", "rate", "duration", "looping", "maxParticles"))
                    if (spec.has(key)) throw new IllegalArgumentException("Use native config SNBT for " + type + ": " + key);
            }
            CompoundTag position = object.getCompound("transform").getCompound("localPosition");
            for (String axis : List.of("x", "y", "z")) if (spec.has(axis)) {
                float value = spec.get(axis).getAsFloat();
                if (!Float.isFinite(value) || Math.abs(value) > 32) throw new IllegalArgumentException("Emitter offset outside -32..32");
                position.putFloat(axis, value);
            }
            result.add(object);
        }
        return result;
    }

    private static JsonObject write(Map<String, String> p) throws Exception {
        Minecraft mc = Minecraft.getInstance();
        ResourceLocation id = PhotonAutomationBridge.requiredId(p);
        Path file = PhotonAutomationBridge.effectPath(mc, id);
        String mode = p.getOrDefault("mode", "create");
        if (!Set.of("create", "replace").contains(mode)) throw new IllegalArgumentException("mode must be create or replace");
        if (mode.equals("create") && Files.exists(file)) throw new IllegalArgumentException("ID exists; explicit replace required");
        if (mode.equals("replace") && !Files.isRegularFile(file)) throw new IllegalArgumentException("ID not found");
        String raw = p.getOrDefault("document", "");
        if (raw.length() > 262144) throw new IllegalArgumentException("Document exceeds 256 KiB");
        JsonObject document = JsonParser.parseString(raw).getAsJsonObject();
        for (String key : document.keySet()) if (!Set.of("emitters", "subFXs").contains(key))
            throw new IllegalArgumentException("Unknown document field: " + key);
        CompoundTag root = new CompoundTag(), fx = new CompoundTag(), main = new CompoundTag(), subs = new CompoundTag();
        main.put("fxObjects", emitters(document.getAsJsonArray("emitters")));
        if (document.has("subFXs")) {
            JsonObject subSpecs = document.getAsJsonObject("subFXs");
            if (subSpecs.size() > 16) throw new IllegalArgumentException("At most 16 sub effects");
            for (String key : subSpecs.keySet()) {
                if (!key.matches("[a-zA-Z0-9_-]{1,64}")) throw new IllegalArgumentException("Invalid sub effect name");
                CompoundTag sub = new CompoundTag();
                sub.put("fxObjects", emitters(subSpecs.getAsJsonArray(key)));
                subs.put(key, sub);
            }
        }
        fx.put("mainFX", main); fx.put("subFXs", subs);
        root.put("fx", fx); root.putInt("_version", 1);
        validate(root);
        nativeFx(root, id);
        PhotonAutomationBridge.writeAndReload(mc, file, root);
        JsonObject out = ok(); out.addProperty("resource", id.toString());
        out.addProperty("file", file.toString()); out.addProperty("reload", "started");
        out.addProperty("emitterCount", main.getList("fxObjects", Tag.TAG_COMPOUND).size());
        return out;
    }

    static void validate(CompoundTag root) {
        CompoundTag fx = root.getCompound("fx");
        if (!root.contains("fx", Tag.TAG_COMPOUND) || root.getInt("_version") != 1)
            throw new IllegalArgumentException("Expected Photon exported version 1 root with fx");
        Map<String, CompoundTag> graphs = new LinkedHashMap<>();
        graphs.put("$main", fx.getCompound("mainFX"));
        CompoundTag subs = fx.getCompound("subFXs");
        for (String key : subs.getAllKeys()) graphs.put(key, subs.getCompound(key));
        Map<String, Set<String>> edges = new HashMap<>();
        for (var entry : graphs.entrySet()) {
            ListTag objects = entry.getValue().getList("fxObjects", Tag.TAG_COMPOUND);
            if (objects.isEmpty() || objects.size() > 32) throw new IllegalArgumentException("Graph must have 1..32 objects: " + entry.getKey());
            Set<String> references = new HashSet<>();
            for (Tag value : objects) {
                CompoundTag object = (CompoundTag) value;
                String type = object.getString("_type");
                if (!TYPES.containsKey(type)) throw new IllegalArgumentException("Unknown emitter type " + type);
                CompoundTag c = object.getCompound("config");
                if (type.equals("particle") && !Set.of("Local", "World").contains(c.getString("simulationSpace")))
                    throw new IllegalArgumentException("simulationSpace must be Local or World (case sensitive)");
                if (!type.equals("empty")) {
                    if (c.getInt("duration") < 1 || c.getInt("duration") > 1200) throw new IllegalArgumentException("duration outside 1..1200");
                    if (type.equals("particle") && (c.getInt("maxParticles") < 1 || c.getInt("maxParticles") > 10000))
                        throw new IllegalArgumentException("maxParticles outside 1..10000");
                }
                CompoundTag se = c.getCompound("subEmitters");
                if (se.getBoolean("enable")) for (Tag item : se.getList("emitters", Tag.TAG_COMPOUND)) {
                    String ref = ((CompoundTag) item).getString("emitter");
                    if (!subs.contains(ref)) throw new IllegalArgumentException("Missing sub effect: " + ref);
                    references.add(ref);
                }
            }
            edges.put(entry.getKey(), references);
        }
        for (String node : edges.keySet()) checkCycle(node, edges, new HashSet<>());
    }

    private static void checkCycle(String node, Map<String, Set<String>> edges, Set<String> path) {
        if (!path.add(node)) throw new IllegalArgumentException("Recursive sub emitter cycle: " + node);
        for (String next : edges.getOrDefault(node, Set.of())) checkCycle(next, edges, path);
        path.remove(node);
    }

    static Object nativeFx(CompoundTag root, ResourceLocation id) throws Exception {
        Object fx = photon("fx.FX").getConstructor().newInstance();
        invoke(fx, "setFxLocation", id);
        invoke(fx, "deserializeNBT", root.getCompound("fx"));
        int expected = root.getCompound("fx").getCompound("mainFX").getList("fxObjects", Tag.TAG_COMPOUND).size();
        Collection<?> objects = (Collection<?>) invoke(invoke(fx, "getMainFX"), "objects");
        if (objects.size() != expected) throw new IllegalArgumentException("Photon silently discarded an emitter");
        return fx;
    }

    private static CompoundTag load(ResourceLocation id, boolean disk) throws Exception {
        Minecraft mc = Minecraft.getInstance();
        if (disk) return NbtIo.readCompressed(PhotonAutomationBridge.effectPath(mc, id).toFile());
        ResourceLocation resource = ResourceLocation.fromNamespaceAndPath(id.getNamespace(), "fx/" + id.getPath() + ".fx");
        try (var stream = mc.getResourceManager().getResourceOrThrow(resource).open()) { return NbtIo.readCompressed(stream); }
    }

    private static JsonObject read(Map<String, String> p) throws Exception {
        ResourceLocation id = PhotonAutomationBridge.requiredId(p);
        CompoundTag root = load(id, true);
        JsonObject out = ok(); out.addProperty("resource", id.toString()); out.addProperty("snbt", root.toString());
        return out;
    }

    private static JsonObject diagnose(Map<String, String> p) {
        String stage = "resource_discovery";
        CompoundTag root = null;
        ResourceLocation id = null;
        try {
            id = PhotonAutomationBridge.requiredId(p);
            root = load(id, false);
            stage = "schema_validation"; validate(root);
            stage = "deserialization"; Object fx = nativeFx(root, id);
            stage = "material_preflight"; materials(fx);
            stage = "runtime_creation"; Object runtime = invoke(fx, "createRuntime");
            JsonObject out = ok(); out.addProperty("resource", id.toString());
            out.addProperty("stage", "runtime_created");
            out.addProperty("objects", ((Map<?, ?>) invoke(runtime, "getObjects")).size());
            out.addProperty("renderVerified", false);
            invoke(runtime, "destroy", true);
            return out;
        } catch (Throwable error) {
            JsonObject out = failure(stage, error);
            if (stage.equals("schema_validation") && root != null) {
                try { out.addProperty("nativeReadback", ((CompoundTag) invoke(nativeFx(root, id), "serializeNBT")).toString()); }
                catch (Throwable nativeError) { out.addProperty("nativeError", cause(nativeError).toString()); }
            }
            return out;
        }
    }

    private static JsonObject start(Map<String, String> p) throws Exception {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) throw new IllegalStateException("No active client world");
        RUNS.entrySet().removeIf(entry -> entry.getValue().state.equals("stopped") || entry.getValue().state.equals("finished"));
        if (RUNS.size() >= MAX_RUNS) throw new IllegalStateException("At most 64 managed runtimes");
        ResourceLocation id = PhotonAutomationBridge.requiredId(p);
        CompoundTag root = load(id, false); validate(root);
        Object fx = nativeFx(root, id);
        materials(fx);
        Vector3f position = vector(p, "", new Vector3f((float) mc.player.getX(), (float) mc.player.getY(), (float) mc.player.getZ()), 30000000);
        Object runtime = null;
        Run run = new Run(); run.handle = UUID.randomUUID().toString(); run.id = id.toString();
        run.level = mc.level; run.position = position;
        run.velocity = vector(p, "v", new Vector3f(), 1); run.started = tick;
        run.lifetime = Integer.parseInt(p.getOrDefault("maxTicks", "1200"));
        if (run.lifetime < 1 || run.lifetime > 12000) throw new IllegalArgumentException("maxTicks outside 1..12000");
        Class<?> contract = photon("fx.IEffect");
        Object effect = Proxy.newProxyInstance(contract.getClassLoader(), new Class<?>[]{contract}, (proxy, method, args) -> {
            return switch (method.getName()) {
                case "getLevel" -> run.level;
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                case "toString" -> "MCP Photon " + run.handle;
                case "updateFXObjectTick", "updateFXObjectFrame" -> {
                    Object object = args[0];
                    run.observed.add(object);
                    if (!run.state.equals("playing")) invoke(object, "remove", true);
                    if (method.getName().equals("updateFXObjectFrame")) run.renderCallbacks++;
                    yield null;
                }
                default -> null;
            };
        });
        try {
            runtime = invoke(fx, "createRuntime");
            run.effect = effect; run.runtime = runtime;
            run.observed.addAll(((Map<?, ?>) invoke(runtime, "getObjects")).values());
            invoke(invoke(runtime, "getRoot"), "updatePos", position);
            invoke(runtime, "emmit", effect);
            RUNS.put(run.handle, run);
            return describe(run);
        } catch (Exception e) {
            RUNS.remove(run.handle);
            if (runtime != null) terminate(run);
            throw e;
        }
    }

    private static Vector3f vector(Map<String, String> p, String prefix, Vector3f fallback, float max) {
        float[] values = {fallback.x, fallback.y, fallback.z};
        String[] axes = {"x", "y", "z"};
        for (int i = 0; i < 3; i++) if (p.containsKey(prefix + axes[i])) values[i] = Float.parseFloat(p.get(prefix + axes[i]));
        for (float value : values) if (!Float.isFinite(value) || Math.abs(value) > max) throw new IllegalArgumentException("Invalid position/velocity");
        return new Vector3f(values[0], values[1], values[2]);
    }

    private static void materials(Object fx) throws Exception {
        List<Object> graphs = new ArrayList<>(); graphs.add(invoke(fx, "getMainFX"));
        graphs.addAll(((Map<?, ?>) invoke(fx, "getSubFXs")).values());
        for (Object graph : graphs) for (Object object : (Collection<?>) invoke(graph, "objects")) {
            if (object.getClass().getSimpleName().equals("EmptyFXObject")) continue;
            Object config;
            try { config = object.getClass().getField("config").get(object); }
            catch (NoSuchFieldException e) { config = invoke(object, "getConfig"); }
            Object setting = config.getClass().getField("material").get(config);
            Object material = invoke(setting, "getMaterial");
            CompoundTag tag = (CompoundTag) invoke(material, "serializeNBT");
            if (tag.contains("texture")) {
                ResourceLocation texture = ResourceLocation.tryParse(tag.getString("texture"));
                if (texture == null || Minecraft.getInstance().getResourceManager().getResource(texture).isEmpty())
                    throw new IllegalArgumentException("Missing material texture: " + tag.getString("texture"));
            }
            try {
                Object shader = invoke(material, "getShader");
                if (shader == null) throw new IllegalStateException("Material shader is unavailable");
                if (material.getClass().getSimpleName().equals("CustomShaderMaterial") && (boolean) invoke(material, "isCompiledError")) {
                    Field error = material.getClass().getDeclaredField("compiledErrorMessage"); error.setAccessible(true);
                    throw new IllegalArgumentException("Shader compilation failed: " + error.get(material));
                }
            } catch (NoSuchMethodException ignored) { }
        }
    }

    private static Run require(Map<String, String> p) {
        Run run = RUNS.get(p.get("handle"));
        if (run == null) throw new IllegalArgumentException("Unknown runtime handle");
        return run;
    }

    private static JsonObject status(Map<String, String> p) throws Exception {
        if (p.containsKey("handle")) return describe(require(p));
        JsonObject out = ok(); JsonArray runs = new JsonArray();
        for (Run run : RUNS.values()) runs.add(describe(run));
        out.add("runtimes", runs); return out;
    }

    private static JsonObject describe(Run run) throws Exception {
        JsonObject out = ok(); out.addProperty("handle", run.handle); out.addProperty("resource", run.id);
        boolean alive = false;
        for (Object object : run.observed) if ((boolean) invoke(object, "isAlive")) alive = true;
        if (!alive && run.state.equals("playing")) run.state = "finished";
        out.addProperty("state", run.state); out.addProperty("alive", alive);
        out.addProperty("scope", "local_client"); out.addProperty("ageTicks", tick - run.started);
        out.addProperty("error", run.error);
        out.addProperty("renderCallbacks", run.renderCallbacks);
        JsonArray emitters = new JsonArray(); int amount = 0;
        for (Object object : run.observed) {
            try {
                int count = ((Number) invoke(object, "getParticleAmount")).intValue(); amount += count;
                JsonObject emitter = new JsonObject(); emitter.addProperty("name", (String) invoke(object, "getName"));
                emitter.addProperty("alive", (boolean) invoke(object, "isAlive")); emitter.addProperty("particles", count);
                emitter.addProperty("type", object.getClass().getSimpleName()); emitters.add(emitter);
            } catch (NoSuchMethodException ignored) { }
        }
        out.add("emitters", emitters); out.addProperty("particleCount", amount);
        out.addProperty("countsAreRenderedPixels", false);
        return out;
    }

    private static JsonObject stop(Map<String, String> p) throws Exception {
        Run run = require(p); terminate(run); return describe(run);
    }

    private static void terminate(Run run) throws Exception {
        run.state = "stopped";
        invoke(run.runtime, "destroy", true);
        for (Object object : run.observed) invoke(object, "remove", true);
    }

    private static JsonObject move(Map<String, String> p) throws Exception {
        Run run = require(p);
        if (!run.state.equals("playing")) throw new IllegalStateException("Runtime is not playing");
        run.position = vector(p, "", run.position, 30000000); run.velocity = vector(p, "v", run.velocity, 1);
        invoke(invoke(run.runtime, "getRoot"), "updatePos", run.position);
        return describe(run);
    }

    public static void tick() {
        tick++;
        Minecraft mc = Minecraft.getInstance();
        for (Run run : RUNS.values()) {
            if (!run.state.equals("playing")) continue;
            try {
                if (mc.level != run.level || tick - run.started >= run.lifetime) {
                    terminate(run);
                } else if (!mc.isPaused()) {
                    run.position.add(run.velocity);
                    invoke(invoke(run.runtime, "getRoot"), "updatePos", run.position);
                    run.observed.removeIf(object -> {
                        try { return !(boolean) invoke(object, "isAlive"); }
                        catch (Exception e) { return false; }
                    });
                    if (run.observed.isEmpty()) run.state = "finished";
                }
            } catch (Throwable error) { run.state = "failed"; run.error = cause(error).toString(); }
        }
    }

    private static JsonObject ok() { JsonObject out = new JsonObject(); out.addProperty("ok", true); return out; }
    private static Throwable cause(Throwable error) {
        while (error.getCause() != null && error.getCause() != error) error = error.getCause();
        return error;
    }
    private static JsonObject failure(String stage, Throwable error) {
        PhotonDiagnostics.record(stage, cause(error).toString(), "");
        JsonObject out = new JsonObject(); out.addProperty("ok", false); out.addProperty("stage", stage);
        out.addProperty("error", cause(error).toString()); return out;
    }
}
