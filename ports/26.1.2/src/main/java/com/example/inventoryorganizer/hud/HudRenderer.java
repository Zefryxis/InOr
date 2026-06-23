package com.example.inventoryorganizer.hud;

import com.example.inventoryorganizer.config.VisualInventoryConfigScreen;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Stateless drawing helpers for the configurable HUD elements. The SAME methods are used by the live
 * in-game HUD ({@code InventoryOrganizerClient}) and by the editor preview ({@code HudLayoutScreen}),
 * so both render identically. All coordinates are element top-left in GUI-scaled pixels.
 */
public final class HudRenderer {

    private HudRenderer() {}

    // --- Element sizes (used for layout, drag hit-testing and on-screen clamping) ---
    public static final int COMBAT_W = 44, COMBAT_H = 20;
    public static final int PAD = 3;                // panel padding around the cell grid
    public static final int MINI_CELL = 16;
    public static final int MINI_W = MINI_CELL * 9 + PAD * 2, MINI_H = MINI_CELL * 3 + PAD * 2;
    public static final int SET_CELL = 18;

    public static int setW(boolean vertical, int count) { return (vertical ? SET_CELL : SET_CELL * count) + PAD * 2; }
    public static int setH(boolean vertical, int count) { return (vertical ? SET_CELL * count : SET_CELL) + PAD * 2; }

    public static final double MIN_SCALE = 0.5, MAX_SCALE = 3.0;

    public static double clampScale(double s) { return Math.max(MIN_SCALE, Math.min(MAX_SCALE, s)); }

    /** Push a translate+scale transform so an element drawn at local (0,0) appears at (x,y) scaled by {@code scale}. */
    public static void pushScale(GuiGraphicsExtractor ctx, int x, int y, double scale) {
        ctx.pose().pushMatrix();
        ctx.pose().translate(x, y);
        ctx.pose().scale((float) scale, (float) scale);
    }

    public static void popScale(GuiGraphicsExtractor ctx) {
        ctx.pose().popMatrix();
    }

    /** Element CENTRE fraction (0..1) → on-screen top-left pixel, clamped to stay fully visible. Storing
     *  the centre (not the top-left) makes "centre" map to 0.5 regardless of element size, so the editor
     *  preview and the live HUD line up exactly even though they use different pixel spans. */
    public static int clampPos(double frac, int screen, int size) {
        int p = (int) Math.round(frac * screen - size / 2.0);
        if (p > screen - size) p = screen - size;
        if (p < 0) p = 0;
        return p;
    }

    // --- Player data collectors (reused by live HUD + editor) ---

    /** The 27 main inventory slots (indices 9..35), hotbar excluded. */
    public static ItemStack[] mainSlots(Player p) {
        ItemStack[] a = new ItemStack[27];
        for (int i = 0; i < 27; i++) a[i] = p.getInventory().getItem(9 + i);
        return a;
    }

    /** Worn set (head, chest, legs, feet) and optionally the offhand as the 5th icon. */
    public static ItemStack[] setSlots(Player p, boolean offhand) {
        if (offhand) {
            return new ItemStack[]{
                p.getItemBySlot(EquipmentSlot.HEAD), p.getItemBySlot(EquipmentSlot.CHEST),
                p.getItemBySlot(EquipmentSlot.LEGS), p.getItemBySlot(EquipmentSlot.FEET),
                p.getItemBySlot(EquipmentSlot.OFFHAND),
            };
        }
        return new ItemStack[]{
            p.getItemBySlot(EquipmentSlot.HEAD), p.getItemBySlot(EquipmentSlot.CHEST),
            p.getItemBySlot(EquipmentSlot.LEGS), p.getItemBySlot(EquipmentSlot.FEET),
        };
    }

    // --- Drawing ---

    /** Combat indicator: red badge + sword icon + countdown label (e.g. "12s"). */
    public static void drawCombat(GuiGraphicsExtractor ctx, Font font, int x, int y, String label) {
        ctx.fill(x, y, x + 20, y + 20, 0xAAFF2222);
        try {
            VisualInventoryConfigScreen.drawItemIcon(ctx, new ItemStack(Holder.direct(Items.IRON_SWORD)), x + 2, y + 2);
        } catch (Throwable ignored) {}
        ctx.text(font, Component.literal(label), x + 22, y + 6, 0xFFFF4444);
    }

    /** Mini-inventory: the 27 main slots in a 9x3 grid, with durability bars and stack counts. */
    public static void drawMiniInv(GuiGraphicsExtractor ctx, Font font, int x, int y, ItemStack[] slots) {
        drawPanel(ctx, x, y, MINI_W, MINI_H);
        int gx = x + PAD, gy = y + PAD;
        for (int i = 0; i < 27; i++) {
            int cx = gx + (i % 9) * MINI_CELL;
            int cy = gy + (i / 9) * MINI_CELL;
            drawCell(ctx, cx, cy, MINI_CELL);
            ItemStack st = (slots != null && i < slots.length) ? slots[i] : ItemStack.EMPTY;
            if (st == null || st.isEmpty()) continue;
            VisualInventoryConfigScreen.drawItemIcon(ctx, st, cx, cy);
            drawDurabilityBar(ctx, st, cx, cy);
            int c = st.getCount();
            if (c > 1) {
                String s = Integer.toString(c);
                ctx.text(font, Component.literal(s), cx + 16 - font.width(s), cy + 8, 0xFFFFFFFF);
            }
        }
    }

    /** Worn set (+ optional offhand): {@code count} icons in a column or row, optional durability bars.
     *  The backing panel resizes to exactly {@code count} cells, so hiding the offhand never leaves a gap. */
    public static void drawSet(GuiGraphicsExtractor ctx, Font font, int x, int y, boolean vertical,
                               boolean durability, int count, ItemStack[] icons) {
        drawPanel(ctx, x, y, setW(vertical, count), setH(vertical, count));
        int gx = x + PAD, gy = y + PAD;
        for (int i = 0; i < count; i++) {
            int cx = vertical ? gx : gx + i * SET_CELL;
            int cy = vertical ? gy + i * SET_CELL : gy;
            drawCell(ctx, cx, cy, SET_CELL);
            ItemStack st = (icons != null && i < icons.length) ? icons[i] : ItemStack.EMPTY;
            if (st == null || st.isEmpty()) continue;
            VisualInventoryConfigScreen.drawItemIcon(ctx, st, cx + 1, cy + 1);
            if (durability) drawDurabilityBar(ctx, st, cx + 1, cy + 1);
        }
    }

    /** Decorated, translucent "glass" backing panel for a HUD element group (soft border + gold corners). */
    public static void drawPanel(GuiGraphicsExtractor ctx, int x, int y, int w, int h) {
        ctx.fill(x, y, x + w, y + h, 0x70101826);            // translucent dark glass
        int b = 0x99566089;                                  // soft blue-grey border
        ctx.fill(x, y, x + w, y + 1, b);
        ctx.fill(x, y + h - 1, x + w, y + h, b);
        ctx.fill(x, y, x + 1, y + h, b);
        ctx.fill(x + w - 1, y, x + w, y + h, b);
        ctx.fill(x + 1, y + 1, x + w - 1, y + 2, 0x22FFFFFF); // inner top highlight (glassy)
        int g = 0xCCFFCC44, t = 4;                            // gold corner ticks
        ctx.fill(x, y, x + t, y + 1, g);                 ctx.fill(x, y, x + 1, y + t, g);
        ctx.fill(x + w - t, y, x + w, y + 1, g);         ctx.fill(x + w - 1, y, x + w, y + t, g);
        ctx.fill(x, y + h - 1, x + t, y + h, g);         ctx.fill(x, y + h - t, x + 1, y + h, g);
        ctx.fill(x + w - t, y + h - 1, x + w, y + h, g); ctx.fill(x + w - 1, y + h - t, x + w, y + h, g);
    }

    /** A subtle translucent slot cell inside an element panel. */
    public static void drawCell(GuiGraphicsExtractor ctx, int x, int y, int size) {
        ctx.fill(x, y, x + size, y + size, 0x33000000);
        int c = 0x18FFFFFF;
        ctx.fill(x, y, x + size, y + 1, c);
        ctx.fill(x, y + size - 1, x + size, y + size, c);
        ctx.fill(x, y, x + 1, y + size, c);
        ctx.fill(x + size - 1, y, x + size, y + size, c);
    }

    /** Vanilla-style durability bar under a 16x16 item icon at (x,y). */
    public static void drawDurabilityBar(GuiGraphicsExtractor ctx, ItemStack stack, int x, int y) {
        if (stack == null || stack.isEmpty()) return;
        int width;
        int color;
        try {
            if (!stack.isBarVisible()) return;
            width = stack.getBarWidth();          // 0..13
            color = stack.getBarColor();          // packed RGB, green→red
        } catch (Throwable t) {
            int max = stack.getMaxDamage();
            if (max <= 0) return;
            int dmg = stack.getDamageValue();
            if (dmg <= 0) return;
            float ratio = Math.max(0f, (float) (max - dmg) / (float) max);
            width = Math.round(13f * ratio);
            color = net.minecraft.util.Mth.hsvToRgb(ratio / 3f, 1f, 1f);
        }
        ctx.fill(x + 2, y + 13, x + 15, y + 15, 0xFF000000);
        ctx.fill(x + 2, y + 13, x + 2 + width, y + 14, 0xFF000000 | color);
    }
}
