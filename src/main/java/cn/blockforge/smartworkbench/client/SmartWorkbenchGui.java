package cn.blockforge.smartworkbench.client;

import cn.blockforge.smartworkbench.ModNetwork;
import cn.blockforge.smartworkbench.SmartWorkbenchMod;
import cn.blockforge.smartworkbench.compat.JeiTransferBridge;
import cn.blockforge.smartworkbench.compat.PolymorphCompat;
import cn.blockforge.smartworkbench.container.CraftableEntry;
import cn.blockforge.smartworkbench.container.SmartWorkbenchContainer;
import cn.blockforge.smartworkbench.gui.GuiLayout;
import cn.blockforge.smartworkbench.network.C2SAutoCraftPacket;
import cn.blockforge.smartworkbench.network.C2SCraftRequestPacket;
import cn.blockforge.smartworkbench.network.C2SRecursiveCraftPacket;
import cn.blockforge.smartworkbench.network.S2CRecursiveCraftPromptPacket;
import net.minecraft.client.audio.PositionedSoundRecord;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.client.resources.I18n;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.init.SoundEvents;
import net.minecraft.inventory.Slot;
import net.minecraft.item.ItemStack;
import net.minecraft.item.crafting.CraftingManager;
import net.minecraft.item.crafting.IRecipe;
import net.minecraft.item.crafting.Ingredient;
import net.minecraft.util.NonNullList;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.text.TextFormatting;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.lwjgl.input.Keyboard;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 智能工作台界面：左边 3x3 合成区，右边 5x5 可合成列表。
 * <p>
 * 所有坐标都来自 {@link GuiLayout}，贴图上的格子框就是按这些坐标画的。
 * 1.12.2 的贴图绘制用 drawScaledCustomSizeModalRect（贴图是 292x166，不是 256x256）。
 */
@SideOnly(Side.CLIENT)
public class SmartWorkbenchGui extends GuiContainer {

    private static final ResourceLocation BACKGROUND =
            new ResourceLocation(SmartWorkbenchMod.MOD_ID, "textures/gui/smart_workbench.png");

    private static final int TEXTURE_W = GuiLayout.TEXTURE_WIDTH;
    private static final int TEXTURE_H = GuiLayout.TEXTURE_HEIGHT;

    private static final int PANEL_X = GuiLayout.PANEL_X;
    private static final int PANEL_Y = GuiLayout.PANEL_Y;
    private static final int PANEL_W = GuiLayout.PANEL_WIDTH;
    private static final int PANEL_H = GuiLayout.PANEL_HEIGHT;

    private static final int LIST_COLS = GuiLayout.LIST_COLS;
    private static final int LIST_ROWS = GuiLayout.LIST_ROWS;
    private static final int CELL = GuiLayout.LIST_PITCH;
    private static final int LIST_X = GuiLayout.LIST_ITEM_X;
    private static final int LIST_Y = GuiLayout.LIST_ITEM_Y;

    private static final int SCROLL_X = GuiLayout.SCROLL_X;
    private static final int SCROLL_W = GuiLayout.SCROLL_WIDTH;

    private static final int BUTTON_X = GuiLayout.BTN_X;
    private static final int BUTTON_Y = GuiLayout.BTN_Y;
    private static final int BUTTON_W = GuiLayout.BTN_WIDTH;
    private static final int BUTTON_H = GuiLayout.BTN_HEIGHT;

    private static final int AUTO_BUTTON_X = GuiLayout.BTN_AUTO_X;
    private static final int AUTO_BUTTON_Y = GuiLayout.BTN_AUTO_Y;
    private static final int AUTO_BUTTON_W = GuiLayout.BTN_AUTO_WIDTH;
    private static final int AUTO_BUTTON_H = GuiLayout.BTN_AUTO_HEIGHT;
    private static final int REFILL_BUTTON_X = GuiLayout.BTN_REFILL_X;
    private static final int REFILL_BUTTON_Y = GuiLayout.BTN_REFILL_Y;
    private static final int REFILL_BUTTON_W = GuiLayout.BTN_REFILL_WIDTH;
    private static final int REFILL_BUTTON_H = GuiLayout.BTN_REFILL_HEIGHT;
    private static final int FOLD_BUTTON_X = GuiLayout.BTN_FOLD_X;
    private static final int FOLD_BUTTON_Y = GuiLayout.BTN_FOLD_Y;
    private static final int FOLD_BUTTON_W = GuiLayout.BTN_FOLD_WIDTH;
    private static final int FOLD_BUTTON_H = GuiLayout.BTN_FOLD_HEIGHT;

    private static final int SEARCH_X = GuiLayout.SEARCH_X;
    private static final int SEARCH_Y = GuiLayout.SEARCH_Y;
    private static final int SEARCH_W = GuiLayout.SEARCH_WIDTH;
    private static final int SEARCH_H = GuiLayout.SEARCH_HEIGHT;

    // 浅底上的字用深色，深色格子上的字用白色
    private static final int TEXT_MAIN = GuiLayout.TEXT_COLOR;
    private static final int TEXT_DIM = GuiLayout.TEXT_DIM_COLOR;
    private static final int TEXT_ON_DARK = 0xFFFFFFFF;
    private static final int HOVER_BG = 0x408A86B8;
    /** 选中项的描边色（金）。 */
    private static final int SELECT_COLOR = 0xFFE0A83C;
    /** 同类折叠头的描边色（青）。 */
    private static final int GROUP_COLOR = 0xFF4FC3F7;
    private static final int GROUP_COUNT_COLOR = 0xFFFFFFFF;
    private static final int CHIP_OUTLINE_COLOR = 0xFF000000;
    private static final int SEARCH_BG = 0xFF5A5674;
    private static final int SEARCH_HINT_COLOR = 0xFFC7C5D8;
    private static final int BUTTON_HOVER = 0x264C4B63;
    private static final int BUTTON_PRESSED = 0x4D4C4B63;
    private static final int TEXT_PRESSED = 0xFF33324A;
    private static final int SCROLL_TRACK = GuiLayout.LINE_SOFT_COLOR;
    private static final int SCROLL_THUMB = GuiLayout.ACCENT_COLOR;

    private final SmartWorkbenchContainer menu;

    private int scroll;
    private boolean refreshPressed;
    private boolean autoPressed;
    private boolean autoHovered;
    private boolean refillHovered;
    private boolean foldHovered;
    private boolean foldPressed;
    /** 是否正在按住右侧滚动条拖动列表。 */
    private boolean draggingScroll;
    /** 当前选中的配方（自动合成按钮 / 快捷键就作用于它）。 */
    private ResourceLocation selectedRecipe;
    /** 搜索框：只负责过滤右侧列表，不参与网络协议。 */
    private GuiTextField searchBox;
    /** 同类折叠开关，默认开。 */
    private boolean folding = true;
    /** 已经点开、正在展开显示成员的分类。 */
    private final Set<String> expandedGroups = new HashSet<String>();
    /** 过滤 + 折叠之后真正要画的一格一格。 */
    private List<CraftableListView.Cell> display = new ArrayList<CraftableListView.Cell>();
    private boolean displayDirty = true;
    private List<CraftableEntry> displaySource;

    public SmartWorkbenchGui(InventoryPlayer playerInventory, cn.blockforge.smartworkbench.tile.SmartWorkbenchTile tile) {
        super(new SmartWorkbenchContainer(playerInventory, tile));
        this.menu = (SmartWorkbenchContainer) this.inventorySlots;
        this.xSize = TEXTURE_W;
        this.ySize = TEXTURE_H;
    }

    @Override
    public void initGui() {
        super.initGui();
        String previous = this.searchBox == null ? "" : this.searchBox.getText();
        this.searchBox = new GuiTextField(0, this.fontRenderer,
                this.guiLeft + SEARCH_X, this.guiTop + SEARCH_Y, SEARCH_W, SEARCH_H);
        this.searchBox.setMaxStringLength(32);
        this.searchBox.setEnableBackgroundDrawing(false);
        this.searchBox.setTextColor(0xFFFFFFFF);
        this.searchBox.setCanLoseFocus(true);
        this.searchBox.setText(previous);
        this.displayDirty = true;
        this.displaySource = null;
    }

    @Override
    public void updateScreen() {
        super.updateScreen();
        // 物品管理器可能比界面晚准备好，或中途重载；每 tick 补挂一次
        JeiTransferBridge.ensureRegistered();
        if (this.searchBox != null) {
            this.searchBox.updateCursorCounter();
        }
        ensureDisplay();
    }

    /** 搜索词或折叠状态变了才重算列表，平时就用上一帧的结果。 */
    private void ensureDisplay() {
        List<CraftableEntry> source = this.menu.getCraftables();
        if (!this.displayDirty && source == this.displaySource) {
            return;
        }
        this.displaySource = source;
        this.displayDirty = false;
        String query = this.searchBox == null ? "" : this.searchBox.getText();
        this.display = CraftableListView.build(source, query, this.folding, this.expandedGroups);
        this.scroll = MathHelper.clamp(this.scroll, 0, maxScroll());
    }

    /** 列表按行滚动：最多能往下滚几行。 */
    private int maxScroll() {
        int rows = (this.display.size() + LIST_COLS - 1) / LIST_COLS;
        return Math.max(0, rows - LIST_ROWS);
    }

    @Override
    protected void drawGuiContainerBackgroundLayer(float partialTicks, int mouseX, int mouseY) {
        this.mc.getTextureManager().bindTexture(BACKGROUND);
        // 贴图 292x166：必须按真实尺寸画，否则会被按 256x256 拉伸导致格子错位
        Gui.drawScaledCustomSizeModalRect(this.guiLeft, this.guiTop, 0, 0,
                TEXTURE_W, TEXTURE_H, TEXTURE_W, TEXTURE_H, TEXTURE_W, TEXTURE_H);

        ensureDisplay();
        this.scroll = MathHelper.clamp(this.scroll, 0, maxScroll());

        drawCentred(tr("gui." + SmartWorkbenchMod.MOD_ID + ".panel.craftable", "可合成"),
                this.guiLeft + GuiLayout.LIST_TITLE_X, this.guiTop + GuiLayout.LIST_TITLE_Y - 1, TEXT_MAIN, false);

        // 搜索框画在背景层：它只占标题行，不和任何物品格重叠
        if (this.searchBox != null) {
            drawSearchBoxFrame();
            this.searchBox.drawTextBox();
        }

        // 5x5 的配方图标
        int hovered = cellIndexAt(mouseX, mouseY);
        GlStateManager.pushMatrix();
        RenderHelper.enableGUIStandardItemLighting();
        GlStateManager.enableRescaleNormal();
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        for (int row = 0; row < LIST_ROWS; row++) {
            for (int col = 0; col < LIST_COLS; col++) {
                int index = (this.scroll + row) * LIST_COLS + col;
                if (index >= this.display.size()) {
                    break;
                }
                CraftableListView.Cell cell = this.display.get(index);
                int x = this.guiLeft + LIST_X + col * CELL;
                int y = this.guiTop + LIST_Y + row * CELL;
                if (hovered == index) {
                    // 贴图上的格子框是 18x18，左上角比物品坐标各小 1 像素
                    Gui.drawRect(x - 1, y - 1, x + CELL - 1, y + CELL - 1, HOVER_BG);
                }
                if (!cell.header && this.selectedRecipe != null
                        && this.selectedRecipe.equals(cell.entry.recipeId)) {
                    // 选中的那格描一圈金边：自动合成按钮和快捷键要作用在它身上
                    drawOutline(x - 1, y - 1, CELL, CELL, SELECT_COLOR);
                }
                ItemStack result = cell.entry.result;
                this.itemRender.renderItemAndEffectIntoGUI(result, x, y);
                if (cell.header) {
                    drawGroupHeader(cell, x, y);
                } else {
                    GlStateManager.disableLighting();
                    this.itemRender.renderItemOverlayIntoGUI(this.fontRenderer, result, x, y, null);
                    GlStateManager.enableLighting();
                }
            }
        }
        GlStateManager.disableRescaleNormal();
        RenderHelper.disableStandardItemLighting();
        GlStateManager.popMatrix();

        if (this.display.isEmpty()) {
            String query = this.searchBox == null ? "" : this.searchBox.getText();
            String message = query == null || query.trim().isEmpty()
                    ? tr("gui." + SmartWorkbenchMod.MOD_ID + ".panel.empty",
                            "接入存储和背包里暂时没有能直接合成的东西")
                    : trf("gui." + SmartWorkbenchMod.MOD_ID + ".panel.empty_search",
                            "没有找到匹配「%s」的配方", query);
            drawCentredHint(message);
        }

        // 滚动条（贴图已画好轨道，这里只画滑块）
        int maxScroll = maxScroll();
        if (maxScroll > 0) {
            int trackTop = this.guiTop + LIST_Y - 1;
            int trackHeight = LIST_ROWS * CELL;
            int thumbHeight = Math.max(10, (trackHeight - 2) * LIST_ROWS / (maxScroll + LIST_ROWS));
            int thumbTop = trackTop + 1 + (trackHeight - 2 - thumbHeight) * this.scroll / maxScroll;
            Gui.drawRect(this.guiLeft + SCROLL_X + 1, thumbTop,
                    this.guiLeft + SCROLL_X + SCROLL_W - 1, thumbTop + thumbHeight, SCROLL_THUMB);
            Gui.drawRect(this.guiLeft + SCROLL_X + 1, thumbTop,
                    this.guiLeft + SCROLL_X + SCROLL_W - 1, thumbTop + 1, SCROLL_TRACK);
        }

        // 三个按钮：底色已经画进贴图，这里补悬停/按下的高亮和文字
        drawButton(BUTTON_X, BUTTON_Y, BUTTON_W, BUTTON_H, isPointInButton(mouseX, mouseY),
                this.refreshPressed, tr("gui." + SmartWorkbenchMod.MOD_ID + ".button.refresh", "刷新"));
        boolean autoHovered = isPointInAutoButton(mouseX, mouseY);
        boolean refillHovered = isPointInRefillButton(mouseX, mouseY);
        boolean foldHovered = isPointInFoldButton(mouseX, mouseY);
        drawButton(AUTO_BUTTON_X, AUTO_BUTTON_Y, AUTO_BUTTON_W, AUTO_BUTTON_H,
                autoHovered, this.autoPressed,
                tr("gui." + SmartWorkbenchMod.MOD_ID + ".button.auto", "自动"));
        drawButton(FOLD_BUTTON_X, FOLD_BUTTON_Y, FOLD_BUTTON_W, FOLD_BUTTON_H,
                foldHovered, this.foldPressed,
                tr("gui." + SmartWorkbenchMod.MOD_ID + ".button.fold", "折叠"));
        if (this.folding) {
            // 按钮本身没有"开/关"字样，用金边表示折叠正开着
            drawOutline(this.guiLeft + FOLD_BUTTON_X, this.guiTop + FOLD_BUTTON_Y,
                    FOLD_BUTTON_W, FOLD_BUTTON_H, SELECT_COLOR);
        }
        drawButton(REFILL_BUTTON_X, REFILL_BUTTON_Y, REFILL_BUTTON_W, REFILL_BUTTON_H,
                refillHovered, false,
                this.menu.isAutoRefillEnabled()
                        ? tr("gui." + SmartWorkbenchMod.MOD_ID + ".button.refill_on", "自动补齐：开")
                        : tr("gui." + SmartWorkbenchMod.MOD_ID + ".button.refill_off", "自动补齐：关"));
        this.autoHovered = autoHovered;
        this.refillHovered = refillHovered;
        this.foldHovered = foldHovered;

        // 接入存储 / 输出容器数量
        String storageLine = tr("gui." + SmartWorkbenchMod.MOD_ID + ".panel.storage", "接入存储: ")
                + this.menu.getConnectedStorageCount()
                + tr("gui." + SmartWorkbenchMod.MOD_ID + ".panel.output", "  输出: ")
                + this.menu.getOutputCount();
        this.fontRenderer.drawString(storageLine,
                this.guiLeft + GuiLayout.STORAGE_X, this.guiTop + GuiLayout.STORAGE_Y, TEXT_DIM, false);

        // 标题
        this.fontRenderer.drawString(tr("tile.smart_workbench.smart_workbench.name", "智能工作台"),
                this.guiLeft + GuiLayout.LABEL_TITLE_X, this.guiTop + GuiLayout.LABEL_TITLE_Y, TEXT_MAIN, false);
        this.fontRenderer.drawString(
                this.mc.player.inventory.hasCustomName() ? this.mc.player.inventory.getName()
                        : tr("container.inventory", "物品栏"),
                this.guiLeft + GuiLayout.LABEL_INVENTORY_X, this.guiTop + GuiLayout.LABEL_INVENTORY_Y,
                TEXT_DIM, false);
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        super.drawScreen(mouseX, mouseY, partialTicks);
        // 1.12.2 的基类 drawScreen 不画槽位提示框：原版箱子/工作台都是在子类里自己补这一句
        // （漏掉它背包物品悬停就没有提示框）
        this.renderHoveredToolTip(mouseX, mouseY);
        // 背景和原版槽位（背包、合成格、产物格）都画完之后，最后画自定义提示，保证不被盖住
        drawListTooltip(mouseX, mouseY);
        drawResultSlotHint(mouseX, mouseY);
        drawButtonHints(mouseX, mouseY);
        drawRecursivePrompt();
    }

    /** 产物格的提示框要带「批量合成」说明：原版那份先让路，由 drawResultSlotHint 补全。 */
    @Override
    protected void renderHoveredToolTip(int mouseX, int mouseY) {
        Slot hovered = this.getSlotUnderMouse();
        if (hovered != null && hovered.slotNumber == SmartWorkbenchContainer.SLOT_RESULT && hovered.getHasStack()) {
            return;
        }
        super.renderHoveredToolTip(mouseX, mouseY);
    }

    /** 按钮说明：鼠标停在"自动"/"自动补齐"/"折叠"上时弹一段说明。 */
    private void drawButtonHints(int mouseX, int mouseY) {
        if (cellIndexAt(mouseX, mouseY) >= 0 || this.getSlotUnderMouse() != null) {
            return;
        }
        if (this.autoHovered) {
            drawButtonHint(tr("gui." + SmartWorkbenchMod.MOD_ID + ".tooltip.auto_button",
                            "自动合成：先点右侧要合成的物品选中它，再点这个按钮。缺少的中间材料会自动补齐，产物送进输出容器"),
                    AUTO_BUTTON_X, AUTO_BUTTON_Y, AUTO_BUTTON_W, AUTO_BUTTON_H);
        } else if (this.refillHovered) {
            drawButtonHint(tr("gui." + SmartWorkbenchMod.MOD_ID + ".tooltip.refill",
                            "缺少的中间材料可由其他配方合成"),
                    REFILL_BUTTON_X, REFILL_BUTTON_Y, REFILL_BUTTON_W, REFILL_BUTTON_H);
        } else if (this.foldHovered) {
            drawButtonHint(tr(this.folding
                            ? "gui." + SmartWorkbenchMod.MOD_ID + ".tooltip.fold_on"
                            : "gui." + SmartWorkbenchMod.MOD_ID + ".tooltip.fold_off",
                    this.folding
                            ? "同类折叠：开。把同一种东西（比如各种木头的楼梯）收成一项，点它展开；再点这个按钮可关掉"
                            : "同类折叠：关。列表里的每一项都单独显示"),
                    FOLD_BUTTON_X, FOLD_BUTTON_Y, FOLD_BUTTON_W, FOLD_BUTTON_H);
        }
    }

    /** 列表空的时候，把提示文字在 5x5 区域里居中排。 */
    private void drawCentredHint(String message) {
        int width = LIST_COLS * CELL - 8;
        List<String> lines = this.fontRenderer.listFormattedStringToWidth(message, width);
        int areaTop = this.guiTop + LIST_Y;
        int total = lines.size() * (this.fontRenderer.FONT_HEIGHT + 1);
        int y = areaTop + (LIST_ROWS * CELL - total) / 2 + 2;
        for (String line : lines) {
            this.fontRenderer.drawString(line,
                    this.guiLeft + LIST_X + (width - this.fontRenderer.getStringWidth(line)) / 2, y,
                    TEXT_ON_DARK, false);
            y += this.fontRenderer.FONT_HEIGHT + 1;
        }
    }

    /** 自己画搜索框的底、边和占位提示。 */
    private void drawSearchBoxFrame() {
        int x = this.guiLeft + SEARCH_X;
        int y = this.guiTop + SEARCH_Y;
        int border = this.searchBox.isFocused() ? SELECT_COLOR : GuiLayout.LINE_SOFT_COLOR;
        Gui.drawRect(x - 1, y - 1, x + SEARCH_W + 1, y + SEARCH_H + 1, border);
        Gui.drawRect(x, y, x + SEARCH_W, y + SEARCH_H, SEARCH_BG);
        String text = this.searchBox.getText();
        if ((text == null || text.isEmpty()) && !this.searchBox.isFocused()) {
            String hint = tr("gui." + SmartWorkbenchMod.MOD_ID + ".search.hint", "搜索 / 拼音");
            // 提示太长就裁掉，避免漏到框外
            int maxWidth = SEARCH_W - 6;
            while (!hint.isEmpty() && this.fontRenderer.getStringWidth(hint) > maxWidth) {
                hint = hint.substring(0, hint.length() - 1);
            }
            this.fontRenderer.drawString(hint, x + 4, y + (SEARCH_H - 8) / 2, SEARCH_HINT_COLOR, false);
        }
    }

    /**
     * 折叠头的额外装饰：青边 + 左上角 +/- 表示收起/展开 + 右下角这一类的成员数。
     * 角标用描边字（黑边白字），落在任何颜色的图标上都有足够对比度。
     */
    private void drawGroupHeader(CraftableListView.Cell cell, int x, int y) {
        // 角标抬到 z=200：物品图标画在 z≈150，不抬会被图标不透明的角落盖住
        GlStateManager.pushMatrix();
        GlStateManager.translate(0.0F, 0.0F, 200.0F);
        drawOutline(x - 1, y - 1, CELL, CELL, GROUP_COLOR);
        String mark = this.expandedGroups.contains(cell.groupKey) ? "-" : "+";
        drawOutlinedChar(mark, x + 1, y + 1, GROUP_COLOR);
        String count = String.valueOf(cell.groupSize);
        drawOutlinedChar(count, x + CELL - 2 - this.fontRenderer.getStringWidth(count), y + 9,
                GROUP_COUNT_COLOR);
        GlStateManager.popMatrix();
    }

    /** 描边字：先把字在周围 8 个方向各画一遍纯黑，再在正中画本色。 */
    private void drawOutlinedChar(String text, int x, int y, int color) {
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                if (dx != 0 || dy != 0) {
                    this.fontRenderer.drawString(text, x + dx, y + dy, CHIP_OUTLINE_COLOR, false);
                }
            }
        }
        this.fontRenderer.drawString(text, x, y, color, false);
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) throws IOException {
        if (handleSearchBoxClick(mouseX, mouseY, mouseButton)) {
            return;
        }
        ensureDisplay();
        // 全部展开 / 全部折叠（默认 空格+左键）
        if (ModKeybindings.isHeld(ModKeybindings.EXPAND_ALL) && mouseButton == 0
                && isInsidePanel(mouseX, mouseY) && toggleExpandAllGroups()) {
            return;
        }
        // 批量合成（默认 左Ctrl+左键）。两处入口：产物格 / 列表项。
        if (ModKeybindings.isHeld(ModKeybindings.BATCH_CRAFT) && mouseButton == 0) {
            if (isPointInResultSlot(mouseX, mouseY)) {
                playClickSound();
                this.mc.playerController.sendEnchantPacket(this.menu.windowId,
                        SmartWorkbenchContainer.BUTTON_BATCH_GRID);
                return;
            }
            int batchIndex = cellIndexAt(mouseX, mouseY);
            if (batchIndex >= 0 && !this.display.get(batchIndex).header) {
                CraftableListView.Cell cell = this.display.get(batchIndex);
                this.selectedRecipe = cell.entry.recipeId;
                playClickSound();
                ModNetwork.sendToServer(new C2SCraftRequestPacket(this.menu.getBenchPos(),
                        cell.entry.recipeId, true, true));
                return;
            }
        }
        // 自动合成（Alt+左键）：点在列表物品上就合成那一项，点别处就合成选中项。
        if (isAltKeyDown() && mouseButton == 0) {
            int autoIndex = cellIndexAt(mouseX, mouseY);
            if (autoIndex >= 0) {
                CraftableListView.Cell cell = this.display.get(autoIndex);
                if (!cell.header) {
                    selectAndAutoCraft(cell);
                    return;
                }
            } else {
                autoCraftSelected();
                return;
            }
        }
        // 递归补齐确认框
        if (this.menu.hasRecursivePrompt() && mouseButton == 0) {
            int boxX = (this.width - 180) / 2;
            int boxY = (this.height - 90) / 2;
            if (mouseX >= boxX && mouseX < boxX + 180 && mouseY >= boxY && mouseY < boxY + 90) {
                ResourceLocation recipeId = this.menu.getRecursivePromptRecipeId();
                boolean accept = mouseX < boxX + 90;
                this.menu.clearRecursivePrompt();
                playClickSound();
                if (recipeId != null) {
                    ModNetwork.sendToServer(new C2SRecursiveCraftPacket(
                            this.menu.getBenchPos(), recipeId, accept));
                }
                return;
            }
        }
        if (mouseButton == 0) {
            if (isPointInScrollBar(mouseX, mouseY) && maxScroll() > 0) {
                this.draggingScroll = true;
                updateScrollFromMouse(mouseY);
                return;
            }
            if (isPointInButton(mouseX, mouseY)) {
                this.refreshPressed = true;
                playClickSound();
                this.mc.playerController.sendEnchantPacket(this.menu.windowId,
                        SmartWorkbenchContainer.BUTTON_REFRESH);
                return;
            }
            if (isPointInAutoButton(mouseX, mouseY)) {
                this.autoPressed = true;
                autoCraftSelected();
                return;
            }
            if (isPointInFoldButton(mouseX, mouseY)) {
                this.foldPressed = true;
                toggleFolding();
                return;
            }
            if (isPointInRefillButton(mouseX, mouseY)) {
                this.mc.playerController.sendEnchantPacket(this.menu.windowId,
                        SmartWorkbenchContainer.BUTTON_AUTO_REFILL);
                playClickSound();
                return;
            }
            int index = cellIndexAt(mouseX, mouseY);
            if (index >= 0) {
                CraftableListView.Cell cell = this.display.get(index);
                if (cell.header) {
                    playClickSound();
                    toggleGroup(cell.groupKey);
                    return;
                }
                // Shift 点击 = 直接合成；普通点击 = 配料到合成格。顺手记成"选中项"
                this.selectedRecipe = cell.entry.recipeId;
                playClickSound();
                ModNetwork.sendToServer(new C2SCraftRequestPacket(this.menu.getBenchPos(),
                        cell.entry.recipeId, isShiftKeyDown(), false));
                return;
            }
        }
        super.mouseClicked(mouseX, mouseY, mouseButton);
    }

    /** 点搜索框就聚焦；点别处就取消聚焦，免得打字一直被搜索框吃掉。 */
    private boolean handleSearchBoxClick(int mouseX, int mouseY, int button) {
        if (this.searchBox == null) {
            return false;
        }
        if (isPointInSearchBox(mouseX, mouseY)) {
            if (!this.searchBox.isFocused()) {
                this.searchBox.setFocused(true);
            }
            this.searchBox.mouseClicked(mouseX, mouseY, button);
            return true;
        }
        if (this.searchBox.isFocused()) {
            this.searchBox.setFocused(false);
        }
        return false;
    }

    /** 记下选中项并直接自动合成（Alt+左键点某个具体物品）。 */
    private void selectAndAutoCraft(CraftableListView.Cell cell) {
        this.selectedRecipe = cell.entry.recipeId;
        sendAutoCraft(cell.entry);
    }

    /**
     * 全部展开 / 全部折叠：列表里还有收起的就全部展开，已经全展开就全部收起。
     * 列表压根没有折叠项时什么都不做，免得白吃一下普通左键。
     */
    private boolean toggleExpandAllGroups() {
        int headers = 0;
        int collapsed = 0;
        for (CraftableListView.Cell cell : this.display) {
            if (cell.header) {
                headers++;
                if (!this.expandedGroups.contains(cell.groupKey)) {
                    collapsed++;
                }
            }
        }
        if (headers == 0) {
            return false;
        }
        if (collapsed > 0) {
            for (CraftableListView.Cell cell : this.display) {
                if (cell.header) {
                    this.expandedGroups.add(cell.groupKey);
                }
            }
        } else {
            this.expandedGroups.clear();
        }
        this.displayDirty = true;
        playClickSound();
        return true;
    }

    /** 展开 / 收起某一类。 */
    private void toggleGroup(String groupKey) {
        if (!this.expandedGroups.remove(groupKey)) {
            this.expandedGroups.add(groupKey);
        }
        this.displayDirty = true;
    }

    /** 折叠总开关：关掉时把展开状态一并清掉。 */
    private void toggleFolding() {
        this.folding = !this.folding;
        if (!this.folding) {
            this.expandedGroups.clear();
        }
        this.displayDirty = true;
        playClickSound();
    }

    /** 自动合成当前选中的配方；没选中就提示一句。 */
    private void autoCraftSelected() {
        CraftableEntry entry = findSelected();
        if (entry == null) {
            this.mc.player.sendStatusMessage(
                    text(tr("gui." + SmartWorkbenchMod.MOD_ID + ".msg.select_first",
                            "先在右侧列表里点一下要自动合成的物品")), true);
            return;
        }
        sendAutoCraft(entry);
    }

    /** 按配方 id 在最新的可合成列表里找回选中项：列表被搜索/折叠/刷新后也找得回来。 */
    private CraftableEntry findSelected() {
        if (this.selectedRecipe == null) {
            return null;
        }
        for (CraftableEntry entry : this.menu.getCraftables()) {
            if (this.selectedRecipe.equals(entry.recipeId)) {
                return entry;
            }
        }
        return null;
    }

    private void sendAutoCraft(CraftableEntry entry) {
        playClickSound();
        ModNetwork.sendToServer(new C2SAutoCraftPacket(this.menu.getBenchPos(), entry.recipeId));
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) throws IOException {
        // 搜索框聚焦时，键盘优先给搜索框：Esc 退出输入，其余按键一律吞掉，
        // 免得打字时误触刷新、自动合成快捷键，或者被 E 键把整个界面关掉。
        if (this.searchBox != null && this.searchBox.isFocused()) {
            if (keyCode == Keyboard.KEY_ESCAPE || keyCode == Keyboard.KEY_RETURN
                    || keyCode == Keyboard.KEY_NUMPADENTER) {
                this.searchBox.setFocused(false);
                return;
            }
            this.searchBox.textboxKeyTyped(typedChar, keyCode);
            this.displayDirty = true;
            return;
        }
        // Ctrl+F 聚焦搜索框
        if (keyCode == Keyboard.KEY_F && isCtrlKeyDown() && this.searchBox != null) {
            this.searchBox.setFocused(true);
            return;
        }
        if (keyCode == ModKeybindings.REFRESH.getKeyCode() && keyCode >= 0
                && !isCtrlKeyDown() && !isAltKeyDown()) {
            playClickSound();
            this.mc.playerController.sendEnchantPacket(this.menu.windowId,
                    SmartWorkbenchContainer.BUTTON_REFRESH);
            return;
        }
        super.keyTyped(typedChar, keyCode);
    }

    @Override
    protected void mouseReleased(int mouseX, int mouseY, int state) {
        this.refreshPressed = false;
        this.autoPressed = false;
        this.foldPressed = false;
        this.draggingScroll = false;
        super.mouseReleased(mouseX, mouseY, state);
    }

    @Override
    protected void mouseClickMove(int mouseX, int mouseY, int clickedMouseButton, long timeSinceLastClick) {
        if (this.draggingScroll && clickedMouseButton == 0) {
            updateScrollFromMouse(mouseY);
            return;
        }
        super.mouseClickMove(mouseX, mouseY, clickedMouseButton, timeSinceLastClick);
    }

    @Override
    public void handleMouseInput() throws IOException {
        // 滚轮：优先给右侧列表
        int wheel = org.lwjgl.input.Mouse.getEventDWheel();
        if (wheel != 0) {
            int mouseX = org.lwjgl.input.Mouse.getEventX() * this.width / this.mc.displayWidth;
            int mouseY = this.height - org.lwjgl.input.Mouse.getEventY() * this.height / this.mc.displayHeight - 1;
            if (isInsidePanel(mouseX, mouseY) && maxScroll() > 0) {
                this.scroll = MathHelper.clamp(this.scroll - Integer.signum(wheel), 0, maxScroll());
                return;
            }
        }
        super.handleMouseInput();
    }

    /** 原版按钮的点击音效，给刷新按钮、列表项一个"点到了"的反馈。 */
    private void playClickSound() {
        this.mc.getSoundHandler().playSound(
                PositionedSoundRecord.getMasterRecord(SoundEvents.UI_BUTTON_CLICK, 1.0F));
    }

    /** 居中绘制文字。 */
    private void drawCentred(String text, int centerX, int y, int color, boolean shadow) {
        this.fontRenderer.drawString(text, centerX - this.fontRenderer.getStringWidth(text) / 2, y,
                color, shadow);
    }

    /** 右侧列表项的提示框：物品名 + 材料清单 + 操作说明。 */
    private void drawListTooltip(int mouseX, int mouseY) {
        ensureDisplay();
        int index = cellIndexAt(mouseX, mouseY);
        if (index < 0 || index >= this.display.size()) {
            return;
        }
        CraftableListView.Cell cell = this.display.get(index);
        drawHoveringText(cell.header ? buildGroupTooltip(cell) : buildTooltip(cell.entry), mouseX, mouseY);
    }

    /** 产物格悬停时在原版物品提示框后面补一行「批量合成」。 */
    private void drawResultSlotHint(int mouseX, int mouseY) {
        Slot hovered = this.getSlotUnderMouse();
        if (hovered == null || hovered.slotNumber != SmartWorkbenchContainer.SLOT_RESULT
                || !hovered.getHasStack()) {
            return;
        }
        List<String> lines = new ArrayList<String>();
        lines.add(hovered.getStack().getDisplayName());
        for (String line : hovered.getStack().getTooltip(this.mc.player,
                this.mc.gameSettings.advancedItemTooltips
                        ? net.minecraft.client.util.ITooltipFlag.TooltipFlags.ADVANCED
                        : net.minecraft.client.util.ITooltipFlag.TooltipFlags.NORMAL)) {
            if (!lines.contains(line)) {
                lines.add(line);
            }
        }
        String batchKey = ModKeybindings.heldKeyName(ModKeybindings.BATCH_CRAFT);
        if (batchKey != null) {
            lines.add(TextFormatting.GRAY + trf("gui." + SmartWorkbenchMod.MOD_ID + ".tooltip.result_batch",
                    "%s+点击：批量合成合成格里这份配方，直到材料用完（自己摆的料也算）", batchKey));
        }
        drawHoveringText(lines, mouseX, mouseY);
    }

    /** 折叠头的提示框：这一类是什么、有多少项、怎么展开。 */
    private List<String> buildGroupTooltip(CraftableListView.Cell cell) {
        List<String> lines = new ArrayList<String>();
        lines.add(cell.entry.result.getDisplayName() + " 等 " + cell.groupSize + " 项同类物品");
        lines.add(TextFormatting.GRAY + trf("gui." + SmartWorkbenchMod.MOD_ID + ".tooltip.group_key",
                "同类：%s", CraftableListView.groupLabel(cell.groupKey)));
        lines.add(TextFormatting.YELLOW + tr("gui." + SmartWorkbenchMod.MOD_ID + ".tooltip.group_click",
                "左键：展开 / 收起这一类"));
        String expandKey = ModKeybindings.heldKeyName(ModKeybindings.EXPAND_ALL);
        if (expandKey != null) {
            lines.add(TextFormatting.GOLD + trf("gui." + SmartWorkbenchMod.MOD_ID + ".tooltip.group_expand_all",
                    "%s+左键：全部展开 / 全部折叠", expandKey));
        }
        return lines;
    }

    private List<String> buildTooltip(CraftableEntry entry) {
        List<String> lines = new ArrayList<String>();
        lines.add(entry.result.getDisplayName());
        IRecipe recipe = entry.recipeId == null ? null : CraftingManager.getRecipe(entry.recipeId);
        if (recipe != null) {
            NonNullList<Ingredient> ingredients = recipe.getIngredients();
            Map<String, Integer> merged = new LinkedHashMap<String, Integer>();
            for (Ingredient ingredient : ingredients) {
                if (ingredient == null || ingredient == Ingredient.EMPTY
                        || ingredient.getMatchingStacks().length == 0) {
                    continue;
                }
                String name = ingredient.getMatchingStacks()[0].getDisplayName();
                Integer old = merged.get(name);
                merged.put(name, old == null ? 1 : old + 1);
            }
            for (Map.Entry<String, Integer> stackEntry : merged.entrySet()) {
                lines.add(TextFormatting.GRAY + stackEntry.getKey() + "x" + stackEntry.getValue());
            }
        }
        if (entry.recipeId != null) {
            lines.add(TextFormatting.DARK_GRAY + trf("gui." + SmartWorkbenchMod.MOD_ID + ".tooltip.recipe_source",
                    "配方来源：%s", entry.recipeId.getNamespace()));
        }
        int sameOutput = countSameOutput(entry);
        if (sameOutput > 1) {
            lines.add(TextFormatting.AQUA + trf("gui." + SmartWorkbenchMod.MOD_ID + ".tooltip.duplicate",
                    "这件产物有 %s 份配方，点哪一项就用哪一份", sameOutput));
        }
        lines.add(TextFormatting.YELLOW + tr("gui." + SmartWorkbenchMod.MOD_ID + ".tooltip.click",
                "点击：自动配料到合成格"));
        lines.add(TextFormatting.YELLOW + tr("gui." + SmartWorkbenchMod.MOD_ID + ".tooltip.shift",
                "Shift+点击：直接用存储或背包里的材料合成"));
        String batchKey = ModKeybindings.heldKeyName(ModKeybindings.BATCH_CRAFT);
        if (batchKey != null) {
            lines.add(TextFormatting.YELLOW + trf("gui." + SmartWorkbenchMod.MOD_ID + ".tooltip.ctrl",
                    "%s+点击：批量合成，直到材料用完", batchKey));
        }
        lines.add(TextFormatting.GOLD + trf("gui." + SmartWorkbenchMod.MOD_ID + ".tooltip.auto",
                "%s：自动合成（缺料自动补齐，产物进输出容器）", "Alt+左键"));
        return lines;
    }

    /** 列表里还有几项是同一件产物。 */
    private int countSameOutput(CraftableEntry entry) {
        int count = 0;
        for (CraftableEntry other : this.menu.getCraftables()) {
            if (cn.blockforge.smartworkbench.container.StoragePool.areSame(other.result, entry.result)) {
                count++;
            }
        }
        return count;
    }

    /** 在按钮旁边固定绘制说明：优先按钮右侧的空位，放不下挪到按钮正上方。 */
    private void drawButtonHint(String message, int buttonX, int buttonY, int buttonWidth, int buttonHeight) {
        final int gap = 4;
        final int minWidth = 96;

        int rightSpace = TEXTURE_W - (buttonX + buttonWidth) - gap;
        int aboveSpace = TEXTURE_W - buttonX - gap;

        int x = buttonX + buttonWidth + gap;
        int maxWidth = rightSpace;
        int y = buttonY + buttonHeight + 2;
        if (rightSpace < minWidth) {
            maxWidth = aboveSpace;
            x = buttonX;
        }

        maxWidth = Math.max(minWidth, maxWidth);
        List<String> lines = this.fontRenderer.listFormattedStringToWidth(message, maxWidth);
        int textWidth = 0;
        for (String line : lines) {
            textWidth = Math.max(textWidth, this.fontRenderer.getStringWidth(line));
        }
        int boxWidth = Math.min(maxWidth, Math.max(minWidth, textWidth)) + 6;
        int lineHeight = this.fontRenderer.FONT_HEIGHT + 1;
        int boxHeight = lines.size() * lineHeight + 6;

        if (rightSpace < minWidth) {
            y = buttonY - boxHeight - 2;
            if (y < 0) {
                y = buttonY + buttonHeight + 2;
            }
        } else if (y + boxHeight > TEXTURE_H) {
            x = buttonX;
            y = buttonY - boxHeight - 2;
            if (y < 0) {
                y = buttonY + buttonHeight + 2;
            }
        }

        x = MathHelper.clamp(x, 0, Math.max(0, TEXTURE_W - boxWidth));
        y = MathHelper.clamp(y, 0, Math.max(0, TEXTURE_H - boxHeight));

        int screenX = this.guiLeft + x;
        int screenY = this.guiTop + y;
        // 抬到 z=400（原版提示框同层）：物品图标画在 z≈150，z=0 的矩形会被深度测试挡住
        GlStateManager.pushMatrix();
        GlStateManager.translate(0.0F, 0.0F, 400.0F);
        Gui.drawRect(screenX - 2, screenY - 2, screenX + boxWidth + 2, screenY + boxHeight + 2, 0xFF2A2440);
        drawOutline(screenX - 2, screenY - 2, boxWidth + 4, boxHeight + 4, SELECT_COLOR);
        int lineY = screenY + 2;
        for (String line : lines) {
            this.fontRenderer.drawString(line, screenX + 1, lineY, TEXT_ON_DARK, false);
            lineY += lineHeight;
        }
        GlStateManager.popMatrix();
    }

    /** 「自动补齐中间材料」的确认框：左侧按钮=补齐，右侧=取消。 */
    private void drawRecursivePrompt() {
        if (!this.menu.hasRecursivePrompt()) {
            return;
        }
        int boxX = (this.width - 180) / 2;
        int boxY = (this.height - 90) / 2;
        GlStateManager.pushMatrix();
        GlStateManager.translate(0.0F, 0.0F, 400.0F);
        Gui.drawRect(boxX - 3, boxY - 3, boxX + 183, boxY + 93, 0xE6101018);
        drawOutline(boxX - 3, boxY - 3, 186, 96, SELECT_COLOR);
        this.fontRenderer.drawString(
                tr("gui." + SmartWorkbenchMod.MOD_ID + ".prompt.title", "缺少的材料可以自动补齐："),
                boxX + 6, boxY + 5, TEXT_ON_DARK, false);
        int row = 0;
        for (S2CRecursiveCraftPromptPacket.MissingEntry entry : this.menu.getRecursivePromptAuto()) {
            if (row >= 4) {
                break;
            }
            this.fontRenderer.drawString(
                    TextFormatting.GOLD + entry.stack.getDisplayName() + " x" + entry.count,
                    boxX + 8, boxY + 18 + row * 11, TEXT_ON_DARK, false);
            row++;
        }
        int autoCount = this.menu.getRecursivePromptAuto().size();
        if (autoCount > 4) {
            this.fontRenderer.drawString("+" + (autoCount - 4) + " ...",
                    boxX + 8, boxY + 18 + 4 * 11, TEXT_DIM, false);
        }
        String yes = tr("gui." + SmartWorkbenchMod.MOD_ID + ".prompt.accept", "补齐并合成");
        String no = tr("gui." + SmartWorkbenchMod.MOD_ID + ".prompt.cancel", "取消");
        Gui.drawRect(boxX + 4, boxY + 66, boxX + 88, boxY + 84, 0xFF3A6A4A);
        Gui.drawRect(boxX + 92, boxY + 66, boxX + 176, boxY + 84, 0xFF6A3A3A);
        drawCentredAbsolute(yes, boxX + 46, boxY + 72, TEXT_ON_DARK);
        drawCentredAbsolute(no, boxX + 134, boxY + 72, TEXT_ON_DARK);
        GlStateManager.popMatrix();
    }

    private void drawCentredAbsolute(String text, int centerX, int y, int color) {
        this.fontRenderer.drawString(text, centerX - this.fontRenderer.getStringWidth(text) / 2, y,
                color, false);
    }

    /** 鼠标落在哪个列表项上；没落在格子上返回 -1。 */
    private int cellIndexAt(int mouseX, int mouseY) {
        int localX = mouseX - (this.guiLeft + LIST_X);
        int localY = mouseY - (this.guiTop + LIST_Y);
        if (localX < 0 || localY < 0) {
            return -1;
        }
        int col = localX / CELL;
        int row = localY / CELL;
        if (col < 0 || col >= LIST_COLS || row < 0 || row >= LIST_ROWS) {
            return -1;
        }
        int index = (this.scroll + row) * LIST_COLS + col;
        return index < this.display.size() ? index : -1;
    }

    private boolean isPointInSearchBox(int mouseX, int mouseY) {
        int x = this.guiLeft + SEARCH_X;
        int y = this.guiTop + SEARCH_Y;
        return mouseX >= x && mouseX < x + SEARCH_W && mouseY >= y && mouseY < y + SEARCH_H;
    }

    private boolean isPointInFoldButton(int mouseX, int mouseY) {
        int x = this.guiLeft + FOLD_BUTTON_X;
        int y = this.guiTop + FOLD_BUTTON_Y;
        return mouseX >= x && mouseX < x + FOLD_BUTTON_W && mouseY >= y && mouseY < y + FOLD_BUTTON_H;
    }

    private boolean isPointInButton(int mouseX, int mouseY) {
        int x = this.guiLeft + BUTTON_X;
        int y = this.guiTop + BUTTON_Y;
        return mouseX >= x && mouseX < x + BUTTON_W && mouseY >= y && mouseY < y + BUTTON_H;
    }

    private boolean isPointInAutoButton(int mouseX, int mouseY) {
        int x = this.guiLeft + AUTO_BUTTON_X;
        int y = this.guiTop + AUTO_BUTTON_Y;
        return mouseX >= x && mouseX < x + AUTO_BUTTON_W && mouseY >= y && mouseY < y + AUTO_BUTTON_H;
    }

    private boolean isPointInRefillButton(int mouseX, int mouseY) {
        int x = this.guiLeft + REFILL_BUTTON_X;
        int y = this.guiTop + REFILL_BUTTON_Y;
        return mouseX >= x && mouseX < x + REFILL_BUTTON_W && mouseY >= y && mouseY < y + REFILL_BUTTON_H;
    }

    /** 这一点落在产物格（16x16 的物品区）上吗。 */
    private boolean isPointInResultSlot(int mouseX, int mouseY) {
        int x = this.guiLeft + GuiLayout.OUTPUT_ITEM_X;
        int y = this.guiTop + GuiLayout.OUTPUT_ITEM_Y;
        return mouseX >= x && mouseX < x + 16 && mouseY >= y && mouseY < y + 16;
    }

    /** 画一个按钮的悬停/按下高亮和居中文字（底色已经在贴图里）。 */
    private void drawButton(int x, int y, int w, int h, boolean hovered, boolean down, String text) {
        int px = this.guiLeft + x;
        int py = this.guiTop + y;
        if (hovered) {
            Gui.drawRect(px + 1, py + 1, px + w - 1, py + h - 1, down ? BUTTON_PRESSED : BUTTON_HOVER);
        }
        int textY = py + (h - this.fontRenderer.FONT_HEIGHT) / 2 + (down ? 1 : 0);
        this.fontRenderer.drawString(text,
                px + w / 2 - this.fontRenderer.getStringWidth(text) / 2, textY,
                down ? TEXT_PRESSED : TEXT_MAIN, false);
    }

    private void drawOutline(int x, int y, int width, int height, int color) {
        Gui.drawRect(x, y, x + width, y + 1, color);
        Gui.drawRect(x, y + height - 1, x + width, y + height, color);
        Gui.drawRect(x, y, x + 1, y + height, color);
        Gui.drawRect(x + width - 1, y, x + width, y + height, color);
    }

    private boolean isInsidePanel(int mouseX, int mouseY) {
        return mouseX >= this.guiLeft + PANEL_X && mouseX < this.guiLeft + PANEL_X + PANEL_W
                && mouseY >= this.guiTop + PANEL_Y && mouseY < this.guiTop + PANEL_Y + PANEL_H;
    }

    private boolean isPointInScrollBar(int mouseX, int mouseY) {
        int x = this.guiLeft + SCROLL_X - 2;
        int y = this.guiTop + LIST_Y - 1;
        int height = LIST_ROWS * CELL;
        return mouseX >= x && mouseX < x + SCROLL_W + 4 && mouseY >= y && mouseY < y + height;
    }

    /** 根据鼠标在滚动轨道上的位置换算列表行偏移。 */
    private void updateScrollFromMouse(int mouseY) {
        int max = maxScroll();
        if (max <= 0) {
            this.scroll = 0;
            return;
        }
        int trackTop = this.guiTop + LIST_Y - 1;
        int trackHeight = LIST_ROWS * CELL;
        int thumbHeight = Math.max(10, (trackHeight - 2) * LIST_ROWS / (max + LIST_ROWS));
        int movable = Math.max(1, trackHeight - 2 - thumbHeight);
        double thumbCenter = mouseY - trackTop - thumbHeight / 2.0D;
        this.scroll = MathHelper.clamp((int) Math.round((thumbCenter - 1) * max / movable), 0, max);
    }

    /** 客户端翻译（带回退）。 */
    private static String tr(String key, String fallback) {
        return I18n.hasKey(key) ? I18n.format(key) : fallback;
    }

    /**
     * 带参数的客户端翻译：语言文件里的 %s 用 args 依次填充。
     * 语言文件缺键时，用开发者回退文本 + 相同参数格式化，保证两边占位一致。
     */
    private static String trf(String key, String fallback, Object... args) {
        return I18n.hasKey(key) ? I18n.format(key, args) : String.format(fallback, args);
    }

    private static net.minecraft.util.text.ITextComponent text(String value) {
        return new net.minecraft.util.text.TextComponentString(value);
    }
}
