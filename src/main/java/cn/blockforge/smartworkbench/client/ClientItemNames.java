package cn.blockforge.smartworkbench.client;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;

/** 物品的注册名工具（1.12.2 的 Item.REGISTRY 写法）。 */
final class ClientItemNames {

    private ClientItemNames() {
    }

    static String registryName(ItemStack stack) {
        if (stack.isEmpty()) {
            return "";
        }
        ResourceLocation location = Item.REGISTRY.getNameForObject(stack.getItem());
        return location == null ? "" : location.toString();
    }
}
