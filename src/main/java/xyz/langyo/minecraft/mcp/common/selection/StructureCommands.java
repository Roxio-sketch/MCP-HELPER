package xyz.langyo.minecraft.mcp.common.selection;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import java.io.File;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.Vec3;
import xyz.langyo.minecraft.mcp.common.StructureLibrary;

/**
 * {@code /mcp structure ...} 命令树（方案第十 ~ 十四节）。
 *
 * <pre>
 * /mcp structure list
 * /mcp structure folder [structures|prefabs|generated]
 * /mcp structure place &lt;id&gt;
 * /mcp structure place &lt;id&gt; ahead &lt;distance&gt;
 * </pre>
 *
 * <p>这些是“不方便用 MCP / Codex 时”的游戏内入口：查看已保存结构、直接打开 NBT 所在
 * 目录、把结构原地放置出来做验证。保存与放置仍然调用现有
 * {@link StructureLibrary}，不在命令层重写任何 NBT / Prefab 逻辑。</p>
 *
 * <p>目录与放置都依赖运行游戏的这台机器，所以和 {@code /mcp selection save} 一样只在
 * 单机 / 局域网主机（集成服务器 + 存在 Minecraft 客户端）里可用；专用服务器上会明确
 * 报错而不是静默失败。</p>
 */
public final class StructureCommands {

    private StructureCommands() {}

    /** 挂到 {@code /mcp} 下的 {@code structure} 子树。 */
    public static LiteralArgumentBuilder<CommandSourceStack> node() {
        return Commands.literal("structure")
                .then(Commands.literal("list").executes(StructureCommands::list))
                .then(Commands.literal("folder")
                        .executes(ctx -> folder(ctx, null))
                        .then(Commands.literal("structures").executes(ctx -> folder(ctx, "structures")))
                        .then(Commands.literal("prefabs").executes(ctx -> folder(ctx, "prefabs")))
                        .then(Commands.literal("generated").executes(ctx -> folder(ctx, "generated"))))
                .then(Commands.literal("place")
                        .then(Commands.argument("id", ResourceLocationArgument.id())
                                .executes(ctx -> place(ctx, 0.0, false))
                                .then(Commands.literal("ahead")
                                        .then(Commands.argument("distance",
                                                        DoubleArgumentType.doubleArg(0.0, 512.0))
                                                .executes(ctx -> place(ctx,
                                                        DoubleArgumentType.getDouble(ctx, "distance"), true))))));
    }

    // ===================== list =====================

    /** 列出运行期保存的结构 id；只报 id，不吐完整 NBT（方案第十三节）。 */
    private static int list(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) {
            src.sendFailure(Component.translatable("mcpmod.structure.integrated_only"));
            return 0;
        }
        JsonArray arr;
        try {
            JsonElement el = JsonParser.parseString(StructureLibrary.listStructures(mc, Map.of()));
            if (el == null || !el.isJsonObject() || !el.getAsJsonObject().has("structures")) {
                src.sendFailure(Component.translatable("mcpmod.structure.list_failed"));
                return 0;
            }
            arr = el.getAsJsonObject().getAsJsonArray("structures");
        } catch (Exception e) {
            src.sendFailure(Component.translatable("mcpmod.structure.list_failed"));
            return 0;
        }
        if (arr == null || arr.size() == 0) {
            src.sendSuccess(() -> Component.translatable("mcpmod.structure.list_empty"), false);
            return 1;
        }
        for (JsonElement row : arr) {
            String id = rowId(row);
            if (id == null || id.isEmpty()) continue;
            src.sendSuccess(() -> Component.literal(id), false);
        }
        int count = arr.size();
        src.sendSuccess(() -> Component.translatable("mcpmod.structure.list_count", count), false);
        return 1;
    }

    /** list_structures 的每一行是 {@code [id, display_name, size, prefab]}。 */
    private static String rowId(JsonElement row) {
        if (row == null) return null;
        try {
            if (row.isJsonArray() && row.getAsJsonArray().size() > 0) {
                return row.getAsJsonArray().get(0).getAsString();
            }
            if (row.isJsonObject() && row.getAsJsonObject().has("id")) {
                return row.getAsJsonObject().get("id").getAsString();
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    // ===================== folder =====================

    /**
     * 打开结构目录（方案第十 ~ 十二节）。
     *
     * <p>路径从 Minecraft 当前运行目录现算，绝不硬编码用户路径；目录不存在时先建后开。
     * 打开优先走原版跨平台实现 {@link Util#getPlatform()}{@code .openFile(File)}。</p>
     */
    private static int folder(CommandContext<CommandSourceStack> ctx, String kind) {
        CommandSourceStack src = ctx.getSource();
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) {
            src.sendFailure(Component.translatable("mcpmod.structure.integrated_only"));
            return 0;
        }
        File dir;
        if (kind == null) {
            dir = StructureLibrary.rootDirectory(mc);
        } else if ("structures".equals(kind)) {
            dir = StructureLibrary.structuresDirectory(mc);
        } else if ("prefabs".equals(kind)) {
            dir = StructureLibrary.prefabsDirectory(mc);
        } else {
            MinecraftServer server = mc.getSingleplayerServer();
            if (server == null) {
                src.sendFailure(Component.translatable("mcpmod.structure.generated_unavailable"));
                return 0;
            }
            dir = server.getWorldPath(LevelResource.GENERATED_DIR).toFile();
        }
        if (dir == null) {
            src.sendFailure(Component.translatable("mcpmod.structure.integrated_only"));
            return 0;
        }
        try {
            if (!dir.isDirectory()) dir.mkdirs();
            Util.getPlatform().openFile(dir);
        } catch (Throwable t) {
            src.sendFailure(Component.translatable("mcpmod.structure.folder_failed", dir.getAbsolutePath()));
            return 0;
        }
        String path = dir.getAbsolutePath();
        src.sendSuccess(() -> Component.translatable("mcpmod.structure.folder_opened", path), false);
        return 1;
    }

    // ===================== place =====================

    /**
     * 用现有 {@code place_structure} 把结构放到玩家脚下（或视线前方 {@code ahead} 处）。
     * 默认约定：不传 {@code ahead} 就用玩家当前方块坐标当原点。
     */
    private static int place(CommandContext<CommandSourceStack> ctx, double distance, boolean ahead)
            throws CommandSyntaxException {
        CommandSourceStack src = ctx.getSource();
        ServerPlayer player = src.getPlayerOrException();
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) {
            src.sendFailure(Component.translatable("mcpmod.structure.integrated_only"));
            return 0;
        }
        ResourceLocation id = ResourceLocationArgument.getId(ctx, "id");
        BlockPos origin;
        if (ahead) {
            Vec3 target = player.getEyePosition().add(player.getLookAngle().scale(distance));
            origin = BlockPos.containing(target.x, target.y, target.z);
        } else {
            origin = player.blockPosition();
        }

        Map<String, String> params = new HashMap<>();
        params.put("id", id.toString());
        params.put("origin", origin.getX() + "," + origin.getY() + "," + origin.getZ());
        params.put("include_entities", "true");

        String result = StructureLibrary.placeStructure(mc, params);
        if (result == null || result.startsWith("{\"error\"")) {
            src.sendFailure(Component.translatable("mcpmod.structure.place_failed",
                    id.toString(), extractError(result)));
            return 0;
        }
        String pos = origin.getX() + " " + origin.getY() + " " + origin.getZ();
        src.sendSuccess(() -> Component.translatable("mcpmod.structure.placed", id.toString(), pos), false);
        return 1;
    }

    private static String extractError(String json) {
        if (json == null) return "unknown error";
        try {
            JsonElement el = JsonParser.parseString(json);
            if (el != null && el.isJsonObject()) {
                JsonObject o = el.getAsJsonObject();
                if (o.has("error")) return o.get("error").getAsString();
            }
        } catch (Exception ignored) {
        }
        return json;
    }
}
