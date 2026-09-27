package xyz.langyo.minecraft.mcp.common;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.Map;

/**
 * 紧凑参数解析工具。
 *
 * <p>优化文档要求「少 schema、少重复」：坐标、方块列表这类结构化参数不再拆成
 * 一堆扁平字段，而是允许直接传 JSON 数组 / 对象，或用最省 token 的文本形式。
 * 这里集中处理这些形式的解析，解析失败一律返回 {@code null}，由调用方给出明确报错。</p>
 *
 * <p>已支持的写法：</p>
 * <ul>
 *   <li>向量：{@code [100,64,200]}、{@code 100,64,200}、{@code 100 64 200}，
 *       或对象 {@code {"x":100,"y":64,"z":200}}</li>
 *   <li>方块列表见 {@link #blockEntries(String)}</li>
 * </ul>
 */
public final class ToolParams {

    private ToolParams() {}

    public static String str(Map<String, String> p, String key, String def) {
        if (p == null) return def;
        String v = p.get(key);
        if (v == null) return def;
        v = v.trim();
        return v.isEmpty() ? def : v;
    }

    public static boolean bool(Map<String, String> p, String key, boolean def) {
        if (p == null) return def;
        String v = p.get(key);
        if (v == null) return def;
        v = v.trim();
        if (v.isEmpty()) return def;
        return v.equalsIgnoreCase("true") || v.equals("1")
                || v.equalsIgnoreCase("yes") || v.equalsIgnoreCase("on");
    }

    public static int intVal(Map<String, String> p, String key, int def) {
        if (p == null) return def;
        String v = p.get(key);
        if (v == null) return def;
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    /** 解析原始 JSON（仅接受数组或对象），失败返回 null。 */
    public static JsonElement json(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.isEmpty()) return null;
        if (!(s.startsWith("[") || s.startsWith("{"))) return null;
        try {
            return JsonParser.parseString(s);
        } catch (Exception e) {
            return null;
        }
    }

    /** 解析三维向量，失败返回 null。 */
    public static int[] vec3(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.isEmpty()) return null;
        if (s.startsWith("[")) {
            JsonElement el = json(s);
            if (el == null || !el.isJsonArray()) return null;
            JsonArray a = el.getAsJsonArray();
            if (a.size() < 3) return null;
            try {
                return new int[]{a.get(0).getAsInt(), a.get(1).getAsInt(), a.get(2).getAsInt()};
            } catch (Exception e) {
                return null;
            }
        }
        if (s.startsWith("{")) {
            JsonElement el = json(s);
            if (el == null || !el.isJsonObject()) return null;
            JsonObject o = el.getAsJsonObject();
            String[] xs = {"x", "dx"};
            String[] ys = {"y", "dy"};
            String[] zs = {"z", "dz"};
            Integer x = number(o, xs), y = number(o, ys), z = number(o, zs);
            if (x == null || y == null || z == null) return null;
            return new int[]{x, y, z};
        }
        String[] parts = s.split("[, ]+");
        if (parts.length < 3) return null;
        try {
            return new int[]{
                    (int) Double.parseDouble(parts[0]),
                    (int) Double.parseDouble(parts[1]),
                    (int) Double.parseDouble(parts[2])
            };
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Integer number(JsonObject o, String[] names) {
        for (String n : names) {
            if (o.has(n) && o.get(n).isJsonPrimitive()) {
                try {
                    return (int) o.get(n).getAsDouble();
                } catch (Exception ignored) {
                }
            }
        }
        return null;
    }

    /**
     * 把 blocks 参数解析成一串「一条一项」的原始条目字符串。
     *
     * <p>支持的写法（按优先级）：</p>
     * <ol>
     *   <li>JSON 数组：{@code [[0,0,0,"minecraft:stone"], ...]}，
     *       每项也可以是 {@code {"p":[0,0,0],"b":"minecraft:stone"}} 或
     *       {@code {"x":0,"y":0,"z":0,"block":"..."}}；</li>
     *   <li>纯文本：{@code "0,0,0,minecraft:stone;1,0,0,minecraft:stone"}，
     *       分隔符可用 {@code ;} 或换行，方块串允许带属性和 NBT。</li>
     * </ol>
     *
     * <p>返回 {@code null} 表示无法解析；返回空数组表示确实没有方块。</p>
     */
    public static String[] blockEntries(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.isEmpty()) return null;
        if (s.startsWith("[")) {
            JsonElement el = json(s);
            if (el == null || !el.isJsonArray()) return null;
            JsonArray arr = el.getAsJsonArray();
            String[] out = new String[arr.size()];
            for (int i = 0; i < arr.size(); i++) {
                JsonElement e = arr.get(i);
                String entry = normalizeEntry(e);
                if (entry == null) return null;
                out[i] = entry;
            }
            return out;
        }
        String[] rows = s.split("[;\\n\\r]+");
        int n = 0;
        for (String r : rows) if (!r.trim().isEmpty()) n++;
        String[] out = new String[n];
        int i = 0;
        for (String r : rows) {
            r = r.trim();
            if (!r.isEmpty()) out[i++] = r;
        }
        return out;
    }

    /** 把单个方括号 / 对象条目归一化成 {@code dx,dy,dz,blockString}。 */
    private static String normalizeEntry(JsonElement e) {
        if (e == null) return null;
        if (e.isJsonArray()) {
            JsonArray a = e.getAsJsonArray();
            if (a.size() < 4) return null;
            String block = a.get(3).isJsonPrimitive()
                    ? a.get(3).getAsString()
                    : a.get(3).toString();
            StringBuilder sb = new StringBuilder(48);
            sb.append(a.get(0).getAsInt()).append(',')
              .append(a.get(1).getAsInt()).append(',')
              .append(a.get(2).getAsInt()).append(',')
              .append(block);
            return sb.toString();
        }
        if (e.isJsonObject()) {
            JsonObject o = e.getAsJsonObject();
            String[] xs = {"x", "dx"};
            String[] ys = {"y", "dy"};
            String[] zs = {"z", "dz"};
            Integer x = number(o, xs), y = number(o, ys), z = number(o, zs);
            if (x == null || y == null || z == null) {
                if (o.has("p") && o.get("p").isJsonArray()) {
                    JsonArray pa = o.getAsJsonArray("p");
                    if (pa.size() < 3) return null;
                    x = pa.get(0).getAsInt();
                    y = pa.get(1).getAsInt();
                    z = pa.get(2).getAsInt();
                }
            }
            JsonElement b = o.has("block") ? o.get("block")
                    : o.has("b") ? o.get("b")
                    : o.has("state") ? o.get("state") : null;
            if (x == null || y == null || z == null || b == null) return null;
            String block = b.isJsonPrimitive() ? b.getAsString() : b.toString();
            return x + "," + y + "," + z + "," + block;
        }
        if (e.isJsonPrimitive()) {
            return e.getAsString();
        }
        return null;
    }

    /**
     * 把 {@code dx,dy,dz,block} 拆成 4 段。方块串里可能带 {@code [a=b,c=d]} 属性，
     * 因此只按前三个逗号切分。
     */
    public static String[] splitEntry(String entry) {
        if (entry == null) return null;
        int c1 = entry.indexOf(',');
        if (c1 < 0) return null;
        int c2 = entry.indexOf(',', c1 + 1);
        if (c2 < 0) return null;
        int c3 = entry.indexOf(',', c2 + 1);
        if (c3 < 0) return null;
        String xs = entry.substring(0, c1).trim();
        String ys = entry.substring(c1 + 1, c2).trim();
        String zs = entry.substring(c2 + 1, c3).trim();
        String block = entry.substring(c3 + 1).trim();
        if (block.isEmpty()) return null;
        return new String[]{xs, ys, zs, block};
    }
}
