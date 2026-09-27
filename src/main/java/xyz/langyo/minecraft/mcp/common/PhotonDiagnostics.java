package xyz.langyo.minecraft.mcp.common;

import com.google.gson.*;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import java.util.*;

/** Bounded Photon error journal. Entries are global; they are not attributed to a runtime. */
public final class PhotonDiagnostics extends AbstractAppender {
    private static final ArrayDeque<JsonObject> EVENTS = new ArrayDeque<>();
    private static long sequence;
    private static boolean installed;

    private PhotonDiagnostics() { super("McpPhotonDiagnostics", null, null, true, Property.EMPTY_ARRAY); }

    public static synchronized void install() {
        if (installed) return;
        PhotonDiagnostics appender = new PhotonDiagnostics();
        appender.start();
        ((org.apache.logging.log4j.core.Logger) LogManager.getRootLogger()).addAppender(appender);
        installed = true;
    }

    @Override
    public void append(LogEvent event) {
        String message = event.getMessage().getFormattedMessage();
        Throwable error = event.getThrown();
        StringBuilder frames = new StringBuilder();
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        while (error != null && visited.add(error)) {
            for (StackTraceElement frame : error.getStackTrace()) {
                if (frame.getClassName().startsWith("com.lowdragmc.photon") && frames.length() < 4096)
                    frames.append(frame).append('\n');
            }
            error = error.getCause();
        }
        boolean photon = event.getLoggerName().toLowerCase(Locale.ROOT).contains("photon")
                || message.contains("com.lowdragmc.photon") || frames.length() > 0;
        if (!photon || (!event.getLevel().isMoreSpecificThan(org.apache.logging.log4j.Level.WARN)
                && !event.getLoggerName().equals("STDERR"))) return;
        String trace = frames.toString();
        String stage = (trace + message).toLowerCase(Locale.ROOT).contains("render") ? "renderer_log" : "photon_log";
        record(stage, message, trace);
    }

    static synchronized void record(String stage, String message, String frames) {
        JsonObject entry = new JsonObject();
        entry.addProperty("sequence", ++sequence);
        entry.addProperty("time", System.currentTimeMillis());
        entry.addProperty("stage", stage);
        entry.addProperty("message", message.substring(0, Math.min(2048, message.length())));
        entry.addProperty("photonFrames", frames);
        EVENTS.addLast(entry);
        while (EVENTS.size() > 64) EVENTS.removeFirst();
    }

    static synchronized JsonObject read(long after) {
        JsonObject out = new JsonObject(); out.addProperty("ok", true);
        out.addProperty("scope", "client_global"); out.addProperty("lastSequence", sequence);
        out.addProperty("droppedBefore", EVENTS.isEmpty() ? sequence : EVENTS.getFirst().get("sequence").getAsLong() - 1);
        out.addProperty("capturesUnloggedExceptions", false);
        JsonArray entries = new JsonArray();
        for (JsonObject event : EVENTS) if (event.get("sequence").getAsLong() > after) entries.add(event.deepCopy());
        out.add("events", entries); return out;
    }
}
