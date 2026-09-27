package xyz.langyo.minecraft.mcp.common;

import com.google.gson.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Bounded, uncached framebuffer capture. Timestamps expose actual sampling intervals. */
public final class PhotonCaptureBridge {
    private static JsonObject state = new JsonObject();
    private static JsonArray frames = new JsonArray();
    private static Path folder;
    private static Object level;
    private static long started, next, interval;
    private static int limit;
    private static boolean active;

    public static boolean supports(String method) {
        return Set.of("photon_capture_start", "photon_capture_status", "photon_capture_stop").contains(method);
    }

    public static String dispatch(String method, Map<String,String> p) {
        CompletableFuture<String> result = new CompletableFuture<>();
        Minecraft.getInstance().execute(() -> {
            try {
                if (method.equals("photon_capture_start")) {
                    if (active) throw new IllegalStateException("A capture is already running");
                    Minecraft mc = Minecraft.getInstance();
                    if (mc.level == null || mc.screen != null || mc.getOverlay() != null)
                        throw new IllegalStateException("Resume a loaded world and finish reload before capture");
                    int count = Integer.parseInt(p.getOrDefault("frames", "40"));
                    int fps = Integer.parseInt(p.getOrDefault("fps", "8"));
                    if (count < 2 || count > 120 || fps < 1 || fps > 10)
                        throw new IllegalArgumentException("frames 2..120, fps 1..10");
                    if ((long) mc.getWindow().getWidth() * mc.getWindow().getHeight() > 2073600)
                        throw new IllegalArgumentException("Capture resolution exceeds 2073600 pixels");
                    folder = mc.gameDirectory.toPath().resolve("screenshots/mcp-capture/" + UUID.randomUUID());
                    Files.createDirectories(folder); state = new JsonObject(); frames = new JsonArray();
                    state.addProperty("directory", folder.toString()); state.addProperty("requestedFps", fps);
                    state.addProperty("requestedFrames", count); state.add("frames", frames);
                    state.addProperty("status", "running");
                    limit = count; interval = 1000000000L / fps;
                    started = System.nanoTime(); next = started; level = mc.level; active = true;
                } else if (method.equals("photon_capture_stop") && active) finish("cancelled");
                JsonObject response = state.deepCopy(); response.addProperty("ok", true);
                if (!response.has("status")) response.addProperty("status", "idle");
                result.complete(response.toString());
            } catch (Exception e) { result.complete(JsonHelper.error(e.toString())); }
        });
        try { return result.get(12, TimeUnit.SECONDS); }
        catch (Exception e) { return JsonHelper.error(e.toString()); }
    }

    private static void finish(String status) throws Exception {
        active = false; level = null; state.addProperty("status", status);
        state.addProperty("elapsedMs", (System.nanoTime() - started) / 1000000.0);
        PhotonAssetBridge.atomic(folder.resolve("capture.json"), state.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    @SubscribeEvent
    public void render(TickEvent.RenderTickEvent event) {
        if (!active || event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getInstance();
        try {
            if (mc.level != level || mc.screen != null || mc.getOverlay() != null) { finish("interrupted"); return; }
            long now = System.nanoTime();
            if (now < next) return;
            if ((long) mc.getWindow().getWidth() * mc.getWindow().getHeight() > 2073600) {
                state.addProperty("error", "Capture resolution changed beyond limit"); finish("failed"); return;
            }
            Path file = folder.resolve(String.format(Locale.ROOT, "%04d.png", frames.size()));
            try (var image = Screenshot.takeScreenshot(mc.getMainRenderTarget())) {
                image.writeToFile(file);
                JsonObject frame = new JsonObject(); frame.addProperty("file", file.getFileName().toString());
                frame.addProperty("timeMs", (now - started) / 1000000.0);
                frame.addProperty("lateMs", Math.max(0, now - next) / 1000000.0);
                frame.addProperty("width", image.getWidth()); frame.addProperty("height", image.getHeight());
                frame.addProperty("sha256", PhotonAssetBridge.sha(Files.readAllBytes(file))); frames.add(frame);
            }
            next = now + interval;
            if (frames.size() >= limit) finish("complete");
        } catch (Exception e) {
            state.addProperty("error", e.toString());
            try { finish("failed"); } catch (Exception ignored) { active = false; }
        }
    }
}
