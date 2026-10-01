package cn.blockforge.smartworkbench.client;

import cn.blockforge.smartworkbench.CommonProxy;
import cn.blockforge.smartworkbench.ModRegistry;
import net.minecraft.client.renderer.block.model.ModelResourceLocation;
import net.minecraft.item.Item;
import net.minecraftforge.client.event.ModelRegistryEvent;
import net.minecraftforge.client.model.ModelLoader;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

/** 客户端逻辑：按键注册、模型注册。 */
@SideOnly(Side.CLIENT)
public class ClientProxy extends CommonProxy {

    @Override
    public void preInit() {
        // 模型注册走 ModelRegistryEvent：它发在 Forge 总线上，时机在 preInit 之后、
        // 物品注册事件之后（那时 registryName 才刚写好）
        MinecraftForge.EVENT_BUS.register(ModelRegistration.class);
    }

    @Override
    public void init() {
        ModKeybindings.register();
    }

    @SideOnly(Side.CLIENT)
    public static final class ModelRegistration {

        private ModelRegistration() {
        }

        @SubscribeEvent
        public static void onModelRegistry(ModelRegistryEvent event) {
            registerItemModel(ModRegistry.SMART_WORKBENCH_ITEM);
            registerItemModel(ModRegistry.WRENCH);
            registerItemModel(ModRegistry.CREATIVE_TAB_ICON);
        }

        private static void registerItemModel(Item item) {
            if (item.getRegistryName() == null) {
                return;
            }
            ModelResourceLocation location = new ModelResourceLocation(
                    item.getRegistryName(), "inventory");
            ModelLoader.setCustomModelResourceLocation(item, 0, location);
        }
    }
}
