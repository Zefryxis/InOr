package com.example.inventoryorganizer;

import com.example.inventoryorganizer.warehouse.ChestRules;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Server-side warehouse sort engine. Treats a group of chests as one logical store: every item is
 * routed to the chest whose per-chest profile most specifically accepts it (a chest with a SPECIFIC
 * rule for the item wins over a type/group rule, regardless of which physical chest that is;
 * unmatched items go to an overflow chest), moving items directly between chest {@link Container}s —
 * no open/close, no reach limit, instant.
 *
 * <p>Fully server-safe: the per-chest slot rules arrive from the client over the wire
 * ({@link ChestRules}) and matching runs through {@link SortLogic} (no client classes), so this
 * works on a real dedicated server as well as the single-player integrated server. A double chest is
 * one logical unit (merged 54-slot container via {@link HopperBlockEntity#getContainerAt}).
 */
public final class WarehouseEngine {

    private static final Logger LOGGER = LoggerFactory.getLogger("inventory-organizer/Warehouse");

    /**
     * Server-side guard: a player may only sort chests within this many blocks of themselves. The
     * client UI caps links at 15 blocks, but a hacked client could send arbitrary positions, so the
     * server independently refuses far-away chests — you can't rearrange storage across the map or in
     * someone else's distant base. (Generous enough for a real on-site warehouse.)
     */
    private static final double MAX_REACH = 60.0;
    private static final double MAX_REACH_SQR = MAX_REACH * MAX_REACH;

    /** Server-side cap on chests processed per request (defence in depth vs. the codec's own cap). */
    private static final int MAX_UNITS = 256;

    private WarehouseEngine() {}

    private record Unit(BlockPos pos, Container container, List<String> rules) {}

    /** Sort a warehouse group: redistribute all items across its chests by per-chest slot rules. */
    public static void sortGroup(ServerPlayer player, ServerLevel level, List<ChestRules> chests) {
        if (level == null || chests == null || chests.isEmpty()) return;
        // Resolve g:/cg: rules against the sorting player's OWN custom-group membership. On a dedicated
        // server the server's config is empty, so the client syncs its groups (WarehouseNet.PLAYER_GROUPS);
        // SortLogic falls back to the local config (SP) / heuristic when none is set.
        if (player != null) SortLogic.setActiveGroups(
                com.example.inventoryorganizer.warehouse.WarehouseNet.playerGroups(player.getUUID()));
        try {
            sortGroup0(player, level, chests);
        } finally {
            SortLogic.clearActiveGroups();
        }
    }

    private static void sortGroup0(ServerPlayer player, ServerLevel level, List<ChestRules> chests) {
        Map<BlockPos, List<String>> ruleMap = new HashMap<>();
        for (ChestRules cr : chests) if (cr.pos() != null) ruleMap.put(cr.pos(), cr.rules());

        // Resolve each position to a chest container + its slot rules. A double chest is one unit:
        // getContainerAt returns the merged 54-slot container, and we skip the partner half. The slot
        // rules come straight from the request (the owner's rules — either the owner's own client, or
        // the server-stored link rules for a non-owner OST); access was already validated by the caller.
        List<Unit> units = new ArrayList<>();
        Set<BlockPos> visited = new HashSet<>();
        for (ChestRules cr : chests) {
            if (units.size() >= MAX_UNITS) break;
            BlockPos pos = cr.pos();
            if (pos == null || visited.contains(pos)) continue;
            visited.add(pos);
            // Reject chests the requesting player isn't actually near (anti-grief / anti-cheat).
            if (player != null && net.minecraft.world.phys.Vec3.atCenterOf(pos).distanceToSqr(player.position()) > MAX_REACH_SQR) continue;
            // Respect vanilla interaction protection (spawn protection / world border): never move items
            // in a chest the player couldn't open by hand.
            if (player != null && !level.mayInteract(player, pos)) continue;
            List<String> rules = cr.rules();
            BlockPos partner = SortLogic.doubleChestPartner(level, pos);
            if (partner != null) {
                visited.add(partner);
                List<String> pr = ruleMap.get(partner); // prefer the fuller (54-slot) rule list
                if (pr != null && (rules == null || pr.size() > rules.size())) rules = pr;
            }
            Container c = HopperBlockEntity.getContainerAt(level, pos);
            if (c == null) { LOGGER.warn("[Warehouse] no container at {} (skipped)", pos); continue; }
            units.add(new Unit(pos, c, rules != null ? rules : List.of()));
        }
        if (units.isEmpty()) {
            LOGGER.warn("[Warehouse] nothing to sort: 0 valid chest units (reach/mayInteract/empty rules?)");
            return;
        }

        if (LOGGER.isDebugEnabled()) {
            for (Unit u : units) {
                LOGGER.debug("[Warehouse] chest {} rules={}", u.pos(), new java.util.LinkedHashSet<>(u.rules()));
            }
        }

        // Pull every item out into a pool, leaving the chests empty.
        List<ItemStack> pool = new ArrayList<>();
        for (Unit u : units) {
            Container c = u.container();
            for (int i = 0; i < c.getContainerSize(); i++) {
                ItemStack s = c.getItem(i);
                if (!s.isEmpty()) {
                    pool.add(s.copy());
                    c.setItem(i, ItemStack.EMPTY);
                }
            }
        }
        LOGGER.debug("[Warehouse] pooled {} stack(s) from {} chest(s)", pool.size(), units.size());

        // Route each stack to its target chest; spill to any chest with room if the target is full.
        for (ItemStack stack : pool) {
            ItemStack remaining = stack;
            Unit target = pickTarget(units, remaining);
            if (LOGGER.isDebugEnabled()) {
                int rank = target != null ? SortLogic.matchRank(target.rules(), stack) : Integer.MAX_VALUE;
                LOGGER.debug("[Warehouse] item {} -> {} (rank {})", SortLogic.getItemId(stack),
                        target != null ? target.pos() : "none", rank == Integer.MAX_VALUE ? "overflow" : rank);
            }
            if (target != null) remaining = placeInto(target.container(), remaining, target.rules());
            for (Unit u : units) {
                if (remaining.isEmpty()) break;
                remaining = placeInto(u.container(), remaining, u.rules());
            }
        }

        // Tidy each chest: merge same-item partials and group identical items into adjacent slots, so
        // a "blocks chest" ends up neat (e.g. all oak planks together) instead of scattered placement.
        for (Unit u : units) tidyContainer(u.container(), u.rules());

        for (Unit u : units) u.container().setChanged();
        LOGGER.debug("Warehouse sorted {} chest unit(s)", units.size());
    }

    /**
     * Re-lay a single container compactly: pull everything out, merge same item+components into full
     * stacks, sort by item id so identical items sit next to each other, and write the result back from
     * slot 0. Pure server-side container manipulation — no packets, no desync.
     */
    /** Public single-chest sort (used by remote-crafting deposit): merge partials + group items by id. */
    public static void sortSingleContainer(Container c) { tidyContainer(c, null); }

    /**
     * Route a single stack into the BEST-MATCHING chest among {@code chests} (same ranking as
     * {@link #sortGroup}: specific item &gt; type &gt; group, else the overflow chest), respecting each
     * slot's rule when placing it, and spilling to any other chest with room if the target is full.
     * Only the chest(s) an item actually landed in are tidied afterward. Returns the leftover (empty if
     * it all fit).
     *
     * <p>Used by remote-crafting deposit ({@link com.example.inventoryorganizer.warehouse.RemoteStock}),
     * which resolves reach/foreign-link/mayInteract guards and hands over the already-validated
     * container for each candidate position. This is the single-stack counterpart to {@link #sortGroup},
     * which redistributes EVERY item already in the group; this only places the ONE new stack, so it
     * never disturbs items the player already organized elsewhere.
     */
    public static ItemStack depositStack(ServerPlayer player, List<com.example.inventoryorganizer.warehouse.ChestRules> chests,
                                          Map<BlockPos, Container> containers, ItemStack stack) {
        if (stack == null || stack.isEmpty() || chests == null || chests.isEmpty() || containers == null) return stack;
        List<Unit> units = new ArrayList<>();
        for (com.example.inventoryorganizer.warehouse.ChestRules cr : chests) {
            Container c = containers.get(cr.pos());
            if (c != null) units.add(new Unit(cr.pos(), c, cr.rules() != null ? cr.rules() : List.of()));
        }
        if (units.isEmpty()) return stack;

        if (player != null) SortLogic.setActiveGroups(
                com.example.inventoryorganizer.warehouse.WarehouseNet.playerGroups(player.getUUID()));
        try {
            Set<Unit> touched = new HashSet<>();
            ItemStack remaining = stack;
            Unit target = pickTarget(units, remaining);
            if (target != null) {
                int before = remaining.getCount();
                remaining = placeInto(target.container(), remaining, target.rules());
                if (remaining.getCount() < before) touched.add(target);
            }
            for (Unit u : units) {
                if (remaining.isEmpty()) break;
                if (u == target) continue;
                int before = remaining.getCount();
                remaining = placeInto(u.container(), remaining, u.rules());
                if (remaining.getCount() < before) touched.add(u);
            }
            for (Unit u : touched) {
                tidyContainer(u.container(), u.rules());
                u.container().setChanged();
            }
            return remaining;
        } finally {
            SortLogic.clearActiveGroups();
        }
    }

    private static void tidyContainer(Container c, List<String> rules) {
        List<ItemStack> items = new ArrayList<>();
        for (int i = 0; i < c.getContainerSize(); i++) {
            if (isNothingSlot(rules, i)) continue; // leave "nothing" slots untouched
            ItemStack s = c.getItem(i);
            if (!s.isEmpty()) items.add(s.copy());
            c.setItem(i, ItemStack.EMPTY);
        }
        // Merge same item+components up to max stack size.
        List<ItemStack> merged = new ArrayList<>();
        for (ItemStack s : items) {
            for (ItemStack m : merged) {
                if (s.isEmpty()) break;
                if (ItemStack.isSameItemSameComponents(m, s) && m.getCount() < m.getMaxStackSize()) {
                    int move = Math.min(m.getMaxStackSize() - m.getCount(), s.getCount());
                    m.grow(move);
                    s.shrink(move);
                }
            }
            if (!s.isEmpty()) merged.add(s);
        }
        // Group identical items together (sort by registry id).
        merged.sort(java.util.Comparator.comparing(st ->
                net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(st.getItem()).toString()));
        int idx = 0;
        for (ItemStack s : merged) {
            while (idx < c.getContainerSize() && isNothingSlot(rules, idx)) idx++; // skip "nothing" slots
            if (idx >= c.getContainerSize()) break;
            c.setItem(idx++, s);
        }
    }

    private static boolean isNothingSlot(List<String> rules, int slot) {
        return rules != null && slot < rules.size() && "empty".equals(rules.get(slot));
    }

    /**
     * Pick the chest a stack should go to. The whole linked group is treated as one sorted space:
     * every chest's slot rules are ranked together and the item goes to the chest with the
     * HIGHEST-priority matching slot (specific item &gt; item type &gt; group). If no chest
     * specifically wants it, it spills to an overflow chest (one with no specific rules).
     */
    private static Unit pickTarget(List<Unit> units, ItemStack stack) {
        Unit best = null;
        int bestRank = Integer.MAX_VALUE;
        for (Unit u : units) {
            int r = SortLogic.matchRank(u.rules(), stack);
            if (r < bestRank) { bestRank = r; best = u; }
        }
        if (best != null && bestRank != Integer.MAX_VALUE) return best;
        for (Unit u : units) {
            if (!SortLogic.hasSpecificRules(u.rules())) return u; // overflow chest
        }
        return units.isEmpty() ? null : units.get(0);
    }

    /** Place a stack into a container (merge into matching stacks, then empty slots). Returns leftover.
     *  "nothing" slots are skipped. Empty slots with a specific rule that does NOT match the item are
     *  also skipped — a pickaxe won't land in an armor chest's empty g:armor slot. */
    private static ItemStack placeInto(Container c, ItemStack stack, List<String> rules) {
        if (stack.isEmpty()) return stack;
        int max = Math.min(c.getMaxStackSize(), stack.getMaxStackSize());
        // Merge into existing matching stacks (rule check not needed — item is already in that slot)
        for (int i = 0; i < c.getContainerSize() && !stack.isEmpty(); i++) {
            if (isNothingSlot(rules, i)) continue;
            ItemStack slot = c.getItem(i);
            if (slot.isEmpty() || slot.getCount() >= max) continue;
            if (ItemStack.isSameItemSameComponents(slot, stack) && c.canPlaceItem(i, stack)) {
                int move = Math.min(max - slot.getCount(), stack.getCount());
                slot.grow(move);
                stack.shrink(move);
                c.setItem(i, slot);
            }
        }
        // Place in empty slots — only where the slot's rule is "any" or matches the item
        for (int i = 0; i < c.getContainerSize() && !stack.isEmpty(); i++) {
            if (isNothingSlot(rules, i)) continue;
            if (!c.getItem(i).isEmpty() || !c.canPlaceItem(i, stack)) continue;
            if (!slotAcceptsItem(rules, i, stack)) continue;
            int move = Math.min(max, stack.getCount());
            ItemStack put = stack.copy();
            put.setCount(move);
            c.setItem(i, put);
            stack.shrink(move);
        }
        return stack;
    }

    /** True if the slot rule is "any"/absent, or explicitly matches the item. */
    private static boolean slotAcceptsItem(List<String> rules, int slot, ItemStack stack) {
        if (rules == null || slot >= rules.size()) return true;
        String rule = rules.get(slot);
        if (rule == null || rule.isEmpty() || rule.equals("any")) return true;
        return SortLogic.matchRank(java.util.List.of(rule), stack) < Integer.MAX_VALUE;
    }
}
