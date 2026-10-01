package cn.blockforge.smartworkbench;

import cn.blockforge.smartworkbench.client.ClientProxy;
import cn.blockforge.smartworkbench.client.SmartWorkbenchGui;
import cn.blockforge.smartworkbench.container.SmartWorkbenchContainer;
import cn.blockforge.smartworkbench.tile.SmartWorkbenchTile;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.SidedProxy;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPostInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.network.IGuiHandler;
import net.minecraftforge.fml.common.network.NetworkRegistry;

/**
 * 智能工作台：3x3 合成 + 附近存储自动取料。
 * 1.12.2 移植版，兼容 Forge 1.12.2 与 Cleanroom 加载器。
 */
@Mod(modid = SmartWorkbenchMod.MOD_ID, name = "Smart Workbench",
        version = SmartWorkbenchMod.VERSION,
        acceptedMinecraftVersions = "[1.12,1.13)")
public final class SmartWorkbenchMod {

    public static final String MOD_ID = "smart_workbench";
    public static final String VERSION = "2.0.0-beta.8";

    @Mod.Instance(MOD_ID)
    public static SmartWorkbenchMod instance;

    @SidedProxy(clientSide = "cn.blockforge.smartworkbench.client.ClientProxy",
            serverSide = "cn.blockforge.smartworkbench.CommonProxy")
    public static CommonProxy proxy;

    public SmartWorkbenchMod() {
    }

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        ModConfig.load(event.getSuggestedConfigurationFile());
        ModRegistry.registerTile();
        ModNetwork.register();
        NetworkRegistry.INSTANCE.registerGuiHandler(this, new GuiHandler());
        proxy.preInit();
    }

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        proxy.init();
    }

    @Mod.EventHandler
    public void postInit(FMLPostInitializationEvent event) {
        proxy.postInit();
    }

    /** 打开工作台界面：GUI 编号 0，坐标用 x/y/z 传 BlockPos。 */
    public static void openGui(EntityPlayer player, World world, BlockPos pos) {
        player.openGui(instance, GuiHandler.GUI_ID, world, pos.getX(), pos.getY(), pos.getZ());
    }

    public static final class GuiHandler implements IGuiHandler {

        public static final int GUI_ID = 0;

        @Override
        public Object getServerGuiElement(int id, EntityPlayer player, World world, int x, int y, int z) {
            TileEntity tile = world.getTileEntity(new BlockPos(x, y, z));
            return tile instanceof SmartWorkbenchTile
                    ? new SmartWorkbenchContainer(player.inventory, (SmartWorkbenchTile) tile)
                    : null;
        }

        @Override
        public Object getClientGuiElement(int id, EntityPlayer player, World world, int x, int y, int z) {
            TileEntity tile = world.getTileEntity(new BlockPos(x, y, z));
            return tile instanceof SmartWorkbenchTile
                    ? new SmartWorkbenchGui(player.inventory, (SmartWorkbenchTile) tile)
                    : null;
        }
    }
}
