package xyz.langyo.minecraft.mcp.common.selection;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import xyz.langyo.minecraft.mcp.common.StructureLibrary;

/**
 * {@code /mcp selection ...} 命令树（方案第十二 ~ 十六、二十、二十一节）。
 *
 * <pre>
 * /mcp selection info
 * /mcp selection clear
 * /mcp selection undo
 * /mcp selection preview
 * /mcp selection preview wireframe
 * /mcp selection preview filled
 * /mcp selection add
 * /mcp selection add &lt;x y z&gt;
 * /mcp selection add ahead &lt;distance&gt;
 * /mcp selection save &lt;name&gt;
 * /mcp selection save &lt;name&gt; prefab
 * /mcp selection save &lt;name&gt; entities
 * /mcp selection save &lt;name&gt; prefab entities
 * </pre>
 *
 * <p>右键只能选中实体方块，所以保留命令模式补空气坐标：{@code add ahead} 从眼睛
 * 沿视线前进指定距离，把空中位置并入边界。</p>
 *
 * <p>保存一律调用现有 {@link StructureLibrary#saveStructure}，不在命令层重写
 * Structure NBT 或 Prefab 逻辑；同名结构未经确认直接拒绝，不静默覆盖。保存成功后
 * 不清除选区，方便“先存普通版，再存 Prefab”（方案第二十八、二十九节）。</p>
 */
public final class SelectionCommands {

    private SelectionCommands() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("mcp")
                .then(Commands.literal("selection")
                        .then(Commands.literal("info").executes(SelectionCommands::info))
                        .then(Commands.literal("clear").executes(SelectionCommands::clear))
                        .then(Commands.literal("undo").executes(SelectionCommands::undo))
                        .then(Commands.literal("preview")
                                .executes(SelectionCommands::previewQuery)
                                .then(Commands.literal("wireframe")
                                        .executes(ctx -> previewSet(ctx, SelectionPreview.Mode.WIREFRAME)))
                                .then(Commands.literal("filled")
                                        .executes(ctx -> previewSet(ctx, SelectionPreview.Mode.FILLED))))
                        .then(Commands.literal("add")
                                .executes(SelectionCommands::addHere)
                                .then(Commands.literal("ahead")
                                        .then(Commands.argument("distance",
                                                        DoubleArgumentType.doubleArg(0.0, 512.0))
                                                .executes(SelectionCommands::addAhead)))
                                .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                        .executes(SelectionCommands::addAt)))
                        .then(Commands.literal("save")
                                .then(Commands.argument("name", StringArgumentType.word())
                                        .executes(ctx -> save(ctx, false, false))
                                        .then(Commands.literal("prefab")
                                                .executes(ctx -> save(ctx, true, false))
                                                .then(Commands.literal("entities")
                                                        .executes(ctx -> save(ctx, true, true))))
                                        .then(Commands.literal("entities")
                                                .executes(ctx -> save(ctx, false, true))))))
                // /mcp structure ...（方案第十 ~ 十四节）：列表 / 打开目录 / 游戏内放置。
                .then(StructureCommands.node()));
    }

    // ===================== info =====================

    private static int info(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        CommandSourceStack src = ctx.getSource();
        ServerPlayer player = src.getPlayerOrException();
        SelectionData data = SelectionManager.serverSelection(player.getUUID());
        if (data == null || data.isEmpty()) {
            src.sendSuccess(() -> Component.translatable("mcpmod.selection.no_selection"), false);
            return 0;
        }
        src.sendSuccess(() -> Component.translatable("mcpmod.selection.info.dimension",
                String.valueOf(data.dimension())), false);
        src.sendSuccess(() -> Component.translatable("mcpmod.selection.info.points",
                data.points()), false);
        src.sendSuccess(() -> Component.translatable("mcpmod.selection.info.axis",
                "X", data.minX(), data.maxX()), false);
        src.sendSuccess(() -> Component.translatable("mcpmod.selection.info.axis",
                "Y", data.minY(), data.maxY()), false);
        src.sendSuccess(() -> Component.translatable("mcpmod.selection.info.axis",
                "Z", data.minZ(), data.maxZ()), false);
        if (data.isComplete()) {
            src.sendSuccess(() -> Component.translatable("mcpmod.selection.info.size",
                    data.sizeX(), data.sizeY(), data.sizeZ()), false);
            src.sendSuccess(() -> Component.translatable("mcpmod.selection.info.volume",
                    data.volume()), false);
        } else {
            src.sendSuccess(() -> Component.translatable("mcpmod.selection.info.need_more_points"), false);
        }
        SelectionData.Validity validity = SelectionManager.validate(player, data);
        src.sendSuccess(() -> Component.translatable("mcpmod.selection.info.valid",
                String.valueOf(validity.isValid())), false);
        if (validity.isError()) {
            src.sendSuccess(() -> validity.text(), false);
        }
        return 1;
    }

    // ===================== clear / undo =====================

    private static int clear(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        CommandSourceStack src = ctx.getSource();
        ServerPlayer player = src.getPlayerOrException();
        SelectionManager.clear(player);
        src.sendSuccess(() -> Component.translatable("mcpmod.selection.cleared"), false);
        return 1;
    }

    private static int undo(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        CommandSourceStack src = ctx.getSource();
        ServerPlayer player = src.getPlayerOrException();
        if (SelectionManager.undo(player)) {
            src.sendSuccess(() -> Component.translatable("mcpmod.selection.undo.done"), false);
            return 1;
        }
        src.sendFailure(Component.translatable("mcpmod.selection.undo.empty"));
        return 0;
    }

    // ===================== preview =====================

    /**
     * {@code /mcp selection preview}：预览模式是客户端设置，服务端不维护这份状态，
     * 所以发一个空包让客户端把自己的当前模式打到聊天栏（方案第六、七节）。
     */
    private static int previewQuery(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        CommandSourceStack src = ctx.getSource();
        ServerPlayer player = src.getPlayerOrException();
        SelectionNetwork.sendPreviewToPlayer(player, null);
        return 1;
    }

    /** {@code /mcp selection preview wireframe|filled}：切换模式并留下聊天回执。 */
    private static int previewSet(CommandContext<CommandSourceStack> ctx, SelectionPreview.Mode mode)
            throws CommandSyntaxException {
        CommandSourceStack src = ctx.getSource();
        ServerPlayer player = src.getPlayerOrException();
        SelectionNetwork.sendPreviewToPlayer(player, mode.id());
        src.sendSuccess(() -> Component.translatable("mcpmod.selection.preview.set", mode.text()), false);
        return 1;
    }

    // ===================== add =====================

    private static int addHere(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        CommandSourceStack src = ctx.getSource();
        ServerPlayer player = src.getPlayerOrException();
        return applyAdd(src, player, player.blockPosition());
    }

    private static int addAt(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        CommandSourceStack src = ctx.getSource();
        ServerPlayer player = src.getPlayerOrException();
        return applyAdd(src, player, BlockPosArgument.getBlockPos(ctx, "pos"));
    }

    private static int addAhead(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        CommandSourceStack src = ctx.getSource();
        ServerPlayer player = src.getPlayerOrException();
        double distance = DoubleArgumentType.getDouble(ctx, "distance");
        Vec3 eye = player.getEyePosition();
        Vec3 direction = player.getLookAngle();
        Vec3 target = eye.add(direction.scale(distance));
        BlockPos pos = BlockPos.containing(target.x, target.y, target.z);
        return applyAdd(src, player, pos);
    }

    private static int applyAdd(CommandSourceStack src, ServerPlayer player, BlockPos pos) {
        SelectionManager.AddOutcome outcome = SelectionManager.addPoint(player, pos);
        SelectionData data = SelectionManager.serverSelection(player.getUUID());
        switch (outcome) {
            case EXPANDED -> {
                if (data != null && data.lastAxis() != null) {
                    src.sendSuccess(() -> Component.translatable("mcpmod.selection.action.expanded",
                            data.lastAxis().label(), data.lastAxisValue()), false);
                } else {
                    src.sendSuccess(() -> Component.translatable("mcpmod.selection.point_first",
                            pos.getX(), pos.getY(), pos.getZ()), false);
                }
                if (data != null && data.isComplete()) {
                    src.sendSuccess(() -> Component.translatable("mcpmod.selection.summary",
                            data.sizeX(), data.sizeY(), data.sizeZ(), data.volume()), false);
                }
                return 1;
            }
            case INSIDE -> {
                src.sendSuccess(() -> Component.translatable("mcpmod.selection.action.inside"), false);
                return 1;
            }
            case OTHER_DIMENSION -> {
                String dim = data != null ? String.valueOf(data.dimension()) : "?";
                src.sendFailure(Component.translatable("mcpmod.selection.other_dimension", dim));
                return 0;
            }
            default -> {
                src.sendFailure(Component.translatable("mcpmod.selection.add_failed"));
                return 0;
            }
        }
    }

    // ===================== save =====================

    private static int save(CommandContext<CommandSourceStack> ctx, boolean prefab, boolean entities)
            throws CommandSyntaxException {
        CommandSourceStack src = ctx.getSource();
        ServerPlayer player = src.getPlayerOrException();
        SelectionData data = SelectionManager.serverSelection(player.getUUID());
        if (data == null || data.isEmpty()) {
            src.sendFailure(Component.translatable("mcpmod.selection.no_selection"));
            return 0;
        }
        SelectionData.Validity validity = SelectionManager.validate(player, data);
        if (validity == SelectionData.Validity.INCOMPLETE) {
            src.sendFailure(Component.translatable("mcpmod.selection.incomplete"));
            return 0;
        }
        if (validity.isError()) {
            src.sendFailure(validity.text());
            return 0;
        }
        String name = StringArgumentType.getString(ctx, "name");
        ResourceLocation id = StructureLibrary.parseId(name);
        if (id == null) {
            src.sendFailure(Component.translatable("mcpmod.selection.bad_name", name));
            return 0;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) {
            src.sendFailure(Component.translatable("mcpmod.selection.integrated_only"));
            return 0;
        }
        if (StructureLibrary.structureExists(mc, id)) {
            src.sendFailure(Component.translatable("mcpmod.selection.exists", id.toString()));
            return 0;
        }

        Map<String, String> params = new HashMap<>();
        params.put("name", id.toString());
        params.put("dimension", String.valueOf(data.dimension()));
        params.put("pos1", data.minX() + "," + data.minY() + "," + data.minZ());
        params.put("pos2", data.maxX() + "," + data.maxY() + "," + data.maxZ());
        params.put("include_air", "false");
        params.put("include_entities", String.valueOf(entities));
        params.put("prefab", String.valueOf(prefab));
        if (prefab) params.put("anchor", "bottom_center");

        String result = StructureLibrary.saveStructure(mc, params);
        if (result == null || result.startsWith("{\"error\"")) {
            src.sendFailure(Component.translatable("mcpmod.selection.save_failed",
                    extractError(result)));
            return 0;
        }
        src.sendSuccess(() -> Component.translatable("mcpmod.selection.saved",
                id.toString(), data.sizeString()), false);
        if (prefab) {
            src.sendSuccess(() -> Component.translatable("mcpmod.selection.saved.prefab",
                    "bottom_center"), false);
        }
        if (entities) {
            src.sendSuccess(() -> Component.translatable("mcpmod.selection.saved.entities_note"), false);
        }
        // 保存成功后不清除选区：玩家可能接着保存为 Prefab 或再核对一次。
        return 1;
    }

    private static String extractError(String json) {
        if (json == null) return "unknown error";
        try {
            JsonElement el = JsonParser.parseString(json);
            if (el != null && el.isJsonObject() && el.getAsJsonObject().has("error")) {
                return el.getAsJsonObject().get("error").getAsString();
            }
        } catch (Exception ignored) {
        }
        return json;
    }
}
