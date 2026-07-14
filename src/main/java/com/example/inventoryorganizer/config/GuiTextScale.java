package com.example.inventoryorganizer.config;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

/**
 * Draws slightly larger text once the player's GUI Scale option goes above 2 — a subtle readability
 * boost, NOT a layout change. Only the glyphs get bigger; box sizes, button bounds, wrapping widths
 * and click-hit rects are all computed in the normal (unscaled) logical-pixel space exactly as before,
 * so nothing shifts or overlaps because of this. The bump is intentionally tiny (max +6% at GUI Scale
 * 4, the vanilla maximum) — GUI Scale 4 already renders each logical pixel 4x larger on screen, this
 * just nudges it a little further for legibility without visually "growing" the dialogs.
 */
final class GuiTextScale {
    private GuiTextScale() {}

    private static final float MAX_EXTRA = 0.06f;      // +6% ceiling, applied at GUI Scale 4
    private static final float PER_STEP = 0.02f;        // +2% per whole GUI-Scale step above 2

    /** 1.0 at GUI Scale ≤ 2; grows very slightly above that, capped at 1.0 + MAX_EXTRA. */
    static float factor() {
        double guiScale;
        try {
            guiScale = Minecraft.getInstance().getWindow().getGuiScale();
        } catch (Exception e) {
            return 1f; // defensive: never let a text-scale lookup crash a screen render
        }
        if (guiScale <= 2.0) return 1f;
        float extra = (float) ((guiScale - 2.0) * PER_STEP);
        return 1f + Math.min(extra, MAX_EXTRA);
    }

    /** Left-aligned text at (x, y), scaled about its own origin so layout math elsewhere is untouched. */
    static void text(GuiGraphicsExtractor context, Font font, Component component, int x, int y, int color) {
        float f = factor();
        if (f == 1f) { context.text(font, component, x, y, color); return; }
        context.pose().pushMatrix();
        context.pose().translate(x, y);
        context.pose().scale(f);
        context.text(font, component, 0, 0, color);
        context.pose().popMatrix();
    }

    static void text(GuiGraphicsExtractor context, Font font, String literal, int x, int y, int color) {
        text(context, font, Component.literal(literal), x, y, color);
    }

    /** Horizontally centred text at (cx, y), scaled about its own anchor point. */
    static void centeredText(GuiGraphicsExtractor context, Font font, Component component, int cx, int y, int color) {
        float f = factor();
        if (f == 1f) { context.centeredText(font, component, cx, y, color); return; }
        context.pose().pushMatrix();
        context.pose().translate(cx, y);
        context.pose().scale(f);
        context.centeredText(font, component, 0, 0, color);
        context.pose().popMatrix();
    }

    static void centeredText(GuiGraphicsExtractor context, Font font, String literal, int cx, int y, int color) {
        centeredText(context, font, Component.literal(literal), cx, y, color);
    }
}
