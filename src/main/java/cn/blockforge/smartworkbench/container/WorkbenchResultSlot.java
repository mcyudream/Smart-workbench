package cn.blockforge.smartworkbench.container;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.IInventory;
import net.minecraft.inventory.InventoryCrafting;
import net.minecraft.inventory.SlotCrafting;
import net.minecraft.item.ItemStack;

/**
 * 产物槽：合成格变化时只是显示结果，真正拿走时才消耗材料。
 * 逻辑参照原版 SlotCrafting，但拿走产物走工作台自己的 consumeGrid（含来源退回）。
 */
public class WorkbenchResultSlot extends SlotCrafting {

    private final SmartWorkbenchContainer menu;

    public WorkbenchResultSlot(SmartWorkbenchContainer menu, EntityPlayer player,
                               InventoryCrafting craftMatrix, IInventory inventoryIn, int slotIndex,
                               int xPosition, int yPosition) {
        super(player, craftMatrix, inventoryIn, slotIndex, xPosition, yPosition);
        this.menu = menu;
    }

    @Override
    public ItemStack onTake(EntityPlayer player, ItemStack stack) {
        // 原版的 onCrafting 一并处理：合成统计、FML ItemCraftedEvent、配方书解锁
        this.onCrafting(stack);
        this.menu.consumeGrid(player);
        return stack;
    }
}
