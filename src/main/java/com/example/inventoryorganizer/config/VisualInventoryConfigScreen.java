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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

public class VisualInventoryConfigScreen extends Screen {

    private static final Logger LOGGER = LoggerFactory.getLogger("inventory-organizer/InvCfg");

    private final Screen parent;
    private final OrganizerConfig config;

    // --- Text-based slot rules ---
    // Each slot stores a text string (e.g. "any", "empty", "g:blocks", "t:sword", "minecraft:diamond_sword")
    // This array is the SINGLE source of truth, synced to/from OrganizerConfig on load/save
    private final String[] slotTexts = new String[36];

    // Equipment slot texts: [0]=head, [1]=chest, [2]=legs, [3]=feet, [4]=offhand
    private final String[] equipTexts = new String[5];
    private static final String[] EQUIP_KEYS = {
        OrganizerConfig.SLOT_ARMOR_HEAD, OrganizerConfig.SLOT_ARMOR_CHEST,
        OrganizerConfig.SLOT_ARMOR_LEGS, OrganizerConfig.SLOT_ARMOR_FEET,
        OrganizerConfig.SLOT_OFFHAND
    };
    private static final String[] EQUIP_LABELS = { "Helmet", "Chest", "Legs", "Boots", "Offhand" };
    // What item types each armor slot accepts (for validation). Offhand (index 4) accepts anything.
    private static final String[][] EQUIP_ALLOWED_TYPES = {
        { "helmet", "turtle_helmet" },  // head
        { "chestplate", "elytra" },      // chest
        { "leggings" },                   // legs
        { "boots" },                       // feet
        null                               // offhand = anything
    };

    // Inventory grid
    private int gridX, gridY;
    private int SLOT_W = 36; // dynamic - set in init() based on screen size
    private int SLOT_H = 36;

    // Palette
    private int paletteX, paletteY, paletteW, paletteH;
    private int paletteScroll = 0;
    private int maxPaletteScroll = 0;
    private final List<ButtonWidget> paletteButtons = new ArrayList<>();

    // Search
    private TextFieldWidget searchField;
    private String lastSearch = "";

    // Selected palette entry (text to paint onto slots)
    private String selectedText = null;
    private String selectedLabel = null;

    // Double-click detection
    private long lastClickTime = 0;
    private int lastClickedSlot = -1;

    // Tier Order warning
    private ButtonWidget solveButton;

    // --- Storage mode ---
    private boolean showStorage = false;
    private int activeStoragePreset = 0;
    private final String[] storageSlotRules = new String[54];
    private int storageSelectedSlot = -1;
    private ButtonWidget[] storageTabBtns = new ButtonWidget[4];

    // All palette entries (unfiltered) and filtered list
    private final List<PaletteEntry> allEntries = new ArrayList<>();
    private List<PaletteEntry> filteredEntries = new ArrayList<>();

    private static class PaletteEntry {
        final String label;       // Display name shown in palette
        final String ruleText;    // The text string to assign to a slot
        final boolean isHeader;   // True for section headers (not clickable)

        PaletteEntry(String label, String ruleText, boolean isHeader) {
            this.label = label;
            this.ruleText = ruleText;
            this.isHeader = isHeader;
        }
    }

    public VisualInventoryConfigScreen(Screen parent) {
        super(Text.literal("Inventory Config"));
        this.parent = parent;
        this.config = OrganizerConfig.get();
        // Load text from config
        for (int i = 0; i < 36; i++) {
            slotTexts[i] = config.getSlotRule(i).toText();
        }
        // Load equipment slot texts
        for (int i = 0; i < 5; i++) {
            equipTexts[i] = config.getSlotRuleByKey(EQUIP_KEYS[i]).toText();
        }
        buildAllEntries();
        filteredEntries = new ArrayList<>(allEntries);
    }

    private void buildAllEntries() {
        allEntries.clear();

        // Special entries
        allEntries.add(new PaletteEntry("Nothing (empty)", "empty", false));
        allEntries.add(new PaletteEntry("Any (auto-sort)", "any", false));

        // Custom Groups (user-defined)
        List<String> cgNames = config.getCustomGroupNames();
        if (!cgNames.isEmpty()) {
            allEntries.add(new PaletteEntry("--- Custom Groups ---", "", true));
            for (String cgName : cgNames) {
                allEntries.add(new PaletteEntry("[CG] " + cgName, "cg:" + cgName, false));
            }
        }

        // Groups
        allEntries.add(new PaletteEntry("--- Groups ---", "", true));
        allEntries.add(new PaletteEntry("[G] Weapons", "g:weapons", false));
        allEntries.add(new PaletteEntry("[G] Tools", "g:tools", false));
        allEntries.add(new PaletteEntry("[G] Armor", "g:armor", false));
        allEntries.add(new PaletteEntry("[G] Blocks", "g:blocks", false));
        allEntries.add(new PaletteEntry("[G] Food", "g:food", false));
        allEntries.add(new PaletteEntry("[G] Utility", "g:utility", false));
        allEntries.add(new PaletteEntry("[G] Valuables", "g:valuables", false));
        allEntries.add(new PaletteEntry("[G] Potions", "g:potions", false));
        allEntries.add(new PaletteEntry("[G] Splash Potions", "g:splash_potions", false));
        allEntries.add(new PaletteEntry("[G] Arrows", "g:arrows", false));
        allEntries.add(new PaletteEntry("[G] Misc", "g:misc", false));

        // Sub-groups
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

        // Item types
        allEntries.add(new PaletteEntry("--- Item Types ---", "", true));
        allEntries.add(new PaletteEntry("[T] Sword", "t:sword", false));
        allEntries.add(new PaletteEntry("[T] Pickaxe", "t:pickaxe", false));
        allEntries.add(new PaletteEntry("[T] Axe", "t:axe", false));
        allEntries.add(new PaletteEntry("[T] Shovel", "t:shovel", false));
        allEntries.add(new PaletteEntry("[T] Hoe", "t:hoe", false));
        allEntries.add(new PaletteEntry("[T] Bow", "t:bow", false));
        allEntries.add(new PaletteEntry("[T] Crossbow", "t:crossbow", false));
        allEntries.add(new PaletteEntry("[T] Trident", "t:trident", false));
        allEntries.add(new PaletteEntry("[T] Mace", "t:mace", false));
        allEntries.add(new PaletteEntry("[T] Helmet", "t:helmet", false));
        allEntries.add(new PaletteEntry("[T] Chestplate", "t:chestplate", false));
        allEntries.add(new PaletteEntry("[T] Leggings", "t:leggings", false));
        allEntries.add(new PaletteEntry("[T] Boots", "t:boots", false));
        allEntries.add(new PaletteEntry("[T] Shield", "t:shield", false));
        allEntries.add(new PaletteEntry("[T] Elytra", "t:elytra", false));
        allEntries.add(new PaletteEntry("[T] Fishing Rod", "t:fishing_rod", false));
        allEntries.add(new PaletteEntry("[T] Shears", "t:shears", false));
        allEntries.add(new PaletteEntry("[T] Flint & Steel", "t:flint_and_steel", false));
        allEntries.add(new PaletteEntry("[T] Potion (drink)",    "t:potion",           false));
        allEntries.add(new PaletteEntry("[T] Splash Potion",    "t:splash_potion",    false));
        allEntries.add(new PaletteEntry("[T] Lingering Potion", "t:lingering_potion", false));
        allEntries.add(new PaletteEntry("[T] Arrow",            "t:arrow",            false));
        allEntries.add(new PaletteEntry("[T] Spectral Arrow",   "t:spectral_arrow",   false));
        allEntries.add(new PaletteEntry("[T] Tipped Arrow",     "t:tipped_arrow",     false));

        // All items from registry
        allEntries.add(new PaletteEntry("--- All Items ---", "", true));
        try {
            List<PaletteEntry> itemEntries = new ArrayList<>();
            for (Identifier id : Registries.ITEM.getIds()) {
                String itemId = id.toString();
                if (itemId.equals("minecraft:air")) continue;
                String name = formatIdAsName(id.getPath());
                itemEntries.add(new PaletteEntry(name, itemId, false));
            }
            itemEntries.sort((a, b) -> a.label.compareToIgnoreCase(b.label));
            allEntries.addAll(itemEntries);
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

    // --- Save slotTexts back to config ---
    private void saveToConfig() {
        for (int i = 0; i < 36; i++) {
            config.setSlotRule(i, SlotRule.fromText(slotTexts[i]));
        }
        // Save equipment slots
        for (int i = 0; i < 5; i++) {
            config.setSlotRuleByKey(EQUIP_KEYS[i], SlotRule.fromText(equipTexts[i]));
        }
        config.save();
        LOGGER.info("[InvOrganizer] Config saved. Slot texts:");
        for (int i = 0; i < 36; i++) {
            if (!slotTexts[i].equals("any")) {
                LOGGER.info("[InvOrganizer]   slot " + i + " = " + slotTexts[i]);
            }
        }
        for (int i = 0; i < 5; i++) {
            if (!equipTexts[i].equals("any")) {
                LOGGER.info("[InvOrganizer]   equip " + EQUIP_KEYS[i] + " = " + equipTexts[i]);
            }
        }
    }

    private void applyFilter() {
        String search = searchField != null ? searchField.getText().trim().toLowerCase() : "";
        if (search.equals(lastSearch)) return;
        lastSearch = search;
        paletteScroll = 0;

        if (search.isEmpty()) {
            filteredEntries = new ArrayList<>();
            for (PaletteEntry entry : allEntries) {
                // In Storage mode, hide Bundle Content entries (b: prefix) – avoids confusing duplication
                if (showStorage && entry.ruleText.startsWith("b:")) continue;
                filteredEntries.add(entry);
            }
        } else {
            filteredEntries = new ArrayList<>();
            for (PaletteEntry entry : allEntries) {
                if (entry.isHeader) continue;
                if (showStorage && entry.ruleText.startsWith("b:")) continue;
                if (entry.label.toLowerCase().contains(search) ||
                        entry.ruleText.toLowerCase().contains(search)) {
                    filteredEntries.add(entry);
                }
            }
        }
        updatePaletteScroll();
        rebuildPaletteButtons();
    }

    private void updatePaletteScroll() {
        int visibleRows = paletteH / 22; // Match rowHeight in rebuildPaletteButtons
        maxPaletteScroll = Math.max(0, filteredEntries.size() - visibleRows);
        paletteScroll = Math.min(paletteScroll, maxPaletteScroll);
    }

    @Override
    protected void init() {
        super.init();

        // Dynamic slot size: scale down if screen is too narrow
        // Minimum needed: armor(1 slot) + gap + 9 grid slots + gap + minPalette(120) + margin
        int minPalette = 120;
        int margins = 40;
        int availableForGrid = width - minPalette - margins;
        int neededSlots = 10; // 1 armor + 9 grid
        int dynSlot = availableForGrid / neededSlots;
        SLOT_W = Math.max(20, Math.min(36, dynSlot));
        SLOT_H = SLOT_W;

        // Responsive layout: calculate based on screen width/height
        int margin = Math.max(4, width / 70);
        gridX = margin + SLOT_W + 8;
        gridY = height / 8;                            // top margin scales with height

        // Palette: fills remaining space between grid and right edge
        int rightEdgeMargin = Math.max(10, width / 50);
        paletteX = gridX + 9 * SLOT_W + Math.max(10, width / 60);
        paletteW = width - paletteX - rightEdgeMargin;
        paletteW = Math.max(140, Math.min(260, paletteW)); // clamp 140-260px
        paletteX = width - paletteW - rightEdgeMargin;    // anchor to right edge
        paletteY = gridY + 4;
        paletteH = height - paletteY - 44;

        // Search field above palette
        searchField = new TextFieldWidget(textRenderer, paletteX, paletteY - 16, paletteW, 14, Text.literal("Search..."));
        searchField.setMaxLength(50);
        searchField.setPlaceholder(Text.literal("Search items..."));
        searchField.setChangedListener(text -> applyFilter());
        addDrawableChild(searchField);

        buildAllEntries();
        lastSearch = "\uFFFF"; // force applyFilter to rebuild filteredEntries
        applyFilter();
        updatePaletteScroll();
        rebuildPaletteButtons();

        // Bottom buttons
        int btnY = height - 28;
        int btnW = 55;
        int totalW = btnW * 6 + 5 * 3;
        int startX = width / 2 - totalW / 2;

        addDrawableChild(StyledButton.styledBuilder(Text.literal("Save"), btn -> {
            saveToConfig();
            MinecraftClient.getInstance().setScreen(parent);
        }).dimensions(startX, btnY, btnW, 20).build());

        addDrawableChild(StyledButton.styledBuilder(Text.literal("Clear All"), btn -> {
            for (int i = 0; i < 36; i++) slotTexts[i] = "any";
            saveToConfig();
        }).dimensions(startX + btnW + 3, btnY, btnW, 20).build());

        addDrawableChild(StyledButton.styledBuilder(Text.literal("Kits"), btn -> {
            saveToConfig();
            MinecraftClient.getInstance().setScreen(new KitsScreen(parent));
        }).dimensions(startX + (btnW + 3) * 2, btnY, btnW, 20).build());

        addDrawableChild(StyledButton.styledBuilder(Text.literal("Names"), btn -> {
            saveToConfig();
            MinecraftClient.getInstance().setScreen(ConfigScreenBuilder.build((Screen)(Object)this));
        }).dimensions(startX + (btnW + 3) * 3, btnY, btnW, 20).build());

        addDrawableChild(StyledButton.styledBuilder(Text.literal("Tier Order"), btn -> {
            saveToConfig();
            if (showStorage) {
                String tierKey = "tier_order_storage_" + activeStoragePreset;
                int sRows = config.getStoragePresets().get(activeStoragePreset).getSize() / 9;
                String[] storageRulesCopy = java.util.Arrays.copyOf(storageSlotRules, sRows * 9);
                MinecraftClient.getInstance().setScreen(
                    new SortingOrderConfigScreen(this, storageRulesCopy, new String[]{"any","any","any","any","any"}, tierKey, true, sRows));
            } else {
                MinecraftClient.getInstance().setScreen(new SortingOrderConfigScreen(this, slotTexts, equipTexts));
            }
        }).dimensions(startX + (btnW + 3) * 4, btnY, btnW, 20).build());

        addDrawableChild(StyledButton.styledBuilder(Text.literal("Back"), btn -> {
            MinecraftClient.getInstance().setScreen(parent);
        }).dimensions(startX + (btnW + 3) * 5, btnY, btnW, 20).build());

        // Tier Order warning + Solve button (shown when rules are set but tier order is not configured)
        solveButton = StyledButton.styledBuilder(Text.literal("Solve"), btn -> {
            config.getPreferences().remove("tier_order");
            config.applyDefaultTierOrder();
            config.save();
        }).dimensions(startX, btnY - 18, 55, 14).build();
        solveButton.visible = false;
        addDrawableChild(solveButton);

        // Guide toggle button (top-right corner)
        addDrawableChild(StyledButton.styledBuilder(Text.literal("?"), btn -> {
            config.setShowHelp(!config.isShowHelp());
            config.save();
        }).dimensions(width - 24, 4, 20, 18).build());

        // --- Mode toggle buttons: [Inv] [Storage] [Groups] at top-left ---
        addDrawableChild(StyledButton.styledBuilder(Text.literal("Inv"),
            btn -> switchMode(false)
        ).dimensions(4, 4, 36, 13).build());
        addDrawableChild(StyledButton.styledBuilder(Text.literal("Storage"),
            btn -> switchMode(true)
        ).dimensions(43, 4, 52, 13).build());
        addDrawableChild(StyledButton.styledBuilder(Text.literal("Groups"),
            btn -> { saveToConfig(); MinecraftClient.getInstance().setScreen(new CustomGroupListScreen(this)); }
        ).dimensions(98, 4, 52, 13).build());

        // --- Storage preset tab buttons (aligned to storage panel, no armor column) ---
        int panelLx = gridX - 8;
        int panelTy = gridY - 20;
        int panelTw = 9 * SLOT_W + 16;
        int nTabs = config.getStoragePresets().size();
        storageTabBtns = new ButtonWidget[nTabs];
        int tabW3 = panelTw / nTabs - 2;
        for (int i = 0; i < nTabs; i++) {
            final int idx = i;
            String tabLabel = config.getStoragePresets().get(i).getName();
            storageTabBtns[i] = addDrawableChild(StyledButton.styledBuilder(
                Text.literal(tabLabel),
                btn -> { saveStoragePreset(); activeStoragePreset = idx; loadStoragePreset(); }
            ).dimensions(panelLx + i * (tabW3 + 2), panelTy - 15, tabW3, 13).build());
        }

        // Load storage data and apply initial visibility
        loadStoragePreset();
        updateStorageWidgetsVisibility();

    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta);

        if (showStorage) {
            // Storage mode: 12px margin sides/bottom, +12px on top for label at gridY-12
            int storageRows = config.getStoragePresets().get(activeStoragePreset).getSize() / 9;
            int panelLeft = gridX - 12;
            int panelTop = gridY - 24;
            int panelW2 = 9 * SLOT_W + 24;
            int panelH2 = storageRows * SLOT_H + 36;
            drawDecoratedPanel(context, panelLeft, panelTop, panelW2, panelH2);
        } else {
            // Inventory mode: full panel including armor column and offhand
            int panelLeft = gridX - SLOT_W - 14;
            int panelTop = gridY - 20;
            int panelW2 = (SLOT_W + 8) + 9 * SLOT_W + 8;
            int panelH2 = 5 * SLOT_H + 36;
            drawDecoratedPanel(context, panelLeft, panelTop, panelW2, panelH2);
        }
        
        // Draw decorated border for palette (no dark background, including search field)
        drawDecoratedBorder(context, paletteX - 8, paletteY - 24, paletteW + 16, paletteH + 32);

        String modeTitle = showStorage
            ? ("\u00a7eStorage: \u00a7f" + config.getStoragePresets().get(activeStoragePreset).getName())
            : "Inventory Slot Config";
        context.drawCenteredTextWithShadow(textRenderer, Text.literal(modeTitle), width / 2, 4, 0xFFFFFFFF);
        // Mode indicator highlights
        if (!showStorage) context.fill(4, 4, 40, 17, 0xFF005588);
        else context.fill(43, 4, 95, 17, 0xFF005588);
        // Storage tab highlight (aligned to storage panel)
        if (showStorage) {
            int panelLx2 = gridX - 8;
            int panelTy2 = gridY - 20;
            int panelTw2 = 9 * SLOT_W + 16;
            int tw2 = panelTw2 / config.getStoragePresets().size() - 2;
            int hx = panelLx2 + activeStoragePreset * (tw2 + 2);
            context.fill(hx - 1, panelTy2 - 16, hx + tw2 + 1, panelTy2, 0xFF55AAFF);
            context.fill(hx, panelTy2 - 15, hx + tw2, panelTy2, 0xFF223355);
        }
        context.drawTextWithShadow(textRenderer, Text.literal("Select a rule from the list, then click a slot to assign it."), width / 2 - 150, 16, 0xFFAAAAAA);

        if (showStorage) {
            drawStorageGrid(context, mouseX, mouseY);
        } else {
            drawInventoryGrid(context, mouseX, mouseY);
        }
        drawPaletteExtras(context, mouseX, mouseY);

        // Guide overlay (drawn last so it's on top of everything)
        if (config.isShowHelp()) drawGuideOverlay(context);

        // Show selected rule as cursor text
        if (selectedLabel != null) {
            context.drawTextWithShadow(textRenderer, Text.literal(selectedLabel),
                    mouseX + 12, mouseY - 4, 0xFF55FF55);
        }

        drawSlotTooltip(context, mouseX, mouseY);

        // Check search field changes every frame
        if (searchField != null) {
            String current = searchField.getText().trim().toLowerCase();
            if (!current.equals(lastSearch)) {
                applyFilter();
            }
        }

        // Tier Order warning: show when rules are configured but no tier assignments are saved
        boolean needsWarning = hasMeaningfulRules() && config.getPreference("tier_order").length == 0;
        if (solveButton != null) solveButton.visible = needsWarning && !showStorage;
        if (needsWarning && !showStorage) {
            int btnW2 = 55;
            int totalW2 = btnW2 * 6 + 5 * 3;
            int warnX = width / 2 - totalW2 / 2;
            int warnY = height - 28 - 18;
            context.drawTextWithShadow(textRenderer,
                Text.literal("\u26a0 Tier Order is not configured \u2013 set it up or click Solve!"),
                warnX + 58, warnY + 3, 0xFFFF8800);
        }
    }

    private boolean hasMeaningfulRules() {
        for (String s : slotTexts) {
            if (s != null && !s.equals("any") && !s.equals("empty")) return true;
        }
        for (String s : equipTexts) {
            if (s != null && !s.equals("any") && !s.equals("empty")) return true;
        }
        return false;
    }

    private void drawStorageGrid(DrawContext context, int mouseX, int mouseY) {
        StoragePreset preset = config.getStoragePresets().get(activeStoragePreset);
        int size = preset.getSize();
        int rows = size / 9;
        context.drawTextWithShadow(textRenderer,
            Text.literal(preset.getName() + " (" + size + " slots)"), gridX, gridY - 12, 0xFFFFFF55);
        for (int row = 0; row < rows; row++) {
            for (int col = 0; col < 9; col++) {
                int slot = row * 9 + col;
                drawStorageSlot(context, gridX + col * SLOT_W, gridY + row * SLOT_H, slot, mouseX, mouseY);
            }
        }
    }

    private void drawStorageSlot(DrawContext context, int x, int y, int slot, int mouseX, int mouseY) {
        String rule = storageSlotRules[slot];
        boolean hovered = mouseX >= x && mouseX < x + SLOT_W && mouseY >= y && mouseY < y + SLOT_H;
        boolean selected = slot == storageSelectedSlot;
        boolean hasRule = !rule.equals("any") && !rule.equals("empty");
        int innerColor = rule.equals("empty") ? 0xFF3D1515 : (hasRule ? 0xFF1A2E1A : 0xFF3D3D3D);
        drawMCSlot(context, x, y, innerColor, hovered, hasRule);
        if (selected) {
            context.fill(x + 3, y + 3, x + SLOT_W - 3, y + SLOT_H - 3, 0x4400AAFF);
            context.drawHorizontalLine(x + 2, x + SLOT_W - 3, y + 2, 0xFF00AAFF);
            context.drawHorizontalLine(x + 2, x + SLOT_W - 3, y + SLOT_H - 3, 0xFF00AAFF);
            context.drawVerticalLine(x + 2, y + 2, y + SLOT_H - 3, 0xFF00AAFF);
            context.drawVerticalLine(x + SLOT_W - 3, y + 2, y + SLOT_H - 3, 0xFF00AAFF);
        }
        Integer tier = config.getStorageTier(activeStoragePreset, slot);
        if (tier != null) {
            context.drawTextWithShadow(textRenderer, Text.literal("\u00a7a" + tier), x + 2, y + 2, 0xFF55FF55);
        }
        ItemStack icon = getIconForText(rule);
        if (!icon.isEmpty()) context.drawItem(icon, x + (SLOT_W - 16) / 2, y + (SLOT_H - 16) / 2);
        String displayText = getDisplayText(rule);
        int maxTextW = SLOT_W - 4;
        if (textRenderer.getWidth(displayText) > maxTextW) {
            while (displayText.length() > 1 && textRenderer.getWidth(displayText + ".") > maxTextW)
                displayText = displayText.substring(0, displayText.length() - 1);
            displayText += ".";
        }
        int tw = textRenderer.getWidth(displayText);
        int textColor = rule.equals("empty") ? 0xFFAA4444 : rule.equals("any") ? 0xFF888888 : 0xFF5599FF;
        context.drawTextWithShadow(textRenderer, Text.literal(displayText),
            x + (SLOT_W - tw) / 2, y + 27, textColor);
    }

    private int getStorageSlotAt(int mx, int my) {
        int size = config.getStoragePresets().get(activeStoragePreset).getSize();
        int rows = size / 9;
        for (int row = 0; row < rows; row++) {
            for (int col = 0; col < 9; col++) {
                int sx = gridX + col * SLOT_W, sy = gridY + row * SLOT_H;
                if (mx >= sx && mx < sx + SLOT_W && my >= sy && my < sy + SLOT_H)
                    return row * 9 + col;
            }
        }
        return -1;
    }

    private void loadStoragePreset() {
        StoragePreset p = config.getStoragePresets().get(activeStoragePreset);
        int size = p.getSize();
        for (int i = 0; i < 54; i++) storageSlotRules[i] = i < size ? p.getSlotRule(i) : "any";
        storageSelectedSlot = -1;
    }

    private void saveStoragePreset() {
        StoragePreset p = config.getStoragePresets().get(activeStoragePreset);
        int size = p.getSize();
        for (int i = 0; i < size; i++) p.setSlotRule(i, storageSlotRules[i]);
        config.save();
    }

    private void switchMode(boolean toStorage) {
        if (showStorage) saveStoragePreset();
        showStorage = toStorage;
        storageSelectedSlot = -1;
        selectedText = null;
        selectedLabel = null;
        lastSearch = null; // force palette rebuild with new mode filter
        applyFilter();
        updateStorageWidgetsVisibility();
    }

    private void updateStorageWidgetsVisibility() {
        for (ButtonWidget tab : storageTabBtns) { if (tab != null) tab.visible = showStorage; }
    }

    private void drawInventoryGrid(DrawContext context, int mouseX, int mouseY) {
        context.drawTextWithShadow(textRenderer, Text.literal("Inventory (slots 9-35)"), gridX, gridY - 12, 0xFFFFFF55);

        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                int slot = 9 + row * 9 + col;
                drawSlot(context, gridX + col * SLOT_W, gridY + row * SLOT_H, slot, mouseX, mouseY);
            }
        }

        int hotbarY = gridY + 3 * SLOT_H + 14;
        context.drawTextWithShadow(textRenderer, Text.literal("Hotbar (slots 0-8)"), gridX, hotbarY - 12, 0xFFFFFF55);
        for (int col = 0; col < 9; col++) {
            drawSlot(context, gridX + col * SLOT_W, hotbarY, col, mouseX, mouseY);
        }

        // Armor slots (left of grid): helmet, chestplate, leggings, boots
        int armorX = gridX - SLOT_W - 8;
        context.drawTextWithShadow(textRenderer, Text.literal("Armor"), armorX, gridY - 12, 0xFF55FFFF);
        for (int i = 0; i < 4; i++) {
            drawEquipSlot(context, armorX, gridY + i * SLOT_H, i, mouseX, mouseY);
        }

        // Offhand slot (below armor)
        int offhandY = gridY + 4 * SLOT_H + 4;
        context.drawTextWithShadow(textRenderer, Text.literal("Off"), armorX + 4, offhandY - 10, 0xFF55FFFF);
        drawEquipSlot(context, armorX, offhandY, 4, mouseX, mouseY);
    }

    private void drawEquipSlot(DrawContext context, int x, int y, int equipIdx, int mouseX, int mouseY) {
        String text = equipTexts[equipIdx];
        boolean hovered = mouseX >= x && mouseX < x + SLOT_W && mouseY >= y && mouseY < y + SLOT_H;
        boolean hasRule = !text.equals("any") && !text.equals("empty");

        int innerColor;
        if (text.equals("empty")) innerColor = 0xFF3D1515;
        else if (hasRule) innerColor = 0xFF15253D;
        else innerColor = 0xFF3D3D3D;

        drawMCSlot(context, x, y, innerColor, hovered, hasRule);
        if (hasRule) {
            // Blue top strip for equip slots with rule
            context.fill(x + 3, y + 3, x + SLOT_W - 3, y + 5, 0xFF5588CC);
        }

        ItemStack icon = getIconForText(text);
        if (icon.isEmpty()) icon = getDefaultEquipIcon(equipIdx);
        if (!icon.isEmpty()) context.drawItem(icon, x + (SLOT_W - 16) / 2, y + (SLOT_H - 16) / 2);

        String displayText = text.equals("any") ? EQUIP_LABELS[equipIdx] : getDisplayText(text);
        int maxTextW = SLOT_W - 4;
        if (textRenderer.getWidth(displayText) > maxTextW) {
            while (displayText.length() > 1 && textRenderer.getWidth(displayText + ".") > maxTextW)
                displayText = displayText.substring(0, displayText.length() - 1);
            displayText += ".";
        }
        int textW = textRenderer.getWidth(displayText);
        int textColor = text.equals("empty") ? 0xFFAA4444 : text.equals("any") ? 0xFF888888 : 0xFF5599FF;
        context.drawTextWithShadow(textRenderer, Text.literal(displayText),
                x + (SLOT_W - textW) / 2, y + 27, textColor);
    }

    private ItemStack getDefaultEquipIcon(int equipIdx) {
        switch (equipIdx) {
            case 0: return new ItemStack(Items.IRON_HELMET);
            case 1: return new ItemStack(Items.IRON_CHESTPLATE);
            case 2: return new ItemStack(Items.IRON_LEGGINGS);
            case 3: return new ItemStack(Items.IRON_BOOTS);
            case 4: return new ItemStack(Items.SHIELD);
            default: return ItemStack.EMPTY;
        }
    }

    /** Draw a Minecraft-style sunken slot with 3D bevel effect */
    private void drawMCSlot(DrawContext context, int x, int y, int innerColor, boolean hovered, boolean hasRule) {
        // 1. Outer black frame
        context.fill(x, y, x + SLOT_W, y + SLOT_H, 0xFF111111);
        // 2. Top + Left dark shadow edge (creates sunken look)
        context.fill(x + 1, y + 1, x + SLOT_W - 1, y + 3, 0xFF2A2A2A);
        context.fill(x + 1, y + 1, x + 3, y + SLOT_H - 1, 0xFF2A2A2A);
        // 3. Bottom + Right lighter edge (opposite highlight)
        context.fill(x + 1, y + SLOT_H - 3, x + SLOT_W - 1, y + SLOT_H - 1, 0xFF6A6A6A);
        context.fill(x + SLOT_W - 3, y + 1, x + SLOT_W - 1, y + SLOT_H - 1, 0xFF6A6A6A);
        // 4. Inner background
        context.fill(x + 3, y + 3, x + SLOT_W - 3, y + SLOT_H - 3, innerColor);
        // 5. Rule indicator: thin colored top strip
        if (hasRule) {
            context.fill(x + 3, y + 3, x + SLOT_W - 3, y + 5, 0xFF55AA55);
        }
        // 6. Hover overlay
        if (hovered) {
            context.fill(x + 3, y + 3, x + SLOT_W - 3, y + SLOT_H - 3, 0x55FFFF00);
            // Bright yellow inner frame
            context.drawHorizontalLine(x + 2, x + SLOT_W - 3, y + 2, 0xFFFFFF00);
            context.drawHorizontalLine(x + 2, x + SLOT_W - 3, y + SLOT_H - 3, 0xFFFFFF00);
            context.drawVerticalLine(x + 2, y + 2, y + SLOT_H - 3, 0xFFFFFF00);
            context.drawVerticalLine(x + SLOT_W - 3, y + 2, y + SLOT_H - 3, 0xFFFFFF00);
        }
    }

    private void drawSlot(DrawContext context, int x, int y, int slot, int mouseX, int mouseY) {
        String text = slotTexts[slot];
        boolean hovered = mouseX >= x && mouseX < x + SLOT_W && mouseY >= y && mouseY < y + SLOT_H;
        boolean hasRule = !text.equals("any") && !text.equals("empty");

        int innerColor;
        if (text.equals("empty")) innerColor = 0xFF3D1515;
        else if (hasRule) innerColor = 0xFF1A2E1A;
        else innerColor = 0xFF3D3D3D;

        drawMCSlot(context, x, y, innerColor, hovered, hasRule);

        // Draw item icon centered
        ItemStack icon = getIconForText(text);
        if (!icon.isEmpty()) {
            context.drawItem(icon, x + (SLOT_W - 16) / 2, y + (SLOT_H - 16) / 2);
        }

        // Label below icon
        String displayText = getDisplayText(text);
        int maxTextW = SLOT_W - 4;
        if (textRenderer.getWidth(displayText) > maxTextW) {
            while (displayText.length() > 1 && textRenderer.getWidth(displayText + ".") > maxTextW)
                displayText = displayText.substring(0, displayText.length() - 1);
            displayText += ".";
        }
        int textW = textRenderer.getWidth(displayText);
        int textColor = text.equals("empty") ? 0xFFAA4444 : text.equals("any") ? 0xFF888888 : 0xFF55CC55;
        context.drawTextWithShadow(textRenderer, Text.literal(displayText),
                x + (SLOT_W - textW) / 2, y + 27, textColor);
    }

    /** Get an item icon for a rule text */
    private ItemStack getIconForText(String text) {
        if (text.equals("empty")) return new ItemStack(Items.BARRIER);
        if (text.equals("any")) return ItemStack.EMPTY;
        if (text.startsWith("g:")) {
            switch (text.substring(2)) {
                case "weapons": return new ItemStack(Items.IRON_SWORD);
                case "tools": return new ItemStack(Items.IRON_PICKAXE);
                case "armor": return new ItemStack(Items.IRON_CHESTPLATE);
                case "blocks": return new ItemStack(Items.OAK_PLANKS);
                case "food": return new ItemStack(Items.COOKED_BEEF);
                case "utility": return new ItemStack(Items.TORCH);
                case "valuables": return new ItemStack(Items.DIAMOND);
                case "potions":        return new ItemStack(Items.POTION);
                case "splash_potions": return new ItemStack(Items.SPLASH_POTION);
                case "arrows":         return new ItemStack(Items.ARROW);
                case "misc":           return new ItemStack(Items.ENDER_PEARL);
                case "logs":     return new ItemStack(Items.OAK_LOG);
                case "boats":    return new ItemStack(Items.OAK_BOAT);
                case "plants":   return new ItemStack(Items.DANDELION);
                case "stone":    return new ItemStack(Items.STONE);
                case "ores":     return new ItemStack(Items.IRON_ORE);
                case "cooked":   return new ItemStack(Items.COOKED_BEEF);
                case "rawfood":  return new ItemStack(Items.BEEF);
                case "nether":   return new ItemStack(Items.NETHERRACK);
                case "end":      return new ItemStack(Items.END_STONE);
                case "partial":  return new ItemStack(Items.OAK_SLAB);
                case "redstone": return new ItemStack(Items.REDSTONE);
                case "creative": return new ItemStack(Items.COMMAND_BLOCK);
                default: return ItemStack.EMPTY;
            }
        }
        if (text.startsWith("b:")) {
            // Bundle content slot: show bundle icon regardless of content
            return new ItemStack(Items.BUNDLE);
        }
        if (text.startsWith("cg:")) {
            // Custom group: show a chest icon
            return new ItemStack(Items.CHEST);
        }
        if (text.startsWith("t:")) {
            switch (text.substring(2)) {
                case "sword": return new ItemStack(Items.IRON_SWORD);
                case "pickaxe": return new ItemStack(Items.IRON_PICKAXE);
                case "axe": return new ItemStack(Items.IRON_AXE);
                case "shovel": return new ItemStack(Items.IRON_SHOVEL);
                case "hoe": return new ItemStack(Items.IRON_HOE);
                case "bow": return new ItemStack(Items.BOW);
                case "crossbow": return new ItemStack(Items.CROSSBOW);
                case "trident": return new ItemStack(Items.TRIDENT);
                case "mace": return new ItemStack(Items.MACE);
                case "helmet": return new ItemStack(Items.IRON_HELMET);
                case "chestplate": return new ItemStack(Items.IRON_CHESTPLATE);
                case "leggings": return new ItemStack(Items.IRON_LEGGINGS);
                case "boots": return new ItemStack(Items.IRON_BOOTS);
                case "shield": return new ItemStack(Items.SHIELD);
                case "elytra": return new ItemStack(Items.ELYTRA);
                case "fishing_rod": return new ItemStack(Items.FISHING_ROD);
                case "shears": return new ItemStack(Items.SHEARS);
                case "flint_and_steel":  return new ItemStack(Items.FLINT_AND_STEEL);
                case "potion":           return new ItemStack(Items.POTION);
                case "splash_potion":    return new ItemStack(Items.SPLASH_POTION);
                case "lingering_potion": return new ItemStack(Items.LINGERING_POTION);
                case "arrow":            return new ItemStack(Items.ARROW);
                case "spectral_arrow":   return new ItemStack(Items.SPECTRAL_ARROW);
                case "tipped_arrow":     return new ItemStack(Items.TIPPED_ARROW);
                default: return ItemStack.EMPTY;
            }
        }
        // Specific item ID
        if (text.contains(":")) {
            try {
                Identifier id = Identifier.of(text);
                Item item = Registries.ITEM.get(id);
                if (item != null && item != Items.AIR) return new ItemStack(item);
            } catch (Exception ignored) {}
        }
        return ItemStack.EMPTY;
    }

    /** Convert rule text to a short display label */
    private String getDisplayText(String text) {
        if (text.equals("any")) return "-";
        if (text.equals("empty")) return "X";
        if (text.startsWith("g:")) return text.substring(2);
        if (text.startsWith("t:")) return text.substring(2);
        if (text.startsWith("cg:")) return text.substring(3);
        // Specific item: show just the item name part
        if (text.contains(":")) {
            String path = text.substring(text.indexOf(':') + 1);
            return path.replace('_', ' ');
        }
        return text;
    }

    private void rebuildPaletteButtons() {
        for (ButtonWidget btn : paletteButtons) remove(btn);
        paletteButtons.clear();

        int rowHeight = 22; // Increased from 16 to 22 for taller buttons
        int visibleRows = paletteH / rowHeight;
        int startIdx = paletteScroll;
        int endIdx = Math.min(filteredEntries.size(), startIdx + visibleRows);

        for (int i = startIdx; i < endIdx; i++) {
            PaletteEntry entry = filteredEntries.get(i);
            int py = paletteY + (i - startIdx) * rowHeight; // Increased spacing
            if (entry.isHeader) continue;

            final PaletteEntry clickedEntry = entry;
            // Leave space on the left for the icon (18px), shorten label to fit
            String shortLabel = entry.label.length() > 10 ? entry.label.substring(0, 10) + ".." : entry.label;
            // Add spaces to push text right of icon
            ButtonWidget btn = ButtonWidget.builder(
                    Text.literal("         " + shortLabel),
                    b -> {
                        if (selectedText != null && selectedText.equals(clickedEntry.ruleText)) {
                            selectedText = null;
                            selectedLabel = null;
                        } else {
                            selectedText = clickedEntry.ruleText;
                            selectedLabel = clickedEntry.label;
                        }
                    }
            ).dimensions(paletteX, py, paletteW, 20).build(); // Increased button height from 15 to 20
            paletteButtons.add(btn);
            addDrawableChild(btn);
        }
    }

    private void drawPaletteExtras(DrawContext context, int mouseX, int mouseY) {
        int rowHeight = 22; // Match rebuildPaletteButtons
        int visibleRows = paletteH / rowHeight;
        int startIdx = paletteScroll;
        int endIdx = Math.min(filteredEntries.size(), startIdx + visibleRows);

        // Draw headers
        for (int i = startIdx; i < endIdx; i++) {
            PaletteEntry entry = filteredEntries.get(i);
            int py = paletteY + (i - startIdx) * rowHeight; // Match new spacing
            if (entry.isHeader) {
                context.drawTextWithShadow(textRenderer, Text.literal(entry.label), paletteX + 2, py + 6, 0xFFFFFF55);
            }
        }

        // Draw item icons on the left side of each palette button
        int btnIdx = 0;
        for (int i = startIdx; i < endIdx; i++) {
            PaletteEntry entry = filteredEntries.get(i);
            if (entry.isHeader) continue;
            if (btnIdx >= paletteButtons.size()) break;
            ButtonWidget btn = paletteButtons.get(btnIdx);
            ItemStack icon = getIconForText(entry.ruleText);
            if (!icon.isEmpty()) {
                // Draw 16x16 icon on the left inner edge of the button
                context.drawItem(icon, btn.getX() + 2, btn.getY() + 2);
            }
            btnIdx++;
        }

        // Green border on selected button
        for (ButtonWidget btn : paletteButtons) {
            if (selectedText != null && btn.getMessage().getString().contains("[" + selectedText + "]")) {
                int bx = btn.getX(), by = btn.getY(), bw = btn.getWidth(), bh = btn.getHeight();
                context.drawHorizontalLine(bx - 1, bx + bw, by - 1, 0xFF00FF00);
                context.drawHorizontalLine(bx - 1, bx + bw, by + bh, 0xFF00FF00);
                context.drawVerticalLine(bx - 1, by - 1, by + bh, 0xFF00FF00);
                context.drawVerticalLine(bx + bw, by - 1, by + bh, 0xFF00FF00);
            }
        }

        // Item count
        context.drawTextWithShadow(textRenderer, Text.literal(filteredEntries.size() + " items"),
                paletteX, paletteY + paletteH + 2, 0xFF888888);
    }

    private void drawSlotTooltip(DrawContext context, int mouseX, int mouseY) {
        int hoveredSlot = getHoveredSlot(mouseX, mouseY);
        if (hoveredSlot >= 0) {
            String text = slotTexts[hoveredSlot];
            String slotName = (hoveredSlot <= 8) ? "Hotbar " + (hoveredSlot + 1) :
                    "Row " + ((hoveredSlot - 9) / 9 + 1) + " Col " + ((hoveredSlot - 9) % 9 + 1);

            List<Text> tooltip = new ArrayList<>();
            tooltip.add(Text.literal("Slot " + hoveredSlot + " (" + slotName + ")"));
            tooltip.add(Text.literal("Rule: " + text));
            if (selectedText != null) {
                tooltip.add(Text.literal("Click to set: " + selectedText));
            } else {
                tooltip.add(Text.literal("Right-click = clear to 'any'"));
            }
            context.drawTooltip(textRenderer, tooltip, mouseX, mouseY);
            return;
        }
        // Equipment slot tooltip
        int equipIdx = getHoveredEquipSlot(mouseX, mouseY);
        if (equipIdx >= 0) {
            String text = equipTexts[equipIdx];
            List<Text> tooltip = new ArrayList<>();
            tooltip.add(Text.literal(EQUIP_LABELS[equipIdx] + " Slot"));
            tooltip.add(Text.literal("Rule: " + text));
            if (selectedText != null) {
                if (isValidForEquipSlot(equipIdx, selectedText)) {
                    tooltip.add(Text.literal("Click to set: " + selectedText));
                } else {
                    tooltip.add(Text.literal("\u00a7c" + selectedText + " cannot go here!"));
                }
            } else {
                tooltip.add(Text.literal("Right-click = clear to 'any'"));
            }
            context.drawTooltip(textRenderer, tooltip, mouseX, mouseY);
        }
    }

    private int getHoveredSlot(int mouseX, int mouseY) {
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                int slot = 9 + row * 9 + col;
                int x = gridX + col * SLOT_W, y = gridY + row * SLOT_H;
                if (mouseX >= x && mouseX < x + SLOT_W && mouseY >= y && mouseY < y + SLOT_H) return slot;
            }
        }
        int hotbarY = gridY + 3 * SLOT_H + 14;
        for (int col = 0; col < 9; col++) {
            int x = gridX + col * SLOT_W;
            if (mouseX >= x && mouseX < x + SLOT_W && mouseY >= hotbarY && mouseY < hotbarY + SLOT_H) return col;
        }
        return -1;
    }

    /** Check if a rule text is valid for an equipment slot */
    private boolean isValidForEquipSlot(int equipIdx, String ruleText) {
        if (ruleText.equals("any") || ruleText.equals("empty")) return true;
        // Offhand accepts anything
        if (equipIdx == 4) return true;
        // Armor slots: check if the rule contains a valid type for this slot
        String[] allowed = EQUIP_ALLOWED_TYPES[equipIdx];
        if (allowed == null) return true;
        for (String type : allowed) {
            if (ruleText.contains(type)) return true;
        }
        return false;
    }

    private int getHoveredEquipSlot(int mouseX, int mouseY) {
        int armorX = gridX - SLOT_W - 8;
        // Armor slots 0-3
        for (int i = 0; i < 4; i++) {
            int y = gridY + i * SLOT_H;
            if (mouseX >= armorX && mouseX < armorX + SLOT_W && mouseY >= y && mouseY < y + SLOT_H) return i;
        }
        // Offhand slot
        int offhandY = gridY + 4 * SLOT_H + 4;
        if (mouseX >= armorX && mouseX < armorX + SLOT_W && mouseY >= offhandY && mouseY < offhandY + SLOT_H) return 4;
        return -1;
    }

    @Override
    public boolean mouseClicked(Click click, boolean bl) {
        double mouseX = click.x();
        double mouseY = click.y();
        int button = click.button();

        if (config.isShowHelp()) {
            int gw = 300, gh = 178;
            int gx = width / 2 - gw / 2;
            int gy = height / 2 - gh / 2;
            if (mouseX < gx || mouseX > gx + gw || mouseY < gy || mouseY > gy + gh) {
                config.setShowHelp(false);
                config.save();
            }
            return true;
        }

        // Double-click detection for opening tier order config (inventory mode only)
        if (button == 0 && !showStorage) { // Left click, inventory mode only
            int hoveredSlot = getHoveredSlot((int) mouseX, (int) mouseY);
            if (hoveredSlot >= 0) {
                String slotRule = slotTexts[hoveredSlot];
                String itemType = extractItemTypeFromRule(slotRule);
                if (itemType != null) {
                    // Check if this is a double click by looking at the time since last click
                    long currentTime = System.currentTimeMillis();
                    if (lastClickTime > 0 && (currentTime - lastClickTime) < 500 && lastClickedSlot == hoveredSlot) {
                        // Double click detected - open tier order config
                        MinecraftClient.getInstance().setScreen(new SortingOrderConfigScreen(this, slotTexts, equipTexts));
                        lastClickTime = 0; // Reset
                        return true;
                    }
                    lastClickTime = currentTime;
                    lastClickedSlot = hoveredSlot;
                }
            }
        }

        // Storage mode: handle storage slot clicks instead of inventory
        if (showStorage) {
            int storageSlot = getStorageSlotAt((int) mouseX, (int) mouseY);
            if (storageSlot >= 0) {
                if (button == 0) {
                    if (selectedText != null) {
                        storageSlotRules[storageSlot] = selectedText;
                        saveStoragePreset();
                    } else {
                        storageSelectedSlot = storageSlot;
                    }
                    return true;
                } else if (button == 1) {
                    storageSlotRules[storageSlot] = "any";
                    if (storageSelectedSlot == storageSlot) storageSelectedSlot = -1;
                    saveStoragePreset();
                    return true;
                }
            }
            return super.mouseClicked(click, bl);
        }

        // Check equipment slots first
        int equipIdx = getHoveredEquipSlot((int) mouseX, (int) mouseY);
        if (equipIdx >= 0) {
            if (button == 0 && selectedText != null) {
                if (isValidForEquipSlot(equipIdx, selectedText)) {
                    equipTexts[equipIdx] = selectedText;
                    saveToConfig();
                }
                return true;
            } else if (button == 1) {
                equipTexts[equipIdx] = "any";
                saveToConfig();
                selectedText = null;
                selectedLabel = null;
                return true;
            }
        }

        int hoveredSlot = getHoveredSlot((int) mouseX, (int) mouseY);
        if (hoveredSlot >= 0) {
            if (button == 0 && selectedText != null) {
                slotTexts[hoveredSlot] = selectedText;
                saveToConfig();
                return true;
            } else if (button == 1) {
                slotTexts[hoveredSlot] = "any";
                saveToConfig();
                selectedText = null;
                selectedLabel = null;
                return true;
            }
        }

        return super.mouseClicked(click, bl);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (mouseX >= paletteX - 2 && mouseX < paletteX + paletteW + 2
                && mouseY >= paletteY - 24 && mouseY < paletteY + paletteH) {
            int oldScroll = paletteScroll;
            // Use signum to avoid truncation of fractional scroll values
            int delta = verticalAmount > 0 ? -1 : (verticalAmount < 0 ? 1 : 0);
            paletteScroll += delta;
            paletteScroll = Math.max(0, Math.min(maxPaletteScroll, paletteScroll));
            if (paletteScroll != oldScroll) rebuildPaletteButtons();
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    private String extractItemTypeFromRule(String rule) {
        if (rule == null || rule.isEmpty()) return null;
        if (rule.equals("any") || rule.equals("empty")) return null;
        
        if (rule.startsWith("t:")) {
            return rule.substring(2); // e.g. "pickaxe" from "t:pickaxe"
        }
        
        // For specific items, extract the type from the item ID
        if (rule.contains(":")) {
            String id = rule.substring(rule.indexOf(':') + 1);
            if (id.contains("pickaxe")) return "pickaxe";
            if (id.contains("sword") && !id.contains("_block") && !id.contains("_ore")) return "sword";
            if (id.contains("_axe") && !id.contains("_block") && !id.contains("_ore")) return "axe";
            if (id.contains("shovel")) return "shovel";
            if (id.contains("hoe")) return "hoe";
            if (id.contains("helmet")) return "helmet";
            if (id.contains("chestplate")) return "chestplate";
            if (id.contains("leggings")) return "leggings";
            if (id.contains("boots")) return "boots";
        }
        
        return null;
    }
    
    private String findSelectedGroupRule() {
        // Find the most common group rule in slots
        java.util.Map<String, Integer> groupCounts = new java.util.HashMap<>();
        
        // Check main inventory slots (0-35)
        for (int i = 0; i < 36; i++) {
            String rule = slotTexts[i];
            if (rule != null && !rule.equals("any") && !rule.equals("empty")) {
                if (rule.startsWith("t:")) {
                    // Item type groups
                    groupCounts.put(rule, groupCounts.getOrDefault(rule, 0) + 1);
                } else if (rule.startsWith("g:")) {
                    // Item groups
                    groupCounts.put(rule, groupCounts.getOrDefault(rule, 0) + 1);
                }
            }
        }
        
        // Check equipment slots (armor + offhand)
        String[] equipKeys = {"armor_head", "armor_chest", "armor_legs", "armor_feet", "offhand"};
        for (int i = 0; i < equipTexts.length && i < equipKeys.length; i++) {
            String rule = equipTexts[i];
            LOGGER.info("[InvOrganizer] Equipment slot " + equipKeys[i] + ": " + rule);
            if (rule != null && !rule.equals("any") && !rule.equals("empty")) {
                // Convert equipment slot rules to group rules
                String groupRule = convertEquipRuleToGroupRule(rule);
                LOGGER.info("[InvOrganizer] Converted to group rule: " + groupRule);
                if (groupRule != null) {
                    groupCounts.put(groupRule, groupCounts.getOrDefault(groupRule, 0) + 1);
                }
            }
        }
        
        // Debug output
        LOGGER.info("[InvOrganizer] Group counts: " + groupCounts);
        
        // Return the group with the most slots
        String bestGroup = null;
        int maxCount = 0;
        for (java.util.Map.Entry<String, Integer> entry : groupCounts.entrySet()) {
            if (entry.getValue() > maxCount) {
                maxCount = entry.getValue();
                bestGroup = entry.getKey();
            }
        }
        
        LOGGER.info("[InvOrganizer] Selected group rule: " + bestGroup + " (count: " + maxCount + ")");
        return bestGroup;
    }
    
    private String convertEquipRuleToGroupRule(String equipRule) {
        if (equipRule == null || equipRule.equals("any") || equipRule.equals("empty")) return null;
        
        // Convert specific item to type group
        if (equipRule.contains(":")) {
            String id = equipRule.substring(equipRule.indexOf(':') + 1);
            
            // Armor pieces
            if (id.contains("helmet") || id.contains("turtle_helmet")) return "t:helmet";
            if (id.contains("chestplate") || id.contains("elytra")) return "t:chestplate";
            if (id.contains("leggings")) return "t:leggings";
            if (id.contains("boots")) return "t:boots";
            
            // Tools
            if (id.contains("pickaxe")) return "t:pickaxe";
            if (id.contains("_axe") && !id.contains("_block") && !id.contains("_ore")) return "t:axe";
            if (id.contains("shovel")) return "t:shovel";
            if (id.contains("hoe")) return "t:hoe";
            
            // Weapons
            if (id.contains("sword") && !id.contains("_block") && !id.contains("_ore")) return "t:sword";
            if (id.contains("bow") && !id.contains("cross")) return "t:bow";
            if (id.contains("crossbow")) return "t:crossbow";
            if (id.contains("trident")) return "t:trident";
            if (id.contains("mace")) return "t:mace";
            
            // Offhand - any item
            return "t:offhand";
        }
        
        return null;
    }
    
    private String capitalizeGroupRule(String groupRule) {
        if (groupRule == null || groupRule.isEmpty()) return "";
        
        if (groupRule.startsWith("t:")) {
            String itemType = groupRule.substring(2);
            return capitalizeItemType(itemType);
        } else if (groupRule.startsWith("g:")) {
            String groupName = groupRule.substring(2);
            return groupName.substring(0, 1).toUpperCase() + groupName.substring(1);
        }
        
        return groupRule;
    }
    
    private String findFirstItemType() {
        // Search through all slots for the first item type rule
        for (int i = 0; i < 36; i++) {
            String itemType = extractItemTypeFromRule(slotTexts[i]);
            if (itemType != null) {
                return itemType;
            }
        }
        // Also check equipment slots
        for (int i = 0; i < 5; i++) {
            String itemType = extractItemTypeFromRule(equipTexts[i]);
            if (itemType != null) {
                return itemType;
            }
        }
        return null;
    }
    
    private String capitalizeItemType(String itemType) {
        if (itemType == null || itemType.isEmpty()) return "";
        return itemType.substring(0, 1).toUpperCase() + itemType.substring(1);
    }

    /** Draw a decorated panel background (Minecraft-style) */
    private void drawDecoratedPanel(DrawContext context, int x, int y, int width, int height) {
        // Dark background
        context.fill(x, y, x + width, y + height, 0xC0101010);
        
        // Outer border (dark)
        context.drawHorizontalLine(x, x + width - 1, y, 0xFF000000);
        context.drawHorizontalLine(x, x + width - 1, y + height - 1, 0xFF000000);
        context.drawVerticalLine(x, y, y + height - 1, 0xFF000000);
        context.drawVerticalLine(x + width - 1, y, y + height - 1, 0xFF000000);
        
        // Inner highlight (light gray)
        context.drawHorizontalLine(x + 1, x + width - 2, y + 1, 0xFF555555);
        context.drawVerticalLine(x + 1, y + 1, y + height - 2, 0xFF555555);
        
        // Bottom-right shadow (darker)
        context.drawHorizontalLine(x + 1, x + width - 2, y + height - 2, 0xFF2A2A2A);
        context.drawVerticalLine(x + width - 2, y + 1, y + height - 2, 0xFF2A2A2A);
        
        // Corner decorations (small dots for detail)
        context.fill(x + 2, y + 2, x + 4, y + 4, 0xFF888888);
        context.fill(x + width - 4, y + 2, x + width - 2, y + 4, 0xFF888888);
        context.fill(x + 2, y + height - 4, x + 4, y + height - 2, 0xFF888888);
        context.fill(x + width - 4, y + height - 4, x + width - 2, y + height - 2, 0xFF888888);
    }

    /** Draw a decorated border only (no dark background) */
    private void drawDecoratedBorder(DrawContext context, int x, int y, int width, int height) {
        // Outer border (dark)
        context.drawHorizontalLine(x, x + width - 1, y, 0xFF000000);
        context.drawHorizontalLine(x, x + width - 1, y + height - 1, 0xFF000000);
        context.drawVerticalLine(x, y, y + height - 1, 0xFF000000);
        context.drawVerticalLine(x + width - 1, y, y + height - 1, 0xFF000000);
        
        // Inner highlight (light gray)
        context.drawHorizontalLine(x + 1, x + width - 2, y + 1, 0xFF555555);
        context.drawVerticalLine(x + 1, y + 1, y + height - 2, 0xFF555555);
        
        // Bottom-right shadow (darker)
        context.drawHorizontalLine(x + 1, x + width - 2, y + height - 2, 0xFF2A2A2A);
        context.drawVerticalLine(x + width - 2, y + 1, y + height - 2, 0xFF2A2A2A);
        
        // Corner decorations (small dots for detail)
        context.fill(x + 2, y + 2, x + 4, y + 4, 0xFF888888);
        context.fill(x + width - 4, y + 2, x + width - 2, y + 4, 0xFF888888);
        context.fill(x + 2, y + height - 4, x + 4, y + height - 2, 0xFF888888);
        context.fill(x + width - 4, y + height - 4, x + width - 2, y + height - 2, 0xFF888888);
    }

    /** Draw the guide overlay panel (centered, on top of everything) */
    private void drawGuideOverlay(DrawContext context) {
        int gw = 300, gh = 178;
        int gx = width / 2 - gw / 2;
        int gy = height / 2 - gh / 2;

        // Darken entire screen behind overlay
        context.fill(0, 0, width, height, 0x88000000);

        drawDecoratedPanel(context, gx, gy, gw, gh);

        // Title bar
        context.fill(gx + 4, gy + 4, gx + gw - 4, gy + 20, 0xFF1A1A2E);
        context.drawCenteredTextWithShadow(textRenderer,
            Text.literal("\u00a7e\u00a7lInventory Config Guide"), width / 2, gy + 8, 0xFFFFFF55);

        int lx = gx + 12, ly = gy + 26, lh = 13;

        context.drawTextWithShadow(textRenderer,
            Text.literal("\u00a7e[1] \u00a7fSelect a rule from the right panel (click to highlight it)"), lx, ly, 0xFFFFFFFF); ly += lh;
        context.drawTextWithShadow(textRenderer,
            Text.literal("\u00a7e[2] \u00a7fLeft-click a slot to assign the selected rule to it"), lx, ly, 0xFFFFFFFF); ly += lh;
        context.drawTextWithShadow(textRenderer,
            Text.literal("\u00a7e[3] \u00a7fRight-click a slot to reset it back to \u00a77'any'"), lx, ly, 0xFFFFFFFF); ly += lh;
        context.drawTextWithShadow(textRenderer,
            Text.literal("\u00a77       'any' = accepts everything  |  'empty' = always empty"), lx, ly, 0xFFAAAAAA); ly += lh;
        context.drawTextWithShadow(textRenderer,
            Text.literal("\u00a7e[4] \u00a7fUse the search box (top-right) to filter the rule list"), lx, ly, 0xFFFFFFFF); ly += lh;
        context.drawTextWithShadow(textRenderer,
            Text.literal("\u00a7e[5] \u00a7f'Tier Order' \u00a77\u00bb \u00a7fset item sort priority per slot"), lx, ly, 0xFFFFFFFF); ly += lh;
        context.drawTextWithShadow(textRenderer,
            Text.literal("\u00a7e[6] \u00a7f'Kits' \u00a77\u00bb \u00a7fsave and load inventory preset layouts"), lx, ly, 0xFFFFFFFF); ly += lh;
        context.drawTextWithShadow(textRenderer,
            Text.literal("\u00a7e[7] \u00a7f'Save' saves your changes and closes the screen"), lx, ly, 0xFFFFFFFF); ly += lh + 6;

        // Separator
        context.fill(gx + 12, ly, gx + gw - 12, ly + 1, 0xFF444444); ly += 6;

        context.drawCenteredTextWithShadow(textRenderer,
            Text.literal("\u00a77Click outside or press \u00a7e[?]\u00a77 to close this guide"), width / 2, ly, 0xFF888888);
    }

    @Override
    public void close() {
        MinecraftClient.getInstance().setScreen(parent);
    }
}
