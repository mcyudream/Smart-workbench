package cn.blockforge.smartworkbench.container;

import net.minecraft.inventory.Container;
import net.minecraft.inventory.IInventory;
import net.minecraft.inventory.InventoryCrafting;
import net.minecraft.item.ItemStack;
import net.minecraft.util.text.ITextComponent;

/**
 * 把方块实体里的 3x3 合成格包装成 InventoryCrafting：
 * 配方判断（matches/getCraftingResult）都认 InventoryCrafting，
 * 读写则全部转交给方块实体，保证关界面不丢东西。
 */
public class TileInventoryCrafting extends InventoryCrafting {

    private final IInventory delegate;
    private final Container eventHandler;
    private final int width;
    private final int height;

    public TileInventoryCrafting(Container eventHandler, IInventory delegate, int width, int height) {
        super(eventHandler, width, height);
        this.eventHandler = eventHandler;
        this.delegate = delegate;
        this.width = width;
        this.height = height;
    }

    public IInventory getDelegate() {
        return this.delegate;
    }

    @Override
    public int getSizeInventory() {
        return this.delegate.getSizeInventory();
    }

    @Override
    public boolean isEmpty() {
        return this.delegate.isEmpty();
    }

    @Override
    public ItemStack getStackInSlot(int index) {
        return this.delegate.getStackInSlot(index);
    }

    @Override
    public ItemStack getStackInRowAndColumn(int row, int column) {
        return row >= 0 && row < this.height && column >= 0 && column < this.width
                ? this.delegate.getStackInSlot(row * this.width + column)
                : ItemStack.EMPTY;
    }

    @Override
    public ItemStack decrStackSize(int index, int count) {
        return this.delegate.decrStackSize(index, count);
    }

    @Override
    public ItemStack removeStackFromSlot(int index) {
        return this.delegate.removeStackFromSlot(index);
    }

    @Override
    public void setInventorySlotContents(int index, ItemStack stack) {
        this.delegate.setInventorySlotContents(index, stack);
        // vanilla 的 InventoryCrafting.setInventorySlotContents 会通知容器重算产物格；
        // 产物格是双端计算的（客户端收到槽位同步包后靠这个回调立刻重算），
        // 少了这一句就会出现「材料摆好了产物格却空着」。
        this.eventHandler.onCraftMatrixChanged(this);
    }

    @Override
    public int getInventoryStackLimit() {
        return this.delegate.getInventoryStackLimit();
    }

    @Override
    public void markDirty() {
        // super 会通知容器重算产物，别丢
        super.markDirty();
        this.delegate.markDirty();
    }

    @Override
    public boolean isUsableByPlayer(net.minecraft.entity.player.EntityPlayer player) {
        return this.delegate.isUsableByPlayer(player);
    }

    @Override
    public boolean isItemValidForSlot(int index, ItemStack stack) {
        return this.delegate.isItemValidForSlot(index, stack);
    }

    @Override
    public void clear() {
        for (int i = 0; i < this.delegate.getSizeInventory(); i++) {
            this.delegate.removeStackFromSlot(i);
        }
        this.delegate.markDirty();
    }

    @Override
    public String getName() {
        return this.delegate.getName();
    }

    @Override
    public boolean hasCustomName() {
        return this.delegate.hasCustomName();
    }

    @Override
    public ITextComponent getDisplayName() {
        return this.delegate.getDisplayName();
    }
}
