package com.example.inventoryorganizer.config;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.Click;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class SortingOrderConfigScreen extends Screen {

    private final Screen parent;
    private final OrganizerConfig config;
    private final String[] slotTexts;   // 36 inventory slot rules (copy)
    private final String[] equipTexts;  // 5 equipment slot rules (copy)
    private final String tierKey;       // preference key for tier assignments
    private final boolean storageMode;  // true = show storage grid, not inventory
    private final int storageRows;      // rows to show in storage mode

    private static final String[] EQUIP_LABELS = { "Helmet", "Chest", "Legs", "Boots", "Offhand" };

    // Tier assignments: slotIndex (0-35 inv, 100-104 equip) -> tier number
    private final Map<Integer, Integer> tierAssignments = new HashMap<>();

    // Inventory grid – same dimensions as VisualInventoryConfigScreen
    private int gridX, gridY;
    private int SLOT_W = 36; // dynamic - set in init() based on screen size
    private int SLOT_H = 36;

    // Selected slot
    private int selectedSlot = -1;
    private TextFieldWidget numberField;

    // Sort criteria configuration (right side)
    private List<String> criteriaOrder;
    private List<String> materialOrder;
    private boolean enchantDesc = true;
    private boolean durabilityDesc = true;

    private List<String> foodOrder;

    private List<String> enchantOrder;
    private List<String> potionOrder;
    private List<String> potionTypeOrder;
    private List<String> blockOrder;

    // Scroll offset for right panel
    private int scrollOffset = 0;
    private static final int MAX_SCROLL = 2500;

    // Drag-and-drop state
    private int dragSection = -1;  // 0=criteria, 1=material, 2=potionType, 3=food, 4=enchant, 5=potion, 6=block, 7+=custom groups
    private int dragIndex = -1;
    private double dragCurrentY = 0;
    private double dragCurrentX = 0;
    private boolean isDragging = false;
    // Section first-item Y positions (set during drawRightPanel, BEFORE scroll) – 7 fixed + up to 23 custom groups
    private int[] sectionFirstItemY = new int[30];

    // Custom group ordering (loaded in loadSortPreferences)
    private List<String> cgNames = new ArrayList<>();
    private List<List<String>> customGroupOrders = new ArrayList<>();

    private static final String[] DEFAULT_CRITERIA = {"material", "enchant", "durability"};
    private static final String[] DEFAULT_MATERIALS = {"netherite", "diamond", "iron", "gold", "stone", "wood", "leather", "chain"};
    // ALL 39 food items in Minecraft 1.21
    private static final String[] DEFAULT_FOODS = {
        "golden_carrot", "enchanted_golden_apple", "golden_apple",
        "cooked_beef", "cooked_porkchop", "cooked_mutton", "cooked_chicken",
        "cooked_salmon", "cooked_cod", "cooked_rabbit",
        "pumpkin_pie", "cake", "bread", "baked_potato",
        "mushroom_stew", "rabbit_stew", "beetroot_soup", "suspicious_stew",
        "honey_bottle", "apple", "melon_slice", "glow_berries", "sweet_berries",
        "carrot", "potato", "beetroot", "dried_kelp", "cookie",
        "chorus_fruit", "beef", "porkchop", "mutton", "chicken",
        "salmon", "cod", "rabbit", "tropical_fish", "pufferfish",
        "rotten_flesh", "spider_eye", "poisonous_potato"
    };
    // ALL 42 enchantments in Minecraft 1.21
    private static final String[] DEFAULT_ENCHANTS = {
        // Weapon enchants (7)
        "sharpness", "smite", "bane_of_arthropods", "knockback", "fire_aspect", "looting", "sweeping_edge",
        // Tool enchants (3)
        "efficiency", "fortune", "silk_touch",
        // Universal (2)
        "unbreaking", "mending",
        // Armor enchants (12)
        "protection", "fire_protection", "blast_protection", "projectile_protection",
        "feather_falling", "thorns", "respiration", "aqua_affinity",
        "depth_strider", "frost_walker", "soul_speed", "swift_sneak",
        // Bow enchants (4)
        "power", "punch", "flame", "infinity",
        // Crossbow enchants (3)
        "piercing", "quick_charge", "multishot",
        // Trident enchants (4)
        "loyalty", "impaling", "riptide", "channeling",
        // Mace enchants (3)
        "density", "breach", "wind_burst",
        // Fishing rod enchants (2)
        "luck_of_the_sea", "lure",
        // Curses (2) - NOTE: In 1.21 these are "binding_curse" and "vanishing_curse"
        "binding_curse", "vanishing_curse"
    };
    // Common Minecraft potion types
    private static final String[] DEFAULT_POTIONS = {
        "healing", "strong_healing",
        "regeneration", "long_regeneration", "strong_regeneration",
        "strength", "long_strength", "strong_strength",
        "swiftness", "long_swiftness", "strong_swiftness",
        "leaping", "long_leaping", "strong_leaping",
        "fire_resistance", "long_fire_resistance",
        "water_breathing", "long_water_breathing",
        "night_vision", "long_night_vision",
        "invisibility", "long_invisibility",
        "slow_falling", "long_slow_falling",
        "luck",
        "poison", "long_poison", "strong_poison",
        "weakness", "long_weakness",
        "slowness", "long_slowness", "strong_slowness",
        "harming", "strong_harming",
        "turtle_master", "long_turtle_master", "strong_turtle_master",
        "water", "awkward", "thick", "mundane"
    };
    // Potion item types (drinkable, splash, lingering, tipped arrow)
    private static final String[] DEFAULT_POTION_TYPES = {
        "potion", "splash_potion", "lingering_potion", "tipped_arrow"
    };
    // Common blocks
    private static final String[] DEFAULT_BLOCKS = {
        "stone", "cobblestone", "deepslate", "cobbled_deepslate",
        "dirt", "grass_block", "sand", "gravel",
        "oak_log", "birch_log", "spruce_log", "jungle_log", "acacia_log", "dark_oak_log",
        "oak_planks", "birch_planks", "spruce_planks",
        "netherrack", "soul_sand", "basalt",
        "obsidian", "glass",
        "white_wool", "white_concrete",
        "torch", "lantern", "glowstone"
    };

    public SortingOrderConfigScreen(Screen parent, String[] slotTexts, String[] equipTexts) {
        this(parent, slotTexts, equipTexts, "tier_order", false, 3);
    }

    public SortingOrderConfigScreen(Screen parent, String[] slotTexts, String[] equipTexts, String tierKey) {
        this(parent, slotTexts, equipTexts, tierKey, false, 3);
    }

    public SortingOrderConfigScreen(Screen parent, String[] slotTexts, String[] equipTexts, String tierKey, boolean storageMode, int storageRows) {
        super(Text.literal("Tier Order Configuration"));
        this.parent = parent;
        this.config = OrganizerConfig.get();
        this.tierKey = tierKey;
        this.storageMode = storageMode;
        this.storageRows = storageRows;
        // Copy arrays so we don't mutate the originals
        int slotCount = storageMode ? storageRows * 9 : 36;
        this.slotTexts = new String[slotCount];
        for (int i = 0; i < slotCount; i++) {
            this.slotTexts[i] = (i < slotTexts.length && slotTexts[i] != null) ? slotTexts[i] : "any";
        }
        this.equipTexts = java.util.Arrays.copyOf(equipTexts, 5);
        loadTierAssignments();
        loadSortPreferences();
    }

    // --- Tier persistence ---

    private void loadTierAssignments() {
        String[] saved = config.getPreference(tierKey);
        if (saved != null && saved.length > 0) {
            for (String entry : saved) {
                if (entry.startsWith("slot_") && entry.contains("_tier_")) {
                    try {
                        String[] parts = entry.split("_");
                        int slot = Integer.parseInt(parts[1]);
                        int tier = Integer.parseInt(parts[3]);
                        tierAssignments.put(slot, tier);
                    } catch (Exception ignored) {}
                }
            }
        } else if ("tier_order".equals(tierKey) && !config.getPreferences().containsKey(tierKey)) {
            // First run: hotbar (0-8) + armor (100-103) = tier 1; main inv (9-35) + offhand (104) = tier 2
            for (int i = 0; i <= 8; i++)  tierAssignments.put(i, 1);
            for (int i = 9; i <= 35; i++) tierAssignments.put(i, 2);
            for (int i = 100; i <= 103; i++) tierAssignments.put(i, 1);
            tierAssignments.put(104, 2);
        }
    }

    private void saveTierAssignments() {
        List<String> entries = new ArrayList<>();
        for (Map.Entry<Integer, Integer> entry : tierAssignments.entrySet()) {
            entries.add("slot_" + entry.getKey() + "_tier_" + entry.getValue());
        }
        config.setPreference(tierKey, entries.toArray(new String[0]));
        if ("tier_order".equals(tierKey)) {
            config.setTierOrderUserConfigured(true);
        }
        config.save();
        System.out.println("[InvOrganizer] Saved tier assignments: " + entries);
    }

    private void loadSortPreferences() {
        criteriaOrder = new ArrayList<>();
        materialOrder = new ArrayList<>();

        String[] savedCriteria = config.getPreference("sort_criteria_order");
        if (savedCriteria != null && savedCriteria.length > 0) {
            for (String c : savedCriteria) criteriaOrder.add(c);
        } else {
            for (String c : DEFAULT_CRITERIA) criteriaOrder.add(c);
        }

        String[] savedMaterials = config.getPreference("sort_material_order");
        if (savedMaterials != null && savedMaterials.length > 0) {
            for (String m : savedMaterials) materialOrder.add(m);
        } else {
            for (String m : DEFAULT_MATERIALS) materialOrder.add(m);
        }

        String[] savedEnchant = config.getPreference("sort_enchant_desc");
        enchantDesc = savedEnchant == null || savedEnchant.length == 0 || !savedEnchant[0].equals("false");

        String[] savedDurability = config.getPreference("sort_durability_desc");
        durabilityDesc = savedDurability == null || savedDurability.length == 0 || !savedDurability[0].equals("false");

        foodOrder = new ArrayList<>();
        String[] savedFoods = config.getPreference("sort_food_order");
        if (savedFoods != null && savedFoods.length > 0) {
            // Load saved foods
            for (String f : savedFoods) foodOrder.add(f);
            // Add any new foods from DEFAULT_FOODS that aren't in saved list
            for (String f : DEFAULT_FOODS) {
                if (!foodOrder.contains(f)) {
                    foodOrder.add(f);
                }
            }
        } else {
            for (String f : DEFAULT_FOODS) foodOrder.add(f);
        }

        enchantOrder = new ArrayList<>();
        String[] savedEnchants = config.getPreference("sort_enchant_order");
        System.out.println("[InvOrganizer] Loading enchants: saved=" + (savedEnchants != null ? savedEnchants.length : 0) + " default=" + DEFAULT_ENCHANTS.length);
        if (savedEnchants != null && savedEnchants.length > 0) {
            // Load saved enchants and replace old IDs with new ones
            for (String e : savedEnchants) {
                // Replace old curse IDs with new ones (Minecraft 1.21 format change)
                if (e.equals("curse_of_vanishing")) {
                    enchantOrder.add("vanishing_curse");
                } else if (e.equals("curse_of_binding")) {
                    enchantOrder.add("binding_curse");
                } else {
                    enchantOrder.add(e);
                }
            }
            // Remove duplicates (in case both old and new IDs exist)
            enchantOrder = new ArrayList<>(new java.util.LinkedHashSet<>(enchantOrder));
            System.out.println("[InvOrganizer] Loaded " + enchantOrder.size() + " saved enchants (old IDs replaced)");
            // Add any new enchants from DEFAULT_ENCHANTS that aren't in saved list
            int addedCount = 0;
            for (String e : DEFAULT_ENCHANTS) {
                if (!enchantOrder.contains(e)) {
                    enchantOrder.add(e);
                    addedCount++;
                }
            }
            System.out.println("[InvOrganizer] Added " + addedCount + " new enchants, total=" + enchantOrder.size());
        } else {
            for (String e : DEFAULT_ENCHANTS) enchantOrder.add(e);
            System.out.println("[InvOrganizer] Using default enchants: " + enchantOrder.size());
        }
        System.out.println("[InvOrganizer] Final enchant list: " + enchantOrder);

        potionOrder = new ArrayList<>();
        String[] savedPotions = config.getPreference("sort_potion_order");
        if (savedPotions != null && savedPotions.length > 0) {
            for (String p : savedPotions) potionOrder.add(p);
            for (String p : DEFAULT_POTIONS) {
                if (!potionOrder.contains(p)) potionOrder.add(p);
            }
        } else {
            for (String p : DEFAULT_POTIONS) potionOrder.add(p);
        }

        potionTypeOrder = new ArrayList<>();
        String[] savedPotionTypes = config.getPreference("sort_potion_type_order");
        if (savedPotionTypes != null && savedPotionTypes.length > 0) {
            for (String p : savedPotionTypes) potionTypeOrder.add(p);
            for (String p : DEFAULT_POTION_TYPES) {
                if (!potionTypeOrder.contains(p)) potionTypeOrder.add(p);
            }
        } else {
            for (String p : DEFAULT_POTION_TYPES) potionTypeOrder.add(p);
        }

        blockOrder = new ArrayList<>();
        String[] savedBlocks = config.getPreference("sort_block_order");
        if (savedBlocks != null && savedBlocks.length > 0) {
            for (String b : savedBlocks) blockOrder.add(b);
            for (String b : DEFAULT_BLOCKS) {
                if (!blockOrder.contains(b)) blockOrder.add(b);
            }
        } else {
            for (String b : DEFAULT_BLOCKS) blockOrder.add(b);
        }

        // Custom group orders
        cgNames = config.getCustomGroupNames();
        customGroupOrders = new ArrayList<>();
        for (String cgName : cgNames) {
            List<String> cgItems = config.getCustomGroup(cgName);
            String[] savedOrder = config.getPreference("cg_order_" + cgName);
            List<String> order = new ArrayList<>();
            if (savedOrder != null && savedOrder.length > 0) {
                for (String s : savedOrder) order.add(s);
                for (String s : cgItems) { if (!order.contains(s)) order.add(s); }
            } else {
                order.addAll(cgItems);
            }
            customGroupOrders.add(order);
        }
    }

    private void saveSortPreferences() {
        config.setPreference("sort_criteria_order", criteriaOrder.toArray(new String[0]));
        config.setPreference("sort_material_order", materialOrder.toArray(new String[0]));
        config.setPreference("sort_enchant_desc", new String[]{String.valueOf(enchantDesc)});
        config.setPreference("sort_durability_desc", new String[]{String.valueOf(durabilityDesc)});
        config.setPreference("sort_food_order", foodOrder.toArray(new String[0]));
        config.setPreference("sort_enchant_order", enchantOrder.toArray(new String[0]));
        config.setPreference("sort_potion_order", potionOrder.toArray(new String[0]));
        config.setPreference("sort_potion_type_order", potionTypeOrder.toArray(new String[0]));
        config.setPreference("sort_block_order", blockOrder.toArray(new String[0]));
        for (int gi = 0; gi < cgNames.size(); gi++) {
            config.setPreference("cg_order_" + cgNames.get(gi), customGroupOrders.get(gi).toArray(new String[0]));
        }
        System.out.println("[InvOrganizer] Saved sort prefs: criteria=" + criteriaOrder + " materials=" + materialOrder + " foods=" + foodOrder + " enchants=" + enchantOrder + " enchDesc=" + enchantDesc + " durDesc=" + durabilityDesc);
    }

    // --- Helpers ---

    private boolean hasRule(String text) {
        return text != null && !text.equals("any") && !text.equals("empty");
    }

    private int equipSlotKey(int equipIdx) { return 100 + equipIdx; }

    // --- Init ---

    @Override
    protected void init() {
        super.init();

        // Dynamic slot size: scale down if screen is too narrow
        int minPanelW = 140;
        int margins = 40;
        int availableForGrid = width - minPanelW - margins;
        int colCount = storageMode ? 9 : 10; // storage: 9 cols; inventory: 1 armor + 9
        SLOT_W = Math.max(20, Math.min(36, availableForGrid / colCount));
        SLOT_H = SLOT_W;

        // Responsive layout
        int margin = Math.max(4, width / 70);
        gridX = storageMode ? margin + 8 : margin + SLOT_W + 8;
        gridY = height / 8;

        // Number input field: center between grid right edge and right panel
        int fieldX = gridX + 9 * SLOT_W + Math.max(40, width / 14);
        numberField = new TextFieldWidget(textRenderer, fieldX, gridY, 50, 18, Text.literal("Tier"));
        numberField.setMaxLength(2);
        numberField.setPlaceholder(Text.literal("1-41"));
        addDrawableChild(numberField);

        // Set button
        addDrawableChild(StyledButton.styledBuilder(Text.literal("Set"), btn -> {
            applyTierFromField();
        }).dimensions(fieldX + 54, gridY, 30, 18).build());

        // Clear button
        addDrawableChild(StyledButton.styledBuilder(Text.literal("X"), btn -> {
            if (selectedSlot >= 0) {
                tierAssignments.remove(selectedSlot);
                numberField.setText("");
            }
        }).dimensions(fieldX + 88, gridY, 20, 18).build());

        // Bottom buttons
        int btnY = height - 28;
        int btnW = 80;
        int totalW = btnW * 3 + 4 * 2;
        int startX = width / 2 - totalW / 2;

        addDrawableChild(StyledButton.styledBuilder(Text.literal("Save"), btn -> {
            saveTierAssignments();
            saveSortPreferences();
            MinecraftClient.getInstance().setScreen(parent);
        }).dimensions(startX, btnY, btnW, 20).build());

        addDrawableChild(StyledButton.styledBuilder(Text.literal("Reset"), btn -> {
            tierAssignments.clear();
            selectedSlot = -1;
            numberField.setText("");
        }).dimensions(startX + btnW + 4, btnY, btnW, 20).build());

        addDrawableChild(StyledButton.styledBuilder(Text.literal("Back"), btn -> {
            MinecraftClient.getInstance().setScreen(parent);
        }).dimensions(startX + (btnW + 4) * 2, btnY, btnW, 20).build());

        // Guide toggle button (top-right)
        OrganizerConfig cfg = OrganizerConfig.get();
        addDrawableChild(StyledButton.styledBuilder(Text.literal("?"), btn -> {
            cfg.setShowHelp(!cfg.isShowHelp());
            cfg.save();
        }).dimensions(width - 24, 4, 20, 18).build());
    }

    private void applyTierFromField() {
        if (selectedSlot < 0) return;
        String text = numberField.getText().trim();
        if (!text.isEmpty()) {
            try {
                int tier = Integer.parseInt(text);
                if (tier >= 1 && tier <= 41) {
                    tierAssignments.put(selectedSlot, tier);
                }
            } catch (NumberFormatException ignored) {}
        } else {
            tierAssignments.remove(selectedSlot);
        }
    }

    // --- Render ---

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta);

        // Draw decorated background panel for grid
        if (storageMode) {
            // 12px margin on all sides; label sits at gridY-12 so add 12 extra on top
            drawDecoratedPanel(context, gridX - 12, gridY - 24, 9 * SLOT_W + 24, storageRows * SLOT_H + 36);
        } else {
            // Left covers armor column (gridX-SLOT_W-8) + 12px; right covers 9-col grid + 12px
            // Top covers label (gridY-12) + 12px; bottom covers offhand (gridY+5*SLOT_H+4) + 12px
            drawDecoratedPanel(context, gridX - SLOT_W - 20, gridY - 24,
                10 * SLOT_W + 32, 5 * SLOT_H + 40);
        }
        
        // Draw decorated border (no dark background) for tier input area
        int fieldX = gridX + 9 * SLOT_W + Math.max(40, width / 14);
        drawDecoratedBorder(context, fieldX - 8, gridY - 8, 124, 34);
        
        // Draw decorated background panel for right panel
        int rightEdgeMargin = Math.max(10, width / 50);
        int panelW = Math.max(160, Math.min(220, width / 3)); // responsive 160-220px
        int px = width - panelW - rightEdgeMargin;
        int py = gridY + 24;
        int panelH = height - py - 34;
        drawDecoratedPanel(context, px - 10, py - 10, panelW + 16, panelH + 16);

        context.drawCenteredTextWithShadow(textRenderer, Text.literal("Tier Order Configuration"), width / 2, 4, 0xFFFFFFFF);
        context.drawTextWithShadow(textRenderer, Text.literal("Click slot > type tier (1=best) > Set | Only slots with rules can be tiered"), width / 2 - 200, 16, 0xFFAAAAAA);

        drawInventoryGrid(context, mouseX, mouseY);
        drawRightPanel(context, mouseX, mouseY);

        // Guide overlay (drawn last, on top)
        if (OrganizerConfig.get().isShowHelp()) drawGuideOverlay(context);
    }

    // --- Inventory grid (same layout as VisualInventoryConfigScreen) ---

    private void drawInventoryGrid(DrawContext context, int mouseX, int mouseY) {
        if (storageMode) {
            context.drawTextWithShadow(textRenderer, Text.literal("Storage Slots"), gridX, gridY - 12, 0xFFFFFF55);
            for (int row = 0; row < storageRows; row++) {
                for (int col = 0; col < 9; col++) {
                    drawSlot(context, gridX + col * SLOT_W, gridY + row * SLOT_H, row * 9 + col, mouseX, mouseY);
                }
            }
            return;
        }
        // Inventory label
        context.drawTextWithShadow(textRenderer, Text.literal("Inventory (slots 9-35)"), gridX, gridY - 12, 0xFFFFFF55);

        // Main inventory rows (3 rows x 9 cols, slots 9-35)
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                int slot = 9 + row * 9 + col;
                drawSlot(context, gridX + col * SLOT_W, gridY + row * SLOT_H, slot, mouseX, mouseY);
            }
        }

        // Hotbar (slots 0-8)
        int hotbarY = gridY + 3 * SLOT_H + 14;
        context.drawTextWithShadow(textRenderer, Text.literal("Hotbar (slots 0-8)"), gridX, hotbarY - 12, 0xFFFFFF55);
        for (int col = 0; col < 9; col++) {
            drawSlot(context, gridX + col * SLOT_W, hotbarY, col, mouseX, mouseY);
        }

        // Armor slots (left of grid)
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

    // --- Draw individual slot ---

    /** Minecraft-style sunken 3D slot (shared between drawSlot and drawEquipSlot) */
    private void drawMCSlot(DrawContext context, int x, int y, int innerColor, boolean hovered, boolean selected) {
        context.fill(x, y, x + SLOT_W, y + SLOT_H, 0xFF111111);
        context.fill(x + 1, y + 1, x + SLOT_W - 1, y + 3, 0xFF2A2A2A);
        context.fill(x + 1, y + 1, x + 3, y + SLOT_H - 1, 0xFF2A2A2A);
        context.fill(x + 1, y + SLOT_H - 3, x + SLOT_W - 1, y + SLOT_H - 1, 0xFF6A6A6A);
        context.fill(x + SLOT_W - 3, y + 1, x + SLOT_W - 1, y + SLOT_H - 1, 0xFF6A6A6A);
        context.fill(x + 3, y + 3, x + SLOT_W - 3, y + SLOT_H - 3, innerColor);
        if (selected) {
            context.fill(x + 3, y + 3, x + SLOT_W - 3, y + SLOT_H - 3, 0x44FFFF00);
            context.drawHorizontalLine(x + 2, x + SLOT_W - 3, y + 2, 0xFFFFFF00);
            context.drawHorizontalLine(x + 2, x + SLOT_W - 3, y + SLOT_H - 3, 0xFFFFFF00);
            context.drawVerticalLine(x + 2, y + 2, y + SLOT_H - 3, 0xFFFFFF00);
            context.drawVerticalLine(x + SLOT_W - 3, y + 2, y + SLOT_H - 3, 0xFFFFFF00);
        } else if (hovered) {
            context.fill(x + 3, y + 3, x + SLOT_W - 3, y + SLOT_H - 3, 0x33FFFFFF);
        }
    }

    private void drawSlot(DrawContext context, int x, int y, int slot, int mouseX, int mouseY) {
        String text = slotTexts[slot];
        boolean active = hasRule(text);
        boolean hovered = active && mouseX >= x && mouseX < x + SLOT_W && mouseY >= y && mouseY < y + SLOT_H;
        boolean selected = (slot == selectedSlot);
        Integer tier = tierAssignments.get(slot);

        int innerColor;
        if (!active) innerColor = 0xFF252525;
        else if (selected) innerColor = 0xFF1A2A4A;
        else if (tier != null) innerColor = 0xFF1A2E1A;
        else innerColor = 0xFF353535;

        drawMCSlot(context, x, y, innerColor, hovered, selected);

        if (!active) {
            // Dimmed placeholder icon
            context.fill(x + 3, y + 3, x + SLOT_W - 3, y + SLOT_H - 3, 0x88000000);
            return;
        }

        // Tier color strip on top
        if (tier != null) {
            context.fill(x + 3, y + 3, x + SLOT_W - 3, y + 5, 0xFF55BB55);
        }

        ItemStack icon = getIconForText(text);
        if (!icon.isEmpty()) context.drawItem(icon, x + (SLOT_W - 16) / 2, y + 2);

        if (tier != null) {
            String tierStr = String.valueOf(tier);
            int tw = textRenderer.getWidth(tierStr);
            context.drawTextWithShadow(textRenderer, Text.literal("\u00a7a" + tierStr),
                    x + (SLOT_W - tw) / 2, y + SLOT_H - 12, 0xFF55FF55);
        } else {
            String displayText = getDisplayText(text);
            int maxTextW = SLOT_W - 4;
            if (textRenderer.getWidth(displayText) > maxTextW) {
                while (displayText.length() > 1 && textRenderer.getWidth(displayText + ".") > maxTextW)
                    displayText = displayText.substring(0, displayText.length() - 1);
                displayText += ".";
            }
            int tw = textRenderer.getWidth(displayText);
            context.drawTextWithShadow(textRenderer, Text.literal(displayText),
                    x + (SLOT_W - tw) / 2, y + 20, 0xFFAAAAAA);
        }
    }

    private void drawEquipSlot(DrawContext context, int x, int y, int equipIdx, int mouseX, int mouseY) {
        String text = equipTexts[equipIdx];
        boolean active = hasRule(text);
        int key = equipSlotKey(equipIdx);
        boolean hovered = active && mouseX >= x && mouseX < x + SLOT_W && mouseY >= y && mouseY < y + SLOT_H;
        boolean selected = (key == selectedSlot);
        Integer tier = tierAssignments.get(key);

        int innerColor;
        if (!active) innerColor = 0xFF252525;
        else if (selected) innerColor = 0xFF1A2A4A;
        else if (tier != null) innerColor = 0xFF1A2E1A;
        else innerColor = 0xFF353535;

        drawMCSlot(context, x, y, innerColor, hovered, selected);

        if (!active) {
            ItemStack defaultIcon = getDefaultEquipIcon(equipIdx);
            if (!defaultIcon.isEmpty()) context.drawItem(defaultIcon, x + (SLOT_W - 16) / 2, y + 2);
            String label = EQUIP_LABELS[equipIdx];
            int tw = textRenderer.getWidth(label);
            context.drawTextWithShadow(textRenderer, Text.literal(label),
                    x + (SLOT_W - tw) / 2, y + 20, 0xFF444444);
            return;
        }

        if (tier != null) context.fill(x + 3, y + 3, x + SLOT_W - 3, y + 5, 0xFF5588CC);

        ItemStack icon = getIconForText(text);
        if (icon.isEmpty()) icon = getDefaultEquipIcon(equipIdx);
        if (!icon.isEmpty()) context.drawItem(icon, x + (SLOT_W - 16) / 2, y + 2);

        if (tier != null) {
            String tierStr = String.valueOf(tier);
            int tw = textRenderer.getWidth(tierStr);
            context.drawTextWithShadow(textRenderer, Text.literal("\u00a7a" + tierStr),
                    x + (SLOT_W - tw) / 2, y + SLOT_H - 12, 0x55FF55);
        } else {
            String label = EQUIP_LABELS[equipIdx];
            int tw = textRenderer.getWidth(label);
            context.drawTextWithShadow(textRenderer, Text.literal(label),
                    x + (SLOT_W - tw) / 2, y + 20, 0xFF6699CC);
        }
    }

    // --- Right panel: selected slot info + sort criteria config ---

    // Track panel Y positions for click handling
    private int panelX, panelStartY;

    private void drawRightPanel(DrawContext context, int mouseX, int mouseY) {
        int rightEdgeMargin = Math.max(10, width / 50);
        int panelW = Math.max(160, Math.min(220, width / 3)); // responsive 160-220px
        int px = width - panelW - rightEdgeMargin;
        panelX = px;
        int py = gridY + 24;

        // Dark background panel
        int panelH = height - py - 34;
        context.fill(px - 2, py - 2, px + panelW, py + panelH, 0xFF111111);
        context.drawHorizontalLine(px - 2, px + panelW, py - 2, 0xFF666666);
        context.drawHorizontalLine(px - 2, px + panelW, py + panelH, 0xFF666666);
        context.drawVerticalLine(px - 2, py - 2, py + panelH, 0xFF666666);
        context.drawVerticalLine(px + panelW, py - 2, py + panelH, 0xFF666666);

        // Enable scissor for scrolling
        context.enableScissor(px - 2, py - 2, px + panelW, py + panelH);

        int curY = py - scrollOffset;

        // -- Selected slot info --
        if (selectedSlot >= 0) {
            String slotName;
            if (selectedSlot >= 100) slotName = EQUIP_LABELS[selectedSlot - 100];
            else if (selectedSlot < 9) slotName = "Hotbar " + (selectedSlot + 1);
            else slotName = "Inv " + (selectedSlot - 9 + 1);
            context.drawTextWithShadow(textRenderer, Text.literal("Selected: " + slotName), px + 2, curY, 0xFFFFFF55);
            curY += 11;
            Integer tier = tierAssignments.get(selectedSlot);
            context.drawTextWithShadow(textRenderer, Text.literal("Tier: " + (tier != null ? tier : "-")),
                    px + 2, curY, tier != null ? 0xFF55FF55 : 0xFF888888);
            curY += 15;
        }

        // -- Sort Priority --
        panelStartY = curY;
        context.drawTextWithShadow(textRenderer, Text.literal("Sort Priority:"), px + 2, curY, 0xFFFFAA00);
        curY += 13;
        sectionFirstItemY[0] = curY + scrollOffset;
        for (int i = 0; i < criteriaOrder.size(); i++) {
            String name = criteriaOrder.get(i);
            String display = (i + 1) + ". " + capitalize(name);
            boolean isBeingDragged = isDragging && dragSection == 0 && dragIndex == i;
            int textColor = isBeingDragged ? 0xFF888888 : 0xFFFFFFFF;
            context.drawTextWithShadow(textRenderer, Text.literal(display), px + 6, curY, textColor);
            // Drop indicator line
            if (isDragging && dragSection == 0) {
                int targetIdx = getDropIndex(0, (int) dragCurrentY);
                if (targetIdx == i) context.fill(px + 2, curY - 1, px + 125, curY, 0xFFFFFF00);
            }
            curY += 13;
        }

        // -- Material Order --
        curY += 6;
        context.drawTextWithShadow(textRenderer, Text.literal("Material Order:"), px + 2, curY, 0xFFFFAA00);
        curY += 13;
        sectionFirstItemY[1] = curY + scrollOffset;
        for (int i = 0; i < materialOrder.size(); i++) {
            String name = materialOrder.get(i);
            String display = (i + 1) + ". " + capitalize(name);
            boolean isBeingDragged = isDragging && dragSection == 1 && dragIndex == i;
            int textColor = isBeingDragged ? 0xFF666666 : (0xFF000000 | getMaterialColor(name));
            context.drawTextWithShadow(textRenderer, Text.literal(display), px + 6, curY, textColor);
            if (isDragging && dragSection == 1) {
                int targetIdx = getDropIndex(1, (int) dragCurrentY);
                if (targetIdx == i) context.fill(px + 2, curY - 1, px + 125, curY, 0xFFFFFF00);
            }
            curY += 13;
        }

        // -- Potion Type Order --
        curY += 6;
        context.drawTextWithShadow(textRenderer, Text.literal("Potion Type Order:"), px + 2, curY, 0xFFFFAA00);
        curY += 13;
        sectionFirstItemY[2] = curY + scrollOffset;
        for (int i = 0; i < potionTypeOrder.size(); i++) {
            String name = potionTypeOrder.get(i);
            String display = (i + 1) + ". " + formatPotionTypeName(name);
            boolean isBeingDragged = isDragging && dragSection == 2 && dragIndex == i;
            int textColor = isBeingDragged ? 0xFF8888AA : 0xFF88AAFF;
            context.drawTextWithShadow(textRenderer, Text.literal(display), px + 6, curY, textColor);
            if (isDragging && dragSection == 2) {
                int targetIdx = getDropIndex(2, (int) dragCurrentY);
                if (targetIdx == i) context.fill(px + 2, curY - 1, px + 125, curY, 0xFFFFFF00);
            }
            curY += 13;
        }

        // -- Toggles --
        curY += 8;
        context.drawTextWithShadow(textRenderer, Text.literal("Toggles:"), px + 2, curY, 0xFFFFAA00);
        curY += 13;

        boolean hoverEnch = isHoveredArea(mouseX, mouseY, px, curY - 1, 180, 12);
        context.fill(px + 2, curY - 1, px + 182, curY + 11, hoverEnch ? 0xFF334455 : 0xFF333333);
        String enchText = "Enchant: " + (enchantDesc ? "More=Better" : "Less=Better");
        context.drawTextWithShadow(textRenderer, Text.literal(enchText), px + 6, curY, 0xFFCCCCCC);
        curY += 14;

        boolean hoverDur = isHoveredArea(mouseX, mouseY, px, curY - 1, 180, 12);
        context.fill(px + 2, curY - 1, px + 182, curY + 11, hoverDur ? 0xFF334455 : 0xFF333333);
        String durText = "Durability: " + (durabilityDesc ? "More=Better" : "Less=Better");
        context.drawTextWithShadow(textRenderer, Text.literal(durText), px + 6, curY, 0xFFCCCCCC);
        curY += 16;

        // -- Food Order --
        context.drawTextWithShadow(textRenderer, Text.literal("Food Order:"), px + 2, curY, 0xFFFFAA00);
        curY += 13;
        sectionFirstItemY[3] = curY + scrollOffset;
        for (int i = 0; i < foodOrder.size(); i++) {
            String name = foodOrder.get(i);
            String display = (i + 1) + ". " + formatFoodName(name);
            boolean isBeingDragged = isDragging && dragSection == 3 && dragIndex == i;
            int textColor = isBeingDragged ? 0xFF886644 : 0xFFFFCC88;
            context.drawTextWithShadow(textRenderer, Text.literal(display), px + 6, curY, textColor);
            if (isDragging && dragSection == 3) {
                int targetIdx = getDropIndex(3, (int) dragCurrentY);
                if (targetIdx == i) context.fill(px + 2, curY - 1, px + 125, curY, 0xFFFFFF00);
            }
            curY += 13;
        }

        // -- Enchantment Order --
        curY += 8;
        context.drawTextWithShadow(textRenderer, Text.literal("Enchantment Order:"), px + 2, curY, 0xFFFFAA00);
        curY += 13;
        sectionFirstItemY[4] = curY + scrollOffset;
        for (int i = 0; i < enchantOrder.size(); i++) {
            String name = enchantOrder.get(i);
            String display = (i + 1) + ". " + formatEnchantName(name);
            boolean isBeingDragged = isDragging && dragSection == 4 && dragIndex == i;
            int textColor = isBeingDragged ? 0xFF886688 : 0xFFDD88FF;
            context.drawTextWithShadow(textRenderer, Text.literal(display), px + 6, curY, textColor);
            if (isDragging && dragSection == 4) {
                int targetIdx = getDropIndex(4, (int) dragCurrentY);
                if (targetIdx == i) context.fill(px + 2, curY - 1, px + 125, curY, 0xFFFFFF00);
            }
            curY += 13;
        }

        // -- Potion Order --
        curY += 8;
        context.drawTextWithShadow(textRenderer, Text.literal("Potion Order:"), px + 2, curY, 0xFFFFAA00);
        curY += 13;
        sectionFirstItemY[5] = curY + scrollOffset;
        for (int i = 0; i < potionOrder.size(); i++) {
            String name = potionOrder.get(i);
            String display = (i + 1) + ". " + formatPotionName(name);
            boolean isBeingDragged = isDragging && dragSection == 5 && dragIndex == i;
            int textColor = isBeingDragged ? 0xFF886644 : 0xFFFF88BB;
            context.drawTextWithShadow(textRenderer, Text.literal(display), px + 6, curY, textColor);
            if (isDragging && dragSection == 5) {
                int targetIdx = getDropIndex(5, (int) dragCurrentY);
                if (targetIdx == i) context.fill(px + 2, curY - 1, px + 125, curY, 0xFFFFFF00);
            }
            curY += 13;
        }

        // -- Block Order --
        curY += 8;
        context.drawTextWithShadow(textRenderer, Text.literal("Block Order:"), px + 2, curY, 0xFFFFAA00);
        curY += 13;
        sectionFirstItemY[6] = curY + scrollOffset;
        for (int i = 0; i < blockOrder.size(); i++) {
            String name = blockOrder.get(i);
            String display = (i + 1) + ". " + formatBlockName(name);
            boolean isBeingDragged = isDragging && dragSection == 6 && dragIndex == i;
            int textColor = isBeingDragged ? 0xFF888866 : 0xFFFFFF88;
            context.drawTextWithShadow(textRenderer, Text.literal(display), px + 6, curY, textColor);
            if (isDragging && dragSection == 6) {
                int targetIdx = getDropIndex(6, (int) dragCurrentY);
                if (targetIdx == i) context.fill(px + 2, curY - 1, px + 125, curY, 0xFFFFFF00);
            }
            curY += 13;
        }

        // -- Custom Group Orders --
        for (int gi = 0; gi < cgNames.size(); gi++) {
            int secIdx = 7 + gi;
            curY += 8;
            context.drawTextWithShadow(textRenderer,
                Text.literal("Group \"" + cgNames.get(gi) + "\" Order:"), px + 2, curY, 0xFFFFAA00);
            curY += 13;
            if (secIdx < sectionFirstItemY.length) sectionFirstItemY[secIdx] = curY + scrollOffset;
            List<String> cgOrder = customGroupOrders.get(gi);
            for (int i = 0; i < cgOrder.size(); i++) {
                String display = (i + 1) + ". " + formatItemIdName(cgOrder.get(i));
                boolean isBeingDragged = isDragging && dragSection == secIdx && dragIndex == i;
                int textColor = isBeingDragged ? 0xFF666666 : 0xFF88CCFF;
                context.drawTextWithShadow(textRenderer, Text.literal(display), px + 6, curY, textColor);
                if (isDragging && dragSection == secIdx) {
                    int targetIdx = getDropIndex(secIdx, (int) dragCurrentY);
                    if (targetIdx == i) context.fill(px + 2, curY - 1, px + 125, curY, 0xFFFFFF00);
                }
                curY += 13;
            }
        }

        context.disableScissor();

        // Draw dragged item floating at mouse position (outside scissor)
        if (isDragging && dragSection >= 0 && dragIndex >= 0) {
            List<String> list = getDragList();
            if (dragIndex < list.size()) {
                String dragName = getDragDisplayName(dragSection, list.get(dragIndex));
                context.fill((int) dragCurrentX - 2, (int) dragCurrentY - 2,
                             (int) dragCurrentX + 130, (int) dragCurrentY + 10, 0xDD000000);
                context.drawHorizontalLine((int) dragCurrentX - 2, (int) dragCurrentX + 130, (int) dragCurrentY - 2, 0xFFFFFF00);
                context.drawHorizontalLine((int) dragCurrentX - 2, (int) dragCurrentX + 130, (int) dragCurrentY + 10, 0xFFFFFF00);
                context.drawTextWithShadow(textRenderer, Text.literal("\u00a7e" + dragName),
                        (int) dragCurrentX + 2, (int) dragCurrentY, 0xFFFFFF00);
            }
        }
        
        // Draw scroll indicator
        if (MAX_SCROLL > 0) {
            int scrollBarH = Math.max(20, panelH * panelH / (panelH + MAX_SCROLL));
            int scrollBarY = py + (int)((float)scrollOffset / MAX_SCROLL * (panelH - scrollBarH));
            context.fill(px + panelW - 6, scrollBarY, px + panelW - 2, scrollBarY + scrollBarH, 0xFF888888);
        }
    }

    // --- Drag-and-drop helpers ---

    private List<String> getDragList() {
        if (dragSection >= 7) {
            int gi = dragSection - 7;
            return gi < customGroupOrders.size() ? customGroupOrders.get(gi) : new java.util.ArrayList<>();
        }
        switch (dragSection) {
            case 0: return criteriaOrder;
            case 1: return materialOrder;
            case 2: return potionTypeOrder;
            case 3: return foodOrder;
            case 4: return enchantOrder;
            case 5: return potionOrder;
            case 6: return blockOrder;
            default: return new java.util.ArrayList<>();
        }
    }

    private String getDragDisplayName(int section, String name) {
        if (section >= 7) return formatItemIdName(name);
        switch (section) {
            case 0: return capitalize(name);
            case 1: return capitalize(name);
            case 2: return formatPotionTypeName(name);
            case 3: return formatFoodName(name);
            case 4: return formatEnchantName(name);
            case 5: return formatPotionName(name);
            case 6: return formatBlockName(name);
            default: return name;
        }
    }

    private int getDropIndex(int section, int mouseY) {
        if (section < 0 || section >= sectionFirstItemY.length) return 0;
        int startY = sectionFirstItemY[section] - scrollOffset;
        int idx = (mouseY - startY + 6) / 13;
        List<String> list;
        if (section >= 7) {
            int gi = section - 7;
            list = gi < customGroupOrders.size() ? customGroupOrders.get(gi) : new java.util.ArrayList<>();
        } else {
            list = section == 0 ? criteriaOrder : section == 1 ? materialOrder : section == 2 ? potionTypeOrder : section == 3 ? foodOrder : section == 4 ? enchantOrder : section == 5 ? potionOrder : blockOrder;
        }
        return list.isEmpty() ? 0 : Math.max(0, Math.min(list.size() - 1, idx));
    }

    private int getSectionForMouseX(double mouseX) {
        int rightEdgeMargin = Math.max(10, width / 50);
        int panelW = Math.max(160, Math.min(220, width / 3));
        int px = width - panelW - rightEdgeMargin;
        return (mouseX >= px && mouseX < px + panelW) ? 0 : -1; // just checks panel
    }

    private int getSectionAndIndex(double mouseX, double mouseY, int[] outIndex) {
        int rightEdgeMargin = Math.max(10, width / 50);
        int panelW = Math.max(160, Math.min(220, width / 3));
        int px = width - panelW - rightEdgeMargin;
        if (mouseX < px || mouseX > px + panelW) return -1;
        for (int s = 0; s < 7; s++) {
            int startY = sectionFirstItemY[s] - scrollOffset;
            List<String> list = s == 0 ? criteriaOrder : s == 1 ? materialOrder : s == 2 ? potionTypeOrder : s == 3 ? foodOrder : s == 4 ? enchantOrder : s == 5 ? potionOrder : blockOrder;
            int endY = startY + list.size() * 13;
            if (mouseY >= startY - 4 && mouseY < endY) {
                int idx = (int)(mouseY - startY) / 13;
                outIndex[0] = Math.max(0, Math.min(list.size() - 1, idx));
                return s;
            }
        }
        // Custom group sections
        for (int gi = 0; gi < cgNames.size(); gi++) {
            int s = 7 + gi;
            if (s >= sectionFirstItemY.length) break;
            int startY = sectionFirstItemY[s] - scrollOffset;
            List<String> list = customGroupOrders.get(gi);
            int endY = startY + list.size() * 13;
            if (mouseY >= startY - 4 && mouseY < endY) {
                int idx = (int)(mouseY - startY) / 13;
                outIndex[0] = Math.max(0, Math.min(list.size() - 1, idx));
                return s;
            }
        }
        return -1;
    }

    @Override
    public boolean mouseDragged(Click click, double deltaX, double deltaY) {
        if (click.button() == 0 && isDragging) {
            dragCurrentX = click.x();
            dragCurrentY = click.y();
            return true;
        }
        return super.mouseDragged(click, deltaX, deltaY);
    }

    @Override
    public boolean mouseReleased(Click click) {
        if (click.button() == 0 && isDragging) {
            int targetIdx = getDropIndex(dragSection, (int) click.y());
            List<String> list = getDragList();
            if (targetIdx != dragIndex && dragIndex < list.size()) {
                String item = list.remove(dragIndex);
                list.add(targetIdx, item);
            }
            isDragging = false;
            dragSection = -1;
            dragIndex = -1;
            return true;
        }
        return super.mouseReleased(click);
    }

    private boolean isHoveredArea(int mouseX, int mouseY, int x, int y, int w, int h) {
        return mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
    }

    private String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return s.substring(0, 1).toUpperCase() + s.substring(1);
    }

    private String formatFoodName(String id) {
        // "cooked_beef" -> "Cooked Beef", "golden_carrot" -> "Golden Carrot"
        String[] parts = id.split("_");
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (sb.length() > 0) sb.append(" ");
            sb.append(capitalize(part));
        }
        return sb.toString();
    }

    private String formatPotionName(String id) {
        String[] parts = id.split("_");
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (sb.length() > 0) sb.append(" ");
            sb.append(capitalize(part));
        }
        return sb.toString();
    }

    private String formatBlockName(String id) {
        String[] parts = id.split("_");
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (sb.length() > 0) sb.append(" ");
            sb.append(capitalize(part));
        }
        return sb.toString();
    }

    private String formatPotionTypeName(String id) {
        switch (id) {
            case "potion":           return "Drinkable Potion";
            case "splash_potion":    return "Splash Potion";
            case "lingering_potion": return "Lingering Potion";
            case "tipped_arrow":     return "Tipped Arrow";
            default: return capitalize(id);
        }
    }

    private String formatItemIdName(String id) {
        if (id == null) return "";
        String path = id.contains(":") ? id.substring(id.indexOf(':') + 1) : id;
        String[] parts = path.split("_");
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (sb.length() > 0) sb.append(" ");
            sb.append(capitalize(part));
        }
        return sb.toString();
    }

    private String formatEnchantName(String id) {
        // "fire_aspect" -> "Fire Aspect", "bane_of_arthropods" -> "Bane Of Arthropods"
        String[] parts = id.split("_");
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (sb.length() > 0) sb.append(" ");
            sb.append(capitalize(part));
        }
        return sb.toString();
    }

    private int getMaterialColor(String material) {
        switch (material) {
            case "netherite": return 0xFFFF5555;
            case "diamond": return 0xFF55FFFF;
            case "iron": return 0xFFFFFFFF;
            case "gold": return 0xFFFFFF55;
            case "stone": return 0xFFAAAAAA;
            case "wood": return 0xFFAA7744;
            case "leather": return 0xFFCC8855;
            case "chain": return 0xFF999999;
            default: return 0xFFFFFFFF;
        }
    }

    // --- Icon/text helpers (same as VisualInventoryConfigScreen) ---

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
                case "potions": return new ItemStack(Items.POTION);
                case "misc":     return new ItemStack(Items.ENDER_PEARL);
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
                case "flint_and_steel": return new ItemStack(Items.FLINT_AND_STEEL);
                default: return ItemStack.EMPTY;
            }
        }
        if (text.startsWith("cg:")) return new ItemStack(Items.CHEST);
        if (text.contains(":")) {
            try {
                Identifier id = Identifier.of(text);
                Item item = Registries.ITEM.get(id);
                if (item != null && item != Items.AIR) return new ItemStack(item);
            } catch (Exception ignored) {}
        }
        return ItemStack.EMPTY;
    }

    private String getDisplayText(String text) {
        if (text.equals("any")) return "-";
        if (text.equals("empty")) return "X";
        if (text.startsWith("g:")) return text.substring(2);
        if (text.startsWith("t:")) return text.substring(2);
        if (text.startsWith("cg:")) return text.substring(3);
        if (text.contains(":")) {
            String path = text.substring(text.indexOf(':') + 1);
            return path.replace('_', ' ');
        }
        return text;
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

    // --- Mouse click ---

    @Override
    public boolean mouseClicked(Click click, boolean bl) {
        double mouseX = click.x();
        double mouseY = click.y();
        int button = click.button();

        if (OrganizerConfig.get().isShowHelp()) {
            int gw = 310, gh = 192;
            int gx = width / 2 - gw / 2;
            int gy = height / 2 - gh / 2;
            if (mouseX < gx || mouseX > gx + gw || mouseY < gy || mouseY > gy + gh) {
                OrganizerConfig.get().setShowHelp(false);
                OrganizerConfig.get().save();
            }
            return true;
        }

        if (button == 0) {
            if (storageMode) {
                // Storage grid: simple N×9
                for (int row = 0; row < storageRows; row++) {
                    for (int col = 0; col < 9; col++) {
                        int slot = row * 9 + col;
                        if (!hasRule(slotTexts[slot])) continue;
                        int x = gridX + col * SLOT_W;
                        int y = gridY + row * SLOT_H;
                        if (mouseX >= x && mouseX < x + SLOT_W && mouseY >= y && mouseY < y + SLOT_H) {
                            selectSlot(slot);
                            return true;
                        }
                    }
                }
            } else {
            // Check main inventory slots (9-35)
            for (int row = 0; row < 3; row++) {
                for (int col = 0; col < 9; col++) {
                    int slot = 9 + row * 9 + col;
                    if (!hasRule(slotTexts[slot])) continue;
                    int x = gridX + col * SLOT_W;
                    int y = gridY + row * SLOT_H;
                    if (mouseX >= x && mouseX < x + SLOT_W && mouseY >= y && mouseY < y + SLOT_H) {
                        selectSlot(slot);
                        return true;
                    }
                }
            }

            // Check hotbar (0-8)
            int hotbarY = gridY + 3 * SLOT_H + 14;
            for (int col = 0; col < 9; col++) {
                if (!hasRule(slotTexts[col])) continue;
                int x = gridX + col * SLOT_W;
                if (mouseX >= x && mouseX < x + SLOT_W && mouseY >= hotbarY && mouseY < hotbarY + SLOT_H) {
                    selectSlot(col);
                    return true;
                }
            }

            // Check armor slots (0-3)
            int armorX = gridX - SLOT_W - 8;
            for (int i = 0; i < 4; i++) {
                if (!hasRule(equipTexts[i])) continue;
                int y = gridY + i * SLOT_H;
                if (mouseX >= armorX && mouseX < armorX + SLOT_W && mouseY >= y && mouseY < y + SLOT_H) {
                    selectSlot(equipSlotKey(i));
                    return true;
                }
            }

            // Check offhand
            int offhandY = gridY + 4 * SLOT_H + 4;
            if (hasRule(equipTexts[4])) {
                if (mouseX >= armorX && mouseX < armorX + SLOT_W && mouseY >= offhandY && mouseY < offhandY + SLOT_H) {
                    selectSlot(equipSlotKey(4));
                    return true;
                }
            }
            } // end !storageMode

            // --- Drag start check (click on row text, not +/- buttons) ---
            {
                int[] outIdx = new int[1];
                int sec = getSectionAndIndex(mouseX, mouseY, outIdx);
                if (sec >= 0) {
                    isDragging = true;
                    dragSection = sec;
                    dragIndex = outIdx[0];
                    dragCurrentX = mouseX;
                    dragCurrentY = mouseY;
                    return true;
                }
            }

            // --- Sort config clicks (right panel) ---
            int rightEdgeMargin = Math.max(10, width / 50);
            int panelW = Math.max(160, Math.min(220, width / 3));
            int px = width - panelW - rightEdgeMargin;
            int curY = gridY + 24 - scrollOffset;

            // Skip selected slot info area
            if (selectedSlot >= 0) curY += 26;

            // Sort Priority title
            curY += 13;
            // Criteria up/down arrows
            for (int i = 0; i < criteriaOrder.size(); i++) {
                if (i > 0 && isHoveredArea((int) mouseX, (int) mouseY, px + 130, curY - 1, 14, 12)) {
                    java.util.Collections.swap(criteriaOrder, i, i - 1);
                    return true;
                }
                if (i < criteriaOrder.size() - 1 && isHoveredArea((int) mouseX, (int) mouseY, px + 147, curY - 1, 14, 12)) {
                    java.util.Collections.swap(criteriaOrder, i, i + 1);
                    return true;
                }
                curY += 13;
            }

            // Material Order title
            curY += 6 + 13;
            for (int i = 0; i < materialOrder.size(); i++) {
                if (i > 0 && isHoveredArea((int) mouseX, (int) mouseY, px + 130, curY - 1, 14, 12)) {
                    java.util.Collections.swap(materialOrder, i, i - 1);
                    return true;
                }
                if (i < materialOrder.size() - 1 && isHoveredArea((int) mouseX, (int) mouseY, px + 147, curY - 1, 14, 12)) {
                    java.util.Collections.swap(materialOrder, i, i + 1);
                    return true;
                }
                curY += 13;
            }

            // Potion Type Order title
            curY += 6 + 13;
            for (int i = 0; i < potionTypeOrder.size(); i++) {
                if (i > 0 && isHoveredArea((int) mouseX, (int) mouseY, px + 115, curY - 1, 14, 12)) {
                    java.util.Collections.swap(potionTypeOrder, i, i - 1);
                    return true;
                }
                if (i < potionTypeOrder.size() - 1 && isHoveredArea((int) mouseX, (int) mouseY, px + 132, curY - 1, 14, 12)) {
                    java.util.Collections.swap(potionTypeOrder, i, i + 1);
                    return true;
                }
                curY += 13;
            }

            // Toggles title
            curY += 8 + 13;

            // Enchant toggle
            if (isHoveredArea((int) mouseX, (int) mouseY, px, curY - 1, 180, 12)) {
                enchantDesc = !enchantDesc;
                return true;
            }
            curY += 14;

            // Durability toggle
            if (isHoveredArea((int) mouseX, (int) mouseY, px, curY - 1, 180, 12)) {
                durabilityDesc = !durabilityDesc;
                return true;
            }
            curY += 16;

            // Food Order title
            curY += 13;
            for (int i = 0; i < foodOrder.size(); i++) {
                if (i > 0 && isHoveredArea((int) mouseX, (int) mouseY, px + 115, curY - 1, 14, 12)) {
                    java.util.Collections.swap(foodOrder, i, i - 1);
                    return true;
                }
                if (i < foodOrder.size() - 1 && isHoveredArea((int) mouseX, (int) mouseY, px + 132, curY - 1, 14, 12)) {
                    java.util.Collections.swap(foodOrder, i, i + 1);
                    return true;
                }
                curY += 13;
            }

            // Enchantment Order title
            curY += 8 + 13;
            for (int i = 0; i < enchantOrder.size(); i++) {
                if (i > 0 && isHoveredArea((int) mouseX, (int) mouseY, px + 115, curY - 1, 14, 12)) {
                    java.util.Collections.swap(enchantOrder, i, i - 1);
                    return true;
                }
                if (i < enchantOrder.size() - 1 && isHoveredArea((int) mouseX, (int) mouseY, px + 132, curY - 1, 14, 12)) {
                    java.util.Collections.swap(enchantOrder, i, i + 1);
                    return true;
                }
                curY += 13;
            }

            // Potion Order title
            curY += 8 + 13;
            for (int i = 0; i < potionOrder.size(); i++) {
                if (i > 0 && isHoveredArea((int) mouseX, (int) mouseY, px + 115, curY - 1, 14, 12)) {
                    java.util.Collections.swap(potionOrder, i, i - 1);
                    return true;
                }
                if (i < potionOrder.size() - 1 && isHoveredArea((int) mouseX, (int) mouseY, px + 132, curY - 1, 14, 12)) {
                    java.util.Collections.swap(potionOrder, i, i + 1);
                    return true;
                }
                curY += 13;
            }

            // Block Order title
            curY += 8 + 13;
            for (int i = 0; i < blockOrder.size(); i++) {
                if (i > 0 && isHoveredArea((int) mouseX, (int) mouseY, px + 115, curY - 1, 14, 12)) {
                    java.util.Collections.swap(blockOrder, i, i - 1);
                    return true;
                }
                if (i < blockOrder.size() - 1 && isHoveredArea((int) mouseX, (int) mouseY, px + 132, curY - 1, 14, 12)) {
                    java.util.Collections.swap(blockOrder, i, i + 1);
                    return true;
                }
                curY += 13;
            }

            // Custom Group Order sections
            for (int gi = 0; gi < cgNames.size(); gi++) {
                curY += 8 + 13;
                List<String> cgOrder = customGroupOrders.get(gi);
                for (int i = 0; i < cgOrder.size(); i++) {
                    if (i > 0 && isHoveredArea((int) mouseX, (int) mouseY, px + 115, curY - 1, 14, 12)) {
                        java.util.Collections.swap(cgOrder, i, i - 1);
                        return true;
                    }
                    if (i < cgOrder.size() - 1 && isHoveredArea((int) mouseX, (int) mouseY, px + 132, curY - 1, 14, 12)) {
                        java.util.Collections.swap(cgOrder, i, i + 1);
                        return true;
                    }
                    curY += 13;
                }
            }
        }
        return super.mouseClicked(click, bl);
    }

    private void selectSlot(int slot) {
        selectedSlot = slot;
        Integer currentTier = tierAssignments.get(slot);
        numberField.setText(currentTier != null ? String.valueOf(currentTier) : "");
        numberField.setFocused(true);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        // Check if mouse is over right panel
        int rightEdgeMargin = Math.max(10, width / 50);
        int panelW = Math.max(160, Math.min(220, width / 3));
        int px = width - panelW - rightEdgeMargin;
        int py = gridY + 24;
        int panelH = height - py - 34;
        
        if (mouseX >= px - 2 && mouseX < px + panelW && mouseY >= py - 2 && mouseY < py + panelH) {
            scrollOffset -= (int)(verticalAmount * 20);
            if (scrollOffset < 0) scrollOffset = 0;
            if (scrollOffset > MAX_SCROLL) scrollOffset = MAX_SCROLL;
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    /** Draw a decorated border only (no dark background) */
    private void drawDecoratedBorder(DrawContext context, int x, int y, int width, int height) {
        context.drawHorizontalLine(x, x + width - 1, y, 0xFF000000);
        context.drawHorizontalLine(x, x + width - 1, y + height - 1, 0xFF000000);
        context.drawVerticalLine(x, y, y + height - 1, 0xFF000000);
        context.drawVerticalLine(x + width - 1, y, y + height - 1, 0xFF000000);
        context.drawHorizontalLine(x + 1, x + width - 2, y + 1, 0xFF555555);
        context.drawVerticalLine(x + 1, y + 1, y + height - 2, 0xFF555555);
        context.drawHorizontalLine(x + 1, x + width - 2, y + height - 2, 0xFF2A2A2A);
        context.drawVerticalLine(x + width - 2, y + 1, y + height - 2, 0xFF2A2A2A);
        context.fill(x + 2, y + 2, x + 4, y + 4, 0xFF888888);
        context.fill(x + width - 4, y + 2, x + width - 2, y + 4, 0xFF888888);
        context.fill(x + 2, y + height - 4, x + 4, y + height - 2, 0xFF888888);
        context.fill(x + width - 4, y + height - 4, x + width - 2, y + height - 2, 0xFF888888);
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

    /** Draw the guide overlay panel for Tier Order screen */
    private void drawGuideOverlay(DrawContext context) {
        int gw = 310, gh = 192;
        int gx = width / 2 - gw / 2;
        int gy = height / 2 - gh / 2;

        context.fill(0, 0, width, height, 0x88000000);

        drawDecoratedPanel(context, gx, gy, gw, gh);

        // Title bar
        context.fill(gx + 4, gy + 4, gx + gw - 4, gy + 20, 0xFF1A1A2E);
        context.drawCenteredTextWithShadow(textRenderer,
            Text.literal("\u00a7e\u00a7lTier Order Config Guide"), width / 2, gy + 8, 0xFFFFFF55);

        int lx = gx + 12, ly = gy + 26, lh = 13;

        context.drawTextWithShadow(textRenderer,
            Text.literal("\u00a7b--- Slot Tier Assignment ---"), lx, ly, 0xFF55FFFF); ly += lh;
        context.drawTextWithShadow(textRenderer,
            Text.literal("\u00a7e[1] \u00a7fClick a slot (only slots with rules can be tiered)"), lx, ly, 0xFFFFFFFF); ly += lh;
        context.drawTextWithShadow(textRenderer,
            Text.literal("\u00a7e[2] \u00a7fType a tier number (1 = highest priority, 41 = lowest)"), lx, ly, 0xFFFFFFFF); ly += lh;
        context.drawTextWithShadow(textRenderer,
            Text.literal("\u00a7e[3] \u00a7fPress 'Set' to apply the tier to the selected slot"), lx, ly, 0xFFFFFFFF); ly += lh;
        context.drawTextWithShadow(textRenderer,
            Text.literal("\u00a7e[4] \u00a7fPress 'X' to clear the tier from the selected slot"), lx, ly, 0xFFFFFFFF); ly += lh + 3;

        context.drawTextWithShadow(textRenderer,
            Text.literal("\u00a7b--- Right Panel (Sort Order) ---"), lx, ly, 0xFF55FFFF); ly += lh;
        context.drawTextWithShadow(textRenderer,
            Text.literal("\u00a7e[5] \u00a7fDrag items in the list to reorder sort priority"), lx, ly, 0xFFFFFFFF); ly += lh;
        context.drawTextWithShadow(textRenderer,
            Text.literal("\u00a7e[6] \u00a7fSort Priority: order of sort criteria applied"), lx, ly, 0xFFFFFFFF); ly += lh;
        context.drawTextWithShadow(textRenderer,
            Text.literal("\u00a7e[7] \u00a7fMaterial/Food/Enchant Order: group ordering"), lx, ly, 0xFFFFFFFF); ly += lh + 6;

        context.fill(gx + 12, ly, gx + gw - 12, ly + 1, 0xFF444444); ly += 6;

        context.drawCenteredTextWithShadow(textRenderer,
            Text.literal("\u00a77Click outside or press \u00a7e[?]\u00a77 to close this guide"), width / 2, ly, 0xFF888888);
    }

    @Override
    public void close() {
        MinecraftClient.getInstance().setScreen(parent);
    }
}
