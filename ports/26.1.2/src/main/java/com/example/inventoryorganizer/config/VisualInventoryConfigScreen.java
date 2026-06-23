package com.example.inventoryorganizer.config;

import net.minecraft.client.Minecraft;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
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
    // Auto-refill flag per inventory slot (middle-click toggle). Synced to OrganizerConfig on load/save.
    private final boolean[] slotRefill = new boolean[36];

    // --- Auto-switch overlay (inventory mode only) ---
    // The "Auto switch" mode overlays a movable switch slot + a pool of storage slots onto the SAME
    // inventory grid (so block/cg/item rules mirror between modes; only tools + these slots differ).
    private boolean autoSwitchView = false;
    private int switchPlaceMode = 0; // 0=none, 1=move switch slot
    private Button switchModeBtn, placeSwitchBtn, switchSetupBtn, switchTriggerBtn, switchCopyBtn;

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
    private boolean draggingScrollbar = false;
    private static final int SCROLLBAR_WIDTH = 6;
    private int paletteScroll = 0;
    private int maxPaletteScroll = 0;
    private final List<Button> paletteButtons = new ArrayList<>();

    // Search
    private EditBox searchField;
    private String lastSearch = "";

    // Selected palette entry (text to paint onto slots)
    private String selectedText = null;
    private String selectedLabel = null;

    // Double-click detection
    private long lastClickTime = 0;
    private int lastClickedSlot = -1;

    // Tier Order warning
    private Button solveButton;
    private Button masterModeBtn;

    // --- Storage mode ---
    private boolean showStorage = false;
    private int activeStoragePreset = 0;
    // When set, the screen edits this transient preset instead of one in the config list (shared-edit
    // flow): no config tabs, and saving calls onTransientSave (which uploads it to the server).
    private StoragePreset transientPreset = null;
    private Runnable onTransientSave = null;
    private boolean pendingFirstHelp = false; // open the full guide once, on first settings entry
    private boolean inlineHelpOpen = false;   // short in-place help overlay (toggled by "?")
    private final String[] storageSlotRules = new String[54];
    private int storageSelectedSlot = -1;
    private Button[] storageTabBtns = new Button[OrganizerConfig.DEFAULT_COUNT];
    private Button storageProfilesBtn;
    private Button trashTabBtn;

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
        super(Component.literal("Inventory Config"));
        this.parent = parent;
        this.config = OrganizerConfig.get();
        this.autoSwitchView = config.isSwitchEnabled(); // load the matching rule set (Auto vs Plain)
        reloadSlotTextsFromConfig();
        buildAllEntries();
        filteredEntries = new ArrayList<>(allEntries);
    }

    /** (Re)load the inventory + equipment slot rules from the live config into the local edit arrays. */
    private void reloadSlotTextsFromConfig() {
        for (int i = 0; i < 36; i++) {
            SlotRule r = config.getInventoryRule(i, autoSwitchView); // Auto vs Plain rule set per current view
            slotTexts[i] = r.toText();
            slotRefill[i] = r.isRefill() && i <= 8; // refill is hotbar-only
        }
        for (int i = 0; i < 5; i++) {
            equipTexts[i] = config.getSlotRuleByKey(EQUIP_KEYS[i]).toText();
        }
    }

    /** Set right before opening a child screen (e.g. Kits) that may rewrite the config; on return,
     *  init() re-reads the config so a loaded kit isn't clobbered by this screen's stale edit arrays. */
    private boolean reloadOnInit = false;

    /**
     * Open the config screen directly editing the storage profile at the given list index.
     * Used by ChestProfileListScreen so per-chest profiles are edited with the exact same UI as
     * the built-in storage presets (modern palette, identical layout).
     */
    public VisualInventoryConfigScreen(Screen parent, int storageProfileIndex) {
        this(parent);
        this.showStorage = true;
        int n = config.getStoragePresets().size();
        this.activeStoragePreset = Math.max(0, Math.min(storageProfileIndex, n - 1));
    }

    /**
     * Open the storage editor on a TRANSIENT preset (not in the config list). On save, {@code onSave}
     * runs — used by the shared-edit flow to upload the edited rules to the server.
     */
    public VisualInventoryConfigScreen(Screen parent, StoragePreset transientPreset, Runnable onSave) {
        this(parent);
        this.showStorage = true;
        this.transientPreset = transientPreset;
        this.onTransientSave = onSave;
    }

    /**
     * Build the Trash/Void editor: a transient 54-slot preset whose slot rules are the trash list.
     * Reuses the storage editor UI (palette + grid). Saving persists the rules to {@code trash_rules}.
     */
    private VisualInventoryConfigScreen buildTrashEditor() {
        StoragePreset trash = new StoragePreset("§cTrash / Void — items dropped in free mode", 54);
        java.util.List<String> rules = config.getTrashRules();
        for (int i = 0; i < 54; i++) trash.setSlotRule(i, i < rules.size() ? rules.get(i) : "any");
        return new VisualInventoryConfigScreen(this, trash, () -> {
            java.util.List<String> out = new java.util.ArrayList<>();
            for (int i = 0; i < trash.getSize(); i++) out.add(trash.getSlotRule(i));
            config.setTrashRules(out);
            config.save();
        });
    }

    /** The preset currently being edited (the transient one, or the selected config preset). */
    private StoragePreset activeStorage() {
        return transientPreset != null ? transientPreset : config.getStoragePresets().get(activeStoragePreset);
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

        // Built-in groups (weapons, tools, blocks, …) are now materialized as editable custom groups
        // and appear in the "Custom Groups" section above — so they are no longer listed here. Only the
        // catch-all "misc" remains a pure heuristic group (everything uncategorised).
        allEntries.add(new PaletteEntry("--- Groups ---", "", true));
        allEntries.add(new PaletteEntry("[G] Misc", "g:misc", false));

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

        // Individual potions (by effect). A "pot:<effect>" slot rule targets that specific potion
        // (all its variants); the Tier Order's Potion list decides which variant ranks higher.
        allEntries.add(new PaletteEntry("--- Potions ---", "", true));
        for (String eff : basePotionEffects()) {
            allEntries.add(new PaletteEntry("[P] " + formatIdAsName(eff), "pot:" + eff, false));
        }

        // All items from registry
        allEntries.add(new PaletteEntry("--- All Items ---", "", true));
        try {
            List<PaletteEntry> itemEntries = new ArrayList<>();
            for (Identifier id : BuiltInRegistries.ITEM.keySet()) {
                String itemId = id.toString();
                if (itemId.equals("minecraft:air")) continue;
                String name = formatIdAsName(id.getPath());
                itemEntries.add(new PaletteEntry(name, itemId, false));
            }
            itemEntries.sort((a, b) -> a.label.compareToIgnoreCase(b.label));
            allEntries.addAll(itemEntries);
        } catch (Exception ignored) {}
    }

    /** Unique base potion effects from the registry (long_/strong_ collapsed), sorted, for the palette. */
    private static java.util.List<String> basePotionEffects() {
        java.util.LinkedHashSet<String> set = new java.util.LinkedHashSet<>();
        try {
            for (Identifier id : BuiltInRegistries.POTION.keySet()) {
                String p = id.getPath();
                if (p.equals("empty")) continue;
                if (p.startsWith("long_")) p = p.substring(5);
                else if (p.startsWith("strong_")) p = p.substring(7);
                set.add(p);
            }
        } catch (Throwable ignored) {}
        java.util.List<String> out = new java.util.ArrayList<>(set);
        out.sort(String::compareToIgnoreCase);
        return out;
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
        int switchSlot = autoSwitchView ? config.getSwitchSlot() : -1;
        for (int i = 0; i < 36; i++) {
            if (i == switchSlot) { config.setInventoryRule(i, new SlotRule(), autoSwitchView); continue; }
            SlotRule rule = SlotRule.fromText(slotTexts[i]);
            rule.setRefill(slotRefill[i] && i <= 8); // refill is hotbar-only
            config.setInventoryRule(i, rule, autoSwitchView);
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
        String search = searchField != null ? searchField.getValue().trim().toLowerCase() : "";
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

        // Returning from a child screen that may have rewritten the config (e.g. Kits "Load"):
        // re-read the rules so our stale edit arrays don't overwrite the loaded data on next save.
        if (reloadOnInit) {
            reloadSlotTextsFromConfig();
            reloadOnInit = false;
        }

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
        gridY = Math.max(70, height / 8);              // top margin scales with height; min 70 to clear 3 button rows (ends y=47) + label

        // Palette: fills remaining space between grid and right edge
        int rightEdgeMargin = Math.max(10, width / 50);
        paletteX = gridX + 9 * SLOT_W + Math.max(10, width / 60);
        paletteW = width - paletteX - rightEdgeMargin;
        paletteW = Math.max(140, Math.min(260, paletteW)); // clamp 140-260px
        paletteX = width - paletteW - rightEdgeMargin;    // anchor to right edge
        paletteY = gridY + 4;
        paletteH = height - paletteY - 44;

        // Search field above palette
        searchField = new EditBox(font, paletteX, paletteY - 16, paletteW, 14, Component.literal("Search..."));
        searchField.setMaxLength(50);
        searchField.setHint(Component.literal("Search items..."));
        searchField.setResponder(text -> applyFilter());
        addRenderableWidget(searchField);

        buildAllEntries();
        lastSearch = "\uFFFF"; // force applyFilter to rebuild filteredEntries
        applyFilter();
        updatePaletteScroll();
        rebuildPaletteButtons();

        // Bottom buttons (a 7th, "Warehouse", appears when the warehouse subsystem is available).
        int btnY = height - 28;
        int btnW = 60;
        boolean whAvail = com.example.inventoryorganizer.warehouse.WarehouseClient.isAvailable();
        int btnCount = whAvail ? 7 : 6;
        int totalW = btnW * btnCount + (btnCount - 1) * 3;
        int startX = width / 2 - totalW / 2;

        if (transientPreset != null) {
            // Trash / shared-edit editor: a focused screen — just Save + Back (the Kits/Names/Ranks/
            // Warehouse buttons are config-screen tools and don't belong here). Back returns to the
            // screen that opened it (the Storage section).
            int tw = 70, tgap = 8;
            int tStart = width / 2 - (tw * 2 + tgap) / 2;
            addRenderableWidget(StyledButton.styledBuilder(Component.translatable("inventory-organizer.button.save"), btn -> {
                saveToConfig();
                Minecraft.getInstance().setScreen(parent);
            }).bounds(tStart, btnY, tw, 20).build());
            addRenderableWidget(StyledButton.styledBuilder(Component.translatable("inventory-organizer.button.back"), btn ->
                Minecraft.getInstance().setScreen(parent)
            ).bounds(tStart + tw + tgap, btnY, tw, 20).build());
        } else {
            addRenderableWidget(StyledButton.styledBuilder(Component.translatable("inventory-organizer.button.save"), btn -> {
                saveToConfig();
                Minecraft.getInstance().setScreen(parent);
            }).bounds(startX, btnY, btnW, 20).build());

            addRenderableWidget(StyledButton.styledBuilder(Component.translatable("inventory-organizer.button.clear_short"), btn -> {
                if (showStorage) {
                    // In storage mode, Clear must reset the CHEST/profile currently being edited — not
                    // the player inventory.
                    int size = activeStorage().getSize();
                    for (int i = 0; i < size; i++) storageSlotRules[i] = "any";
                    storageSelectedSlot = -1;
                    saveStoragePreset();
                } else {
                    for (int i = 0; i < 36; i++) { slotTexts[i] = "any"; slotRefill[i] = false; }
                    saveToConfig();
                }
            }).bounds(startX + btnW + 3, btnY, btnW, 20).build());

            // Kits opens with THIS screen as parent, so Back from Kits returns here (not the grandparent).
            addRenderableWidget(StyledButton.styledBuilder(Component.translatable("inventory-organizer.button.kits"), btn -> {
                saveToConfig();
                reloadOnInit = true; // Kits may load a kit into the config; re-read it on return.
                Minecraft.getInstance().setScreen(new KitsScreen(this, autoSwitchView));
            }).bounds(startX + (btnW + 3) * 2, btnY, btnW, 20).build());

            addRenderableWidget(StyledButton.styledBuilder(Component.translatable("inventory-organizer.button.names"), btn -> {
                saveToConfig();
                Minecraft.getInstance().setScreen(ConfigScreenBuilder.build((Screen)(Object)this));
            }).bounds(startX + (btnW + 3) * 3, btnY, btnW, 20).build());

            addRenderableWidget(StyledButton.styledBuilder(Component.translatable("inventory-organizer.button.ranks"), btn -> {
                saveToConfig();
                if (showStorage) {
                    // Key tiers by stable profile id (not list index) so StorageSorter reads the same data.
                    String tierKey = "tier_order_storage_" + activeStorage().getId();
                    int sRows = activeStorage().getSize() / 9;
                    String[] storageRulesCopy = java.util.Arrays.copyOf(storageSlotRules, sRows * 9);
                    Minecraft.getInstance().setScreen(
                        new SortingOrderConfigScreen(this, storageRulesCopy, new String[]{"any","any","any","any","any"}, tierKey, true, sRows));
                } else {
                    Minecraft.getInstance().setScreen(new SortingOrderConfigScreen(this, slotTexts, equipTexts));
                }
            }).bounds(startX + (btnW + 3) * 4, btnY, btnW, 20).build());

            addRenderableWidget(StyledButton.styledBuilder(Component.translatable("inventory-organizer.button.back"), btn -> {
                Minecraft.getInstance().setScreen(parent);
            }).bounds(startX + (btnW + 3) * 5, btnY, btnW, 20).build());

            if (whAvail) {
                addRenderableWidget(StyledButton.styledBuilder(
                    Component.translatable("inventory-organizer.warehouse.button"), btn -> {
                        saveToConfig();
                        Minecraft.getInstance().setScreen(new com.example.inventoryorganizer.warehouse.WarehouseMapScreen(this));
                    }).bounds(startX + (btnW + 3) * 6, btnY, btnW, 20).build());
            }
        }

        // Tier Order warning + Solve button (shown when rules are set but tier order is not configured)
        solveButton = StyledButton.styledBuilder(Component.literal("Solve"), btn -> {
            config.getPreferences().remove("tier_order");
            config.applyDefaultTierOrder();
            config.save();
        }).bounds(startX, btnY - 18, 55, 14).build();
        solveButton.visible = false;
        addRenderableWidget(solveButton);

        // "?" toggles a short, in-place help overlay for THIS screen (quick tips without leaving).
        // The "Guide" button still opens the full, detailed HelpScreen.
        addRenderableWidget(StyledButton.styledBuilder(Component.literal("?"), btn ->
            inlineHelpOpen = !inlineHelpOpen
        ).bounds(width - 24, 4, 20, 18).build());
        if (transientPreset == null) {
            addRenderableWidget(StyledButton.styledBuilder(Component.translatable("inventory-organizer.button.guide"), btn ->
                Minecraft.getInstance().setScreen(new HelpScreen(this))
            ).bounds(width - 24 - 4 - 52, 4, 52, 18).build());
        }

        // "Special" button — opens the Special Settings sub-screen (sort action + whitelist).
        // Placed LEFT of the Guide button (was overlapping it, so clicks on the right half of
        // "Special" were caught by Guide and opened the guide instead of the settings).
        // Hidden in the focused Trash editor (transient) — it's a config-wide tool.
        if (transientPreset == null) {
            addRenderableWidget(StyledButton.styledBuilder(Component.translatable("inventory-organizer.button.special"), btn -> {
                saveToConfig();
                Minecraft.getInstance().setScreen(new SpecialSettingsScreen(this));
            }).bounds(width - 140, 4, 56, 18).build());
        }

        // Master quick-switch — a fast preset that flips all server-sensitive toggles at once.
        // The individual toggles in Special Settings still work; this is just a shortcut.
        // Locked during fight mode. Hidden in the focused Trash editor (transient).
        if (transientPreset == null) {
            masterModeBtn = StyledButton.styledBuilder(Component.literal(masterModeLabel()), btn -> {
                if (com.example.inventoryorganizer.FightModeTracker.isCombatActive()) return; // locked in fight
                if (isServerFriendlyPreset()) {
                    // Currently Server-Friendly → switch to Free preset.
                    config.setKeybindMode("free");
                    config.setDeathSortEnabled(true);
                } else {
                    // Free or Custom → switch to Server-Friendly preset.
                    config.setKeybindMode("inventory_only");
                    config.setDeathSortEnabled(false);
                }
                config.save();
            }).bounds(width - 244, 4, 100, 18).build();
            addRenderableWidget(masterModeBtn);
        }

        // --- Mode toggle buttons: [Inv] [Storage] [Groups] at top-left ---
        // Hidden in the focused Trash editor (transient): there are no Storage tabs there, so switching
        // would drop you into an empty, tab-less Storage view. Use Back to leave the Trash editor.
        if (transientPreset == null) {
            addRenderableWidget(StyledButton.styledBuilder(Component.translatable("inventory-organizer.button.inv"),
                btn -> switchMode(false)
            ).bounds(4, 4, 38, 13).build());
            addRenderableWidget(StyledButton.styledBuilder(Component.translatable("inventory-organizer.button.storage"),
                btn -> switchMode(true)
            ).bounds(44, 4, 62, 13).build());
            addRenderableWidget(StyledButton.styledBuilder(Component.translatable("inventory-organizer.button.groups"),
                btn -> { saveToConfig(); Minecraft.getInstance().setScreen(new CustomGroupListScreen(this)); }
            ).bounds(108, 4, 60, 13).build());

            // --- Auto-switch mode toggle + placement controls (inventory mode only) ---
            autoSwitchView = config.isSwitchEnabled();
            switchModeBtn = addRenderableWidget(StyledButton.styledBuilder(switchModeLabel(),
                btn -> {
                    saveToConfig();                 // persist the current view's edits first
                    autoSwitchView = !autoSwitchView;
                    config.setSwitchEnabled(autoSwitchView);
                    if (autoSwitchView) ensureSwitchDefaults();
                    config.save();
                    reloadSlotTextsFromConfig();     // load the other rule set into the grid
                    switchPlaceMode = 0;
                    switchModeBtn.setMessage(switchModeLabel());
                    if (switchCopyBtn != null) switchCopyBtn.setMessage(switchCopyLabel());
                    updateSwitchWidgetsVisibility();
                }).bounds(172, 4, 92, 13).build());
            placeSwitchBtn = addRenderableWidget(StyledButton.styledBuilder(
                Component.translatable("inventory-organizer.switch.move_switch"),
                btn -> switchPlaceMode = (switchPlaceMode == 1 ? 0 : 1)).bounds(4, 19, 120, 13).build());
            switchSetupBtn = addRenderableWidget(StyledButton.styledBuilder(
                Component.translatable("inventory-organizer.switch.setup"),
                btn -> { saveToConfig(); Minecraft.getInstance().setScreen(new SwitchRanksScreen(this)); })
                .bounds(128, 19, 120, 13).build());
            switchTriggerBtn = addRenderableWidget(StyledButton.styledBuilder(switchTriggerLabel(),
                btn -> {
                    boolean wasAuto = config.isSwitchTriggerAuto();
                    config.setSwitchTrigger(wasAuto ? "button" : "auto");
                    config.save();
                    btn.setMessage(switchTriggerLabel());
                }).bounds(4, 34, 120, 13).build());
            switchCopyBtn = addRenderableWidget(StyledButton.styledBuilder(switchCopyLabel(),
                btn -> {
                    saveToConfig();
                    config.copyRulesToOtherMode(autoSwitchView);
                    config.save();
                    reloadSlotTextsFromConfig();
                }).bounds(128, 34, 120, 13).build());
            updateSwitchWidgetsVisibility();
        }

        // --- Storage default tabs + Profiles + Trash (aligned to storage panel, no armor column) ---
        // Only the three protected defaults are tabs here ("the basics"); per-chest profiles are
        // managed in ChestProfileListScreen via the "Profiles…" button. The Trash/Void editor lives
        // here too (under Storage) — it's a storage-style 54-slot rule grid, so it belongs with storage.
        int panelLx = gridX - 8;
        int panelTy = gridY - 20;
        int panelTw = 9 * SLOT_W + 16;
        int nCells = OrganizerConfig.DEFAULT_COUNT + 2; // 3 default tabs + Profiles + Trash
        storageTabBtns = new Button[OrganizerConfig.DEFAULT_COUNT];
        int tabW3 = panelTw / nCells - 2;
        // Shared-edit (transient) mode: no config tabs — there's exactly one profile to edit.
        if (transientPreset == null) {
            for (int i = 0; i < OrganizerConfig.DEFAULT_COUNT; i++) {
                final int idx = i;
                String tabLabel = config.getStoragePresets().get(i).getName();
                storageTabBtns[i] = addRenderableWidget(StyledButton.styledBuilder(
                    Component.literal(tabLabel),
                    btn -> { saveStoragePreset(); activeStoragePreset = idx; loadStoragePreset(); }
                ).bounds(panelLx + i * (tabW3 + 2), panelTy - 15, tabW3, 13).build());
            }
            storageProfilesBtn = addRenderableWidget(StyledButton.styledBuilder(
                Component.translatable("inventory-organizer.profile.profiles_btn"),
                btn -> { saveStoragePreset(); Minecraft.getInstance().setScreen(new ChestProfileListScreen(this)); }
            ).bounds(panelLx + OrganizerConfig.DEFAULT_COUNT * (tabW3 + 2), panelTy - 15, tabW3, 13).build());
            // Trash/Void editor — only reachable from the Storage section now.
            trashTabBtn = addRenderableWidget(StyledButton.styledBuilder(Component.translatable("inventory-organizer.button.trash"),
                btn -> { saveToConfig(); Minecraft.getInstance().setScreen(buildTrashEditor()); }
            ).bounds(panelLx + (OrganizerConfig.DEFAULT_COUNT + 1) * (tabW3 + 2), panelTy - 15, tabW3, 13).build());
        }

        // Load storage data and apply initial visibility
        loadStoragePreset();
        updateStorageWidgetsVisibility();

        // First time the settings are opened, drop the user into the full feature guide.
        if (transientPreset == null && !config.isHelpSeen()) pendingFirstHelp = true;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
        super.extractRenderState(context, mouseX, mouseY, delta);

        // First settings entry → open the full guide once (done here, not in init, so setScreen is safe).
        if (pendingFirstHelp) {
            pendingFirstHelp = false;
            config.setHelpSeen(true);
            config.save();
            Minecraft.getInstance().setScreen(new HelpScreen(this));
            return;
        }

        if (showStorage) {
            // Storage mode: 12px margin sides/bottom, +12px on top for label at gridY-12
            int storageRows = activeStorage().getSize() / 9;
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
            ? ("\u00a7eStorage: \u00a7f" + activeStorage().getName())
            : "Inventory Slot Config";
        context.centeredText(font, Component.literal(modeTitle), width / 2, 4, 0xFFFFFFFF);
        // Mode indicator — bright border around the active tab (NOT a solid fill, which
        // would cover the button text since super.extractRenderState renders widgets first).
        // Skipped in the Trash editor (transient) where the top tabs are hidden.
        if (transientPreset == null) {
            int hx1, hx2;
            // Match the actual top-tab bounds: Inv (x=4,w=38), Storage (x=44,w=62).
            if (!showStorage) { hx1 = 4;  hx2 = 42; }
            else              { hx1 = 44; hx2 = 106; }
            int hy1 = 4, hy2 = 17;
            int color = 0xFF00AAFF;
            context.fill(hx1,     hy1,     hx2,     hy1 + 1, color); // top
            context.fill(hx1,     hy2 - 1, hx2,     hy2,     color); // bottom
            context.fill(hx1,     hy1,     hx1 + 1, hy2,     color); // left
            context.fill(hx2 - 1, hy1,     hx2,     hy2,     color); // right
        }

        // Storage tab highlight (aligned to storage panel) — only for the default tabs.
        // A per-chest profile (index >= DEFAULT_COUNT) is being edited "off-tab"; its name is shown
        // in the mode title instead, so we skip the highlight to avoid drawing it off-screen.
        if (showStorage && transientPreset == null && activeStoragePreset < OrganizerConfig.DEFAULT_COUNT) {
            int panelLx2 = gridX - 8;
            int panelTy2 = gridY - 20;
            int panelTw2 = 9 * SLOT_W + 16;
            // Must match the storage-tab layout (3 defaults + Profiles + Trash = DEFAULT_COUNT + 2).
            int tw2 = panelTw2 / (OrganizerConfig.DEFAULT_COUNT + 2) - 2;
            int hx = panelLx2 + activeStoragePreset * (tw2 + 2);
            // Draw a BORDER (not a solid fill) like the top tabs above: super.extractRenderState renders
            // the tab button first, so a solid fill here would cover the tab's label.
            int hy1 = panelTy2 - 16, hy2 = panelTy2 - 1, hcol = 0xFF55AAFF;
            context.fill(hx - 1,   hy1,     hx + tw2 + 1, hy1 + 1, hcol); // top
            context.fill(hx - 1,   hy2 - 1, hx + tw2 + 1, hy2,     hcol); // bottom
            context.fill(hx - 1,   hy1,     hx,           hy2,     hcol); // left
            context.fill(hx + tw2, hy1,     hx + tw2 + 1, hy2,     hcol); // right
        }
        context.text(font, Component.literal("Select a rule from the list, then click a slot to assign it."), width / 2 - 150, 16, 0xFFAAAAAA);
        // (The old "full inventory may be buggy" warning was removed from the slot menu — the rebuilt
        // verify+retry sorter handles a full inventory reliably and shows a precise "no room for X" message
        // only when an item genuinely can't be placed.)

        // Keep the master switch label/lock state live (fight mode counts down each frame).
        if (masterModeBtn != null) {
            masterModeBtn.setMessage(Component.literal(masterModeLabel()));
            masterModeBtn.active = !com.example.inventoryorganizer.FightModeTracker.isCombatActive();
        }

        if (showStorage) {
            drawStorageGrid(context, mouseX, mouseY);
        } else {
            drawInventoryGrid(context, mouseX, mouseY);
        }
        drawPaletteExtras(context, mouseX, mouseY);
        drawInlineHelp(context); // short in-place tips (toggled by "?"); full guide is the Guide button

        // Show selected rule as cursor text
        if (selectedLabel != null) {
            context.text(font, Component.literal(selectedLabel),
                    mouseX + 12, mouseY - 4, 0xFF55FF55);
        }

        drawSlotTooltip(context, mouseX, mouseY);

        // Check search field changes every frame
        if (searchField != null) {
            String current = searchField.getValue().trim().toLowerCase();
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
            context.text(font,
                Component.literal("\u26a0 Tier Order is not configured \u2013 set it up or click Solve!"),
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

    private void drawStorageGrid(GuiGraphicsExtractor context, int mouseX, int mouseY) {
        StoragePreset preset = activeStorage();
        int size = preset.getSize();
        int rows = size / 9;
        context.text(font,
            Component.literal(preset.getName() + " (" + size + " slots)"), gridX, gridY - 12, 0xFFFFFF55);
        for (int row = 0; row < rows; row++) {
            for (int col = 0; col < 9; col++) {
                int slot = row * 9 + col;
                drawStorageSlot(context, gridX + col * SLOT_W, gridY + row * SLOT_H, slot, mouseX, mouseY);
            }
        }
    }

    private void drawStorageSlot(GuiGraphicsExtractor context, int x, int y, int slot, int mouseX, int mouseY) {
        String rule = storageSlotRules[slot];
        boolean hovered = mouseX >= x && mouseX < x + SLOT_W && mouseY >= y && mouseY < y + SLOT_H;
        boolean selected = slot == storageSelectedSlot;
        boolean hasRule = !rule.equals("any") && !rule.equals("empty");
        int innerColor = rule.equals("empty") ? 0xFF3D1515 : (hasRule ? 0xFF1A2E1A : 0xFF3D3D3D);
        drawMCSlot(context, x, y, innerColor, hovered, hasRule);
        if (selected) {
            context.fill(x + 3, y + 3, x + SLOT_W - 3, y + SLOT_H - 3, 0x4400AAFF);
            context.horizontalLine(x + 2, x + SLOT_W - 3, y + 2, 0xFF00AAFF);
            context.horizontalLine(x + 2, x + SLOT_W - 3, y + SLOT_H - 3, 0xFF00AAFF);
            context.verticalLine(x + 2, y + 2, y + SLOT_H - 3, 0xFF00AAFF);
            context.verticalLine(x + SLOT_W - 3, y + 2, y + SLOT_H - 3, 0xFF00AAFF);
        }
        Integer tier = config.getStorageTier(activeStorage().getId(), slot);
        if (tier != null) {
            context.text(font, Component.literal("\u00a7a" + tier), x + 2, y + 2, 0xFF55FF55);
        }
        ItemStack icon = getIconForText(rule);
        if (!icon.isEmpty()) VisualInventoryConfigScreen.drawItemIcon(context, icon, x + (SLOT_W - 16) / 2, y + (SLOT_H - 16) / 2);
        String displayText = getDisplayText(rule);
        int maxTextW = SLOT_W - 4;
        if (font.width(displayText) > maxTextW) {
            while (displayText.length() > 1 && font.width(displayText + ".") > maxTextW)
                displayText = displayText.substring(0, displayText.length() - 1);
            displayText += ".";
        }
        int tw = font.width(displayText);
        int textColor = rule.equals("empty") ? 0xFFAA4444 : rule.equals("any") ? 0xFF888888 : 0xFF5599FF;
        context.text(font, Component.literal(displayText),
            x + (SLOT_W - tw) / 2, y + 27, textColor);
    }

    private int getStorageSlotAt(int mx, int my) {
        int size = activeStorage().getSize();
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
        StoragePreset p = activeStorage();
        int size = p.getSize();
        for (int i = 0; i < 54; i++) storageSlotRules[i] = i < size ? p.getSlotRule(i) : "any";
        storageSelectedSlot = -1;
    }

    private void saveStoragePreset() {
        StoragePreset p = activeStorage();
        int size = p.getSize();
        for (int i = 0; i < size; i++) p.setSlotRule(i, storageSlotRules[i]);
        if (transientPreset != null) {
            // Shared-edit: don't persist to local config; hand the edited rules to the save callback.
            if (onTransientSave != null) onTransientSave.run();
        } else {
            config.save();
        }
    }

    /** True when the toggles are exactly the Server-Friendly preset. */
    private boolean isServerFriendlyPreset() {
        return "inventory_only".equals(config.getKeybindMode()) && !config.isDeathSortEnabled();
    }

    /** True when the toggles are exactly the Free preset. */
    private boolean isFreePreset() {
        return "free".equals(config.getKeybindMode()) && config.isDeathSortEnabled();
    }

    /** Dynamic label for the master quick-switch (reflects fight mode + environment + custom state). */
    private String masterModeLabel() {
        if (com.example.inventoryorganizer.FightModeTracker.isCombatActive()) {
            long s = (com.example.inventoryorganizer.FightModeTracker.remainingMs() / 1000) + 1;
            return "§cFight: SF " + s + "s";
        }
        if (com.example.inventoryorganizer.FightModeTracker.isSfForced()) {
            // Public server → Server-Friendly is forced permanently (no countdown).
            return "§eForced SF";
        }
        if (isFreePreset()) {
            return com.example.inventoryorganizer.ServerEnvironment.canUseFree()
                    ? "§aQuick: Free"
                    : "§eQuick: Free*";
        }
        if (isServerFriendlyPreset()) return "Quick: SF";
        return "§7Quick: Custom";
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
        for (Button tab : storageTabBtns) { if (tab != null) tab.visible = showStorage; }
        if (storageProfilesBtn != null) storageProfilesBtn.visible = showStorage;
        if (trashTabBtn != null) trashTabBtn.visible = showStorage; // Trash lives under Storage now
        updateSwitchWidgetsVisibility();
    }

    /** Auto-switch controls live in inventory mode; the placement buttons only when Auto switch is on. */
    private void updateSwitchWidgetsVisibility() {
        boolean inv = !showStorage;
        if (switchModeBtn != null) switchModeBtn.visible = inv;
        boolean place = inv && autoSwitchView;
        if (placeSwitchBtn != null) placeSwitchBtn.visible = place;
        if (switchSetupBtn != null) switchSetupBtn.visible = place;
        if (switchTriggerBtn != null) switchTriggerBtn.visible = place;
        if (switchCopyBtn != null) switchCopyBtn.visible = inv;
    }

    private net.minecraft.network.chat.Component switchModeLabel() {
        return Component.translatable(autoSwitchView
                ? "inventory-organizer.switch.mode_auto" : "inventory-organizer.switch.mode_plain");
    }

    private net.minecraft.network.chat.Component switchTriggerLabel() {
        return Component.translatable(config.isSwitchTriggerAuto()
                ? "inventory-organizer.switch.trigger_auto" : "inventory-organizer.switch.trigger_button");
    }

    private net.minecraft.network.chat.Component switchCopyLabel() {
        // "Copy → Plain" when viewing Auto, "Copy → Auto" when viewing Plain
        return Component.translatable(autoSwitchView
                ? "inventory-organizer.switch.copy_to_plain" : "inventory-organizer.switch.copy_to_auto");
    }

    /** Ensure the switch slot exists + the tool groups are seeded when Auto switch is enabled. */
    private void ensureSwitchDefaults() {
        if (config.getSwitchSlot() < 0 || config.getSwitchSlot() > 8) config.setSwitchSlot(8);
        config.materializeSwitchGroupsOnce();
    }

    /** Top-left pixel of an inventory slot (0-8 hotbar, 9-35 main grid); null if out of range. */
    private int[] switchSlotXY(int slot) {
        if (slot >= 9 && slot < 36) {
            int r = (slot - 9) / 9, c = (slot - 9) % 9;
            return new int[]{gridX + c * SLOT_W, gridY + r * SLOT_H};
        }
        if (slot >= 0 && slot < 9) {
            int hotbarY = gridY + 3 * SLOT_H + 14;
            return new int[]{gridX + slot * SLOT_W, hotbarY};
        }
        return null;
    }

    private void drawRoleMarker(GuiGraphicsExtractor context, int slot, int color, String letter) {
        int[] xy = switchSlotXY(slot);
        if (xy == null) return;
        int x = xy[0], y = xy[1];
        context.horizontalLine(x, x + SLOT_W - 1, y, color);
        context.horizontalLine(x, x + SLOT_W - 1, y + 1, color);
        context.horizontalLine(x, x + SLOT_W - 1, y + SLOT_H - 1, color);
        context.horizontalLine(x, x + SLOT_W - 1, y + SLOT_H - 2, color);
        context.verticalLine(x, y, y + SLOT_H - 1, color);
        context.verticalLine(x + 1, y, y + SLOT_H - 1, color);
        context.verticalLine(x + SLOT_W - 1, y, y + SLOT_H - 1, color);
        context.verticalLine(x + SLOT_W - 2, y, y + SLOT_H - 1, color);
        context.text(font, Component.literal(letter), x + 2, y + 2, color);
    }

    /** Resolve a quick-help line to the active language. */
    private static String qh(String key) {
        return Component.translatable("inventory-organizer.qhelp." + key).getString();
    }

    /** Short, in-place help overlay for this screen (toggled by "?"); the full guide is its own screen. */
    private void drawInlineHelp(GuiGraphicsExtractor context) {
        if (!inlineHelpOpen) return;
        java.util.List<String> lines = new java.util.ArrayList<>();
        if (showStorage) {
            lines.add(qh("storage.head"));
            lines.add(qh("storage.l1"));
            lines.add(qh("storage.l2"));
            lines.add(qh("storage.l3"));
            lines.add(qh("storage.l4"));
            lines.add(qh("storage.l5"));
        } else {
            lines.add(qh("inv.head"));
            lines.add(qh("inv.l1"));
            lines.add(qh("inv.l2"));
            lines.add(qh("inv.l3"));
            lines.add(qh("inv.l4"));
            lines.add(qh("inv.l5"));
        }
        lines.add("");
        lines.add(qh("footer"));

        // Word-wrap every line to the panel's inner width so nothing spills out the side
        // (some tip lines + the warning are long, and translations are longer still).
        int w = Math.min(360, width - 20);
        int inner = w - 16;
        java.util.List<net.minecraft.util.FormattedCharSequence> wrapped = new java.util.ArrayList<>();
        for (String s : lines) {
            if (s.isEmpty()) { wrapped.add(net.minecraft.util.FormattedCharSequence.EMPTY); continue; }
            wrapped.addAll(font.split(Component.literal(s), inner));
        }
        int h = wrapped.size() * 11 + 12;
        int x = (width - w) / 2;
        int y = (height - h) / 2;
        // Dim the screen behind, then a bordered panel.
        context.fill(0, 0, width, height, 0x88000000);
        context.fill(x - 2, y - 2, x + w + 2, y + h + 2, 0xFF3A3A5A);
        context.fill(x, y, x + w, y + h, 0xFF161622);
        int ly = y + 6;
        for (net.minecraft.util.FormattedCharSequence seq : wrapped) {
            context.text(font, seq, x + 8, ly, 0xFFFFFFFF);
            ly += 11;
        }
    }

    private void drawInventoryGrid(GuiGraphicsExtractor context, int mouseX, int mouseY) {
        context.text(font, Component.literal("Inventory (slots 9-35)"), gridX, gridY - 12, 0xFFFFFF55);

        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                int slot = 9 + row * 9 + col;
                drawSlot(context, gridX + col * SLOT_W, gridY + row * SLOT_H, slot, mouseX, mouseY);
            }
        }

        int hotbarY = gridY + 3 * SLOT_H + 14;
        context.text(font, Component.literal("Hotbar (slots 0-8)"), gridX, hotbarY - 12, 0xFFFFFF55);
        for (int col = 0; col < 9; col++) {
            drawSlot(context, gridX + col * SLOT_W, hotbarY, col, mouseX, mouseY);
        }

        // Always-visible auto-refill hint (so it's discoverable without opening the help guide).
        context.text(font, Component.literal("§a[R] §7Middle-click a hotbar slot = auto-refill"),
                gridX, gridY + 3 * SLOT_H + 14 + SLOT_H + 3, 0xFFAAAAAA);

        // Armor slots (left of grid): helmet, chestplate, leggings, boots
        int armorX = gridX - SLOT_W - 8;
        context.text(font, Component.literal("Armor"), armorX, gridY - 12, 0xFF55FFFF);
        for (int i = 0; i < 4; i++) {
            drawEquipSlot(context, armorX, gridY + i * SLOT_H, i, mouseX, mouseY);
        }

        // Offhand slot (below armor) — its label ("Offhand") is drawn under the slot by drawEquipSlot,
        // so no separate header here (the old one overlapped the Boots label).
        int offhandY = gridY + 4 * SLOT_H + 4;
        drawEquipSlot(context, armorX, offhandY, 4, mouseX, mouseY);

        // Auto-switch overlay: mark the switch slot (gold "S").
        if (autoSwitchView) {
            int sw = config.getSwitchSlot();
            if (sw >= 0) drawRoleMarker(context, sw, 0xFFFFD24A, "S");
            if (switchPlaceMode == 1) context.text(font,
                    Component.translatable("inventory-organizer.switch.click_hotbar"), gridX, gridY - 24, 0xFFFFD24A);
        }
    }

    private void drawEquipSlot(GuiGraphicsExtractor context, int x, int y, int equipIdx, int mouseX, int mouseY) {
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
        if (!icon.isEmpty()) VisualInventoryConfigScreen.drawItemIcon(context, icon, x + (SLOT_W - 16) / 2, y + (SLOT_H - 16) / 2);

        String displayText = text.equals("any") ? EQUIP_LABELS[equipIdx] : getDisplayText(text);
        int maxTextW = SLOT_W - 4;
        if (font.width(displayText) > maxTextW) {
            while (displayText.length() > 1 && font.width(displayText + ".") > maxTextW)
                displayText = displayText.substring(0, displayText.length() - 1);
            displayText += ".";
        }
        int textW = font.width(displayText);
        int textColor = text.equals("empty") ? 0xFFAA4444 : text.equals("any") ? 0xFF888888 : 0xFF5599FF;
        context.text(font, Component.literal(displayText),
                x + (SLOT_W - textW) / 2, y + 27, textColor);
    }

    private ItemStack getDefaultEquipIcon(int equipIdx) {
        try {
            switch (equipIdx) {
                case 0: return safeIcon(Items.IRON_HELMET);
                case 1: return safeIcon(Items.IRON_CHESTPLATE);
                case 2: return safeIcon(Items.IRON_LEGGINGS);
                case 3: return safeIcon(Items.IRON_BOOTS);
                case 4: return safeIcon(Items.SHIELD);
                default: return ItemStack.EMPTY;
            }
        } catch (NullPointerException e) {
            return ItemStack.EMPTY;
        }
    }

    /** Draw a Minecraft-style sunken slot with 3D bevel effect */
    private void drawMCSlot(GuiGraphicsExtractor context, int x, int y, int innerColor, boolean hovered, boolean hasRule) {
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
            context.horizontalLine(x + 2, x + SLOT_W - 3, y + 2, 0xFFFFFF00);
            context.horizontalLine(x + 2, x + SLOT_W - 3, y + SLOT_H - 3, 0xFFFFFF00);
            context.verticalLine(x + 2, y + 2, y + SLOT_H - 3, 0xFFFFFF00);
            context.verticalLine(x + SLOT_W - 3, y + 2, y + SLOT_H - 3, 0xFFFFFF00);
        }
    }

    private void drawSlot(GuiGraphicsExtractor context, int x, int y, int slot, int mouseX, int mouseY) {
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
            VisualInventoryConfigScreen.drawItemIcon(context, icon, x + (SLOT_W - 16) / 2, y + (SLOT_H - 16) / 2);
        }

        // Label below icon
        String displayText = getDisplayText(text);
        int maxTextW = SLOT_W - 4;
        if (font.width(displayText) > maxTextW) {
            while (displayText.length() > 1 && font.width(displayText + ".") > maxTextW)
                displayText = displayText.substring(0, displayText.length() - 1);
            displayText += ".";
        }
        int textW = font.width(displayText);
        int textColor = text.equals("empty") ? 0xFFAA4444 : text.equals("any") ? 0xFF888888 : 0xFF55CC55;
        context.text(font, Component.literal(displayText),
                x + (SLOT_W - textW) / 2, y + 27, textColor);

        // Auto-refill marker: a bright green border around the whole slot + an "R" corner badge, so it's
        // unmistakable (the recycle glyph doesn't render in Minecraft's font).
        if (slot >= 0 && slot < 36 && slotRefill[slot]) {
            int g = 0xFF33DD33;
            context.fill(x, y, x + SLOT_W, y + 2, g);                       // top
            context.fill(x, y + SLOT_H - 2, x + SLOT_W, y + SLOT_H, g);     // bottom
            context.fill(x, y, x + 2, y + SLOT_H, g);                       // left
            context.fill(x + SLOT_W - 2, y, x + SLOT_W, y + SLOT_H, g);     // right
            context.fill(x + 1, y + 1, x + 10, y + 10, 0xFF1E8E1E);
            context.text(font, Component.literal("§fR"), x + 2, y + 1, 0xFFFFFFFF);
        }
    }

    /** Get an item icon for a rule text */
    private ItemStack getIconForText(String text) {
        try {
            return getIconForTextUnsafe(text);
        } catch (NullPointerException e) {
            // 26.1 sometimes throws "Components not bound yet" if accessed too early.
            // Fall back to empty icon — text label still shows.
            return ItemStack.EMPTY;
        }
    }

    private ItemStack getIconForTextUnsafe(String text) {
        if (text.equals("empty")) return safeIcon(Items.BARRIER);
        if (text.equals("any")) return ItemStack.EMPTY;
        if (text.startsWith("g:")) {
            switch (text.substring(2)) {
                case "weapons": return safeIcon(Items.IRON_SWORD);
                case "tools": return safeIcon(Items.IRON_PICKAXE);
                case "armor": return safeIcon(Items.IRON_CHESTPLATE);
                case "blocks": return safeIcon(Items.OAK_PLANKS);
                case "food": return safeIcon(Items.COOKED_BEEF);
                case "utility": return safeIcon(Items.TORCH);
                case "valuables": return safeIcon(Items.DIAMOND);
                case "potions":        return safeIcon(Items.POTION);
                case "splash_potions": return safeIcon(Items.SPLASH_POTION);
                case "arrows":         return safeIcon(Items.ARROW);
                case "misc":           return safeIcon(Items.ENDER_PEARL);
                case "logs":     return safeIcon(Items.OAK_LOG);
                case "boats":    return safeIcon(Items.OAK_BOAT);
                case "plants":   return safeIcon(Items.DANDELION);
                case "stone":    return safeIcon(Items.STONE);
                case "ores":     return safeIcon(Items.IRON_ORE);
                case "cooked":   return safeIcon(Items.COOKED_BEEF);
                case "rawfood":  return safeIcon(Items.BEEF);
                case "nether":   return safeIcon(Items.NETHERRACK);
                case "end":      return safeIcon(Items.END_STONE);
                case "partial":  return safeIcon(Items.OAK_SLAB);
                case "redstone": return safeIcon(Items.REDSTONE);
                case "creative": return safeIcon(Items.COMMAND_BLOCK);
                default: return ItemStack.EMPTY;
            }
        }
        if (text.startsWith("b:")) {
            // Bundle content slot: show bundle icon regardless of content
            return safeIcon(Items.BUNDLE);
        }
        if (text.startsWith("cg:")) {
            String name = text.substring(3);
            // User-chosen icon override (set via "Set Icon" in the group editor) takes priority.
            String[] iconPref = OrganizerConfig.get().getPreference("cg_icon_" + name);
            if (iconPref != null && iconPref.length > 0 && iconPref[0] != null && !iconPref[0].isEmpty()) {
                try {
                    String mid = iconPref[0].contains(":") ? iconPref[0] : "minecraft:" + iconPref[0];
                    Item it = BuiltInRegistries.ITEM.getValue(Identifier.parse(mid));
                    if (it != null && it != Items.AIR) return new ItemStack(net.minecraft.core.Holder.direct(it));
                } catch (Exception ignored) {}
            }
            // Built-in groups are materialized as custom groups but keep their iconic look.
            for (String bn : com.example.inventoryorganizer.InventorySorter.BUILTIN_GROUP_NAMES) {
                if (bn.equals(name)) return getIconForTextUnsafe("g:" + name);
            }
            // Real custom group: show its first member item, falling back to a chest.
            java.util.List<String> members = OrganizerConfig.get().getCustomGroup(name);
            if (!members.isEmpty() && members.get(0) != null && !members.get(0).isEmpty()) {
                try {
                    String mid = members.get(0).contains(":") ? members.get(0) : "minecraft:" + members.get(0);
                    Item it = BuiltInRegistries.ITEM.getValue(Identifier.parse(mid));
                    if (it != null && it != Items.AIR) return new ItemStack(net.minecraft.core.Holder.direct(it));
                } catch (Exception ignored) {}
            }
            return safeIcon(Items.CHEST);
        }
        if (text.startsWith("t:")) {
            switch (text.substring(2)) {
                case "sword": return safeIcon(Items.IRON_SWORD);
                case "pickaxe": return safeIcon(Items.IRON_PICKAXE);
                case "axe": return safeIcon(Items.IRON_AXE);
                case "shovel": return safeIcon(Items.IRON_SHOVEL);
                case "hoe": return safeIcon(Items.IRON_HOE);
                case "bow": return safeIcon(Items.BOW);
                case "crossbow": return safeIcon(Items.CROSSBOW);
                case "trident": return safeIcon(Items.TRIDENT);
                case "mace": return safeIcon(Items.MACE);
                case "helmet": return safeIcon(Items.IRON_HELMET);
                case "chestplate": return safeIcon(Items.IRON_CHESTPLATE);
                case "leggings": return safeIcon(Items.IRON_LEGGINGS);
                case "boots": return safeIcon(Items.IRON_BOOTS);
                case "shield": return safeIcon(Items.SHIELD);
                case "elytra": return safeIcon(Items.ELYTRA);
                case "fishing_rod": return safeIcon(Items.FISHING_ROD);
                case "shears": return safeIcon(Items.SHEARS);
                case "flint_and_steel":  return safeIcon(Items.FLINT_AND_STEEL);
                case "potion":           return safeIcon(Items.POTION);
                case "splash_potion":    return safeIcon(Items.SPLASH_POTION);
                case "lingering_potion": return safeIcon(Items.LINGERING_POTION);
                case "arrow":            return safeIcon(Items.ARROW);
                case "spectral_arrow":   return safeIcon(Items.SPECTRAL_ARROW);
                case "tipped_arrow":     return safeIcon(Items.TIPPED_ARROW);
                default: return ItemStack.EMPTY;
            }
        }
        // Specific potion effect (pot:healing) — generic potion icon (the label names the effect).
        if (text.startsWith("pot:")) {
            return safeIcon(Items.POTION);
        }
        // Specific item ID
        if (text.contains(":")) {
            try {
                Identifier id = Identifier.parse(text);
                Item item = BuiltInRegistries.ITEM.getValue(id);
                if (item != null && item != Items.AIR) return new ItemStack(net.minecraft.core.Holder.direct(item));
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
        if (text.startsWith("pot:")) return text.substring(4);
        if (text.startsWith("cg:")) return text.substring(3);
        // Specific item: show just the item name part
        if (text.contains(":")) {
            String path = text.substring(text.indexOf(':') + 1);
            return path.replace('_', ' ');
        }
        return text;
    }

    private void rebuildPaletteButtons() {
        for (Button btn : paletteButtons) removeWidget(btn);
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
            Button btn = Button.builder(
                    Component.literal("         " + shortLabel),
                    b -> {
                        if (selectedText != null && selectedText.equals(clickedEntry.ruleText)) {
                            selectedText = null;
                            selectedLabel = null;
                        } else {
                            selectedText = clickedEntry.ruleText;
                            selectedLabel = clickedEntry.label;
                        }
                    }
            ).bounds(paletteX, py, paletteW, 20).build(); // Increased button height from 15 to 20
            paletteButtons.add(btn);
            addRenderableWidget(btn);
        }
    }

    private void drawPaletteExtras(GuiGraphicsExtractor context, int mouseX, int mouseY) {
        int rowHeight = 22; // Match rebuildPaletteButtons
        int visibleRows = paletteH / rowHeight;
        int startIdx = paletteScroll;
        int endIdx = Math.min(filteredEntries.size(), startIdx + visibleRows);

        // Draw headers
        for (int i = startIdx; i < endIdx; i++) {
            PaletteEntry entry = filteredEntries.get(i);
            int py = paletteY + (i - startIdx) * rowHeight; // Match new spacing
            if (entry.isHeader) {
                context.text(font, Component.literal(entry.label), paletteX + 2, py + 6, 0xFFFFFF55);
            }
        }

        // Draw item icons on the left side of each palette button
        int btnIdx = 0;
        for (int i = startIdx; i < endIdx; i++) {
            PaletteEntry entry = filteredEntries.get(i);
            if (entry.isHeader) continue;
            if (btnIdx >= paletteButtons.size()) break;
            Button btn = paletteButtons.get(btnIdx);
            ItemStack icon = getIconForText(entry.ruleText);
            if (!icon.isEmpty()) {
                // Draw 16x16 icon on the left inner edge of the button
                VisualInventoryConfigScreen.drawItemIcon(context, icon, btn.getX() + 2, btn.getY() + 2);
            }
            btnIdx++;
        }

        // Green border on selected button
        for (Button btn : paletteButtons) {
            if (selectedText != null && btn.getMessage().getString().contains("[" + selectedText + "]")) {
                int bx = btn.getX(), by = btn.getY(), bw = btn.getWidth(), bh = btn.getHeight();
                context.horizontalLine(bx - 1, bx + bw, by - 1, 0xFF00FF00);
                context.horizontalLine(bx - 1, bx + bw, by + bh, 0xFF00FF00);
                context.verticalLine(bx - 1, by - 1, by + bh, 0xFF00FF00);
                context.verticalLine(bx + bw, by - 1, by + bh, 0xFF00FF00);
            }
        }

        // Scrollbar (draggable)
        if (maxPaletteScroll > 0) {
            int sbRowH = 22;
            int sbVisibleRows = paletteH / sbRowH;
            int totalRows = filteredEntries.size();
            int sbX = paletteX + paletteW - SCROLLBAR_WIDTH + 7;
            int sbH = Math.max(20, paletteH * sbVisibleRows / Math.max(1, totalRows));
            int sbY = paletteY + (int)((float)paletteScroll / maxPaletteScroll * (paletteH - sbH));
            context.fill(sbX, paletteY, sbX + SCROLLBAR_WIDTH, paletteY + paletteH, 0xFF1A1A1A);
            boolean hov = mouseX >= sbX && mouseX < sbX + SCROLLBAR_WIDTH && mouseY >= sbY && mouseY < sbY + sbH;
            int color = (draggingScrollbar || hov) ? 0xFFCCCCCC : 0xFF888888;
            context.fill(sbX, sbY, sbX + SCROLLBAR_WIDTH, sbY + sbH, color);
        }

        // Item count
        context.text(font, Component.literal(filteredEntries.size() + " items"),
                paletteX, paletteY + paletteH + 2, 0xFF888888);
    }

    /** Returns {sbX, trackTop, trackBottom, handleY, handleH} or null if no scroll needed. */
    private int[] getScrollbarRect() {
        if (maxPaletteScroll <= 0) return null;
        int rowHeight = 22;
        int visibleRows = paletteH / rowHeight;
        int totalRows = filteredEntries.size();
        int sbH = Math.max(20, paletteH * visibleRows / Math.max(1, totalRows));
        int sbY = paletteY + (int)((float)paletteScroll / maxPaletteScroll * (paletteH - sbH));
        int sbX = paletteX + paletteW - SCROLLBAR_WIDTH + 7;
        return new int[]{sbX, paletteY, paletteY + paletteH, sbY, sbH};
    }

    private void scrollbarDragTo(double mouseY) {
        int[] r = getScrollbarRect();
        if (r == null) return;
        int trackTop = r[1], trackBottom = r[2], handleH = r[4];
        int trackHeight = trackBottom - trackTop;
        if (trackHeight <= handleH) return;
        double rel = (mouseY - trackTop - handleH / 2.0) / (trackHeight - handleH);
        rel = Math.max(0.0, Math.min(1.0, rel));
        int newScroll = (int)Math.round(rel * maxPaletteScroll);
        if (newScroll != paletteScroll) {
            paletteScroll = newScroll;
            rebuildPaletteButtons();
        }
    }

    @Override
    public boolean mouseDragged(net.minecraft.client.input.MouseButtonEvent click, double deltaX, double deltaY) {
        if (click.button() == 0 && draggingScrollbar) {
            scrollbarDragTo(click.y());
            return true;
        }
        return super.mouseDragged(click, deltaX, deltaY);
    }

    @Override
    public boolean mouseReleased(net.minecraft.client.input.MouseButtonEvent click) {
        if (click.button() == 0 && draggingScrollbar) {
            draggingScrollbar = false;
            return true;
        }
        return super.mouseReleased(click);
    }

    private void drawSlotTooltip(GuiGraphicsExtractor context, int mouseX, int mouseY) {
        int hoveredSlot = getHoveredSlot(mouseX, mouseY);
        if (hoveredSlot >= 0) {
            String text = slotTexts[hoveredSlot];
            String slotName = (hoveredSlot <= 8) ? "Hotbar " + (hoveredSlot + 1) :
                    "Row " + ((hoveredSlot - 9) / 9 + 1) + " Col " + ((hoveredSlot - 9) % 9 + 1);

            List<Component> tooltip = new ArrayList<>();
            tooltip.add(Component.literal("Slot " + hoveredSlot + " (" + slotName + ")"));
            tooltip.add(Component.literal("Rule: " + text));
            if (slotRefill[hoveredSlot]) tooltip.add(Component.literal("§bAuto-refill ON"));
            if (selectedText != null) {
                tooltip.add(Component.literal("Click to set: " + selectedText));
            } else {
                tooltip.add(Component.literal("Right-click = clear to 'any'"));
            }
            // Tooltip rendering disabled in 26.1 — GuiGraphicsExtractor has no direct tooltip method.
            // context.renderTooltip(font, tooltip, mouseX, mouseY);
            return;
        }
        // Equipment slot tooltip
        int equipIdx = getHoveredEquipSlot(mouseX, mouseY);
        if (equipIdx >= 0) {
            String text = equipTexts[equipIdx];
            List<Component> tooltip = new ArrayList<>();
            tooltip.add(Component.literal(EQUIP_LABELS[equipIdx] + " Slot"));
            tooltip.add(Component.literal("Rule: " + text));
            if (selectedText != null) {
                if (isValidForEquipSlot(equipIdx, selectedText)) {
                    tooltip.add(Component.literal("Click to set: " + selectedText));
                } else {
                    tooltip.add(Component.literal("\u00a7c" + selectedText + " cannot go here!"));
                }
            } else {
                tooltip.add(Component.literal("Right-click = clear to 'any'"));
            }
            // Tooltip rendering disabled in 26.1 — GuiGraphicsExtractor has no direct tooltip method.
            // context.renderTooltip(font, tooltip, mouseX, mouseY);
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
    public boolean mouseClicked(MouseButtonEvent click, boolean bl) {
        double mouseX = click.x();
        double mouseY = click.y();
        int button = click.button();

        // Inline help overlay is modal: any click (inside or outside) dismisses it.
        if (inlineHelpOpen) { inlineHelpOpen = false; return true; }

        // Scrollbar drag start
        if (button == 0) {
            int[] r = getScrollbarRect();
            if (r != null) {
                int sbX = r[0], trackTop = r[1], trackBottom = r[2], handleY = r[3], handleH = r[4];
                if (mouseX >= sbX && mouseX < sbX + SCROLLBAR_WIDTH && mouseY >= trackTop && mouseY < trackBottom) {
                    draggingScrollbar = true;
                    if (mouseY < handleY || mouseY >= handleY + handleH) scrollbarDragTo(mouseY);
                    return true;
                }
            }
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
                        Minecraft.getInstance().setScreen(new SortingOrderConfigScreen(this, slotTexts, equipTexts));
                        lastClickTime = 0; // Reset
                        return true;
                    }
                    lastClickTime = currentTime;
                    lastClickedSlot = hoveredSlot;
                }
            }
        }

        // Auto-switch placement: assign the switch slot on the next hotbar click.
        if (!showStorage && autoSwitchView && switchPlaceMode == 1) {
            int slot = getHoveredSlot((int) mouseX, (int) mouseY);
            if (slot >= 0) {
                if (slot <= 8) { config.setSwitchSlot(slot); config.save(); }
                switchPlaceMode = 0;
                return true;
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
            // Switch slot is reserved — no rule may be assigned to it in auto mode
            boolean isSwitchSlot = autoSwitchView && hoveredSlot == config.getSwitchSlot();
            if (button == 0 && selectedText != null) {
                if (!isSwitchSlot) { slotTexts[hoveredSlot] = selectedText; saveToConfig(); }
                return true;
            } else if (button == 1) {
                if (!isSwitchSlot) {
                    slotTexts[hoveredSlot] = "any";
                    saveToConfig();
                    selectedText = null;
                    selectedLabel = null;
                }
                return true;
            } else if (button == 2) {
                // Middle-click toggles auto-refill — only allowed on hotbar slots (0-8).
                if (hoveredSlot <= 8 && !isSwitchSlot) {
                    slotRefill[hoveredSlot] = !slotRefill[hoveredSlot];
                    saveToConfig();
                }
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
    private void drawDecoratedPanel(GuiGraphicsExtractor context, int x, int y, int width, int height) {
        // Dark background
        context.fill(x, y, x + width, y + height, 0xC0101010);
        
        // Outer border (dark)
        context.horizontalLine(x, x + width - 1, y, 0xFF000000);
        context.horizontalLine(x, x + width - 1, y + height - 1, 0xFF000000);
        context.verticalLine(x, y, y + height - 1, 0xFF000000);
        context.verticalLine(x + width - 1, y, y + height - 1, 0xFF000000);
        
        // Inner highlight (light gray)
        context.horizontalLine(x + 1, x + width - 2, y + 1, 0xFF555555);
        context.verticalLine(x + 1, y + 1, y + height - 2, 0xFF555555);
        
        // Bottom-right shadow (darker)
        context.horizontalLine(x + 1, x + width - 2, y + height - 2, 0xFF2A2A2A);
        context.verticalLine(x + width - 2, y + 1, y + height - 2, 0xFF2A2A2A);
        
        // Corner decorations (small dots for detail)
        context.fill(x + 2, y + 2, x + 4, y + 4, 0xFF888888);
        context.fill(x + width - 4, y + 2, x + width - 2, y + 4, 0xFF888888);
        context.fill(x + 2, y + height - 4, x + 4, y + height - 2, 0xFF888888);
        context.fill(x + width - 4, y + height - 4, x + width - 2, y + height - 2, 0xFF888888);
    }

    /** Draw a decorated border only (no dark background) */
    private void drawDecoratedBorder(GuiGraphicsExtractor context, int x, int y, int width, int height) {
        // Outer border (dark)
        context.horizontalLine(x, x + width - 1, y, 0xFF000000);
        context.horizontalLine(x, x + width - 1, y + height - 1, 0xFF000000);
        context.verticalLine(x, y, y + height - 1, 0xFF000000);
        context.verticalLine(x + width - 1, y, y + height - 1, 0xFF000000);
        
        // Inner highlight (light gray)
        context.horizontalLine(x + 1, x + width - 2, y + 1, 0xFF555555);
        context.verticalLine(x + 1, y + 1, y + height - 2, 0xFF555555);
        
        // Bottom-right shadow (darker)
        context.horizontalLine(x + 1, x + width - 2, y + height - 2, 0xFF2A2A2A);
        context.verticalLine(x + width - 2, y + 1, y + height - 2, 0xFF2A2A2A);
        
        // Corner decorations (small dots for detail)
        context.fill(x + 2, y + 2, x + 4, y + 4, 0xFF888888);
        context.fill(x + width - 4, y + 2, x + width - 2, y + 4, 0xFF888888);
        context.fill(x + 2, y + height - 4, x + 4, y + height - 2, 0xFF888888);
        context.fill(x + width - 4, y + height - 4, x + width - 2, y + height - 2, 0xFF888888);
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(parent);
    }

    /** Safely create ItemStack — returns EMPTY if components aren't bound yet (26.1 NPE workaround). */
    public static net.minecraft.world.item.ItemStack safeIcon(net.minecraft.world.item.Item item) {
        if (item == null) return net.minecraft.world.item.ItemStack.EMPTY;
        try {
            return new net.minecraft.world.item.ItemStack(net.minecraft.core.Holder.direct(item));
        } catch (Throwable t) {
            return net.minecraft.world.item.ItemStack.EMPTY;
        }
    }

    /** 26.1 fallback: blit item PNG texture directly when GuiItemRenderState fails to render due to unbound components. */
    // Items whose flat texture name differs from their registry name, OR have no flat item texture (use fallback).
    private static final java.util.Map<String, String> TEXTURE_NAME_OVERRIDES = java.util.Map.ofEntries(
        // 3D models with a "_standby" or similar flat texture
        java.util.Map.entry("crossbow", "item/crossbow_standby"),
        // 3D-only models — fallback to a sensible flat representation
        java.util.Map.entry("shield", "gui/sprites/container/slot/shield"),  // 2D shield silhouette from vanilla slot icons
        java.util.Map.entry("oak_chest_boat", "item/oak_boat"),
        java.util.Map.entry("spruce_chest_boat", "item/spruce_boat"),
        java.util.Map.entry("birch_chest_boat", "item/birch_boat"),
        java.util.Map.entry("jungle_chest_boat", "item/jungle_boat"),
        java.util.Map.entry("acacia_chest_boat", "item/acacia_boat"),
        java.util.Map.entry("dark_oak_chest_boat", "item/dark_oak_boat"),
        java.util.Map.entry("mangrove_chest_boat", "item/mangrove_boat"),
        java.util.Map.entry("cherry_chest_boat", "item/cherry_boat"),
        java.util.Map.entry("bamboo_chest_raft", "item/bamboo_raft"),
        java.util.Map.entry("pale_oak_chest_boat", "item/pale_oak_boat"),
        // tipped arrow renders as 3D — use base arrow as flat fallback
        java.util.Map.entry("tipped_arrow", "item/arrow"),
        // Block-named items that need block/* paths instead of item/*
        java.util.Map.entry("torch", "block/torch"),
        java.util.Map.entry("redstone_torch", "block/redstone_torch"),
        java.util.Map.entry("soul_torch", "block/soul_torch"),
        java.util.Map.entry("command_block", "block/command_block_front"),
        java.util.Map.entry("chain_command_block", "block/chain_command_block_front"),
        java.util.Map.entry("repeating_command_block", "block/repeating_command_block_front"),
        // Special block items where the item name doesn't directly match block texture
        java.util.Map.entry("chest", "block/oak_planks"),
        java.util.Map.entry("trapped_chest", "block/oak_planks"),
        java.util.Map.entry("ender_chest", "block/end_stone"),
        java.util.Map.entry("crafting_table", "block/crafting_table_top"),
        java.util.Map.entry("furnace", "block/furnace_front"),
        java.util.Map.entry("blast_furnace", "block/blast_furnace_front"),
        java.util.Map.entry("smoker", "block/smoker_front"),
        java.util.Map.entry("dispenser", "block/dispenser_front"),
        java.util.Map.entry("dropper", "block/dropper_front"),
        java.util.Map.entry("hopper", "block/hopper_inside"),
        java.util.Map.entry("piston", "block/piston_side"),
        java.util.Map.entry("sticky_piston", "block/piston_side"),
        java.util.Map.entry("observer", "block/observer_side"),
        java.util.Map.entry("note_block", "block/note_block"),
        java.util.Map.entry("jukebox", "block/jukebox_top"),
        java.util.Map.entry("loom", "block/loom_front"),
        java.util.Map.entry("stonecutter", "block/stonecutter_top"),
        java.util.Map.entry("smithing_table", "block/smithing_table_top"),
        java.util.Map.entry("cartography_table", "block/cartography_table_top"),
        java.util.Map.entry("fletching_table", "block/fletching_table_top"),
        java.util.Map.entry("grindstone", "block/grindstone_side"),
        java.util.Map.entry("anvil", "block/anvil"),
        java.util.Map.entry("chipped_anvil", "block/chipped_anvil_top"),
        java.util.Map.entry("damaged_anvil", "block/damaged_anvil_top"),
        java.util.Map.entry("beacon", "block/beacon"),
        java.util.Map.entry("conduit", "block/conduit"),
        java.util.Map.entry("dragon_egg", "block/dragon_egg"),
        java.util.Map.entry("end_portal_frame", "block/end_portal_frame_top"),
        java.util.Map.entry("end_rod", "block/end_rod"),
        java.util.Map.entry("lightning_rod", "block/lightning_rod"),
        java.util.Map.entry("brewing_stand", "block/brewing_stand"),
        java.util.Map.entry("cauldron", "block/cauldron_top"),
        java.util.Map.entry("respawn_anchor", "block/respawn_anchor_top"),
        java.util.Map.entry("lodestone", "block/lodestone_side"),
        java.util.Map.entry("decorated_pot", "block/decorated_pot_side"),
        java.util.Map.entry("oak_slab", "block/oak_planks"),
        // Animated items — texture is at `<name>_00.png` (first animation frame).
        java.util.Map.entry("clock", "item/clock_00"),
        java.util.Map.entry("compass", "item/compass_00"),
        java.util.Map.entry("recovery_compass", "item/recovery_compass_00"),
        java.util.Map.entry("light", "item/light_00"),
        // Copper chest variants — 3D entity model, flat fallback to copper block.
        java.util.Map.entry("copper_chest", "block/copper_block"),
        java.util.Map.entry("exposed_copper_chest", "block/exposed_copper"),
        java.util.Map.entry("weathered_copper_chest", "block/weathered_copper"),
        java.util.Map.entry("oxidized_copper_chest", "block/oxidized_copper"),
        java.util.Map.entry("waxed_copper_chest", "block/copper_block"),
        java.util.Map.entry("waxed_exposed_copper_chest", "block/exposed_copper"),
        java.util.Map.entry("waxed_weathered_copper_chest", "block/weathered_copper"),
        java.util.Map.entry("waxed_oxidized_copper_chest", "block/oxidized_copper"),
        // Heads/skulls — 3D-only models with no flat texture; use a recognizable themed fallback.
        java.util.Map.entry("player_head", "block/note_block"),
        java.util.Map.entry("zombie_head", "item/rotten_flesh"),
        java.util.Map.entry("creeper_head", "item/gunpowder"),
        java.util.Map.entry("skeleton_skull", "item/bone"),
        java.util.Map.entry("wither_skeleton_skull", "item/coal"),
        java.util.Map.entry("dragon_head", "item/dragon_breath"),
        java.util.Map.entry("piglin_head", "item/gold_ingot"),
        // 3D-only blocks with reasonable flat fallbacks.
        java.util.Map.entry("crafter", "block/crafter_top"),
        java.util.Map.entry("vault", "block/vault_top"),
        java.util.Map.entry("trial_spawner", "block/trial_spawner_top_inactive"),
        java.util.Map.entry("chiseled_bookshelf", "block/chiseled_bookshelf_side"),
        // 26.1 dried ghast (4 hydration stages × multi-face) — use stage 0 north face as flat icon.
        java.util.Map.entry("dried_ghast", "block/dried_ghast_hydration_0_north"),
        // Copper golem statues — 3D entity models with no flat texture; fall back to corresponding copper block.
        java.util.Map.entry("copper_golem_statue", "block/copper_block"),
        java.util.Map.entry("exposed_copper_golem_statue", "block/exposed_copper"),
        java.util.Map.entry("weathered_copper_golem_statue", "block/weathered_copper"),
        java.util.Map.entry("oxidized_copper_golem_statue", "block/oxidized_copper"),
        java.util.Map.entry("waxed_copper_golem_statue", "block/copper_block"),
        java.util.Map.entry("waxed_exposed_copper_golem_statue", "block/exposed_copper"),
        java.util.Map.entry("waxed_weathered_copper_golem_statue", "block/weathered_copper"),
        java.util.Map.entry("waxed_oxidized_copper_golem_statue", "block/oxidized_copper"),
        // Enchanted golden apple — own texture renders oddly; reuse plain golden apple icon.
        java.util.Map.entry("enchanted_golden_apple", "item/golden_apple")
    );

    // Block-suffix patterns — items ending in these get textures from textures/block/
    private static final String[] BLOCK_SUFFIXES = {
        "_planks", "_log", "_wood", "_leaves", "_sapling", "_block", "_ore",
        "_terracotta", "_wool", "_concrete", "_concrete_powder", "_glass", "_glass_pane",
        "_slab", "_stairs", "_fence", "_fence_gate", "_wall", "_door", "_trapdoor",
        "_carpet", "_bed", "_banner", "_button", "_pressure_plate", "_sign", "_hanging_sign",
        "_stem", "_hyphae", "_stripped_log", "_stripped_wood", "_pillar", "_bricks", "_brick",
        "_tiles", "_tile", "_shulker_box", "_candle", "_coral", "_coral_block", "_coral_fan",
        "_kelp", "_seagrass", "_grass", "_fern", "_mushroom", "_mushroom_block", "_roots",
        "_pumpkin", "_melon", "_sand", "_sandstone", "_gravel", "_dirt", "_clay", "_ice",
        "_snow", "_obsidian", "_basalt", "_deepslate", "_tuff", "_calcite", "_amethyst",
        "_copper", "_amethyst_cluster", "_bud", "_wart_block", "_nylium", "_nether_bricks"
    };

    // Shape suffixes — items in this category typically have NO own texture and reuse the base block's
    // (or the base material's wool texture for color-based items like beds/banners/carpets).
    // Order matters: longer/more specific suffixes must come first so we don't accidentally strip
    // "_fence" from "_fence_gate" or "_banner" from "_wall_banner".
    private static final String[] SHAPE_SUFFIXES = {
        "_wall_banner", "_fence_gate", "_pressure_plate", "_hanging_sign",
        "_slab", "_stairs", "_wall", "_fence",
        "_button", "_sign", "_carpet", "_banner", "_bed",
        "_door", "_trapdoor"
    };

    private static final java.util.concurrent.ConcurrentHashMap<String, String> TEXTURE_PATH_CACHE = new java.util.concurrent.ConcurrentHashMap<>();

    private static boolean textureExists(String namespace, String subpath) {
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        if (mc == null || mc.getResourceManager() == null) return false;
        try {
            return mc.getResourceManager().getResource(
                net.minecraft.resources.Identifier.parse(namespace + ":textures/" + subpath + ".png")).isPresent();
        } catch (Throwable t) { return false; }
    }

    private static String resolveTexturePath(String namespace, String path) {
        String cacheKey = namespace + ":" + path;
        String cached = TEXTURE_PATH_CACHE.get(cacheKey);
        if (cached != null) return cached;

        String resolved = TEXTURE_NAME_OVERRIDES.get(path);
        boolean fromOverride = resolved != null;
        if (resolved == null) resolved = findTexture(namespace, path);

        // Diagnostic: log first 30 unique resolutions, especially barrier fallbacks.
        if (TEXTURE_PATH_CACHE.size() < 30 || "item/barrier".equals(resolved)) {
            LOGGER.info("[InvOrg26.1] resolve {}:{} -> {} (override={})", namespace, path, resolved, fromOverride);
        }

        TEXTURE_PATH_CACHE.put(cacheKey, resolved);
        return resolved;
    }

    private static String findTexture(String namespace, String path) {
        boolean isBlock = false;
        for (String suffix : BLOCK_SUFFIXES) {
            if (path.endsWith(suffix)) { isBlock = true; break; }
        }

        // Spawn eggs: 80+ items, all use the same shared texture with tinting we can't apply here.
        if (path.endsWith("_spawn_egg") && textureExists(namespace, "item/spawn_egg")) return "item/spawn_egg";

        // 1. Direct lookups — item/ first for non-block names, otherwise block/ first.
        if (!isBlock && textureExists(namespace, "item/" + path)) return "item/" + path;
        if (textureExists(namespace, "block/" + path)) return "block/" + path;
        if (textureExists(namespace, "item/" + path)) return "item/" + path;

        // 1a. Animated / state-based items — texture file is `<name>_00.png` or `<name>_0.png` (first frame/state).
        //     Handles compass, clock, recovery_compass, light (`_00`) AND suspicious_sand, suspicious_gravel (`_0`).
        if (textureExists(namespace, "item/" + path + "_00")) return "item/" + path + "_00";
        if (textureExists(namespace, "block/" + path + "_00")) return "block/" + path + "_00";
        if (textureExists(namespace, "item/" + path + "_0")) return "item/" + path + "_0";
        if (textureExists(namespace, "block/" + path + "_0")) return "block/" + path + "_0";

        // 1b. Wood / hyphae aliases — `<wood>_wood` uses `<wood>_log` texture (bark side);
        //                            `<wood>_hyphae` uses `<wood>_stem` (nether wood).
        //     Handles birch_wood, cherry_wood, dark_oak_wood, stripped_<wood>_wood, crimson_hyphae, warped_hyphae etc.
        if (path.endsWith("_wood")) {
            String logName = path.substring(0, path.length() - "_wood".length()) + "_log";
            if (textureExists(namespace, "block/" + logName)) return "block/" + logName;
        }
        if (path.endsWith("_hyphae")) {
            String stemName = path.substring(0, path.length() - "_hyphae".length()) + "_stem";
            if (textureExists(namespace, "block/" + stemName)) return "block/" + stemName;
        }

        // 1c. Multi-face blocks ending in _block (or any block name) — try base/_side/_top/_bottom suffixes.
        //     Handles snow_block (→ block/snow), dried_kelp_block, hay_block, mushroom_block variants, etc.
        if (path.endsWith("_block")) {
            String stripped = path.substring(0, path.length() - "_block".length());
            if (textureExists(namespace, "block/" + stripped)) return "block/" + stripped;
            if (textureExists(namespace, "block/" + stripped + "_side")) return "block/" + stripped + "_side";
            if (textureExists(namespace, "block/" + stripped + "_top")) return "block/" + stripped + "_top";
            if (textureExists(namespace, "block/" + stripped + "_bottom")) return "block/" + stripped + "_bottom";
        }

        // 1b. Multi-face blocks: try _front / _top / _side / _side1 (e.g. crafter, decorated_pot, conduit variants).
        if (textureExists(namespace, "block/" + path + "_front")) return "block/" + path + "_front";
        if (textureExists(namespace, "block/" + path + "_top")) return "block/" + path + "_top";
        if (textureExists(namespace, "block/" + path + "_side")) return "block/" + path + "_side";

        // 2. Shape-suffix stripping: <base>_slab/_stairs/_wall/etc. has no own texture in Minecraft —
        //    those reuse the base block's texture (e.g. mossy_cobblestone_slab → mossy_cobblestone.png).
        for (String suffix : SHAPE_SUFFIXES) {
            if (!path.endsWith(suffix)) continue;
            String base = path.substring(0, path.length() - suffix.length());

            // 2a. Direct base block.
            if (textureExists(namespace, "block/" + base)) return "block/" + base;
            // 2b. Singular → plural for _brick and _tile (mossy_stone_brick_slab → mossy_stone_bricks; deepslate_tile_slab → deepslate_tiles).
            if (base.endsWith("_brick") && textureExists(namespace, "block/" + base + "s")) return "block/" + base + "s";
            if (base.endsWith("_tile") && textureExists(namespace, "block/" + base + "s")) return "block/" + base + "s";
            // 2c. Wood-shape items use planks (oak_button, pale_oak_pressure_plate → <wood>_planks).
            if (textureExists(namespace, "block/" + base + "_planks")) return "block/" + base + "_planks";
            // 2d. Multi-face base block (e.g. furnace_button → furnace_front).
            if (textureExists(namespace, "block/" + base + "_top")) return "block/" + base + "_top";
            if (textureExists(namespace, "block/" + base + "_side")) return "block/" + base + "_side";
            if (textureExists(namespace, "block/" + base + "_front")) return "block/" + base + "_front";
            // 2e. Doors/trapdoors keep their own name suffixes.
            if (textureExists(namespace, "block/" + path + "_bottom")) return "block/" + path + "_bottom";
            if (textureExists(namespace, "block/" + path + "_top")) return "block/" + path + "_top";
            // 2f. Color-based items (beds, banners, carpets, wall_banners) — use the color's wool texture.
            if (textureExists(namespace, "block/" + base + "_wool")) return "block/" + base + "_wool";
            // 2g. Try item/<base> too in case there's a flat icon for the base material.
            if (textureExists(namespace, "item/" + base)) return "item/" + base;
            // 2h. Try <base>_block (purpur_slab → purpur_block) and <base>_block_top (quartz_slab → quartz_block_top).
            if (textureExists(namespace, "block/" + base + "_block")) return "block/" + base + "_block";
            if (textureExists(namespace, "block/" + base + "_block_top")) return "block/" + base + "_block_top";
            // 2i. Recursive retry: resolve the stripped base via full findTexture so smooth_/waxed_/etc. handlers fire.
            //     Handles smooth_quartz_slab → smooth_quartz → quartz_block_top.
            String recursive = findTexture(namespace, base);
            if (!"item/barrier".equals(recursive)) return recursive;
            break;
        }

        // 3. Sapling/log fallback — items like polished_<X> where X has its own texture.
        if (path.startsWith("polished_") && textureExists(namespace, "block/" + path.substring("polished_".length()))) {
            return "block/" + path.substring("polished_".length());
        }

        // 3b. Smooth variants — smooth_sandstone, smooth_red_sandstone, smooth_quartz, smooth_basalt, smooth_stone.
        //     Some have own texture (smooth_basalt, smooth_stone) — covered by direct lookup.
        //     Others reuse the base block's _top face (smooth_sandstone → sandstone_top, smooth_quartz → quartz_block_top).
        if (path.startsWith("smooth_")) {
            String base = path.substring("smooth_".length());
            if (textureExists(namespace, "block/" + base + "_top")) return "block/" + base + "_top";
            if (textureExists(namespace, "block/" + base + "_block_top")) return "block/" + base + "_block_top";
            if (textureExists(namespace, "block/" + base)) return "block/" + base;
        }

        // 4. Waxed copper variants — wax doesn't change appearance; recurse with the non-waxed name
        //    so ALL the above logic (direct lookup, shape strip, multi-face, etc.) applies.
        //    Handles waxed_copper_block, waxed_oxidized_cut_copper_stairs, waxed_<patina>_copper_door, etc.
        if (path.startsWith("waxed_")) {
            String unwaxed = path.substring("waxed_".length());
            String resolved = findTexture(namespace, unwaxed);
            if (!"item/barrier".equals(resolved)) return resolved;
        }

        // 4a. Infested blocks — silverfish hidden inside; visually identical to base block.
        //     Handles infested_stone, infested_cobblestone, infested_(stone|mossy_stone|cracked_stone|chiseled_stone)_bricks, infested_deepslate.
        if (path.startsWith("infested_")) {
            String base = path.substring("infested_".length());
            String resolved = findTexture(namespace, base);
            if (!"item/barrier".equals(resolved)) return resolved;
        }

        // 4b. Petrified blocks — only petrified_oak_slab exists; reuse oak_planks.
        if (path.startsWith("petrified_")) {
            String base = path.substring("petrified_".length());
            String resolved = findTexture(namespace, base);
            if (!"item/barrier".equals(resolved)) return resolved;
        }

        // 4b. Generic prefix-strip retry — if path starts with a common adjective, retry with the bare material.
        //     Handles chiseled_*, cut_*, cracked_*, etc. where the smooth/cut/chiseled variant uses the base block.
        for (String prefix : new String[]{"chiseled_", "cut_", "cracked_"}) {
            if (path.startsWith(prefix)) {
                String unprefixed = path.substring(prefix.length());
                if (textureExists(namespace, "block/" + unprefixed)) return "block/" + unprefixed;
            }
        }

        // 5. Final fallback: barrier icon — visibly missing but doesn't crash.
        return "item/barrier";
    }

    public static void drawItemIcon(net.minecraft.client.gui.GuiGraphicsExtractor context, net.minecraft.world.item.ItemStack stack, int x, int y) {
        if (stack == null || stack.isEmpty()) return;
        try {
            net.minecraft.world.item.Item item = stack.getItem();
            net.minecraft.resources.Identifier key = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item);
            if (key == null) return;
            String texSubpath = resolveTexturePath(key.getNamespace(), key.getPath());
            net.minecraft.resources.Identifier tex = net.minecraft.resources.Identifier.parse(
                key.getNamespace() + ":textures/" + texSubpath + ".png");
            // 26.1 classic blit: (pipeline, id, x, y, u, v, width, height, textureWidth, textureHeight)
            context.blit(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED,
                tex, x, y, 0f, 0f, 16, 16, 16, 16);
        } catch (Throwable ignored) {}
    }

}
