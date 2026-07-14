package com.example.inventoryorganizer;

import com.example.inventoryorganizer.config.OrganizerConfig;
import com.example.inventoryorganizer.config.SlotRule;
import com.example.inventoryorganizer.config.StoragePreset;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.client.Minecraft;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.component.BundleContents;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import org.apache.commons.lang3.math.Fraction;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.resources.Identifier;
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
    // Slots 9-35: main inventory (Inventory 9-35)
    // Slots 36-44: hotbar (Inventory 0-8)
    // So Inventory slot X maps to screen slot:
    //   hotbar 0-8 -> screen 36-44
    //   main 9-35 -> screen 9-35

    static int playerToScreenSlot(int playerSlot) {
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
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.gameMode == null) return;
        if (client.player.inventoryMenu == null) return;

        // ---- Defense in depth: central fight-mode enforcement ----
        // If combat is active and we were NOT entered through sortInventoryFightMode(), re-route so the
        // one-item-per-press limit + cooldown always apply (no caller can full-sort during PvP).
        if (FightModeTracker.isActive() && !fightModeLimit) {
            if (!FightModeTracker.canUseOI()) return;   // honour the randomised cooldown
            FightModeTracker.markOIUsed();
            sortInventoryFightMode();                    // sets fightModeLimit=true, re-enters with the limit
            return;
        }

        Inventory inv = client.player.getInventory();
        int syncId = client.player.inventoryMenu.containerId;
        OrganizerConfig cfg = OrganizerConfig.get();
        // In-world, tags are loaded: this is where the deferred built-in-group materialization (v9)
        // actually runs if it was skipped at client init, so g:/cg: groups have full membership.
        cfg.materializeBuiltinGroupsOnce();
        cfg.materializeSwitchGroupsOnce(); // ensure Pickaxes/Axes/Shovels/Hoes/Swords exist as normal cg's
        long runId = System.currentTimeMillis() % 100000L;

        // Single-player Auto-switch mode: serve the Auto inventory rule set during this sort so tools land
        // in their per-mode slots. Cleared in finally so other readers see the plain rules.
        boolean autoSp = false;
        try {
            autoSp = client.hasSingleplayerServer() && client.getSingleplayerServer() != null
                    && !client.getSingleplayerServer().isPublished() && cfg.isSwitchEnabled();
        } catch (Throwable ignored) {}
        OrganizerConfig.setAutoRuleActive(autoSp);
        try {

        // Merge partial stacks of the same item FIRST (the swap-based layout only moves whole slots).
        // Skipped in fight mode (one action per press).
        if (!fightModeLimit) consolidatePartialStacks(client, syncId, inv);

        LOGGER.info("[InvSort] #{} START {} {}", runId, fightModeLimit ? "FIGHT" : "normal", snapshotStr(inv));

        // Two phases, each with verify + retry: Pass A places items by KIND (rules satisfied + the
        // group-row layout for free slots) IGNORING rank ordering; Pass B refines the layout to the rank
        // settings (material/tier/enchant/…). Pass B is a no-op (0 swaps) when ranks already match.
        unplacedItemId = null; // reset; groupRowFill sets it if an item can't be placed (inv full)
        // Run BOTH phases unconditionally. A phase that can't fully converge (e.g. one item that has no
        // valid source — torch you no longer own, overflow when the inventory is full) must NOT abort the
        // whole sort: that used to skip Pass B (ranks/equipment) AND the bundle fill. We still do every
        // placement that IS possible, then report a problem only at the end if either phase fell short.
        boolean okA = runPhase(client, syncId, inv, cfg, false, runId);
        boolean okB = runPhase(client, syncId, inv, cfg, true,  runId);

        // Final pass: absorb matching items into bundles. Done AFTER the verify+retry phases (never inside
        // them) so pulling items into a bundle can't trip the exact-match verify and cause retry loops.
        if (!fightModeLimit) {
            try {
                ItemStack[] cur = new ItemStack[36];
                for (int i = 0; i < 36; i++) cur[i] = inv.getItem(i).copy();
                fillBundles(client, syncId, cfg, cur);
            } catch (Throwable t) {
                LOGGER.error("[InvSort] #{} fillBundles failed", runId, t);
            }
        }
        if (!okA || !okB) { notifySortProblem(client, runId); return; }
        LOGGER.info("[InvSort] #{} DONE {}", runId, snapshotStr(inv));
        // If something had nowhere to go (inventory full), tell the player and suggest a remedy:
        // free up a slot for that item, or set up a trash/void rule to auto-clear overflow.
        if (unplacedItemId != null && !fightModeLimit) notifyNoRoom(client, unplacedItemId, runId);
        } finally {
            OrganizerConfig.setAutoRuleActive(false);
        }
    }

    /** Max sort attempts per phase before giving up and warning the player. */
    private static final int SORT_MAX_ATTEMPTS = 5;

    /** Set by groupRowFill when an item had no free slot to land in (inventory full). Null = all placed.
     *  Read once after the sort phases to warn the player which item didn't get a home. */
    private static String unplacedItemId = null;

    /**
     * Does player slot {@code s} (0-35) take part in rule/tier sorting? The main inventory (9-35) always
     * does. A hotbar slot (0-8) participates ONLY when it carries an explicit (non-ANY) rule — so a
     * specific-item / type / group / tier set on a hotbar slot is honoured. Hotbar slots left on ANY stay
     * completely untouched (never pooled, never a sort target), preserving the "hotbar is yours" rule.
     */
    static boolean isSortableSlot(OrganizerConfig config, int s) {
        if (s >= 9 && s <= 35) return true;
        if (s >= 0 && s <= 8) return config.getSlotRule(s).getType() != SlotRule.Type.ANY;
        return false;
    }

    /**
     * Run one sort phase to completion: (re)compute the target layout from the live inventory, execute
     * it, verify an exact match, retry up to {@link #SORT_MAX_ATTEMPTS} times on mismatch (desync
     * recovery). Everything is logged. Returns false only if it never converged.
     */
    private static boolean runPhase(Minecraft client, int syncId, Inventory inv, OrganizerConfig config,
                                    boolean applyRanks, long runId) {
        String phase = applyRanks ? "B(rank)" : "A(base)";
        int maxAttempts = fightModeLimit ? 1 : SORT_MAX_ATTEMPTS;
        int prevMismatch = Integer.MAX_VALUE;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            java.util.List<String> mismatches = sortOnce(client, syncId, inv, config, applyRanks, runId, attempt);
            if (mismatches == null) {
                LOGGER.info("[InvSort] #{} {} attempt {}/{}: nothing to place", runId, phase, attempt, maxAttempts);
                return true;
            }
            LOGGER.info("[InvSort] #{} {} attempt {}/{}: {}", runId, phase, attempt, maxAttempts,
                    mismatches.isEmpty() ? "OK" : ("MISMATCH " + mismatches));
            if (mismatches.isEmpty()) return true;
            if (fightModeLimit) return true; // fight mode does a single swap; never loop/flicker
            // No progress vs the previous attempt → the remaining mismatches aren't desync, they're
            // genuinely unsatisfiable (item not present / inventory full). Stop retrying: more rounds
            // won't help and just spam clicks. The caller still proceeds to the next phase + bundle fill.
            if (mismatches.size() >= prevMismatch) {
                LOGGER.warn("[InvSort] #{} {} stuck ({} unsatisfiable) — stopping retries", runId, phase, mismatches.size());
                return false;
            }
            prevMismatch = mismatches.size();
        }
        LOGGER.warn("[InvSort] #{} {} FAILED after {} attempts", runId, phase, maxAttempts);
        return false;
    }

    /**
     * One attempt of a phase: compute the desired layout (rules + group-row free-slot layout; rank
     * ordering only when {@code applyRanks}) and execute it with the proven swap primitives. Returns the
     * remaining mismatches (empty = perfect; null = there was nothing to place at all).
     */
    private static java.util.List<String> sortOnce(Minecraft client, int syncId, Inventory inv,
                                                   OrganizerConfig config, boolean applyRanks, long runId, int attempt) {
        // Step 1: Create EXACT snapshot of current inventory (with enchantments, durability, etc.)
        // This is the SOURCE OF TRUTH for finding items during swaps
        ItemStack[] inventorySnapshot = new ItemStack[36];
        for (int i = 0; i < 36; i++) {
            inventorySnapshot[i] = inv.getItem(i).copy(); // DEEP COPY with all components
        }

        // Diagnostic snapshot — BEFORE sort
        StringBuilder beforeSnap = new StringBuilder("[InvOrganizer] BEFORE sort: ");
        for (int i = 0; i < 36; i++) {
            if (!inventorySnapshot[i].isEmpty()) {
                beforeSnap.append(i).append("=").append(getItemId(inventorySnapshot[i])).append("x").append(inventorySnapshot[i].getCount()).append(" ");
            }
        }
        LOGGER.debug(beforeSnap.toString());

        // Step 2: Build desired layout as an array: desiredSlots[playerSlot] = desired ItemStack
        ItemStack[] desired = new ItemStack[36];
        for (int i = 0; i < 36; i++) {
            desired[i] = ItemStack.EMPTY;
        }
        // desiredEquip[0-4] = head, chest, legs, feet, offhand (assigned during pool distribution)
        ItemStack[] desiredEquip = new ItemStack[5];
        for (int i = 0; i < 5; i++) desiredEquip[i] = ItemStack.EMPTY;

        // Collect items to sort from the INTERNAL inventory (9-35) + equipment only. The hotbar (0-8) is
        // deliberately left untouched: its items are NOT pooled (so the sort never pulls them out) and its
        // slots are never targeted. Equipment is included so the tier system can place gear.
        List<ItemStack> allItems = new ArrayList<>();
        for (int i = 0; i < 36; i++) {
            if (!isSortableSlot(config, i)) continue; // unruled hotbar (0-8 on ANY) stays untouched
            ItemStack stack = inv.getItem(i);
            if (!stack.isEmpty()) {
                allItems.add(stack.copy());
            }
        }
        // Also collect from equipment slots (armor + offhand)
        // Inventory: armor slots 36-39 (feet=36, legs=37, chest=38, head=39), offhand=40
        // But getArmorStack/screen slots: head=39, chest=38, legs=37, feet=36
        int[] equipInvSlots = {39, 38, 37, 36, 40}; // head, chest, legs, feet, offhand
        ItemStack[] equipOriginal = new ItemStack[5];
        for (int e = 0; e < 5; e++) {
            ItemStack eqStack = inv.getItem(equipInvSlots[e]);
            equipOriginal[e] = eqStack.copy();
            // Only pool worn gear when this equip slot has an explicit rule. An unruled (ANY) equip slot is
            // left completely alone — otherwise the worn armour would be pooled, stripped off and re-equipped
            // every sort, which plays the equip sound for no reason.
            boolean equipRuled = config.getSlotRuleByKey(OrganizerConfig.EQUIPMENT_SLOTS[e]).getType() != SlotRule.Type.ANY;
            if (equipRuled && !eqStack.isEmpty()) {
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

        // Sort each category using user-configured criteria from sort preferences (config is a param now).

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

        // PASS A (applyRanks == false): ignore rank ordering entirely — sort every bucket by item id so
        // the layout is deterministic and groups identical items, but WITHOUT material/tier/enchant
        // preference. Pass B (applyRanks == true) keeps the ranked sort above.
        if (!applyRanks) {
            Comparator<ItemStack> byId = Comparator.comparing(InventorySorter::getItemId);
            weapons.sort(byId); tools.sort(byId); armor.sort(byId); valuables.sort(byId);
            blocks.sort(byId); food.sort(byId); potions.sort(byId); arrows.sort(byId);
        }

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
            // Tier order is a RANK setting → only honoured in Pass B. In Pass A tierOrder stays empty
            // (natural slot order), so the base pass doesn't fight the rank pass over slot priority.
            String[] tierSaved = applyRanks ? config.getPreference("tier_order") : null;
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
            List<Integer> untieredHotbar = new ArrayList<>(); // intentionally left empty: hotbar untouched
            List<Integer> untieredMain = new ArrayList<>();
            // Main inventory (9-35) always participates; hotbar slots (0-8) only when they carry an
            // explicit rule (isSortableSlot). Unruled hotbar slots are skipped so they stay untouched.
            for (int i = 0; i <= 35; i++) {
                if (!isSortableSlot(config, i)) continue;
                if (tierOrder.containsKey(i)) tieredSlots.add(i);
                else untieredMain.add(i);
            }
            // Equipment slots (100-104): ALWAYS part of the rule passes — tiered ones sort by tier, the
            // rest go in natural order. (Previously only tiered equip slots were added, so in Pass A — where
            // tierOrder is empty — gear was never assigned to its equip slot. The pooled armour then fell
            // into inventory slots it couldn't be sourced into, failing Pass A and blocking Pass B: the set
            // never got worn and bundles never filled.)
            List<Integer> untieredEquip = new ArrayList<>();
            for (int i = 100; i <= 104; i++) {
                if (tierOrder.containsKey(i)) tieredSlots.add(i);
                else untieredEquip.add(i);
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
            ruleOrderList.addAll(untieredEquip); // equip slots always processed (see note above)

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
                    String groupName = rule.getValue();
                    // After materialization, built-in groups ARE custom groups: resolve membership +
                    // rank order the same way custom groups do, so the ranks screen controls which item
                    // wins. Falls back to the heuristic only for non-materialized names (e.g. "misc").
                    java.util.List<String> members = groupMembersOrdered(config, groupName);
                    if (members != null) {
                        outerGroup:
                        for (String preferredId : members) {
                            for (int j = 0; j < pool.size(); j++) {
                                String poolId = getItemId(pool.get(j));
                                if (poolId.equals(preferredId) || poolId.equals("minecraft:" + preferredId)) {
                                    if (s >= 100) desiredEquip[s - 100] = pool.remove(j);
                                    else desired[s] = pool.remove(j);
                                    break outerGroup;
                                }
                            }
                        }
                    } else {
                        for (int j = 0; j < pool.size(); j++) {
                            if (matchesBuiltinGroup(pool.get(j), groupName)) {
                                if (s >= 100) desiredEquip[s - 100] = pool.remove(j);
                                else desired[s] = pool.remove(j);
                                break;
                            }
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
                    // Current membership merged with the saved rank order (so items just added to the group
                    // are matched immediately, no ranks re-save needed). See groupMembersOrdered.
                    java.util.List<String> groupItems = groupMembersOrdered(config, groupName);
                    if (groupItems == null) groupItems = config.getCustomGroup(groupName);
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
            // (Old Pass 3/4 "fill ANY slots" removed — the free internal-inventory slots are now laid out
            // by groupRowFill() AFTER the if/else, for both the has-rules and no-rules cases.)

            // Debug desired equipment
            for (int i = 0; i < 5; i++) {
                if (!desiredEquip[i].isEmpty()) {
                    LOGGER.debug("[InvOrganizer] DesiredEquip[" + i + "]=" + getItemId(desiredEquip[i]));
                }
            }
        }
        // (no-rules case simply skips the rule passes above and falls through here)

        // Group-row layout for the free ANY slots of the INTERNAL inventory (9-35); hotbar (0-8) is left
        // untouched. Runs in BOTH cases: leftover free slots (has-rules) and the whole 9-35 (no-rules).
        groupRowFill(desired, config, pool, runId);

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
            current[i] = inv.getItem(i).copy();
        }

        // Abort if player is holding an item on cursor: first clickSlot would place it
        // instead of picking up, corrupting all subsequent swap operations.
        if (!client.player.inventoryMenu.getCarried().isEmpty()) {
            LOGGER.info("[InvSort] #{} abort: cursor not empty", runId);
            return java.util.List.of("aborted: cursor not empty");
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
            for (int i = 0; i < 36; i++) current[i] = inv.getItem(i).copy();

            ItemStack want = desired[targetSlot];
            String wantId = getItemId(want);
            boolean isSpecial = isColorFamilyItem(wantId);

            ItemStack have = current[targetSlot];

            // Already correct
            if (!have.isEmpty()) {
                if (isSpecial) {
                    if (getItemId(have).equals(wantId)) continue;
                } else {
                    if (ItemStack.isSameItemSameComponents(have, want)
                            && have.getCount() == want.getCount()) continue;
                }
            }

            // If target slot is occupied, we must pre-clear it first.
            // needsClear = true whenever target doesn't have EXACTLY the right item+count.
            // (Same-type but different count must also pre-clear to avoid stacking instead of swapping.)
            if (!have.isEmpty()) {
                boolean needsClear = isSpecial
                    ? !getItemId(have).equals(wantId)
                    : !(ItemStack.isSameItemSameComponents(have, want) && have.getCount() == want.getCount());
                if (needsClear) {
                    int emptyDump = -1;
                    // Only dump into ANY-rule slots in the internal inventory (9-35); never the hotbar.
                    for (int dist = 1; dist < 36 && emptyDump == -1; dist++) {
                        int above = targetSlot - dist;
                        int below = targetSlot + dist;
                        if (above >= 9 && current[above].isEmpty() && desired[above].isEmpty()
                                && config.getSlotRule(above).getType() == SlotRule.Type.ANY) emptyDump = above;
                        else if (below < 36 && current[below].isEmpty() && desired[below].isEmpty()
                                && config.getSlotRule(below).getType() == SlotRule.Type.ANY) emptyDump = below;
                    }
                    if (emptyDump != -1) {
                        LOGGER.debug("[InvOrganizer] Pre-clearing slot " + targetSlot + " -> " + emptyDump);
                        doSwap(client, syncId, targetSlot, emptyDump);
                        for (int i = 0; i < 36; i++) current[i] = inv.getItem(i).copy();
                        have = current[targetSlot]; // should be empty now
                    }
                    // else: inventory full (no empty dump slot) → fall through to a DIRECT source↔target
                    // swap below; the displaced item lands in the source slot. Safe now: verify+retry will
                    // re-run if this leaves anything out of place.
                }
            }

            // Find where the wanted item currently is.
            // Skip slots where the desired item is already correctly placed.
            int sourceSlot = -1;

            if (isSpecial) {
                for (int i = 0; i < 36; i++) { // sortable slots only (unruled hotbar untouched)
                    if (i == targetSlot || !isSortableSlot(config, i)) continue;
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
                    if (i == targetSlot || !isSortableSlot(config, i)) continue;
                    if (!desired[i].isEmpty() && !current[i].isEmpty()
                            && ItemStack.isSameItemSameComponents(current[i], desired[i])) continue;
                    if (!current[i].isEmpty() && ItemStack.isSameItemSameComponents(current[i], want)) {
                        sourceSlot = i; break;
                    }
                }
                // Fallback: ID + count match
                if (sourceSlot == -1) {
                    for (int i = 0; i < 36; i++) {
                        if (i == targetSlot || !isSortableSlot(config, i)) continue;
                        if (!desired[i].isEmpty() && !current[i].isEmpty()
                                && ItemStack.isSameItemSameComponents(current[i], desired[i])) continue;
                        if (!current[i].isEmpty() && getItemId(current[i]).equals(wantId)
                                && current[i].getCount() == want.getCount()) {
                            sourceSlot = i; break;
                        }
                    }
                }
            }

            if (sourceSlot == -1) {
                LOGGER.info("[InvSort] #{} swap SKIP target={} want={} (source not found)", runId, targetSlot, wantId);
                continue;
            }

            LOGGER.info("[InvSort] #{} swap {}->{} want={} displaced={}", runId, sourceSlot, targetSlot, wantId,
                    have.isEmpty() ? "empty" : getItemId(have));
            doSwap(client, syncId, sourceSlot, targetSlot);

            // Re-read actual inventory after swap — this is the key fix for duplication:
            // instead of manually tracking changes (which diverges on stacking/server corrections),
            // we always work with ground truth.
            for (int i = 0; i < 36; i++) current[i] = inv.getItem(i).copy();

            // If cursor is non-empty after doSwap, something went wrong — emergency place
            if (!client.player.inventoryMenu.getCarried().isEmpty()) {
                LOGGER.debug("[InvOrganizer] WARNING: cursor not empty after swap, emergency drop");
                boolean cleared = false;
                // Prefer ANY-rule slots so we don't pollute a rule-bound slot.
                for (int ec = 9; ec < 36 && !cleared; ec++) {
                    if (current[ec].isEmpty() && config.getSlotRule(ec).getType() == SlotRule.Type.ANY) {
                        client.gameMode.handleContainerInput(syncId, playerToScreenSlot(ec), 0, ContainerInput.PICKUP, client.player);
                        if (client.player.inventoryMenu.getCarried().isEmpty()) {
                            for (int i = 0; i < 36; i++) current[i] = inv.getItem(i).copy();
                            cleared = true;
                        }
                    }
                }
                // Last-resort fallback: any empty slot (avoids losing the item entirely).
                for (int ec = 0; ec < 36 && !cleared; ec++) {
                    if (current[ec].isEmpty()) {
                        client.gameMode.handleContainerInput(syncId, playerToScreenSlot(ec), 0, ContainerInput.PICKUP, client.player);
                        if (client.player.inventoryMenu.getCarried().isEmpty()) {
                            for (int i = 0; i < 36; i++) current[i] = inv.getItem(i).copy();
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
            // No desired item for this slot (e.g. unruled equip slot) → leave the worn gear ON. Stripping it
            // would be a pointless unequip + later re-equip, both of which play the equip sound.
            if (desiredEquip[e].isEmpty()) continue;
            // Check if this equip slot already has the desired item
            if (ItemStack.isSameItemSameComponents(equipOriginal[e], desiredEquip[e])) {
                LOGGER.debug("[InvOrganizer] EquipKeep[" + e + "]=" + getItemId(equipOriginal[e]) + " (already correct)");
                continue;
            }
            // Find an empty INTERNAL-inventory slot (9-35) to unequip into — prefer ANY-rule slots; never
            // the hotbar (kept untouched). If 9-35 is completely full, leave the gear equipped.
            int emptySlot = -1;
            for (int i = 9; i <= 35; i++) {
                if (current[i].isEmpty() && config.getSlotRule(i).getType() == SlotRule.Type.ANY) { emptySlot = i; break; }
            }
            if (emptySlot == -1) {
                for (int i = 9; i <= 35; i++) {
                    if (current[i].isEmpty()) { emptySlot = i; break; }
                }
            }
            if (emptySlot != -1) {
                LOGGER.debug("[InvOrganizer] Unequip[" + e + "]=" + getItemId(equipOriginal[e]) + " to slot " + emptySlot);
                int equipScreen = equipScreenSlots[e];
                int destScreen = playerToScreenSlot(emptySlot);
                client.gameMode.handleContainerInput(syncId, equipScreen, 0, ContainerInput.PICKUP, client.player);
                client.gameMode.handleContainerInput(syncId, destScreen, 0, ContainerInput.PICKUP, client.player);
                if (!client.player.inventoryMenu.getCarried().isEmpty()) {
                    client.gameMode.handleContainerInput(syncId, equipScreen, 0, ContainerInput.PICKUP, client.player);
                }
                current[emptySlot] = equipOriginal[e].copy();
            }
        }

        // Now equip desired items from inventory
        for (int e = 0; e < 5; e++) {
            if (fightModeLimit) break; // fight mode: never touch equipment
            if (desiredEquip[e].isEmpty()) continue;
            // Check if already equipped correctly (wasn't unequipped above)
            if (!equipOriginal[e].isEmpty() && ItemStack.isSameItemSameComponents(equipOriginal[e], desiredEquip[e])) {
                continue; // already correct
            }
            // Find the desired item in the internal inventory (9-35); never source from the hotbar.
            int sourcePlayerSlot = -1;
            for (int i = 9; i < 36; i++) {
                if (current[i].isEmpty()) continue;
                if (ItemStack.isSameItemSameComponents(current[i], desiredEquip[e])) {
                    sourcePlayerSlot = i;
                    break;
                }
            }
            // Fallback: match by item ID
            if (sourcePlayerSlot == -1) {
                String wantId = getItemId(desiredEquip[e]);
                for (int i = 9; i < 36; i++) {
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
            client.gameMode.handleContainerInput(syncId, sourceScreen, 0, ContainerInput.PICKUP, client.player);
            client.gameMode.handleContainerInput(syncId, equipScreenSlots[e], 0, ContainerInput.PICKUP, client.player);
            if (!client.player.inventoryMenu.getCarried().isEmpty()) {
                client.gameMode.handleContainerInput(syncId, sourceScreen, 0, ContainerInput.PICKUP, client.player);
            }
            current[sourcePlayerSlot] = ItemStack.EMPTY;
        }

        // NOTE: bundle filling is intentionally NOT done here. Absorbing items into a bundle empties their
        // slots, which would trip this phase's exact-match verify() and trigger needless retries (and could
        // even block Pass B). It runs once as a final pass in sortInventory(), after verify is satisfied.

        // Second pass: clear slots that should be empty but still have items
        // Only move items OUT of EMPTY_LOCKED slots or slots where the wrong item ended up
        // Never dump items INTO EMPTY_LOCKED or rule-bound slots
        // Re-read inventory state — equip/swap phases may have left current[] stale.
        for (int i = 0; i < 36; i++) current[i] = inv.getItem(i).copy();
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
            // (No hotbar fallback — the hotbar is kept untouched.)
            if (emptySlot != -1) {
                LOGGER.debug("[InvOrganizer] 2ndPass: moving " + getItemId(current[targetSlot]) + " from rule-bound slot " + targetSlot + " to ANY slot " + emptySlot);
                doSwap(client, syncId, targetSlot, emptySlot);
                current[emptySlot] = current[targetSlot];
                current[targetSlot] = ItemStack.EMPTY;
            } else {
                LOGGER.debug("[InvOrganizer] 2ndPass: NO ANY slot available for " + getItemId(current[targetSlot]) + " stuck in rule-bound slot " + targetSlot);
            }
        }

        // Verify: compare the live inventory to the computed desired layout (exact match). The returned
        // list is empty on success, or describes each mismatch for the log + retry decision.
        return verify(inv, config, desired, desiredEquip, runId);
    }

    /**
     * Exact-match verification of the executed sort: every non-empty desired slot must hold exactly the
     * desired stack (item + components + count), and every desired equipment piece must be worn. Returns
     * the list of mismatches (empty = perfect). Items that vanished entirely are flagged separately.
     */
    private static java.util.List<String> verify(Inventory inv, OrganizerConfig config,
                                                 ItemStack[] desired, ItemStack[] desiredEquip, long runId) {
        java.util.List<String> mism = new java.util.ArrayList<>();
        for (int s = 0; s < 36; s++) {
            ItemStack want = desired[s];
            if (want.isEmpty()) continue;
            ItemStack have = inv.getItem(s);
            if (!(ItemStack.isSameItemSameComponents(have, want) && have.getCount() == want.getCount())) {
                mism.add("slot " + s + " want=" + getItemId(want) + "x" + want.getCount()
                        + " have=" + (have.isEmpty() ? "empty" : getItemId(have) + "x" + have.getCount()));
            }
        }
        int[] equipInvSlots = {39, 38, 37, 36, 40}; // head, chest, legs, feet, offhand
        for (int e = 0; e < 5; e++) {
            ItemStack want = desiredEquip[e];
            if (want.isEmpty()) continue;
            ItemStack have = inv.getItem(equipInvSlots[e]);
            if (!(ItemStack.isSameItemSameComponents(have, want) && have.getCount() == want.getCount())) {
                mism.add("equip " + e + " want=" + getItemId(want)
                        + " have=" + (have.isEmpty() ? "empty" : getItemId(have)));
            }
        }
        return mism;
    }

    /** Lower-cased one-line snapshot of the inventory (slot=item×count) for the log. */
    private static String snapshotStr(Inventory inv) {
        StringBuilder sb = new StringBuilder("inv[");
        for (int i = 0; i < 36; i++) {
            ItemStack s = inv.getItem(i);
            if (!s.isEmpty()) sb.append(i).append(":").append(getItemId(s)).append("x").append(s.getCount()).append(" ");
        }
        return sb.append("]").toString();
    }

    /** Tell the player an item had no free slot (inventory full): free up space, or set up a trash rule. */
    private static void notifyNoRoom(Minecraft client, String itemId, long runId) {
        LOGGER.warn("[InvSort] #{} no room for {} — notifying player", runId, itemId);
        if (client.gui == null) return;
        net.minecraft.network.chat.Component name;
        try {
            net.minecraft.world.item.Item item = net.minecraft.core.registries.BuiltInRegistries.ITEM
                    .getValue(net.minecraft.resources.Identifier.parse(itemId));
            name = new ItemStack(net.minecraft.core.Holder.direct(item)).getHoverName();
        } catch (Throwable t) {
            name = net.minecraft.network.chat.Component.literal(itemId);
        }
        client.gui.setOverlayMessage(
                net.minecraft.network.chat.Component.translatable("inventory-organizer.sort.no_room", name), false);
    }

    /** Tell the player the sort couldn't finish (after all retries) and suggest a manual nudge. */
    private static void notifySortProblem(Minecraft client, long runId) {
        LOGGER.warn("[InvSort] #{} gave up — notifying player", runId);
        if (client.gui != null) {
            client.gui.setOverlayMessage(
                    net.minecraft.network.chat.Component.translatable("inventory-organizer.sort.retry_failed"), false);
        }
    }

    /**
     * Lay out the remaining {@code pool} into the FREE ANY slots of the internal inventory (9-35), grouped
     * so members of the same category sit together. Hotbar (0-8) is never touched. When all of 9-35 is
     * free it uses a row-aligned layout (each group starts on a new row); if that needs more than 3 rows
     * (too full) it falls back to dense packing (word-wrap). Consumes the placed items from {@code pool}.
     */
    private static void groupRowFill(ItemStack[] desired, OrganizerConfig config, List<ItemStack> pool, long runId) {
        List<Integer> free = new ArrayList<>();
        for (int s = 9; s <= 35; s++) {
            if (!desired[s].isEmpty()) continue;
            if (config.getSlotRule(s).getType() == SlotRule.Type.ANY) free.add(s);
        }
        if (pool.isEmpty()) { unplacedItemId = null; return; }
        if (free.isEmpty()) { unplacedItemId = getItemId(pool.get(0)); return; } // nowhere to put the pool

        // The pool is already category-contiguous; use it directly as the grouped item order.
        List<ItemStack> items = new ArrayList<>(pool);
        boolean rowAligned = (free.size() == 27); // all of 9-35 free → row layout is meaningful
        if (rowAligned) {
            int rows = 0, i = 0;
            while (i < items.size()) {
                int cat = poolCategoryOf(getCategory(items.get(i)));
                int run = 0;
                while (i < items.size() && poolCategoryOf(getCategory(items.get(i))) == cat) { run++; i++; }
                rows += (run + 8) / 9;
            }
            if (rows > 3) rowAligned = false; // too full to give each group its own row → dense pack
        }

        int placed = 0;
        if (rowAligned) {
            int pos = 0, idx = 0; // pos = 0..26 index within 9-35
            while (idx < items.size() && pos < 27) {
                int cat = poolCategoryOf(getCategory(items.get(idx)));
                if (pos % 9 != 0) pos = ((pos / 9) + 1) * 9;       // start each group on a fresh row
                while (idx < items.size() && pos < 27
                        && poolCategoryOf(getCategory(items.get(idx))) == cat) {
                    desired[9 + pos] = items.get(idx); idx++; pos++; placed++;
                }
            }
        } else {
            int idx = 0;
            for (int slot : free) {
                if (idx >= items.size()) break;
                desired[slot] = items.get(idx++); placed++;
            }
        }
        for (int k = 0; k < placed; k++) pool.remove(0); // consume the items we placed (front of pool)
        // Anything still in the pool had no free slot to land in → remember the first for the warning.
        unplacedItemId = pool.isEmpty() ? null : getItemId(pool.get(0));
        LOGGER.info("[InvSort] #{} group-fill: {} mode, placed {} of {} free{}", runId,
                rowAligned ? "row-aligned" : "dense", placed, free.size(),
                unplacedItemId != null ? (" | NO ROOM for " + unplacedItemId) : "");
    }

    /**
     * Merge partial stacks of the same item+components in the player inventory (slots 0-35) so OI
     * actually combines them instead of just reordering. Each merge = pick up the later stack and drop
     * it onto an earlier non-full stack (the server merges up to max); any leftover goes back.
     */
    static void consolidatePartialStacks(Minecraft client, int syncId, Inventory inv) {
        for (int guard = 0; guard < 256; guard++) {
            int dest = -1, src = -1;
            outer:
            for (int i = 0; i < 36; i++) {
                ItemStack a = inv.getItem(i);
                if (a.isEmpty() || a.getCount() >= a.getMaxStackSize()) continue; // already full
                for (int j = i + 1; j < 36; j++) {
                    ItemStack b = inv.getItem(j);
                    if (!b.isEmpty() && ItemStack.isSameItemSameComponents(a, b)) { dest = i; src = j; break outer; }
                }
            }
            if (dest < 0) break; // nothing left to merge
            int from = playerToScreenSlot(src);
            int to = playerToScreenSlot(dest);
            client.gameMode.handleContainerInput(syncId, from, 0, ContainerInput.PICKUP, client.player); // cursor = src
            client.gameMode.handleContainerInput(syncId, to, 0, ContainerInput.PICKUP, client.player);   // merge into dest (up to max)
            if (!client.player.inventoryMenu.getCarried().isEmpty()) {
                client.gameMode.handleContainerInput(syncId, from, 0, ContainerInput.PICKUP, client.player); // leftover back to src
            }
        }
    }

    static void doSwap(Minecraft client, int syncId, int fromPlayerSlot, int toPlayerSlot) {
        if (fightModeLimit) {
            if (fightModeSwapsDone >= 1) return; // one item per OI press in fight mode
            fightModeSwapsDone++;
        }
        int fromScreen = playerToScreenSlot(fromPlayerSlot);
        int toScreen = playerToScreenSlot(toPlayerSlot);

        // Pick up item from source (left click)
        client.gameMode.handleContainerInput(syncId, fromScreen, 0, ContainerInput.PICKUP, client.player);
        // Place at destination (left click)
        client.gameMode.handleContainerInput(syncId, toScreen, 0, ContainerInput.PICKUP, client.player);
        // If there was an item at destination, it's now on cursor - put it back at source
        if (!client.player.inventoryMenu.getCarried().isEmpty()) {
            client.gameMode.handleContainerInput(syncId, fromScreen, 0, ContainerInput.PICKUP, client.player);
        }
    }


    /**
     * After the main sort, fills bundles with items matching their designated content rule.
     * Sequence: pick up bundle → left-click each matching item slot → place bundle back.
     * The server handles actual insertion via BundleItem.onStackClicked().
     */
    private static void fillBundles(Minecraft client, int syncId, OrganizerConfig config, ItemStack[] current) {
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
            BundleContents contents = bundleStack.get(DataComponents.BUNDLE_CONTENTS);
            if (contents == null) contents = BundleContents.EMPTY;
            // BundleContents.weight() in 26.1 returns DataResult<Fraction> — use .result().orElse(Fraction.ZERO)
            Fraction currentWeight = contents.weight().result().orElse(Fraction.ZERO);
            Fraction remaining = Fraction.ONE.subtract(currentWeight);

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
                Fraction itemWeight = Fraction.getFraction(current[i].getCount(), current[i].getMaxStackSize());
                if (remaining.compareTo(itemWeight) < 0) continue;
                toBundle.add(i);
                remaining = remaining.subtract(itemWeight);
            }

            if (toBundle.isEmpty()) continue;
            LOGGER.debug("[InvOrganizer] fillBundles: bundle@" + bundleSlot + " receives " + toBundle.size() + " stacks");

            // pick up item → left-click bundle slot → item goes into bundle
            for (int itemSlot : toBundle) {
                client.gameMode.handleContainerInput(syncId, playerToScreenSlot(itemSlot), 0, ContainerInput.PICKUP, client.player);
                client.gameMode.handleContainerInput(syncId, playerToScreenSlot(bundleSlot), 0, ContainerInput.PICKUP, client.player);
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
        if (contentRule.startsWith("cg:")) {
            // Custom / materialized built-in group: match against the group's actual member list (the same
            // resolution refill uses). Without this, a cg: bundle rule (e.g. cg:plants) matched nothing.
            java.util.List<String> members = OrganizerConfig.get().getCustomGroup(contentRule.substring(3));
            if (members.isEmpty()) return false;
            String id = getItemId(stack);
            String path = id.contains(":") ? id.substring(id.indexOf(':') + 1) : id;
            return members.contains(id) || members.contains(path);
        }
        if (contentRule.startsWith("g:")) {
            int cat = groupNameToCategory(contentRule.substring(2));
            return categoryMatches(stack, cat);
        }
        if (contentRule.startsWith("t:")) {
            return matchesItemType(getItemId(stack), contentRule.substring(2));
        }
        if (contentRule.startsWith("pot:")) {
            return matchesPotionEffect(stack, contentRule.substring(4));
        }
        return matchesSpecificItem(getItemId(stack), contentRule);
    }

    /** The base potion effect of a potion/splash/lingering/tipped-arrow stack (long_/strong_ stripped), or null. */
    static String basePotionEffect(ItemStack stack) {
        String id = getPotionTypeId(stack); // e.g. "long_swiftness" / "swiftness" / ""
        if (id == null || id.isEmpty()) return null;
        if (id.startsWith("long_")) id = id.substring(5);
        else if (id.startsWith("strong_")) id = id.substring(7);
        return id;
    }

    /** Match a stack against a specific potion effect (all variants of that effect match). */
    static boolean matchesPotionEffect(ItemStack stack, String effect) {
        String base = basePotionEffect(stack);
        return base != null && base.equals(effect);
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
            PotionContents comp = stack.get(DataComponents.POTION_CONTENTS);
            if (comp == null || comp.potion().isEmpty()) return "";
            return comp.potion().get().unwrapKey()
                    .map(k -> k.identifier().getPath())
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

    /** All built-in group names that appear in the Groups menu and can be customized. */
    public static final String[] BUILTIN_GROUP_NAMES = {
        "weapons", "tools", "armor", "blocks", "food", "utility", "valuables",
        "potions", "splash_potions", "arrows", "logs", "boats", "plants",
        "stone", "ores", "cooked", "rawfood", "nether", "end", "partial",
        "redstone", "creative"
    };

    /**
     * When true, the materialized built-in groups are HIDDEN from the Groups list and the Tier Order
     * (ranks) screen — only the player's own hand-made custom groups are shown there. The groups still
     * exist in config and still work in slot rules / sorting (so nothing breaks); this only declutters
     * the UI. Flip to {@code false} to bring the built-in groups back into those screens.
     */
    public static final boolean HIDE_BUILTIN_GROUPS_IN_UI = true;

    /** True if {@code name} is one of the 22 materialized built-in group names OR a fixed switch tool group. */
    public static boolean isBuiltinGroup(String name) {
        if (name == null) return false;
        for (String b : BUILTIN_GROUP_NAMES) if (b.equals(name)) return true;
        for (String s : com.example.inventoryorganizer.config.OrganizerConfig.SWITCH_CATEGORIES)
            if (com.example.inventoryorganizer.config.OrganizerConfig.switchGroupName(s).equals(name)) return true;
        return false;
    }

    /** True if this group should be hidden from the Groups list / ranks UI (built-in + hide flag on). */
    public static boolean isGroupHiddenInUi(String name) {
        return HIDE_BUILTIN_GROUPS_IN_UI && isBuiltinGroup(name);
    }

    /** Display label for a built-in group name (used in the Groups menu). */
    public static String builtinGroupLabel(String name) {
        switch (name) {
            case "splash_potions": return "Splash Potions";
            case "rawfood": return "Raw Food";
            case "cooked": return "Cooked Food";
            case "partial": return "Partial Blocks";
            default:
                if (name.isEmpty()) return name;
                return Character.toUpperCase(name.charAt(0)) + name.substring(1);
        }
    }

    /**
     * Generates the default member item IDs for a built-in group by scanning the item
     * registry and applying the heuristic category match. Used to pre-fill the editor
     * when the user first customizes a group. Cached per group.
     */
    private static final java.util.Map<String, List<String>> DEFAULT_GROUP_CACHE = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Are the data-driven item tags loaded yet? At client init (before joining a world) the tag
     * bindings are empty, so tag-based group generation (arrows/logs/boats/tools/weapons/armor) would
     * yield EMPTY lists. We probe with oak_log ∈ #minecraft:logs — true only once tags are bound.
     * The materialization + the default-group cache both gate on this so the empty lists never stick.
     */
    public static boolean itemTagsLoaded() {
        try {
            return new ItemStack(net.minecraft.world.item.Items.OAK_LOG).is(net.minecraft.tags.ItemTags.LOGS);
        } catch (Throwable t) {
            return false;
        }
    }

    public static List<String> getDefaultItemsForGroup(String groupName) {
        List<String> cached = DEFAULT_GROUP_CACHE.get(groupName);
        if (cached != null) return new ArrayList<>(cached);
        List<String> result = new ArrayList<>();
        int targetCat = groupNameToCategory(groupName);
        // For the broad groups we use the game's OWN data (block mapping, tags, food component) — the
        // authoritative ground truth, far more complete than the substring heuristic. The heuristic
        // remains the fallback for niche/sub-groups. Generation only — the live sorter is untouched.
        try {
            for (net.minecraft.resources.Identifier id : net.minecraft.core.registries.BuiltInRegistries.ITEM.keySet()) {
                String sid = id.toString();
                if (sid.equals("minecraft:air")) continue;
                net.minecraft.world.item.Item item = net.minecraft.core.registries.BuiltInRegistries.ITEM.getValue(id);
                try {
                    ItemStack stack = new ItemStack(net.minecraft.core.Holder.direct(item));
                    boolean add;
                    switch (groupName) {
                        // Only full 1×1×1 solid blocks the player can't walk through (stone, sand, wood,
                        // glass, ores …). Excludes slabs/stairs/fences (partial), plants/saplings/crops,
                        // torches/carpets/etc. (no full collision), and edible blocks. Authoritative via
                        // the block's collision shape rather than substring guessing.
                        case "blocks":  add = isFullSolidBlock(item)
                                            && !stack.has(net.minecraft.core.component.DataComponents.FOOD); break;
                        case "food":    add = stack.has(net.minecraft.core.component.DataComponents.FOOD)
                                            || isAnyId(sid, FOOD_IDS); break;
                        // Tag matching PLUS an item-id fallback for every tag-based group. In 26.1 the
                        // data-driven item tags are unreliable at generation time (some bind, some don't —
                        // arrows/logs/boats/armor/mining-tools came out empty while swords/axes worked), which
                        // left these groups blank. The id suffix check guarantees they're populated regardless,
                        // and the tag union still catches modded items that follow the tag conventions.
                        case "arrows":  add = stack.is(net.minecraft.tags.ItemTags.ARROWS)
                                            || sid.endsWith("arrow"); break;
                        case "logs":    add = stack.is(net.minecraft.tags.ItemTags.LOGS)
                                            || sid.endsWith("_log") || sid.endsWith("_wood")
                                            || sid.endsWith("_stem") || sid.endsWith("_hyphae"); break;
                        case "boats":   add = stack.is(net.minecraft.tags.ItemTags.BOATS)
                                            || stack.is(net.minecraft.tags.ItemTags.CHEST_BOATS)
                                            || sid.contains("boat") || sid.endsWith("raft"); break;
                        // Anything held and right-click "used" on something: mining tools + a broad set of
                        // usable utility items (ender pearl, buckets, spyglass, lead, name tag, books …).
                        case "tools":   add = stack.is(net.minecraft.tags.ItemTags.PICKAXES)
                                            || stack.is(net.minecraft.tags.ItemTags.AXES)
                                            || stack.is(net.minecraft.tags.ItemTags.SHOVELS)
                                            || stack.is(net.minecraft.tags.ItemTags.HOES)
                                            || sid.endsWith("pickaxe") || sid.endsWith("shovel")
                                            || sid.endsWith("_hoe") || (sid.endsWith("axe") && !sid.endsWith("pickaxe"))
                                            || isAnyId(sid, TOOL_EXTRA_IDS); break;
                        // Serious-damage weapons: swords + axes (high melee) + bow/crossbow/trident/mace.
                        case "weapons": add = stack.is(net.minecraft.tags.ItemTags.SWORDS)
                                            || stack.is(net.minecraft.tags.ItemTags.AXES)
                                            || sid.endsWith("sword") || (sid.endsWith("axe") && !sid.endsWith("pickaxe"))
                                            || isAnyId(sid, "bow", "crossbow", "trident", "mace"); break;
                        case "armor":   add = stack.is(net.minecraft.tags.ItemTags.HEAD_ARMOR)
                                            || stack.is(net.minecraft.tags.ItemTags.CHEST_ARMOR)
                                            || stack.is(net.minecraft.tags.ItemTags.LEG_ARMOR)
                                            || stack.is(net.minecraft.tags.ItemTags.FOOT_ARMOR)
                                            || sid.endsWith("helmet") || sid.endsWith("chestplate")
                                            || sid.endsWith("leggings") || sid.endsWith("boots")
                                            || isAnyId(sid, "elytra", "shield", "turtle_helmet"); break;
                        default:        add = categoryMatches(stack, targetCat); break;
                    }
                    if (add) result.add(sid);
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
        result.sort(String::compareToIgnoreCase);
        // Only cache once tags are loaded — otherwise an early (tags-empty) call would freeze an empty
        // arrows/logs/boats list in the cache and a later, correct regeneration would never happen.
        if (itemTagsLoaded()) DEFAULT_GROUP_CACHE.put(groupName, result);
        return new ArrayList<>(result);
    }

    /**
     * Usable utility items that belong in the "tools" group beyond the mining-tool tags. Kept in sync
     * with {@link SortLogic#TOOL_EXTRA_IDS} (server-side warehouse matching) — change both together.
     */
    static final String[] TOOL_EXTRA_IDS = {
        "shears", "flint_and_steel", "fishing_rod", "brush",
        "ender_pearl", "ender_eye", "fire_charge", "snowball", "egg", "experience_bottle",
        "spyglass", "goat_horn", "lead", "name_tag", "saddle", "carrot_on_a_stick", "warped_fungus_on_a_stick",
        "bundle", "compass", "recovery_compass", "clock", "map", "filled_map",
        "bucket", "water_bucket", "lava_bucket", "milk_bucket", "powder_snow_bucket",
        "cod_bucket", "salmon_bucket", "pufferfish_bucket", "tropical_fish_bucket", "axolotl_bucket", "tadpole_bucket",
        "book", "writable_book", "written_book", "knowledge_book",
        "firework_rocket", "totem_of_undying", "honeycomb", "ink_sac", "glow_ink_sac", "armor_stand"
    };

    /**
     * Every vanilla edible item. Used as an id fallback for the "food" group because the FOOD data
     * component proved unreliable in 26.1 (the 1.21.2 consumable refactor) — generating from it alone
     * left the group EMPTY. Kept in sync with {@link SortLogic#FOOD_IDS} — change both together.
     */
    static final String[] FOOD_IDS = {
        "apple", "golden_apple", "enchanted_golden_apple", "golden_carrot", "carrot", "potato",
        "baked_potato", "poisonous_potato", "beetroot", "beetroot_soup", "bread", "cookie",
        "melon_slice", "dried_kelp", "sweet_berries", "glow_berries", "honey_bottle", "chorus_fruit",
        "pumpkin_pie", "mushroom_stew", "rabbit_stew", "suspicious_stew",
        "beef", "cooked_beef", "porkchop", "cooked_porkchop", "mutton", "cooked_mutton",
        "chicken", "cooked_chicken", "rabbit", "cooked_rabbit", "cod", "cooked_cod",
        "salmon", "cooked_salmon", "tropical_fish", "pufferfish", "rotten_flesh", "spider_eye"
    };

    /** True when the full item id ({@code minecraft:x}) ends in any of the given vanilla paths. */
    private static boolean isAnyId(String sid, String... paths) {
        String path = sid.contains(":") ? sid.substring(sid.indexOf(':') + 1) : sid;
        for (String p : paths) if (path.equals(p)) return true;
        return false;
    }

    /** True when this item places an actual block in the world (mapping-stable; no instanceof). */
    private static boolean isPlaceableBlock(net.minecraft.world.item.Item item) {
        try {
            return net.minecraft.world.level.block.Block.byItem(item) != net.minecraft.world.level.block.Blocks.AIR;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * True when this item places a FULL 1×1×1 solid block (collision-shape full cube) — i.e. something
     * the player cannot walk through (stone, sand, wood, glass, ores, full nether/end blocks …). False
     * for slabs/stairs/fences, plants, torches, carpets and other non-full placeables. Uses the block's
     * own collision shape (authoritative) via an empty block-getter, so no world context is needed.
     */
    private static boolean isFullSolidBlock(net.minecraft.world.item.Item item) {
        try {
            net.minecraft.world.level.block.Block b = net.minecraft.world.level.block.Block.byItem(item);
            if (b == net.minecraft.world.level.block.Blocks.AIR) return false;
            return b.defaultBlockState().isCollisionShapeFullBlock(
                net.minecraft.world.level.EmptyBlockGetter.INSTANCE, net.minecraft.core.BlockPos.ZERO);
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Whether an item matches a built-in group, honouring user overrides.
     * If the group has an explicit override list, use it; otherwise the heuristic.
     */
    static boolean matchesBuiltinGroup(ItemStack stack, String groupName) {
        List<String> override = OrganizerConfig.get().getBuiltinGroupItems(groupName);
        if (override != null) {
            return override.contains(getItemId(stack));
        }
        return categoryMatches(stack, groupNameToCategory(groupName));
    }

    /**
     * The ordered member item-IDs of a group NAME if it exists as a (materialized or hand-made)
     * custom group, honouring the ranks-screen order ({@code cg_order_<name>}); otherwise null so the
     * caller falls back to the heuristic. Lets {@code g:NAME} and {@code cg:NAME} resolve identically.
     */
    static List<String> groupMembersOrdered(OrganizerConfig config, String name) {
        if (!config.getCustomGroups().containsKey(name)) return null;
        List<String> members = config.getCustomGroup(name);
        String[] order = config.getPreference("cg_order_" + name);
        if (order == null || order.length == 0) return members;
        // Merge the saved rank order with the CURRENT membership: keep the ranked order for members that
        // still exist, then append any members missing from the saved order (e.g. items the user just
        // added to the group). Stale order entries (items removed from the group) are dropped. This way
        // edits to a group take effect immediately in sorting — no need to re-open/re-save the ranks screen.
        java.util.Set<String> memberSet = new java.util.HashSet<>(members);
        java.util.LinkedHashSet<String> result = new java.util.LinkedHashSet<>();
        for (String o : order) if (memberSet.contains(o)) result.add(o);
        result.addAll(members);
        return new ArrayList<>(result);
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
                return CAT_BLOCK;
            // CAT_PARTIAL (slabs/stairs/walls/fences/doors/trapdoors/panes) and CAT_PLANT (poppies/sugar_cane/etc.)
            // are NOT full 1×1×1 solid blocks → do NOT match g:blocks slots.
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
        Identifier id = BuiltInRegistries.ITEM.getKey(stack.getItem());
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

        // --- Explicit ITEM overrides (must run before the broad substring heuristics below) ---
        // End-dimension mob-drop / loot ITEMS that no later heuristic catches → pin to END so they land
        // in the "end" group with the rest of the End's items. (The nether brewing/mob items — blaze rod,
        // ghast tear, magma cream, glowstone dust, nether star, nether wart — are intentionally NOT here:
        // the nether substring heuristic below already maps them to CAT_NETHER.) Mirrors SortLogic.
        switch (id) {
            case "minecraft:dragon_breath":
            case "minecraft:shulker_shell":
            case "minecraft:end_crystal":
            case "minecraft:dragon_head":
            case "minecraft:chorus_fruit":
                return CAT_END;
            default:
                break;
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
                id.contains("redstone_block")) {
            return CAT_REDSTONE;
        }
        // Trapped chest is a storage block (groups with the blocks/chests), not redstone.

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
                (id.contains("chorus_") && !id.equals("minecraft:chorus_fruit")) ||
                id.equals("minecraft:dragon_egg")) {
            return CAT_END;
        }

        // Weapons
        if (id.contains("sword") && !id.contains("_block") && !id.contains("_ore") ||
                id.contains("bow") && !id.contains("cross") && !id.contains("bowl") ||
                id.contains("crossbow") || id.contains("trident") || id.contains("mace") ||
                id.contains("spear")) {  // 26.1 added spears (wooden/stone/iron/golden/copper/diamond/netherite)
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
                id.contains("barrel") || id.contains("chest") ||
                id.contains("crafting_table") || id.contains("furnace") ||
                id.contains("smoker") || id.contains("blast_furnace") ||
                id.contains("smithing_table") || id.contains("fletching_table") ||
                id.contains("cartography_table") || id.contains("loom") ||
                id.contains("bookshelf") || id.contains("beacon") ||
                id.contains("dispenser") || id.contains("dropper") ||
                id.contains("observer") || id.contains("piston") ||
                id.contains("tnt") || id.contains("target") ||
                id.contains("daylight_detector") || id.contains("note_block") ||
                id.contains("jukebox") || id.contains("redstone_lamp") ||
                id.contains("bamboo_mosaic") ||
                id.contains("mangrove_roots") || id.contains("cherry_log") ||
                id.contains("leaves") || id.contains("mushroom_block") ||
                id.contains("mycelium") || id.contains("podzol") ||
                id.contains("farmland") || id.contains("grass_block") ||
                id.contains("shulker_box") ||
                id.contains("lodestone") || id.contains("respawn_anchor") ||
                id.contains("bedrock")) {
            return CAT_BLOCK;
        }
        // Partial / decoration / utility blocks — NOT full 1×1×1 cubes, so NOT CAT_BLOCK.
        // These go to CAT_UTILITY so they don't pollute g:blocks slots.
        if (id.contains("lantern") || id.contains("campfire") ||
                id.contains("candle") || id.contains("chain") ||
                id.contains("bell") || id.contains("lightning_rod") || id.contains("end_rod") ||
                id.contains("anvil") || id.contains("cauldron") ||
                id.contains("grindstone") || id.contains("stonecutter") ||
                id.contains("composter") || id.contains("brewing_stand") ||
                id.contains("enchanting_table") || id.contains("lectern") ||
                id.contains("conduit") || id.contains("hopper") ||
                id.contains("rail") || id.contains("repeater") ||
                id.contains("comparator") ||
                id.contains("_bed") || id.contains("banner") || id.contains("carpet") ||
                id.contains("_sign") || id.contains("_head") || id.contains("skull") ||
                id.contains("sapling") || id.contains("azalea") ||
                id.contains("dripleaf") || id.contains("cobweb") ||
                id.contains("ladder") || id.contains("scaffolding") ||
                id.contains("_trapdoor") || id.contains("pressure_plate") ||
                id.contains("lever") || id.contains("tripwire_hook") || id.contains("button")) {
            return CAT_UTILITY;
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
            if (id.contains("spear")) return 14 + materialTier(id); // after mace, ordered by material
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
            ItemEnchantments enchantments = EnchantmentHelper.getEnchantmentsForCrafting(stack);
            return enchantments.size();
        } catch (Exception e) {
            return 0;
        }
    }

    // Get best (lowest tier index) enchantment on item based on user-configured enchant order
    // Returns a score: tier * 1000 - level (so lower tier + higher level = lower score = better)
    private static int getBestEnchantTier(ItemStack stack, String[] enchOrder) {
        try {
            ItemEnchantments enchantments = EnchantmentHelper.getEnchantmentsForCrafting(stack);
            if (enchantments.isEmpty()) return 999999;

            int bestScore = 999999;
            String bestEnchant = "none";
            int bestLevel = 0;
            LOGGER.debug("[InvOrganizer] DEBUG: Checking enchants on " + getItemId(stack));
            for (var entry : enchantments.entrySet()) {
                var holder = entry.getKey();
                int level = entry.getIntValue();
                String enchId = holder.unwrapKey().map(k -> k.identifier().toString()).orElse("unknown");
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
        return stack.getMaxDamage() - stack.getDamageValue();
    }

    // Material tier based on config preferences
    private static int materialTier(String id) {
        // Determine item type to look up the right preference
        String type = null;
        if (id.contains("sword")) type = "sword";
        else if (id.contains("spear")) type = "sword"; // reuse sword material order for spears
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
        if (id.contains("copper")) return 3;  // 26.1 copper tools/weapons (between stone and iron in power)
        if (id.contains("gold") || id.contains("golden")) return 4;
        if (id.contains("stone")) return 5;
        if (id.contains("wood") || id.contains("leather")) return 6;
        if (id.contains("chain")) return 7;
        return 8;
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
