package com.example.inventoryorganizer;

import com.example.inventoryorganizer.config.OrganizerConfig;
import com.example.inventoryorganizer.config.SlotRule;
import com.example.inventoryorganizer.config.StoragePreset;
import net.minecraft.component.type.PotionContentsComponent;
import net.minecraft.client.MinecraftClient;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.BundleContentsComponent;
import net.minecraft.component.type.ItemEnchantmentsComponent;
import org.apache.commons.lang3.math.Fraction;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class InventorySorter {

    private static final Logger LOGGER = LoggerFactory.getLogger("inventory-organizer/Sorter");

    // Category constants
    private static final int CAT_WEAPON = 0;
    private static final int CAT_TOOL = 1;
    private static final int CAT_ARMOR = 2;
    private static final int CAT_VALUABLE = 3;
    private static final int CAT_BLOCK = 4;
    private static final int CAT_FOOD = 5;
    private static final int CAT_UTILITY = 6;
    private static final int CAT_POTION = 7;
    private static final int CAT_MISC = 8;
    // Sub-groups (more specific, checked first in getCategory)
    private static final int CAT_LOG      = 9;   // fa log-ok
    private static final int CAT_BOAT     = 10;  // hajók
    private static final int CAT_PLANT    = 11;  // növények
    private static final int CAT_STONE    = 12;  // kő fajták
    private static final int CAT_ORE      = 13;  // ércek
    private static final int CAT_COOKED   = 14;  // sült kaják
    private static final int CAT_RAW_FOOD = 15;  // nyers kaják
    private static final int CAT_NETHER   = 16;  // nether cuccok
    private static final int CAT_END      = 17;  // end cuccok
    private static final int CAT_PARTIAL  = 18;  // nem egész block-ok
    private static final int CAT_REDSTONE = 19;  // redstone cuccok
    private static final int CAT_CREATIVE = 20;  // creative cuccok
    private static final int CAT_ARROW          = 21;  // nyilak
    private static final int CAT_SPLASH_POTION  = 22;  // csak splash potion group

    // InventoryScreen slot mapping:
    // Slot 0: crafting output
    // Slots 1-4: crafting input
    // Slots 5-8: armor slots
    // Slot 45: offhand
    // Slots 9-35: main inventory (PlayerInventory 9-35)
    // Slots 36-44: hotbar (PlayerInventory 0-8)
    // So PlayerInventory slot X maps to screen slot:
    //   hotbar 0-8 -> screen 36-44
    //   main 9-35 -> screen 9-35

    private static int playerToScreenSlot(int playerSlot) {
        if (playerSlot >= 0 && playerSlot <= 8) {
            return playerSlot + 36; // hotbar
        }
        return playerSlot; // main inventory 9-35 stays the same
    }

    // --- Fight Mode ---
    private static volatile boolean fightModeLimit     = false;
    private static volatile int     fightModeSwapsDone = 0;

    /** Fight mode: runs sortInventory but stops after the very first item swap. */
    public static void sortInventoryFightMode() {
        fightModeLimit     = true;
        fightModeSwapsDone = 0;
        try {
            sortInventory();
        } finally {
            fightModeLimit     = false;
            fightModeSwapsDone = 0;
        }
    }

    public static void sortInventory() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.interactionManager == null) return;
        if (client.player.playerScreenHandler == null) return;

        PlayerInventory inv = client.player.getInventory();
        int syncId = client.player.playerScreenHandler.syncId;

        // Step 1: Create EXACT snapshot of current inventory (with enchantments, durability, etc.)
        // This is the SOURCE OF TRUTH for finding items during swaps
        ItemStack[] inventorySnapshot = new ItemStack[36];
        for (int i = 0; i < 36; i++) {
            inventorySnapshot[i] = inv.getStack(i).copy(); // DEEP COPY with all components
        }

        // Step 2: Build desired layout as an array: desiredSlots[playerSlot] = desired ItemStack
        ItemStack[] desired = new ItemStack[36];
        for (int i = 0; i < 36; i++) {
            desired[i] = ItemStack.EMPTY;
        }
        // desiredEquip[0-4] = head, chest, legs, feet, offhand (assigned during pool distribution)
        ItemStack[] desiredEquip = new ItemStack[5];
        for (int i = 0; i < 5; i++) desiredEquip[i] = ItemStack.EMPTY;

        // Collect all items from inventory AND equipment slots
        // Equipment items must be included so the tier system can assign them correctly
        List<ItemStack> allItems = new ArrayList<>();
        for (int i = 0; i < 36; i++) {
            ItemStack stack = inv.getStack(i);
            if (!stack.isEmpty()) {
                allItems.add(stack.copy());
            }
        }
        // Also collect from equipment slots (armor + offhand)
        // PlayerInventory: armor slots 36-39 (feet=36, legs=37, chest=38, head=39), offhand=40
        // But getArmorStack/screen slots: head=39, chest=38, legs=37, feet=36
        int[] equipInvSlots = {39, 38, 37, 36, 40}; // head, chest, legs, feet, offhand
        ItemStack[] equipOriginal = new ItemStack[5];
        for (int e = 0; e < 5; e++) {
            ItemStack eqStack = inv.getStack(equipInvSlots[e]);
            equipOriginal[e] = eqStack.copy();
            if (!eqStack.isEmpty()) {
                allItems.add(eqStack.copy());
                LOGGER.debug("[InvOrganizer] Equipment item collected: " + getItemId(eqStack) + " from inv slot " + equipInvSlots[e]);
            }
        }

        // Sort items into categories
        List<ItemStack> weapons = new ArrayList<>();
        List<ItemStack> tools = new ArrayList<>();
        List<ItemStack> armor = new ArrayList<>();
        List<ItemStack> valuables = new ArrayList<>();
        List<ItemStack> blocks = new ArrayList<>();
        List<ItemStack> food = new ArrayList<>();
        List<ItemStack> utility = new ArrayList<>();
        List<ItemStack> potions = new ArrayList<>();
        List<ItemStack> misc = new ArrayList<>();
        List<ItemStack> arrows = new ArrayList<>();

        for (ItemStack stack : allItems) {
            int cat = getCategory(stack);
            int poolCat = poolCategoryOf(cat);
            String catName;
            if (poolCat == CAT_WEAPON) { weapons.add(stack); catName = "weapon"; }
            else if (poolCat == CAT_TOOL) { tools.add(stack); catName = "tool"; }
            else if (poolCat == CAT_ARMOR) { armor.add(stack); catName = "armor"; }
            else if (poolCat == CAT_VALUABLE) { valuables.add(stack); catName = "valuable"; }
            else if (poolCat == CAT_BLOCK) { blocks.add(stack); catName = "block"; }
            else if (poolCat == CAT_FOOD) { food.add(stack); catName = "food"; }
            else if (poolCat == CAT_UTILITY) { utility.add(stack); catName = "utility"; }
            else if (poolCat == CAT_POTION) { potions.add(stack); catName = "potion"; }
            else if (poolCat == CAT_ARROW) { arrows.add(stack); catName = "arrow"; }
            else { misc.add(stack); catName = "misc"; }
            LOGGER.debug("[InvOrganizer] Item: " + getItemId(stack) + " x" + stack.getCount() + " -> " + catName);
        }

        // Sort each category using user-configured criteria from sort preferences
        OrganizerConfig config = OrganizerConfig.get();

        // Read sort criteria from config
        String[] criteriaArr = config.getPreference("sort_criteria_order");
        if (criteriaArr == null || criteriaArr.length == 0) {
            criteriaArr = new String[]{"material", "enchant", "durability"};
        }
        String[] enchantDescArr = config.getPreference("sort_enchant_desc");
        boolean enchDesc = enchantDescArr == null || enchantDescArr.length == 0 || !enchantDescArr[0].equals("false");
        String[] durDescArr = config.getPreference("sort_durability_desc");
        boolean durDesc = durDescArr == null || durDescArr.length == 0 || !durDescArr[0].equals("false");

        // Read material order from config
        String[] matOrderArr = config.getPreference("sort_material_order");
        if (matOrderArr == null || matOrderArr.length == 0) {
            matOrderArr = new String[]{"netherite", "diamond", "iron", "gold", "stone", "wood", "leather", "chain"};
        }
        // Read enchant order from config
        String[] enchOrderArr = config.getPreference("sort_enchant_order");
        LOGGER.debug("[InvOrganizer] DEBUG: enchOrderArr from config = " + (enchOrderArr != null ? enchOrderArr.length + " items" : "NULL"));
        // Replace old curse IDs with new ones (Minecraft 1.21 format)
        if (enchOrderArr != null && enchOrderArr.length > 0) {
            for (int i = 0; i < enchOrderArr.length; i++) {
                if (enchOrderArr[i].equals("curse_of_vanishing")) {
                    enchOrderArr[i] = "vanishing_curse";
                } else if (enchOrderArr[i].equals("curse_of_binding")) {
                    enchOrderArr[i] = "binding_curse";
                }
            }
            LOGGER.debug("[InvOrganizer] DEBUG: First 3 enchants: " + enchOrderArr[0] + ", " + (enchOrderArr.length > 1 ? enchOrderArr[1] : "N/A") + ", " + (enchOrderArr.length > 2 ? enchOrderArr[2] : "N/A"));
        }
        final String[] finalMatOrder = matOrderArr;
        final String[] finalEnchOrder = enchOrderArr;
        final String[] finalCriteria = criteriaArr;
        final boolean finalEnchDesc = enchDesc;
        final boolean finalDurDesc = durDesc;

        LOGGER.debug("[InvOrganizer] Sort config: criteria=" + java.util.Arrays.toString(finalCriteria)
                + " materials=" + java.util.Arrays.toString(finalMatOrder)
                + " enchants=" + (finalEnchOrder != null ? java.util.Arrays.toString(finalEnchOrder) : "null")
                + " enchDesc=" + finalEnchDesc + " durDesc=" + finalDurDesc);

        // Build configurable comparator for gear (weapons/tools/armor)
        Comparator<ItemStack> configSort = (a, b) -> {
            for (String criterion : finalCriteria) {
                int cmp = 0;
                switch (criterion) {
                    case "material":
                        cmp = Integer.compare(
                                materialTierFromOrder(getItemId(a), finalMatOrder),
                                materialTierFromOrder(getItemId(b), finalMatOrder));
                        break;
                    case "enchant":
                        if (finalEnchOrder != null && finalEnchOrder.length > 0) {
                            // Use enchant order: compare by best enchant tier
                            int enchA = getBestEnchantTier(a, finalEnchOrder);
                            int enchB = getBestEnchantTier(b, finalEnchOrder);
                            LOGGER.debug("[InvOrganizer] ENCHANT COMPARE: " + getItemId(a) + " tier=" + enchA + " vs " + getItemId(b) + " tier=" + enchB + " => " + (enchA < enchB ? "A wins" : enchB < enchA ? "B wins" : "tie"));
                            cmp = Integer.compare(enchA, enchB);
                        } else {
                            // Fallback: count enchantments
                            LOGGER.debug("[InvOrganizer] ENCHANT FALLBACK: using count");
                            int enchA = getEnchantmentScore(a);
                            int enchB = getEnchantmentScore(b);
                            cmp = finalEnchDesc ? Integer.compare(enchB, enchA) : Integer.compare(enchA, enchB);
                        }
                        break;
                    case "durability":
                        int durA = getRemainingDurability(a);
                        int durB = getRemainingDurability(b);
                        cmp = finalDurDesc ? Integer.compare(durB, durA) : Integer.compare(durA, durB);
                        break;
                }
                if (cmp != 0) return cmp;
            }
            return getItemId(a).compareTo(getItemId(b));
        };

        weapons.sort(configSort);
        tools.sort(configSort);
        armor.sort(configSort);
        // Sort food by user-configured food order, then by count
        String[] foodOrderArr = config.getPreference("sort_food_order");
        if (foodOrderArr != null && foodOrderArr.length > 0) {
            final String[] finalFoodOrder = foodOrderArr;
            food.sort(Comparator.comparingInt((ItemStack s) -> foodTierFromOrder(getItemId(s), finalFoodOrder))
                    .thenComparingInt((ItemStack s) -> -s.getCount()));
        } else {
            food.sort(Comparator.comparingInt((ItemStack s) -> -getFoodValue(s))
                    .thenComparingInt((ItemStack s) -> -s.getCount()));
        }
        // Sort blocks by user-configured block order, then by count descending
        String[] blockOrderArr = config.getPreference("sort_block_order");
        if (blockOrderArr != null && blockOrderArr.length > 0) {
            final String[] finalBlockOrder = blockOrderArr;
            blocks.sort(Comparator.comparingInt((ItemStack s) -> blockTierFromOrder(getItemId(s), finalBlockOrder))
                    .thenComparingInt((ItemStack s) -> -s.getCount())
                    .thenComparing(s -> getItemId(s)));
        } else {
            blocks.sort(Comparator.comparingInt((ItemStack s) -> -s.getCount()).thenComparing(s -> getItemId(s)));
        }
        // Sort potions: effect/name order is PRIMARY (strength vs night_vision wins
        // regardless of whether it's drinkable or splash). Type order is only a
        // tie-breaker between two potions of the same effect.
        String[] potionTypeOrderArr = config.getPreference("sort_potion_type_order");
        String[] potionOrderArr = config.getPreference("sort_potion_order");
        final String[] finalTypeOrder = (potionTypeOrderArr != null && potionTypeOrderArr.length > 0) ? potionTypeOrderArr : new String[0];
        final String[] finalPotionOrder = (potionOrderArr != null && potionOrderArr.length > 0) ? potionOrderArr : new String[0];
        potions.sort(Comparator.comparingInt((ItemStack s) -> potionTierFromOrder(s, finalPotionOrder))
                .thenComparingInt((ItemStack s) -> potionTypeFromOrder(s, finalTypeOrder))
                .thenComparing(s -> getItemId(s)));
        // Sort arrows alphabetically by item ID
        arrows.sort(Comparator.comparing(s -> getItemId(s)));
        // Sort valuables (ores, ingots, gems) alphabetically by item ID
        valuables.sort(Comparator.comparing(s -> getItemId(s)));

        // All sorted items in one pool
        List<ItemStack> pool = new ArrayList<>();
        pool.addAll(weapons);
        pool.addAll(tools);
        pool.addAll(armor);
        pool.addAll(valuables);
        pool.addAll(blocks);
        pool.addAll(food);
        pool.addAll(utility);
        pool.addAll(potions);
        pool.addAll(arrows);
        pool.addAll(misc);

        // Check for custom slot rules
        boolean hasCustomRules = false;
        StringBuilder debugRules = new StringBuilder("Slot rules: ");
        for (int i = 0; i < 36; i++) {
            SlotRule rule = config.getSlotRule(i);
            if (rule.getType() != SlotRule.Type.ANY) {
                hasCustomRules = true;
                debugRules.append(i).append("=").append(rule.getType()).append(":").append(rule.getValue()).append(" ");
            }
        }
        LOGGER.debug("[InvOrganizer] hasCustomRules=" + hasCustomRules + " " + debugRules);
        LOGGER.debug("[InvOrganizer] Pool size: " + pool.size() + " items");

        // Load tier_order preferences: slot -> tier number (lower = gets better item first)
        java.util.Map<Integer, Integer> tierOrder = new java.util.HashMap<>();

        if (hasCustomRules) {
            String[] tierSaved = config.getPreference("tier_order");
            if (tierSaved != null) {
                for (String te : tierSaved) {
                    if (te.startsWith("slot_") && te.contains("_tier_")) {
                        try {
                            String[] tp = te.split("_");
                            int tSlot = Integer.parseInt(tp[1]);
                            int tTier = Integer.parseInt(tp[3]);
                            tierOrder.put(tSlot, tTier);
                        } catch (Exception ignored) {}
                    }
                }
            }
            LOGGER.debug("[InvOrganizer] Tier order loaded: " + tierOrder);

            // Build unified ruleOrder that includes BOTH inventory slots (0-35) AND equipment slots (100-104)
            // Equipment slots use virtual indices 100-104: 100=head, 101=chest, 102=legs, 103=feet, 104=offhand
            // All slots sorted by tier (lower tier = first = gets best item)
            List<Integer> tieredSlots = new ArrayList<>();
            List<Integer> untieredHotbar = new ArrayList<>();
            List<Integer> untieredMain = new ArrayList<>();
            for (int i = 0; i <= 8; i++) {
                if (tierOrder.containsKey(i)) tieredSlots.add(i);
                else untieredHotbar.add(i);
            }
            for (int i = 9; i <= 35; i++) {
                if (tierOrder.containsKey(i)) tieredSlots.add(i);
                else untieredMain.add(i);
            }
            // Add equipment slots (100-104) to tiered list
            for (int i = 100; i <= 104; i++) {
                if (tierOrder.containsKey(i)) tieredSlots.add(i);
            }
            // Sort tiered slots by tier number (ascending: tier 1 first = gets best item)
            // Pool is already sorted by enchant/material/durability, so tier 1 slot gets first item from pool
            tieredSlots.sort(Comparator.comparingInt(s -> tierOrder.getOrDefault(s, 999)));
            LOGGER.debug("[InvOrganizer] Tiered slots after sort: " + tieredSlots + " (order: tier 1 first)");

            // Unified ruleOrder: all tiered slots first, then untiered
            List<Integer> ruleOrderList = new ArrayList<>();
            ruleOrderList.addAll(tieredSlots);
            ruleOrderList.addAll(untieredHotbar);
            ruleOrderList.addAll(untieredMain);

            // Pass -1: Assign bundles to BUNDLE_CONTENT slots (before all other passes)
            for (int s : ruleOrderList) {
                if (s >= 100) continue;
                SlotRule rule = config.getSlotRule(s);
                if (rule.getType() != SlotRule.Type.BUNDLE_CONTENT) continue;
                if (!desired[s].isEmpty()) continue;
                for (int j = 0; j < pool.size(); j++) {
                    if (!pool.get(j).isEmpty() && isBundleItem(getItemId(pool.get(j)))) {
                        desired[s] = pool.remove(j);
                        break;
                    }
                }
            }

            // For ANY/overflow passes: respect tier order first, then untiered main inv, then untiered hotbar.
            // This ensures tier-1 slots (e.g. hotbar) get items before tier-2 slots (e.g. main inv).
            // When no tiers exist: untieredMain (9-35) first, then untieredHotbar (0-8) = original behavior.
            List<Integer> anyOrderList = new ArrayList<>();
            for (int s : tieredSlots) {
                if (s < 36) anyOrderList.add(s);  // only inventory slots (not equipment)
            }
            anyOrderList.addAll(untieredMain);
            anyOrderList.addAll(untieredHotbar);
            int[] anyOrder = anyOrderList.stream().mapToInt(Integer::intValue).toArray();

            // Helper: get rule for any slot (inventory or equipment)
            // Equipment keys: armor_head=100, armor_chest=101, armor_legs=102, armor_feet=103, offhand=104
            String[] equipKeys = OrganizerConfig.EQUIPMENT_SLOTS;

            // Pass 0: Fill SPECIFIC_ITEM slots (exact item match, highest priority)
            for (int s : ruleOrderList) {
                SlotRule rule;
                if (s >= 100) rule = config.getSlotRuleByKey(equipKeys[s - 100]);
                else rule = config.getSlotRule(s);
                if (rule.getType() == SlotRule.Type.SPECIFIC_ITEM && !rule.getValue().isEmpty()) {
                    String ruleVal = rule.getValue();
                    for (int j = 0; j < pool.size(); j++) {
                        if (matchesSpecificItem(getItemId(pool.get(j)), ruleVal)) {
                            if (s >= 100) desiredEquip[s - 100] = pool.remove(j);
                            else desired[s] = pool.remove(j);
                            break;
                        }
                    }
                }
            }
            // Pass 1: Fill SPECIFIC type slots
            for (int s : ruleOrderList) {
                if (s >= 100) { if (!desiredEquip[s - 100].isEmpty()) continue; }
                else { if (!desired[s].isEmpty()) continue; }
                SlotRule rule;
                if (s >= 100) rule = config.getSlotRuleByKey(equipKeys[s - 100]);
                else rule = config.getSlotRule(s);
                if (rule.getType() == SlotRule.Type.SPECIFIC && !rule.getValue().isEmpty()) {
                    for (int j = 0; j < pool.size(); j++) {
                        if (matchesItemType(getItemId(pool.get(j)), rule.getValue())) {
                            if (s >= 100) desiredEquip[s - 100] = pool.remove(j);
                            else desired[s] = pool.remove(j);
                            break;
                        }
                    }
                }
            }
            // Pass 2: Fill GROUP slots
            for (int s : ruleOrderList) {
                if (s >= 100) { if (!desiredEquip[s - 100].isEmpty()) continue; }
                else { if (!desired[s].isEmpty()) continue; }
                SlotRule rule;
                if (s >= 100) rule = config.getSlotRuleByKey(equipKeys[s - 100]);
                else rule = config.getSlotRule(s);
                if (rule.getType() == SlotRule.Type.GROUP && !rule.getValue().isEmpty()) {
                    int targetCat = groupNameToCategory(rule.getValue());
                    for (int j = 0; j < pool.size(); j++) {
                        if (categoryMatches(pool.get(j), targetCat)) {
                            if (s >= 100) desiredEquip[s - 100] = pool.remove(j);
                            else desired[s] = pool.remove(j);
                            break;
                        }
                    }
                }
            }
            // Pass 2.5: Fill CUSTOM_GROUP slots (user-defined item groups)
            for (int s : ruleOrderList) {
                if (s >= 100) { if (!desiredEquip[s - 100].isEmpty()) continue; }
                else { if (!desired[s].isEmpty()) continue; }
                SlotRule rule;
                if (s >= 100) rule = config.getSlotRuleByKey(equipKeys[s - 100]);
                else rule = config.getSlotRule(s);
                if (rule.getType() == SlotRule.Type.CUSTOM_GROUP && !rule.getValue().isEmpty()) {
                    String groupName = rule.getValue();
                    // Get ordered list of item IDs for this group
                    String[] cgOrder = config.getPreference("cg_order_" + groupName);
                    java.util.List<String> groupItems;
                    if (cgOrder != null && cgOrder.length > 0) {
                        groupItems = java.util.Arrays.asList(cgOrder);
                    } else {
                        groupItems = config.getCustomGroup(groupName);
                    }
                    // Try to find an item from the pool that belongs to this group, preferring earlier in order
                    outer:
                    for (String preferredId : groupItems) {
                        for (int j = 0; j < pool.size(); j++) {
                            String poolId = getItemId(pool.get(j));
                            if (poolId.equals(preferredId) || poolId.equals("minecraft:" + preferredId)) {
                                if (s >= 100) desiredEquip[s - 100] = pool.remove(j);
                                else desired[s] = pool.remove(j);
                                break outer;
                            }
                        }
                    }
                }
            }
            // Pass 3: Fill ANY inventory slots with remaining items (main inv first, skip EMPTY_LOCKED)
            for (int s : anyOrder) {
                if (!desired[s].isEmpty()) continue;
                SlotRule rule = config.getSlotRule(s);
                if (rule.getType() == SlotRule.Type.EMPTY_LOCKED) continue;
                if (rule.getType() == SlotRule.Type.BUNDLE_CONTENT) continue;
                if (rule.getType() == SlotRule.Type.ANY) {
                    if (!pool.isEmpty()) {
                        desired[s] = pool.remove(0);
                    }
                }
            }
            // Pass 4: Overflow into ANY slots
            for (int s : anyOrder) {
                if (pool.isEmpty()) break;
                if (!desired[s].isEmpty()) continue;
                SlotRule rule = config.getSlotRule(s);
                if (rule.getType() == SlotRule.Type.BUNDLE_CONTENT) continue;
                if (rule.getType() != SlotRule.Type.ANY) continue;
                desired[s] = pool.remove(0);
            }

            // Debug desired equipment
            for (int i = 0; i < 5; i++) {
                if (!desiredEquip[i].isEmpty()) {
                    LOGGER.debug("[InvOrganizer] DesiredEquip[" + i + "]=" + getItemId(desiredEquip[i]));
                }
            }
        } else {
            // No custom rules - do nothing, keep items where they are
            LOGGER.debug("[InvOrganizer] No custom rules set, skipping sort");
            return;
        }

        // Debug: print desired layout
        StringBuilder desiredDebug = new StringBuilder("[InvOrganizer] Desired layout: ");
        for (int i = 0; i < 36; i++) {
            if (!desired[i].isEmpty()) {
                desiredDebug.append(i).append("=").append(getItemId(desired[i])).append("x").append(desired[i].getCount()).append(" ");
            }
        }
        LOGGER.debug(desiredDebug.toString());

        // Step 2: Execute swaps using clickSlot to sync with server
        // We keep a parallel tracking array of what's currently where
        ItemStack[] current = new ItemStack[36];
        for (int i = 0; i < 36; i++) {
            current[i] = inv.getStack(i).copy();
        }

        // Abort if player is holding an item on cursor: first clickSlot would place it
        // instead of picking up, corrupting all subsequent swap operations.
        if (!client.player.playerScreenHandler.getCursorStack().isEmpty()) {
            LOGGER.debug("[InvOrganizer] Aborting sort: cursor is not empty");
            return;
        }

        // First pass: place normal items first, then color-family items (bundles, shulker boxes, etc.) last
        // This ensures special items go into empty slots rather than causing swap conflicts
        List<Integer> normalSlots = new ArrayList<>();
        List<Integer> specialSlots = new ArrayList<>();
        for (int targetSlot = 0; targetSlot < 36; targetSlot++) {
            if (desired[targetSlot].isEmpty()) continue;
            if (isColorFamilyItem(getItemId(desired[targetSlot]))) {
                specialSlots.add(targetSlot);
            } else {
                normalSlots.add(targetSlot);
            }
        }
        List<Integer> orderedSlots = new ArrayList<>();
        orderedSlots.addAll(normalSlots);
        orderedSlots.addAll(specialSlots);

        boolean abortSwaps = false;
        for (int targetSlot : orderedSlots) {
            if (abortSwaps) break;

            // Always re-read actual inventory state — never trust stale tracking
            for (int i = 0; i < 36; i++) current[i] = inv.getStack(i).copy();

            ItemStack want = desired[targetSlot];
            String wantId = getItemId(want);
            boolean isSpecial = isColorFamilyItem(wantId);

            ItemStack have = current[targetSlot];

            // Already correct
            if (!have.isEmpty()) {
                if (isSpecial) {
                    if (getItemId(have).equals(wantId)) continue;
                } else {
                    if (ItemStack.areItemsAndComponentsEqual(have, want)
                            && have.getCount() == want.getCount()) continue;
                }
            }

            // If target slot is occupied, we must pre-clear it first.
            // needsClear = true whenever target doesn't have EXACTLY the right item+count.
            // (Same-type but different count must also pre-clear to avoid stacking instead of swapping.)
            if (!have.isEmpty()) {
                boolean needsClear = isSpecial
                    ? !getItemId(have).equals(wantId)
                    : !(ItemStack.areItemsAndComponentsEqual(have, want) && have.getCount() == want.getCount());
                if (needsClear) {
                    int emptyDump = -1;
                    for (int dist = 1; dist < 36 && emptyDump == -1; dist++) {
                        int above = targetSlot - dist;
                        int below = targetSlot + dist;
                        if (above >= 0 && current[above].isEmpty() && desired[above].isEmpty()) emptyDump = above;
                        else if (below < 36 && current[below].isEmpty() && desired[below].isEmpty()) emptyDump = below;
                    }
                    if (emptyDump == -1) {
                        LOGGER.debug("[InvOrganizer] No emptyDump for slot " + targetSlot + ", skipping");
                        continue;
                    }
                    LOGGER.debug("[InvOrganizer] Pre-clearing slot " + targetSlot + " -> " + emptyDump);
                    doSwap(client, syncId, targetSlot, emptyDump);
                    // Re-read after pre-clear swap
                    for (int i = 0; i < 36; i++) current[i] = inv.getStack(i).copy();
                    have = current[targetSlot]; // should be empty now
                }
            }

            // Find where the wanted item currently is.
            // Skip slots where the desired item is already correctly placed.
            int sourceSlot = -1;

            if (isSpecial) {
                for (int i = 0; i < 36; i++) {
                    if (i == targetSlot) continue;
                    if (!desired[i].isEmpty() && !current[i].isEmpty()
                            && getItemId(current[i]).equals(getItemId(desired[i]))
                            && current[i].getCount() == desired[i].getCount()) continue;
                    if (!current[i].isEmpty() && getItemId(current[i]).equals(wantId)) {
                        sourceSlot = i; break;
                    }
                }
            }
            if (sourceSlot == -1 && !isSpecial) {
                for (int i = 0; i < 36; i++) {
                    if (i == targetSlot) continue;
                    if (!desired[i].isEmpty() && !current[i].isEmpty()
                            && ItemStack.areItemsAndComponentsEqual(current[i], desired[i])) continue;
                    if (!current[i].isEmpty() && ItemStack.areItemsAndComponentsEqual(current[i], want)) {
                        sourceSlot = i; break;
                    }
                }
                // Fallback: ID + count match
                if (sourceSlot == -1) {
                    for (int i = 0; i < 36; i++) {
                        if (i == targetSlot) continue;
                        if (!desired[i].isEmpty() && !current[i].isEmpty()
                                && ItemStack.areItemsAndComponentsEqual(current[i], desired[i])) continue;
                        if (!current[i].isEmpty() && getItemId(current[i]).equals(wantId)
                                && current[i].getCount() == want.getCount()) {
                            sourceSlot = i; break;
                        }
                    }
                }
            }

            LOGGER.debug("[InvOrganizer] Swap: want=" + wantId + " target=" + targetSlot + " source=" + sourceSlot);

            if (sourceSlot == -1) continue;

            doSwap(client, syncId, sourceSlot, targetSlot);

            // Re-read actual inventory after swap — this is the key fix for duplication:
            // instead of manually tracking changes (which diverges on stacking/server corrections),
            // we always work with ground truth.
            for (int i = 0; i < 36; i++) current[i] = inv.getStack(i).copy();

            // If cursor is non-empty after doSwap, something went wrong — emergency place
            if (!client.player.playerScreenHandler.getCursorStack().isEmpty()) {
                LOGGER.debug("[InvOrganizer] WARNING: cursor not empty after swap, emergency drop");
                boolean cleared = false;
                for (int ec = 9; ec < 36 && !cleared; ec++) {
                    if (current[ec].isEmpty()) {
                        client.interactionManager.clickSlot(syncId, playerToScreenSlot(ec), 0, SlotActionType.PICKUP, client.player);
                        if (client.player.playerScreenHandler.getCursorStack().isEmpty()) {
                            for (int i = 0; i < 36; i++) current[i] = inv.getStack(i).copy();
                            cleared = true;
                        }
                    }
                }
                if (!cleared) {
                    LOGGER.debug("[InvOrganizer] ABORT: cursor still not empty, stopping sort");
                    abortSwaps = true;
                }
            }
        }

        // Equipment pass: use desiredEquip[] from unified pool assignment
        // Screen slot mapping: 5=helmet, 6=chestplate, 7=leggings, 8=boots, 45=offhand
        int[] equipScreenSlots = { 5, 6, 7, 8, 45 };

        // First: unequip all current equipment into inventory (to free up equip slots)
        // Only unequip if the desired item is different from what's currently equipped
        for (int e = 0; e < 5; e++) {
            if (fightModeLimit) break; // fight mode: never touch equipment
            if (equipOriginal[e].isEmpty()) continue;
            // Check if this equip slot already has the desired item
            if (!desiredEquip[e].isEmpty() && ItemStack.areItemsAndComponentsEqual(equipOriginal[e], desiredEquip[e])) {
                LOGGER.debug("[InvOrganizer] EquipKeep[" + e + "]=" + getItemId(equipOriginal[e]) + " (already correct)");
                continue;
            }
            // Find empty inventory slot to unequip into
            int emptySlot = -1;
            for (int i = 9; i <= 35; i++) {
                if (current[i].isEmpty()) { emptySlot = i; break; }
            }
            if (emptySlot == -1) {
                for (int i = 0; i <= 8; i++) {
                    if (current[i].isEmpty()) { emptySlot = i; break; }
                }
            }
            if (emptySlot != -1) {
                LOGGER.debug("[InvOrganizer] Unequip[" + e + "]=" + getItemId(equipOriginal[e]) + " to slot " + emptySlot);
                int equipScreen = equipScreenSlots[e];
                int destScreen = playerToScreenSlot(emptySlot);
                client.interactionManager.clickSlot(syncId, equipScreen, 0, SlotActionType.PICKUP, client.player);
                client.interactionManager.clickSlot(syncId, destScreen, 0, SlotActionType.PICKUP, client.player);
                if (!client.player.playerScreenHandler.getCursorStack().isEmpty()) {
                    client.interactionManager.clickSlot(syncId, equipScreen, 0, SlotActionType.PICKUP, client.player);
                }
                current[emptySlot] = equipOriginal[e].copy();
            }
        }

        // Now equip desired items from inventory
        for (int e = 0; e < 5; e++) {
            if (fightModeLimit) break; // fight mode: never touch equipment
            if (desiredEquip[e].isEmpty()) continue;
            // Check if already equipped correctly (wasn't unequipped above)
            if (!equipOriginal[e].isEmpty() && ItemStack.areItemsAndComponentsEqual(equipOriginal[e], desiredEquip[e])) {
                continue; // already correct
            }
            // Find the desired item in current inventory
            int sourcePlayerSlot = -1;
            for (int i = 0; i < 36; i++) {
                if (current[i].isEmpty()) continue;
                if (ItemStack.areItemsAndComponentsEqual(current[i], desiredEquip[e])) {
                    sourcePlayerSlot = i;
                    break;
                }
            }
            // Fallback: match by item ID
            if (sourcePlayerSlot == -1) {
                String wantId = getItemId(desiredEquip[e]);
                for (int i = 0; i < 36; i++) {
                    if (current[i].isEmpty()) continue;
                    if (getItemId(current[i]).equals(wantId)) {
                        sourcePlayerSlot = i;
                        break;
                    }
                }
            }
            if (sourcePlayerSlot == -1) continue;

            LOGGER.debug("[InvOrganizer] Equip[" + e + "]=" + getItemId(desiredEquip[e])
                    + " from slot " + sourcePlayerSlot + " to screen " + equipScreenSlots[e]);

            int sourceScreen = playerToScreenSlot(sourcePlayerSlot);
            client.interactionManager.clickSlot(syncId, sourceScreen, 0, SlotActionType.PICKUP, client.player);
            client.interactionManager.clickSlot(syncId, equipScreenSlots[e], 0, SlotActionType.PICKUP, client.player);
            if (!client.player.playerScreenHandler.getCursorStack().isEmpty()) {
                client.interactionManager.clickSlot(syncId, sourceScreen, 0, SlotActionType.PICKUP, client.player);
            }
            current[sourcePlayerSlot] = ItemStack.EMPTY;
        }

        // Phase: Fill bundles with matching items (after main sort)
        if (!fightModeLimit) fillBundles(client, syncId, config, current);

        // Second pass: clear slots that should be empty but still have items
        // Only move items OUT of EMPTY_LOCKED slots or slots where the wrong item ended up
        // Never dump items INTO EMPTY_LOCKED or rule-bound slots
        for (int targetSlot = 0; targetSlot < 36; targetSlot++) {
            if (!desired[targetSlot].isEmpty()) continue; // only handle empty-desired slots
            if (current[targetSlot].isEmpty()) continue; // already empty

            // Only clear this slot if it's EMPTY_LOCKED (must be empty) 
            // or if it has a specific rule that wasn't matched
            SlotRule targetRule = config.getSlotRule(targetSlot);
            if (targetRule.getType() == SlotRule.Type.ANY) continue; // ANY slots can keep leftover items

            // Find a safe empty slot to dump to: must be ANY type, currently empty, desired empty
            int emptySlot = -1;
            for (int i = 9; i <= 35; i++) { // prefer main inv
                if (i == targetSlot) continue;
                if (current[i].isEmpty() && desired[i].isEmpty()) {
                    SlotRule dumpRule = config.getSlotRule(i);
                    if (dumpRule.getType() == SlotRule.Type.ANY) {
                        emptySlot = i;
                        break;
                    }
                }
            }
            // Fallback: try hotbar ANY slots
            if (emptySlot == -1) {
                for (int i = 0; i <= 8; i++) {
                    if (i == targetSlot) continue;
                    if (current[i].isEmpty() && desired[i].isEmpty()) {
                        SlotRule dumpRule = config.getSlotRule(i);
                        if (dumpRule.getType() == SlotRule.Type.ANY) {
                            emptySlot = i;
                            break;
                        }
                    }
                }
            }
            if (emptySlot != -1) {
                LOGGER.debug("[InvOrganizer] Moving item from slot " + targetSlot + " to " + emptySlot + " (" + getItemId(current[targetSlot]) + ")");
                doSwap(client, syncId, targetSlot, emptySlot);
                current[emptySlot] = current[targetSlot];
                current[targetSlot] = ItemStack.EMPTY;
            }
        }
    }

    static void doSwap(MinecraftClient client, int syncId, int fromPlayerSlot, int toPlayerSlot) {
        if (fightModeLimit) {
            if (fightModeSwapsDone >= 1) return; // one item per OI press in fight mode
            fightModeSwapsDone++;
        }
        int fromScreen = playerToScreenSlot(fromPlayerSlot);
        int toScreen = playerToScreenSlot(toPlayerSlot);

        // Pick up item from source (left click)
        client.interactionManager.clickSlot(syncId, fromScreen, 0, SlotActionType.PICKUP, client.player);
        // Place at destination (left click)
        client.interactionManager.clickSlot(syncId, toScreen, 0, SlotActionType.PICKUP, client.player);
        // If there was an item at destination, it's now on cursor - put it back at source
        if (!client.player.playerScreenHandler.getCursorStack().isEmpty()) {
            client.interactionManager.clickSlot(syncId, fromScreen, 0, SlotActionType.PICKUP, client.player);
        }
    }

    /**
     * After the main sort, fills bundles with items matching their designated content rule.
     * Sequence: pick up bundle → left-click each matching item slot → place bundle back.
     * The server handles actual insertion via BundleItem.onStackClicked().
     */
    private static void fillBundles(MinecraftClient client, int syncId, OrganizerConfig config, ItemStack[] current) {
        // Step 1: Build fill plan – ordered map of (bundleInventorySlot → contentRules)
        // Priority: designated slots first (BUNDLE_CONTENT / SPECIFIC_ITEM bundle rules),
        // then any remaining bundles in inventory (uses Bundle storage preset rules).
        java.util.LinkedHashMap<Integer, List<String>> fillPlan = new java.util.LinkedHashMap<>();

        for (int s = 0; s < 36; s++) {
            SlotRule rule = config.getSlotRule(s);
            List<String> contentRules = null;
            if (rule.getType() == SlotRule.Type.BUNDLE_CONTENT) {
                contentRules = java.util.Collections.singletonList(rule.getValue());
            } else if (rule.getType() == SlotRule.Type.SPECIFIC_ITEM && isBundleItem(rule.getValue())) {
                contentRules = getBundlePresetRules(config);
            }
            if (contentRules == null || contentRules.isEmpty()) continue;

            // Prefer the bundle physically at the designated slot; otherwise take the
            // first unassigned bundle found in the inventory.
            int bundleSlot = -1;
            if (!current[s].isEmpty() && isBundleItem(getItemId(current[s])) && !fillPlan.containsKey(s)) {
                bundleSlot = s;
            } else {
                for (int i = 0; i < 36; i++) {
                    if (fillPlan.containsKey(i)) continue;
                    if (!current[i].isEmpty() && isBundleItem(getItemId(current[i]))) {
                        bundleSlot = i;
                        break;
                    }
                }
            }
            if (bundleSlot == -1) continue;
            fillPlan.put(bundleSlot, contentRules);
        }

        // Step 2: Any remaining bundles in inventory not yet in the plan → add them
        // using the Bundle storage preset rules so overflow items can flow into them.
        List<String> presetRules = getBundlePresetRules(config);
        if (!presetRules.isEmpty()) {
            for (int i = 0; i < 36; i++) {
                if (fillPlan.containsKey(i)) continue;
                if (current[i].isEmpty() || !isBundleItem(getItemId(current[i]))) continue;
                fillPlan.put(i, presetRules);
                LOGGER.debug("[InvOrganizer] fillBundles: extra bundle found at slot " + i + ", adding to fill plan");
            }
        }

        if (fillPlan.isEmpty()) return;

        // Step 3: Fill each bundle in plan order.
        // Items consumed by an earlier bundle are marked EMPTY in current[] and
        // will not be seen by later bundles, enabling natural overflow chaining.
        for (java.util.Map.Entry<Integer, List<String>> entry : fillPlan.entrySet()) {
            int bundleSlot = entry.getKey();
            List<String> contentRules = entry.getValue();

            ItemStack bundleStack = current[bundleSlot];
            BundleContentsComponent contents = bundleStack.get(DataComponentTypes.BUNDLE_CONTENTS);
            if (contents == null) contents = BundleContentsComponent.DEFAULT;
            Fraction remaining = Fraction.ONE.subtract(contents.getOccupancy());

            List<Integer> toBundle = new ArrayList<>();
            for (int i = 0; i < 36; i++) {
                if (i == bundleSlot) continue;
                if (current[i].isEmpty()) continue;
                if (isBundleItem(getItemId(current[i]))) continue;
                boolean matches = false;
                for (String cr : contentRules) {
                    if (matchesContentRule(current[i], cr)) { matches = true; break; }
                }
                if (!matches) continue;
                Fraction itemWeight = Fraction.getFraction(current[i].getCount(), current[i].getMaxCount());
                if (remaining.compareTo(itemWeight) < 0) continue;
                toBundle.add(i);
                remaining = remaining.subtract(itemWeight);
            }

            if (toBundle.isEmpty()) continue;
            LOGGER.debug("[InvOrganizer] fillBundles: bundle@" + bundleSlot + " receives " + toBundle.size() + " stacks");

            // pick up item → left-click bundle slot → item goes into bundle
            for (int itemSlot : toBundle) {
                client.interactionManager.clickSlot(syncId, playerToScreenSlot(itemSlot), 0, SlotActionType.PICKUP, client.player);
                client.interactionManager.clickSlot(syncId, playerToScreenSlot(bundleSlot), 0, SlotActionType.PICKUP, client.player);
                current[itemSlot] = ItemStack.EMPTY;
            }
        }
    }

    /** Collect all non-"any" content rules from the "Bundle" storage preset. */
    private static List<String> getBundlePresetRules(OrganizerConfig config) {
        List<String> result = new ArrayList<>();
        for (StoragePreset p : config.getStoragePresets()) {
            if ("Bundle".equalsIgnoreCase(p.getName())) {
                for (int i = 0; i < p.getSize(); i++) {
                    String r = p.getSlotRule(i);
                    if (!r.equals("any") && !r.isEmpty()) result.add(r);
                }
                break;
            }
        }
        return result;
    }

    static boolean matchesContentRule(ItemStack stack, String contentRule) {
        if (contentRule.startsWith("g:")) {
            int cat = groupNameToCategory(contentRule.substring(2));
            return categoryMatches(stack, cat);
        }
        if (contentRule.startsWith("t:")) {
            return matchesItemType(getItemId(stack), contentRule.substring(2));
        }
        return matchesSpecificItem(getItemId(stack), contentRule);
    }

    // Match item ID against a type name, handling ambiguous cases like axe/pickaxe
    static boolean matchesItemType(String itemId, String type) {
        if (type.equals("axe")) {
            // "axe" must match "_axe" to exclude "pickaxe"
            return itemId.contains("_axe") && !itemId.contains("pickaxe");
        }
        if (type.equals("arrow")) {
            // plain "arrow" must not match tipped_arrow or spectral_arrow
            return itemId.equals("minecraft:arrow");
        }
        if (type.equals("potion")) {
            // "potion" matches only drinkable potions, not splash_potion or lingering_potion
            return itemId.equals("minecraft:potion");
        }
        return itemId.contains(type);
    }

    // All Minecraft color prefixes
    private static final String[] COLOR_PREFIXES = {
        "white_", "orange_", "magenta_", "light_blue_", "yellow_", "lime_",
        "pink_", "gray_", "light_gray_", "cyan_", "purple_", "blue_",
        "brown_", "green_", "red_", "black_"
    };

    // Item families that have color variants
    private static final String[] COLOR_FAMILIES = {
        "bundle", "shulker_box", "bed", "banner", "candle", "carpet",
        "concrete", "concrete_powder", "wool", "terracotta",
        "glazed_terracotta", "stained_glass", "stained_glass_pane",
        "dye"
    };

    /**
     * Strip color prefix from an item ID's path to get the base family name.
     * E.g. "minecraft:white_shulker_box" -> "shulker_box"
     *      "minecraft:red_bundle" -> "bundle"
     *      "minecraft:shulker_box" -> "shulker_box" (no color, already base)
     * Returns null if the item is not a color-variant family.
     */
    private static String getColorFamily(String itemId) {
        // Remove namespace
        String path = itemId.contains(":") ? itemId.substring(itemId.indexOf(':') + 1) : itemId;

        // Check if the path itself IS a base family name (no color prefix)
        for (String family : COLOR_FAMILIES) {
            if (path.equals(family)) return family;
        }

        // Check if the path starts with a color prefix and ends with a family name
        for (String color : COLOR_PREFIXES) {
            if (path.startsWith(color)) {
                String remainder = path.substring(color.length());
                for (String family : COLOR_FAMILIES) {
                    if (remainder.equals(family)) return family;
                }
            }
        }
        return null;
    }

    /**
     * Check if a pool item matches a SPECIFIC_ITEM rule, with color family support.
     * - If the rule is a base family name (e.g. "minecraft:bundle"), match ANY color variant
     * - If the rule has a specific color (e.g. "minecraft:red_bundle"), match ONLY that exact item
     */
    static boolean matchesSpecificItem(String poolItemId, String ruleValue) {
        // Exact match always works
        if (poolItemId.equals(ruleValue)) return true;

        // Check if the rule is a base family name (no color prefix)
        String ruleFamily = getColorFamily(ruleValue);
        if (ruleFamily == null) return false; // not a color family item

        // Get the rule path
        String rulePath = ruleValue.contains(":") ? ruleValue.substring(ruleValue.indexOf(':') + 1) : ruleValue;

        // If the rule IS the base family name (e.g. "bundle", "shulker_box") -> match any variant
        if (rulePath.equals(ruleFamily)) {
            String poolFamily = getColorFamily(poolItemId);
            return ruleFamily.equals(poolFamily);
        }

        // If the rule has a specific color (e.g. "red_bundle") -> exact match only (already checked above)
        return false;
    }

    /**
     * Check if a pool item ID belongs to a color family (for "already correct" and source finding).
     * Used for bundle-like items where components differ.
     */
    static boolean isColorFamilyItem(String itemId) {
        return getColorFamily(itemId) != null;
    }

    static boolean isBundleItem(String itemId) {
        return "bundle".equals(getColorFamily(itemId));
    }

    private static String getPotionTypeId(ItemStack stack) {
        try {
            PotionContentsComponent comp = stack.get(DataComponentTypes.POTION_CONTENTS);
            if (comp == null || comp.potion().isEmpty()) return "";
            return comp.potion().get().getKey()
                    .map(k -> k.getValue().getPath())
                    .orElse("");
        } catch (Exception e) {
            return "";
        }
    }

    static int blockTierFromOrder(String itemId, String[] order) {
        if (order == null || order.length == 0) return Integer.MAX_VALUE;
        String path = itemId.contains(":") ? itemId.substring(itemId.indexOf(':') + 1) : itemId;
        for (int i = 0; i < order.length; i++) {
            if (path.equals(order[i])) return i;
        }
        return order.length;
    }

    static int arrowTypeFromOrder(ItemStack stack, String[] order) {
        if (order.length == 0) return 0;
        String itemId = getItemId(stack);
        String typeName;
        if (itemId.equals("minecraft:arrow"))           typeName = "arrow";
        else if (itemId.equals("minecraft:spectral_arrow")) typeName = "spectral_arrow";
        else if (itemId.equals("minecraft:tipped_arrow"))   typeName = "tipped_arrow";
        else typeName = "";
        for (int i = 0; i < order.length; i++) {
            if (typeName.equals(order[i])) return i;
        }
        return order.length;
    }

    static int potionTypeFromOrder(ItemStack stack, String[] order) {
        if (order.length == 0) return 0;
        String itemId = getItemId(stack);
        String typeName;
        if (itemId.equals("minecraft:potion"))            typeName = "potion";
        else if (itemId.equals("minecraft:splash_potion"))    typeName = "splash_potion";
        else if (itemId.equals("minecraft:lingering_potion")) typeName = "lingering_potion";
        else typeName = "";
        for (int i = 0; i < order.length; i++) {
            if (typeName.equals(order[i])) return i;
        }
        return order.length;
    }

    static int potionTierFromOrder(ItemStack stack, String[] order) {
        String typeId = getPotionTypeId(stack);
        if (typeId.isEmpty()) return order.length;
        for (int i = 0; i < order.length; i++) {
            if (typeId.equals(order[i])) return i;
        }
        return order.length;
    }

    static int groupNameToCategory(String group) {
        if (group == null) return CAT_MISC;
        if (group.equals("weapons"))  return CAT_WEAPON;
        if (group.equals("tools"))    return CAT_TOOL;
        if (group.equals("armor"))    return CAT_ARMOR;
        if (group.equals("valuables")) return CAT_VALUABLE;
        if (group.equals("blocks"))   return CAT_BLOCK;
        if (group.equals("food"))     return CAT_FOOD;
        if (group.equals("utility"))  return CAT_UTILITY;
        if (group.equals("potions"))  return CAT_POTION;
        if (group.equals("logs"))     return CAT_LOG;
        if (group.equals("boats"))    return CAT_BOAT;
        if (group.equals("plants"))   return CAT_PLANT;
        if (group.equals("stone"))    return CAT_STONE;
        if (group.equals("ores"))     return CAT_ORE;
        if (group.equals("cooked"))   return CAT_COOKED;
        if (group.equals("rawfood"))  return CAT_RAW_FOOD;
        if (group.equals("nether"))   return CAT_NETHER;
        if (group.equals("end"))      return CAT_END;
        if (group.equals("partial"))  return CAT_PARTIAL;
        if (group.equals("redstone")) return CAT_REDSTONE;
        if (group.equals("creative")) return CAT_CREATIVE;
        if (group.equals("arrows"))         return CAT_ARROW;
        if (group.equals("splash_potions")) return CAT_SPLASH_POTION;
        return CAT_MISC;
    }

    /**
     * Returns true if the given item matches the target category.
     * Handles virtual sub-group categories (e.g. CAT_SPLASH_POTION) that are not
     * returned by getCategory() but need special item-ID matching.
     * Sub-categories roll up: g:food catches cooked/raw, g:blocks catches logs/stone/nether/end/partial/plants,
     * g:valuables catches ores, g:utility catches boats/redstone.
     */
    static boolean categoryMatches(ItemStack stack, int targetCat) {
        if (targetCat == CAT_SPLASH_POTION) {
            return getItemId(stack).equals("minecraft:splash_potion");
        }
        int cat = getCategory(stack);
        if (cat == targetCat) return true;
        return poolCategoryOf(cat) == targetCat;
    }

    /**
     * Maps a fine-grained category (e.g. CAT_COOKED, CAT_LOG, CAT_ORE) to the broader
     * sort-pool it belongs to (CAT_FOOD, CAT_BLOCK, CAT_VALUABLE). Primary categories
     * return themselves.
     */
    static int poolCategoryOf(int cat) {
        switch (cat) {
            case CAT_COOKED:
            case CAT_RAW_FOOD:
                return CAT_FOOD;
            case CAT_LOG:
            case CAT_STONE:
            case CAT_NETHER:
            case CAT_END:
            case CAT_PARTIAL:
            case CAT_PLANT:
                return CAT_BLOCK;
            case CAT_ORE:
                return CAT_VALUABLE;
            case CAT_BOAT:
            case CAT_REDSTONE:
                return CAT_UTILITY;
            default:
                return cat;
        }
    }

    static String getItemId(ItemStack stack) {
        Identifier id = Registries.ITEM.getId(stack.getItem());
        return id.toString();
    }

    static int getCategory(ItemStack stack) {
        String id = getItemId(stack);

        // --- Sub-group checks (priority over broad categories) ---

        // Creative/admin items
        if (id.contains("command_block") || id.equals("minecraft:barrier") ||
                id.equals("minecraft:structure_block") || id.equals("minecraft:structure_void") ||
                id.equals("minecraft:jigsaw") || id.equals("minecraft:debug_stick") ||
                id.equals("minecraft:light")) {
            return CAT_CREATIVE;
        }

        // Ores, raw ores, ingots, nuggets, gems and their storage blocks
        if (id.contains("_ore") || id.equals("minecraft:ancient_debris") ||
                // Raw ore items
                id.equals("minecraft:raw_iron") || id.equals("minecraft:raw_gold") || id.equals("minecraft:raw_copper") ||
                // Raw ore storage blocks
                id.equals("minecraft:raw_iron_block") || id.equals("minecraft:raw_gold_block") || id.equals("minecraft:raw_copper_block") ||
                // Ingots & nuggets
                id.equals("minecraft:iron_ingot") || id.equals("minecraft:gold_ingot") || id.equals("minecraft:copper_ingot") ||
                id.equals("minecraft:netherite_ingot") || id.equals("minecraft:netherite_scrap") ||
                id.equals("minecraft:iron_nugget") || id.equals("minecraft:gold_nugget") ||
                // Gems & crystals
                id.equals("minecraft:diamond") || id.equals("minecraft:emerald") ||
                id.equals("minecraft:coal") || id.equals("minecraft:lapis_lazuli") ||
                id.equals("minecraft:amethyst_shard") || id.equals("minecraft:quartz") ||
                // Ore storage blocks
                id.equals("minecraft:diamond_block") || id.equals("minecraft:emerald_block") ||
                id.equals("minecraft:gold_block") || id.equals("minecraft:iron_block") ||
                id.equals("minecraft:copper_block") || id.equals("minecraft:coal_block") ||
                id.equals("minecraft:lapis_block") || id.equals("minecraft:netherite_block") ||
                id.equals("minecraft:amethyst_block") || id.equals("minecraft:quartz_block")) {
            return CAT_ORE;
        }

        // Cooked food (before generic food)
        if (id.contains("cooked_") || id.equals("minecraft:baked_potato")) {
            return CAT_COOKED;
        }

        // Raw meat/fish (before valuables which catches raw_)
        if (id.equals("minecraft:beef") || id.equals("minecraft:porkchop") ||
                id.equals("minecraft:chicken") || id.equals("minecraft:mutton") ||
                id.equals("minecraft:rabbit") || id.equals("minecraft:cod") ||
                id.equals("minecraft:salmon") || id.equals("minecraft:tropical_fish")) {
            return CAT_RAW_FOOD;
        }

        // Redstone components (before blocks/utility)
        if (id.equals("minecraft:redstone") || id.contains("redstone_torch") ||
                id.contains("redstone_lamp") || id.equals("minecraft:repeater") ||
                id.equals("minecraft:comparator") || id.contains("piston") ||
                id.equals("minecraft:observer") || id.equals("minecraft:lever") ||
                id.contains("tripwire_hook") || id.equals("minecraft:hopper") ||
                id.equals("minecraft:dropper") || id.equals("minecraft:dispenser") ||
                id.contains("daylight_detector") || id.equals("minecraft:target") ||
                id.equals("minecraft:note_block") || id.contains("_rail") ||
                id.equals("minecraft:rail") || id.equals("minecraft:tnt") ||
                id.contains("trapped_chest") || id.contains("redstone_block")) {
            return CAT_REDSTONE;
        }

        // Boats/rafts (before utility)
        if (id.contains("_boat") || id.contains("_raft")) {
            return CAT_BOAT;
        }

        // Partial/non-full blocks (slabs, stairs, fences, doors, etc.) - before stone/nether/log
        if (id.contains("_slab") || id.contains("_stairs") || id.contains("_fence") ||
                id.contains("_trapdoor") || id.contains("_door") ||
                id.contains("_pressure_plate") || id.contains("_button") ||
                id.contains("_pane") || id.contains("iron_bars") || id.contains("_wall")) {
            return CAT_PARTIAL;
        }

        // Logs & wood variants (before broad blocks)
        {
            String path = id.contains(":") ? id.substring(id.indexOf(':') + 1) : id;
            if (path.endsWith("_log") || path.endsWith("_wood") || path.endsWith("_stem") ||
                    path.equals("bamboo_block")) {
                return CAT_LOG;
            }
        }

        // Plants & vegetation (before broad blocks)
        if (id.contains("sapling") || id.contains("_flower") ||
                id.equals("minecraft:dandelion") || id.equals("minecraft:poppy") ||
                id.contains("_tulip") || id.contains("orchid") || id.contains("bluet") ||
                id.contains("allium") || id.contains("lily_of_the_valley") ||
                id.contains("oxeye_daisy") || id.contains("cornflower") ||
                id.contains("sunflower") || id.contains("lilac") || id.contains("peony") ||
                id.contains("rose_bush") || id.contains("_fern") ||
                id.contains("dead_bush") || id.contains("kelp") ||
                id.contains("lily_pad") || id.contains("dripleaf") ||
                id.contains("spore_blossom") || id.contains("cactus") ||
                (id.contains("bamboo") && !id.contains("bamboo_block")) ||
                id.contains("sugar_cane") || id.contains("vine") ||
                id.contains("glow_lichen") || id.contains("hanging_roots") ||
                id.contains("nether_sprouts") || id.contains("twisting_vines") ||
                id.contains("weeping_vines") || id.contains("torchflower") ||
                id.contains("pitcher") || id.contains("sea_pickle") ||
                id.contains("short_grass") || id.contains("tall_grass") ||
                id.contains("large_fern") || id.contains("seagrass") ||
                (id.contains("azalea") && !id.contains("leaves")) ||
                (id.contains("mushroom") && !id.contains("mushroom_block") && !id.contains("mushroom_stew")) ||
                // 1.21.4+
                id.contains("eyeblossom") || id.contains("wildflowers") ||
                id.contains("firefly_bush") || id.contains("leaf_litter") ||
                id.equals("minecraft:bush") || id.contains("dry_grass") ||
                id.contains("pink_petals")) {
            return CAT_PLANT;
        }

        // Stone types (before broad blocks)
        if (id.equals("minecraft:stone") || id.equals("minecraft:cobblestone") ||
                id.contains("granite") || id.contains("diorite") || id.contains("andesite") ||
                id.contains("sandstone") || id.contains("mossy_cobblestone") ||
                id.contains("stone_bricks") || id.contains("calcite") ||
                id.contains("dripstone") || id.contains("tuff") || id.contains("deepslate") ||
                id.contains("prismarine") || id.equals("minecraft:obsidian") ||
                id.contains("bedrock") || id.contains("smooth_stone") ||
                id.contains("chiseled_stone") || id.equals("minecraft:gravel") ||
                id.equals("minecraft:sand") || id.contains("red_sand")) {
            return CAT_STONE;
        }

        // Nether-specific items (before broad blocks)
        if (id.contains("dried_ghast") ||
                id.contains("netherrack") || id.contains("nether_brick") ||
                id.contains("nether_wart") || id.contains("magma_block") ||
                id.contains("soul_sand") || id.contains("soul_soil") ||
                id.contains("glowstone") || id.contains("blaze_rod") ||
                id.contains("blaze_powder") || id.contains("ghast_tear") ||
                id.contains("nether_star") || id.contains("nether_quartz") ||
                id.contains("crimson") || id.contains("warped") ||
                id.contains("basalt") || id.contains("blackstone") ||
                id.contains("nylium") || id.contains("shroomlight") ||
                id.contains("wart_block") || id.contains("crying_obsidian") ||
                id.contains("respawn_anchor") || id.contains("magma_cream") ||
                id.contains("soul_torch") || id.contains("soul_lantern")) {
            return CAT_NETHER;
        }

        // End-specific items (before broad blocks)
        if (id.contains("end_stone") || id.contains("purpur") || id.contains("end_rod") ||
                id.contains("chorus_") || id.equals("minecraft:dragon_egg") ||
                id.equals("minecraft:dragon_breath") || id.contains("end_crystal") ||
                id.contains("shulker_shell")) {
            return CAT_END;
        }

        // Weapons
        if (id.contains("sword") && !id.contains("_block") && !id.contains("_ore") ||
                id.contains("bow") && !id.contains("cross") && !id.contains("bowl") ||
                id.contains("crossbow") || id.contains("trident") || id.contains("mace")) {
            return CAT_WEAPON;
        }
        // Tools
        if (id.contains("pickaxe") || id.contains("_axe") ||
                id.contains("shovel") || id.contains("hoe") ||
                id.contains("shears") || id.contains("flint_and_steel") ||
                id.contains("fishing_rod")) {
            return CAT_TOOL;
        }
        // Armor
        if (id.contains("helmet") || id.contains("chestplate") || id.contains("leggings") ||
                id.contains("boots") || id.contains("shield") || id.contains("elytra") ||
                id.contains("turtle_helmet")) {
            return CAT_ARMOR;
        }
        // Note: A previous CAT_VALUABLE branch existed here, but every item it matched
        // (ore, raw_, ingot, nugget, diamond, emerald, amethyst_shard, lapis_lazuli,
        // coal, netherite_scrap) is already captured earlier by the more comprehensive
        // CAT_ORE branch. The constant is still used by the sort pools (valuables)
        // and can be re-enabled here if future valuables need a separate category.

        // Blocks (placeable) - manual string list
        if (id.contains("resin") || id.contains("vault") ||
                id.contains("decorated_pot") || id.contains("creaking_heart") ||
                id.contains("_block") || id.contains("plank") || id.contains("log") ||
                id.contains("_wood") ||
                id.contains("stone") || id.contains("dirt") || id.contains("sand") ||
                id.contains("gravel") || id.contains("glass") && !id.contains("glass_bottle") || id.contains("brick") ||
                id.contains("concrete") || id.contains("wool") || id.contains("terracotta") ||
                id.contains("slab") || id.contains("stairs") || id.contains("wall") ||
                id.contains("fence") || id.contains("door") || id.contains("pane") ||
                id.contains("copper") || id.contains("deepslate") || id.contains("tuff") ||
                id.contains("mud") || id.contains("clay") || id.contains("coral") ||
                id.contains("sculk") || id.contains("prismarine") || id.contains("sea_lantern") ||
                id.contains("netherrack") || id.contains("nether_brick") ||
                id.contains("basalt") || id.contains("blackstone") ||
                id.contains("soul_sand") || id.contains("soul_soil") ||
                id.contains("nylium") || id.contains("shroomlight") ||
                id.contains("wart_block") || id.contains("crying_obsidian") ||
                id.contains("end_stone") || id.contains("purpur") ||
                id.contains("_ore") || id.contains("obsidian") ||
                id.contains("moss") || id.contains("dripstone") || id.contains("calcite") ||
                id.contains("amethyst_block") || id.contains("budding_amethyst") ||
                id.contains("ice") || id.contains("hay_block") ||
                id.contains("sponge") || id.contains("magma_block") ||
                id.contains("lantern") || id.contains("campfire") ||
                id.contains("candle") || id.contains("chain") ||
                id.contains("bell") || id.contains("lightning_rod") ||
                id.contains("anvil") || id.contains("cauldron") ||
                id.contains("barrel") || id.contains("chest") ||
                id.contains("crafting_table") || id.contains("furnace") ||
                id.contains("smoker") || id.contains("blast_furnace") ||
                id.contains("grindstone") || id.contains("loom") ||
                id.contains("cartography_table") || id.contains("fletching_table") ||
                id.contains("smithing_table") || id.contains("stonecutter") ||
                id.contains("composter") || id.contains("brewing_stand") ||
                id.contains("enchanting_table") || id.contains("bookshelf") ||
                id.contains("lectern") || id.contains("beacon") ||
                id.contains("conduit") || id.contains("dispenser") ||
                id.contains("dropper") || id.contains("hopper") ||
                id.contains("observer") || id.contains("piston") ||
                id.contains("tnt") || id.contains("target") ||
                id.contains("daylight_detector") || id.contains("note_block") ||
                id.contains("jukebox") || id.contains("rail") ||
                id.contains("redstone_lamp") || id.contains("repeater") ||
                id.contains("comparator") || id.contains("bamboo_mosaic") ||
                id.contains("mangrove_roots") || id.contains("cherry") ||
                id.contains("leaves") || id.contains("mushroom_block") ||
                id.contains("mycelium") || id.contains("podzol") ||
                id.contains("farmland") || id.contains("grass_block") ||
                id.contains("shulker_box") || id.contains("_bed") ||
                id.contains("banner") || id.contains("carpet") ||
                id.contains("_sign") || id.contains("_head") || id.contains("skull") ||
                id.contains("sapling") || id.contains("azalea") ||
                id.contains("dripleaf") || id.contains("cobweb") ||
                id.contains("ladder") || id.contains("scaffolding") ||
                id.contains("_trapdoor") || id.contains("pressure_plate") ||
                id.contains("lever") || id.contains("tripwire_hook") ||
                id.contains("lodestone") || id.contains("respawn_anchor") ||
                id.contains("bedrock")) {
            return CAT_BLOCK;
        }
        // Food
        if (isFood(id)) {
            return CAT_FOOD;
        }
        // Potions (potion, splash_potion, lingering_potion)
        if (id.equals("minecraft:potion") || id.equals("minecraft:splash_potion") ||
                id.equals("minecraft:lingering_potion")) {
            return CAT_POTION;
        }
        // Arrows (before utility which also contains arrow via contains())
        if (id.equals("minecraft:arrow") || id.equals("minecraft:spectral_arrow") ||
                id.equals("minecraft:tipped_arrow")) {
            return CAT_ARROW;
        }
        // Utility items
        if (id.contains("torch") || id.contains("bucket") || id.contains("compass") ||
                id.contains("clock") || id.contains("map") || id.contains("lead") ||
                id.contains("name_tag") || id.contains("saddle") || id.contains("ender_pearl") ||
                id.contains("ender_eye") || id.contains("firework") || id.contains("arrow") ||
                id.contains("rocket") || id.contains("bone_meal") || id.contains("dye") ||
                id.contains("book") || id.contains("paper") || id.contains("string") ||
                id.contains("stick") || id.contains("feather") ||
                id.contains("bundle") || id.contains("spyglass") ||
                id.contains("goat_horn") || id.contains("music_disc") ||
                id.contains("spawn_egg") ||
                id.contains("experience_bottle") || id.contains("glass_bottle") ||
                id.contains("painting") || id.contains("item_frame") ||
                id.contains("armor_stand") || id.contains("minecart") ||
                id.contains("boat") || id.contains("raft") ||
                id.contains("snowball") || id.contains("egg") ||
                id.contains("fire_charge") ||
                // 1.21+ (Tricky Trials / Garden Awakens / Chase the Skies)
                id.contains("wind_charge") || id.contains("breeze_rod") ||
                id.contains("trial_key") || id.contains("ominous_bottle") ||
                id.contains("harness") || id.equals("minecraft:heavy_core") ||
                id.equals("minecraft:golden_apple") || id.equals("minecraft:enchanted_golden_apple")) {
            return CAT_UTILITY;
        }
        return CAT_MISC;
    }

    private static int getSubCategory(ItemStack stack) {
        String id = getItemId(stack);

        // Weapons: sword > bow > crossbow > trident > mace
        if (getCategory(stack) == CAT_WEAPON) {
            if (id.contains("sword")) return materialTier(id);
            if (id.contains("bow") && !id.contains("cross")) return 10;
            if (id.contains("crossbow")) return 11;
            if (id.contains("trident")) return 12;
            if (id.contains("mace")) return 13;
        }
        // Tools: pickaxe > axe > shovel > hoe
        if (getCategory(stack) == CAT_TOOL) {
            if (id.contains("pickaxe")) return materialTier(id);
            if (id.contains("_axe")) return 10 + materialTier(id);
            if (id.contains("shovel")) return 20 + materialTier(id);
            if (id.contains("hoe")) return 30 + materialTier(id);
            return 40;
        }
        // Armor: helmet > chestplate > leggings > boots > shield > elytra
        if (getCategory(stack) == CAT_ARMOR) {
            if (id.contains("helmet")) return materialTier(id);
            if (id.contains("chestplate")) return 10 + materialTier(id);
            if (id.contains("leggings")) return 20 + materialTier(id);
            if (id.contains("boots")) return 30 + materialTier(id);
            if (id.contains("shield")) return 40;
            if (id.contains("elytra")) return 41;
        }
        return 0;
    }

    private static boolean isFood(String id) {
        // Exclude golden apple and enchanted golden apple - they are utility items
        if (id.equals("minecraft:golden_apple") || id.equals("minecraft:enchanted_golden_apple")) return false;

        // Simple matches (unambiguous food item names)
        if (id.contains("apple")) return true;
        if (id.contains("bread")) return true;
        if (id.contains("beef")) return true;
        if (id.contains("pork")) return true;
        if (id.contains("mutton")) return true;
        if (id.contains("cod")) return true;
        if (id.contains("salmon")) return true;
        if (id.contains("carrot")) return true; // includes golden_carrot
        if (id.contains("melon_slice")) return true;
        if (id.contains("cookie")) return true;
        if (id.contains("pie")) return true;
        if (id.contains("stew")) return true;
        if (id.contains("soup")) return true;
        if (id.contains("berr")) return true; // sweet_berries, glow_berries
        if (id.contains("cooked_")) return true;
        if (id.contains("honey_bottle")) return true;
        if (id.contains("chorus_fruit")) return true;

        // Matches with exclusions (use explicit parens for clarity)
        if (id.contains("chicken")   && !id.contains("_egg"))                                   return true; // future-proof against chicken_egg
        if (id.contains("potato")    && !id.contains("poison"))                                  return true; // excludes poisonous_potato
        if (id.contains("dried_kelp") && !id.contains("_block"))                                 return true; // excludes dried_kelp_block
        if (id.contains("rabbit")    && !id.contains("_hide") && !id.contains("_foot"))          return true; // excludes rabbit_hide, rabbit_foot
        if (id.contains("beetroot")  && !id.contains("_seeds"))                                  return true; // excludes beetroot_seeds

        return false;
    }

    private static int getEnchantmentScore(ItemStack stack) {
        try {
            ItemEnchantmentsComponent enchantments = EnchantmentHelper.getEnchantments(stack);
            return enchantments.getSize();
        } catch (Exception e) {
            return 0;
        }
    }

    // Get best (lowest tier index) enchantment on item based on user-configured enchant order
    // Returns a score: tier * 1000 - level (so lower tier + higher level = lower score = better)
    private static int getBestEnchantTier(ItemStack stack, String[] enchOrder) {
        try {
            ItemEnchantmentsComponent enchantments = EnchantmentHelper.getEnchantments(stack);
            if (enchantments.isEmpty()) return 999999;
            
            int bestScore = 999999;
            String bestEnchant = "none";
            int bestLevel = 0;
            LOGGER.debug("[InvOrganizer] DEBUG: Checking enchants on " + getItemId(stack));
            for (var entry : enchantments.getEnchantments()) {
                String enchId = entry.getIdAsString();
                int level = enchantments.getLevel(entry);
                LOGGER.debug("[InvOrganizer] DEBUG: Found enchant ID: " + enchId + " level " + level);
                // Strip namespace: "minecraft:sharpness" -> "sharpness"
                String path = enchId.contains(":") ? enchId.substring(enchId.indexOf(':') + 1) : enchId;
                
                for (int i = 0; i < enchOrder.length; i++) {
                    if (path.equals(enchOrder[i]) || path.contains(enchOrder[i])) {
                        // Score = tier * 1000 - level
                        // Lower tier = better, higher level = better
                        // Example: Sharpness V (tier 2, level 5) = 2000 - 5 = 1995
                        //          Sharpness IV (tier 2, level 4) = 2000 - 4 = 1996
                        //          Unbreaking III (tier 11, level 3) = 11000 - 3 = 10997
                        int score = i * 1000 - level;
                        LOGGER.debug("[InvOrganizer] DEBUG: Matched " + path + " at tier " + i + " level " + level + " score=" + score);
                        if (score < bestScore) {
                            bestScore = score;
                            bestEnchant = path;
                            bestLevel = level;
                        }
                        break;
                    }
                }
            }
            LOGGER.debug("[InvOrganizer] Item " + getItemId(stack) + " best enchant: " + bestEnchant + " " + bestLevel + " (score " + bestScore + ")");
            return bestScore;
        } catch (Exception e) {
            LOGGER.debug("[InvOrganizer] ERROR in getBestEnchantTier: " + e.getMessage());
            return 999999;
        }
    }

    private static int getRemainingDurability(ItemStack stack) {
        if (stack.getMaxDamage() == 0) return 0;
        return stack.getMaxDamage() - stack.getDamage();
    }

    // Material tier based on config preferences
    private static int materialTier(String id) {
        // Determine item type to look up the right preference
        String type = null;
        if (id.contains("sword")) type = "sword";
        else if (id.contains("pickaxe")) type = "pickaxe";
        else if (id.contains("_axe")) type = "axe";
        else if (id.contains("shovel")) type = "shovel";
        else if (id.contains("hoe")) type = "hoe";
        else if (id.contains("helmet")) type = "helmet";
        else if (id.contains("chestplate")) type = "chestplate";
        else if (id.contains("leggings")) type = "leggings";
        else if (id.contains("boots")) type = "boots";

        if (type != null) {
            OrganizerConfig config = OrganizerConfig.get();
            String[] order = config.getPreference(type);
            if (order != null && order.length > 0) {
                for (int i = 0; i < order.length; i++) {
                    if (id.contains(order[i])) return i;
                }
                return order.length;
            }
        }

        // Fallback default
        if (id.contains("netherite")) return 0;
        if (id.contains("diamond")) return 1;
        if (id.contains("iron")) return 2;
        if (id.contains("gold") || id.contains("golden")) return 3;
        if (id.contains("stone")) return 4;
        if (id.contains("wood") || id.contains("leather")) return 5;
        if (id.contains("chain")) return 6;
        return 7;
    }

    // Food tier from user-configured order
    private static int foodTierFromOrder(String id, String[] foodOrder) {
        // Strip namespace for matching: "minecraft:cooked_beef" matches "cooked_beef"
        String path = id.contains(":") ? id.substring(id.indexOf(':') + 1) : id;
        for (int i = 0; i < foodOrder.length; i++) {
            if (path.equals(foodOrder[i]) || path.contains(foodOrder[i])) return i;
        }
        return foodOrder.length; // unknown food = last
    }

    // Material tier from user-configured order (used by configSort comparator)
    private static int materialTierFromOrder(String id, String[] matOrder) {
        for (int i = 0; i < matOrder.length; i++) {
            String mat = matOrder[i];
            if (mat.equals("gold")) {
                if (id.contains("gold") || id.contains("golden")) return i;
            } else if (mat.equals("wood")) {
                if (id.contains("wood") || id.contains("wooden")) return i;
            } else {
                if (id.contains(mat)) return i;
            }
        }
        return matOrder.length; // unknown material = last
    }

    private static int getFoodValue(ItemStack stack) {
        String id = getItemId(stack);
        // Manual food values (hunger restored) - approximate order by nutrition
        // High value foods (8+)
        if (id.contains("stew") || id.contains("soup")) return 10;
        if (id.equals("minecraft:golden_carrot")) return 6;
        if (id.contains("pie")) return 8;
        if (id.equals("minecraft:cooked_beef") || id.equals("minecraft:cooked_mutton")) return 8;
        if (id.equals("minecraft:cooked_porkchop")) return 8;
        if (id.equals("minecraft:cooked_chicken")) return 6;
        if (id.equals("minecraft:cooked_rabbit")) return 5;
        if (id.equals("minecraft:cooked_cod") || id.equals("minecraft:cooked_salmon")) return 5;
        if (id.equals("minecraft:steak")) return 8;
        
        // Medium value foods (4-6)
        if (id.equals("minecraft:bread")) return 5;
        if (id.equals("minecraft:cooked_potato")) return 5;
        if (id.equals("minecraft:honey_bottle")) return 6;
        if (id.equals("minecraft:melon_slice")) return 2;
        if (id.equals("minecraft:carrot")) return 3;
        if (id.equals("minecraft:potato")) return 1;
        if (id.equals("minecraft:beetroot")) return 1;
        if (id.equals("minecraft:pumpkin_pie")) return 8;
        
        // Low value foods (1-3)
        if (id.equals("minecraft:apple")) return 4;
        if (id.equals("minecraft:dried_kelp")) return 1;
        if (id.equals("minecraft:cookie")) return 2;
        if (id.equals("minecraft:chorus_fruit")) return 4;
        
        // Raw meats (lower than cooked)
        if (id.equals("minecraft:beef") || id.equals("minecraft:porkchop")) return 3;
        if (id.equals("minecraft:chicken")) return 2;
        if (id.equals("minecraft:mutton") || id.equals("minecraft:rabbit")) return 3;
        if (id.equals("minecraft:cod") || id.equals("minecraft:salmon")) return 2;
        
        // Berries and small items
        if (id.contains("berr")) return 2;
        
        // Default low value
        return 1;
    }
}
