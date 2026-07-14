package com.example.inventoryorganizer;

import com.example.inventoryorganizer.config.OrganizerConfig;
import com.example.inventoryorganizer.config.StoragePreset;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.inventory.ContainerInput;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class StorageSorter {

    private static final Logger LOGGER = LoggerFactory.getLogger("inventory-organizer/OST");

    // Fight-mode rate limiting (mirrors InventorySorter): during PvP the OST does at most ONE swap
    // per press, shares OI's cooldown, and skips bulk consolidation — so it can't be used as a
    // fast macro in combat. Enforced centrally in sortContainer() so the OST button AND the keybind
    // (both call sortContainer) are limited identically.
    private static boolean fightModeLimit = false;
    private static int fightModeSwapsDone = 0;

    /**
     * Sort the currently open container.
     *
     * <p>Uses the per-chest profile that {@link ChestIdentifier} resolved for the open chest, if
     * any; otherwise falls back to the size-based default profile (Container 27 / Large Chest 54),
     * which is what every chest used before per-chest profiles existed.
     *
     * @param containerSize  number of slots in the container (27 or 54, etc.)
     * @param syncId         the screen handler sync ID
     */
    public static void sortContainer(int containerSize, int syncId) {
        OrganizerConfig config = OrganizerConfig.get();
        StoragePreset profile = ChestIdentifier.getActiveProfile();
        if (profile == null) {
            // No per-chest profile bound → use the protected default for this size.
            profile = config.getDefaultForSize(containerSize);
        }
        if (profile == null) {
            LOGGER.warn("No storage profile or default for container size {}", containerSize);
            return;
        }
        // During PvP, rate-limit OST exactly like OI: shared cooldown + a single swap per press.
        if (FightModeTracker.isActive()) {
            if (!FightModeTracker.canUseOI()) return;
            FightModeTracker.markOIUsed();
            fightModeLimit = true;
            fightModeSwapsDone = 0;
            try {
                sortContainerWithPreset(containerSize, syncId, profile, profile.getId());
            } finally {
                fightModeLimit = false;
            }
            return;
        }
        // Tier data is keyed by stable profile id (defaults keep ids 0/1/2 for backward compat).
        sortContainerWithPreset(containerSize, syncId, profile, profile.getId());
    }

    /**
     * Applies a StoragePreset's rules and tier assignments to sort the container slots.
     * Uses the same matching logic as InventorySorter (via package-private helpers).
     */
    public static void sortContainerWithPreset(int containerSize, int syncId, StoragePreset preset, int presetIdx) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.gameMode == null) return;

        // Step 0: Merge same-item partial stacks into full stacks first, so the sort works with
        // consolidated stacks instead of leaving several half-stacks of the same item lying around.
        consolidateStacks(client, syncId, containerSize);

        // Step 1: Collect items from container slots (indices 0 .. containerSize-1), now consolidated.
        ItemStack[] containerItems = readContainer(client, containerSize);

        // Step 2: Collect all non-empty items into a pool
        List<ItemStack> pool = new ArrayList<>();
        for (ItemStack s : containerItems) {
            if (!s.isEmpty()) pool.add(s.copy());
        }

        if (pool.isEmpty()) {
            LOGGER.debug("Container is empty.");
            return;
        }

        // Step 3: Sort pool by category (same logic as InventorySorter)
        OrganizerConfig config = OrganizerConfig.get();
        String[] matOrderArr = config.getPreference("sort_material_order");
        if (matOrderArr == null || matOrderArr.length == 0) {
            matOrderArr = new String[]{"netherite", "diamond", "iron", "gold", "stone", "wood", "leather", "chain"};
        }
        final String[] finalMatOrder = matOrderArr;

        Comparator<ItemStack> sortComp = (a, b) -> {
            int catA = InventorySorter.poolCategoryOf(InventorySorter.getCategory(a));
            int catB = InventorySorter.poolCategoryOf(InventorySorter.getCategory(b));
            if (catA != catB) return Integer.compare(catA, catB);
            // Within same category, sort by material tier
            int mA = materialTier(InventorySorter.getItemId(a), finalMatOrder);
            int mB = materialTier(InventorySorter.getItemId(b), finalMatOrder);
            if (mA != mB) return Integer.compare(mA, mB);
            return InventorySorter.getItemId(a).compareTo(InventorySorter.getItemId(b));
        };
        pool.sort(sortComp);

        // Step 4: Build desired layout from preset rules + tier
        ItemStack[] desired = new ItemStack[containerSize];
        for (int i = 0; i < containerSize; i++) desired[i] = ItemStack.EMPTY;

        // Build slot order: tiered slots first (sorted by tier asc), then the rest
        OrganizerConfig cfg = OrganizerConfig.get();
        List<Integer> tieredSlots = new ArrayList<>();
        List<Integer> untiredSlots = new ArrayList<>();
        for (int i = 0; i < containerSize; i++) {
            if (cfg.getStorageTier(presetIdx, i) != null) tieredSlots.add(i);
            else untiredSlots.add(i);
        }
        tieredSlots.sort(Comparator.comparingInt(s -> {
            Integer t = cfg.getStorageTier(presetIdx, s);
            return t != null ? t : 999;
        }));

        List<Integer> slotOrder = new ArrayList<>();
        slotOrder.addAll(tieredSlots);
        slotOrder.addAll(untiredSlots);

        // Pass 0: exact item matches
        for (int s : slotOrder) {
            String rule = preset.getSlotRule(s);
            if (!rule.contains(":") && !rule.startsWith("g:") && !rule.startsWith("t:")
                    && !rule.equals("any") && !rule.equals("empty")) continue;
            if (rule.contains(":")) { // specific item (minecraft:xxx) or specific potion (pot:effect)
                boolean isPot = rule.startsWith("pot:");
                String potEffect = isPot ? rule.substring(4) : null;
                for (int j = 0; j < pool.size(); j++) {
                    boolean m = isPot
                            ? InventorySorter.matchesPotionEffect(pool.get(j), potEffect)
                            : InventorySorter.matchesSpecificItem(InventorySorter.getItemId(pool.get(j)), rule);
                    if (m) {
                        desired[s] = pool.remove(j);
                        break;
                    }
                }
            }
        }

        // Pass 1: type matches (t:sword, t:pickaxe etc.)
        for (int s : slotOrder) {
            if (!desired[s].isEmpty()) continue;
            String rule = preset.getSlotRule(s);
            if (!rule.startsWith("t:")) continue;
            String type = rule.substring(2);
            for (int j = 0; j < pool.size(); j++) {
                if (InventorySorter.matchesItemType(InventorySorter.getItemId(pool.get(j)), type)) {
                    desired[s] = pool.remove(j);
                    break;
                }
            }
        }

        // Pass 2: group matches — both heuristic groups (g:weapons, g:food …) and materialized
        // built-in / custom groups (cg:blocks …). ruleMatchesItem resolves cg: against the actual
        // group membership and g: through the authoritative SortLogic matcher, so a cg:blocks slot
        // pulls real blocks instead of falling through to the "any" pass.
        for (int s : slotOrder) {
            if (!desired[s].isEmpty()) continue;
            String rule = preset.getSlotRule(s);
            if (!rule.startsWith("g:") && !rule.startsWith("cg:")) continue;
            for (int j = 0; j < pool.size(); j++) {
                if (InventoryOrganizerClient.ruleMatchesItem(rule, pool.get(j))) {
                    desired[s] = pool.remove(j);
                    break;
                }
            }
        }

        // Pass 3: fill 'any' slots with remaining items
        for (int s : slotOrder) {
            if (pool.isEmpty()) break;
            if (!desired[s].isEmpty()) continue;
            String rule = preset.getSlotRule(s);
            if (rule.equals("empty")) continue;
            desired[s] = pool.remove(0);
        }

        LOGGER.debug("Sorting container size={} preset='{}' pool remaining={}", containerSize, preset.getName(), pool.size());

        // Step 5: Execute the layout the same robust way the OI inventory sort does — re-read the
        // real container state after EVERY swap (ground truth) and clear the cursor if a same-item
        // merge leaves leftovers. The old version tracked state manually and required exact stack
        // counts, so partial / merging stacks (e.g. several bread x7) were left unplaced.
        ItemStack[] current = readContainer(client, containerSize);

        for (int targetSlot = 0; targetSlot < containerSize; targetSlot++) {
            ItemStack want = desired[targetSlot];
            if (want.isEmpty()) continue;

            ItemStack have = current[targetSlot];
            if (!have.isEmpty() && ItemStack.isSameItemSameComponents(have, want)
                    && have.getCount() == want.getCount()) continue; // already correct

            // Find the wanted stack elsewhere: prefer an exact (item+components+count) match, then
            // fall back to item+components ignoring count (covers partial / already-merged stacks).
            int sourceSlot = findSource(current, desired, containerSize, targetSlot, want, true);
            if (sourceSlot == -1) sourceSlot = findSource(current, desired, containerSize, targetSlot, want, false);
            if (sourceSlot == -1) continue;

            // In PvP, stop after a single swap per press (shared OI cooldown gates the rest).
            if (fightModeLimit && fightModeSwapsDone >= 1) break;
            doContainerSwap(client, syncId, sourceSlot, targetSlot);
            fightModeSwapsDone++;
            current = readContainer(client, containerSize); // ground truth after the swap

            // A same-item merge can leave items on the cursor — drop them into any empty slot.
            if (!client.player.containerMenu.getCarried().isEmpty()) {
                for (int i = 0; i < containerSize; i++) {
                    if (current[i].isEmpty()) {
                        client.gameMode.handleContainerInput(syncId, i, 0, ContainerInput.PICKUP, client.player);
                        current = readContainer(client, containerSize);
                        break;
                    }
                }
            }
        }

        LOGGER.debug("Sort complete.");
    }

    /**
     * Merge partial stacks of the same item within the container into full stacks (up to the item's
     * max stack size), so e.g. several bread x7 become one bread x35. For each not-yet-full stack,
     * pour later same-item stacks into it: pick up the later stack, left-click the earlier one (vanilla
     * fills it up to max, leftover stays on the cursor), then drop any leftover back. Ground truth is
     * re-read after each merge.
     */
    private static void consolidateStacks(Minecraft client, int syncId, int size) {
        if (fightModeLimit) return; // no bulk multi-click consolidation during PvP
        ItemStack[] current = readContainer(client, size);
        for (int i = 0; i < size; i++) {
            if (current[i].isEmpty() || current[i].getCount() >= current[i].getMaxStackSize()) continue;
            for (int j = i + 1; j < size; j++) {
                if (current[i].getCount() >= current[i].getMaxStackSize()) break; // i is full now
                if (current[j].isEmpty()) continue;
                if (!ItemStack.isSameItemSameComponents(current[i], current[j])) continue;
                client.gameMode.handleContainerInput(syncId, j, 0, ContainerInput.PICKUP, client.player); // cursor = j
                client.gameMode.handleContainerInput(syncId, i, 0, ContainerInput.PICKUP, client.player); // pour into i
                if (!client.player.containerMenu.getCarried().isEmpty()) {
                    client.gameMode.handleContainerInput(syncId, j, 0, ContainerInput.PICKUP, client.player); // leftover back to j
                }
                current = readContainer(client, size);
            }
        }
    }

    /** Snapshot the container's own slots (0 .. size-1) straight from the live menu. */
    private static ItemStack[] readContainer(Minecraft client, int size) {
        ItemStack[] arr = new ItemStack[size];
        for (int i = 0; i < size; i++) {
            arr[i] = client.player.containerMenu.getSlot(i).getItem().copy();
        }
        return arr;
    }

    /**
     * Find a slot holding {@code want}. Never pulls from a slot that already holds its own desired
     * item. When {@code exactCount} is false, matches on item+components only (ignores the amount).
     */
    private static int findSource(ItemStack[] current, ItemStack[] desired, int size,
                                  int targetSlot, ItemStack want, boolean exactCount) {
        for (int i = 0; i < size; i++) {
            if (i == targetSlot) continue;
            if (!desired[i].isEmpty() && !current[i].isEmpty()
                    && ItemStack.isSameItemSameComponents(current[i], desired[i])
                    && (!exactCount || current[i].getCount() == desired[i].getCount())) continue;
            if (!current[i].isEmpty() && ItemStack.isSameItemSameComponents(current[i], want)
                    && (!exactCount || current[i].getCount() == want.getCount())) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Swap two container slots (direct 0-based indices). pickup → pickup, and if the destination
     * held an item it's now on the cursor → drop it back at the source.
     */
    private static void doContainerSwap(Minecraft client, int syncId, int from, int to) {
        client.gameMode.handleContainerInput(syncId, from, 0, ContainerInput.PICKUP, client.player);
        client.gameMode.handleContainerInput(syncId, to, 0, ContainerInput.PICKUP, client.player);
        if (!client.player.containerMenu.getCarried().isEmpty()) {
            client.gameMode.handleContainerInput(syncId, from, 0, ContainerInput.PICKUP, client.player);
        }
    }

    private static int materialTier(String itemId, String[] matOrder) {
        for (int i = 0; i < matOrder.length; i++) {
            if (itemId.contains(matOrder[i])) return i;
        }
        return matOrder.length;
    }
}
