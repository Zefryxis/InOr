package com.example.inventoryorganizer.config;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.List;

public class StorageConfigScreen extends Screen {

    private final Screen parent;
    private final OrganizerConfig config;

    private int activePreset = 0;

    // Working copy of the active preset's slot rules
    private String[] slotRules = new String[54];

    private static final int SLOT_W = 28;
    private static final int SLOT_H = 28;

    // Layout
    private int gridX, gridY;
    private int paletteX, paletteY, paletteW, paletteH;

    // Selected slot (for tier assignment)
    private int selectedSlot = -1;

    // Palette
    private final List<PaletteEntry> allEntries = new ArrayList<>();
    private List<PaletteEntry> filteredEntries = new ArrayList<>();
    private final List<ButtonWidget> paletteButtons = new ArrayList<>();
    private int paletteScroll = 0;
    private int maxPaletteScroll = 0;
    private String selectedText = null;
    private String selectedLabel = null;

    // Widgets
    private TextFieldWidget searchField;
    private TextFieldWidget tierField;
    private TextFieldWidget nameField;
    private String lastSearch = "";

    // Tab buttons (3 preset tabs)
    private ButtonWidget[] tabButtons = new ButtonWidget[3];

    private static class PaletteEntry {
        final String label;
        final String ruleText;
        final boolean isHeader;
        PaletteEntry(String label, String ruleText, boolean isHeader) {
            this.label = label; this.ruleText = ruleText; this.isHeader = isHeader;
        }
    }

    public StorageConfigScreen(Screen parent) {
        super(Text.literal("Storage Config"));
        this.parent = parent;
        this.config = OrganizerConfig.get();
        buildAllEntries();
        filteredEntries = new ArrayList<>(allEntries);
    }

    public StorageConfigScreen(Screen parent, int initialPreset) {
        this(parent);
        this.activePreset = Math.max(0, Math.min(2, initialPreset));
    }

    private StoragePreset getPreset() {
        return config.getStoragePresets().get(activePreset);
    }

    private void loadPreset() {
        StoragePreset p = getPreset();
        int size = p.getSize();
        for (int i = 0; i < 54; i++) {
            slotRules[i] = i < size ? p.getSlotRule(i) : "any";
        }
    }

    private void savePreset() {
        StoragePreset p = getPreset();
        if (nameField != null && !nameField.getText().trim().isEmpty()) {
            p.setName(nameField.getText().trim());
        }
        int size = p.getSize();
        for (int i = 0; i < size; i++) {
            p.setSlotRule(i, slotRules[i]);
        }
        config.save();
    }

    @Override
    protected void init() {
        loadPreset();
        selectedSlot = -1;
        paletteScroll = 0;

        int screenW = this.width;
        int screenH = this.height;

        gridX = 14;
        gridY = 54;
        paletteW = Math.max(140, Math.min(180, screenW / 5));
        paletteX = screenW - paletteW - 10;
        paletteY = gridY;
        paletteH = screenH - paletteY - 44;

        // --- Tab buttons: [Preset 1] [Preset 2] [Preset 3] ---
        int tabW = 68, tabH = 13;
        int tabStartX = gridX;
        for (int i = 0; i < 3; i++) {
            final int idx = i;
            String tabLabel = config.getStoragePresets().get(i).getName();
            tabButtons[i] = addDrawableChild(StyledButton.styledBuilder(
                Text.literal(tabLabel),
                btn -> {
                    savePreset();
                    activePreset = idx;
                    reinitScreen();
                }
            ).dimensions(tabStartX + i * (tabW + 4), 16, tabW, 13).build());
        }

        // --- Name field ---
        int nameY = 32;
        nameField = addDrawableChild(new TextFieldWidget(textRenderer, gridX, nameY, 110, 11, Text.literal("Name")));
        nameField.setText(getPreset().getName());
        nameField.setMaxLength(32);

        // --- Size buttons [27] [54] ---
        int szX = gridX + 114;
        addDrawableChild(StyledButton.styledBuilder(Text.literal("27 slots"),
            btn -> { getPreset().setSize(27); savePreset(); reinitScreen(); }
        ).dimensions(szX, nameY, 44, 11).build());
        addDrawableChild(StyledButton.styledBuilder(Text.literal("54 slots"),
            btn -> { getPreset().setSize(54); savePreset(); reinitScreen(); }
        ).dimensions(szX + 48, nameY, 44, 11).build());

        // --- Search field ---
        int searchX = paletteX;
        int searchY = paletteY - 14;
        searchField = addDrawableChild(new TextFieldWidget(textRenderer, searchX, searchY, paletteW, 12, Text.literal("Search")));
        searchField.setMaxLength(64);
        searchField.setPlaceholder(Text.literal("Search..."));

        // --- Tier widgets (below grid) ---
        int rows = getPreset().getSize() / 9;
        int tierY = gridY + rows * SLOT_H + 6;
        tierField = addDrawableChild(new TextFieldWidget(textRenderer, gridX + 60, tierY, 30, 11, Text.literal("Tier")));
        tierField.setMaxLength(3);

        addDrawableChild(StyledButton.styledBuilder(Text.literal("Set"), btn -> applyTier())
            .dimensions(gridX + 94, tierY, 26, 11).build());
        addDrawableChild(StyledButton.styledBuilder(Text.literal("X"), btn -> clearTier())
            .dimensions(gridX + 122, tierY, 16, 11).build());

        // --- Save / Back ---
        int btnY = screenH - 20;
        addDrawableChild(StyledButton.styledBuilder(Text.literal("Save"), btn -> { savePreset(); MinecraftClient.getInstance().setScreen(parent); })
            .dimensions(gridX, btnY, 50, 14).build());
        addDrawableChild(StyledButton.styledBuilder(Text.literal("Back"), btn -> MinecraftClient.getInstance().setScreen(parent))
            .dimensions(gridX + 54, btnY, 50, 14).build());

        // --- Guide toggle button ---
        addDrawableChild(StyledButton.styledBuilder(Text.literal("?"), btn -> {
            config.setShowHelp(!config.isShowHelp()); config.save();
        }).dimensions(screenW - 24, 4, 20, 18).build());

        // --- Palette buttons ---
        rebuildPaletteButtons();
    }

    private void reinitScreen() {
        clearChildren();
        paletteButtons.clear();
        init();
    }

    // ---- Palette ----

    private void buildAllEntries() {
        allEntries.clear();
        allEntries.add(new PaletteEntry("Nothing (empty)", "empty", false));
        allEntries.add(new PaletteEntry("Any (auto-sort)", "any", false));
        allEntries.add(new PaletteEntry("--- Groups ---", "", true));
        allEntries.add(new PaletteEntry("[G] Weapons",   "g:weapons",   false));
        allEntries.add(new PaletteEntry("[G] Tools",     "g:tools",     false));
        allEntries.add(new PaletteEntry("[G] Armor",     "g:armor",     false));
        allEntries.add(new PaletteEntry("[G] Blocks",    "g:blocks",    false));
        allEntries.add(new PaletteEntry("[G] Food",      "g:food",      false));
        allEntries.add(new PaletteEntry("[G] Utility",   "g:utility",   false));
        allEntries.add(new PaletteEntry("[G] Valuables", "g:valuables", false));
        allEntries.add(new PaletteEntry("[G] Potions",   "g:potions",   false));
        allEntries.add(new PaletteEntry("[G] Misc",      "g:misc",      false));
        allEntries.add(new PaletteEntry("--- Sub-Groups ---", "", true));
        allEntries.add(new PaletteEntry("[G] Logs",        "g:logs",     false));
        allEntries.add(new PaletteEntry("[G] Boats",       "g:boats",    false));
        allEntries.add(new PaletteEntry("[G] Plants",      "g:plants",   false));
        allEntries.add(new PaletteEntry("[G] Stone",       "g:stone",    false));
        allEntries.add(new PaletteEntry("[G] Ores",        "g:ores",     false));
        allEntries.add(new PaletteEntry("[G] Cooked Food", "g:cooked",   false));
        allEntries.add(new PaletteEntry("[G] Raw Food",    "g:rawfood",  false));
        allEntries.add(new PaletteEntry("[G] Nether",      "g:nether",   false));
        allEntries.add(new PaletteEntry("[G] End",         "g:end",      false));
        allEntries.add(new PaletteEntry("[G] Partial Blk", "g:partial",  false));
        allEntries.add(new PaletteEntry("[G] Redstone",    "g:redstone", false));
        allEntries.add(new PaletteEntry("[G] Creative",    "g:creative", false));
        allEntries.add(new PaletteEntry("--- Item Types ---", "", true));
        allEntries.add(new PaletteEntry("[T] Sword",       "t:sword",         false));
        allEntries.add(new PaletteEntry("[T] Pickaxe",     "t:pickaxe",       false));
        allEntries.add(new PaletteEntry("[T] Axe",         "t:axe",           false));
        allEntries.add(new PaletteEntry("[T] Shovel",      "t:shovel",        false));
        allEntries.add(new PaletteEntry("[T] Hoe",         "t:hoe",           false));
        allEntries.add(new PaletteEntry("[T] Bow",         "t:bow",           false));
        allEntries.add(new PaletteEntry("[T] Crossbow",    "t:crossbow",      false));
        allEntries.add(new PaletteEntry("[T] Trident",     "t:trident",       false));
        allEntries.add(new PaletteEntry("[T] Helmet",      "t:helmet",        false));
        allEntries.add(new PaletteEntry("[T] Chestplate",  "t:chestplate",    false));
        allEntries.add(new PaletteEntry("[T] Leggings",    "t:leggings",      false));
        allEntries.add(new PaletteEntry("[T] Boots",       "t:boots",         false));
        allEntries.add(new PaletteEntry("[T] Shield",      "t:shield",        false));
        allEntries.add(new PaletteEntry("[T] Elytra",      "t:elytra",        false));
        allEntries.add(new PaletteEntry("--- All Items ---", "", true));
        try {
            List<PaletteEntry> items = new ArrayList<>();
            for (Identifier id : Registries.ITEM.getIds()) {
                String itemId = id.toString();
                if (itemId.equals("minecraft:air")) continue;
                String name = formatIdAsName(id.getPath());
                items.add(new PaletteEntry(name, itemId, false));
            }
            items.sort((a, b) -> a.label.compareToIgnoreCase(b.label));
            allEntries.addAll(items);
        } catch (Exception ignored) {}
    }

    private static String formatIdAsName(String path) {
        String[] parts = path.split("_");
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (!part.isEmpty()) {
                if (sb.length() > 0) sb.append(" ");
                sb.append(Character.toUpperCase(part.charAt(0)));
                if (part.length() > 1) sb.append(part.substring(1));
            }
        }
        return sb.toString();
    }

    private void applyFilter() {
        String search = searchField != null ? searchField.getText().trim().toLowerCase() : "";
        if (search.equals(lastSearch)) return;
        lastSearch = search;
        if (search.isEmpty()) {
            filteredEntries = new ArrayList<>(allEntries);
        } else {
            filteredEntries = new ArrayList<>();
            for (PaletteEntry e : allEntries) {
                if (!e.isHeader && (e.label.toLowerCase().contains(search) || e.ruleText.toLowerCase().contains(search))) {
                    filteredEntries.add(e);
                }
            }
        }
        paletteScroll = 0;
        maxPaletteScroll = 0;
        rebuildPaletteButtons();
    }

    private void rebuildPaletteButtons() {
        for (ButtonWidget btn : paletteButtons) remove(btn);
        paletteButtons.clear();

        int btnH = 11, gap = 1;
        int visibleCount = (paletteH - 2) / (btnH + gap);
        maxPaletteScroll = Math.max(0, filteredEntries.size() - visibleCount);
        paletteScroll = Math.min(paletteScroll, maxPaletteScroll);

        int by = paletteY + 2;
        for (int i = paletteScroll; i < filteredEntries.size() && by + btnH <= paletteY + paletteH - 2; i++) {
            final PaletteEntry entry = filteredEntries.get(i);
            if (entry.isHeader) { by += btnH + gap; continue; }
            final ButtonWidget btn = StyledButton.styledBuilder(Text.literal(entry.label), b -> {
                if (selectedText != null && selectedText.equals(entry.ruleText)) {
                    selectedText = null; selectedLabel = null;
                } else {
                    selectedText = entry.ruleText; selectedLabel = entry.label;
                }
            }).dimensions(paletteX, by, paletteW, btnH).build();
            addDrawableChild(btn);
            paletteButtons.add(btn);
            by += btnH + gap;
        }
    }

    private void updatePaletteScroll(int delta) {
        int btnH = 11, gap = 1;
        int visibleCount = (paletteH - 2) / (btnH + gap);
        maxPaletteScroll = Math.max(0, filteredEntries.size() - visibleCount);
        paletteScroll = Math.max(0, Math.min(paletteScroll + delta, maxPaletteScroll));
        rebuildPaletteButtons();
    }

    // ---- Tier ----

    private void applyTier() {
        if (selectedSlot < 0 || selectedSlot >= getPreset().getSize()) return;
        if (tierField == null) return;
        String text = tierField.getText().trim();
        try {
            int tier = Integer.parseInt(text);
            if (tier >= 1 && tier <= 54) getPreset().setTier(selectedSlot, tier);
        } catch (NumberFormatException ignored) {}
    }

    private void clearTier() {
        if (selectedSlot >= 0 && selectedSlot < getPreset().getSize()) {
            getPreset().removeTier(selectedSlot);
            if (tierField != null) tierField.setText("");
        }
    }

    // ---- Slot grid ----

    private int getSlotAt(int mx, int my) {
        int cols = 9, rows = getPreset().getSize() / 9;
        for (int row = 0; row < rows; row++) {
            for (int col = 0; col < cols; col++) {
                int sx = gridX + col * SLOT_W;
                int sy = gridY + row * SLOT_H;
                if (mx >= sx && mx < sx + SLOT_W && my >= sy && my < sy + SLOT_H) {
                    return row * 9 + col;
                }
            }
        }
        return -1;
    }

    @Override
    public boolean mouseClicked(Click click, boolean bl) {
        int mx = (int) click.x();
        int my = (int) click.y();
        int button = click.button();

        if (config.isShowHelp()) {
            int gw = 310, gh = 200;
            int gx = width / 2 - gw / 2;
            int gy = height / 2 - gh / 2;
            if (mx < gx || mx > gx + gw || my < gy || my > gy + gh) {
                config.setShowHelp(false); config.save();
            }
            return true;
        }

        int slot = getSlotAt(mx, my);
        if (slot >= 0) {
            if (button == 0) {
                if (selectedText != null) {
                    slotRules[slot] = selectedText;
                } else {
                    selectedSlot = slot;
                    if (tierField != null) {
                        Integer tier = getPreset().getTier(slot);
                        tierField.setText(tier != null ? String.valueOf(tier) : "");
                    }
                }
                return true;
            } else if (button == 1) {
                slotRules[slot] = "any";
                if (selectedSlot == slot) { selectedSlot = -1; }
                return true;
            }
        }
        return super.mouseClicked(click, bl);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (mouseX >= paletteX && mouseX < paletteX + paletteW && mouseY >= paletteY && mouseY < paletteY + paletteH) {
            updatePaletteScroll(verticalAmount > 0 ? -3 : 3);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    // ---- Render ----

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        applyFilter();

        // Background
        context.fill(0, 0, width, height, 0xFF151520);

        // Title
        context.drawCenteredTextWithShadow(textRenderer, Text.literal("\u00a7e\u00a7lStorage Config"), width / 2, 4, 0xFFFFFFFF);

        drawTabHighlights(context);
        drawNameSizeLabels(context);
        drawSlotGrid(context, mouseX, mouseY);
        drawPalettePanel(context, mouseX, mouseY);
        drawTierRow(context);
        drawSelectedInfo(context);

        super.render(context, mouseX, mouseY, delta);

        if (config.isShowHelp()) drawGuideOverlay(context);
    }

    private void drawTabHighlights(DrawContext context) {
        int tabW = 68, tabH = 13;
        int tabStartX = gridX;
        for (int i = 0; i < 3; i++) {
            int tx = tabStartX + i * (tabW + 4);
            if (i == activePreset) {
                context.fill(tx - 1, 15, tx + tabW + 1, 15 + tabH + 2, 0xFF55AAFF);
                context.fill(tx, 16, tx + tabW, 16 + tabH, 0xFF223355);
            }
        }
    }

    private void drawNameSizeLabels(DrawContext context) {
        context.drawTextWithShadow(textRenderer, Text.literal("\u00a77Name:"), gridX - 1, 23, 0xFFAAAAAA);
        int sizeNow = getPreset().getSize();
        int szX = gridX + 114;
        context.drawTextWithShadow(textRenderer, Text.literal("Size: \u00a7e" + sizeNow), szX + 94, 23, 0xFFAAAAAA);
    }

    private void drawSlotGrid(DrawContext context, int mouseX, int mouseY) {
        StoragePreset preset = getPreset();
        int rows = preset.getSize() / 9;
        int cols = 9;

        // Panel behind grid
        drawDecoratedPanel(context, gridX - 4, gridY - 4, cols * SLOT_W + 8, rows * SLOT_H + 8);

        // Row label
        context.drawTextWithShadow(textRenderer, Text.literal("\u00a77Storage Slots (" + preset.getSize() + ")"), gridX, gridY - 11, 0xFFAAAAAA);

        for (int row = 0; row < rows; row++) {
            for (int col = 0; col < cols; col++) {
                int slot = row * cols + col;
                int sx = gridX + col * SLOT_W;
                int sy = gridY + row * SLOT_H;
                drawSlot(context, sx, sy, slot, mouseX, mouseY);
            }
        }
    }

    private void drawSlot(DrawContext context, int x, int y, int slot, int mouseX, int mouseY) {
        String rule = slotRules[slot];
        boolean hovered = mouseX >= x && mouseX < x + SLOT_W && mouseY >= y && mouseY < y + SLOT_H;
        boolean selected = slot == selectedSlot;
        boolean hasRule = !rule.equals("any") && !rule.equals("empty");

        int innerColor;
        if (rule.equals("empty"))      innerColor = 0xFF3D1515;
        else if (rule.equals("any"))   innerColor = 0xFF2A2A2A;
        else                           innerColor = 0xFF1E2B1E;

        drawMCSlot(context, x, y, innerColor, hovered, selected, hasRule);

        // Rule text
        String display = getShortLabel(rule);
        int maxW = SLOT_W - 2;
        if (textRenderer.getWidth(display) > maxW) {
            while (display.length() > 1 && textRenderer.getWidth(display + ".") > maxW)
                display = display.substring(0, display.length() - 1);
            display += ".";
        }
        int tw = textRenderer.getWidth(display);
        context.drawTextWithShadow(textRenderer, Text.literal(display), x + (SLOT_W - tw) / 2, y + 19, 0xFFAAAAAA);

        // Tier badge
        Integer tier = getPreset().getTier(slot);
        if (tier != null) {
            String ts = String.valueOf(tier);
            context.drawTextWithShadow(textRenderer, Text.literal("\u00a7a" + ts), x + 2, y + 2, 0xFF55FF55);
        }

        // Item icon
        ItemStack icon = getIconForRule(rule);
        if (!icon.isEmpty()) context.drawItem(icon, x + (SLOT_W - 16) / 2, y + 1);
    }

    private void drawMCSlot(DrawContext context, int x, int y, int innerColor,
                             boolean hovered, boolean selected, boolean hasRule) {
        context.fill(x, y, x + SLOT_W, y + SLOT_H, 0xFF111111);
        context.fill(x + 1, y + 1, x + SLOT_W - 1, y + 3, 0xFF2A2A2A);
        context.fill(x + 1, y + 1, x + 3, y + SLOT_H - 1, 0xFF2A2A2A);
        context.fill(x + 1, y + SLOT_H - 3, x + SLOT_W - 1, y + SLOT_H - 1, 0xFF6A6A6A);
        context.fill(x + SLOT_W - 3, y + 1, x + SLOT_W - 1, y + SLOT_H - 1, 0xFF6A6A6A);
        context.fill(x + 3, y + 3, x + SLOT_W - 3, y + SLOT_H - 3, innerColor);
        if (hasRule) {
            context.fill(x + 3, y + 3, x + SLOT_W - 3, y + 5, 0xFF55AA55);
        }
        if (selected) {
            context.fill(x + 3, y + 3, x + SLOT_W - 3, y + SLOT_H - 3, 0x4400AAFF);
            context.drawHorizontalLine(x + 2, x + SLOT_W - 3, y + 2, 0xFF00AAFF);
            context.drawHorizontalLine(x + 2, x + SLOT_W - 3, y + SLOT_H - 3, 0xFF00AAFF);
            context.drawVerticalLine(x + 2, y + 2, y + SLOT_H - 3, 0xFF00AAFF);
            context.drawVerticalLine(x + SLOT_W - 3, y + 2, y + SLOT_H - 3, 0xFF00AAFF);
        } else if (hovered) {
            context.fill(x + 3, y + 3, x + SLOT_W - 3, y + SLOT_H - 3, 0x55FFFF00);
            context.drawHorizontalLine(x + 2, x + SLOT_W - 3, y + 2, 0xFFFFFF00);
            context.drawHorizontalLine(x + 2, x + SLOT_W - 3, y + SLOT_H - 3, 0xFFFFFF00);
            context.drawVerticalLine(x + 2, y + 2, y + SLOT_H - 3, 0xFFFFFF00);
            context.drawVerticalLine(x + SLOT_W - 3, y + 2, y + SLOT_H - 3, 0xFFFFFF00);
        }
    }

    private void drawPalettePanel(DrawContext context, int mouseX, int mouseY) {
        drawDecoratedPanel(context, paletteX - 4, paletteY - 18, paletteW + 8, paletteH + 22);
        context.drawTextWithShadow(textRenderer, Text.literal("\u00a7eRules"), paletteX + 2, paletteY - 15, 0xFFFFFF55);

        // Highlight selected entry
        if (selectedText != null) {
            int btnH = 11, gap = 1;
            int by = paletteY + 2;
            for (int i = paletteScroll; i < filteredEntries.size() && by + btnH <= paletteY + paletteH - 2; i++) {
                PaletteEntry entry = filteredEntries.get(i);
                if (entry.isHeader) { by += btnH + gap; continue; }
                if (entry.ruleText.equals(selectedText)) {
                    context.fill(paletteX - 1, by - 1, paletteX + paletteW + 1, by + btnH + 1, 0xFF005588);
                }
                if (entry.isHeader) {
                    context.drawTextWithShadow(textRenderer, Text.literal(entry.label), paletteX + 2, by + 2, 0xFF888888);
                }
                by += btnH + gap;
            }
        }

        // Draw headers directly (buttons skip headers)
        int btnH = 11, gap = 1;
        int by = paletteY + 2;
        for (int i = paletteScroll; i < filteredEntries.size() && by + btnH <= paletteY + paletteH - 2; i++) {
            PaletteEntry entry = filteredEntries.get(i);
            if (entry.isHeader) {
                context.drawTextWithShadow(textRenderer, Text.literal(entry.label), paletteX + 2, by + 2, 0xFF888888);
            }
            by += btnH + gap;
        }

        // Scroll indicators
        if (paletteScroll > 0) {
            context.drawCenteredTextWithShadow(textRenderer, Text.literal("\u25b2"), paletteX + paletteW / 2, paletteY - 2, 0xFFAAAAAA);
        }
        if (paletteScroll < maxPaletteScroll) {
            context.drawCenteredTextWithShadow(textRenderer, Text.literal("\u25bc"), paletteX + paletteW / 2, paletteY + paletteH + 1, 0xFFAAAAAA);
        }

        // Selected palette indicator
        if (selectedLabel != null) {
            context.drawTextWithShadow(textRenderer, Text.literal("\u00a7eSelected: \u00a7f" + selectedLabel),
                paletteX, paletteY + paletteH + 12, 0xFFFFFFFF);
        }
    }

    private void drawTierRow(DrawContext context) {
        if (selectedSlot < 0 || selectedSlot >= getPreset().getSize()) return;
        int rows = getPreset().getSize() / 9;
        int tierY = gridY + rows * SLOT_H + 6;
        context.drawTextWithShadow(textRenderer,
            Text.literal("\u00a77Slot \u00a7e" + selectedSlot + " \u00a77Tier:"),
            gridX, tierY + 1, 0xFFFFFFFF);
    }

    private void drawSelectedInfo(DrawContext context) {
        // Active preset indicator
        context.drawTextWithShadow(textRenderer,
            Text.literal("\u00a77Active: \u00a7e" + getPreset().getName() + " \u00a77(" + getPreset().getSize() + " slots)"),
            paletteX, height - 18, 0xFFFFFFFF);
    }

    // ---- Decorated panel (same style as VisualInventoryConfigScreen) ----

    private void drawDecoratedPanel(DrawContext context, int x, int y, int w, int h) {
        context.fill(x, y, x + w, y + h, 0xFF1A1A2E);
        context.fill(x + 1, y + 1, x + w - 1, y + h - 1, 0xFF16213E);
        context.fill(x + 1, y, x + w - 1, y + 1, 0xFF444466);
        context.fill(x + 1, y + h - 1, x + w - 1, y + h, 0xFF444466);
        context.fill(x, y + 1, x + 1, y + h - 1, 0xFF444466);
        context.fill(x + w - 1, y + 1, x + w, y + h - 1, 0xFF444466);
    }

    // ---- Guide overlay ----

    private void drawGuideOverlay(DrawContext context) {
        int gw = 310, gh = 200;
        int gx = width / 2 - gw / 2;
        int gy = height / 2 - gh / 2;

        context.fill(0, 0, width, height, 0x88000000);
        drawDecoratedPanel(context, gx, gy, gw, gh);

        context.fill(gx + 4, gy + 4, gx + gw - 4, gy + 20, 0xFF1A1A2E);
        context.drawCenteredTextWithShadow(textRenderer,
            Text.literal("\u00a7e\u00a7lStorage Config Guide"), width / 2, gy + 8, 0xFFFFFF55);

        int lx = gx + 12, ly = gy + 26, lh = 13;
        context.drawTextWithShadow(textRenderer, Text.literal("\u00a7e[1] \u00a7fSelect a preset tab (1/2/3) \u00a77\u2013 each is a separate layout"), lx, ly, 0xFFFFFFFF); ly += lh;
        context.drawTextWithShadow(textRenderer, Text.literal("\u00a7e[2] \u00a7fSet preset name and size \u00a77(27=single, 54=double chest)"), lx, ly, 0xFFFFFFFF); ly += lh;
        context.drawTextWithShadow(textRenderer, Text.literal("\u00a7e[3] \u00a7fSelect a rule (right panel), then left-click a slot"), lx, ly, 0xFFFFFFFF); ly += lh;
        context.drawTextWithShadow(textRenderer, Text.literal("\u00a7e[4] \u00a7fRight-click a slot to reset it to \u00a77'any' \u00a7f(accepts all)"), lx, ly, 0xFFFFFFFF); ly += lh;
        context.drawTextWithShadow(textRenderer, Text.literal("\u00a7e[5] \u00a7fClick slot (no rule), type a tier number, press Set"), lx, ly, 0xFFFFFFFF); ly += lh;
        context.drawTextWithShadow(textRenderer, Text.literal("\u00a7e[6] \u00a7fOST button in-game applies the preset matching chest name"), lx, ly, 0xFFFFFFFF); ly += lh;
        context.drawTextWithShadow(textRenderer, Text.literal("\u00a77       Preset name must match the chest's custom name exactly"), lx, ly, 0xFFAAAAAA); ly += lh + 4;

        context.fill(gx + 12, ly, gx + gw - 12, ly + 1, 0xFF444444); ly += 6;
        context.drawCenteredTextWithShadow(textRenderer,
            Text.literal("\u00a77Click outside or press \u00a7e[?]\u00a77 to close this guide"), width / 2, ly, 0xFF888888);
    }

    // ---- Helpers ----

    private String getShortLabel(String rule) {
        if (rule.equals("any"))   return "any";
        if (rule.equals("empty")) return "empty";
        if (rule.startsWith("g:")) return "[G]" + rule.substring(2);
        if (rule.startsWith("t:")) return "[T]" + rule.substring(2);
        if (rule.contains(":"))   return rule.substring(rule.indexOf(':') + 1);
        return rule;
    }

    private ItemStack getIconForRule(String rule) {
        if (rule.equals("empty")) return new ItemStack(Items.BARRIER);
        if (rule.equals("any"))   return ItemStack.EMPTY;
        if (rule.startsWith("g:")) {
            switch (rule.substring(2)) {
                case "weapons":   return new ItemStack(Items.IRON_SWORD);
                case "tools":     return new ItemStack(Items.IRON_PICKAXE);
                case "armor":     return new ItemStack(Items.IRON_CHESTPLATE);
                case "blocks":    return new ItemStack(Items.STONE);
                case "food":      return new ItemStack(Items.BREAD);
                case "valuables": return new ItemStack(Items.DIAMOND);
                case "potions":   return new ItemStack(Items.POTION);
                case "utility":   return new ItemStack(Items.TORCH);
                default:          return ItemStack.EMPTY;
            }
        }
        if (rule.startsWith("t:")) {
            switch (rule.substring(2)) {
                case "sword":      return new ItemStack(Items.IRON_SWORD);
                case "pickaxe":    return new ItemStack(Items.IRON_PICKAXE);
                case "axe":        return new ItemStack(Items.IRON_AXE);
                case "shovel":     return new ItemStack(Items.IRON_SHOVEL);
                case "hoe":        return new ItemStack(Items.IRON_HOE);
                case "bow":        return new ItemStack(Items.BOW);
                case "crossbow":   return new ItemStack(Items.CROSSBOW);
                case "trident":    return new ItemStack(Items.TRIDENT);
                case "helmet":     return new ItemStack(Items.IRON_HELMET);
                case "chestplate": return new ItemStack(Items.IRON_CHESTPLATE);
                case "leggings":   return new ItemStack(Items.IRON_LEGGINGS);
                case "boots":      return new ItemStack(Items.IRON_BOOTS);
                case "shield":     return new ItemStack(Items.SHIELD);
                case "elytra":     return new ItemStack(Items.ELYTRA);
                default:           return ItemStack.EMPTY;
            }
        }
        // Specific item
        try {
            Identifier id = Identifier.of(rule);
            Item item = Registries.ITEM.get(id);
            if (item != Items.AIR) return new ItemStack(item);
        } catch (Exception ignored) {}
        return ItemStack.EMPTY;
    }

    @Override
    public void close() {
        MinecraftClient.getInstance().setScreen(parent);
    }
}
