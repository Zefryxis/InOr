package com.example.inventoryorganizer.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class OrganizerConfig {

    private static final Logger LOGGER = LoggerFactory.getLogger("inventory-organizer/Config");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final File CONFIG_FILE = new File("config/inventory-organizer.json");

    private static OrganizerConfig INSTANCE;

    // Slot rules: key = player inventory slot (0-35), value = rule
    private Map<String, SlotRule> slotRules = new HashMap<>();

    // Auto-switch mode's inventory rules (slot 0-35). Mirrors the plain rules for everything EXCEPT
    // tool/weapon slots, which are independent per mode (see setInventoryRule / isToolRule). When a slot
    // has no auto entry, it falls back to the plain rule (so non-tool changes mirror automatically).
    private Map<String, SlotRule> autoSlotRules = new HashMap<>();
    /** When true, getSlotRule() serves the Auto-switch rule set (set transiently around an SP auto sort). */
    private static boolean autoRuleActive = false;
    public static void setAutoRuleActive(boolean v) { autoRuleActive = v; }

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

    // Warehouse chest links: pressing OST at any member sorts the whole group (server-side).
    private List<WarehouseGroup> warehouseGroups = new ArrayList<>();

    // Warehouse map visibility: chests the player has actually opened (shown on the map), and chests
    // the player chose "Nothing" for (hidden from the map). Each entry = {x, y, z}.
    private List<int[]> knownChests = new ArrayList<>();
    private List<int[]> nothingChests = new ArrayList<>();
    // Positions of "generic" containers — chest-like screens from OTHER mods we can't identify by item/
    // icon. They appear on the warehouse map with a ring marker (○) instead of a chest glyph.
    private List<int[]> genericChests = new ArrayList<>();

    // Configurable HUD overlay: per-element visibility + fractional screen position. All off by default.
    private HudSettings hud = new HudSettings();

    // Remote crafting: when filling a recipe, prefer pulling ingredients from nearby chests (true) vs
    // using the player's own inventory first (false). Default: prefer chests.
    private boolean craftPreferChests = true;

    // Auto-refill master switch (toggled by keybind). When false, ↻-marked slots are NOT auto-refilled
    // in free mode. The manual chest-refill (button/keybind) is unaffected. Default on.
    private boolean autoRefillEnabled = true;

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
        // Default storage presets (protected; act as the size-based fallback for unbound chests)
        storagePresets.add(new StoragePreset("Container", 27));
        storagePresets.add(new StoragePreset("Large Chest", 54));
        storagePresets.add(new StoragePreset("Bundle", 27));
        normalizeProfiles();

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

    /** Configurable HUD overlay settings (never null). */
    public HudSettings getHud() {
        if (hud == null) hud = new HudSettings();
        return hud;
    }

    /** Remote crafting: prefer pulling recipe ingredients from chests over the player's inventory. */
    public boolean isCraftPreferChests() { return craftPreferChests; }
    public void setCraftPreferChests(boolean v) { craftPreferChests = v; }

    public boolean isAutoRefillEnabled() { return autoRefillEnabled; }
    public void setAutoRefillEnabled(boolean v) { autoRefillEnabled = v; }

    public SlotRule getSlotRule(int slot) {
        if (autoRuleActive) {
            SlotRule a = autoSlotRules.get(String.valueOf(slot));
            if (a != null) return a;
        }
        SlotRule rule = slotRules.get(String.valueOf(slot));
        return rule != null ? rule : new SlotRule();
    }

    /** Read an inventory rule for the Auto ({@code auto=true}) or Plain rule set explicitly. The two rule
     *  sets are fully independent — no fallback between them. */
    public SlotRule getInventoryRule(int slot, boolean auto) {
        String k = String.valueOf(slot);
        Map<String, SlotRule> map = auto ? autoSlotRules : slotRules;
        SlotRule r = map.get(k);
        return r != null ? r : new SlotRule();
    }

    /** Write an inventory rule into the Auto or Plain set. The two sets are fully independent. */
    public void setInventoryRule(int slot, SlotRule rule, boolean auto) {
        String k = String.valueOf(slot);
        if (auto) autoSlotRules.put(k, rule);
        else slotRules.put(k, rule);
    }

    /** Copy all inventory slot rules from one mode to the other (one-shot sync button). */
    public void copyRulesToOtherMode(boolean fromAuto) {
        Map<String, SlotRule> src = fromAuto ? autoSlotRules : slotRules;
        Map<String, SlotRule> dst = fromAuto ? slotRules : autoSlotRules;
        dst.clear();
        for (Map.Entry<String, SlotRule> e : src.entrySet()) dst.put(e.getKey(), e.getValue().copy());
    }

    /** A slot rule that targets a tool/weapon (so it must NOT mirror between Plain and Auto modes). */
    public static boolean isToolRule(SlotRule r) {
        if (r == null) return false;
        switch (r.getType()) {
            case SPECIFIC: {
                String v = r.getValue().toLowerCase();
                return v.contains("sword") || v.contains("axe") || v.contains("pickaxe")
                        || v.contains("shovel") || v.contains("hoe");
            }
            case GROUP: case CUSTOM_GROUP: {
                String v = r.getValue().toLowerCase();
                return v.equals("weapons") || v.equals("tools") || v.equals("swords")
                        || v.equals("pickaxes") || v.equals("axes") || v.equals("shovels") || v.equals("hoes");
            }
            case SPECIFIC_ITEM: return isToolItem(r.getValue());
            default: return false;
        }
    }

    private static boolean isToolItem(String id) {
        try {
            net.minecraft.world.item.Item it = net.minecraft.core.registries.BuiltInRegistries.ITEM
                    .getValue(net.minecraft.resources.Identifier.tryParse(id));
            if (it == null) return false;
            net.minecraft.world.item.ItemStack s = new net.minecraft.world.item.ItemStack(it);
            return s.is(net.minecraft.tags.ItemTags.SWORDS) || s.is(net.minecraft.tags.ItemTags.AXES)
                    || s.is(net.minecraft.tags.ItemTags.PICKAXES) || s.is(net.minecraft.tags.ItemTags.SHOVELS)
                    || s.is(net.minecraft.tags.ItemTags.HOES);
        } catch (Throwable ignored) { return false; }
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

    /** Whether the given player inventory slot (0-35) is flagged for auto-refill (free mode only). */
    public boolean isSlotRefill(int slot) {
        return getSlotRule(slot).isRefill();
    }

    /** Toggle the auto-refill flag for a slot, creating the rule entry if needed. */
    public void setSlotRefill(int slot, boolean refill) {
        SlotRule rule = slotRules.get(String.valueOf(slot));
        if (rule == null) { rule = new SlotRule(); slotRules.put(String.valueOf(slot), rule); }
        rule.setRefill(refill);
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

    /** Whether the user has seen the full feature guide (opened automatically on first settings entry). */
    public boolean isHelpSeen() {
        String[] arr = getPreference("help_seen");
        return arr.length > 0 && "true".equals(arr[0]);
    }

    public void setHelpSeen(boolean seen) {
        setPreference("help_seen", new String[]{seen ? "true" : "false"});
    }

    /**
     * Keybind activation mode. One of: "free", "inventory_only", "disabled".
     * Default: "inventory_only" — keybinds only fire while the relevant screen is open.
     * Stored in the generic preferences map under key "keybind_mode" (no schema change).
     */
    public String getKeybindMode() {
        String[] arr = getPreference("keybind_mode");
        if (arr.length > 0 && arr[0] != null && !arr[0].isEmpty()) {
            String v = arr[0];
            if (v.equals("free") || v.equals("inventory_only") || v.equals("disabled")) return v;
        }
        return "inventory_only";
    }

    public void setKeybindMode(String mode) {
        if (!"free".equals(mode) && !"inventory_only".equals(mode) && !"disabled".equals(mode)) {
            mode = "inventory_only";
        }
        setPreference("keybind_mode", new String[]{mode});
    }

    /**
     * Sort keybind action. One of: "oi_only", "ost_only", "smart", "both".
     * Default: "smart" — sort chest if one's open, otherwise sort player inventory.
     * Stored under preferences key "sort_action".
     */
    public String getSortAction() {
        String[] arr = getPreference("sort_action");
        if (arr.length > 0 && arr[0] != null && !arr[0].isEmpty()) {
            String v = arr[0];
            if (v.equals("oi_only") || v.equals("ost_only") || v.equals("smart") || v.equals("both")) return v;
        }
        return "smart";
    }

    public void setSortAction(String action) {
        if (!"oi_only".equals(action) && !"ost_only".equals(action)
                && !"smart".equals(action) && !"both".equals(action)) {
            action = "smart";
        }
        setPreference("sort_action", new String[]{action});
    }

    public boolean isTierOrderUserConfigured() { return tierOrderUserConfigured; }
    public void setTierOrderUserConfigured(boolean v) { this.tierOrderUserConfigured = v; }

    /**
     * Auto-sort after death: when enough snapshotted items are recovered after dying, the
     * inventory is sorted automatically. Opt-in (can be macro-like on servers). Default off.
     * Stored under preferences key "death_autosort" ("true"/"false").
     */
    public boolean isDeathSortEnabled() {
        String[] arr = getPreference("death_autosort");
        return arr.length > 0 && "true".equals(arr[0]);
    }

    public void setDeathSortEnabled(boolean enabled) {
        setPreference("death_autosort", new String[]{enabled ? "true" : "false"});
    }

    // ===== Switch (auto tool-swapper, single-player only) =====
    // Keeps the best tool for the crosshair target in a designated hotbar "switch" slot. Targets map to a
    // CATEGORY (the block's mineable type, or "mob"); each category points at a custom group, and among
    // that group's items present in the inventory the material RANKS (preferences[category]) pick the piece.

    /** The switch tool categories. Each maps to a fixed, editable custom group (below). */
    public static final String[] SWITCH_CATEGORIES = {"pickaxe", "axe", "shovel", "hoe", "mob"};

    /** Reserved, non-deletable custom-group names that hold each category's tools (vanilla items match by
     *  tag automatically; these groups exist so MODDED tools can be added). category → group name. */
    public static String switchGroupName(String category) {
        switch (category) {
            case "pickaxe": return "Pickaxes";
            case "axe":     return "Axes";
            case "shovel":  return "Shovels";
            case "hoe":     return "Hoes";
            case "mob":     return "Swords";
            default:        return "Swords";
        }
    }

    public boolean isSwitchEnabled() {
        String[] a = getPreference("switch_enabled");
        return a.length > 0 && "true".equals(a[0]);
    }
    public void setSwitchEnabled(boolean v) { setPreference("switch_enabled", new String[]{v ? "true" : "false"}); }

    /** Hotbar index (0-8) of the active "switch" slot; -1 = not set. */
    public int getSwitchSlot() {
        String[] a = getPreference("switch_slot");
        try { return a.length > 0 ? Integer.parseInt(a[0]) : -1; } catch (NumberFormatException e) { return -1; }
    }
    public void setSwitchSlot(int slot) { setPreference("switch_slot", new String[]{Integer.toString(slot)}); }

    /** Air-look behaviour: "keep" (leave last tool) or "restore" (put back the previous slot contents). */
    public String getSwitchAir() {
        String[] a = getPreference("switch_air");
        return a.length > 0 ? a[0] : "keep";
    }
    public void setSwitchAir(String mode) { setPreference("switch_air", new String[]{mode}); }

    /** Trigger mode: "auto" = swap on every crosshair change; "button" = only on keybind press. */
    public String getSwitchTrigger() {
        String[] a = getPreference("switch_trigger");
        return a.length > 0 ? a[0] : "auto";
    }
    public void setSwitchTrigger(String mode) { setPreference("switch_trigger", new String[]{mode}); }
    public boolean isSwitchTriggerAuto() { return "auto".equals(getSwitchTrigger()); }

    /** Seed the five fixed switch tool groups (Pickaxes/Axes/Shovels/Hoes/Swords) from their vanilla tags
     *  ONCE, so they are full, normal custom groups (usable as cg: slot rules everywhere) that the player can
     *  extend with modded tools. Needs tags loaded (call in-world); no-op if already seeded. */
    public void materializeSwitchGroupsOnce() {
        String[] v = getPreference("switch_groups_v");
        if (v.length > 0 && "1".equals(v[0])) return;
        int total = seedSwitchGroup("Pickaxes", net.minecraft.tags.ItemTags.PICKAXES)
                + seedSwitchGroup("Axes", net.minecraft.tags.ItemTags.AXES)
                + seedSwitchGroup("Shovels", net.minecraft.tags.ItemTags.SHOVELS)
                + seedSwitchGroup("Hoes", net.minecraft.tags.ItemTags.HOES)
                + seedSwitchGroup("Swords", net.minecraft.tags.ItemTags.SWORDS);
        if (total > 0) setPreference("switch_groups_v", new String[]{"1"}); // else retry later (tags not ready)
    }

    private int seedSwitchGroup(String name, net.minecraft.tags.TagKey<net.minecraft.world.item.Item> tag) {
        if (!getCustomGroup(name).isEmpty()) return 1; // already has content — leave the player's edits alone
        java.util.List<String> ids = new ArrayList<>();
        try {
            for (net.minecraft.world.item.Item it : net.minecraft.core.registries.BuiltInRegistries.ITEM) {
                if (new net.minecraft.world.item.ItemStack(it).is(tag)) {
                    ids.add(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(it).toString());
                }
            }
        } catch (Throwable ignored) {}
        if (!ids.isEmpty()) setCustomGroup(name, ids);
        return ids.size();
    }


    // ===== Free-mode server whitelist =====
    // The master quick-switch on the slot config screen is just a preset that flips the
    // existing keybind-mode + death-autosort toggles; it has no preference of its own.
    // Free behaviour is additionally gated by ServerEnvironment (private environments +
    // this whitelist).

    /**
     * Whether the whitelist feature is unlocked in Special Settings. Hidden by default; the user
     * must explicitly enable it after acknowledging the "use at your own risk" warning. Stored under
     * preferences key "whitelist_enabled" ("true"/"false").
     */
    public boolean isWhitelistEnabled() {
        String[] arr = getPreference("whitelist_enabled");
        return arr.length > 0 && "true".equals(arr[0]);
    }

    public void setWhitelistEnabled(boolean enabled) {
        setPreference("whitelist_enabled", new String[]{enabled ? "true" : "false"});
    }

    // ===== Trash / void list =====
    // Items matching any of these rules ("g:..", "t:..", "pot:..", or a full item id) are auto-dropped
    // in free mode the moment they enter your inventory. Edited via the "Trash" tab (a 54-slot grid).

    public List<String> getTrashRules() {
        return new ArrayList<>(java.util.Arrays.asList(getPreference("trash_rules")));
    }

    /** When true, trash keeps up to one stack of each trashed item and only drops the excess. */
    public boolean isTrashOverflowOnly() {
        String[] arr = getPreference("trash_overflow_only");
        return arr.length > 0 && "true".equals(arr[0]);
    }

    public void setTrashOverflowOnly(boolean v) {
        setPreference("trash_overflow_only", new String[]{v ? "true" : "false"});
    }

    public void setTrashRules(List<String> rules) {
        List<String> clean = new ArrayList<>();
        if (rules != null) {
            for (String r : rules) {
                if (r != null && !r.isEmpty() && !r.equals("any") && !r.equals("empty")) clean.add(r);
            }
        }
        setPreference("trash_rules", clean.toArray(new String[0]));
    }

    public List<String> getServerWhitelist() {
        return new ArrayList<>(java.util.Arrays.asList(getPreference("server_whitelist")));
    }

    public void addServerWhitelist(String host) {
        if (host == null || host.isEmpty()) return;
        List<String> list = getServerWhitelist();
        if (!list.contains(host)) {
            list.add(host);
            setPreference("server_whitelist", list.toArray(new String[0]));
        }
    }

    public void removeServerWhitelist(String host) {
        List<String> list = getServerWhitelist();
        if (list.remove(host)) {
            setPreference("server_whitelist", list.toArray(new String[0]));
        }
    }

    // ===== Built-in group customization =====
    // Built-in groups (weapons, tools, blocks, ...) are normally defined by heuristics in
    // InventorySorter. The user can override a built-in group's membership with an explicit
    // item list. We track which groups have been overridden in "builtin_overridden" so an
    // empty override list (user removed everything) is still respected instead of falling
    // back to the heuristic.

    public boolean hasBuiltinOverride(String groupName) {
        for (String s : getPreference("builtin_overridden")) {
            if (s.equals(groupName)) return true;
        }
        return false;
    }

    /** Returns the explicit item-ID list for a built-in group, or null if it uses the default heuristic. */
    public List<String> getBuiltinGroupItems(String groupName) {
        if (!hasBuiltinOverride(groupName)) return null;
        return new ArrayList<>(java.util.Arrays.asList(getPreference("builtin_group_" + groupName)));
    }

    public void setBuiltinGroupItems(String groupName, List<String> items) {
        setPreference("builtin_group_" + groupName, items.toArray(new String[0]));
        List<String> ov = new ArrayList<>(java.util.Arrays.asList(getPreference("builtin_overridden")));
        if (!ov.contains(groupName)) {
            ov.add(groupName);
            setPreference("builtin_overridden", ov.toArray(new String[0]));
        }
    }

    /** Remove the override so the built-in group reverts to its default heuristic membership. */
    public void resetBuiltinGroup(String groupName) {
        getPreferences().remove("builtin_group_" + groupName);
        List<String> ov = new ArrayList<>(java.util.Arrays.asList(getPreference("builtin_overridden")));
        ov.remove(groupName);
        setPreference("builtin_overridden", ov.toArray(new String[0]));
    }

    public boolean isBuiltinHidden(String groupName) {
        for (String s : getPreference("builtin_hidden")) {
            if (s.equals(groupName)) return true;
        }
        return false;
    }

    public void setBuiltinHidden(String groupName, boolean hidden) {
        List<String> list = new ArrayList<>(java.util.Arrays.asList(getPreference("builtin_hidden")));
        list.remove(groupName);
        if (hidden) list.add(groupName);
        setPreference("builtin_hidden", list.toArray(new String[0]));
    }

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

    /**
     * One-time migration: turn the former built-in groups (weapons, tools, blocks, …) into REAL
     * custom groups, generated from the heuristic membership. After this they behave exactly like a
     * hand-made group everywhere — editable membership, draggable order in the ranks screen, and the
     * sorter resolves {@code g:NAME} rules through this list. Guarded by a flag so it never clobbers
     * user edits (a deleted built-in group stays deleted). Must run with the item registry available
     * (called from client init), since {@link com.example.inventoryorganizer.InventorySorter#getDefaultItemsForGroup}
     * scans the registry.
     */
    public void materializeBuiltinGroupsOnce() {
        // Versioned so a heuristic fix can refresh the generated built-in groups exactly once.
        // v1: initial materialization. v2: regenerate after the explicit item fix (dragon's breath,
        // blaze rod, … out of blocks). v3 was a BlockItem-guard experiment that broke the blocks group,
        // now reverted. v4: regenerate once more with the reverted, working heuristic so anyone who got
        // the broken v3 lists is repaired. v5: the "blocks" group now contains EVERY placeable block
        // (Block.byItem). v6: food/tools/weapons/armor/arrows now generated from the game's own data
        // (food component + item tags) for full coverage. v7: logs + boats also from item tags.
        // v8: group-content overhaul per user spec — blocks = full solid blocks only (collision shape),
        // tools = broad usable items, weapons += axes, nether/end now include their mob-drop/loot items.
        // v9: regenerate once tags are actually loaded — v8 ran at client init before tag binding, so the
        // tag-based groups (arrows/logs/boats/tools/weapons/armor) were stored EMPTY/under-populated.
        // v10: 26.1's item tags proved unreliable even in-world (arrows/logs/boats/armor/mining-tools came
        // out EMPTY while swords/axes worked), so getDefaultItemsForGroup now has an item-id fallback for
        // every tag-based group. Generation no longer depends on tags, so we drop the tags-loaded guard and
        // regenerate unconditionally to repair everyone stuck with empty groups at v9.
        // v11: "food" group was empty — the FOOD data component is unreliable in 26.1 (1.21.2 consumable
        // refactor). getDefaultItemsForGroup now also matches an explicit vanilla food-id list (FOOD_IDS).
        final int CURRENT = 11;
        String[] done = getPreference("builtin_groups_materialized");
        int have = 0;
        if (done.length > 0) {
            if ("true".equals(done[0])) have = 1;
            else try { have = Integer.parseInt(done[0]); } catch (NumberFormatException ignored) {}
        }
        if (have >= CURRENT) return;
        for (String name : com.example.inventoryorganizer.InventorySorter.BUILTIN_GROUP_NAMES) {
            // have < 2: refresh the built-in groups to the corrected membership (overwrites the prior
            // auto-generated list; hand-made custom groups are never touched). have == 0: also creates.
            setCustomGroup(name, com.example.inventoryorganizer.InventorySorter.getDefaultItemsForGroup(name));
            // Drop the stale rank order so it rebuilds cleanly from the corrected membership — otherwise
            // a previously-saved cg_order_ could still list an item the fix just removed (e.g. dragon's
            // breath in blocks) and the sorter, which honours cg_order, would re-place it there.
            preferences.remove("cg_order_" + name);
        }
        setPreference("builtin_groups_materialized", new String[]{String.valueOf(CURRENT)});
        save();
    }

    /** The three protected default profiles, in fixed order/id. */
    private static final String[] DEFAULT_NAMES = {"Container", "Large Chest", "Bundle"};
    private static final int[]    DEFAULT_SIZES = {27, 54, 27};
    public static final int DEFAULT_COUNT = 3;

    public List<StoragePreset> getStoragePresets() {
        normalizeProfiles();
        return storagePresets;
    }

    /**
     * Ensures the list is well-formed: the three protected defaults sit at the front with stable
     * ids 0/1/2 and canonical names, and every per-chest profile after them is non-default with a
     * unique id ≥ 3. Idempotent and cheap so it can run from getStoragePresets() each frame.
     */
    private void normalizeProfiles() {
        if (storagePresets == null) storagePresets = new ArrayList<>();
        // Legacy migration: old "Chest 1" → "Container", drop the removed "Custom" preset.
        storagePresets.removeIf(p -> "Custom".equals(p.getName()));
        for (StoragePreset p : storagePresets) {
            if ("Chest 1".equals(p.getName())) p.setName("Container");
        }
        // Guarantee the three defaults exist at the front.
        while (storagePresets.size() < DEFAULT_COUNT) {
            int i = storagePresets.size();
            storagePresets.add(new StoragePreset(DEFAULT_NAMES[i], DEFAULT_SIZES[i]));
        }
        // Force the defaults' identity (protected, canonical name, fixed id, never bound).
        for (int i = 0; i < DEFAULT_COUNT; i++) {
            StoragePreset p = storagePresets.get(i);
            p.setId(i);
            p.setDefault(true);
            p.setName(DEFAULT_NAMES[i]);
            p.setCustomName(null);
            p.setSignText(null);
            p.clearPositions();
        }
        // Per-chest profiles: non-default, unique ids ≥ 3.
        java.util.Set<Integer> used = new java.util.HashSet<>(java.util.Arrays.asList(0, 1, 2));
        int next = DEFAULT_COUNT;
        for (int i = DEFAULT_COUNT; i < storagePresets.size(); i++) {
            StoragePreset p = storagePresets.get(i);
            p.setDefault(false);
            int id = p.getId();
            if (id < DEFAULT_COUNT || used.contains(id)) {
                while (used.contains(next)) next++;
                id = next;
                p.setId(id);
            }
            used.add(id);
        }
    }

    /** Smallest free profile id ≥ 3. */
    public int getNextProfileId() {
        int max = DEFAULT_COUNT - 1;
        for (StoragePreset p : getStoragePresets()) max = Math.max(max, p.getId());
        return max + 1;
    }

    /** Create a new (unbound) per-chest profile and append it. */
    public StoragePreset addProfile(String name, int size) {
        StoragePreset p = new StoragePreset(name, size);
        p.setDefault(false);
        p.setId(getNextProfileId());
        getStoragePresets().add(p);
        return p;
    }

    /** Duplicate any profile into a fresh, unbound per-chest profile (tier data is copied too). */
    public StoragePreset duplicateProfile(StoragePreset src) {
        StoragePreset c = src.copy();
        c.setName(src.getName() + " (copy)");
        c.setId(getNextProfileId());
        getStoragePresets().add(c);
        // Tier assignments live in preferences keyed by id — copy them to the new id.
        String[] srcTiers = getPreference("tier_order_storage_" + src.getId());
        if (srcTiers.length > 0) setPreference("tier_order_storage_" + c.getId(), srcTiers.clone());
        return c;
    }

    /** Remove a per-chest profile (defaults are protected and ignored) and drop its tier data. */
    public void removeProfile(StoragePreset p) {
        if (p == null || p.isDefault()) return;
        getStoragePresets().remove(p);
        getPreferences().remove("tier_order_storage_" + p.getId());
        // The chest(s) this profile was bound to lose their profile → drop them from the warehouse
        // map (and any link), so a chest "that had a profile but no longer does" disappears. They
        // come back if the player opens them again (re-learned as a known chest).
        List<int[]> pos = p.getPositions();
        if (pos != null) {
            for (int[] q : pos) {
                if (q.length == 3) {
                    final int x = q[0], y = q[1], z = q[2];
                    getKnownChests().removeIf(a -> a.length == 3 && a[0] == x && a[1] == y && a[2] == z);
                    detachFromGroups(x, y, z);
                }
            }
        }
    }

    /** Index of a profile in the live list, or -1. */
    public int indexOfProfile(StoragePreset p) {
        return getStoragePresets().indexOf(p);
    }

    /** Look up a profile by stable id, or null. */
    public StoragePreset getProfileById(int id) {
        for (StoragePreset p : getStoragePresets()) {
            if (p.getId() == id) return p;
        }
        return null;
    }

    /**
     * Resolve the per-chest profile bound to an opened chest, or null if none.
     * Priority: (1) anvil custom name (survives relocation), (2) coordinates within ±1 block.
     * Only bound, non-default profiles are considered.
     */
    public StoragePreset findProfileFor(String chestCustomName, List<int[]> positions, String signText) {
        // (1) anvil custom name
        if (chestCustomName != null && !chestCustomName.isEmpty()) {
            for (StoragePreset p : getStoragePresets()) {
                if (!p.isDefault() && p.matchesName(chestCustomName)) return p;
            }
        }
        // (2) EXACT coordinate match against any of the chest's current position(s). Both halves of a
        // double chest are passed in, so doubles still match without a ±1 tolerance — which would
        // otherwise wrongly match an *adjacent* unrelated chest (neighbours are 1 block apart).
        if (positions != null) {
            for (StoragePreset p : getStoragePresets()) {
                if (p.isDefault()) continue;
                for (int[] pos : positions) {
                    if (pos.length == 3 && p.matchesPosition(pos[0], pos[1], pos[2], 0)) return p;
                }
            }
        }
        // (3) adjacent-sign text (lets a relocated chest re-attach if its sign moves with it)
        if (signText != null && !signText.isEmpty()) {
            for (StoragePreset p : getStoragePresets()) {
                if (!p.isDefault() && p.matchesSign(signText)) return p;
            }
        }
        return null;
    }

    /** The size-based default profile (Container 27 / Large Chest 54) for an unbound chest, or null. */
    public StoragePreset getDefaultForSize(int containerSize) {
        for (StoragePreset p : getStoragePresets()) {
            if (p.isDefault() && !"Bundle".equalsIgnoreCase(p.getName()) && p.getSize() == containerSize) {
                return p;
            }
        }
        return null;
    }

    // ===== Warehouse chest links =====

    public List<WarehouseGroup> getWarehouseGroups() {
        if (warehouseGroups == null) warehouseGroups = new ArrayList<>();
        // drop any degenerate group (fewer than 2 chests)
        warehouseGroups.removeIf(g -> g.getPositions().size() < 2);
        return warehouseGroups;
    }

    /** Create a link from a set of chest positions. A chest can only be in one group, so any of these
     *  positions are first detached from existing groups. Needs at least 2 chests. */
    public void addWarehouseGroup(List<int[]> positions) {
        if (positions == null || positions.size() < 2) return;
        for (int[] p : positions) detachFromGroups(p[0], p[1], p[2]);
        WarehouseGroup g = new WarehouseGroup();
        for (int[] p : positions) g.add(p[0], p[1], p[2]);
        getWarehouseGroups().add(g);
    }

    /** The group a chest belongs to, or null. */
    public WarehouseGroup findWarehouseGroupFor(int x, int y, int z) {
        for (WarehouseGroup g : getWarehouseGroups()) {
            if (g.contains(x, y, z)) return g;
        }
        return null;
    }

    /** Remove a chest from whatever group it's in (and drop the group if it becomes too small). */
    public void detachFromGroups(int x, int y, int z) {
        for (WarehouseGroup g : getWarehouseGroups()) g.remove(x, y, z);
        getWarehouseGroups().removeIf(g -> g.getPositions().size() < 2);
    }

    // ===== Warehouse map visibility (known / "Nothing" chests) =====

    public List<int[]> getKnownChests() {
        if (knownChests == null) knownChests = new ArrayList<>();
        return knownChests;
    }

    public List<int[]> getNothingChests() {
        if (nothingChests == null) nothingChests = new ArrayList<>();
        return nothingChests;
    }

    private static boolean posListContains(List<int[]> list, int x, int y, int z) {
        for (int[] p : list) if (p.length == 3 && p[0] == x && p[1] == y && p[2] == z) return true;
        return false;
    }

    public boolean isNothingChest(int x, int y, int z) { return posListContains(getNothingChests(), x, y, z); }

    /** Record a chest the player has opened (shown on the map). Returns true if newly added. */
    public boolean addKnownChest(int x, int y, int z) {
        if (isNothingChest(x, y, z) || posListContains(getKnownChests(), x, y, z)) return false;
        getKnownChests().add(new int[]{x, y, z});
        return true;
    }

    /** Mark a chest as "Nothing": hide it from the map, forget it as known, and unlink it. */
    public void markNothingChest(int x, int y, int z) {
        if (!isNothingChest(x, y, z)) getNothingChests().add(new int[]{x, y, z});
        getKnownChests().removeIf(p -> p.length == 3 && p[0] == x && p[1] == y && p[2] == z);
        getGenericChests().removeIf(p -> p.length == 3 && p[0] == x && p[1] == y && p[2] == z);
        detachFromGroups(x, y, z);
    }

    // ===== Generic (unidentified / modded) containers =====

    public List<int[]> getGenericChests() {
        if (genericChests == null) genericChests = new ArrayList<>();
        return genericChests;
    }

    public boolean isGenericChest(int x, int y, int z) { return posListContains(getGenericChests(), x, y, z); }

    /** Mark a chest position as a generic (unidentified) container. Returns true if newly added. */
    public boolean addGenericChest(int x, int y, int z) {
        if (isGenericChest(x, y, z)) return false;
        getGenericChests().add(new int[]{x, y, z});
        return true;
    }

    public List<Kit> getKits() {
        if (kits == null) kits = new ArrayList<>();
        return kits;
    }

    public void saveCurrentAsKit(String name, boolean auto) {
        Map<String, SlotRule> src = auto ? autoSlotRules : slotRules;
        Kit kit = new Kit(name, src, preferences);
        if (kits == null) kits = new ArrayList<>();
        kits.add(kit);
    }

    public void loadKit(Kit kit, boolean auto) {
        Map<String, SlotRule> dst = auto ? autoSlotRules : slotRules;
        dst.clear();
        for (Map.Entry<String, SlotRule> entry : kit.getSlotRules().entrySet()) {
            dst.put(entry.getKey(), entry.getValue().copy());
        }
        // Ensure all 36 slots exist
        for (int i = 0; i < 36; i++) {
            if (!dst.containsKey(String.valueOf(i))) {
                dst.put(String.valueOf(i), new SlotRule());
            }
        }
        // Overwrite preferences
        preferences.clear();
        for (Map.Entry<String, String[]> entry : kit.getPreferences().entrySet()) {
            preferences.put(entry.getKey(), entry.getValue().clone());
        }
    }

    public void saveToKit(int index, boolean auto) {
        if (kits != null && index >= 0 && index < kits.size()) {
            String name = kits.get(index).getName();
            Map<String, SlotRule> src = auto ? autoSlotRules : slotRules;
            kits.set(index, new Kit(name, src, preferences));
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
            LOGGER.error("Failed to save config to {}", CONFIG_FILE.getAbsolutePath(), e);
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
                    // Ensure defaults exist + per-chest profiles have stable ids
                    config.normalizeProfiles();
                    // One-time migration: if Auto rules are empty, seed them from Plain so existing users
                    // don't lose their non-tool slot setup when the mirroring is removed.
                    if (config.autoSlotRules == null) config.autoSlotRules = new HashMap<>();
                    if (config.autoSlotRules.isEmpty() && !config.slotRules.isEmpty()) {
                        for (Map.Entry<String, SlotRule> e : config.slotRules.entrySet())
                            config.autoSlotRules.put(e.getKey(), e.getValue().copy());
                    }
                    return config;
                }
            } catch (Exception e) {
                LOGGER.error("Failed to load config from {} (falling back to defaults)", CONFIG_FILE.getAbsolutePath(), e);
            }
        }
        OrganizerConfig config = new OrganizerConfig();
        config.save();
        return config;
    }

    public static void reload() {
        INSTANCE = load();
    }

    // ===== Whole-config backup (single file holding EVERYTHING: groups+contents, profiles, slot rules,
    // kits, HUD, prefs). Lives in the kits folder so users can carry it between instances. =====

    /** The single backup file path (in the kits folder). */
    public static java.nio.file.Path backupFile() throws java.io.IOException {
        return KitFile.getKitsFolder().resolve("inventory-organizer-backup.txt");
    }

    /** Write the entire current config to the backup file. Returns true on success. */
    public static boolean exportBackup() {
        try {
            get().save(); // flush live state to the config file first
            java.nio.file.Files.copy(CONFIG_FILE.toPath(), backupFile(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            return true;
        } catch (Exception e) {
            LOGGER.error("Failed to export config backup", e);
            return false;
        }
    }

    /** Restore the entire config from the backup file (full replace). Returns true on success. */
    public static boolean importBackup() {
        try {
            java.nio.file.Path f = backupFile();
            if (!java.nio.file.Files.exists(f)) return false;
            OrganizerConfig parsed;
            try (java.io.Reader r = java.nio.file.Files.newBufferedReader(f)) {
                parsed = GSON.fromJson(r, OrganizerConfig.class);
            }
            if (parsed == null) return false;
            // Copy fields INTO the existing singleton so screens holding a config reference stay valid.
            get().copyFrom(parsed);
            get().save();
            return true;
        } catch (Exception e) {
            LOGGER.error("Failed to import config backup", e);
            return false;
        }
    }

    /** Make sure all 36 inventory slots + equipment slots exist and profiles are normalized. */
    private void ensureSlotsAndProfiles() {
        for (int i = 0; i < 36; i++) {
            if (!slotRules.containsKey(String.valueOf(i))) slotRules.put(String.valueOf(i), new SlotRule());
        }
        for (String key : EQUIPMENT_SLOTS) {
            if (!slotRules.containsKey(key)) slotRules.put(key, new SlotRule());
        }
        normalizeProfiles();
    }

    /** Copy every persisted field from {@code o} into this instance (keeps INSTANCE identity). */
    void copyFrom(OrganizerConfig o) {
        if (o.slotRules != null) this.slotRules = o.slotRules;
        if (o.autoSlotRules != null) this.autoSlotRules = o.autoSlotRules;
        if (o.preferences != null) this.preferences = o.preferences;
        if (o.kits != null) this.kits = o.kits;
        this.showHelp = o.showHelp;
        if (o.customGroups != null) this.customGroups = o.customGroups;
        if (o.storagePresets != null) this.storagePresets = o.storagePresets;
        if (o.warehouseGroups != null) this.warehouseGroups = o.warehouseGroups;
        if (o.knownChests != null) this.knownChests = o.knownChests;
        if (o.nothingChests != null) this.nothingChests = o.nothingChests;
        if (o.genericChests != null) this.genericChests = o.genericChests;
        if (o.hud != null) this.hud = o.hud;
        this.craftPreferChests = o.craftPreferChests;
        this.autoRefillEnabled = o.autoRefillEnabled;
        ensureSlotsAndProfiles();
    }
}
