package com.example.inventoryorganizer.config;

import net.minecraft.client.Minecraft;

/**
 * Counter-zooms our own screens so they always have AT LEAST as much logical layout space as GUI
 * Scale 2 would give, no matter how high the player's actual GUI Scale is set. Several of our screens
 * (Special Settings, the Custom Group editor, the main slot-config screen) pack a lot of controls onto
 * one page; trying to reflow every one of them to survive an arbitrarily small logical width/height was
 * attempted repeatedly and never held up (buttons/panels still ended up overlapping at high GUI Scale
 * or a small window). Instead of chasing that per-screen, this makes the "not enough room" case not
 * happen at all for our UI: at GUI Scale 3/4, our screens compute their layout as if the scale were
 * capped at 2 (a bigger virtual canvas than the real one), then the whole thing is rendered shrunk back
 * down to fit the real (smaller) screen, and incoming mouse coordinates are converted the other way so
 * clicks still land on the right widget. At GUI Scale 1-2 this is a no-op — nothing changes.
 *
 * <p>Purely additive per-screen: a screen has to explicitly call into this (compute {@link #vw}/{@link
 * #vh} instead of using {@code width}/{@code height} directly for layout, wrap its render body in
 * {@link #renderFactor()}, and remap incoming mouse coordinates via {@link #mx}/{@link #my} before
 * delegating to {@code super}). Vanilla screens, other mods' screens, and any of our own screens that
 * don't opt in are completely unaffected.
 */
public final class GuiScaleCap {
    private GuiScaleCap() {}

    private static final double CAP = 2.0;

    /** The player's real, currently-configured GUI Scale (1/2/3/4/...). */
    private static double realScale() {
        try {
            return Minecraft.getInstance().getWindow().getGuiScale();
        } catch (Throwable t) {
            return CAP;
        }
    }

    /** Factor to shrink virtual-space rendering by so it fits the real (smaller, more cramped at high
     *  GUI Scale) screen — 1.0 (no-op) whenever the real GUI Scale is already &lt;= 2. */
    public static float renderFactor() {
        double s = realScale();
        return s <= CAP ? 1f : (float) (CAP / s);
    }

    /** Virtual width for layout math: identical to the real {@code Screen.width} at GUI Scale &lt;= 2,
     *  otherwise the (larger) width the screen would have at GUI Scale 2. */
    public static int vw(int realWidth) {
        double s = realScale();
        return s <= CAP ? realWidth : (int) Math.round(realWidth * (s / CAP));
    }

    /** Virtual height — see {@link #vw}. */
    public static int vh(int realHeight) {
        double s = realScale();
        return s <= CAP ? realHeight : (int) Math.round(realHeight * (s / CAP));
    }

    /** Converts a REAL mouse X (as vanilla mouse events deliver it) into virtual-space, matching
     *  whatever coordinate space widget bounds were computed in via {@link #vw}/{@link #vh}. */
    public static double mx(double realMouseX) {
        double s = realScale();
        return s <= CAP ? realMouseX : realMouseX * (s / CAP);
    }

    /** See {@link #mx}. */
    public static double my(double realMouseY) {
        double s = realScale();
        return s <= CAP ? realMouseY : realMouseY * (s / CAP);
    }
}
