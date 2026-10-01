package cn.blockforge.smartworkbench.container;

import cn.blockforge.smartworkbench.network.PacketIO;
import net.minecraft.item.ItemStack;
import net.minecraft.network.PacketBuffer;
import net.minecraft.util.ResourceLocation;

/**
 * 界面右侧“可合成”列表里的一项：配方 ID + 产物预览。
 */
public final class CraftableEntry {

    public final ResourceLocation recipeId;
    public final ItemStack result;

    public CraftableEntry(ResourceLocation recipeId, ItemStack result) {
        this.recipeId = recipeId;
        this.result = result;
    }

    public void write(PacketBuffer buf) {
        PacketIO.writeResourceLocation(buf, this.recipeId);
        PacketIO.writeStack(buf, this.result);
    }

    public static CraftableEntry read(PacketBuffer buf) {
        ResourceLocation id = PacketIO.readResourceLocation(buf);
        ItemStack result = PacketIO.readStack(buf);
        if (id == null) {
            return null;
        }
        return new CraftableEntry(id, result);
    }

    /** 用来判断列表有没有变化，变了才发包。 */
    public String signature() {
        return this.recipeId + "|" + this.result.getCount();
    }
}
