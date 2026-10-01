package cn.blockforge.smartworkbench.compat;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.InventoryCrafting;
import net.minecraft.item.crafting.IRecipe;
import net.minecraft.world.World;

import java.lang.reflect.Method;

/**
 * 与整合包里的「多态合成」(Polymorph Legacy, com.shiver.polymorphlegacy) 对接的反射桥。
 * <p>
 * <b>多态合成解决什么问题：</b>几个不同模组可能都加了「产物一样、但材料或排列不同」的配方。
 * 原版遇到这种情况只会闷头取第一份，玩家没得选；多态合成让玩家挑用哪一份配方。
 * <p>
 * <b>为什么全用反射：</b>不引入编译期依赖；玩家没装多态合成时这段代码根本不会执行。
 * 1.12.2 移植版 Polymorph 的 API 很小：{@code PolymorphApi.resolve(container, matrix, world, player)}
 * 返回该玩家当前选中的配方，{@code PolymorphApi.sync} 通知它界面变了。
 */
public final class PolymorphCompat {

    private static final String API_CLASS = "com.shiver.polymorphlegacy.api.PolymorphApi";

    private static boolean probed;
    private static boolean available;

    private static Method resolveMethod;
    private static Method syncMethod;

    private PolymorphCompat() {
    }

    /** 玩家有没有装多态合成。只探测一次，之后走缓存。 */
    public static boolean isLoaded() {
        probe();
        return available;
    }

    private static void probe() {
        if (probed) {
            return;
        }
        probed = true;
        try {
            Class<?> api = Class.forName(API_CLASS);
            resolveMethod = api.getMethod("resolve",
                    Container.class, InventoryCrafting.class, World.class, EntityPlayer.class);
            syncMethod = api.getMethod("sync", Container.class, EntityPlayer.class);
            available = true;
        } catch (Throwable ignored) {
            available = false;
        }
    }

    /**
     * 让多态合成决定当前合成格该出哪一份配方。
     * 返回 null 表示没装多态合成、没选过、或当前材料跟已选配方对不上，调用方应走自己的兜底逻辑。
     */
    public static IRecipe pickRecipe(Container container, InventoryCrafting matrix, World world, EntityPlayer player) {
        if (!isLoaded() || container == null || matrix == null || world == null || player == null) {
            return null;
        }
        try {
            Object result = resolveMethod.invoke(null, container, matrix, world, player);
            return result instanceof IRecipe ? (IRecipe) result : null;
        } catch (Throwable ignored) {
            // 链接失败时不要卡住合成，退回原版
            return null;
        }
    }

    /** 玩家在本界面上明确点了某一份配方时调用，让多态合成的选择跟着走。 */
    public static void selectRecipe(Container container, EntityPlayer player, IRecipe recipe) {
        if (!isLoaded() || container == null || player == null) {
            return;
        }
        try {
            syncMethod.invoke(null, container, player);
        } catch (Throwable ignored) {
            // 同步不上就退化成原版行为，不影响合成
        }
    }
}
