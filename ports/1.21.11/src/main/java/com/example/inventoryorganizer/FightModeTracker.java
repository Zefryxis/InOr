package com.example.inventoryorganizer;

import net.minecraft.client.gui.widget.ButtonWidget;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Tracks combat state for Fight Mode.
 * Fight Mode activates when the player takes damage or attacks a living entity,
 * and stays active for 20 seconds after the last combat event.
 * While active, the OI button moves only one item per press (randomised 130-179 ms cooldown).
 *
 * Design guarantees:
 *  - Class is final → cannot be subclassed or overridden.
 *  - All state fields are private AtomicLong → thread-safe, no public setters.
 *  - triggerCombat() only ADVANCES the timer (never resets it backwards).
 *  - There is NO method to disable or shorten fight mode once active.
 */
public final class FightModeTracker {

    private FightModeTracker() {}

    /** Set by InventoryScreenMixin on init; used by ClientTickEvents to re-enable OI button. */
    public static volatile ButtonWidget oiButtonRef = null;

    private static final AtomicLong lastCombatMs     = new AtomicLong(-1L);
    private static final AtomicLong lastOiUseMs      = new AtomicLong(-1L);
    private static final AtomicLong currentCooldownMs = new AtomicLong(130L);

    public static final long FIGHT_DURATION_MS   = 20_000L;
    public static final long OI_COOLDOWN_BASE_MS =    130L;
    public static final long OI_COOLDOWN_RAND_MS =     50L;

    /** Returns true if fight mode is currently active. */
    public static boolean isActive() {
        long last = lastCombatMs.get();
        return last > 0 && (System.currentTimeMillis() - last) < FIGHT_DURATION_MS;
    }

    /** Remaining milliseconds before fight mode expires (0 if not active). */
    public static long remainingMs() {
        if (!isActive()) return 0L;
        return FIGHT_DURATION_MS - (System.currentTimeMillis() - lastCombatMs.get());
    }

    /**
     * Call when combat is detected. Only advances the timer – never moves it backwards.
     * This means fight mode can only be extended or triggered, never shortened.
     */
    public static void triggerCombat() {
        long now = System.currentTimeMillis();
        lastCombatMs.accumulateAndGet(now, Math::max);
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
