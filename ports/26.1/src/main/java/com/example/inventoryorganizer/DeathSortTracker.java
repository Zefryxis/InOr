package com.example.inventoryorganizer;

import com.example.inventoryorganizer.config.OrganizerConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;

import java.util.HashMap;
import java.util.Map;

/**
 * Auto-sort after death: when the player dies, a snapshot of their inventory is kept for
 * 5 minutes. While that snapshot is active, if enough of the snapshotted items reappear in
 * the inventory (i.e. the player has picked their gear back up off the ground), the OI sort
 * is triggered automatically.
 *
 * Match rule: an item type counts as "recovered" once it is PRESENT again in the inventory
 * (count ≥ 1), regardless of the exact amount. Earlier this required the exact same total count
 * for stackables, which broke when the player kept gathering (count exceeded the snapshot) or only
 * partially recovered a stack (count below it) — neither could ever satisfy an exact match, so the
 * threshold / 80% never triggered. Presence-based matching avoids that.
 *
 * Trigger timing (per user spec): once {@link #THRESHOLD} of the lost items are back, a
 * {@link #TRIGGER_DELAY_MS}-long window opens. If within that window {@link #EARLY_FIRE_FRACTION}
 * (80%) of the lost items are recovered, the OI sort fires {@link #EARLY_FIRE_GRACE_MS} (2.25s)
 * after that moment (a short grace to grab the last items); otherwise it fires when the window
 * elapses. Either way the sort runs exactly once.
 *
 * This is opt-in (config toggle) because auto-actions can be considered macros on servers.
 */
public final class DeathSortTracker {

    private static final long TTL_MS = 5 * 60 * 1000L; // keep snapshot 5 minutes
    private static final int THRESHOLD = 4;            // recovered items that open the wait window
    private static final long TRIGGER_DELAY_MS = 4000L; // wait window length after the threshold
    private static final double EARLY_FIRE_FRACTION = 0.80; // recover this fraction → arm the grace timer
    private static final long EARLY_FIRE_GRACE_MS = 2250L; // wait this long after hitting 80%, then sort

    private static Snap deathSnap = null;     // inventory as it was just before death
    private static long deathTime = 0L;
    private static boolean armed = false;     // true after death until sort fires / TTL expires
    private static Snap lastLive = null;      // buffer of the last living-state inventory
    private static boolean wasAlive = true;
    private static long sortAtMs = 0L;        // window-end fallback fire time (0 = threshold not reached yet)
    private static long earlyFireAtMs = 0L;   // 80%-grace fire time (0 = 80% not reached yet)

    private DeathSortTracker() {}

    /** Per-client-tick entry point, called from the client mod initializer. */
    public static void tick(Minecraft client) {
        // Enabled by its own toggle, but only takes effect where Free is allowed
        // (private environment, not in fight mode).
        if (!OrganizerConfig.get().isDeathSortEnabled() || !ServerEnvironment.canUseFree()) { reset(); return; }
        LocalPlayer p = client.player;
        if (p == null) { wasAlive = true; return; }

        boolean alive = p.getHealth() > 0.0f && !p.isDeadOrDying();

        // Continuously buffer the inventory while alive, so we have the pre-death state.
        if (alive) {
            lastLive = snapshotOf(p);
        }

        // Death edge: capture the buffered (pre-death) inventory as the death snapshot.
        if (wasAlive && !alive) {
            if (lastLive != null && !lastLive.counts.isEmpty()) {
                deathSnap = lastLive;
                deathTime = System.currentTimeMillis();
                armed = true;
            }
        }
        wasAlive = alive;

        // Expire the snapshot after the TTL.
        if (armed && System.currentTimeMillis() - deathTime > TTL_MS) {
            reset();
            return;
        }

        // While armed and respawned, watch for recovery of the snapshot items.
        if (armed && deathSnap != null && alive) {
            int matches = countMatches(snapshotOf(p), deathSnap);
            int totalLost = deathSnap.counts.size();
            // Number of recovered items that opens the wait window: THRESHOLD, but never more than
            // the number actually lost (so dying with few items still triggers).
            int startNeeded = Math.min(THRESHOLD, totalLost);
            long now = System.currentTimeMillis();
            if (sortAtMs == 0L) {
                // Open the wait window once enough lost items are back.
                if (totalLost > 0 && matches >= startNeeded) {
                    sortAtMs = now + TRIGGER_DELAY_MS;
                }
            } else {
                // Once 80% of the lost items are recovered, schedule the sort for 1.75s later
                // (grace to grab the last few). If 80% is never reached, fall back to the window end.
                int need80 = (int) Math.ceil(EARLY_FIRE_FRACTION * totalLost);
                if (earlyFireAtMs == 0L && matches >= need80) {
                    earlyFireAtMs = now + EARLY_FIRE_GRACE_MS;
                }
                long fireAt = (earlyFireAtMs > 0L) ? earlyFireAtMs : sortAtMs;
                if (now >= fireAt) {
                    armed = false;
                    deathSnap = null;
                    sortAtMs = 0L;
                    earlyFireAtMs = 0L;
                    InventorySorter.sortInventory();
                }
            }
        }
    }

    private static void reset() {
        deathSnap = null;
        armed = false;
        sortAtMs = 0L;
        earlyFireAtMs = 0L;
    }

    /** Aggregate the main inventory (36 slots) into item-id -> total count. */
    private static Snap snapshotOf(LocalPlayer p) {
        Snap snap = new Snap();
        try {
            for (ItemStack s : p.getInventory().getNonEquipmentItems()) {
                if (s == null || s.isEmpty()) continue;
                net.minecraft.resources.Identifier key = BuiltInRegistries.ITEM.getKey(s.getItem());
                if (key == null) continue;
                String id = key.toString();
                snap.counts.merge(id, s.getCount(), Integer::sum);
            }
        } catch (Throwable ignored) {}
        return snap;
    }

    /** How many distinct death-snapshot item types are present again (count ≥ 1), regardless of amount. */
    private static int countMatches(Snap current, Snap death) {
        int matches = 0;
        for (String id : death.counts.keySet()) {
            if (current.counts.getOrDefault(id, 0) >= 1) matches++;
        }
        return matches;
    }

    private static final class Snap {
        final Map<String, Integer> counts = new HashMap<>();
    }
}
