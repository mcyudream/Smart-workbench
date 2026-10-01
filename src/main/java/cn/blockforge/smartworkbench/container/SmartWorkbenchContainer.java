package cn.blockforge.smartworkbench.container;

import cn.blockforge.smartworkbench.ModConfig;
import cn.blockforge.smartworkbench.ModNetwork;
import cn.blockforge.smartworkbench.SmartWorkbenchMod;
import cn.blockforge.smartworkbench.compat.PolymorphCompat;
import cn.blockforge.smartworkbench.gui.GuiLayout;
import cn.blockforge.smartworkbench.network.S2CAutoRefillPacket;
import cn.blockforge.smartworkbench.network.S2CCraftableListPacket;
import cn.blockforge.smartworkbench.network.S2CRecursiveCraftPromptPacket;
import cn.blockforge.smartworkbench.tile.SmartWorkbenchTile;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.InventoryCraftResult;
import net.minecraft.inventory.InventoryCrafting;
import net.minecraft.inventory.Slot;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.crafting.CraftingManager;
import net.minecraft.item.crafting.IRecipe;
import net.minecraft.item.crafting.Ingredient;
import net.minecraft.item.crafting.ShapedRecipes;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.NonNullList;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.text.TextComponentTranslation;
import net.minecraftforge.common.ForgeHooks;
import net.minecraftforge.common.crafting.IShapedRecipe;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.ItemHandlerHelper;
import net.minecraftforge.items.wrapper.PlayerMainInvWrapper;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 智能工作台的容器：3x3 合成格存在方块实体里（关界面不丢东西），
 * 右侧列表支持「从附近存储取料进合成格」和「直接从存储合成一次」。
 */
public class SmartWorkbenchContainer extends Container {

    public static final int SLOT_RESULT = 0;
    public static final int GRID_START = 1;
    public static final int GRID_END = GRID_START + SmartWorkbenchTile.GRID_SIZE;
    public static final int INV_START = GRID_END;
    public static final int INV_END = INV_START + 36;

    /** 界面上的「刷新」按钮（1.12.2 的按钮通道走 enchantItem）。 */
    public static final int BUTTON_REFRESH = 0;
    /** 左侧「自动补齐」开关。 */
    public static final int BUTTON_AUTO_REFILL = 1;
    /** 产物格上的 Ctrl+左键：把合成格里摆好的这份配方批量合成（材料不论来源，手动摆的也算）。 */
    public static final int BUTTON_BATCH_GRID = 2;

    /**
     * 自动补齐开关在玩家存档上的键。
     * <p>
     * 存在玩家身上而不是菜单上：菜单每次打开都会新建一个，开关放在菜单里就永远是默认值。
     */
    private static final String AUTO_REFILL_TAG = "SmartWorkbenchAutoRefill";

    /** 合成格行列数也取自 GuiLayout，改贴图布局时一处改。 */
    private static final int COLUMNS = GuiLayout.GRID_COLS;
    private static final int ROWS = GuiLayout.GRID_ROWS;

    /** 发给客户端的存储快照最多保留多少种物品，防止大仓库把包撑爆 */
    private static final int STORAGE_SNAPSHOT_LIMIT = 512;
    /** Ctrl 批量合成一次最多做多少个，防止点一下就把整仓库抽干或卡住 */
    private static final int MAX_BATCH_CRAFTS = 256;
    /** 递归补齐最多展开这么多次，避免错误配方或循环配方拖住服务器。 */
    private static final int MAX_RECURSIVE_STEPS = 256;
    /** 递归补齐的最大深度走配置 craft.recursiveDepth（默认 5 层，见 ModConfig）。 */

    /** 客户端正在显示的“自动补齐”确认框；服务端不会依赖这些字段做权限判断。 */
    private ResourceLocation recursivePromptRecipeId;
    private List<S2CRecursiveCraftPromptPacket.MissingEntry> recursivePromptAuto = Collections.emptyList();
    private List<S2CRecursiveCraftPromptPacket.MissingEntry> recursivePromptUnavailable = Collections.emptyList();

    /** craftOnce 的结果：成功 / 缺料 / 配方不支持 / 背包满了（产物已掉在地上） */
    private static final int CRAFT_OK = 0;
    private static final int CRAFT_MISSING = 1;
    private static final int CRAFT_UNSUPPORTED = 2;
    private static final int CRAFT_INVENTORY_FULL = 3;
    /** 绑定了输出容器但容器满了，产物已退回背包，本次自动合成到此为止。 */
    private static final int CRAFT_OUTPUT_FULL = 4;

    private final net.minecraft.world.World world;
    private final EntityPlayer player;
    private final net.minecraft.util.math.BlockPos benchPos;
    private final SmartWorkbenchTile bench;
    private final TileInventoryCrafting matrix;
    /** 合成格的只读料源视图，仅供「可不够合成」这类扫描使用（见 {@link #scanHandlers()}）。 */
    private final MatrixScanHandler matrixScanHandler;
    private final InventoryCraftResult resultSlots = new InventoryCraftResult();
    /**
     * 玩家自身背包（主背包 + 快捷栏）的处理器。
     * <p>
     * 很多玩家习惯把材料直接放身上，所以除了箱子之外，玩家自己的物品栏也是一路料源。
     * 只覆盖 0~35 号槽，不会把身上穿的盔甲也拿去当材料。
     */
    private final IItemHandler playerInventoryHandler;
    /** 合成格中由存储取来的材料来源；切换配方时优先退回原槽位。 */
    private final List<SourcePart>[] matrixSources = createSourceLists();
    private boolean updatingMatrix;

    @SuppressWarnings("unchecked")
    private static List<SourcePart>[] createSourceLists() {
        List<SourcePart>[] sources = (List<SourcePart>[]) new List<?>[SmartWorkbenchTile.GRID_SIZE];
        for (int i = 0; i < sources.length; i++) {
            sources[i] = new ArrayList<SourcePart>();
        }
        return sources;
    }

    private static final class SourcePart {
        final ItemStack stack;
        final StoragePool.Source source;

        SourcePart(ItemStack stack, StoragePool.Source source) {
            this.stack = stack;
            this.source = source;
        }
    }

    /**
     * 合成格的只读 IItemHandler 视图：让「材料够不够」的模拟扫描把格子里的东西也当成库存。
     * <p>
     * extractItem 一律返回空——真正抽料必须走 {@code placeRecipeIntoGrid} /
     * {@code returnMatrixMaterials} 那两条会同步维护来源记录（matrixSources）的路。
     */
    private static final class MatrixScanHandler implements IItemHandler {
        private final InventoryCrafting matrix;

        MatrixScanHandler(InventoryCrafting matrix) {
            this.matrix = matrix;
        }

        @Override
        public int getSlots() {
            return this.matrix.getSizeInventory();
        }

        @Override
        public ItemStack getStackInSlot(int slot) {
            return this.matrix.getStackInSlot(slot);
        }

        @Override
        public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
            return stack;
        }

        @Override
        public ItemStack extractItem(int slot, int amount, boolean simulate) {
            return ItemStack.EMPTY;
        }

        @Override
        public int getSlotLimit(int slot) {
            return 64;
        }

        @Override
        public boolean isItemValid(int slot, ItemStack stack) {
            return false;
        }
    }

    private String matrixSignature = "";
    private List<CraftableEntry> craftables = Collections.emptyList();
    /** 普通点击缺料时是否询问并自动递归补齐中间材料。 */
    private boolean autoRefillEnabled = true;
    /** 服务端是否已经把开关的真实状态发过给客户端。 */
    private boolean autoRefillSynced;
    /** 客户端专用的接入存储快照，给 JEI 桥判断「原料够不够」用 */
    private List<ItemStack> clientStorageStacks = Collections.emptyList();
    /** 客户端是否已经收到过服务器推来的存储快照；没收到时缺料判断不可信。 */
    private boolean clientStorageSynced;
    /** 客户端显示用；服务端的真实数量仍从方块实体读取。 */
    private int clientConnectedStorageCount;
    /** 客户端显示用：绑定的输出容器数量。 */
    private int clientOutputCount;
    private String listSignature = "";
    private long nextRefreshTick;
    /** 同一个菜单内复用普通合成配方索引，避免递归规划时反复遍历全量配方。 */
    private List<IRecipe> plainRecipes;
    private Map<Item, List<IRecipe>> recipesByResult;
    private long recipeIndexVersion = Long.MIN_VALUE;
    /** 所有客户端操作共用一个服务端冷却，防止连点堆积主线程任务。 */
    private long nextActionTick;
    private CraftFeedback activeFeedback;
    /**
     * 玩家在本界面明确选过的配方（点列表 / JEI 取料）。
     * 同一件产物可能有好几个模组的配方都能匹配，没装多态合成时就靠它保证产物格
     * 显示的就是玩家点的那一份，而不是原版随手取的第一份。
     */
    private IRecipe preferredRecipe;
    /** 上一次算产物用的配方：拿走产物时要按同一份配方算剩余物（桶、瓶子之类）。 */
    private IRecipe lastGridRecipe;
    /**
     * 「连续合成」锁定的配方：只有 JEI 的加号、右侧列表这些<b>明确点配方</b>摆进
     * 合成格的料才算数；玩家一格一格手动摆的料不算。拿走产物后按它自动补一份同样的材料，
     * 这样点一次加号就能连续合成。
     */
    private IRecipe stickyRecipe;
    /** 锁定时用的是不是「摆满」取料（Shift+加号）：补料时补到同样的量。 */
    private boolean stickyStackAll;

    public SmartWorkbenchContainer(InventoryPlayer inventory, SmartWorkbenchTile tile) {
        this.world = inventory.player.world;
        this.player = inventory.player;
        this.playerInventoryHandler = new PlayerMainInvWrapper(inventory);
        this.benchPos = tile.getPos();
        this.bench = tile;

        this.matrix = new TileInventoryCrafting(this, tile, COLUMNS, ROWS);
        this.matrixScanHandler = new MatrixScanHandler(this.matrix);

        this.addSlotToContainer(new WorkbenchResultSlot(this, this.player, this.matrix,
                this.resultSlots, 0, GuiLayout.OUTPUT_ITEM_X, GuiLayout.OUTPUT_ITEM_Y));
        for (int row = 0; row < ROWS; row++) {
            for (int col = 0; col < COLUMNS; col++) {
                this.addSlotToContainer(new MatrixSlot(this.matrix, col + row * COLUMNS,
                        GuiLayout.GRID_ITEM_X + col * GuiLayout.GRID_PITCH,
                        GuiLayout.GRID_ITEM_Y + row * GuiLayout.GRID_PITCH));
            }
        }
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                // 背包槽位下标 9~35 是主背包，0~8 是快捷栏，别写成 col + row * 9
                this.addSlotToContainer(new Slot(inventory, col + (row + 1) * 9,
                        GuiLayout.INV_ITEM_X + col * GuiLayout.INV_PITCH,
                        GuiLayout.INV_ITEM_Y + row * GuiLayout.INV_PITCH));
            }
        }
        for (int col = 0; col < 9; col++) {
            this.addSlotToContainer(new Slot(inventory, col,
                    GuiLayout.HOTBAR_ITEM_X + col * GuiLayout.HOTBAR_PITCH, GuiLayout.HOTBAR_ITEM_Y));
        }
        // 自动补齐开关跟着玩家存档走：读上一次的选择，没存过才用默认的「开」。
        if (!this.world.isRemote) {
            NBTTagCompound persisted = this.player.getEntityData()
                    .getCompoundTag(EntityPlayer.PERSISTED_NBT_TAG);
            if (persisted.hasKey(AUTO_REFILL_TAG)) {
                this.autoRefillEnabled = persisted.getBoolean(AUTO_REFILL_TAG);
            }
        }
    }

    private final class MatrixSlot extends Slot {
        MatrixSlot(net.minecraft.inventory.IInventory inventory, int slot, int x, int y) {
            super(inventory, slot, x, y);
        }

        @Override
        public void putStack(ItemStack stack) {
            if (!SmartWorkbenchContainer.this.updatingMatrix) {
                SmartWorkbenchContainer.this.matrixSources[this.getSlotIndex()].clear();
                // 玩家自己动手往格子里放/挪了东西：连续合成的约定作废
                SmartWorkbenchContainer.this.clearStickyRecipe();
            }
            super.putStack(stack);
        }

        @Override
        public ItemStack decrStackSize(int amount) {
            SmartWorkbenchContainer.this.clearStickyRecipe();
            return super.decrStackSize(amount);
        }

        @Override
        public ItemStack onTake(EntityPlayer player, ItemStack stack) {
            SmartWorkbenchContainer.this.clearStickyRecipe();
            return super.onTake(player, stack);
        }
    }

    /* 基本信息 */

    @Override
    public boolean canInteractWith(EntityPlayer player) {
        return this.bench != null && this.bench.isUsableByPlayer(player);
    }

    public net.minecraft.util.math.BlockPos getBenchPos() {
        return this.benchPos;
    }

    public InventoryCrafting getMatrix() {
        return this.matrix;
    }

    public List<ItemStack> getMatrixItems() {
        List<ItemStack> items = new ArrayList<ItemStack>(this.matrix.getSizeInventory());
        for (int i = 0; i < this.matrix.getSizeInventory(); i++) {
            items.add(this.matrix.getStackInSlot(i));
        }
        return items;
    }

    public IRecipe getLastGridRecipe() {
        return this.lastGridRecipe;
    }

    public int getConnectedStorageCount() {
        return this.world.isRemote
                ? this.clientConnectedStorageCount
                : (this.bench == null ? 0 : this.bench.getConnectedStorageCount());
    }

    public void setClientConnectedStorageCount(int count) {
        this.clientConnectedStorageCount = Math.max(0, count);
    }

    public int getOutputCount() {
        return this.world.isRemote
                ? this.clientOutputCount
                : (this.bench == null ? 0 : this.bench.getOutputBindingCount());
    }

    public void setClientOutputCount(int count) {
        this.clientOutputCount = Math.max(0, count);
    }

    public boolean isAutoRefillEnabled() {
        return this.autoRefillEnabled;
    }

    public void setAutoRefillEnabled(boolean enabled) {
        this.autoRefillEnabled = enabled;
    }

    public List<CraftableEntry> getCraftables() {
        return this.craftables;
    }

    /** 服务器算好的列表推给客户端，客户端只负责显示。 */
    public void setClientCraftables(List<CraftableEntry> entries) {
        this.craftables = entries;
    }

    /** 服务器推来的接入存储快照（同类已合并），JEI 桥的缺料判断会用到。 */
    public void setClientStorageStacks(List<ItemStack> stacks) {
        this.clientStorageStacks = stacks;
        this.clientStorageSynced = true;
    }

    public List<ItemStack> getClientStorageStacks() {
        return this.clientStorageStacks;
    }

    /** 客户端有没有拿到过这份存储快照。没拿到之前，「缺料」的结论是不准的。 */
    public boolean hasClientStorageSnapshot() {
        return this.clientStorageSynced;
    }

    /**
     * 合成可用的全部料源：接入的容器在前，玩家自己的背包在最后。
     * 因为玩家背包排在后面，箱子里的材料会优先被用掉，符合“先花箱子里的存货”的直觉。
     */
    private List<IItemHandler> inputHandlers() {
        List<IItemHandler> handlers = new ArrayList<IItemHandler>();
        if (this.bench != null) {
            handlers.addAll(this.bench.getStorages());
        }
        if (this.playerInventoryHandler != null) {
            handlers.add(this.playerInventoryHandler);
        }
        return handlers;
    }

    /**
     * 「能不能合成」这类<b>只读</b>扫描用的料源：{@link #inputHandlers()} 再加上合成格自己。
     * <p>
     * 点一下列表把材料整份搬进合成格之后，箱子和背包可能已经一滴不剩——这时工作台<b>自己身上</b>
     * （合成格里的料）就是唯一的库存。把合成格算进来之后，切配方来回挑选都不用先把料退回去。
     * 真正要抽料、退料的路径（Shift 直接合成、Ctrl 批量、自动合成）仍然只用 {@link #inputHandlers()}。
     */
    private List<IItemHandler> scanHandlers() {
        List<IItemHandler> handlers = inputHandlers();
        handlers.add(this.matrixScanHandler);
        return handlers;
    }

    public boolean hasRecursivePrompt() {
        return this.recursivePromptRecipeId != null;
    }

    public ResourceLocation getRecursivePromptRecipeId() {
        return this.recursivePromptRecipeId;
    }

    public List<S2CRecursiveCraftPromptPacket.MissingEntry> getRecursivePromptAuto() {
        return this.recursivePromptAuto;
    }

    public List<S2CRecursiveCraftPromptPacket.MissingEntry> getRecursivePromptUnavailable() {
        return this.recursivePromptUnavailable;
    }

    public void setRecursivePrompt(ResourceLocation recipeId,
                                   List<S2CRecursiveCraftPromptPacket.MissingEntry> autoCraftable,
                                   List<S2CRecursiveCraftPromptPacket.MissingEntry> unavailable) {
        this.recursivePromptRecipeId = recipeId;
        this.recursivePromptAuto = new ArrayList<S2CRecursiveCraftPromptPacket.MissingEntry>(autoCraftable);
        this.recursivePromptUnavailable = new ArrayList<S2CRecursiveCraftPromptPacket.MissingEntry>(unavailable);
    }

    public void clearRecursivePrompt() {
        this.recursivePromptRecipeId = null;
        this.recursivePromptAuto = Collections.emptyList();
        this.recursivePromptUnavailable = Collections.emptyList();
    }

    public net.minecraft.world.World getLevel() {
        return this.world;
    }

    /* 来源记录维护 */

    private void clearMatrixSources() {
        for (List<SourcePart> sources : this.matrixSources) {
            sources.clear();
        }
    }

    private void consumeOneSource(int slot) {
        List<SourcePart> sources = this.matrixSources[slot];
        for (int i = 0; i < sources.size(); i++) {
            SourcePart part = sources.get(i);
            if (part.stack.isEmpty()) {
                sources.remove(i--);
                continue;
            }
            part.stack.shrink(1);
            if (part.stack.isEmpty()) {
                sources.remove(i);
            }
            return;
        }
    }

    private void removeSourceCount(int slot, int count) {
        for (int i = 0; i < count; i++) {
            consumeOneSource(slot);
        }
    }

    private void clearMatrixSourcesAt(int slot) {
        this.matrixSources[slot].clear();
    }

    private void setMatrixInternal(int slot, ItemStack stack) {
        this.updatingMatrix = true;
        try {
            this.matrix.setInventorySlotContents(slot, stack);
        } finally {
            this.updatingMatrix = false;
        }
    }

    /** 菜单内部刷新格子时保留已记录的存储来源；玩家或 JEI 直接写入仍会清掉来源。 */
    private void setMatrixFromMenu(int slot, ItemStack stack) {
        setMatrixInternal(slot, stack);
    }

    private void trimMatrixSources(int slot, int maxCount) {
        List<SourcePart> sources = this.matrixSources[slot];
        int total = 0;
        for (SourcePart part : sources) {
            total += part.stack.getCount();
        }
        for (int i = sources.size() - 1; total > maxCount && i >= 0; i--) {
            SourcePart part = sources.get(i);
            int remove = Math.min(part.stack.getCount(), total - maxCount);
            part.stack.shrink(remove);
            total -= remove;
            if (part.stack.isEmpty()) {
                sources.remove(i);
            }
        }
    }

    @Override
    public void onCraftMatrixChanged(net.minecraft.inventory.IInventory inventoryIn) {
        // 1.12.2 的产物格和原版一样是双端计算的：客户端合成格一变（含服务端同步过来的槽位更新，
        // 槽位 putStack -> markDirty -> 这里），立刻在本地重算产物，界面才不会出现
        // 「材料摆好了产物格却空着、点一下产物才出来」。
        updateResult();
        if (!this.world.isRemote) {
            this.matrixSignature = matrixSignature();
        }
    }

    /**
     * JEI 的加号按钮走这里（见 compat 包的转移处理器）：
     * 服务器按配方 id 把材料从接入存储摆进合成格。
     */
    public void placeRecipeFromClient(EntityPlayerMP player, ResourceLocation recipeId, boolean stackAll) {
        if (!acceptAction(player)) {
            return;
        }
        if (this.world.isRemote || this.bench == null) {
            return;
        }
        IRecipe recipe = resolveRecipe(recipeId);
        if (recipe == null || !isPlainCraft(recipe)) {
            notify(player, "这个配方不支持一键取料", key("unsupported"));
            return;
        }
        this.bench.refreshStorages(true);
        // 预检要算上合成格里已有的料：placeRecipeIntoGrid 本来就会优先复用格子里现成的材料
        StoragePool availablePool = StoragePool.collect(scanHandlers());
        if (!hasAllIngredients(recipe, availablePool)) {
            notify(player, "附近存储和背包材料不足", key("not_enough"));
            refreshCraftables(true);
            return;
        }
        placeRecipeIntoGrid(player, recipe, stackAll);
        markDirty();
        updateResult();
        beginStickyRecipe(player, recipe, stackAll);
        this.matrixSignature = matrixSignature();
        refreshCraftables(false);
    }

    /* 把配方摆进合成格 */

    /**
     * 把一份配方摆进合成格：
     * <ol>
     *   <li>格子里已经符合这份配方的材料留在原地接着用；</li>
     *   <li>不符合配方的材料全部退回玩家背包（背包塞不下就掉在脚下）；</li>
     *   <li>还缺的材料从接入的存储里取，placeAll 时尽量把每格补满。</li>
     * </ol>
     * 返回从存储里实际取出的件数。
     */
    private int placeRecipeIntoGrid(EntityPlayerMP player, IRecipe recipe, boolean placeAll) {
        if (this.bench == null) {
            return 0;
        }
        NonNullList<Ingredient> ingredients = recipe.getIngredients();
        int[] layout = gridLayout(recipe, ingredients);
        // wanted[格子] = 这个格子应该放第几个材料（-1 表示这格不该有东西）
        int[] wanted = new int[SmartWorkbenchTile.GRID_SIZE];
        Arrays.fill(wanted, -1);
        for (int i = 0; i < ingredients.size(); i++) {
            int slot = layout[i];
            if (slot >= 0 && slot < wanted.length && !isUnused(ingredients.get(i))) {
                wanted[slot] = i;
            }
        }

        // 1) 先把工作台里已有的堆叠全部暂存。由存储取来的部分保留原槽位来源，
        //    切换配方后优先退回原容器；手动放入的部分才回玩家背包。
        List<TrackedStack> existing = new ArrayList<TrackedStack>();
        for (int slot = 0; slot < SmartWorkbenchTile.GRID_SIZE; slot++) {
            ItemStack inSlot = this.matrix.getStackInSlot(slot).copy();
            int trackedCount = 0;
            for (SourcePart part : this.matrixSources[slot]) {
                int count = Math.min(part.stack.getCount(), Math.max(0, inSlot.getCount() - trackedCount));
                if (count > 0) {
                    existing.add(new TrackedStack(copyWithCount(part.stack, count), part.source));
                    trackedCount += count;
                }
            }
            int manualCount = inSlot.getCount() - trackedCount;
            if (manualCount > 0) {
                existing.add(new TrackedStack(copyWithCount(inSlot, manualCount), null));
            }
            this.matrixSources[slot].clear();
            setMatrixFromMenu(slot, ItemStack.EMPTY);
        }

        // 2) 缺的从接入的存储里补；工作台已有材料优先，真正多出来的最后才退回原容器。
        this.bench.refreshStorages(true);
        StoragePool pool = StoragePool.collect(inputHandlers());
        int pulled = 0;
        ForgeHooks.setCraftingPlayer(player);
        try {
            for (int i = 0; i < ingredients.size(); i++) {
                Ingredient ingredient = ingredients.get(i);
                int slot = layout[i];
                if (isUnused(ingredient) || slot < 0 || slot >= wanted.length) {
                    continue;
                }
                int targetCount = placeAll ? maxIngredientStackSize(ingredient) : 1;
                ItemStack target = this.matrix.getStackInSlot(slot);
                while (target.getCount() < targetCount) {
                    int need = targetCount - target.getCount();
                    TrackedStack reused = takeMatching(existing, ingredient, Math.min(need, 1));
                    if (reused == null) {
                        int candidate = pool.find(ingredient);
                        if (candidate < 0) {
                            break;
                        }
                        ItemStack extracted = pool.take(candidate, 1);
                        if (extracted.isEmpty()) {
                            break;
                        }
                        reused = new TrackedStack(extracted, pool.source(candidate));
                        pulled++;
                    }
                    ItemStack reusedStack = reused.stack;
                    if (target.isEmpty()) {
                        target = reusedStack;
                    } else if (StoragePool.areSame(target, reusedStack)
                            && target.getCount() + reusedStack.getCount() <= target.getMaxStackSize()) {
                        target.grow(reusedStack.getCount());
                    } else {
                        // 一个目标格不能容纳不兼容的堆叠，放回暂存列表，避免丢失。
                        existing.add(reused);
                        break;
                    }
                    if (reused.source != null) {
                        this.matrixSources[slot].add(new SourcePart(reusedStack.copy(), reused.source));
                    }
                    if (!placeAll) {
                        break;
                    }
                }
                if (!target.isEmpty()) {
                    setMatrixFromMenu(slot, target);
                }
            }
        } finally {
            ForgeHooks.setCraftingPlayer(null);
        }

        // 3) 还没有被新配方用掉的旧材料优先回到原提取槽位，手动材料才回玩家背包。
        for (TrackedStack leftover : existing) {
            if (leftover.stack.isEmpty()) {
                continue;
            }
            ItemStack rest = leftover.source == null ? leftover.stack
                    : leftover.source.handler.insertItem(leftover.source.slot, leftover.stack, false);
            if (!rest.isEmpty() && !player.inventory.addItemStackToInventory(rest)) {
                player.dropItem(rest, false, false);
            }
        }
        return pulled;
    }

    private static final class TrackedStack {
        final ItemStack stack;
        final StoragePool.Source source;

        TrackedStack(ItemStack stack, StoragePool.Source source) {
            this.stack = stack;
            this.source = source;
        }
    }

    /** 从暂存的工作台材料里拿出一件符合材料条件的物品。 */
    private static TrackedStack takeMatching(List<TrackedStack> stacks, Ingredient ingredient, int count) {
        for (int i = 0; i < stacks.size(); i++) {
            TrackedStack tracked = stacks.get(i);
            ItemStack stack = tracked.stack;
            if (!ingredient.apply(stack)) {
                continue;
            }
            int taken = Math.min(Math.max(1, count), stack.getCount());
            ItemStack result = copyWithCount(stack, taken);
            stack.shrink(taken);
            if (stack.isEmpty()) {
                stacks.remove(i);
            }
            return new TrackedStack(result, tracked.source);
        }
        return null;
    }

    /** placeAll 时每个材料格最多摆满该物品自己的最大堆叠数。 */
    private static int maxIngredientStackSize(Ingredient ingredient) {
        for (ItemStack preview : ingredient.getMatchingStacks()) {
            if (!preview.isEmpty()) {
                return preview.getMaxStackSize();
            }
        }
        return 1;
    }

    /* 结果计算 */

    /** 合成格变化后重算产物，逻辑与原版工作台一致（客户端和服务端都算，见 onCraftMatrixChanged 注释）。 */
    public void updateResult() {
        ItemStack result = ItemStack.EMPTY;
        IRecipe chosen = null;
        if (!this.matrix.isEmpty()) {
            chosen = pickGridRecipe();
            if (chosen != null) {
                ItemStack assembled = chosen.getCraftingResult(this.matrix);
                if (!assembled.isEmpty()) {
                    result = assembled;
                }
            }
        }
        this.lastGridRecipe = chosen;
        this.resultSlots.setInventorySlotContents(0, result);
    }

    /**
     * 决定当前合成格该出哪一份配方。一件产物可能同时有多份配方能匹配（不同模组各加了一份），
     * 挑选顺序是：
     * <ol>
     *   <li><b>装了多态合成就听它的</b>：玩家可以用它的按钮在几份配方间切换；</li>
     *   <li>否则用玩家在本界面明确点过的那一份（点列表 / JEI 取料时记下的）；</li>
     *   <li>都没有才回到原版逻辑，取第一份匹配的。</li>
     * </ol>
     */
    private IRecipe pickGridRecipe() {
        IRecipe polymorph = PolymorphCompat.pickRecipe(this, this.matrix, this.world, this.player);
        if (polymorph != null && polymorph.matches(this.matrix, this.world)) {
            return polymorph;
        }
        if (this.preferredRecipe != null && this.preferredRecipe.matches(this.matrix, this.world)) {
            return this.preferredRecipe;
        }
        return CraftingManager.findMatchingRecipe(this.matrix, this.world);
    }

    /** 记下玩家明确选的配方，并同步给多态合成，保证产物格显示的正是这一份。 */
    private void rememberSelection(EntityPlayerMP player, IRecipe recipe) {
        this.preferredRecipe = recipe;
        PolymorphCompat.selectRecipe(this, player, recipe);
    }

    /** 拿走一次产物：每个非空合成格减 1，桶之类的剩余物放回格子或塞给玩家。 */
    public void consumeGrid(EntityPlayer player) {
        if (this.world.isRemote || this.matrix.isEmpty()) {
            return;
        }
        ForgeHooks.setCraftingPlayer(player);
        NonNullList<ItemStack> remaining;
        IRecipe used;
        try {
            // 按「显示产物时用的同一份配方」算剩余物：同产物配方有好几份时，
            // 原版 getRemainingItemsFor 又会去随便挑一份，可能和刚拿走的产物对不上。
            used = this.lastGridRecipe;
            remaining = used != null && used.matches(this.matrix, this.world)
                    ? used.getRemainingItems(this.matrix)
                    : CraftingManager.getRemainingItems(this.matrix, this.world);
        } finally {
            ForgeHooks.setCraftingPlayer(null);
        }
        for (int i = 0; i < remaining.size() && i < SmartWorkbenchTile.GRID_SIZE; i++) {
            ItemStack original = this.matrix.getStackInSlot(i);
            ItemStack replacement = remaining.get(i);
            if (original.isEmpty()) {
                setMatrixFromMenu(i, replacement);
                clearMatrixSourcesAt(i);
            } else if (original.getCount() == 1) {
                removeSourceCount(i, 1);
                setMatrixFromMenu(i, replacement);
                clearMatrixSourcesAt(i);
            } else if (StoragePool.areSame(original, replacement)) {
                removeSourceCount(i, 1);
                replacement.grow(original.getCount() - 1);
                setMatrixFromMenu(i, replacement);
                trimMatrixSources(i, replacement.getCount());
            } else {
                removeSourceCount(i, 1);
                setMatrixFromMenu(i, copyWithCount(original, original.getCount() - 1));
                trimMatrixSources(i, original.getCount() - 1);
                if (!replacement.isEmpty() && !player.inventory.addItemStackToInventory(replacement)) {
                    player.dropItem(replacement, false, false);
                }
            }
        }
        markDirty();
        updateResult();
        refillStickyAfterCraft(player, used);
        this.matrixSignature = matrixSignature();
        refreshCraftables(false);
    }

    /**
     * 记下「连续合成」锁定的配方：只在材料是从 JEI/右侧列表一键摆进格子的、
     * 并且摆完真能出产物时成立。手动一格一格摆的料走不到这里，也就不会被自动补料。
     */
    private void beginStickyRecipe(EntityPlayerMP player, IRecipe recipe, boolean stackAll) {
        if (recipe == null || recipe.getRegistryName() == null) {
            this.stickyRecipe = null;
            return;
        }
        ForgeHooks.setCraftingPlayer(player);
        boolean matches;
        try {
            matches = recipe.matches(this.matrix, this.world);
        } finally {
            ForgeHooks.setCraftingPlayer(null);
        }
        if (!matches) {
            this.stickyRecipe = null;
            return;
        }
        this.stickyRecipe = recipe;
        this.stickyStackAll = stackAll;
    }

    private void clearStickyRecipe() {
        this.stickyRecipe = null;
        this.stickyStackAll = false;
    }

    /**
     * 合成拿走产物后，按锁定的配方把同样的材料再补进合成格，让玩家能一口气合成很多次
     * （这就是「点一次 JEI 加号只能合成一次」的解药）。
     * <p>
     * 只在<b>凑得齐完整一份</b>时才补：凑不齐就干脆不动，免得从仓库里掏出一半材料
     * 卡在格子里。背包 + 接入存储 + 格子里剩下的料加在一起算，够就补。
     */
    private void refillStickyAfterCraft(EntityPlayer player, IRecipe justUsed) {
        if (this.stickyRecipe == null || this.bench == null) {
            return;
        }
        if (!(player instanceof EntityPlayerMP)) {
            return;
        }
        EntityPlayerMP serverPlayer = (EntityPlayerMP) player;
        if (justUsed == null || justUsed.getRegistryName() == null
                || !justUsed.getRegistryName().equals(this.stickyRecipe.getRegistryName())) {
            // 刚合成的不是锁定的那一份（比如用多态按钮换了配方）：约定作废，别再自动补
            clearStickyRecipe();
            return;
        }
        IRecipe fresh = CraftingManager.getRecipe(this.stickyRecipe.getRegistryName());
        if (fresh == null || !isPlainCraft(fresh)) {
            clearStickyRecipe();
            return;
        }
        this.stickyRecipe = fresh;
        this.bench.refreshStorages(true);
        StoragePool available = StoragePool.collect(scanHandlers());
        if (available.isEmpty() || !hasAllIngredients(fresh, available)) {
            clearStickyRecipe();
            return;
        }
        placeRecipeIntoGrid(serverPlayer, fresh, this.stickyStackAll);
        markDirty();
        updateResult();
    }

    /* 快捷移动 */

    @Override
    public ItemStack transferStackInSlot(EntityPlayer player, int index) {
        if (index == SLOT_RESULT) {
            return shiftCraftResult(player);
        }
        Slot slot = this.inventorySlots.get(index);
        if (slot == null || !slot.getHasStack()) {
            return ItemStack.EMPTY;
        }
        ItemStack slotStack = slot.getStack();
        ItemStack original = slotStack.copy();
        boolean movingFromGrid = index >= GRID_START && index < GRID_END;
        if (index >= INV_START) {
            if (!this.mergeItemStack(slotStack, GRID_START, GRID_END, false)) {
                return ItemStack.EMPTY;
            }
            // 玩家自己把身上的料甩进合成格：这一下属于手动摆放，连续合成约定作废
            clearStickyRecipe();
        } else if (!movingFromGrid || !this.mergeItemStack(slotStack, INV_START, INV_END, true)) {
            return ItemStack.EMPTY;
        }
        if (movingFromGrid) {
            int gridSlot = index - GRID_START;
            trimMatrixSources(gridSlot, slotStack.getCount());
        }
        this.updatingMatrix = true;
        try {
            if (slotStack.isEmpty()) {
                slot.putStack(ItemStack.EMPTY);
            } else {
                slot.onSlotChanged();
            }
        } finally {
            this.updatingMatrix = false;
        }
        if (slotStack.getCount() == original.getCount()) {
            return ItemStack.EMPTY;
        }
        slot.onTake(player, slotStack);
        return original;
    }

    /** shift 点产物 = 合成一次，产物进背包，放不下掉脚下。 */
    private ItemStack shiftCraftResult(EntityPlayer player) {
        if (this.world.isRemote) {
            return ItemStack.EMPTY;
        }
        ItemStack display = this.resultSlots.getStackInSlot(0);
        if (display.isEmpty()) {
            return ItemStack.EMPTY;
        }
        ItemStack moved = display.copy();
        if (!player.inventory.addItemStackToInventory(moved)) {
            player.dropItem(moved, false, false);
        }
        consumeGrid(player);
        return moved;
    }

    /* 界面按钮（客户端点击 → 服务器执行，1.12.2 的按钮通道是 enchantItem） */

    @Override
    public boolean enchantItem(EntityPlayer player, int id) {
        if (this.world.isRemote) {
            return false;
        }
        if (id == BUTTON_REFRESH && player instanceof EntityPlayerMP && canInteractWith(player)) {
            if (!acceptAction((EntityPlayerMP) player)) {
                return false;
            }
            refreshCraftables(true);
            return true;
        }
        if (id == BUTTON_AUTO_REFILL && canInteractWith(player)) {
            this.autoRefillEnabled = !this.autoRefillEnabled;
            if (player instanceof EntityPlayerMP) {
                EntityPlayerMP serverPlayer = (EntityPlayerMP) player;
                // 写进玩家存档：下次打开界面还是这个状态
                NBTTagCompound entityData = serverPlayer.getEntityData();
                NBTTagCompound persisted = entityData.getCompoundTag(EntityPlayer.PERSISTED_NBT_TAG);
                persisted.setBoolean(AUTO_REFILL_TAG, this.autoRefillEnabled);
                entityData.setTag(EntityPlayer.PERSISTED_NBT_TAG, persisted);
                // 立刻回包让界面上的按钮文字跟着变
                ModNetwork.sendToPlayer(serverPlayer, new S2CAutoRefillPacket(this.benchPos, this.autoRefillEnabled));
            }
            notify(player, this.autoRefillEnabled ? "已开启自动补齐" : "已关闭自动补齐",
                    this.autoRefillEnabled ? key("auto_refill_on") : key("auto_refill_off"));
            return true;
        }
        if (id == BUTTON_BATCH_GRID && player instanceof EntityPlayerMP && canInteractWith(player)) {
            if (!acceptAction((EntityPlayerMP) player)) {
                return false;
            }
            batchCraftFromGrid((EntityPlayerMP) player);
            return true;
        }
        return false;
    }

    /**
     * 客户端点了右侧列表里的某一项（走自定义包，见 C2SCraftRequestPacket）。
     *
     * @param craft true = Shift 点击：直接从存储合成一次，产物进背包
     *              false = 普通点击：把材料配到合成格（不需要的材料退回背包）
     * @param batch true = Ctrl 点击：反复合成，直到材料不够或背包塞不下
     */
    public void onRecipeListClick(EntityPlayerMP player, ResourceLocation recipeId, boolean craft, boolean batch) {
        if (!acceptAction(player)) {
            return;
        }
        this.activeFeedback = new CraftFeedback();
        if (batch || craft) {
            // 右侧直接合成与当前合成格无关；先把旧材料退回各自来源，
            // 避免同一批材料同时出现在合成格和存储快照里。
            returnMatrixMaterials(player);
            if (batch) {
                batchCraftFromStorage(player, recipeId);
            } else {
                craftFromStorage(player, recipeId);
            }
        } else {
            pullFromStorage(player, recipeId);
        }
    }

    /* 可合成列表 */

    /** 清空当前合成格：取自存储的材料优先退回原槽位，手动放入的材料退回玩家背包。 */
    private void returnMatrixMaterials(EntityPlayerMP player) {
        for (int slot = 0; slot < SmartWorkbenchTile.GRID_SIZE; slot++) {
            ItemStack inSlot = this.matrix.getStackInSlot(slot).copy();
            if (inSlot.isEmpty()) {
                this.matrixSources[slot].clear();
                continue;
            }
            int trackedCount = 0;
            for (SourcePart part : this.matrixSources[slot]) {
                int count = Math.min(part.stack.getCount(), Math.max(0, inSlot.getCount() - trackedCount));
                if (count <= 0) {
                    continue;
                }
                trackedCount += count;
                ItemStack rest = part.source.handler.insertItem(part.source.slot,
                        copyWithCount(part.stack, count), false);
                if (!rest.isEmpty() && !player.inventory.addItemStackToInventory(rest)) {
                    player.dropItem(rest, false, false);
                }
            }
            int manualCount = inSlot.getCount() - trackedCount;
            if (manualCount > 0) {
                ItemStack manual = copyWithCount(inSlot, manualCount);
                if (!player.inventory.addItemStackToInventory(manual)) {
                    player.dropItem(manual, false, false);
                }
            }
            this.matrixSources[slot].clear();
            setMatrixFromMenu(slot, ItemStack.EMPTY);
        }
        clearStickyRecipe();
        markDirty();
        updateResult();
        this.matrixSignature = matrixSignature();
    }

    private void refreshCraftables(boolean forceScan) {
        if (this.world.isRemote || this.bench == null) {
            return;
        }
        long now = this.world.getTotalWorldTime();
        if (!forceScan && now < this.nextRefreshTick) {
            return;
        }
        this.bench.refreshStorages(forceScan);
        this.nextRefreshTick = now + 20L;
        List<CraftableEntry> next = new ArrayList<CraftableEntry>();
        List<IItemHandler> storages = this.bench.getStorages();
        // 可合成列表按「接入存储 + 玩家背包 + 合成格自身」一起算：只放身上、不塞箱子的材料能列出来，
        // 一键取料搬进合成格后（箱子和背包可能已经掏空），格子里的料也还能撑起别的配方。
        StoragePool pool = StoragePool.collect(scanHandlers());
        // 推给客户端的快照仍然只抄接入存储：玩家背包在客户端本来就能实时读到。
        List<ItemStack> storageSnapshot = storages.isEmpty()
                ? Collections.<ItemStack>emptyList()
                : StoragePool.collect(storages).mergeForClient(STORAGE_SNAPSHOT_LIMIT);
        if (!pool.isEmpty()) {
            int max = ModConfig.maxListSize();
            List<IRecipe> recipes = getPlainRecipes();
            for (IRecipe recipe : recipes) {
                if (next.size() >= max) {
                    break;
                }
                if (!isPlainCraft(recipe)) {
                    continue;
                }
                NonNullList<Ingredient> ingredients = recipe.getIngredients();
                pool.reset();
                boolean possible = true;
                for (Ingredient ingredient : ingredients) {
                    if (!pool.mayContain(ingredient)) {
                        possible = false;
                        break;
                    }
                }
                // 右侧列表只显示一次合成所需材料全部充足的配方。
                boolean direct = possible && pool.plan(ingredients) != null;
                if (!direct) {
                    continue;
                }
                ItemStack result = recipe.getRecipeOutput();
                if (!result.isEmpty()) {
                    next.add(new CraftableEntry(recipe.getRegistryName(), result.copy()));
                }
            }
        }
        Collections.sort(next, new java.util.Comparator<CraftableEntry>() {
            @Override
            public int compare(CraftableEntry a, CraftableEntry b) {
                return a.result.getDisplayName().compareTo(b.result.getDisplayName());
            }
        });
        StringBuilder signature = new StringBuilder();
        for (CraftableEntry entry : next) {
            signature.append(entry.signature()).append(';');
        }
        // 库存数量变了也得重发，否则 JEI 手里的快照会过期，还会误报缺料
        signature.append('#').append(storages.size()).append(';');
        signature.append('@').append(this.bench == null ? 0 : this.bench.getOutputBindingCount()).append(';');
        for (ItemStack stack : storageSnapshot) {
            signature.append(registryName(stack.getItem())).append(':').append(stack.getCount())
                    .append(':').append(stack.getTagCompound()).append(',');
        }
        if (!signature.toString().equals(this.listSignature)) {
            this.listSignature = signature.toString();
            this.craftables = next;
            if (this.player instanceof EntityPlayerMP) {
                ModNetwork.sendToPlayer((EntityPlayerMP) this.player, new S2CCraftableListPacket(
                        this.benchPos, next, storageSnapshot, storages.size(), getOutputCount()));
            }
        }
    }

    /** 按服务器刚读取的存储内容预检一份配方，避免客户端快照过期时仍开始搬料。 */
    private static boolean hasAllIngredients(IRecipe recipe, StoragePool pool) {
        if (pool.isEmpty()) {
            return false;
        }
        return pool.plan(recipe.getIngredients()) != null;
    }

    private static boolean isUnused(Ingredient ingredient) {
        return ingredient == null || ingredient == Ingredient.EMPTY || ingredient.getMatchingStacks().length == 0;
    }

    /** 只处理能放进 3x3 的普通合成配方，特殊配方（修装备、复制旗帜等）跳过。 */
    private boolean isPlainCraft(IRecipe recipe) {
        if (recipe.isDynamic() || !recipe.canFit(COLUMNS, ROWS)) {
            return false;
        }
        NonNullList<Ingredient> ingredients = recipe.getIngredients();
        if (ingredients.isEmpty()) {
            return false;
        }
        int width = recipeWidth(recipe);
        int height = recipeHeight(recipe, width, ingredients.size());
        return width <= COLUMNS && height <= ROWS;
    }

    /**
     * 给 JEI 桥用：这份配方能不能一键从存储取料。
     * 判断规则和服务器端 {@link #placeRecipeFromClient} 完全一致。
     */
    public boolean canAutoPlace(IRecipe recipe) {
        return recipe != null && isPlainCraft(recipe);
    }

    private static int recipeWidth(IRecipe recipe) {
        if (recipe instanceof IShapedRecipe) {
            return ((IShapedRecipe) recipe).getRecipeWidth();
        }
        if (recipe instanceof ShapedRecipes) {
            return ((ShapedRecipes) recipe).recipeWidth;
        }
        return COLUMNS;
    }

    private static int recipeHeight(IRecipe recipe, int width, int ingredientCount) {
        if (recipe instanceof IShapedRecipe) {
            return ((IShapedRecipe) recipe).getRecipeHeight();
        }
        if (recipe instanceof ShapedRecipes) {
            return ((ShapedRecipes) recipe).recipeHeight;
        }
        return width <= 0 ? ingredientCount : (ingredientCount + width - 1) / width;
    }

    /** 这个配方该摆在 3x3 的哪些格子上，返回每个材料对应的格子序号。 */
    private static int[] gridLayout(IRecipe recipe, NonNullList<Ingredient> ingredients) {
        int[] positions = new int[ingredients.size()];
        int width = recipeWidth(recipe);
        int height = recipeHeight(recipe, width, ingredients.size());
        boolean shaped = recipe instanceof IShapedRecipe || recipe instanceof ShapedRecipes;
        if (!shaped || width <= 0 || height <= 0 || width > COLUMNS || height > ROWS) {
            // 无序配方：从第一个格子往后依次摆
            for (int i = 0; i < positions.length; i++) {
                positions[i] = Math.min(i, SmartWorkbenchTile.GRID_SIZE - 1);
            }
            return positions;
        }
        int offsetX = (COLUMNS - width) / 2;
        int offsetY = (ROWS - height) / 2;
        for (int i = 0; i < positions.length; i++) {
            positions[i] = (offsetY + i / width) * COLUMNS + offsetX + i % width;
        }
        return positions;
    }

    private IRecipe resolveRecipe(ResourceLocation id) {
        if (id == null) {
            return null;
        }
        // 配方列表可能在网络包到达前刷新，因此按服务端真实 ID 重新解析，而不是信任客户端下标。
        IRecipe recipe = CraftingManager.getRecipe(id);
        return recipe != null && isPlainCraft(recipe) ? recipe : null;
    }

    /* 从附近存储取料 */

    /** 点一下列表：把材料从附近存储配到合成格；格子里不符合配方的料退回背包。 */
    private void pullFromStorage(EntityPlayerMP player, ResourceLocation recipeId) {
        IRecipe recipe = resolveRecipe(recipeId);
        if (recipe == null) {
            notify(player, "配方已失效，列表已刷新", key("recipe_gone"));
            refreshCraftables(false);
            return;
        }
        if (this.bench == null) {
            return;
        }
        placeRecipeIntoGrid(player, recipe, false);
        markDirty();
        updateResult();
        this.matrixSignature = matrixSignature();
        refreshCraftables(false);
        ForgeHooks.setCraftingPlayer(player);
        boolean matches;
        try {
            matches = recipe.matches(this.matrix, this.world);
        } finally {
            ForgeHooks.setCraftingPlayer(null);
        }
        if (!matches) {
            notify(player, "附近存储和背包材料不足，缺少的中间材料可由其他配方合成", key("not_enough"));
        } else {
            // 普通点击列表 = 明确选了这份配方，和 JEI 加号一样享受「合成后自动补料」
            rememberSelection(player, recipe);
            beginStickyRecipe(player, recipe, false);
        }
    }

    /** shift 点列表：直接从存储取材合成一次，产物进背包，不占用合成格。 */
    private void craftFromStorage(EntityPlayerMP player, ResourceLocation recipeId) {
        IRecipe recipe = resolveRecipe(recipeId);
        if (recipe == null) {
            notify(player, "配方已失效，列表已刷新", key("recipe_gone"));
            refreshCraftables(false);
            return;
        }
        if (this.bench == null) {
            return;
        }
        this.bench.refreshStorages(true);
        StoragePool pool = StoragePool.collect(inputHandlers());
        int outcome = craftOnce(player, recipe, pool, false);
        if (outcome == CRAFT_OK) {
            notifyFeedback(player, 1);
        } else if (outcome == CRAFT_MISSING) {
            notify(player, "附近存储和背包材料不足，缺少的中间材料可由其他配方合成", key("not_enough"));
        } else if (outcome == CRAFT_UNSUPPORTED) {
            notify(player, "这个配方不支持一键合成，请点图标取料到合成格", key("unsupported"));
        } else if (outcome == CRAFT_INVENTORY_FULL) {
            notify(player, "产物已掉在脚下，因为背包没有空位", key("inventory_full"));
        }
        markDirty();
        updateResult();
        refreshCraftables(false);
    }

    /**
     * Ctrl 点列表：反复取材合成，直到材料不够、背包塞不下或到单次上限。
     * 每轮都重新读一遍库存，因为上一轮真的从箱子里把材料抽走了。
     */
    private void batchCraftFromStorage(EntityPlayerMP player, ResourceLocation recipeId) {
        IRecipe recipe = resolveRecipe(recipeId);
        if (recipe == null) {
            notify(player, "配方已失效，列表已刷新", key("recipe_gone"));
            refreshCraftables(false);
            return;
        }
        if (this.bench == null) {
            return;
        }
        this.bench.refreshStorages(true);
        int crafted = 0;
        int outcome = CRAFT_OK;
        for (int guard = 0; guard < MAX_BATCH_CRAFTS; guard++) {
            StoragePool pool = StoragePool.collect(inputHandlers());
            if (pool.isEmpty()) {
                outcome = CRAFT_MISSING;
                if (crafted == 0) {
                    notify(player, "附近存储和背包材料不足", key("not_enough"));
                }
                break;
            }
            // 只有第一次失败时才提示，中途没料了是正常结束
            outcome = craftOnce(player, recipe, pool, crafted == 0);
            if (outcome != CRAFT_OK) {
                break;
            }
            crafted++;
        }
        if (crafted > 0) {
            notifyFeedback(player, crafted);
        }
        markDirty();
        updateResult();
        refreshCraftables(true);
    }

    /**
     * 产物格上的 Ctrl+左键：把合成格里<b>已经摆好的这份配方</b>批量合成。
     * <p>
     * 材料从哪来的不重要——JEI 加号摆进来的、右侧列表点进来的、自己一格一格
     * 从背包里拖进来的，只要格子里能出产物，就按这份配方一直做到材料用完。
     * 格子里现成的料会先退回（来源记录的回原容器，手动放的回背包），再和
     * 接入存储、背包一起按「整份库存」反复取材，产物进背包。
     */
    private void batchCraftFromGrid(EntityPlayerMP player) {
        if (this.bench == null) {
            return;
        }
        updateResult();
        IRecipe recipe = this.lastGridRecipe;
        if (recipe == null || recipe.getRegistryName() == null || this.resultSlots.getStackInSlot(0).isEmpty()) {
            notify(player, "合成格里还没有能合成的配方", key("grid_empty"));
            return;
        }
        if (!isPlainCraft(recipe)) {
            notify(player, "这个配方不支持一键合成，请点图标取料到合成格", key("unsupported"));
            return;
        }
        this.activeFeedback = new CraftFeedback();
        // 格子里的料也算库存：先整份退回（来源记录的回原容器，手动放的回背包），
        // 之后每一轮都按「接入存储 + 背包」取材，刚退回的那份一定会被用上，不会被丢掉。
        returnMatrixMaterials(player);
        this.bench.refreshStorages(true);
        int crafted = 0;
        for (int guard = 0; guard < MAX_BATCH_CRAFTS; guard++) {
            StoragePool pool = StoragePool.collect(inputHandlers());
            if (pool.isEmpty()) {
                if (crafted == 0) {
                    notify(player, "附近存储和背包材料不足", key("not_enough"));
                }
                break;
            }
            int outcome = craftOnce(player, recipe, pool, crafted == 0);
            if (outcome != CRAFT_OK) {
                break;
            }
            crafted++;
        }
        if (crafted > 0) {
            notifyFeedback(player, crafted);
        }
        markDirty();
        updateResult();
        this.matrixSignature = matrixSignature();
        refreshCraftables(true);
    }

    /**
     * 自动合成：一次点击把目标物品做出来，直到达到配置的上限（默认 64 个）或材料用完。
     * <p>
     * 和 Ctrl 批量合成的区别：
     * <ul>
     *   <li>直接材料不够时，会自动递归补做缺的中间物品（不需要再弹确认框）；</li>
     *   <li>产物优先送进扳手绑定的输出容器，没绑才退回玩家背包。</li>
     * </ul>
     */
    public void autoCraftFromStorage(EntityPlayerMP player, ResourceLocation recipeId) {
        if (!acceptAction(player)) {
            return;
        }
        if (this.world.isRemote || this.bench == null) {
            return;
        }
        IRecipe recipe = resolveRecipe(recipeId);
        if (recipe == null) {
            notify(player, "配方已失效，列表已刷新", key("recipe_gone"));
            refreshCraftables(false);
            return;
        }
        this.bench.refreshStorages(true);

        int limit = ModConfig.autoCraftLimit();
        int crafted = 0;
        int outcome = CRAFT_OK;
        List<ItemStack> missing = Collections.emptyList();
        this.activeFeedback = new CraftFeedback();
        // 自动合成完全独立于合成格，先归还当前格子，防止把同一份材料重复算入库存。
        returnMatrixMaterials(player);

        for (int i = 0; i < limit; i++) {
            StoragePool pool = StoragePool.collect(inputHandlers());
            if (pool.isEmpty()) {
                outcome = CRAFT_MISSING;
                break;
            }
            // 先试直接合成；这一步不成功时 pool 没被消耗（失败会退款）
            outcome = craftOnce(player, recipe, pool, false, true);
            if (outcome == CRAFT_OK) {
                crafted++;
                continue;
            }
            if (outcome != CRAFT_MISSING) {
                break;
            }
            // 直接材料不够：重新读一遍库存，规划整棵依赖树，能补齐就补一次
            StoragePool planPool = StoragePool.collect(inputHandlers());
            RecursivePlanState state = new RecursivePlanState(planPool.snapshotStacks());
            if (!planRecipe(recipe, state, 0, new HashSet<ResourceLocation>()) || state.steps.isEmpty()
                    || !state.unavailable.isEmpty() || state.autoCraftable.isEmpty()) {
                missing = RecursivePlanState.copyStacks(state.unavailable);
                outcome = CRAFT_MISSING;
                break;
            }
            int recursive = executeRecursivePlan(player, state.steps, planPool, true);
            if (recursive == CRAFT_OK) {
                crafted++;
                continue;
            }
            outcome = recursive;
            missing = RecursivePlanState.copyStacks(state.unavailable);
            break;
        }

        if (crafted > 0) {
            notifyFeedback(player, crafted);
        } else if (outcome == CRAFT_OUTPUT_FULL) {
            notify(player, "输出容器满了，产物已退回背包", key("output_full"));
        } else if (outcome == CRAFT_UNSUPPORTED) {
            notify(player, "这个配方不支持一键合成，请点图标取料到合成格", key("unsupported"));
        } else if (!missing.isEmpty()) {
            notifyMissing(player, missing, key("missing_base"));
        } else {
            notify(player, "附近存储和背包材料不足", key("not_enough"));
        }

        markDirty();
        updateResult();
        refreshCraftables(false);
    }

    /** 第一次发现直接材料不够时，先在内存里规划整棵配方依赖树。 */
    private void requestRecursiveCraft(EntityPlayerMP player, IRecipe target) {
        if (this.bench == null) {
            return;
        }
        this.bench.refreshStorages(true);
        StoragePool pool = StoragePool.collect(inputHandlers());
        RecursivePlanState state = new RecursivePlanState(pool.snapshotStacks());
        if (!planRecipe(target, state, 0, new HashSet<ResourceLocation>()) || state.steps.isEmpty()) {
            if (!state.unavailable.isEmpty()) {
                notifyMissing(player, state.unavailable, key("missing_base"));
            } else {
                notify(player, "附近存储和背包材料不足", key("not_enough"));
            }
            return;
        }
        if (!state.autoCraftable.isEmpty() && state.unavailable.isEmpty()) {
            ModNetwork.sendToPlayer(player, new S2CRecursiveCraftPromptPacket(
                    this.benchPos, target.getRegistryName(),
                    toPromptEntries(state.autoCraftable, true),
                    Collections.<S2CRecursiveCraftPromptPacket.MissingEntry>emptyList()));
            return;
        }
        if (!state.unavailable.isEmpty()) {
            notifyMissing(player, state.unavailable, key("missing_base"));
        } else {
            notify(player, "附近存储和背包材料不足", key("not_enough"));
        }
    }

    /** 客户端确认后重新规划并真实执行，避免确认期间箱子内容变化造成错误取料。 */
    public void confirmRecursiveCraft(EntityPlayerMP player, ResourceLocation recipeId, boolean accepted) {
        if (!acceptAction(player)) {
            return;
        }
        if (this.world.isRemote) {
            return;
        }
        this.clearRecursivePrompt();
        if (!accepted) {
            notify(player, "已取消自动补齐", key("recursive_cancel"));
            return;
        }
        IRecipe target = resolveRecipe(recipeId);
        if (target == null || this.bench == null) {
            notify(player, "配方已失效，请重新刷新", key("recipe_gone"));
            refreshCraftables(false);
            return;
        }
        this.bench.refreshStorages(true);
        StoragePool pool = StoragePool.collect(inputHandlers());
        RecursivePlanState state = new RecursivePlanState(pool.snapshotStacks());
        if (!planRecipe(target, state, 0, new HashSet<ResourceLocation>()) || !state.unavailable.isEmpty()
                || state.autoCraftable.isEmpty()) {
            if (!state.unavailable.isEmpty()) {
                notifyMissing(player, state.unavailable, key("missing_base"));
            } else {
                notify(player, "材料已经变化，请重新点击合成", key("recursive_changed"));
            }
            return;
        }
        this.activeFeedback = new CraftFeedback();
        int recursiveOutcome = executeRecursivePlan(player, state.steps, pool, false);
        if (recursiveOutcome == CRAFT_OK || recursiveOutcome == CRAFT_OUTPUT_FULL) {
            notifyFeedback(player, 1);
        }
        markDirty();
        updateResult();
        refreshCraftables(false);
    }

    /* 递归规划 */

    /** 递归规划一份配方；steps 按依赖先后排列，最后一项是目标物品。 */
    private boolean planRecipe(IRecipe recipe, RecursivePlanState state, int depth, Set<ResourceLocation> active) {
        if (depth > ModConfig.recursiveDepth() || state.steps.size() >= MAX_RECURSIVE_STEPS
                || recipe == null || !isPlainCraft(recipe)) {
            return false;
        }
        ResourceLocation id = recipe.getRegistryName();
        if (id != null && !active.add(id)) {
            return false;
        }
        RecursivePlanState.Mark mark = state.mark();
        boolean complete = true;
        for (Ingredient ingredient : recipe.getIngredients()) {
            if (!planIngredient(ingredient, state, depth + 1, active)) {
                complete = false;
                break;
            }
        }
        if (complete) {
            ItemStack result = recipe.getRecipeOutput();
            if (!result.isEmpty()) {
                state.virtual.add(result.copy());
                state.steps.add(recipe);
            } else {
                complete = false;
            }
        }
        if (id != null) {
            active.remove(id);
        }
        if (!complete) {
            List<ItemStack> failure = RecursivePlanState.copyStacks(state.unavailable);
            state.restore(mark);
            state.unavailable.addAll(failure);
        }
        return complete;
    }

    /** 先消耗已有库存；没有时找能产出该材料的普通合成配方。 */
    private boolean planIngredient(Ingredient ingredient, RecursivePlanState state,
                                   int depth, Set<ResourceLocation> active) {
        if (isUnused(ingredient)) {
            return true;
        }
        int available = findAndConsumeVirtual(state.virtual, ingredient);
        if (available > 0) {
            return true;
        }
        ItemStack[] candidates = ingredient.getMatchingStacks();
        List<ItemStack> deepestFailure = new ArrayList<ItemStack>();
        for (ItemStack candidate : candidates) {
            if (candidate.isEmpty()) {
                continue;
            }
            for (IRecipe dependency : findRecipesFor(candidate, active)) {
                RecursivePlanState.Mark mark = state.mark();
                if (planRecipe(dependency, state, depth, active)
                        && findAndConsumeVirtual(state.virtual, ingredient) > 0) {
                    addShortage(state.autoCraftable, candidate, 1);
                    return true;
                }
                // 这一条配方失败时，先保存它递归发现的底层缺口；只有所有候选都失败后才合并。
                for (ItemStack missingItem : shortageDelta(state.unavailable, mark.unavailable)) {
                    addShortage(deepestFailure, missingItem, missingItem.getCount());
                }
                state.restore(mark);
            }
        }
        if (!deepestFailure.isEmpty()) {
            for (ItemStack missingItem : deepestFailure) {
                addShortage(state.unavailable, missingItem, missingItem.getCount());
            }
        } else {
            ItemStack report = candidates.length == 0 ? ItemStack.EMPTY : candidates[0].copy();
            if (!report.isEmpty()) {
                addShortage(state.unavailable, report, 1);
            }
        }
        return false;
    }

    private List<IRecipe> findRecipesFor(ItemStack wanted, Set<ResourceLocation> active) {
        List<IRecipe> matches = new ArrayList<IRecipe>();
        List<IRecipe> candidates = getRecipesByResult().get(wanted.getItem());
        if (candidates == null) {
            return matches;
        }
        for (IRecipe recipe : candidates) {
            if (recipe.getRegistryName() == null || active.contains(recipe.getRegistryName())) {
                continue;
            }
            ItemStack result = recipe.getRecipeOutput();
            if (StoragePool.areSame(result, wanted)) {
                matches.add(recipe);
            }
        }
        return matches;
    }

    private List<IRecipe> getPlainRecipes() {
        long version = CraftingManager.REGISTRY.getKeys().size();
        if (this.plainRecipes == null || version != this.recipeIndexVersion) {
            List<IRecipe> recipes = new ArrayList<IRecipe>();
            Map<Item, List<IRecipe>> byResult = new HashMap<Item, List<IRecipe>>();
            for (IRecipe recipe : CraftingManager.REGISTRY) {
                if (!isPlainCraft(recipe)) {
                    continue;
                }
                recipes.add(recipe);
                ItemStack result = recipe.getRecipeOutput();
                if (!result.isEmpty()) {
                    List<IRecipe> list = byResult.get(result.getItem());
                    if (list == null) {
                        list = new ArrayList<IRecipe>();
                        byResult.put(result.getItem(), list);
                    }
                    list.add(recipe);
                }
            }
            this.plainRecipes = recipes;
            this.recipesByResult = byResult;
            this.recipeIndexVersion = version;
        }
        return this.plainRecipes;
    }

    private Map<Item, List<IRecipe>> getRecipesByResult() {
        getPlainRecipes();
        return this.recipesByResult;
    }

    /** 返回并消耗一件匹配材料；列表中的堆叠只属于规划阶段。 */
    private static int findAndConsumeVirtual(List<ItemStack> stacks, Ingredient ingredient) {
        for (int i = 0; i < stacks.size(); i++) {
            ItemStack stack = stacks.get(i);
            if (ingredient.apply(stack)) {
                stack.shrink(1);
                if (stack.isEmpty()) {
                    stacks.remove(i);
                }
                return 1;
            }
        }
        return 0;
    }

    private static void addShortage(List<ItemStack> list, ItemStack stack, int count) {
        for (ItemStack existing : list) {
            if (StoragePool.areSame(existing, stack)) {
                existing.grow(count);
                return;
            }
        }
        ItemStack copy = stack.copy();
        copy.setCount(count);
        list.add(copy);
    }

    /** 计算候选分支相对进入前状态新增的缺口，避免把外层缺口重复统计。 */
    private static List<ItemStack> shortageDelta(List<ItemStack> current, List<ItemStack> baseline) {
        List<ItemStack> delta = RecursivePlanState.copyStacks(current);
        for (ItemStack previous : baseline) {
            for (ItemStack candidate : delta) {
                if (!StoragePool.areSame(previous, candidate)) {
                    continue;
                }
                candidate.shrink(Math.min(candidate.getCount(), previous.getCount()));
                break;
            }
        }
        for (int i = delta.size() - 1; i >= 0; i--) {
            if (delta.get(i).isEmpty()) {
                delta.remove(i);
            }
        }
        return delta;
    }

    private static List<S2CRecursiveCraftPromptPacket.MissingEntry> toPromptEntries(List<ItemStack> stacks,
                                                                                     boolean craftable) {
        List<S2CRecursiveCraftPromptPacket.MissingEntry> entries =
                new ArrayList<S2CRecursiveCraftPromptPacket.MissingEntry>();
        for (ItemStack stack : stacks) {
            entries.add(new S2CRecursiveCraftPromptPacket.MissingEntry(stack.copy(), stack.getCount(), craftable));
        }
        return entries;
    }

    private void notifyMissing(EntityPlayer player, List<ItemStack> stacks, String translationKey) {
        StringBuilder names = new StringBuilder();
        for (ItemStack stack : stacks) {
            if (names.length() > 0) {
                names.append("、");
            }
            names.append(stack.getDisplayName()).append("x").append(stack.getCount());
        }
        player.sendStatusMessage(new TextComponentTranslation(translationKey, names.toString()), true);
    }

    /** 按规划顺序合成中间物品，最后一步的产物交付出去（toOutput 时优先送输出容器）。 */
    private int executeRecursivePlan(EntityPlayerMP player, List<IRecipe> steps, StoragePool pool, boolean toOutput) {
        List<ItemStack> generated = new ArrayList<ItemStack>();
        List<ItemStack> pulled = new ArrayList<ItemStack>();
        List<Integer> pulledFrom = new ArrayList<Integer>();
        int delivery = CRAFT_OK;
        for (int index = 0; index < steps.size(); index++) {
            IRecipe recipe = steps.get(index);
            InventoryCrafting grid = new InventoryCrafting(this, COLUMNS, ROWS);
            boolean success = true;
            int[] layout = gridLayout(recipe, recipe.getIngredients());
            ForgeHooks.setCraftingPlayer(player);
            try {
                NonNullList<Ingredient> ingredients = recipe.getIngredients();
                for (int i = 0; i < ingredients.size(); i++) {
                    Ingredient ingredient = ingredients.get(i);
                    if (isUnused(ingredient)) {
                        continue;
                    }
                    ItemStack got = takeFromGenerated(generated, ingredient);
                    if (got.isEmpty()) {
                        int candidate = pool.find(ingredient);
                        got = candidate < 0 ? ItemStack.EMPTY : pool.take(candidate, 1);
                        if (!got.isEmpty()) {
                            pulled.add(got.copy());
                            pulledFrom.add(candidate);
                            if (this.activeFeedback != null) {
                                this.activeFeedback.addConsumed(got);
                            }
                        }
                    }
                    if (got.isEmpty()) {
                        success = false;
                        break;
                    }
                    grid.setInventorySlotContents(layout[i], got);
                }
                if (success && recipe.matches(grid, this.world)) {
                    ItemStack result = recipe.getCraftingResult(grid);
                    NonNullList<ItemStack> remaining = recipe.getRemainingItems(grid);
                    if (result.isEmpty()) {
                        success = false;
                    } else {
                        result.onCrafting(this.world, player, result.getCount());
                        FMLCommonHandler.instance().firePlayerCraftingEvent(player, result, grid);
                        player.unlockRecipes(Collections.singletonList(recipe));
                        if (index == steps.size() - 1) {
                            delivery = deliverResult(player, result, toOutput);
                            if (this.activeFeedback != null) {
                                this.activeFeedback.addResult(result);
                            }
                        } else {
                            generated.add(result);
                        }
                        for (ItemStack leftover : remaining) {
                            if (!leftover.isEmpty()) {
                                generated.add(leftover.copy());
                            }
                        }
                    }
                } else {
                    success = false;
                }
            } finally {
                ForgeHooks.setCraftingPlayer(null);
            }
            if (!success) {
                // 本次操作是事务性的：返还所有已从存储抽出的原料，丢弃尚未交付的中间产物，避免重复返还。
                refund(pool, pulled, pulledFrom, player);
                generated.clear();
                notify(player, "材料已经变化，自动合成已停止", key("recursive_changed"));
                return CRAFT_MISSING;
            }
        }
        for (ItemStack stack : generated) {
            if (!stack.isEmpty() && !player.inventory.addItemStackToInventory(stack)) {
                player.dropItem(stack, false, false);
            }
        }
        return delivery;
    }

    private static ItemStack takeFromGenerated(List<ItemStack> generated, Ingredient ingredient) {
        for (int i = 0; i < generated.size(); i++) {
            ItemStack stack = generated.get(i);
            if (!ingredient.apply(stack)) {
                continue;
            }
            ItemStack result = copyWithCount(stack, 1);
            stack.shrink(1);
            if (stack.isEmpty()) {
                generated.remove(i);
            }
            return result;
        }
        return ItemStack.EMPTY;
    }

    private static final class RecursivePlanState {
        private final List<ItemStack> virtual;
        private final List<IRecipe> steps = new ArrayList<IRecipe>();
        private final List<ItemStack> autoCraftable = new ArrayList<ItemStack>();
        private final List<ItemStack> unavailable = new ArrayList<ItemStack>();

        RecursivePlanState(List<ItemStack> initial) {
            this.virtual = new ArrayList<ItemStack>();
            for (ItemStack stack : initial) {
                this.virtual.add(stack.copy());
            }
        }

        Mark mark() {
            return new Mark(copyStacks(this.virtual), this.steps.size(), copyStacks(this.autoCraftable),
                    copyStacks(this.unavailable));
        }

        void restore(Mark mark) {
            this.virtual.clear();
            this.virtual.addAll(copyStacks(mark.virtual));
            while (this.steps.size() > mark.stepsSize) {
                this.steps.remove(this.steps.size() - 1);
            }
            this.autoCraftable.clear();
            this.autoCraftable.addAll(copyStacks(mark.autoCraftable));
            this.unavailable.clear();
            this.unavailable.addAll(copyStacks(mark.unavailable));
        }

        static List<ItemStack> copyStacks(List<ItemStack> source) {
            List<ItemStack> copy = new ArrayList<ItemStack>(source.size());
            for (ItemStack stack : source) {
                copy.add(stack.copy());
            }
            return copy;
        }

        static final class Mark {
            final List<ItemStack> virtual;
            final int stepsSize;
            final List<ItemStack> autoCraftable;
            final List<ItemStack> unavailable;

            Mark(List<ItemStack> virtual, int stepsSize, List<ItemStack> autoCraftable, List<ItemStack> unavailable) {
                this.virtual = virtual;
                this.stepsSize = stepsSize;
                this.autoCraftable = autoCraftable;
                this.unavailable = unavailable;
            }
        }
    }

    /* 真正取料合成 */

    private int craftOnce(EntityPlayerMP player, IRecipe recipe, StoragePool pool, boolean notifyOnFail) {
        return craftOnce(player, recipe, pool, notifyOnFail, false);
    }

    /**
     * 从存储里真取一份材料合成一次，产物进背包（塞不下就掉在脚下）。
     * 返回 {@link #CRAFT_OK} 等结果码，批量合成靠它判断该不该接着做。
     */
    private int craftOnce(EntityPlayerMP player, IRecipe recipe, StoragePool pool, boolean notifyOnFail,
                          boolean toOutput) {
        NonNullList<Ingredient> ingredients = recipe.getIngredients();
        int[] plan = pool.isEmpty() ? null : pool.plan(ingredients);
        if (plan == null) {
            if (notifyOnFail) {
                notify(player, "附近存储和背包材料不足", key("not_enough"));
            }
            return CRAFT_MISSING;
        }
        // 临时合成格只用来摆样子给配方判断，不占用工作台的格子
        InventoryCrafting grid = new InventoryCrafting(this, COLUMNS, ROWS);
        int[] layout = gridLayout(recipe, ingredients);
        List<ItemStack> pulled = new ArrayList<ItemStack>();
        List<Integer> pulledFrom = new ArrayList<Integer>();
        int[] pulledFromByGridSlot = new int[SmartWorkbenchTile.GRID_SIZE];
        Arrays.fill(pulledFromByGridSlot, -1);
        ForgeHooks.setCraftingPlayer(player);
        try {
            for (int i = 0; i < ingredients.size(); i++) {
                if (plan[i] < 0) {
                    continue;
                }
                ItemStack got = pool.takePlanned(plan[i], 1);
                if (got.isEmpty()) {
                    refund(pool, pulled, pulledFrom, player);
                    if (notifyOnFail) {
                        notify(player, "附近存储和背包材料不足", key("not_enough"));
                    }
                    return CRAFT_MISSING;
                }
                pulled.add(got);
                pulledFrom.add(plan[i]);
                grid.setInventorySlotContents(layout[i], got);
                pulledFromByGridSlot[layout[i]] = plan[i];
            }
            if (!recipe.matches(grid, this.world)) {
                refund(pool, pulled, pulledFrom, player);
                if (notifyOnFail) {
                    notify(player, "这个配方不支持一键合成，请点图标取料到合成格", key("unsupported"));
                }
                return CRAFT_UNSUPPORTED;
            }
            ItemStack result = recipe.getCraftingResult(grid);
            if (result.isEmpty()) {
                refund(pool, pulled, pulledFrom, player);
                if (notifyOnFail) {
                    notify(player, "这个配方不支持一键合成，请点图标取料到合成格", key("unsupported"));
                }
                return CRAFT_UNSUPPORTED;
            }
            NonNullList<ItemStack> remaining = recipe.getRemainingItems(grid);
            result.onCrafting(this.world, player, result.getCount());
            FMLCommonHandler.instance().firePlayerCraftingEvent(player, result, grid);
            player.unlockRecipes(Collections.singletonList(recipe));
            int delivery = deliverResult(player, result, toOutput);
            if (this.activeFeedback != null) {
                this.activeFeedback.addResult(result);
                for (int i = 0; i < ingredients.size(); i++) {
                    Ingredient ingredient = ingredients.get(i);
                    ItemStack consumed = grid.getStackInSlot(layout[i]);
                    if (!isUnused(ingredient) && !consumed.isEmpty()) {
                        this.activeFeedback.addConsumed(consumed);
                    }
                }
            }
            for (int i = 0; i < remaining.size(); i++) {
                ItemStack leftover = remaining.get(i);
                if (leftover.isEmpty()) {
                    continue;
                }
                int candidate = i < pulledFromByGridSlot.length ? pulledFromByGridSlot[i] : -1;
                ItemStack rest = candidate >= 0 ? pool.giveBack(candidate, leftover) : leftover;
                if (!rest.isEmpty() && !player.inventory.addItemStackToInventory(rest)) {
                    player.dropItem(rest, false, false);
                }
            }
            return delivery;
        } finally {
            ForgeHooks.setCraftingPlayer(null);
        }
    }

    /**
     * 交付一次合成的产物：toOutput 时先塞进绑定的输出容器，塞不下或没绑才退回玩家背包，最后才掉在地上。
     */
    private int deliverResult(EntityPlayer player, ItemStack stack, boolean toOutput) {
        if (stack.isEmpty()) {
            return CRAFT_OK;
        }
        if (toOutput) {
            ItemStack rest = insertIntoOutputs(stack);
            if (rest.isEmpty()) {
                return CRAFT_OK;
            }
            // 输出容器满了：剩下的退回背包，保证不丢，但这次自动合成要停下
            if (!player.inventory.addItemStackToInventory(rest)) {
                player.dropItem(rest, false, false);
            }
            return CRAFT_OUTPUT_FULL;
        }
        if (player.inventory.addItemStackToInventory(stack)) {
            return CRAFT_OK;
        }
        player.dropItem(stack, false, false);
        return CRAFT_INVENTORY_FULL;
    }

    /** 把产物塞进所有绑定的输出容器，返回塞不下的部分（没绑输出容器时原样返回）。 */
    private ItemStack insertIntoOutputs(ItemStack stack) {
        ItemStack rest = stack;
        if (this.bench == null) {
            return rest;
        }
        for (IItemHandler handler : this.bench.getOutputStorages()) {
            rest = ItemHandlerHelper.insertItemStacked(handler, rest, false);
            if (rest.isEmpty()) {
                break;
            }
        }
        return rest;
    }

    private void refund(StoragePool pool, List<ItemStack> pulled, List<Integer> pulledFrom, EntityPlayer player) {
        for (int i = 0; i < pulled.size(); i++) {
            ItemStack stack = pulled.get(i);
            if (stack.isEmpty()) {
                continue;
            }
            ItemStack rest = pool.giveBack(pulledFrom.get(i), stack);
            if (!rest.isEmpty() && !player.inventory.addItemStackToInventory(rest)) {
                player.dropItem(rest, false, false);
            }
        }
    }

    /* 周期同步 */

    @Override
    public void detectAndSendChanges() {
        if (!this.world.isRemote) {
            // 界面刚打开时补一包开关状态：客户端菜单是新建的，默认「开」，
            // 而玩家存档里可能是「关」，必须明确告诉他一次。
            if (!this.autoRefillSynced) {
                this.autoRefillSynced = true;
                if (this.player instanceof EntityPlayerMP) {
                    ModNetwork.sendToPlayer((EntityPlayerMP) this.player,
                            new S2CAutoRefillPacket(this.benchPos, this.autoRefillEnabled));
                }
            }
            String signature = matrixSignature();
            if (!signature.equals(this.matrixSignature)) {
                this.matrixSignature = signature;
                if (this.bench != null) {
                    this.bench.markDirty();
                }
                updateResult();
                // 可合成列表现在也算合成格里的料：格子里东西一变，列表就得跟上
                this.nextRefreshTick = 0L;
            }
            if (this.world.getTotalWorldTime() >= this.nextRefreshTick) {
                refreshCraftables(false);
            }
        }
        super.detectAndSendChanges();
    }

    @Override
    public void onContainerClosed(EntityPlayer player) {
        super.onContainerClosed(player);
        if (!this.world.isRemote) {
            // 产物槽里的东西只是显示用，材料留在工作台格子里
            this.resultSlots.setInventorySlotContents(0, ItemStack.EMPTY);
        }
    }

    /* 小工具 */

    /** 限制客户端请求频率；服务器每个菜单每 4 tick 最多处理一次重操作。 */
    private boolean acceptAction(EntityPlayerMP player) {
        if (this.world.isRemote || this.bench == null || !canInteractWith(player)) {
            return false;
        }
        long now = this.world.getTotalWorldTime();
        if (now < this.nextActionTick) {
            return false;
        }
        this.nextActionTick = now + 4L;
        return true;
    }

    private void markDirty() {
        if (this.bench != null) {
            this.bench.markDirty();
        }
    }

    private static String key(String name) {
        return "gui." + SmartWorkbenchMod.MOD_ID + ".msg." + name;
    }

    private void notify(EntityPlayer player, String fallback, String translationKey) {
        player.sendStatusMessage(new TextComponentTranslation(translationKey), true);
    }

    private void notifyFeedback(EntityPlayer player, int crafts) {
        if (this.activeFeedback == null || this.activeFeedback.isEmpty()) {
            return;
        }
        String consumed = this.activeFeedback.describeConsumed();
        String produced = this.activeFeedback.describeProduced();
        player.sendStatusMessage(new TextComponentTranslation(
                key("craft_feedback"), crafts, consumed, produced), true);
        this.activeFeedback = null;
    }

    private static final class CraftFeedback {
        private final List<ItemStack> consumed = new ArrayList<ItemStack>();
        private final List<ItemStack> produced = new ArrayList<ItemStack>();

        void addConsumed(ItemStack stack) {
            merge(this.consumed, stack);
        }

        void addResult(ItemStack stack) {
            merge(this.produced, stack);
        }

        boolean isEmpty() {
            return this.consumed.isEmpty() && this.produced.isEmpty();
        }

        String describeConsumed() {
            return describe(this.consumed);
        }

        String describeProduced() {
            return describe(this.produced);
        }

        static void merge(List<ItemStack> target, ItemStack stack) {
            if (stack == null || stack.isEmpty()) {
                return;
            }
            for (ItemStack existing : target) {
                if (StoragePool.areSame(existing, stack)) {
                    existing.grow(stack.getCount());
                    return;
                }
            }
            target.add(stack.copy());
        }

        static String describe(List<ItemStack> stacks) {
            if (stacks.isEmpty()) {
                return "无";
            }
            StringBuilder text = new StringBuilder();
            for (ItemStack stack : stacks) {
                if (text.length() > 0) {
                    text.append("、");
                }
                text.append(stack.getDisplayName()).append("x").append(stack.getCount());
            }
            return text.toString();
        }
    }

    private String matrixSignature() {
        StringBuilder builder = new StringBuilder(64);
        for (int i = 0; i < SmartWorkbenchTile.GRID_SIZE; i++) {
            ItemStack stack = this.matrix.getStackInSlot(i);
            if (stack.isEmpty()) {
                builder.append('|');
            } else {
                builder.append(registryName(stack.getItem())).append(':').append(stack.getCount())
                        .append(':').append(stack.getTagCompound()).append('|');
            }
        }
        return builder.toString();
    }

    private static String registryName(Item item) {
        ResourceLocation location = Item.REGISTRY.getNameForObject(item);
        return location == null ? "unknown" : location.toString();
    }

    private static ItemStack copyWithCount(ItemStack stack, int count) {
        ItemStack copy = stack.copy();
        copy.setCount(count);
        return copy;
    }
}
