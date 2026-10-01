package cn.blockforge.smartworkbench.client;

import cn.blockforge.smartworkbench.container.CraftableEntry;
import net.minecraft.item.ItemStack;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 把「可合成」列表整理成界面上真正要画的一格一格：先按搜索词过滤，再按需把同类物品折叠成一项。
 * <p>
 * 折叠规则故意做得简单、可预期：看物品注册 ID 的<b>最后一段</b>（{@code oak_stairs -> stairs}）。
 * 同名尾段的物品归成「同类」——所以各种木头的楼梯、各色羊毛都会收成一项，点一下再展开。
 * 尾段是 {@code block} 这种大杂烩的不折，免得把「草方块、黏液块」硬塞成一类。
 */
public final class CraftableListView {

    /** 过短的尾段（比如单个字母）和不适合当分类名的尾段不参与折叠。 */
    private static final Set<String> BLACKLIST =
            new HashSet<String>(Arrays.asList("block", "item", "thing"));

    private static final int MIN_GROUP = 2;
    /** 一类最多收多少个：太多说明这个尾段太笼统，干脆不折。 */
    private static final int MAX_GROUP = 32;

    /**
     * 列表里的一格。
     */
    public static final class Cell {
        public final CraftableEntry entry;
        /** 同类折叠的尾段；不折叠的普通项为 null */
        public final String groupKey;
        /** true 表示这是折叠头（点它展开/收起） */
        public final boolean header;
        /** 这一类的成员数；普通项为 1 */
        public final int groupSize;

        Cell(CraftableEntry entry, String groupKey, boolean header, int groupSize) {
            this.entry = entry;
            this.groupKey = groupKey;
            this.header = header;
            this.groupSize = groupSize;
        }
    }

    private CraftableListView() {
    }

    public static List<Cell> build(List<CraftableEntry> entries, String query, boolean folding,
                                   Set<String> expandedGroups) {
        List<CraftableEntry> filtered = new ArrayList<CraftableEntry>(entries.size());
        for (CraftableEntry entry : entries) {
            if (CraftableSearch.matches(entry, query)) {
                filtered.add(entry);
            }
        }
        if (!folding) {
            List<Cell> flat = new ArrayList<Cell>(filtered.size());
            for (CraftableEntry entry : filtered) {
                flat.add(new Cell(entry, null, false, 1));
            }
            return flat;
        }

        // 先把每个条目的分类键、每一类的成员和数量一次算好（O(n)）
        List<String> keys = new ArrayList<String>(filtered.size());
        Map<String, Integer> counts = new HashMap<String, Integer>();
        Map<String, List<CraftableEntry>> members = new HashMap<String, List<CraftableEntry>>();
        for (CraftableEntry entry : filtered) {
            String key = groupKey(entry.result);
            keys.add(key);
            if (key != null) {
                Integer old = counts.get(key);
                counts.put(key, old == null ? 1 : old + 1);
                List<CraftableEntry> list = members.get(key);
                if (list == null) {
                    list = new ArrayList<CraftableEntry>();
                    members.put(key, list);
                }
                list.add(entry);
            }
        }
        Set<String> foldable = new HashSet<String>();
        for (Map.Entry<String, Integer> count : counts.entrySet()) {
            int size = count.getValue();
            if (size >= MIN_GROUP && size <= MAX_GROUP) {
                foldable.add(count.getKey());
            }
        }

        List<Cell> out = new ArrayList<Cell>(filtered.size());
        Set<String> emitted = new HashSet<String>();
        for (int i = 0; i < filtered.size(); i++) {
            CraftableEntry entry = filtered.get(i);
            String key = keys.get(i);
            if (key != null && foldable.contains(key)) {
                if (!emitted.add(key)) {
                    continue; // 这一类的头已经发过了
                }
                int size = counts.get(key);
                out.add(new Cell(entry, key, true, size));
                if (expandedGroups.contains(key)) {
                    for (CraftableEntry member : members.get(key)) {
                        out.add(new Cell(member, key, false, size));
                    }
                }
            } else {
                out.add(new Cell(entry, null, false, 1));
            }
        }
        return out;
    }

    /** 物品注册 ID 的最后一段，作为「同类」的分类键；不参与折叠的返回 null。 */
    public static String groupKey(ItemStack stack) {
        if (stack.isEmpty()) {
            return null;
        }
        String id = ClientItemNames.registryName(stack);
        int slash = id.lastIndexOf(':');
        if (slash >= 0) {
            id = id.substring(slash + 1);
        }
        int underscore = id.lastIndexOf('_');
        String suffix = underscore >= 0 ? id.substring(underscore + 1) : id;
        if (suffix.length() < 3 || BLACKLIST.contains(suffix)) {
            return null;
        }
        return suffix;
    }

    /** 折叠头的名字：优先用语言文件里翻译好的说法（stairs -> 楼梯），没有就退回英文尾段。 */
    public static String groupLabel(String groupKey) {
        String key = "gui.smart_workbench.group." + groupKey;
        if (net.minecraft.client.resources.I18n.hasKey(key)) {
            return net.minecraft.client.resources.I18n.format(key);
        }
        return prettify(groupKey);
    }

    private static String prettify(String raw) {
        String text = raw.replace('_', ' ').trim();
        if (text.isEmpty()) {
            return raw;
        }
        return Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }
}
