package cn.blockforge.smartworkbench;

import cn.blockforge.smartworkbench.block.SmartWorkbenchBlock;
import cn.blockforge.smartworkbench.item.WrenchItem;
import cn.blockforge.smartworkbench.tile.SmartWorkbenchTile;
import net.minecraft.block.Block;
import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.item.Item;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.util.NonNullList;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.event.RegistryEvent;
import net.minecraftforge.fml.common.registry.GameRegistry;
import net.minecraftforge.registries.IForgeRegistry;

/** 所有注册对象集中在这里：方块、物品、创造标签页、方块实体。 */
public final class ModRegistry {

    public static final SmartWorkbenchBlock SMART_WORKBENCH = new SmartWorkbenchBlock();
    public static final Item SMART_WORKBENCH_ITEM = new ItemBlock(SMART_WORKBENCH);
    /** 绑定扳手：手动把容器接到工作台上。 */
    public static final WrenchItem WRENCH = new WrenchItem();
    /** 只用作创造标签页图标的隐藏物品：正常游玩拿不到。 */
    public static final Item CREATIVE_TAB_ICON = new Item();

    private static final String MOD_TAB_LABEL = "smart_workbench";

    /** 独立创造标签页：工作台和扳手都收在这里。 */
    public static final CreativeTabs MAIN_TAB = new CreativeTabs(MOD_TAB_LABEL) {
        @Override
        public ItemStack createIcon() {
            return new ItemStack(CREATIVE_TAB_ICON);
        }

        @Override
        public void displayAllRelevantItems(NonNullList<ItemStack> items) {
            items.add(new ItemStack(SMART_WORKBENCH_ITEM));
            items.add(new ItemStack(WRENCH));
        }
    };

    private static final ResourceLocation TILE_KEY = new ResourceLocation(SmartWorkbenchMod.MOD_ID, "smart_workbench");

    private ModRegistry() {
    }

    @net.minecraftforge.fml.common.Mod.EventBusSubscriber
    public static final class Registration {

        private Registration() {
        }

        @net.minecraftforge.fml.common.eventhandler.SubscribeEvent
        public static void onRegisterBlocks(RegistryEvent.Register<Block> event) {
            SMART_WORKBENCH.setCreativeTab(MAIN_TAB);
            event.getRegistry().register(SMART_WORKBENCH);
        }

        @net.minecraftforge.fml.common.eventhandler.SubscribeEvent
        public static void onRegisterItems(RegistryEvent.Register<Item> event) {
            IForgeRegistry<Item> registry = event.getRegistry();
            SMART_WORKBENCH_ITEM.setRegistryName(SmartWorkbenchMod.MOD_ID, "smart_workbench");
            SMART_WORKBENCH_ITEM.setTranslationKey(SmartWorkbenchMod.MOD_ID + ".smart_workbench");
            SMART_WORKBENCH_ITEM.setCreativeTab(MAIN_TAB);
            registry.register(SMART_WORKBENCH_ITEM);
            WRENCH.setCreativeTab(MAIN_TAB);
            registry.register(WRENCH);
            CREATIVE_TAB_ICON.setRegistryName(SmartWorkbenchMod.MOD_ID, "creative_tab_icon");
            CREATIVE_TAB_ICON.setTranslationKey(SmartWorkbenchMod.MOD_ID + ".creative_tab_icon");
            registry.register(CREATIVE_TAB_ICON);
        }
    }

    /** 在 preInit 里调用（1.12.2 的 TileEntity 注册走 GameRegistry）。 */
    public static void registerTile() {
        GameRegistry.registerTileEntity(SmartWorkbenchTile.class, TILE_KEY);
    }
}
