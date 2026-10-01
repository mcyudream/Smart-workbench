package cn.blockforge.smartworkbench.client;

import net.minecraftforge.fml.client.registry.ClientRegistry;
import net.minecraft.client.settings.KeyBinding;
import org.lwjgl.input.Keyboard;

/**
 * 智能工作台的按键，全部在「选项 → 控制 → 按键绑定 → 智能工作台」里可以改成自己顺手的键。
 * <ul>
 *   <li>刷新键：默认 {@code R}，等同于界面上的「刷新」按钮；</li>
 *   <li>批量合成键：默认 <b>左 Ctrl</b>，按住它再点鼠标左键——点右侧列表项批量合成那一项，
 *       点产物格批量合成合成格里摆好的配方（自己手动摆的料也算），直到材料用完；</li>
 *   <li>全部展开/折叠键：默认 <b>空格</b>，按住它再点鼠标左键（点在右侧列表区域上）。</li>
 * </ul>
 * 批量合成和全部展开/折叠注册成「键盘键」：鼠标点击时实时查询这两个键此刻是否被按住，
 * 玩家在按键绑定界面改绑后立刻生效，不需要重开游戏。
 * 自动合成固定是 <b>Alt+鼠标左键</b>（1.12.2 的按键绑定不支持「修饰键+鼠标」组合）。
 */
public final class ModKeybindings {

    public static final String CATEGORY = "key.categories.smart_workbench";

    public static final KeyBinding REFRESH = new KeyBinding(
            "key.smart_workbench.refresh", Keyboard.KEY_R, CATEGORY);

    /** 批量合成的修饰键（默认左 Ctrl）：按住它 + 鼠标左键触发，键位可自己改。 */
    public static final KeyBinding BATCH_CRAFT = new KeyBinding(
            "key.smart_workbench.batch_craft", Keyboard.KEY_LCONTROL, CATEGORY);

    /** 全部展开 / 全部折叠的修饰键（默认空格）：按住它 + 鼠标左键点列表触发，键位可自己改。 */
    public static final KeyBinding EXPAND_ALL = new KeyBinding(
            "key.smart_workbench.expand_all", Keyboard.KEY_SPACE, CATEGORY);

    private ModKeybindings() {
    }

    /** 在客户端 init 阶段注册。 */
    public static void register() {
        ClientRegistry.registerKeyBinding(REFRESH);
        ClientRegistry.registerKeyBinding(BATCH_CRAFT);
        ClientRegistry.registerKeyBinding(EXPAND_ALL);
    }

    /** 此刻这个键是不是被按住（用于「按住键盘键 + 鼠标左键」的手势）。 */
    public static boolean isHeld(KeyBinding binding) {
        try {
            int code = binding.getKeyCode();
            return code >= 0 && Keyboard.isKeyDown(code);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 手势键的显示名（如「Left Ctrl」）；未绑定返回 null。 */
    public static String heldKeyName(KeyBinding binding) {
        try {
            int code = binding.getKeyCode();
            if (code < 0) {
                return null;
            }
            return Keyboard.getKeyName(code);
        } catch (Throwable ignored) {
            return null;
        }
    }
}
