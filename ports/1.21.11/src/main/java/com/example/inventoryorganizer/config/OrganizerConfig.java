package com.example.inventoryorganizer.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class OrganizerConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final File CONFIG_FILE = new File("config/inventory-organizer.json");

    private static OrganizerConfig INSTANCE;

    // Slot rules: key = player inventory slot (0-35), value = rule
    private Map<String, SlotRule> slotRules = new HashMap<>();

    // Item preferences per category: key = group name, value = ordered list of preferred item ids
    private Map<String, String[]> preferences = new HashMap<>();

    // Saved kits (presets)
    private List<Kit> kits = new ArrayList<>();

    // Show help tips in UI
    private boolean showHelp = true;

    // Session-only flag (transient = not persisted in JSON).
    // False on every game start. Set to true when user clicks Solve or saves Tier Order.
    // Used to show the "Tier Order not set" warning in VisualInventoryConfigScreen.
    private transient boolean tierOrderUserConfigured = false;

    // User-defined custom item groups: key = group name, value = ordered list of item IDs
    private Map<String, List<String>> customGroups = new HashMap<>();

    // Storage device presets (3 configurable chest/container layouts)
    private List<StoragePreset> storagePresets = new ArrayList<>();

    // Special slot keys for armor and offhand
    public static final String SLOT_ARMOR_HEAD = "armor_head";
    public static final String SLOT_ARMOR_CHEST = "armor_chest";
    public static final String SLOT_ARMOR_LEGS = "armor_legs";
    public static final String SLOT_ARMOR_FEET = "armor_feet";
    public static final String SLOT_OFFHAND = "offhand";
    public static final String[] EQUIPMENT_SLOTS = { SLOT_ARMOR_HEAD, SLOT_ARMOR_CHEST, SLOT_ARMOR_LEGS, SLOT_ARMOR_FEET, SLOT_OFFHAND };

    public OrganizerConfig() {
        // Default: all slots are ANY
        for (int i = 0; i < 36; i++) {
            slotRules.put(String.valueOf(i), new SlotRule());
        }
        // Default: equipment slots are ANY
        for (String key : EQUIPMENT_SLOTS) {
            slotRules.put(key, new SlotRule());
        }
        // Default storage presets
        storagePresets.add(new StoragePreset("Container", 27));
        storagePresets.add(new StoragePreset("Large Chest", 54));
        storagePresets.add(new StoragePreset("Bundle", 27));

        // Default preferences: material order per item type (best first)
        String[] tierOrder = {"netherite", "diamond", "iron", "golden", "stone", "wooden"};
        String[] armorTierOrder = {"netherite", "diamond", "iron", "golden", "chainmail", "leather"};
        preferences.put("sword", tierOrder);
        preferences.put("pickaxe", tierOrder);
        preferences.put("axe", tierOrder);
        preferences.put("shovel", tierOrder);
        preferences.put("hoe", tierOrder);
        preferences.put("helmet", armorTierOrder);
        preferences.put("chestplate", armorTierOrder);
        preferences.put("leggings", armorTierOrder);
        preferences.put("boots", armorTierOrder);
    }

    /** Applies the default tier_order (hotbar=1, main=2). Always overwrites existing values. */
    public void applyDefaultTierOrder() {
        java.util.List<String> defaults = new java.util.ArrayList<>();
        for (int i = 0; i <= 8; i++)  defaults.add("slot_" + i + "_tier_1");   // hotbar
        for (int i = 9; i <= 35; i++) defaults.add("slot_" + i + "_tier_2");   // main inv
        defaults.add("slot_100_tier_1"); // armor head
        defaults.add("slot_101_tier_1"); // armor chest
        defaults.add("slot_102_tier_1"); // armor legs
        defaults.add("slot_103_tier_1"); // armor feet
        defaults.add("slot_104_tier_1"); // offhand
        setPreference("tier_order", defaults.toArray(new String[0]));
    }

    public static OrganizerConfig get() {
        if (INSTANCE == null) {
            INSTANCE = load();
        }
        return INSTANCE;
    }

    public SlotRule getSlotRule(int slot) {
        SlotRule rule = slotRules.get(String.valueOf(slot));
        return rule != null ? rule : new SlotRule();
    }

    public SlotRule getSlotRuleByKey(String key) {
        SlotRule rule = slotRules.get(key);
        return rule != null ? rule : new SlotRule();
    }

    public void setSlotRule(int slot, SlotRule rule) {
        slotRules.put(String.valueOf(slot), rule);
    }

    public void setSlotRuleByKey(String key, SlotRule rule) {
        slotRules.put(key, rule);
    }

    public Map<String, SlotRule> getSlotRules() {
        return slotRules;
    }

    public Map<String, String[]> getPreferences() {
        return preferences;
    }

    public String[] getPreference(String category) {
        return preferences.getOrDefault(category, new String[0]);
    }

    public void setPreference(String category, String[] items) {
        preferences.put(category, items);
    }

    /** Get the tier number for a specific storage preset slot, or null if unset. */
    public Integer getStorageTier(int presetIdx, int slot) {
        String key = "tier_order_storage_" + presetIdx;
        String prefix = "slot_" + slot + "_tier_";
        for (String entry : getPreference(key)) {
            if (entry.startsWith(prefix)) {
                try { return Integer.parseInt(entry.substring(prefix.length())); }
                catch (NumberFormatException ignored) {}
            }
        }
        return null;
    }

    /** Set (or clear when tier==null) the tier for a storage preset slot. */
    public void setStorageTier(int presetIdx, int slot, Integer tier) {
        String key = "tier_order_storage_" + presetIdx;
        java.util.List<String> entries = new java.util.ArrayList<>(java.util.Arrays.asList(getPreference(key)));
        entries.removeIf(e -> e.startsWith("slot_" + slot + "_tier_"));
        if (tier != null && tier >= 1) entries.add("slot_" + slot + "_tier_" + tier);
        setPreference(key, entries.toArray(new String[0]));
    }

    public boolean isShowHelp() { return showHelp; }
    public void setShowHelp(boolean showHelp) { this.showHelp = showHelp; }

    public boolean isTierOrderUserConfigured() { return tierOrderUserConfigured; }
    public void setTierOrderUserConfigured(boolean v) { this.tierOrderUserConfigured = v; }

    public Map<String, List<String>> getCustomGroups() {
        if (customGroups == null) customGroups = new HashMap<>();
        return customGroups;
    }

    public List<String> getCustomGroup(String name) {
        return getCustomGroups().getOrDefault(name, new ArrayList<>());
    }

    public void setCustomGroup(String name, List<String> itemIds) {
        getCustomGroups().put(name, new ArrayList<>(itemIds));
    }

    public void deleteCustomGroup(String name) {
        getCustomGroups().remove(name);
        preferences.remove("cg_order_" + name);
    }

    public List<String> getCustomGroupNames() {
        List<String> names = new ArrayList<>(getCustomGroups().keySet());
        java.util.Collections.sort(names);
        return names;
    }

    public List<StoragePreset> getStoragePresets() {
        if (storagePresets == null) storagePresets = new ArrayList<>();
        // Migration: rename old "Chest 1" → "Container", remove "Custom"
        storagePresets.removeIf(p -> "Custom".equals(p.getName()));
        for (StoragePreset p : storagePresets) {
            if ("Chest 1".equals(p.getName())) p.setName("Container");
        }
        String[] defaultNames = {"Container", "Large Chest", "Bundle"};
        int[] defaultSizes  = {27, 54, 27};
        while (storagePresets.size() < defaultNames.length) {
            int i = storagePresets.size();
            storagePresets.add(new StoragePreset(defaultNames[i], defaultSizes[i]));
        }
        return storagePresets;
    }

    public List<Kit> getKits() {
        if (kits == null) kits = new ArrayList<>();
        return kits;
    }

    public void saveCurrentAsKit(String name) {
        Kit kit = new Kit(name, slotRules, preferences);
        if (kits == null) kits = new ArrayList<>();
        kits.add(kit);
    }

    public void loadKit(Kit kit) {
        // Overwrite current slot rules with kit's saved rules
        slotRules.clear();
        for (Map.Entry<String, SlotRule> entry : kit.getSlotRules().entrySet()) {
            slotRules.put(entry.getKey(), entry.getValue().copy());
        }
        // Ensure all 36 slots exist
        for (int i = 0; i < 36; i++) {
            if (!slotRules.containsKey(String.valueOf(i))) {
                slotRules.put(String.valueOf(i), new SlotRule());
            }
        }
        // Overwrite preferences
        preferences.clear();
        for (Map.Entry<String, String[]> entry : kit.getPreferences().entrySet()) {
            preferences.put(entry.getKey(), entry.getValue().clone());
        }
    }

    public void saveToKit(int index) {
        if (kits != null && index >= 0 && index < kits.size()) {
            String name = kits.get(index).getName();
            kits.set(index, new Kit(name, slotRules, preferences));
        }
    }

    public void deleteKit(int index) {
        if (kits != null && index >= 0 && index < kits.size()) {
            kits.remove(index);
        }
    }

    public void save() {
        try {
            CONFIG_FILE.getParentFile().mkdirs();
            try (FileWriter writer = new FileWriter(CONFIG_FILE)) {
                GSON.toJson(this, writer);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public static OrganizerConfig load() {
        if (CONFIG_FILE.exists()) {
            try (FileReader reader = new FileReader(CONFIG_FILE)) {
                OrganizerConfig config = GSON.fromJson(reader, OrganizerConfig.class);
                if (config != null) {
                    // Ensure all 36 slots exist
                    for (int i = 0; i < 36; i++) {
                        if (!config.slotRules.containsKey(String.valueOf(i))) {
                            config.slotRules.put(String.valueOf(i), new SlotRule());
                        }
                    }
                    // Ensure equipment slots exist
                    for (String key : EQUIPMENT_SLOTS) {
                        if (!config.slotRules.containsKey(key)) {
                            config.slotRules.put(key, new SlotRule());
                        }
                    }
                    // Ensure 3 storage presets exist
                    if (config.storagePresets == null || config.storagePresets.isEmpty()) {
                        config.storagePresets = new ArrayList<>();
                        config.storagePresets.add(new StoragePreset("Chest 1", 27));
                        config.storagePresets.add(new StoragePreset("Large Chest", 54));
                        config.storagePresets.add(new StoragePreset("Custom", 27));
                    }
                    while (config.storagePresets.size() < 3) {
                        config.storagePresets.add(new StoragePreset("Preset " + (config.storagePresets.size() + 1), 27));
                    }
                    return config;
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        OrganizerConfig config = new OrganizerConfig();
        config.save();
        return config;
    }

    public static void reload() {
        INSTANCE = load();
    }
}
