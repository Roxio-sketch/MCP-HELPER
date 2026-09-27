package xyz.langyo.minecraft.mcp.common;

import com.google.gson.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.nbt.*;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.lwjgl.glfw.GLFW;

import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Function;

/** Editor operations preserve projects and use LDLib's validators/responders. */
public final class PhotonEditorBridge {
    private static final String EDITOR = "com.lowdragmc.lowdraglib.gui.editor.ui.Editor";
    private static final String FIELD = "com.lowdragmc.lowdraglib.gui.widget.TextFieldWidget";
    private static String lastBackup = "", lastError = "";

    public static boolean supports(String method) {
        return Set.of("photon_editor_state", "photon_editor_new", "photon_editor_save", "photon_editor_close",
                "photon_editor_set", "photon_editor_fields", "photon_editor_set_text",
                "photon_editor_load", "photon_editor_select", "photon_editor_export",
                "photon_editor_restore", "photon_editor_bind_texture").contains(method);
    }

    public static String dispatch(String method, Map<String, String> p) {
        CompletableFuture<String> result = new CompletableFuture<>();
        Minecraft.getInstance().execute(() -> {
            try { result.complete(execute(method, p).toString()); }
            catch (Throwable e) {
                while (e.getCause() != null) e = e.getCause();
                JsonObject error = new JsonObject(); error.addProperty("ok", false); error.addProperty("error", e.toString());
                result.complete(error.toString());
            }
        });
        try { return result.get(12, TimeUnit.SECONDS); }
        catch (Exception e) { return JsonHelper.error(e.toString()); }
    }

    private static Object call(Object target, String method, Object... args) throws Exception {
        return PhotonAdvancedBridge.invoke(target, method, args);
    }

    private static Object editor() throws Exception {
        Object editor = Class.forName(EDITOR).getField("INSTANCE").get(null);
        if (editor == null || !editor.getClass().getName().equals("com.lowdragmc.photon.gui.editor.FXEditor"))
            throw new IllegalStateException("Photon editor is not open");
        Screen screen = Minecraft.getInstance().screen;
        if (screen == null || !screen.getClass().getName().equals("com.lowdragmc.lowdraglib.gui.modular.ModularUIGuiContainer"))
            throw new IllegalStateException("Photon editor is not the active screen");
        Object ui = screen.getClass().getField("modularUI").get(screen);
        if (!((Collection<?>) call(ui, "getFlatWidgetCollection")).contains(editor)
                && ui.getClass().getField("mainGroup").get(ui) != editor)
            throw new IllegalStateException("Active screen belongs to another editor");
        return editor;
    }

    private static JsonObject execute(String method, Map<String, String> p) throws Exception {
        Object editor = editor(); Object project = call(editor, "getCurrentProject");
        JsonObject out = new JsonObject(); out.addProperty("ok", true);
        if (method.equals("photon_editor_restore")) {
            String snbt = p.getOrDefault("snbt", "");
            if (snbt.length() > 2097152) throw new IllegalArgumentException("Project exceeds 2 MiB");
            CompoundTag root = TagParser.parseTag(snbt);
            PhotonAdvancedBridge.validate(root);
            Object fresh = Class.forName("com.lowdragmc.photon.gui.editor.FXProject").getConstructor().newInstance();
            fresh = call(fresh, "newEmptyProject");
            call(fresh, "deserializeNBT", root);
            if (project != null) out.addProperty("backup", backup(project));
            call(editor, "loadProject", fresh);
            out.addProperty("snbt", ((CompoundTag) call(fresh, "serializeNBT")).toString());
            return out;
        }
        if (method.equals("photon_editor_load")) {
            if (project != null) backup(project);
            Object fresh = Class.forName("com.lowdragmc.photon.gui.editor.FXProject").getConstructor().newInstance();
            fresh = call(fresh, "newEmptyProject");
            CompoundTag root = NbtIo.readCompressed(PhotonAutomationBridge.effectPath(Minecraft.getInstance(),
                    PhotonAutomationBridge.requiredId(p)).toFile());
            PhotonAdvancedBridge.validate(root);
            call(call(fresh, "getFx"), "deserializeNBT", root.getCompound("fx"));
            call(editor, "loadProject", fresh);
            out.addProperty("snbt", ((CompoundTag) call(fresh, "serializeNBT")).toString());
            return out;
        }
        if (method.equals("photon_editor_new")) {
            if (project != null) backup(project);
            Object fresh = Class.forName("com.lowdragmc.photon.gui.editor.FXProject").getConstructor().newInstance();
            call(editor, "loadProject", call(fresh, "newEmptyProject"));
            return out;
        }
        if (method.equals("photon_editor_fields") || method.equals("photon_editor_set_text")) {
            List<Object> fields = new ArrayList<>(); collect(editor, fields, Collections.newSetFromMap(new IdentityHashMap<>()));
            if (method.equals("photon_editor_set_text")) {
                String id = p.get("field"), text = p.getOrDefault("text", "");
                if (text.length() > 4096) throw new IllegalArgumentException("Text too long");
                Object widget = fields.stream().filter(f -> identity(f).equals(id)).findFirst()
                        .orElseThrow(() -> new IllegalArgumentException("Stale field ID; query fields again"));
                Class<?> type = Class.forName(FIELD);
                Field validator = type.getDeclaredField("textValidator"); validator.setAccessible(true);
                @SuppressWarnings("unchecked") Function<String, String> validate = (Function<String, String>) validator.get(widget);
                String accepted = validate == null ? text : validate.apply(text);
                if (accepted == null || !accepted.equals(text)) throw new IllegalArgumentException("Value rejected by native field validator: " + accepted);
                if (project != null) backup(project);
                // onTextChanged compares the old value before invoking the native responder.
                Method changed = type.getDeclaredMethod("onTextChanged", String.class); changed.setAccessible(true); changed.invoke(widget, text);
                out.addProperty("value", (String) call(widget, "getCurrentString"));
                return out;
            }
            JsonArray values = new JsonArray();
            for (Object widget : fields) {
                JsonObject value = new JsonObject(); value.addProperty("field", identity(widget));
                value.addProperty("value", (String) call(widget, "getCurrentString"));
                value.addProperty("position", call(widget, "getPosition").toString());
                values.add(value);
            }
            out.add("fields", values); return out;
        }
        if (method.equals("photon_editor_close")) { safeClose(); out.addProperty("backup", lastBackup); return out; }
        if (project == null) throw new IllegalStateException("No current Photon project");
        if (method.equals("photon_editor_save")) {
            out.addProperty("backup", backup(project)); return out;
        }
        Object fx = call(project, "getFx");
        if (method.equals("photon_editor_export")) {
            var id = PhotonAutomationBridge.requiredId(p);
            var target = PhotonAutomationBridge.effectPath(Minecraft.getInstance(), id);
            PhotonAssetBridge.checkMode(target, p);
            CompoundTag root = new CompoundTag(); root.putInt("_version", 1);
            root.put("fx", (CompoundTag) call(fx, "serializeNBT"));
            PhotonAdvancedBridge.validate(root); PhotonAdvancedBridge.nativeFx(root, id);
            out.addProperty("projectBackup", backup(project));
            if (Files.exists(target)) out.addProperty("backup", PhotonAssetBridge.backup(target).toString());
            PhotonAutomationBridge.writeAndReload(Minecraft.getInstance(), target, root);
            out.addProperty("file", target.toString()); out.addProperty("resource", id.toString());
            out.addProperty("sha256", PhotonAssetBridge.sha(Files.readAllBytes(target)));
            out.addProperty("reload", "started"); return out;
        }
        if (method.equals("photon_editor_set") || method.equals("photon_editor_select") || method.equals("photon_editor_bind_texture")) {
            String graph = p.getOrDefault("graph", "main");
            Object data = graph.equals("main") ? call(fx, "getMainFX") : ((Map<?, ?>) call(fx, "getSubFXs")).get(graph);
            if (data == null) throw new IllegalArgumentException("Unknown graph");
            List<?> objects = (List<?>) call(data, "objects");
            int index = Integer.parseInt(p.getOrDefault("index", "0"));
            if (index < 0 || index >= objects.size()) throw new IllegalArgumentException("Emitter index out of bounds");
            Object object = objects.get(index);
            if (method.equals("photon_editor_select")) {
                Object tab = editor.getClass().getField("BASIC").get(null);
                call(call(editor, "getConfigPanel"), "openConfigurator", tab, object);
                return out;
            }
            CompoundTag original = (CompoundTag) call(object, "serializeNBT");
            CompoundTag changed = original.copy();
            if (method.equals("photon_editor_bind_texture")) {
                PhotonAssetBridge.bind(changed, p);
                backup(project);
                Object resources = call(project, "getResources");
                Object library = ((Map<?, ?>) resources.getClass().getField("resources").get(resources)).get("material");
                Object material = Class.forName("com.lowdragmc.photon.client.gameobject.emitter.data.material.TextureMaterial")
                        .getConstructor().newInstance();
                call(material, "deserializeNBT", changed.getCompound("config").getCompound("material").getCompound("material"));
                call(library, "addBuiltinResource", "mcp/" + p.get("texture"), material);
                call(object, "deserializeNBT", changed); call(editor, "loadProject", project);
                out.addProperty("snbt", ((CompoundTag) call(project, "serializeNBT")).toString()); return out;
            }
            CompoundTag patch = TagParser.parseTag(p.getOrDefault("patch", "{}"));
            for (String key : patch.getAllKeys()) if (!Set.of("config", "transform", "name").contains(key))
                throw new IllegalArgumentException("Only config, transform and name can be edited");
            changed.merge(patch);
            if (!changed.getCompound("transform").get("id").equals(original.getCompound("transform").get("id")))
                throw new IllegalArgumentException("Emitter identity cannot change");
            backup(project);
            try { call(object, "deserializeNBT", changed); call(editor, "loadProject", project); }
            catch (Throwable error) { call(object, "deserializeNBT", original); throw error; }
        }
        out.addProperty("snbt", ((CompoundTag) call(project, "serializeNBT")).toString());
        out.addProperty("lastBackup", lastBackup); out.addProperty("lastError", lastError);
        return out;
    }

    private static String identity(Object widget) { return Integer.toHexString(System.identityHashCode(widget)); }
    private static void collect(Object widget, List<Object> fields, Set<Object> seen) throws Exception {
        if (!seen.add(widget) || seen.size() > 10000) return;
        try { if (!(boolean) call(widget, "isVisible")) return; } catch (NoSuchMethodException ignored) { }
        if (Class.forName(FIELD).isInstance(widget)) fields.add(widget);
        try { for (Object child : (Collection<?>) widget.getClass().getField("widgets").get(widget)) collect(child, fields, seen); }
        catch (NoSuchFieldException ignored) { }
    }

    private static String backup(Object project) throws Exception {
        CompoundTag data = (CompoundTag) call(project, "serializeNBT");
        Path folder = Minecraft.getInstance().gameDirectory.toPath().resolve("ldlib/mcp-backups");
        Files.createDirectories(folder);
        Path path = folder.resolve("photon-" + System.currentTimeMillis() + "-" + UUID.randomUUID() + ".fxproj");
        NbtIo.write(data, path.toFile());
        if (!data.equals(NbtIo.read(path.toFile()))) throw new IllegalStateException("Project backup verification failed");
        lastBackup = path.toString(); return lastBackup;
    }

    private static void safeClose() throws Exception {
        Object editor = editor(); Object project = call(editor, "getCurrentProject");
        if (project != null) backup(project);
        Minecraft.getInstance().setScreen(null);
    }

    @SubscribeEvent
    public void onEscape(ScreenEvent.KeyPressed.Pre event) {
        if (event.getKeyCode() != GLFW.GLFW_KEY_ESCAPE) return;
        try { editor(); } catch (Exception ignored) { return; }
        event.setCanceled(true);
        try { safeClose(); } catch (Exception e) { lastError = e.toString(); }
    }

}
