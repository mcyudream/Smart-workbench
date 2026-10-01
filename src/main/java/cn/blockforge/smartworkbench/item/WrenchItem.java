package cn.blockforge.smartworkbench.item;

import cn.blockforge.smartworkbench.SmartWorkbenchMod;
import cn.blockforge.smartworkbench.block.SmartWorkbenchBlock;
import cn.blockforge.smartworkbench.tile.SmartWorkbenchTile;
import net.minecraft.client.util.ITooltipFlag;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.ActionResult;
import net.minecraft.util.EnumActionResult;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.world.World;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import javax.annotation.Nullable;
import java.util.List;

/**
 * 绑定扳手。它有两种绑定模式：
 * <ul>
 *   <li><b>取料容器</b>（默认）：工作台从这里抽材料；</li>
 *   <li><b>输出容器</b>：自动合成出来的产物优先送进这里。</li>
 * </ul>
 * 用法：手里拿着扳手，<b>潜行 + 右键空中</b>切换模式 → 右键一个容器选中它 →
 * 右键智能工作台完成绑定。潜行右键工作台仍然是清空该工作台的全部绑定。
 */
public class WrenchItem extends Item {

    private static final String TAG_TARGET = "BoundTarget";
    private static final String TAG_OUTPUT_TARGET = "BoundOutputTarget";
    private static final String TAG_DIMENSION = "BoundDimension";
    private static final String TAG_BENCH_TARGET = "SelectedWorkbench";
    private static final String TAG_BENCH_DIMENSION = "SelectedWorkbenchDimension";
    private static final String TAG_MODE = "BindMode";

    private static final String MODE_OUTPUT = "output";

    public WrenchItem() {
        setRegistryName(SmartWorkbenchMod.MOD_ID, "wrench");
        setTranslationKey(SmartWorkbenchMod.MOD_ID + ".wrench");
        setMaxStackSize(1);
    }

    /**
     * Forge 的“先问物品”钩子：不潜行时原版是方块先处理（箱子、工作台的界面会把右键吃掉），
     * 走这个钩子扳手才能真正拿到右键。
     */
    @Override
    public EnumActionResult onItemUseFirst(EntityPlayer player, World world, BlockPos pos, EnumFacing side,
                                           float hitX, float hitY, float hitZ, EnumHand hand) {
        return handleUse(player, world, pos, hand);
    }

    /** 兜底：某些情况下方块自己的右键返回未处理之后也会走到这里。 */
    @Override
    public EnumActionResult onItemUse(EntityPlayer player, World worldIn, BlockPos pos, EnumHand hand,
                                      EnumFacing facing, float hitX, float hitY, float hitZ) {
        return handleUse(player, worldIn, pos, hand);
    }

    /** 右键“空气”时走这里：潜行右键切换绑定模式。 */
    @Override
    public ActionResult<ItemStack> onItemRightClick(World worldIn, EntityPlayer playerIn, EnumHand handIn) {
        ItemStack stack = playerIn.getHeldItem(handIn);
        if (playerIn.isSneaking()) {
            if (!worldIn.isRemote) {
                boolean toOutput = toggleMode(stack);
                feedback(playerIn, toOutput
                        ? "绑定模式：输出容器（产物会送进去）"
                        : "绑定模式：取料容器（工作台从它抽材料）");
            }
            return new ActionResult<ItemStack>(EnumActionResult.SUCCESS, stack);
        }
        return new ActionResult<ItemStack>(EnumActionResult.PASS, stack);
    }

    private EnumActionResult handleUse(EntityPlayer player, World level, BlockPos pos, EnumHand hand) {
        ItemStack stack = player.getHeldItem(hand);
        if (player == null) {
            return EnumActionResult.PASS;
        }
        if (level.getBlockState(pos).getBlock() instanceof SmartWorkbenchBlock) {
            if (!(level.getTileEntity(pos) instanceof SmartWorkbenchTile)) {
                return EnumActionResult.PASS;
            }
            SmartWorkbenchTile bench = (SmartWorkbenchTile) level.getTileEntity(pos);
            if (level.isRemote) {
                return EnumActionResult.SUCCESS;
            }
            if (player.isSneaking()) {
                int cleared = bench.clearBindings();
                clearSelectedTarget(stack, false);
                clearSelectedTarget(stack, true);
                clearSelectedWorkbench(stack);
                feedback(player, cleared > 0
                        ? "已清空这个工作台的 " + cleared + " 个绑定"
                        : "这个工作台还没有绑定任何容器");
                return EnumActionResult.SUCCESS;
            }
            boolean output = isOutputMode(stack);
            BlockPos target = output ? readOutputTarget(stack) : readTarget(stack);
            if (target == null) {
                clearSelectedTarget(stack, false);
                clearSelectedTarget(stack, true);
                writeSelectedWorkbench(stack, pos, dimensionName(level));
                feedback(player, "已选中这个工作台，再右键容器即可完成绑定或取消绑定");
                return EnumActionResult.SUCCESS;
            }
            String dimension = readDimension(stack);
            if (dimension != null && !dimension.equals(dimensionName(level))) {
                feedback(player, "选中的容器在别的维度，绑定不了");
                return EnumActionResult.SUCCESS;
            }
            // 再次点已经绑定的同一个容器就是取消绑定，避免必须潜行清空全部绑定。
            boolean removedOutput = false;
            boolean removed = output ? bench.removeOutputBinding(target) : bench.removeBinding(target);
            if (!removed) {
                // 取消绑定不受当前扳手模式限制，避免切换模式后无法解除已有绑定。
                removedOutput = output ? bench.removeBinding(target) : bench.removeOutputBinding(target);
                removed = removedOutput;
            } else {
                removedOutput = output;
            }
            if (removed) {
                clearSelectedTarget(stack, output);
                clearSelectedWorkbench(stack);
                feedback(player, removedOutput
                        ? "已取消输出容器绑定：" + shortPos(target)
                        : "已取消取料容器绑定：" + shortPos(target));
                return EnumActionResult.SUCCESS;
            }
            if (!bench.canBind(target)) {
                feedback(player, "选中的容器已经不在了，或它本身是工作台");
                return EnumActionResult.SUCCESS;
            }
            boolean added = output ? bench.addOutputBinding(target) : bench.addBinding(target);
            if (added) {
                clearSelectedTarget(stack, output);
                clearSelectedWorkbench(stack);
                feedback(player, output
                        ? "已把 " + shortPos(target) + " 绑成输出容器，当前输出容器 " + bench.getOutputBindingCount() + " 个"
                        : "已绑定 " + shortPos(target) + "，当前取料容器 " + bench.getBindingCount() + " 个");
            } else {
                feedback(player, output
                        ? "绑不了：重复了、上限到了，或这个容器已经当成取料容器"
                        : "绑不了：重复了、上限到了，或这个容器已经当成输出容器");
            }
            return EnumActionResult.SUCCESS;
        }

        // 右击普通方块：把有物品栏的容器记到扳手上（潜行时让给其它模组处理）
        if (player.isSneaking()) {
            return EnumActionResult.PASS;
        }
        if (level.isRemote) {
            return SmartWorkbenchTile.findItemHandler(level, pos) != null
                    ? EnumActionResult.SUCCESS : EnumActionResult.PASS;
        }
        if (SmartWorkbenchTile.findItemHandler(level, pos) == null) {
            return EnumActionResult.PASS;
        }

        // 反向操作：先点工作台，再点容器时，第二次点击就完成绑定或取消绑定。
        BlockPos selectedWorkbench = readSelectedWorkbench(stack);
        if (selectedWorkbench != null) {
            String benchDimension = readSelectedWorkbenchDimension(stack);
            if (benchDimension != null && !benchDimension.equals(dimensionName(level))) {
                clearSelectedWorkbench(stack);
                feedback(player, "选中的工作台在别的维度，绑定不了");
                return EnumActionResult.SUCCESS;
            }
            if (level.getBlockState(selectedWorkbench).getBlock() instanceof SmartWorkbenchBlock
                    && level.getTileEntity(selectedWorkbench) instanceof SmartWorkbenchTile) {
                SmartWorkbenchTile bench = (SmartWorkbenchTile) level.getTileEntity(selectedWorkbench);
                boolean removedInput = bench.removeBinding(pos);
                boolean removedOutput = !removedInput && bench.removeOutputBinding(pos);
                if (removedInput || removedOutput) {
                    clearSelectedTarget(stack, false);
                    clearSelectedTarget(stack, true);
                    clearSelectedWorkbench(stack);
                    feedback(player, removedOutput
                            ? "已取消输出容器绑定：" + shortPos(pos)
                            : "已取消取料容器绑定：" + shortPos(pos));
                    return EnumActionResult.SUCCESS;
                }

                boolean output = isOutputMode(stack);
                if (!bench.canBind(pos)) {
                    clearSelectedWorkbench(stack);
                    feedback(player, "选中的容器已经不在了，或它本身是工作台");
                    return EnumActionResult.SUCCESS;
                }
                boolean added = output ? bench.addOutputBinding(pos) : bench.addBinding(pos);
                clearSelectedTarget(stack, false);
                clearSelectedTarget(stack, true);
                clearSelectedWorkbench(stack);
                feedback(player, added
                        ? (output
                                ? "已把 " + shortPos(pos) + " 绑成输出容器，当前输出容器 " + bench.getOutputBindingCount() + " 个"
                                : "已绑定 " + shortPos(pos) + "，当前取料容器 " + bench.getBindingCount() + " 个")
                        : (output
                                ? "绑不了：重复了、上限到了，或这个容器已经当成取料容器"
                                : "绑不了：重复了、上限到了，或这个容器已经当成输出容器"));
                return EnumActionResult.SUCCESS;
            }
            clearSelectedWorkbench(stack);
            feedback(player, "选中的工作台已经不存在了");
            return EnumActionResult.SUCCESS;
        }

        boolean output = isOutputMode(stack);
        writeTarget(stack, pos, dimensionName(level), output);
        feedback(player, output
                ? "已选中容器 " + shortPos(pos) + "，右键智能工作台绑成输出容器"
                : "已选中容器 " + shortPos(pos) + "，右键智能工作台完成绑定");
        return EnumActionResult.SUCCESS;
    }

    private static String dimensionName(World level) {
        return level.provider.getDimensionType().getName();
    }

    private static String shortPos(BlockPos pos) {
        return pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }

    /** 当前是不是“输出容器”模式。 */
    public static boolean isOutputMode(ItemStack stack) {
        NBTTagCompound tag = stack.getTagCompound();
        return tag != null && MODE_OUTPUT.equals(tag.getString(TAG_MODE));
    }

    /** 切换绑定模式，返回切换后是不是“输出容器”模式。 */
    private static boolean toggleMode(ItemStack stack) {
        NBTTagCompound tag = stack.getTagCompound();
        if (tag == null) {
            tag = new NBTTagCompound();
            stack.setTagCompound(tag);
        }
        boolean output = !MODE_OUTPUT.equals(tag.getString(TAG_MODE));
        tag.setString(TAG_MODE, output ? MODE_OUTPUT : "input");
        return output;
    }

    /** 扳手上当前选中的“取料容器”坐标，没选过就是 null。 */
    @Nullable
    public static BlockPos readTarget(ItemStack stack) {
        return readPos(stack, TAG_TARGET);
    }

    /** 扳手上当前选中的“输出容器”坐标，没选过就是 null。 */
    @Nullable
    public static BlockPos readOutputTarget(ItemStack stack) {
        return readPos(stack, TAG_OUTPUT_TARGET);
    }

    @Nullable
    private static BlockPos readPos(ItemStack stack, String key) {
        NBTTagCompound tag = stack.getTagCompound();
        if (tag == null || !tag.hasKey(key)) {
            return null;
        }
        long packed = tag.getLong(key);
        return unpack(packed);
    }

    private static BlockPos unpack(long packed) {
        return BlockPos.fromLong(packed);
    }

    @Nullable
    private static String readDimension(ItemStack stack) {
        NBTTagCompound tag = stack.getTagCompound();
        return tag != null && tag.hasKey(TAG_DIMENSION) ? tag.getString(TAG_DIMENSION) : null;
    }

    private static void writeTarget(ItemStack stack, BlockPos pos, String dimension, boolean output) {
        NBTTagCompound tag = stack.getTagCompound();
        if (tag == null) {
            tag = new NBTTagCompound();
            stack.setTagCompound(tag);
        }
        tag.setLong(output ? TAG_OUTPUT_TARGET : TAG_TARGET, pack(pos));
        tag.setString(TAG_DIMENSION, dimension);
    }

    private static long pack(BlockPos pos) {
        return pos.toLong();
    }

    private static void writeSelectedWorkbench(ItemStack stack, BlockPos pos, String dimension) {
        NBTTagCompound tag = stack.getTagCompound();
        if (tag == null) {
            tag = new NBTTagCompound();
            stack.setTagCompound(tag);
        }
        tag.setLong(TAG_BENCH_TARGET, pack(pos));
        tag.setString(TAG_BENCH_DIMENSION, dimension);
    }

    @Nullable
    private static BlockPos readSelectedWorkbench(ItemStack stack) {
        return readPos(stack, TAG_BENCH_TARGET);
    }

    @Nullable
    private static String readSelectedWorkbenchDimension(ItemStack stack) {
        NBTTagCompound tag = stack.getTagCompound();
        return tag != null && tag.hasKey(TAG_BENCH_DIMENSION) ? tag.getString(TAG_BENCH_DIMENSION) : null;
    }

    private static void clearSelectedWorkbench(ItemStack stack) {
        NBTTagCompound tag = stack.getTagCompound();
        if (tag == null) {
            return;
        }
        tag.removeTag(TAG_BENCH_TARGET);
        tag.removeTag(TAG_BENCH_DIMENSION);
        if (tag.isEmpty()) {
            stack.setTagCompound(null);
        }
    }

    private static void clearSelectedTarget(ItemStack stack, boolean output) {
        NBTTagCompound tag = stack.getTagCompound();
        if (tag == null) {
            return;
        }
        tag.removeTag(output ? TAG_OUTPUT_TARGET : TAG_TARGET);
        if (!tag.hasKey(TAG_TARGET) && !tag.hasKey(TAG_OUTPUT_TARGET)) {
            tag.removeTag(TAG_DIMENSION);
        }
        if (tag.isEmpty()) {
            stack.setTagCompound(null);
        }
    }

    @Override
    @SideOnly(Side.CLIENT)
    public void addInformation(ItemStack stack, @Nullable World worldIn, List<String> tooltip, ITooltipFlag flagIn) {
        boolean output = isOutputMode(stack);
        String modeName = tr(output ? "tooltip.smart_workbench.wrench.mode.output" : "tooltip.smart_workbench.wrench.mode.input",
                output ? "输出容器" : "取料容器");
        tooltip.add(colored(tr("tooltip.smart_workbench.wrench.mode", "当前模式：%s", modeName),
                output ? TextFormatting.GOLD : TextFormatting.AQUA));

        BlockPos input = readTarget(stack);
        BlockPos out = readOutputTarget(stack);
        tooltip.add(colored(
                tr("tooltip.smart_workbench.wrench.selected", "已选中的取料容器：")
                        + (input == null ? tr("tooltip.smart_workbench.wrench.none", "无") : shortPos(input)),
                TextFormatting.DARK_AQUA));
        tooltip.add(colored(
                tr("tooltip.smart_workbench.wrench.selected_output", "已选中的输出容器：")
                        + (out == null ? tr("tooltip.smart_workbench.wrench.none", "无") : shortPos(out)),
                TextFormatting.GOLD));

        tooltip.add(colored(
                tr("tooltip.smart_workbench.wrench.switch", "潜行 + 右键空中：切换绑定模式"), TextFormatting.GRAY));
        tooltip.add(colored(
                tr("tooltip.smart_workbench.wrench.clear", "潜行右键工作台：清空绑定"), TextFormatting.DARK_GRAY));
        tooltip.add(colored(
                tr("tooltip.smart_workbench.wrench.gui", "想打开工作台界面：手里换成别的物品再右键"), TextFormatting.DARK_GRAY));
    }

    /** 客户端翻译（带回退）：语言文件里有就用翻译，没有就用开发者给的中文回退。 */
    @SideOnly(Side.CLIENT)
    private static String tr(String key, String fallback, Object... args) {
        if (net.minecraft.client.resources.I18n.hasKey(key)) {
            return net.minecraft.client.resources.I18n.format(key, args);
        }
        return args.length == 0 ? fallback : String.format(fallback, args);
    }

    @SideOnly(Side.CLIENT)
    private static String colored(String text, TextFormatting color) {
        return color + text;
    }

    private static void feedback(EntityPlayer player, String text) {
        player.sendStatusMessage(new TextComponentString(text), true);
    }
}
