package cn.blockforge.smartworkbench.tile;

import cn.blockforge.smartworkbench.ModConfig;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.IInventory;
import net.minecraft.inventory.ItemStackHelper;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.NonNullList;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextComponentTranslation;
import net.minecraft.world.World;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.items.CapabilityItemHandler;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.wrapper.InvWrapper;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 智能工作台本体：3x3 合成格存在方块实体里（关界面不丢东西，借鉴匠魂工作台的作法），
 * 另外缓存一份“可取料的容器”列表——扳手手动绑定的优先，剩下靠附近自动扫描补足。
 */
public class SmartWorkbenchTile extends TileEntity implements IInventory {

    public static final int GRID_SIZE = 9;
    /** 与原版一致：离开 64 格（8 格距离）自动关闭界面 */
    private static final double USE_DISTANCE_SQUARED = 64.0D;

    private final NonNullList<ItemStack> items = NonNullList.withSize(GRID_SIZE, ItemStack.EMPTY);
    /** 缓存的取料存储：绑定的在前，自动扫描的在后 */
    private List<IItemHandler> storages = new ArrayList<>();
    /** 缓存的输出容器：自动合成的产物优先送进这里 */
    private List<IItemHandler> outputStorages = new ArrayList<>();
    /** 扳手手动绑定的「取料容器」坐标，不受扫描范围限制 */
    private final List<BlockPos> manualBindings = new ArrayList<>();
    /** 扳手手动绑定的「输出容器」坐标，只收自动合成的产物，不参与取料 */
    private final List<BlockPos> outputBindings = new ArrayList<>();
    private long lastScanTick = Long.MIN_VALUE;

    /* IInventory：合成格 */

    @Override
    public int getSizeInventory() {
        return GRID_SIZE;
    }

    @Override
    public boolean isEmpty() {
        for (ItemStack stack : this.items) {
            if (!stack.isEmpty()) {
                return false;
            }
        }
        return true;
    }

    @Override
    public ItemStack getStackInSlot(int index) {
        return index >= 0 && index < this.items.size() ? this.items.get(index) : ItemStack.EMPTY;
    }

    @Override
    public ItemStack decrStackSize(int index, int count) {
        ItemStack result = ItemStackHelper.getAndSplit(this.items, index, count);
        if (!result.isEmpty()) {
            this.markDirty();
        }
        return result;
    }

    @Override
    public ItemStack removeStackFromSlot(int index) {
        return ItemStackHelper.getAndRemove(this.items, index);
    }

    @Override
    public void setInventorySlotContents(int index, ItemStack stack) {
        this.items.set(index, stack);
        if (!stack.isEmpty() && stack.getCount() > this.getInventoryStackLimit()) {
            stack.setCount(this.getInventoryStackLimit());
        }
        this.markDirty();
    }

    @Override
    public int getInventoryStackLimit() {
        return 64;
    }

    @Override
    public void markDirty() {
        super.markDirty();
        if (this.world != null) {
            IBlockState state = this.world.getBlockState(this.pos);
            this.world.markChunkDirty(this.pos, this);
            // 让渲染缓存（扩展模型/光照）也感知变化
            this.world.updateComparatorOutputLevel(this.pos, state.getBlock());
        }
    }

    @Override
    public boolean isUsableByPlayer(EntityPlayer player) {
        return this.world != null && !player.isDead
                && this.world.getTileEntity(this.pos) == this
                && player.getDistanceSq(this.pos.getX() + 0.5D, this.pos.getY() + 0.5D, this.pos.getZ() + 0.5D)
                <= USE_DISTANCE_SQUARED;
    }

    @Override
    public void openInventory(EntityPlayer player) {
    }

    @Override
    public void closeInventory(EntityPlayer player) {
    }

    @Override
    public boolean isItemValidForSlot(int index, ItemStack stack) {
        return true;
    }

    @Override
    public int getField(int id) {
        return 0;
    }

    @Override
    public void setField(int id, int value) {
    }

    @Override
    public int getFieldCount() {
        return 0;
    }

    @Override
    public void clear() {
        this.items.clear();
        this.markDirty();
    }

    @Override
    public String getName() {
        return "smart_workbench";
    }

    @Override
    public boolean hasCustomName() {
        return false;
    }

    @Override
    public ITextComponent getDisplayName() {
        return hasCustomName() ? new TextComponentString(getName())
                : new TextComponentTranslation("tile." + getName() + ".name");
    }

    public NonNullList<ItemStack> getItems() {
        return this.items;
    }

    /** 合成格是否全空，取料前会检查。 */
    public boolean isGridEmpty() {
        for (ItemStack stack : this.items) {
            if (!stack.isEmpty()) {
                return false;
            }
        }
        return true;
    }

    /* NBT */

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound compound) {
        super.writeToNBT(compound);
        ItemStackHelper.saveAllItems(compound, this.items);
        compound.setTag("Bindings", packPositions(this.manualBindings));
        compound.setTag("OutputBindings", packPositions(this.outputBindings));
        return compound;
    }

    @Override
    public void readFromNBT(NBTTagCompound compound) {
        super.readFromNBT(compound);
        ItemStackHelper.loadAllItems(compound, this.items);
        this.manualBindings.clear();
        unpackPositions(compound.getTagList("Bindings", 3), this.manualBindings);
        this.outputBindings.clear();
        unpackPositions(compound.getTagList("OutputBindings", 3), this.outputBindings);
    }

    /** 1.12.2 没有 long 数组的便捷读写，绑定坐标按 x/y/z 三个 int 存进 NBTTagList。 */
    private static NBTTagList packPositions(List<BlockPos> positions) {
        NBTTagList list = new NBTTagList();
        for (BlockPos pos : positions) {
            NBTTagCompound tag = new NBTTagCompound();
            tag.setInteger("x", pos.getX());
            tag.setInteger("y", pos.getY());
            tag.setInteger("z", pos.getZ());
            list.appendTag(tag);
        }
        return list;
    }

    private static void unpackPositions(NBTTagList list, List<BlockPos> out) {
        for (int i = 0; i < list.tagCount(); i++) {
            NBTTagCompound tag = list.getCompoundTagAt(i);
            out.add(new BlockPos(tag.getInteger("x"), tag.getInteger("y"), tag.getInteger("z")));
        }
    }

    /* 扳手绑定 */

    /** 把容器绑成「取料容器」；已经是输出容器的坐标不能再绑，避免取料和产物互相打架。 */
    public boolean addBinding(BlockPos pos) {
        if (this.manualBindings.contains(pos) || this.outputBindings.contains(pos)
                || this.manualBindings.size() >= ModConfig.maxInputBindings()) {
            return false;
        }
        this.manualBindings.add(pos);
        this.markDirty();
        refreshStorages(true);
        return true;
    }

    /** 把容器绑成「输出容器」：自动合成的产物会送进去。 */
    public boolean addOutputBinding(BlockPos pos) {
        if (this.outputBindings.contains(pos) || this.manualBindings.contains(pos)
                || this.outputBindings.size() >= ModConfig.maxOutputBindings()) {
            return false;
        }
        this.outputBindings.add(pos);
        this.markDirty();
        refreshStorages(true);
        return true;
    }

    public boolean hasBinding(BlockPos pos) {
        return this.manualBindings.contains(pos) || this.outputBindings.contains(pos);
    }

    /** 取消一个取料容器绑定。 */
    public boolean removeBinding(BlockPos pos) {
        if (!this.manualBindings.remove(pos)) {
            return false;
        }
        this.markDirty();
        refreshStorages(true);
        return true;
    }

    /** 取消一个输出容器绑定。 */
    public boolean removeOutputBinding(BlockPos pos) {
        if (!this.outputBindings.remove(pos)) {
            return false;
        }
        this.markDirty();
        refreshStorages(true);
        return true;
    }

    /** 清空全部绑定（取料 + 输出），返回清掉了几个。 */
    public int clearBindings() {
        int count = this.manualBindings.size() + this.outputBindings.size();
        if (count > 0) {
            this.manualBindings.clear();
            this.outputBindings.clear();
            this.markDirty();
            refreshStorages(true);
        }
        return count;
    }

    public int getBindingCount() {
        return this.manualBindings.size();
    }

    public int getOutputBindingCount() {
        return this.outputBindings.size();
    }

    /** 这个坐标能不能绑：得是个真实存在、能装东西的容器。 */
    public boolean canBind(BlockPos pos) {
        if (this.world == null || pos.equals(this.pos)) {
            return false;
        }
        IItemHandler handler = findItemHandler(this.world, pos);
        return handler != null && handler.getSlots() > 0;
    }

    /* 接入存储 */

    /**
     * 重新整理接入的存储：
     * <ol>
     *   <li>取料容器 = 扳手绑定的（优先，不受范围限制）+ 范围内自动扫描到的；</li>
     *   <li>输出容器 = 扳手绑定的输出容器，只收产物、不参与取料；</li>
     *   <li>被绑成输出容器的坐标会从自动扫描里排除，免得刚做出来的东西又被当材料抽走；</li>
     *   <li>指向同一份库存的重复处理器只接一次（原版大箱子的两半就是这种情况）。</li>
     * </ol>
     * 服务器端调用，带节流。
     */
    public void refreshStorages(boolean force) {
        if (this.world == null || this.world.isRemote) {
            return;
        }
        long now = this.world.getTotalWorldTime();
        if (!force && now < this.lastScanTick + 20L) {
            return;
        }
        this.lastScanTick = now;

        List<IItemHandler> inputs = new ArrayList<>();
        List<IItemHandler> outputs = new ArrayList<>();

        // 输出容器：只认手动绑定的，永远不参与合成取料
        Set<BlockPos> output = new HashSet<>();
        for (BlockPos bound : this.outputBindings) {
            output.add(bound);
            IItemHandler handler = findItemHandler(this.world, bound);
            if (handler != null && handler.getSlots() > 0) {
                outputs.add(handler);
            }
        }

        // 手动绑定的取料容器永远优先，而且不受扫描范围限制
        Set<BlockPos> manual = new HashSet<>();
        for (BlockPos bound : this.manualBindings) {
            manual.add(bound);
            IItemHandler handler = findItemHandler(this.world, bound);
            if (handler != null && handler.getSlots() > 0 && !containsInventory(inputs, handler)) {
                inputs.add(handler);
            }
        }

        int radius = ModConfig.scanRadius();
        int max = ModConfig.maxStorages();
        List<Entry> found = new ArrayList<>();
        BlockPos from = this.pos.add(-radius, -radius, -radius);
        BlockPos to = this.pos.add(radius, radius, radius);
        for (BlockPos.MutableBlockPos pos : BlockPos.getAllInBoxMutable(from, to)) {
            if (pos.equals(this.pos) || manual.contains(pos) || output.contains(pos)) {
                continue;
            }
            IBlockState state = this.world.getBlockState(pos);
            if (state.getBlock().isAir(state, this.world, pos)) {
                continue;
            }
            if (ModConfig.isBlacklisted(state.getBlock())) {
                continue;
            }
            TileEntity tile = this.world.getTileEntity(pos);
            if (tile == null || tile instanceof SmartWorkbenchTile) {
                continue;
            }
            IItemHandler handler = findItemHandler(this.world, pos.toImmutable());
            if (handler != null && handler.getSlots() > 0) {
                found.add(new Entry(handler, Math.abs(pos.getX() - this.pos.getX())
                    + Math.abs(pos.getY() - this.pos.getY()) + Math.abs(pos.getZ() - this.pos.getZ())));
            }
        }
        found.sort(Comparator.comparingInt(Entry::distance));
        int added = 0;
        for (Entry entry : found) {
            if (added >= max) {
                break;
            }
            // 原版大箱子的两半、部分模组的双方块容器，每一半通过能力查询拿到的都是
            // 「整个合并库存」的包装器：再接一遍会让同一格材料被数两遍，还白白占掉名额。
            if (containsInventory(inputs, entry.handler())) {
                continue;
            }
            inputs.add(entry.handler());
            added++;
        }

        this.storages = inputs;
        this.outputStorages = outputs;
    }

    public List<IItemHandler> getStorages() {
        return this.storages;
    }

    /** 自动合成产物的去处；空列表表示没有绑定输出容器，产物退回玩家背包。 */
    public List<IItemHandler> getOutputStorages() {
        return this.outputStorages;
    }

    public int getConnectedStorageCount() {
        return this.storages.size();
    }

    /**
     * 这个处理器是不是已经接进来的某一份库存的重复包装。
     * 判定方式是槽位数相同、每个槽位返回的 ItemStack 是同一个对象，并且至少有一个非空格子；
     * 两个全空的容器看不出区别，就不合并，宁可多接一个也不要漏掉。
     */
    private static boolean containsInventory(List<IItemHandler> handlers, IItemHandler candidate) {
        for (IItemHandler existing : handlers) {
            if (sameInventory(existing, candidate)) {
                return true;
            }
        }
        return false;
    }

    private static boolean sameInventory(IItemHandler first, IItemHandler second) {
        int slots = first.getSlots();
        if (slots != second.getSlots()) {
            return false;
        }
        boolean sawItem = false;
        for (int slot = 0; slot < slots; slot++) {
            ItemStack a = first.getStackInSlot(slot);
            ItemStack b = second.getStackInSlot(slot);
            if (a != b) {
                return false;
            }
            if (!a.isEmpty()) {
                sawItem = true;
            }
        }
        return sawItem;
    }

    /**
     * 找方块暴露出来的物品栏：先试无朝向的能力，不行再逐个方向试；
     * 都没有但方块实体本身是 IInventory（EnderStorage 这类老模组）就包一层 InvWrapper。
     */
    @Nullable
    public static IItemHandler findItemHandler(World world, BlockPos pos) {
        TileEntity tile = world.getTileEntity(pos);
        if (tile == null || tile instanceof SmartWorkbenchTile) {
            return null;
        }
        if (tile.hasCapability(CapabilityItemHandler.ITEM_HANDLER_CAPABILITY, null)) {
            IItemHandler handler = tile.getCapability(CapabilityItemHandler.ITEM_HANDLER_CAPABILITY, null);
            if (handler != null) {
                return handler;
            }
        }
        for (EnumFacing direction : EnumFacing.values()) {
            if (tile.hasCapability(CapabilityItemHandler.ITEM_HANDLER_CAPABILITY, direction)) {
                IItemHandler handler = tile.getCapability(CapabilityItemHandler.ITEM_HANDLER_CAPABILITY, direction);
                if (handler != null) {
                    return handler;
                }
            }
        }
        if (tile instanceof IInventory) {
            return new InvWrapper((IInventory) tile);
        }
        return null;
    }

    @Override
    public boolean shouldRefresh(World world, BlockPos pos, IBlockState oldState, IBlockState newState) {
        // 只有方块本身换了才重建方块实体，属性变化（比如朝向）不重建
        return oldState.getBlock() != newState.getBlock();
    }

    private static final class Entry {
        private final IItemHandler handler;
        private final int distance;

        private Entry(IItemHandler handler, int distance) {
            this.handler = handler;
            this.distance = distance;
        }

        private IItemHandler handler() {
            return this.handler;
        }

        private int distance() {
            return this.distance;
        }
    }
}
