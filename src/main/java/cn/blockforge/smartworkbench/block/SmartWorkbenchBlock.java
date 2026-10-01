package cn.blockforge.smartworkbench.block;

import cn.blockforge.smartworkbench.SmartWorkbenchMod;
import cn.blockforge.smartworkbench.item.WrenchItem;
import cn.blockforge.smartworkbench.tile.SmartWorkbenchTile;
import net.minecraft.block.BlockContainer;
import net.minecraft.block.SoundType;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.InventoryHelper;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/**
 * 智能工作台方块：右键打开界面，破坏时把合成格里的东西掉出来。
 */
public class SmartWorkbenchBlock extends BlockContainer {

    public SmartWorkbenchBlock() {
        super(Material.WOOD);
        setRegistryName(SmartWorkbenchMod.MOD_ID, "smart_workbench");
        setTranslationKey(SmartWorkbenchMod.MOD_ID + ".smart_workbench");
        setHardness(2.5F);
        setResistance(3.0F);
        setSoundType(SoundType.WOOD);
        setHarvestLevel("axe", 0);
    }

    @Override
    public TileEntity createNewTileEntity(World worldIn, int meta) {
        return new SmartWorkbenchTile();
    }

    /**
     * 1.12.2 的 BlockContainer 默认返回 INVISIBLE（原版容器方块靠 TESR 渲染），
     * 不覆写的话世界里的静态模型一个面都不会画——方块会“完全透明”。
     */
    @Override
    public net.minecraft.util.EnumBlockRenderType getRenderType(IBlockState state) {
        return net.minecraft.util.EnumBlockRenderType.MODEL;
    }

    @Override
    public boolean onBlockActivated(World worldIn, BlockPos pos, IBlockState state, EntityPlayer playerIn,
                                    EnumHand hand, EnumFacing facing, float hitX, float hitY, float hitZ) {
        // 手里拿着扳手时，工作台交给扳手处理（选中容器 / 清空绑定），不弹界面
        ItemStack held = playerIn.getHeldItem(hand);
        if (held.getItem() instanceof WrenchItem) {
            return false;
        }
        if (worldIn.isRemote) {
            return true;
        }
        // 潜行右键不打开界面，方便往工作台上放方块
        if (playerIn.isSneaking()) {
            return false;
        }
        TileEntity tile = worldIn.getTileEntity(pos);
        if (tile instanceof SmartWorkbenchTile && ((SmartWorkbenchTile) tile).isUsableByPlayer(playerIn)) {
            SmartWorkbenchMod.openGui(playerIn, worldIn, pos);
            return true;
        }
        return false;
    }

    @Override
    public void breakBlock(World worldIn, BlockPos pos, IBlockState state) {
        TileEntity tile = worldIn.getTileEntity(pos);
        if (!worldIn.isRemote && tile instanceof SmartWorkbenchTile) {
            for (ItemStack stack : ((SmartWorkbenchTile) tile).getItems()) {
                if (!stack.isEmpty()) {
                    InventoryHelper.spawnItemStack(worldIn, pos.getX(), pos.getY(), pos.getZ(), stack);
                }
            }
        }
        super.breakBlock(worldIn, pos, state);
    }
}
