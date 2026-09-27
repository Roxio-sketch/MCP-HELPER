package xyz.langyo.minecraft.mcp.common;

import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.gui.screens.Screen;
import net.minecraftforge.fml.util.ObfuscationReflectionHelper;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** Forge 1.20.1 pointer events in original screenshot pixels, without OS input. */
public final class ClientGuiBridge {
    private ClientGuiBridge() {}
    private static volatile String reloadStatus = "idle";

    public static String dispatch(String command, Map<String, String> params) {
        CompletableFuture<String> result = new CompletableFuture<>();
        Minecraft.getInstance().execute(() -> {
            try {
                Minecraft mc = Minecraft.getInstance();
                if (command.equals("gui_state")) result.complete(state().toString());
                else if (command.equals("client_resume")) {
                    if (mc.level == null) throw new IllegalStateException("no active world");
                    if (mc.screen != null && !(mc.screen instanceof net.minecraft.client.gui.screens.PauseScreen))
                        throw new IllegalStateException("client_resume only closes the pause menu");
                    mc.setScreen(null);
                    org.lwjgl.glfw.GLFW.glfwFocusWindow(mc.getWindow().getWindow());
                    result.complete(state().toString());
                }
                else if (command.equals("client_window")) {
                    int width = Integer.parseInt(params.getOrDefault("width", "1600"));
                    int height = Integer.parseInt(params.getOrDefault("height", "900"));
                    if (width < 854 || height < 480 || width > 2560 || height > 1440)
                        throw new IllegalArgumentException("window size out of range");
                    org.lwjgl.glfw.GLFW.glfwSetWindowSize(mc.getWindow().getWindow(), width, height);
                    result.complete("{\"requested\":true}");
                }
                else if (command.equals("resource_reload")) {
                    if (reloadStatus.equals("running")) throw new IllegalStateException("reload already running");
                    var repo = mc.getResourcePackRepository();
                    repo.reload();
                    var selected = new java.util.ArrayList<>(repo.getSelectedIds());
                    String pack = params.get("pack");
                    if (pack != null && !pack.isBlank()) {
                        if (!repo.getAvailableIds().contains(pack)) throw new IllegalArgumentException("pack not found: " + pack);
                        if (!selected.contains(pack)) selected.add(pack);
                    }
                    repo.setSelected(selected);
                    reloadStatus = "running";
                    mc.reloadResourcePacks().whenComplete((unused, error) -> {
                        reloadStatus = error == null ? "complete" : "failed: " + error;
                    });
                    result.complete("{\"reload\":\"started\"}");
                }
                else {
                    String action = params.getOrDefault("action", "move");
                    if (!action.equals("move") && !action.equals("click"))
                        throw new IllegalArgumentException("action must be move or click");
                    int button = switch (params.getOrDefault("button", "left")) {
                        case "left" -> 0; case "right" -> 1; case "middle" -> 2;
                        default -> throw new IllegalArgumentException("invalid button");
                    };
                    result.complete(pointer(Integer.parseInt(params.get("x")),
                            Integer.parseInt(params.get("y")), button, action.equals("click")));
                }
            } catch (Throwable e) { result.complete(JsonHelper.error(e.toString())); }
        });
        try { return result.get(5, TimeUnit.SECONDS); }
        catch (Exception e) { return JsonHelper.error(e.toString()); }
    }

    private static JsonObject state() {
        Minecraft mc = Minecraft.getInstance();
        var window = mc.getWindow();
        JsonObject out = new JsonObject();
        out.addProperty("bridge", "photon-test2");
        out.addProperty("resourceReload", reloadStatus);
        out.addProperty("selectedPacks", mc.getResourcePackRepository().getSelectedIds().toString());
        out.addProperty("screen", mc.screen == null ? "" : mc.screen.getClass().getName());
        out.addProperty("pixelWidth", window.getWidth());
        out.addProperty("pixelHeight", window.getHeight());
        out.addProperty("guiWidth", window.getGuiScaledWidth());
        out.addProperty("guiHeight", window.getGuiScaledHeight());
        out.addProperty("mouseX", mc.mouseHandler.xpos());
        out.addProperty("mouseY", mc.mouseHandler.ypos());
        return out;
    }

    public static String pointer(int x, int y, int button, boolean click) {
        try {
            Minecraft mc = Minecraft.getInstance();
            Screen screen = mc.screen;
            if (screen == null) throw new IllegalStateException("no screen");
            var w = mc.getWindow();
            if (x < 0 || y < 0 || x >= w.getWidth() || y >= w.getHeight())
                throw new IllegalArgumentException("coordinates outside original screenshot");
            double gx = x * (double) w.getGuiScaledWidth() / w.getWidth();
            double gy = y * (double) w.getGuiScaledHeight() / w.getHeight();
            // MouseHandler.onMove updates the coordinates used by rendering/hover menus.
            Method move = ObfuscationReflectionHelper.findMethod(MouseHandler.class,
                    "m_91561_", long.class, double.class, double.class);
            double wx = x * (double) w.getScreenWidth() / w.getWidth();
            double wy = y * (double) w.getScreenHeight() / w.getHeight();
            move.invoke(mc.mouseHandler, w.getWindow(), wx, wy);
            screen.mouseMoved(gx, gy);
            JsonObject out = state();
            out.addProperty("guiX", gx);
            out.addProperty("guiY", gy);
            if (click) {
                boolean pressed = screen.mouseClicked(gx, gy, button);
                boolean released = screen.mouseReleased(gx, gy, button);
                out.addProperty("clicked", pressed);
                out.addProperty("released", released);
            }
            out.addProperty("ok", true);
            return out.toString();
        } catch (Throwable e) { return JsonHelper.error(e.toString()); }
    }
}
