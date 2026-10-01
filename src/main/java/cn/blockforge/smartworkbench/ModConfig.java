package cn.blockforge.smartworkbench;

import net.minecraft.block.Block;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.common.config.Configuration;

import java.io.File;

/**
 * 通用配置（config/smart_workbench.cfg）。
 * <p>
 * 这里管几件事：
 * <ul>
 *   <li>工作台自动扫描附近容器的范围（默认 5x5x5，最大 50x50x50）；</li>
 *   <li>扳手绑定「取料容器」/「输出容器」的上限（1~50）；</li>
 *   <li>一次自动合成最多做多少个（默认 64，最大 12318）；</li>
 *   <li>界面可合成列表容量与方块黑名单。</li>
 * </ul>
 */
public final class ModConfig {

    /** 界面配方列表的硬上限。 */
    public static final int MAX_LIST_SIZE = 4096;
    /** 新配置的默认列表容量。 */
    public static final int DEFAULT_LIST_SIZE = 1024;
    /** 1.2.0 之前列表的硬上限；配置里留着这个数说明是旧版本遗留的。 */
    private static final int LEGACY_LIST_LIMIT = 384;

    private static Configuration config;

    private static int scanRange = 5;
    private static int maxStorages = 6;
    private static int maxInputBindings = 24;
    private static int maxOutputBindings = 6;
    private static int autoCraftLimit = 64;
    private static int recursiveDepth = 5;
    private static int listSlots = DEFAULT_LIST_SIZE;
    private static String[] blacklist = new String[0];

    private ModConfig() {
    }

    public static void load(File file) {
        config = new Configuration(file);
        reload();
        if (config.hasChanged()) {
            config.save();
        }
    }

    public static void reload() {
        if (config == null) {
            return;
        }
        config.load();
        scanRange = clamp(config.getInt("scanRange", "storage", 5, 1, 50,
                "工作台自动接入附近容器的范围，按立方体边长算：5 表示 5x5x5，50 表示 50x50x50。默认 5。"), 1, 50);
        maxStorages = clamp(config.getInt("maxStorages", "storage", 6, 1, 24,
                "自动扫描最多同时接入多少个容器，越界的部分会被忽略。"), 1, 24);
        maxInputBindings = clamp(config.getInt("maxInputBindings", "storage", 24, 1, 50,
                "扳手最多能给一台工作台绑定多少个「取料容器」。"), 1, 50);
        maxOutputBindings = clamp(config.getInt("maxOutputBindings", "storage", 6, 1, 50,
                "扳手最多能给一台工作台绑定多少个「输出容器」（自动合成的产物会送进去）。"), 1, 50);
        listSlots = config.getInt("maxListSize", "storage", DEFAULT_LIST_SIZE, 12, MAX_LIST_SIZE,
                "一次最多在界面上列出多少个可合成配方。默认 1024。");
        blacklist = config.getStringList("blacklist", "storage", new String[0],
                "不接入的方块 ID 列表，例如 [\"minecraft:chest\"]。");
        autoCraftLimit = clamp(config.getInt("autoCraftLimit", "craft", 64, 1, 12318,
                "点一次「自动合成」最多做多少个，默认 64，最大 12318。"), 1, 12318);
        recursiveDepth = clamp(config.getInt("recursiveDepth", "craft", 5, 1, 16,
                "链式合成（自动补齐中间材料）的最大深度：1 表示只补一层中间材料，"
                        + "5 表示最多自动向下补 5 层（例如 目标<-半成品B<-半成品A<-原材料）。默认 5。"), 1, 16);
        if (config.hasChanged()) {
            config.save();
        }
    }

    public static Configuration config() {
        return config;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    /** 自动扫描立方体的边长（5 表示 5x5x5）。 */
    public static int scanRange() {
        return Math.max(1, scanRange);
    }

    /** 由边长换算出的半径：5 边长 -> 半径 2，正好覆盖 5x5x5。 */
    public static int scanRadius() {
        return scanRange() / 2;
    }

    public static int maxStorages() {
        return Math.max(1, maxStorages);
    }

    public static int maxInputBindings() {
        return Math.max(1, maxInputBindings);
    }

    public static int maxOutputBindings() {
        return Math.max(1, maxOutputBindings);
    }

    public static int autoCraftLimit() {
        return Math.max(1, autoCraftLimit);
    }

    /** 链式合成的最大深度（目标产物向下自动补中间材料的层数）。 */
    public static int recursiveDepth() {
        return Math.max(1, recursiveDepth);
    }

    public static int maxListSize() {
        // 老配置里留下的 384 及以下的值是旧版本的硬上限，统一顶到新默认值
        int effective = listSlots <= LEGACY_LIST_LIMIT ? DEFAULT_LIST_SIZE : listSlots;
        return Math.max(12, Math.min(MAX_LIST_SIZE, effective));
    }

    public static boolean isBlacklisted(Block block) {
        if (blacklist.length == 0) {
            return false;
        }
        ResourceLocation location = Block.REGISTRY.getNameForObject(block);
        if (location == null) {
            return false;
        }
        String full = location.toString();
        for (String entry : blacklist) {
            if (entry != null && full.equalsIgnoreCase(entry.trim())) {
                return true;
            }
        }
        return false;
    }
}
