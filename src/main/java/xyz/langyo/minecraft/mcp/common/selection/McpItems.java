package xyz.langyo.minecraft.mcp.common.selection;

import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraftforge.event.BuildCreativeModeTabContentsEvent;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/**
 * MCP-HELPER 的物品注册。
 *
 * <p>目前只有一个结构选择器。工程没有自己的创造模式标签页，就把它放进原版的
 * 工具类标签，不为了一个物品去重构创造栏系统。</p>
 */
public final class McpItems {

    public static final String MOD_ID = "mcpmod";

    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(ForgeRegistries.ITEMS, MOD_ID);

    public static final RegistryObject<Item> STRUCTURE_SELECTOR = ITEMS.register(
            "structure_selector",
            () -> new StructureSelectorItem(new Item.Properties().stacksTo(1)));

    private McpItems() {}

    /** 加入原版“工具与实用物品”创造标签。 */
    public static void onBuildCreativeTab(BuildCreativeModeTabContentsEvent event) {
        if (CreativeModeTabs.TOOLS_AND_UTILITIES.equals(event.getTabKey())) {
            event.accept(STRUCTURE_SELECTOR);
        }
    }
}
