package com.example.inventoryorganizer;

import net.minecraft.client.gui.widget.ButtonWidget;

import java.security.SecureRandom;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Tracks combat state for Fight Mode.
 * Fight Mode activates when the player takes damage or attacks a living entity,
 * and stays active for 20 seconds after the last combat event.
 * While active, the OI button moves only one item per press (randomised 130-179 ms cooldown).
 *
 * TAMPER-PROOF DESIGN:
 *  - Class is final → cannot be subclassed.
 *  - All state fields are private → no external writes.
 *  - triggerCombat() only ADVANCES the timer (monotonic, never moves it backwards).
 *  - There is NO public method to disable, reset, or shorten fight mode.
 *  - State is redundantly stored in THREE independent sources:
 *      1. lastCombatMs         — plain AtomicLong
 *      2. lastCombatEncoded    — XOR-encoded AtomicLong (with a per-JVM random key)
 *      3. combatLog            — append-only ConcurrentLinkedDeque of timestamps
 *  - A daemon watchdog thread runs every 50 ms, healing all three sources to
 *    the maximum observed value. Any reflective write to one source that tries
 *    to shorten the timer gets overwritten within milliseconds from the other two.
 *  - isActive() and remainingMs() also self-heal on every read.
 */
public final class FightModeTracker {

    private FightModeTracker() {}

    /** Set by InventoryScreenMixin on init; used by ClientTickEvents to re-enable OI button. */
    public static volatile ButtonWidget oiButtonRef = null;

    // ---- Primary + redundant state (all private) --------------------------
    private static final long XOR_KEY = new SecureRandom().nextLong();
    private static final AtomicLong lastCombatMs        = new AtomicLong(-1L);
    private static final AtomicLong lastCombatEncoded   = new AtomicLong((-1L) ^ XOR_KEY);
    private static final ConcurrentLinkedDeque<Long> combatLog = new ConcurrentLinkedDeque<>();
    private static final int COMBAT_LOG_MAX = 64;

    private static final AtomicLong lastOiUseMs         = new AtomicLong(-1L);
    private static final AtomicLong currentCooldownMs   = new AtomicLong(130L);

    public static final long FIGHT_DURATION_MS   = 20_000L;
    public static final long OI_COOLDOWN_BASE_MS =    130L;
    public static final long OI_COOLDOWN_RAND_MS =     50L;

    // ---- Watchdog: heals redundant state every 50 ms ----------------------
    static {
        Thread watchdog = new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(50L);
                    heal();
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (Throwable ignored) {
                    // Never let watchdog die.
                }
            }
        }, "InventoryOrganizer-FightMode-Watchdog");
        watchdog.setDaemon(true);
        watchdog.setPriority(Thread.MIN_PRIORITY + 1);
        watchdog.start();
    }

    /** Compute the maximum timestamp across all three redundant sources. */
    private static long computeMax() {
        long p = lastCombatMs.get();
        long s;
        try {
            s = lastCombatEncoded.get() ^ XOR_KEY;
        } catch (Throwable t) {
            s = -1L;
        }
        long logMax = -1L;
        for (Long ts : combatLog) {
            if (ts != null && ts > logMax) logMax = ts;
        }
        long m = p;
        if (s > m) m = s;
        if (logMax > m) m = logMax;
        return m;
    }

    /** Force all redundant sources to the max observed value. Called by watchdog + on reads. */
    private static void heal() {
        long max = computeMax();
        if (max <= 0) return;
        lastCombatMs.accumulateAndGet(max, Math::max);
        // Accumulate the encoded form so concurrent decodes stay consistent.
        final long finalMax = max;
        lastCombatEncoded.getAndUpdate(cur -> {
            long decoded = cur ^ XOR_KEY;
            long winner = Math.max(decoded, finalMax);
            return winner ^ XOR_KEY;
        });
        // Trim log to keep memory bounded; never remove the newest entry.
        while (combatLog.size() > COMBAT_LOG_MAX) {
            combatLog.pollFirst();
        }
    }

    /** Returns true if fight mode is currently active. Self-heals state on read. */
    public static boolean isActive() {
        long effective = computeMax();
        if (effective <= 0) return false;
        boolean active = (System.currentTimeMillis() - effective) < FIGHT_DURATION_MS;
        if (active) heal();
        return active;
    }

    /** Remaining milliseconds before fight mode expires (0 if not active). */
    public static long remainingMs() {
        long effective = computeMax();
        if (effective <= 0) return 0L;
        long rem = FIGHT_DURATION_MS - (System.currentTimeMillis() - effective);
        return Math.max(0L, rem);
    }

    /**
     * Call when combat is detected. Only advances the timer – never moves it backwards.
     * Writes to ALL THREE redundant sources so a tampered single source self-heals.
     */
    public static void triggerCombat() {
        long now = System.currentTimeMillis();
        lastCombatMs.accumulateAndGet(now, Math::max);
        lastCombatEncoded.getAndUpdate(cur -> {
            long decoded = cur ^ XOR_KEY;
            long winner = Math.max(decoded, now);
            return winner ^ XOR_KEY;
        });
        combatLog.addLast(now);
        while (combatLog.size() > COMBAT_LOG_MAX) {
            combatLog.pollFirst();
        }
    }

    /** Whether the OI button may fire right now (respects the randomised cooldown). */
    public static boolean canUseOI() {
        long last = lastOiUseMs.get();
        return last < 0 || (System.currentTimeMillis() - last) >= currentCooldownMs.get();
    }

    public static void markOIUsed() {
        lastOiUseMs.set(System.currentTimeMillis());
        currentCooldownMs.set(OI_COOLDOWN_BASE_MS + (long)(Math.random() * OI_COOLDOWN_RAND_MS));
    }

    /** Remaining OI cooldown in milliseconds (0 if not on cooldown). */
    public static long remainingOICooldownMs() {
        long last = lastOiUseMs.get();
        if (last < 0) return 0L;
        long remaining = currentCooldownMs.get() - (System.currentTimeMillis() - last);
        return Math.max(0L, remaining);
    }
}
