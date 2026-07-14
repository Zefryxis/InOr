package com.example.inventoryorganizer.warehouse;

import com.example.inventoryorganizer.SortLogic;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.HopperBlockEntity;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Server-side helper for remote crafting: read the combined contents of the player's nearby chests and
 * pull items out of them. Mirrors {@link com.example.inventoryorganizer.WarehouseEngine}'s container
 * access (HopperBlockEntity.getContainerAt + double-chest dedup + per-chest distance re-validation).
 */
public final class RemoteStock {

    private RemoteStock() {}

    /**
     * Reach in blocks. 60 only in true single player AND at a crafting table; everywhere else (the plain
     * inventory, or any server) it's 15. The open menu tells us the context: a {@link net.minecraft.world.inventory.CraftingMenu}
     * means the player is at a crafting table.
     */
    public static double reach(ServerPlayer player, ServerLevel level) {
        var server = level.getServer();
        boolean sp = !server.isDedicatedServer() && !server.isPublished();
        boolean atTable = player.containerMenu instanceof net.minecraft.world.inventory.CraftingMenu;
        return (sp && atTable) ? 60.0 : 15.0;
    }

    /** The distinct chest containers within reach of the player (double-chest partner merged once). */
    static List<Container> nearbyContainers(ServerPlayer player, ServerLevel level, List<BlockPos> chests) {
        double r = reach(player, level);
        double reachSqr = r * r;
        String dim = level.dimension().identifier().toString();
        String uuid = player.getUUID().toString();
        List<Container> out = new ArrayList<>();
        Set<BlockPos> visited = new HashSet<>();
        for (BlockPos pos : chests) {
            if (pos == null || visited.contains(pos)) continue;
            visited.add(pos);
            if (pos.getCenter().distanceToSqr(player.position()) > reachSqr) continue;
            // Anti-theft: never read/withdraw from a chest that belongs to another player's link.
            if (WarehouseLinks.get().isForeignLinkChest(dim, pos, uuid)) continue;
            // Respect vanilla interaction protection (spawn protection / world border).
            if (!level.mayInteract(player, pos)) continue;
            BlockPos partner = SortLogic.doubleChestPartner(level, pos);
            if (partner != null) {
                visited.add(partner);
                if (WarehouseLinks.get().isForeignLinkChest(dim, partner, uuid)) continue;
            }
            Container c = HopperBlockEntity.getContainerAt(level, pos);
            if (c != null) out.add(c);
        }
        return out;
    }

    /** One chest's contents for the per-chest materials panel: position, display name, item→count (ordered). */
    public record ChestStock(BlockPos pos, String name, LinkedHashMap<String, Integer> items) {}

    /** A chest's display name (custom anvil name if set, else the block's name e.g. "Chest"). */
    private static String chestName(ServerLevel level, BlockPos pos) {
        try {
            net.minecraft.world.level.block.entity.BlockEntity be = level.getBlockEntity(pos);
            if (be instanceof net.minecraft.world.Nameable n) return n.getDisplayName().getString();
        } catch (Throwable ignored) {}
        return level.getBlockState(pos).getBlock().getName().getString();
    }

    /** Per-chest contents within reach (same reach/foreign/mayInteract guards as {@link #nearbyContainers}). */
    public static List<ChestStock> aggregatePerChest(ServerPlayer player, ServerLevel level, List<BlockPos> chests) {
        double r = reach(player, level);
        double reachSqr = r * r;
        String dim = level.dimension().identifier().toString();
        String uuid = player.getUUID().toString();
        List<ChestStock> out = new ArrayList<>();
        Set<BlockPos> visited = new HashSet<>();
        for (BlockPos pos : chests) {
            if (pos == null || visited.contains(pos)) continue;
            visited.add(pos);
            if (pos.getCenter().distanceToSqr(player.position()) > reachSqr) continue;
            if (WarehouseLinks.get().isForeignLinkChest(dim, pos, uuid)) continue;
            if (!level.mayInteract(player, pos)) continue;
            BlockPos partner = SortLogic.doubleChestPartner(level, pos);
            if (partner != null) {
                visited.add(partner);
                if (WarehouseLinks.get().isForeignLinkChest(dim, partner, uuid)) continue;
            }
            Container c = HopperBlockEntity.getContainerAt(level, pos);
            if (c == null) continue;
            LinkedHashMap<String, Integer> items = new LinkedHashMap<>();
            for (int i = 0; i < c.getContainerSize(); i++) {
                ItemStack s = c.getItem(i);
                if (s.isEmpty()) continue;
                items.merge(BuiltInRegistries.ITEM.getKey(s.getItem()).toString(), s.getCount(), Integer::sum);
            }
            // Include EVERY chest in reach (even empty ones) so the panel can list them all.
            out.add(new ChestStock(pos.immutable(), chestName(level, pos), items));
        }
        return out;
    }

    /** Withdraw up to {@code amount} of {@code itemId} from ONE specific chest (with the usual guards). */
    public static int withdrawFrom(ServerPlayer player, ServerLevel level, BlockPos sourcePos, String itemId, int amount) {
        Item want = itemFromId(itemId);
        if (want == null || amount <= 0 || sourcePos == null) return 0;
        double r = reach(player, level);
        if (sourcePos.getCenter().distanceToSqr(player.position()) > r * r) return 0;
        String dim = level.dimension().identifier().toString();
        String uuid = player.getUUID().toString();
        if (WarehouseLinks.get().isForeignLinkChest(dim, sourcePos, uuid)) return 0;
        if (!level.mayInteract(player, sourcePos)) return 0;
        // Double chest: getContainerAt returns the COMBINED container, so without this check a foreign
        // owner's linked half could be drained by targeting the non-linked half's BlockPos. Refuse if the
        // partner belongs to another player's link (matches the nearbyContainers guard).
        BlockPos partner = SortLogic.doubleChestPartner(level, sourcePos);
        if (partner != null && WarehouseLinks.get().isForeignLinkChest(dim, partner, uuid)) return 0;
        Container c = HopperBlockEntity.getContainerAt(level, sourcePos);
        if (c == null) return 0;
        int remaining = Math.min(amount, 2304), given = 0;
        for (int i = 0; i < c.getContainerSize() && remaining > 0; i++) {
            ItemStack s = c.getItem(i);
            if (s.isEmpty() || s.getItem() != want) continue;
            int take = Math.min(remaining, s.getCount());
            ItemStack moved = s.copy();
            moved.setCount(take);
            if (!player.getInventory().add(moved)) take -= moved.getCount();
            if (take > 0) {
                s.shrink(take);
                c.setItem(i, s.isEmpty() ? ItemStack.EMPTY : s);
                c.setChanged();
                remaining -= take;
                given += take;
            }
        }
        return given;
    }

    /** Aggregate item id → total count across the nearby chests (insertion order = first seen). */
    public static Map<String, Integer> aggregate(ServerPlayer player, ServerLevel level, List<BlockPos> chests) {
        Map<String, Integer> totals = new LinkedHashMap<>();
        for (Container c : nearbyContainers(player, level, chests)) {
            for (int i = 0; i < c.getContainerSize(); i++) {
                ItemStack s = c.getItem(i);
                if (s.isEmpty()) continue;
                String id = BuiltInRegistries.ITEM.getKey(s.getItem()).toString();
                totals.merge(id, s.getCount(), Integer::sum);
            }
        }
        return totals;
    }

    /**
     * Pull up to {@code amount} of {@code itemId} out of the nearby chests into the player's inventory.
     * Anything that doesn't fit stays in the chest. Returns how many items were actually given.
     */
    public static int withdraw(ServerPlayer player, ServerLevel level, List<BlockPos> chests, String itemId, int amount) {
        Item want = itemFromId(itemId);
        if (want == null || amount <= 0) return 0;
        int remaining = Math.min(amount, 2304); // cap to ~a full inventory's worth; multiple stacks allowed
        int given = 0;
        for (Container c : nearbyContainers(player, level, chests)) {
            for (int i = 0; i < c.getContainerSize() && remaining > 0; i++) {
                ItemStack s = c.getItem(i);
                if (s.isEmpty() || s.getItem() != want) continue;
                int take = Math.min(remaining, s.getCount());
                ItemStack moved = s.copy();
                moved.setCount(take);
                if (!player.getInventory().add(moved)) {
                    // Inventory full → put back whatever didn't fit.
                    take -= moved.getCount();
                }
                if (take > 0) {
                    s.shrink(take);
                    c.setItem(i, s.isEmpty() ? ItemStack.EMPTY : s);
                    c.setChanged();
                    remaining -= take;
                    given += take;
                }
            }
        }
        return given;
    }

    /**
     * Deposit {@code stack} into the player's nearby chests (merge into matching stacks first, then empty
     * slots), and SORT (OST) each chest an item actually landed in. Returns the leftover (empty if all of
     * it fit). Same reach / foreign-link / mayInteract guards as {@link #nearbyContainers} — you can only
     * deposit into a chest you could open by hand and that isn't another player's link.
     */
    public static ItemStack deposit(ServerPlayer player, ServerLevel level, List<BlockPos> chests, ItemStack stack) {
        if (stack == null || stack.isEmpty()) return stack;
        for (Container c : nearbyContainers(player, level, chests)) {
            if (stack.isEmpty()) break;
            int before = stack.getCount();
            insertInto(c, stack);
            if (stack.getCount() < before) {
                // "OST" the chest it went into: merge partials + group identical items together.
                com.example.inventoryorganizer.WarehouseEngine.sortSingleContainer(c);
                c.setChanged();
            }
        }
        return stack;
    }

    /**
     * Deposit {@code stack} into ONE specific chest (with the usual reach/foreign/mayInteract guards), and
     * OST that chest. Returns the leftover (empty if it all fit). Used to return crafting ingredients to
     * the exact chest they were pulled from.
     */
    public static ItemStack depositInto(ServerPlayer player, ServerLevel level, BlockPos pos, ItemStack stack) {
        if (stack == null || stack.isEmpty() || pos == null) return stack;
        double r = reach(player, level);
        if (pos.getCenter().distanceToSqr(player.position()) > r * r) return stack;
        String dim = level.dimension().identifier().toString();
        String uuid = player.getUUID().toString();
        if (WarehouseLinks.get().isForeignLinkChest(dim, pos, uuid)) return stack;
        if (!level.mayInteract(player, pos)) return stack;
        // Double chest: refuse if the partner half belongs to another player's link (the combined
        // container would otherwise let a foreign-linked half be written via the non-linked half).
        BlockPos partner = SortLogic.doubleChestPartner(level, pos);
        if (partner != null && WarehouseLinks.get().isForeignLinkChest(dim, partner, uuid)) return stack;
        Container c = HopperBlockEntity.getContainerAt(level, pos);
        if (c == null) return stack;
        int before = stack.getCount();
        insertInto(c, stack);
        if (stack.getCount() < before) {
            com.example.inventoryorganizer.WarehouseEngine.sortSingleContainer(c);
            c.setChanged();
        }
        return stack;
    }

    /** Merge {@code stack} into matching partials, then empty slots, in container {@code c}. Mutates stack. */
    private static void insertInto(Container c, ItemStack stack) {
        int max = Math.min(c.getMaxStackSize(), stack.getMaxStackSize());
        for (int i = 0; i < c.getContainerSize() && !stack.isEmpty(); i++) {
            ItemStack slot = c.getItem(i);
            if (slot.isEmpty() || slot.getCount() >= max) continue;
            if (ItemStack.isSameItemSameComponents(slot, stack) && c.canPlaceItem(i, stack)) {
                int move = Math.min(max - slot.getCount(), stack.getCount());
                slot.grow(move); stack.shrink(move); c.setItem(i, slot);
            }
        }
        for (int i = 0; i < c.getContainerSize() && !stack.isEmpty(); i++) {
            if (!c.getItem(i).isEmpty() || !c.canPlaceItem(i, stack)) continue;
            int move = Math.min(max, stack.getCount());
            ItemStack put = stack.copy(); put.setCount(move);
            c.setItem(i, put); stack.shrink(move);
        }
    }

    static Item itemFromId(String id) {
        Identifier rid = Identifier.tryParse(id);
        return rid == null ? null : BuiltInRegistries.ITEM.getValue(rid);
    }
}
