package cn.blockforge.smartworkbench.compat;

import cn.blockforge.smartworkbench.container.SmartWorkbenchContainer;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * JEI(HEI) 桥共用的「材料够不够」判断（客户端侧）。
 * 把配方翻译成「每个材料槽的候选物品清单」，再逐个从可用材料里扣。
 */
public final class MaterialAvailability {

    private MaterialAvailability() {
    }

    /** 客户端可由智能工作台直接取用的材料：合成格里已有的 + 服务器推来的接入存储快照。 */
    public static List<ItemStack> collectAvailable(SmartWorkbenchContainer menu) {
        return collectAvailable(menu, null);
    }

    /**
     * 在上面那份材料的基础上，再把玩家自己背包（主背包 + 快捷栏）里的东西算进来。
     * 和服务器端的 PlayerMainInvWrapper 保持一致，只数 0~35 号槽（主背包 + 快捷栏）。
     */
    public static List<ItemStack> collectAvailable(SmartWorkbenchContainer menu, EntityPlayer player) {
        List<ItemStack> available = new ArrayList<ItemStack>();
        for (ItemStack stack : menu.getMatrixItems()) {
            if (!stack.isEmpty()) {
                available.add(stack);
            }
        }
        for (ItemStack stack : menu.getClientStorageStacks()) {
            if (!stack.isEmpty()) {
                available.add(stack);
            }
        }
        if (player != null) {
            for (ItemStack stack : player.inventory.mainInventory) {
                if (!stack.isEmpty()) {
                    available.add(stack);
                }
            }
        }
        return available;
    }

    /**
     * 逐个材料槽从可用材料里扣一件，扣不到的就是缺料。
     * 候选越少的槽越先分配，避免木板这类通用材料被前面的槽先吃掉、后面明明够却报缺料。
     *
     * @param candidates 每个材料槽的候选物品（多选一）
     * @param available  手上能用的材料，会在内部复制一份再扣，不会改动传进来的列表
     * @return 与 candidates 等长的数组，true 表示这个槽能配上
     */
    public static boolean[] match(List<List<ItemStack>> candidates, List<ItemStack> available) {
        List<ItemStack> remaining = new ArrayList<ItemStack>();
        for (ItemStack stack : available) {
            if (stack != null && !stack.isEmpty()) {
                remaining.add(stack.copy());
            }
        }
        boolean[] matched = new boolean[candidates.size()];
        List<Integer> order = new ArrayList<Integer>();
        for (int i = 0; i < candidates.size(); i++) {
            if (candidateCount(candidates.get(i)) > 0) {
                order.add(i);
            } else {
                matched[i] = true;
            }
        }
        Collections_sort(order, candidates);
        for (int index : order) {
            for (ItemStack candidate : candidates.get(index)) {
                if (candidate == null || candidate.isEmpty()) {
                    continue;
                }
                int at = findMatching(remaining, candidate);
                if (at >= 0) {
                    ItemStack held = remaining.get(at);
                    if (held.getCount() <= 1) {
                        remaining.remove(at);
                    } else {
                        held.shrink(1);
                    }
                    matched[index] = true;
                    break;
                }
            }
        }
        return matched;
    }

    private static void Collections_sort(List<Integer> order, final List<List<ItemStack>> candidates) {
        java.util.Collections.sort(order, new Comparator<Integer>() {
            @Override
            public int compare(Integer a, Integer b) {
                return candidateCount(candidates.get(a)) - candidateCount(candidates.get(b));
            }
        });
    }

    /** 候选清单里真正能拿出来的物品数量（挑候选最少的槽优先分配时用）。 */
    public static int candidateCount(List<ItemStack> candidates) {
        int count = 0;
        for (ItemStack stack : candidates) {
            if (stack != null && !stack.isEmpty()) {
                count++;
            }
        }
        return count;
    }

    /**
     * 候选物和手上的物品算不算同一个东西。
     * 候选自带 NBT（附魔书、药水之类）时必须连标签一起对上，否则只认物品本身。
     */
    public static boolean matches(ItemStack held, ItemStack candidate) {
        return held.getItem() == candidate.getItem()
                && (candidate.getTagCompound() == null
                || (ItemStack.areItemsEqual(held, candidate) && ItemStack.areItemStackTagsEqual(held, candidate)));
    }

    private static int findMatching(List<ItemStack> available, ItemStack candidate) {
        for (int i = 0; i < available.size(); i++) {
            if (matches(available.get(i), candidate)) {
                return i;
            }
        }
        return -1;
    }
}
