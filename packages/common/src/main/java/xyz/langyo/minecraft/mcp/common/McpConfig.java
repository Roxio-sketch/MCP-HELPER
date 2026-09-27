package xyz.langyo.minecraft.mcp.common;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;

/**
 * 运行参数（目前主要是 HTTP 服务端口）的读取与持久化。
 *
 * <p>端口优先级：JVM 参数 {@code -Dmcp.port=XXXX} → 环境变量 {@code MC_MCP_PORT}
 * → 配置文件 {@code config/mcpmod.properties} → 默认 {@link #DEFAULT_PORT}。
 * 配置文件由游戏内 F9 状态界面或 {@code set_port} 命令写入，改完立刻生效，
 * 不需要重启游戏；下次启动也会沿用。</p>
 *
 * <p>本类处于各加载器共用的 common 包，因此刻意不引用任何 Forge/Fabric API：
 * config 目录由加载器侧调用 {@link #setConfigDirectory(Path)} 注入，
 * 未注入时退回工作目录下的 {@code config/}。</p>
 */
public final class McpConfig {
    private McpConfig() {}

    /** 默认端口（原来的硬编码值）。 */
    public static final int DEFAULT_PORT = 9876;
    /** 未显式配置端口时，向下回退扫描到的最小端口。 */
    public static final int PORT_END = 9000;
    /** 兼容旧代码的别名。 */
    public static final int PORT_START = DEFAULT_PORT;

    /** 配置文件名（放在 config/ 目录下）。 */
    public static final String CONFIG_FILE_NAME = "mcpmod.properties";

    private static final Object LOCK = new Object();
    private static volatile Path configDirectory;
    private static volatile Path configFile;
    /** 缓存的文件端口；-1 表示尚未从文件读取，0 表示文件里没有有效端口。 */
    private static volatile int filePort = -1;

    /** 由加载器侧注入 config 目录（Forge 下为 FMLPaths.CONFIGDIR）。 */
    public static void setConfigDirectory(Path dir) {
        synchronized (LOCK) {
            configDirectory = dir;
            configFile = dir == null ? null : dir.resolve(CONFIG_FILE_NAME);
            filePort = -1;
        }
    }

    /** 配置文件路径（可能尚未存在）。 */
    public static Path getConfigFile() {
        Path f = configFile;
        if (f == null) {
            synchronized (LOCK) {
                f = configFile;
                if (f == null) {
                    Path dir = configDirectory;
                    if (dir == null) dir = Paths.get("config");
                    f = dir.resolve(CONFIG_FILE_NAME);
                    configFile = f;
                }
            }
        }
        return f;
    }

    /** 端口是否合法（1–65535）。 */
    public static boolean isValidPort(int port) {
        return port > 0 && port <= 65535;
    }

    /**
     * 已配置的端口；未配置时返回 0，由调用方决定是使用默认值还是向下回退扫描。
     */
    public static int getConfiguredPort() {
        int p = parsePort(System.getProperty("mcp.port"));
        if (p > 0) return p;
        p = parsePort(System.getenv("MC_MCP_PORT"));
        if (p > 0) return p;
        return getFilePort();
    }

    /** 实际优先使用的端口；未配置时返回 {@link #DEFAULT_PORT}。 */
    public static int getServerPort() {
        int p = getConfiguredPort();
        return p > 0 ? p : DEFAULT_PORT;
    }

    /**
     * 把端口写进配置文件并立即生效。返回是否成功落盘
     * （落盘失败时本次运行仍然使用新端口）。
     */
    public static boolean setConfiguredPort(int port) {
        if (!isValidPort(port)) return false;
        synchronized (LOCK) {
            filePort = port;
        }
        return writeFilePort(port);
    }

    /** 端口来源：{@code jvm} / {@code env} / {@code file} / {@code default}。 */
    public static String getPortSource() {
        if (parsePort(System.getProperty("mcp.port")) > 0) return "jvm";
        if (parsePort(System.getenv("MC_MCP_PORT")) > 0) return "env";
        if (getFilePort() > 0) return "file";
        return "default";
    }

    /**
     * 首次启动时生成一份带注释的配置模板，方便用户直接编辑。
     * 默认把 {@code port} 注释掉：不配置时保持原有行为（9876 起向下找空闲端口）。
     */
    public static void ensureConfigFile() {
        Path f = getConfigFile();
        if (f == null || Files.exists(f)) return;
        try {
            Path parent = f.getParent();
            if (parent != null) Files.createDirectories(parent);
            String template = "# MCP-HELPER 服务配置 / MCP-HELPER server config\n"
                    + "# 取消下面一行的注释即可固定端口；保持注释状态则默认使用 "
                    + DEFAULT_PORT + "，被占用时自动向下寻找空闲端口。\n"
                    + "# Uncomment the line below to pin the port; left commented the mod uses "
                    + DEFAULT_PORT + " with automatic fallback.\n"
                    + "#port=" + DEFAULT_PORT + "\n";
            Files.write(f, template.getBytes(StandardCharsets.UTF_8));
        } catch (IOException ignored) {}
    }

    private static int getFilePort() {
        int cached = filePort;
        if (cached >= 0) return cached;
        synchronized (LOCK) {
            if (filePort < 0) {
                filePort = readFilePort();
            }
            return filePort;
        }
    }

    private static int readFilePort() {
        Path f = getConfigFile();
        if (f == null || !Files.isRegularFile(f)) return 0;
        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(f)) {
            props.load(in);
        } catch (IOException e) {
            return 0;
        }
        return parsePort(props.getProperty("port"));
    }

    private static boolean writeFilePort(int port) {
        Path f = getConfigFile();
        if (f == null) return false;
        Properties props = new Properties();
        if (Files.isRegularFile(f)) {
            try (InputStream in = Files.newInputStream(f)) {
                props.load(in);
            } catch (IOException ignored) {}
        }
        props.setProperty("port", Integer.toString(port));
        try {
            Path parent = f.getParent();
            if (parent != null) Files.createDirectories(parent);
            try (OutputStream out = Files.newOutputStream(f)) {
                props.store(out, "MCP-HELPER HTTP server config");
            }
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static int parsePort(String s) {
        if (s == null) return 0;
        s = s.trim();
        if (s.isEmpty()) return 0;
        try {
            int p = Integer.parseInt(s);
            return isValidPort(p) ? p : 0;
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    public static String getModVersion() {
        String v = System.getProperty("mcp.mod.version");
        if (v != null && !v.isEmpty()) return v;
        return "unknown";
    }

    public static String getModLoader() {
        String l = System.getProperty("mcp.mod.loader");
        if (l != null && !l.isEmpty()) return l;
        return "unknown";
    }

    public static String getForgeVersion() {
        String f = System.getProperty("mcp.mod.forge.version");
        if (f != null && !f.isEmpty()) return f;
        return null;
    }
}
