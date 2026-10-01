package cn.blockforge.smartworkbench.client;

import net.minecraft.client.resources.IResource;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * 离线拼音表：把汉字映射成不带声调的 ASCII 拼音（ü 记作 v）。
 * <p>
 * 数据来自 Unihan 的 kMandarin 字段，随模组打包在
 * {@code assets/smart_workbench/pinyin.tsv.gz}，玩家端不需要联网，
 * 也不依赖 JEI 之类的外部模组。
 * <p>
 * 表是懒加载的：第一次搜索时才读，读进来常驻内存（约两万个汉字，几百 KB）。
 */
@SideOnly(Side.CLIENT)
public final class PinyinTable {

    private static final ResourceLocation RESOURCE =
            new ResourceLocation("smart_workbench", "pinyin.tsv.gz");

    private static Map<Character, String> table;
    private static boolean loaded;

    private PinyinTable() {
    }

    /** 这个汉字的拼音；不是汉字或表里没有返回 null。 */
    public static String pinyin(char ch) {
        if (!loaded) {
            loaded = true;
            table = load();
        }
        return table == null ? null : table.get(ch);
    }

    private static Map<Character, String> load() {
        Map<Character, String> result = new HashMap<Character, String>(8192);
        try {
            IResource resource = net.minecraft.client.Minecraft.getMinecraft().getResourceManager().getResource(RESOURCE);
            InputStream raw = resource.getInputStream();
            try {
                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(new GZIPInputStream(raw), StandardCharsets.UTF_8));
                String line;
                while ((line = reader.readLine()) != null) {
                    int tab = line.indexOf('\t');
                    if (tab <= 0) {
                        continue;
                    }
                    String head = line.substring(0, tab);
                    if (head.isEmpty()) {
                        continue;
                    }
                    result.put(head.charAt(0), line.substring(tab + 1));
                }
                reader.close();
            } finally {
                raw.close();
            }
        } catch (Throwable ignored) {
            // 拼音表读不出来：拼音搜索自然失效，但不影响其他功能
        }
        return result;
    }
}
