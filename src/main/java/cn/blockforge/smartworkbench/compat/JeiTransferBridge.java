package cn.blockforge.smartworkbench.compat;

import cn.blockforge.smartworkbench.ModNetwork;
import cn.blockforge.smartworkbench.SmartWorkbenchMod;
import cn.blockforge.smartworkbench.container.SmartWorkbenchContainer;
import cn.blockforge.smartworkbench.network.C2SPlaceRecipePacket;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.item.crafting.IRecipe;
import net.minecraft.item.crafting.Ingredient;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * 和 JEI / HEI（HadEnoughItems，JEI 的 1.12.2 分支，类名仍为 mezz.jei.*）对接的反射桥。
 * <p>
 * <b>为什么全用反射：</b>不引入编译期依赖；玩家不装 JEI/HEI 时这段代码根本不会执行，
 * 1.12.2 的 JEI 4.x 和 HEI 的内部结构略有差异，反射 + 按名字找字段最稳。
 * <p>
 * <b>它解决什么问题：</b>JEI 判断「原料够不够」时只数玩家背包和容器槽位，看不到我们接在
 * 箱子上的存储，所以明明箱子里有料也会报缺料、加号点不动。这里把工作台自己的转移处理器
 * 塞进 JEI 运行时的处理器表：缺料判断把接入存储算进去，点加号不让 JEI 搬物品，而是把配方
 * id 发给服务端，由服务端从存储取料摆进合成格。
 * <p>
 * 1.12.2 的注入路径：{@code Internal.getRuntime().getRecipeRegistry()} 拿到内部 RecipeRegistry，
 * 把它的 {@code recipeTransferHandlers}（guava ImmutableTable）照抄一份再补上我们这一行，
 * 反射写回（JEI 重载会重建这张表，所以界面每 tick 补挂一次）。
 */
public final class JeiTransferBridge {

    private static final String HANDLER_NAME = "mezz.jei.api.recipe.transfer.IRecipeTransferHandler";
    private static final String ERROR_TOOLTIP_NAME = "mezz.jei.transfer.RecipeTransferErrorTooltip";
    private static final String INTERNAL_NAME = "mezz.jei.Internal";
    private static final String RECIPE_REGISTRY_NAME = "mezz.jei.recipes.RecipeRegistry";
    private static final String CATEGORY_UID_NAME = "mezz.jei.api.recipe.VanillaRecipeCategoryUid";

    private static boolean prepared;
    private static boolean available;

    private static Class<?> handlerClass;
    private static Object craftingCategoryUid;
    private static Object handlerProxy;

    private static Method getRuntimeMethod;
    private static Field handlersField;
    private static java.lang.reflect.Constructor<?> errorConstructor;
    private static Field wrapperField;
    private static Method wrapperRawRecipeMethod;

    /** 最近一次已经挂过处理器的 JEI 运行时注册表；换了新的（JEI 重载）才需要重挂。 */
    private static Object lastRegistry;

    private JeiTransferBridge() {
    }

    /**
     * 打开智能工作台界面时调用：确保我们的处理器在 JEI 的转移处理器表里。
     * JEI 重载会重建这张表，所以界面每 tick 都来问一次（已经在就只做一次引用比较）。
     */
    public static void ensureRegistered() {
        if (!prepare()) {
            return;
        }
        try {
            Object runtime = getRuntimeMethod.invoke(null);
            if (runtime == null) {
                return;
            }
            Object registry = invokeGetter(runtime, "getRecipeRegistry");
            if (registry == null || registry == lastRegistry) {
                return;
            }
            lastRegistry = registry;
            Object table = handlersField.get(registry);
            if (table == null) {
                return;
            }
            if (tableContains(table, SmartWorkbenchContainer.class, craftingCategoryUid)) {
                return;
            }
            handlersField.set(registry, rebuildTable(table));
        } catch (Throwable ignored) {
            // JEI 还没起好或版本对不上：下次界面 tick 再试
        }
    }

    private static synchronized boolean prepare() {
        if (prepared) {
            return available;
        }
        prepared = true;
        try {
            handlerClass = Class.forName(HANDLER_NAME);
            Class<?> internalClass = Class.forName(INTERNAL_NAME);
            Class<?> registryClass = Class.forName(RECIPE_REGISTRY_NAME);

            craftingCategoryUid = Class.forName(CATEGORY_UID_NAME).getField("CRAFTING").get(null);
            getRuntimeMethod = internalClass.getMethod("getRuntime");
            handlersField = findHandlersField(registryClass);
            errorConstructor = Class.forName(ERROR_TOOLTIP_NAME).getConstructor(String.class);

            // JEI 4.x 的 RecipeLayout（mezz.jei.gui.recipes.RecipeLayout）里存着配方包装器
            Class<?> layoutClass = findClass("mezz.jei.gui.recipes.RecipeLayout");
            if (layoutClass != null) {
                for (Field field : layoutClass.getDeclaredFields()) {
                    if (field.getType().getName().endsWith("IRecipeWrapper")) {
                        field.setAccessible(true);
                        wrapperField = field;
                        break;
                    }
                }
            }
            // 原版合成包装器有 getRawRecipe()；别的模组的包装器没有就靠字段扫描兜底
            Class<?> wrapperInterface = findClass("mezz.jei.api.recipe.IRecipeWrapper");
            if (wrapperInterface != null) {
                for (Method method : wrapperInterface.getMethods()) {
                    if (method.getParameterCount() == 0 && method.getName().equals("getRawRecipe")) {
                        wrapperRawRecipeMethod = method;
                        break;
                    }
                }
            }
            if (wrapperRawRecipeMethod == null) {
                Class<?> shapedWrapper = findClass("mezz.jei.plugins.vanilla.crafting.ShapelessRecipeWrapper");
                if (shapedWrapper != null) {
                    for (Method method : shapedWrapper.getMethods()) {
                        if (method.getParameterCount() == 0 && method.getName().equals("getRawRecipe")) {
                            wrapperRawRecipeMethod = method;
                            break;
                        }
                    }
                }
            }

            handlerProxy = Proxy.newProxyInstance(handlerClass.getClassLoader(),
                    new Class<?>[]{handlerClass}, new Handler());
            available = true;
        } catch (Throwable ignored) {
            available = false;
        }
        return available;
    }

    private static Class<?> findClass(String name) {
        try {
            return Class.forName(name);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 表字段名在 JEI/HEI 里都叫 recipeTransferHandlers；万一改名就认 ImmutableTable 类型的那个。 */
    private static Field findHandlersField(Class<?> registryClass) throws NoSuchFieldException {
        for (Field field : registryClass.getDeclaredFields()) {
            if (field.getType().getName().endsWith("ImmutableTable")) {
                field.setAccessible(true);
                return field;
            }
        }
        Field field = registryClass.getDeclaredField("recipeTransferHandlers");
        field.setAccessible(true);
        return field;
    }

    /** 同名方法可能有协变重载（返回内部类型/接口类型），随便调哪个都行。 */
    private static Object invokeGetter(Object target, String name) throws Exception {
        for (Method method : target.getClass().getMethods()) {
            if (method.getName().equals(name) && method.getParameterCount() == 0) {
                method.setAccessible(true);
                return method.invoke(target);
            }
        }
        return null;
    }

    private static boolean tableContains(Object table, Object row, Object column) throws Exception {
        Class<?> tableClass = Class.forName("com.google.common.collect.Table");
        Object result = tableClass.getMethod("contains", Object.class, Object.class).invoke(table, row, column);
        return Boolean.TRUE.equals(result);
    }

    /**
     * ImmutableTable 是不可变的，想加一行就得照原样抄一份再补上我们的那一行。
     * 这里刻意不 import Guava，全部按名字反射，避免给工程加依赖。
     */
    private static Object rebuildTable(Object table) throws Exception {
        Class<?> tableClass = Class.forName("com.google.common.collect.Table");
        Class<?> immutableTableClass = Class.forName("com.google.common.collect.ImmutableTable");
        Class<?> hashBasedTableClass = Class.forName("com.google.common.collect.HashBasedTable");
        Class<?> cellClass = Class.forName("com.google.common.collect.Table$Cell");

        Object copy = hashBasedTableClass.getMethod("create").invoke(null);
        Method put = tableClass.getMethod("put", Object.class, Object.class, Object.class);
        Method cellSet = tableClass.getMethod("cellSet");
        Method getRowKey = cellClass.getMethod("getRowKey");
        Method getColumnKey = cellClass.getMethod("getColumnKey");
        Method getValue = cellClass.getMethod("getValue");

        for (Object cell : (Collection<?>) cellSet.invoke(table)) {
            put.invoke(copy, getRowKey.invoke(cell), getColumnKey.invoke(cell), getValue.invoke(cell));
        }
        put.invoke(copy, SmartWorkbenchContainer.class, craftingCategoryUid, handlerProxy);
        return immutableTableClass.getMethod("copyOf", tableClass).invoke(null, copy);
    }

    /* ---------------- 处理器本体 ---------------- */

    /** 我们塞给 JEI 的转移处理器；JEI 只调 getContainerClass / transferRecipe。 */
    private static final class Handler implements InvocationHandler {

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            String name = method.getName();
            if ("getContainerClass".equals(name)) {
                return SmartWorkbenchContainer.class;
            }
            if ("transferRecipe".equals(name)) {
                return safeTransfer(args);
            }
            if ("equals".equals(name)) {
                return args != null && args.length == 1 && proxy == args[0];
            }
            if ("hashCode".equals(name)) {
                return System.identityHashCode(proxy);
            }
            if ("toString".equals(name)) {
                return "SmartWorkbenchJeiTransferHandler";
            }
            return defaultValue(method.getReturnType());
        }
    }

    private static Object safeTransfer(Object[] args) {
        try {
            return transfer(args);
        } catch (Throwable ignored) {
            // 出问题就当没这回事，把按钮交回给 JEI 自己显示，别把界面搞崩
            return null;
        }
    }

    private static Object transfer(Object[] args) throws Exception {
        if (args == null || args.length < 5) {
            return null;
        }
        Object containerArg = args[0];
        Object layoutArg = args[1];
        EntityPlayer player = (EntityPlayer) args[2];
        boolean maxTransfer = Boolean.TRUE.equals(args[3]);
        boolean doTransfer = Boolean.TRUE.equals(args[4]);
        if (!(containerArg instanceof SmartWorkbenchContainer)) {
            return null;
        }
        SmartWorkbenchContainer menu = (SmartWorkbenchContainer) containerArg;
        IRecipe recipe = extractRecipe(layoutArg);
        if (recipe == null || !menu.canAutoPlace(recipe)) {
            return userError("这个配方不支持一键取料");
        }
        List<ItemStack> missing = findMissing(recipe,
                MaterialAvailability.collectAvailable(menu, player));
        // 快照只用于按钮悬停提示；真正点击时必须交给服务端重新读取存储，
        // 否则箱子刚被漏斗补料就会被旧快照误判为缺料。
        if (!missing.isEmpty() && !doTransfer) {
            return userError("接入存储和背包里还缺 " + missing.size() + " 种材料");
        }
        if (doTransfer && missing.isEmpty()) {
            if (recipe.getRegistryName() != null) {
                ModNetwork.sendToServer(new C2SPlaceRecipePacket(
                        menu.getBenchPos(), recipe.getRegistryName(), maxTransfer));
            }
        }
        return null;
    }

    /** 从 JEI 的配方布局里挖出原版 IRecipe：包装器有 getRawRecipe 就直接拿，没有再扫字段。 */
    private static IRecipe extractRecipe(Object layout) throws Exception {
        if (layout == null || wrapperField == null) {
            return null;
        }
        Object wrapper = wrapperField.get(layout);
        if (wrapper == null) {
            return null;
        }
        if (wrapperRawRecipeMethod != null) {
            try {
                Object raw = wrapperRawRecipeMethod.invoke(wrapper);
                if (raw instanceof IRecipe) {
                    return (IRecipe) raw;
                }
            } catch (Throwable ignored) {
                // 这个包装器不认 getRawRecipe，走字段扫描
            }
        }
        Class<?> type = wrapper.getClass();
        while (type != null && type != Object.class) {
            for (Field field : type.getDeclaredFields()) {
                if (IRecipe.class.isAssignableFrom(field.getType())) {
                    field.setAccessible(true);
                    Object value = field.get(wrapper);
                    if (value instanceof IRecipe) {
                        return (IRecipe) value;
                    }
                }
            }
            type = type.getSuperclass();
        }
        return null;
    }

    private static List<ItemStack> findMissing(IRecipe recipe, List<ItemStack> available) {
        List<Ingredient> ingredients = recipe.getIngredients();
        List<List<ItemStack>> candidates = new ArrayList<List<ItemStack>>();
        for (Ingredient ingredient : ingredients) {
            if (ingredient == null || ingredient == Ingredient.EMPTY
                    || ingredient.getMatchingStacks().length == 0) {
                continue;
            }
            List<ItemStack> options = new ArrayList<ItemStack>();
            for (ItemStack stack : ingredient.getMatchingStacks()) {
                if (!stack.isEmpty()) {
                    options.add(stack);
                }
            }
            if (!options.isEmpty()) {
                candidates.add(options);
            }
        }
        boolean[] matched = MaterialAvailability.match(candidates, available);
        List<ItemStack> missing = new ArrayList<ItemStack>();
        for (int i = 0; i < matched.length; i++) {
            if (!matched[i]) {
                missing.add(candidates.get(i).get(0));
            }
        }
        return missing;
    }

    /** 缺料时给 JEI 的「用户可见错误」：按钮会变红并显示我们的提示。 */
    private static Object userError(String reason) {
        try {
            return errorConstructor.newInstance(reason);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Object defaultValue(Class<?> type) {
        if (type == boolean.class) {
            return Boolean.FALSE;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == float.class) {
            return 0.0F;
        }
        if (type == double.class) {
            return 0.0D;
        }
        if (type == short.class) {
            return (short) 0;
        }
        if (type == byte.class) {
            return (byte) 0;
        }
        if (type == char.class) {
            return (char) 0;
        }
        return null;
    }
}
