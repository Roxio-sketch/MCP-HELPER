package xyz.langyo.minecraft.mcp.common;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

public class McpHttpServer {

    private static final int PORT_START = McpConfig.PORT_START;
    private static final int PORT_END = McpConfig.PORT_END;
    private HttpServer server;
    private final McpMessageHandler handler;
    private int port;
    private final List<CallEvent> callHistory = new CopyOnWriteArrayList<>();
    private final List<SseClient> sseClients = new CopyOnWriteArrayList<>();
    private static final int MAX_HISTORY = 200;
    private static final Map<String, String> MIME_TYPES = new HashMap<>();
    private final long startTimeMs = System.currentTimeMillis();
    private final AtomicLong requestCount = new AtomicLong();
    private volatile long lastActivityMs = 0L;

    /** 当前存活的服务实例，供游戏内状态界面读取（可能为 null，表示尚未启动）。 */
    private static volatile McpHttpServer activeInstance;
    /** 最近一次绑定的消息处理器，供"服务未启动也要换端口"的场景复用。 */
    private static volatile McpMessageHandler boundHandler;
    private static volatile long cachedPid = -2L;
    private static volatile List<String> cachedLanAddresses = null;
    private static volatile long cachedLanAddressesTime = 0L;

    /**
     * SSE 广播走独立单线程，避免慢速客户端把 /api/cmd 的应答线程堵住。
     * 队列满时直接丢弃事件（事件只用于观测，历史仍可从 /api/calls 读取）。
     */
    private final ExecutorService sseExecutor = new ThreadPoolExecutor(
            1, 1, 0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(512),
            r -> { Thread t = new Thread(r, "MCP-SSE"); t.setDaemon(true); return t; },
            new ThreadPoolExecutor.DiscardPolicy());

    static {
        MIME_TYPES.put(".html", "text/html; charset=utf-8");
        MIME_TYPES.put(".css", "text/css; charset=utf-8");
        MIME_TYPES.put(".js", "application/javascript; charset=utf-8");
        MIME_TYPES.put(".json", "application/json");
        MIME_TYPES.put(".png", "image/png");
        MIME_TYPES.put(".svg", "image/svg+xml");
    }

    public static class CallEvent {
        public long timestamp;
        public String direction;
        public String method;
        public String params;
        public String result;
        public String error;
        public long durationMs;
    }

    private static class SseClient {
        OutputStream os;
        final Object writeLock = new Object();
        volatile boolean active = true;
    }

    public McpHttpServer(McpMessageHandler handler, int configuredPort) {
        this.handler = handler;
        boundHandler = handler;
        this.port = configuredPort > 0 ? configuredPort : PORT_START;
    }

    public McpHttpServer(McpMessageHandler handler) {
        this(handler, 0);
    }

    /**
     * 在指定端口上重启 HTTP 服务（先停旧实例再起新实例）。
     *
     * <p>供游戏内状态界面「应用并重启」与 MCP 命令 {@code set_port} 使用；
     * 旧实例的 SSE 连接会被关闭，客户端需要重连。</p>
     *
     * @return 新的服务实例
     * @throws IOException 新端口绑定失败（旧实例已停止）
     */
    public static McpHttpServer restartOnPort(int port) throws IOException {
        McpHttpServer old = activeInstance;
        McpMessageHandler handler = old != null ? old.handler : boundHandler;
        if (handler == null) throw new IOException("MCP message handler not bound yet");
        if (old != null) old.stop();
        McpHttpServer fresh = new McpHttpServer(handler, port);
        fresh.start();
        return fresh;
    }

    public int getPort() { return port; }

    /** 当前存活的服务实例；未启动时为 null。 */
    public static McpHttpServer getActive() { return activeInstance; }

    public boolean isRunning() { return server != null; }

    public int getSseClientCount() { return sseClients.size(); }

    public long getRequestCount() { return requestCount.get(); }

    public double getUptimeSeconds() { return (System.currentTimeMillis() - startTimeMs) / 1000.0; }

    /** 距上一次请求/事件过去的毫秒数；从未有活动时返回 -1。 */
    public long getIdleMillis() {
        long t = lastActivityMs;
        return t <= 0L ? -1L : System.currentTimeMillis() - t;
    }

    public String getHttpUrl() { return "http://127.0.0.1:" + port; }

    /** 监听地址（本机所有网卡，局域网内其它机器也能访问）。 */
    public String getListenAddress() { return "0.0.0.0"; }

    /**
     * 本机可用的局域网 IPv4 地址（如 192.168.1.23），带 15 秒缓存。
     * 供状态界面显示"同一局域网用哪个地址连"。
     */
    public static List<String> getLanAddresses() {
        long now = System.currentTimeMillis();
        List<String> cached = cachedLanAddresses;
        if (cached == null || now - cachedLanAddressesTime > 15000L) {
            cached = new ArrayList<>();
            try {
                Enumeration<NetworkInterface> ifaces = NetworkInterface.getNetworkInterfaces();
                while (ifaces != null && ifaces.hasMoreElements()) {
                    NetworkInterface ni = ifaces.nextElement();
                    if (!ni.isUp() || ni.isLoopback() || ni.isVirtual()) continue;
                    for (InetAddress addr : Collections.list(ni.getInetAddresses())) {
                        if (addr.isLoopbackAddress() || !(addr instanceof java.net.Inet4Address)) continue;
                        if (addr.isSiteLocalAddress()) cached.add(addr.getHostAddress());
                    }
                }
            } catch (Exception ignored) {}
            cachedLanAddresses = cached;
            cachedLanAddressesTime = now;
        }
        return cached;
    }

    public void logEvent(String method, String params, String result, String error) {
        CallEvent ev = new CallEvent();
        ev.timestamp = System.currentTimeMillis();
        ev.direction = "mod";
        ev.method = method;
        ev.params = params;
        ev.result = result;
        ev.error = error;
        emitEvent(ev);
    }

    public void start() throws IOException {
        int configuredPort = this.port;
        if (configuredPort > 0) {
            server = HttpServer.create(new InetSocketAddress("0.0.0.0", configuredPort), 0);
            port = configuredPort;
        } else {
            IOException lastErr = null;
            for (int p = PORT_START; p >= PORT_END; p--) {
                try {
                    server = HttpServer.create(new InetSocketAddress("0.0.0.0", p), 0);
                    port = p;
                    lastErr = null;
                    break;
                } catch (IOException e) {
                    lastErr = e;
                }
            }
            if (lastErr != null) throw lastErr;
        }
        server.setExecutor(Executors.newFixedThreadPool(16));
        server.createContext("/api/screenshot", new ScreenshotHandler());
        server.createContext("/api/cmd", new CmdHandler());
        server.createContext("/api/events", new EventHandler());
        server.createContext("/api/calls", new CallsHandler());
        server.createContext("/api/status", exchange -> {
            sendJson(exchange, 200, buildStatusJson());
        });
        server.createContext("/debug", new StaticHandler());
        server.createContext("/", new RootHandler());
        server.start();
        activeInstance = this;
        ReflectionHelper.dbg("McpHttpServer: started on port " + port);
    }

    private String buildStatusJson() {
        long pid = getPid();
        double uptime = (System.currentTimeMillis() - startTimeMs) / 1000.0;
        StringBuilder sb = new StringBuilder("{");
        sb.append("\"ok\":true");
        sb.append(",\"type\":\"minecraft-mod\"");
        sb.append(",\"version\":\"").append(esc(McpConfig.getModVersion())).append("\"");
        sb.append(",\"build_revision\":\"").append(esc(McpConfig.getModVersion())).append("\"");
        sb.append(",\"loader\":\"").append(esc(McpConfig.getModLoader())).append("\"");
        String forgeVer = McpConfig.getForgeVersion();
        if (forgeVer != null) sb.append(",\"forgeVersion\":\"").append(esc(forgeVer)).append("\"");
        sb.append(",\"pid\":").append(pid);
        sb.append(",\"port\":").append(port);
        sb.append(",\"uptime\":").append(String.format(java.util.Locale.ROOT, "%.1f", uptime));
        sb.append(",\"control_mode\":").append(ControlModeHelper.isMcpControlMode());
        // 可用性与「Take Over 控制模式」分开：control_mode 只表示 AI 是否接管鼠标/视角，
        // 以下三项表示各接口当前是否可用，不随 control_mode 变化。
        net.minecraft.client.Minecraft mc = minecraftOrNull();
        boolean singleplayer = mc != null && McBridge.hasSingleplayerServer(mc);
        sb.append(",\"mcp_available\":").append(this.isRunning() && handler != null);
        sb.append(",\"helper_available\":").append(singleplayer);
        sb.append(",\"structure_available\":").append(singleplayer && mc != null && McBridge.getLevel(mc) != null);
        // 只读的选区快照，给 Debug 页面显示；不参与任何 MCP 工具逻辑。
        String selection = xyz.langyo.minecraft.mcp.common.selection.SelectionManager.statusJson(mc);
        if (selection != null) sb.append(",\"selection\":").append(selection);
        sb.append(",\"mouse_mode\":\"").append(ControlModeHelper.isMouseDetached() ? "detached" : "shared").append("\"");
        sb.append(",\"player_can_look\":").append(!ControlModeHelper.isMouseDetached());
        sb.append(",\"no_pause\":").append(ControlModeHelper.isNoPause());
        sb.append(",\"sse_clients\":").append(sseClients.size());
        sb.append(",\"requests\":").append(requestCount.get());
        long idle = lastActivityMs <= 0L ? -1L : System.currentTimeMillis() - lastActivityMs;
        sb.append(",\"last_activity_ms\":").append(idle);
        sb.append(",\"http_url\":\"http://127.0.0.1:").append(port).append("\"");
        List<String> lan = getLanAddresses();
        if (!lan.isEmpty()) {
            sb.append(",\"lan_addresses\":[");
            for (int i = 0; i < lan.size(); i++) {
                if (i > 0) sb.append(",");
                sb.append("\"").append(esc(lan.get(i))).append("\"");
            }
            sb.append("]");
        }
        sb.append("}");
        return sb.toString();
    }

    /** 安全取当前客户端 Minecraft 实例；异常/早期阶段拿不到时返回 null，避免状态接口抛错。 */
    private static net.minecraft.client.Minecraft minecraftOrNull() {
        try {
            return McBridge.asMc(ReflectionHelper.getMinecraftInstance());
        } catch (Throwable t) {
            return null;
        }
    }

    private static long getPid() {
        long p = cachedPid;
        if (p != -2L) return p;
        try {
            String rtName = ManagementFactory.getRuntimeMXBean().getName();
            p = Long.parseLong(rtName.split("@")[0]);
        } catch (Exception e) { p = -1L; }
        cachedPid = p;
        return p;
    }

    public void stop() {
        if (server != null) server.stop(0);
        for (SseClient c : sseClients) c.active = false;
        sseClients.clear();
        if (activeInstance == this) activeInstance = null;
        sseExecutor.shutdownNow();
    }

    private void emitEvent(CallEvent ev) {
        callHistory.add(ev);
        while (callHistory.size() > MAX_HISTORY) callHistory.remove(0);
        lastActivityMs = System.currentTimeMillis();
        if (sseClients.isEmpty()) return;
        // 异步广播：慢速 SSE 读者不再阻塞 /api/cmd、/api/screenshot 的应答。
        final String data = eventToJson(ev);
        try {
            sseExecutor.execute(() -> broadcastSse(data));
        } catch (Exception ignored) {}
    }

    private void broadcastSse(String data) {
        String msg = "data: " + data + "\n\n";
        byte[] bytes = msg.getBytes(StandardCharsets.UTF_8);
        for (SseClient c : sseClients) {
            if (!c.active) continue;
            try {
                sseWrite(c, bytes);
            } catch (Exception e) {
                c.active = false;
                sseClients.remove(c);
            }
        }
    }

    private static void sseWrite(SseClient c, byte[] bytes) throws IOException {
        synchronized (c.writeLock) {
            c.os.write(bytes);
            c.os.flush();
        }
    }

    private String eventToJson(CallEvent ev) {
        StringBuilder sb = new StringBuilder("{");
        sb.append("\"timestamp\":").append(ev.timestamp);
        sb.append(",\"direction\":\"").append(esc(ev.direction)).append("\"");
        sb.append(",\"method\":\"").append(esc(ev.method)).append("\"");
        if (ev.params != null) sb.append(",\"params\":").append(ev.params);
        if (ev.result != null) sb.append(",\"result\":").append(ev.result.length() > 500 ? quote(ev.result.substring(0, 500)) : quote(ev.result));
        if (ev.error != null) sb.append(",\"error\":\"").append(esc(ev.error)).append("\"");
        if (ev.durationMs > 0) sb.append(",\"duration_ms\":").append(ev.durationMs);
        sb.append("}");
        return sb.toString();
    }

    private static String esc(String s) { return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n"); }
    private static String quote(String s) { return "\"" + esc(s) + "\""; }

    class StaticHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            serveResource(exchange, "index.html");
        }
    }

    class RootHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String path = exchange.getRequestURI().getPath();
            if (path.equals("/") || path.equals("/index.html") || path.equals("/debug")) {
                serveResource(exchange, "index.html");
                return;
            }
            String resource = path.startsWith("/") ? path.substring(1) : path;
            if (resource.startsWith("mcp-debug/")) resource = resource.substring("mcp-debug/".length());
            if (resource.isEmpty()) resource = "index.html";
            if (resource.contains("..")) {
                sendJson(exchange, 403, "{\"error\":\"forbidden\"}");
                return;
            }
            serveResource(exchange, resource);
        }
    }

    private void serveResource(HttpExchange exchange, String name) throws IOException {
        String path = "mcp-debug/" + name;
        InputStream is = McpHttpServer.class.getClassLoader().getResourceAsStream(path);
        if (is == null) {
            sendJson(exchange, 404, "{\"error\":\"not found\"}");
            return;
        }
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = is.read(buf)) != -1) baos.write(buf, 0, n);
        is.close();
        byte[] data = baos.toByteArray();
        String mime = "application/octet-stream";
        int dot = name.lastIndexOf('.');
        if (dot >= 0) mime = MIME_TYPES.getOrDefault(name.substring(dot).toLowerCase(), mime);
        exchange.getResponseHeaders().set("Content-Type", mime);
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.sendResponseHeaders(200, data.length);
        exchange.getResponseBody().write(data);
        exchange.getResponseBody().close();
    }

    class ScreenshotHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            CallEvent ev = new CallEvent();
            ev.timestamp = System.currentTimeMillis();
            ev.direction = "request";
            ev.method = "screenshot";
            long start = System.nanoTime();
            try {
                java.util.Map<String, String> empty = new java.util.LinkedHashMap<>();
                Object result = handler.dispatch("screenshot", empty, null);
                String b64Data = result instanceof String ? (String) result : McpProtocol.GSON.toJson(result);
                if (b64Data == null || !b64Data.startsWith("data:image/png;base64,")) {
                    sendJson(exchange, 500, "{\"error\":\"" + esc(b64Data != null ? b64Data : "null") + "\"}");
                    ev.error = "bad screenshot data";
                    ev.direction = "response";
                    ev.durationMs = (System.nanoTime() - start) / 1_000_000;
                    emitEvent(ev);
                    return;
                }
                String rawB64 = b64Data.substring("data:image/png;base64,".length());
                byte[] pngBytes = Base64.getDecoder().decode(rawB64);
                BufferedImage img = javax.imageio.ImageIO.read(new ByteArrayInputStream(pngBytes));
                int w = img != null ? img.getWidth() : 0;
                int h = img != null ? img.getHeight() : 0;
                String b64Grid = generateGridBase64(pngBytes, w, h);
                String json = "{\"original\":\"" + b64Data + "\",\"grid\":\"data:image/png;base64," + b64Grid + "\",\"width\":" + w + ",\"height\":" + h + "}";
                ev.result = "png " + w + "x" + h;
                ev.durationMs = (System.nanoTime() - start) / 1_000_000;
                ev.direction = "response";
                sendJson(exchange, 200, json);
                emitEvent(ev);
            } catch (Exception e) {
                ev.error = e.getMessage();
                ev.durationMs = (System.nanoTime() - start) / 1_000_000;
                ev.direction = "response";
                emitEvent(ev);
                sendJson(exchange, 500, "{\"error\":\"" + esc(e.getMessage()) + "\"}");
            }
        }
    }

    private String generateGridBase64(byte[] pngBytes, int w, int h) {
        try {
            BufferedImage img = javax.imageio.ImageIO.read(new ByteArrayInputStream(pngBytes));
            if (img == null) return "";
            BufferedImage canvas = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = canvas.createGraphics();
            g.drawImage(img, 0, 0, null);
            g.setColor(new Color(255, 0, 0, 180));
            g.setStroke(new BasicStroke(3));
            int step = 100;
            for (int y = 0; y < h; y += step) { g.drawLine(0, y, w, y); }
            for (int x = 0; x < w; x += step) { g.drawLine(x, 0, x, h); }
            g.setColor(new Color(255, 255, 0, 255));
            g.setFont(new Font("Monospaced", Font.BOLD, 14));
            for (int x = step; x < w; x += step) { g.drawString(String.valueOf(x), x + 4, 16); }
            for (int y = step; y < h; y += step) { g.drawString(String.valueOf(y), 4, y + 14); }
            g.dispose();
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            javax.imageio.ImageIO.write(canvas, "png", baos);
            return Base64.getEncoder().encodeToString(baos.toByteArray());
        } catch (Exception e) {
            return "";
        }
    }

    class CmdHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"POST".equals(exchange.getRequestMethod())) {
                exchange.getResponseHeaders().set("Allow", "POST");
                sendJson(exchange, 405, "{\"error\":\"method not allowed\"}");
                return;
            }
            String body = readBody(exchange);
            requestCount.incrementAndGet();
            CallEvent ev = new CallEvent();
            ev.timestamp = System.currentTimeMillis();
            ev.direction = "request";
            long start = System.nanoTime();
            String result;
            try {
                result = dispatchCmd(body, ev);
            } catch (Exception e) {
                result = "{\"error\":\"" + esc(e.getMessage()) + "\"}";
                ev.error = e.getMessage();
            }
            ev.durationMs = (System.nanoTime() - start) / 1_000_000;
            ev.result = result;
            ev.direction = "response";
            sendJson(exchange, 200, result);
            emitEvent(ev);
        }

        private String dispatchCmd(String body, CallEvent ev) {
            try {
                com.google.gson.JsonObject jo = McpProtocol.GSON.fromJson(body, com.google.gson.JsonObject.class);
                String cmd = jo.has("cmd") ? jo.get("cmd").getAsString() : jo.has("method") ? jo.get("method").getAsString() : "";
                ev.method = cmd;
                ev.params = body;
                java.util.Map<String, String> params = new java.util.LinkedHashMap<>();
                if (jo.has("params") && jo.get("params").isJsonObject()) {
                    for (java.util.Map.Entry<String, com.google.gson.JsonElement> e : jo.getAsJsonObject("params").entrySet()) {
                        params.put(e.getKey(), JsonHelper.paramValue(e.getValue()));
                    }
                }
                for (java.util.Map.Entry<String, com.google.gson.JsonElement> e : jo.entrySet()) {
                    if (!e.getKey().equals("cmd") && !e.getKey().equals("method") && !e.getKey().equals("params")) {
                        params.putIfAbsent(e.getKey(), JsonHelper.paramValue(e.getValue()));
                    }
                }
                Object r = handler.dispatch(cmd, params, null);
                if (r instanceof String) {
                    String s = (String) r;
                    if (s.startsWith("data:image") || s.startsWith("{") || s.startsWith("[")) return s;
                    return "{\"result\":\"" + esc(s) + "\"}";
                }
                return McpProtocol.GSON.toJson(r);
            } catch (Exception e) {
                return "{\"error\":\"" + esc(e.getMessage()) + "\"}";
            }
        }
    }

    class EventHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (sseClients.size() >= 4) {
                sendJson(exchange, 503, "{\"error\":\"too many SSE clients\"}");
                return;
            }
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.getResponseHeaders().set("Cache-Control", "no-cache");
            exchange.getResponseHeaders().set("Connection", "keep-alive");
            exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
            exchange.sendResponseHeaders(200, 0);
            OutputStream os = exchange.getResponseBody();
            SseClient client = new SseClient();
            client.os = os;
            sseClients.add(client);
            int histSize = callHistory.size();
            int start = Math.max(0, histSize - 20);
            StringBuilder sb = new StringBuilder();
            for (int i = start; i < histSize; i++) {
                sb.append("data: ").append(eventToJson(callHistory.get(i))).append("\n\n");
            }
            if (sb.length() > 0) {
                sseWrite(client, sb.toString().getBytes(StandardCharsets.UTF_8));
            }
            // 最长保留 300 秒；每 15 秒发一次注释心跳，避免中间层把静默连接判定为卡死。
            byte[] ping = ": ping\n\n".getBytes(StandardCharsets.UTF_8);
            int timeout = 300;
            int elapsed = 0;
            while (client.active && elapsed < timeout) {
                try { Thread.sleep(1000); } catch (InterruptedException e) { break; }
                elapsed++;
                if (elapsed % 15 == 0) {
                    try { sseWrite(client, ping); } catch (Exception e) { break; }
                }
            }
            client.active = false;
            sseClients.remove(client);
            os.close();
        }
    }

    class CallsHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            StringBuilder sb = new StringBuilder("[");
            int sz = callHistory.size();
            int start = Math.max(0, sz - 50);
            for (int i = start; i < sz; i++) {
                if (i > start) sb.append(",");
                sb.append(eventToJson(callHistory.get(i)));
            }
            sb.append("]");
            sendJson(exchange, 200, sb.toString());
        }
    }

    private static void sendJson(HttpExchange exchange, int code, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.sendResponseHeaders(code, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.getResponseBody().close();
    }

    private static String readBody(HttpExchange exchange) throws IOException {
        InputStream is = exchange.getRequestBody();
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = is.read(buf)) != -1) baos.write(buf, 0, n);
        return baos.toString(StandardCharsets.UTF_8.name());
    }
}
