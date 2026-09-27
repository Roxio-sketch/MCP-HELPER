package xyz.langyo.minecraft.mcp.common;

import com.google.gson.*;
import net.minecraft.client.Minecraft;
import net.minecraft.nbt.*;
import net.minecraft.resources.ResourceLocation;
import javax.imageio.ImageIO;
import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;

/** Managed PNG assets and validated runtime-file restoration. */
public final class PhotonAssetBridge {
    public static boolean supports(String name) {
        return Set.of("photon_import_texture", "photon_bind_texture", "photon_restore_fx").contains(name);
    }

    public static String dispatch(String name, Map<String,String> p) {
        CompletableFuture<String> future = new CompletableFuture<>();
        Minecraft.getInstance().execute(() -> {
            try { future.complete(execute(name, p).toString()); }
            catch (Exception e) { future.complete(JsonHelper.error(e.toString())); }
        });
        try { return future.get(12, TimeUnit.SECONDS); }
        catch (Exception e) { return JsonHelper.error(e.toString()); }
    }

    static String sha(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    static Path assetPath(ResourceLocation id) {
        return Minecraft.getInstance().gameDirectory.toPath().resolve("resourcepacks/mcpmod-generated-vfx/assets")
                .resolve(id.getNamespace()).resolve("textures/mcp").resolve(id.getPath() + ".png");
    }

    static ResourceLocation textureId(Map<String,String> p) {
        return PhotonAutomationBridge.requiredId(Map.of("id", p.getOrDefault("texture", "")));
    }

    static void checkMode(Path path, Map<String,String> p) throws Exception {
        String mode = p.getOrDefault("mode", "create");
        if (!Set.of("create", "replace").contains(mode)) throw new IllegalArgumentException("mode must be create or replace");
        if (mode.equals("create") && Files.exists(path)) throw new IllegalArgumentException("Destination exists; use explicit replace");
        if (mode.equals("replace") && !Files.isRegularFile(path)) throw new IllegalArgumentException("Destination does not exist");
        if (p.containsKey("expectedSha256") && (!Files.isRegularFile(path)
                || !sha(Files.readAllBytes(path)).equals(p.get("expectedSha256"))))
            throw new IllegalStateException("Destination changed since readback");
    }

    static void atomic(Path path, byte[] bytes) throws Exception {
        Files.createDirectories(path.getParent());
        Path temp = Files.createTempFile(path.getParent(), "mcp-", ".tmp");
        try {
            Files.write(temp, bytes);
            try { Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException e) { Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temp); }
    }

    private static JsonObject execute(String name, Map<String,String> p) throws Exception {
        Minecraft mc = Minecraft.getInstance();
        JsonObject out = new JsonObject(); out.addProperty("ok", true);
        if (name.equals("photon_import_texture")) {
            ResourceLocation id = PhotonAutomationBridge.requiredId(p);
            Path path = assetPath(id); checkMode(path, p);
            String encoded = p.getOrDefault("png", "");
            if (encoded.length() > 5592408) throw new IllegalArgumentException("PNG exceeds 4 MiB");
            byte[] bytes = Base64.getDecoder().decode(encoded);
            if (bytes.length < 24 || bytes.length > 4194304 || bytes[0] != (byte)137 || bytes[1] != 80 || bytes[2] != 78 || bytes[3] != 71)
                throw new IllegalArgumentException("Expected a PNG under 4 MiB");
            try (var input = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
                var readers = ImageIO.getImageReaders(input);
                if (!readers.hasNext()) throw new IllegalArgumentException("Invalid PNG");
                var reader = readers.next();
                try {
                    reader.setInput(input);
                    int width = reader.getWidth(0), height = reader.getHeight(0);
                    if (width < 1 || height < 1 || width > 2048 || height > 2048) throw new IllegalArgumentException("Texture dimensions must be 1..2048");
                    var image = reader.read(0);
                    if (!image.getColorModel().hasAlpha()) throw new IllegalArgumentException("Normalize to RGBA before import");
                    out.addProperty("width", width); out.addProperty("height", height);
                } finally { reader.dispose(); }
            }
            if (Files.exists(path)) backup(path);
            atomic(path, bytes);
            PhotonAutomationBridge.reloadGenerated(mc);
            out.addProperty("texture", id.getNamespace() + ":textures/mcp/" + id.getPath() + ".png");
            out.addProperty("file", path.toString()); out.addProperty("sha256", sha(bytes));
            out.addProperty("reload", "started"); return out;
        }
        ResourceLocation id = PhotonAutomationBridge.requiredId(p);
        Path target = PhotonAutomationBridge.effectPath(mc, id);
        CompoundTag root;
        if (name.equals("photon_restore_fx")) {
            checkMode(target, p);
            String snbt = p.getOrDefault("snbt", "");
            if (snbt.length() > 2097152) throw new IllegalArgumentException("FX SNBT exceeds 2 MiB");
            root = TagParser.parseTag(snbt);
        } else {
            root = NbtIo.readCompressed(target.toFile());
            ListTag objects = graph(root, p).getList("fxObjects", Tag.TAG_COMPOUND);
            int index = Integer.parseInt(p.getOrDefault("index", "0"));
            if (index < 0 || index >= objects.size()) throw new IllegalArgumentException("Emitter index out of bounds");
            bind(objects.getCompound(index), p);
        }
        PhotonAdvancedBridge.validate(root);
        PhotonAdvancedBridge.nativeFx(root, id);
        if (Files.exists(target)) out.addProperty("backup", backup(target).toString());
        PhotonAutomationBridge.writeAndReload(mc, target, root);
        out.addProperty("resource", id.toString()); out.addProperty("file", target.toString());
        out.addProperty("sha256", sha(Files.readAllBytes(target))); out.addProperty("reload", "started");
        return out;
    }

    static Path backup(Path file) throws Exception {
        Path folder = Minecraft.getInstance().gameDirectory.toPath().resolve("ldlib/mcp-backups/assets");
        Files.createDirectories(folder);
        Path target = folder.resolve(UUID.randomUUID() + "-" + file.getFileName());
        Files.copy(file, target); return target;
    }

    static CompoundTag graph(CompoundTag root, Map<String,String> p) {
        String graph = p.getOrDefault("graph", "main");
        CompoundTag fx = root.getCompound("fx");
        if (graph.equals("main")) return fx.getCompound("mainFX");
        if (!fx.getCompound("subFXs").contains(graph)) throw new IllegalArgumentException("Unknown graph");
        return fx.getCompound("subFXs").getCompound(graph);
    }

    static void bind(CompoundTag object, Map<String,String> p) throws Exception {
        ResourceLocation id = textureId(p);
        if (!Files.isRegularFile(assetPath(id))) throw new IllegalArgumentException("Import the texture first");
        CompoundTag config = object.getCompound("config");
        if (!config.contains("material")) throw new IllegalArgumentException("This emitter has no material");
        CompoundTag material = new CompoundTag();
        material.putString("_type", "TextureMaterial");
        material.putString("texture", id.getNamespace() + ":textures/mcp/" + id.getPath() + ".png");
        material.putFloat("discardThreshold", 0.001f);
        config.getCompound("material").put("material", material);
        int columns = Integer.parseInt(p.getOrDefault("columns", "1")), rows = Integer.parseInt(p.getOrDefault("rows", "1"));
        int frames = Integer.parseInt(p.getOrDefault("frames", "1"));
        if (columns < 1 || rows < 1 || columns > 32 || rows > 32 || frames < 1 || frames > columns * rows || frames > 256)
            throw new IllegalArgumentException("Invalid atlas layout");
        var image = ImageIO.read(assetPath(id).toFile());
        if (image.getWidth() % columns != 0 || image.getHeight() % rows != 0)
            throw new IllegalArgumentException("Atlas dimensions must be divisible by its grid");
        if (frames > 1 && !object.getString("_type").equals("particle")) throw new IllegalArgumentException("Animated atlas requires a particle emitter");
        if (object.getString("_type").equals("particle")) {
            // Photon interprets frameOverTime as an absolute frame index over normalized particle age.
            CompoundTag uv = config.getCompound("uvAnimation");
            uv.putBoolean("enable", frames > 1); uv.putString("animation", "WholeSheet");
            CompoundTag tiles = new CompoundTag(); tiles.putInt("a", columns); tiles.putInt("b", rows); uv.put("tiles", tiles);
            uv.putFloat("cycle", 1);
            uv.put("startFrame", TagParser.parseTag("{_type:\"Constant\",number:0}"));
            uv.put("frameOverTime", TagParser.parseTag("{_type:\"Curve\",min:0.0f,max:256.0f,lower:0.0f,upper:" + (frames - 0.001f)
                    + "f,defaultValue:0.0f,curves:[[0.0f,0.0f,0.333333f,0.333333f,0.666667f,0.666667f,1.0f,1.0f]],xAxis:\"age\",yAxis:\"frame\",lockControlPoint:1b}"));
            config.put("uvAnimation", uv);
        }
    }
}
