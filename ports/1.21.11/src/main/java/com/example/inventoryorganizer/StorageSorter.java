package com.example.inventoryorganizer;

import com.example.inventoryorganizer.config.OrganizerConfig;
import com.example.inventoryorganizer.config.StoragePreset;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.slot.SlotActionType;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class StorageSorter {

    /**
     * Sort the currently open container using the first matching storage preset.
     * Must be called while a container screen is open.
     *
     * @param containerSize  number of slots in the container (27 or 54, etc.)
     * @param syncId         the screen handler sync ID
     */
    public static void sortContainer(int containerSize, int syncId) {
        OrganizerConfig config = OrganizerConfig.get();
        int presetIdx = findMatchingPresetIndex(config, containerSize);
        if (presetIdx < 0) {
            System.out.println("[InvOrganizer] OST: No storage preset configured for size " + containerSize);
            return;
        }
        sortContainerWithPreset(containerSize, syncId, config.getStoragePresets().get(presetIdx), presetIdx);
    }

    /** Find the index of the first storage preset whose size matches the container (-1 if not found). */
    private static int findMatchingPresetIndex(OrganizerConfig config, int containerSize) {
        List<StoragePreset> presets = config.getStoragePresets();
        for (int i = 0; i < presets.size(); i++) {
            if (presets.get(i).getSize() == containerSize) return i;
        }
        return -1;
    }

    /**
     * Applies a StoragePreset's rules and tier assignments to sort the container slots.
     * Uses the same matching logic as InventorySorter (via package-private helpers).
     */
    public static void sortContainerWithPreset(int containerSize, int syncId, StoragePreset preset, int presetIdx) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.interactionManager == null) return;

        // Step 1: Collect items from container slots (indices 0 .. containerSize-1)
        // The container handler's slots 0..containerSize-1 are the container's own slots.
        // We read them via client.player.currentScreenHandler.getSlot(i).getStack()
        ItemStack[] containerItems = new ItemStack[containerSize];
        for (int i = 0; i < containerSize; i++) {
            containerItems[i] = client.player.currentScreenHandler.getSlot(i).getStack().copy();
        }

        // Step 2: Collect all non-empty items into a pool
        List<ItemStack> pool = new ArrayList<>();
        for (ItemStack s : containerItems) {
            if (!s.isEmpty()) pool.add(s.copy());
        }

        if (pool.isEmpty()) {
            System.out.println("[InvOrganizer] OST: Container is empty.");
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
            int catA = InventorySorter.getCategory(a);
            int catB = InventorySorter.getCategory(b);
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
            if (rule.contains(":")) { // specific item (minecraft:xxx)
                for (int j = 0; j < pool.size(); j++) {
                    if (InventorySorter.matchesSpecificItem(InventorySorter.getItemId(pool.get(j)), rule)) {
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

        // Pass 2: group matches (g:weapons, g:food etc.)
        for (int s : slotOrder) {
            if (!desired[s].isEmpty()) continue;
            String rule = preset.getSlotRule(s);
            if (!rule.startsWith("g:")) continue;
            String groupName = rule.substring(2);
            int targetCat = InventorySorter.groupNameToCategory(groupName);
            for (int j = 0; j < pool.size(); j++) {
                if (InventorySorter.getCategory(pool.get(j)) == targetCat) {
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

        System.out.println("[InvOrganizer] OST: Sorting container size=" + containerSize
                + " preset='" + preset.getName() + "' pool remaining=" + pool.size());

        // Step 5: Execute swaps within container slots only
        ItemStack[] current = containerItems; // already copied

        for (int targetSlot = 0; targetSlot < containerSize; targetSlot++) {
            ItemStack want = desired[targetSlot];
            if (want.isEmpty()) continue;
            String wantId = InventorySorter.getItemId(want);

            ItemStack have = current[targetSlot];
            if (!have.isEmpty() && InventorySorter.getItemId(have).equals(wantId)
                    && have.getCount() == want.getCount()) continue; // already correct

            // Clear target slot if occupied by wrong item
            if (!have.isEmpty()) {
                int dumpSlot = findEmptyContainerSlot(current, containerSize, targetSlot);
                if (dumpSlot != -1) {
                    doContainerSwap(client, syncId, targetSlot, dumpSlot, containerSize);
                    current[dumpSlot] = current[targetSlot];
                    current[targetSlot] = ItemStack.EMPTY;
                    have = ItemStack.EMPTY;
                }
            }

            // Find source
            int sourceSlot = -1;
            for (int i = 0; i < containerSize; i++) {
                if (i == targetSlot) continue;
                if (!current[i].isEmpty() && InventorySorter.getItemId(current[i]).equals(wantId)
                        && current[i].getCount() == want.getCount()) {
                    sourceSlot = i;
                    break;
                }
            }
            if (sourceSlot == -1) continue;

            doContainerSwap(client, syncId, sourceSlot, targetSlot, containerSize);
            ItemStack tmp = current[targetSlot];
            current[targetSlot] = current[sourceSlot];
            current[sourceSlot] = tmp;
        }

        System.out.println("[InvOrganizer] OST: Sort complete.");
    }

    private static int findEmptyContainerSlot(ItemStack[] current, int size, int excludeSlot) {
        for (int i = 0; i < size; i++) {
            if (i != excludeSlot && current[i].isEmpty()) return i;
        }
        return -1;
    }

    /**
     * Swap two slots within the container. Container slot indices are direct (0-based).
     * The screen handler maps: container slot i → screen slot i.
     */
    private static void doContainerSwap(MinecraftClient client, int syncId, int from, int to, int containerSize) {
        // For container screens, container slots are at their direct indices (0..size-1)
        client.interactionManager.clickSlot(syncId, from, 0, SlotActionType.PICKUP, client.player);
        client.interactionManager.clickSlot(syncId, to, 0, SlotActionType.PICKUP, client.player);
        if (!client.player.currentScreenHandler.getCursorStack().isEmpty()) {
            client.interactionManager.clickSlot(syncId, from, 0, SlotActionType.PICKUP, client.player);
        }
    }

    private static int materialTier(String itemId, String[] matOrder) {
        for (int i = 0; i < matOrder.length; i++) {
            if (itemId.contains(matOrder[i])) return i;
        }
        return matOrder.length;
    }
}
