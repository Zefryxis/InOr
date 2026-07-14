package com.example.inventoryorganizer.config;

import com.example.inventoryorganizer.hud.HudRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/**
 * HUD layout editor. Left = a preview frame at the live window aspect ratio, with a screenshot
 * background; you drag the enabled elements onto it. Right = on/off toggles + element options.
 * Positions are stored as screen fractions in {@link HudSettings}, so they survive resolution changes.
 *
 * <p>Element ids: 0 = combat indicator, 1 = mini-inventory (27 main slots), 2 = worn set + offhand.
 */
public class HudLayoutScreen extends Screen {

    private static final Identifier BG =
        Identifier.fromNamespaceAndPath("inventory-organizer", "textures/gui/hud_editor_bg.png");
    private static final int BG_W = 1918, BG_H = 970;

    private final Screen parent;
    private final OrganizerConfig config;
    private final HudSettings hud;

    private int boxX, boxY, boxW, boxH;   // preview frame
    private int panelX, panelW;           // right control panel

    private int dragElem = -1;            // element currently being dragged (-1 = none)
    private double grabDX, grabDY;        // cursor offset from the element's top-left
    private boolean snapV = false, snapH = false; // snapped to the vertical / horizontal centre line
    private static final int SNAP = 4;   // snap threshold (box pixels)

    public HudLayoutScreen(Screen parent) {
        super(Component.translatable("inventory-organizer.hud.title"));
        this.parent = parent;
        this.config = OrganizerConfig.get();
        this.hud = config.getHud();
    }

    @Override
    protected void init() {
        super.init();
        // Right control panel reserves a fixed strip; the preview frame fills the rest, keeping the
        // live window aspect ratio (this.width/this.height already equals the GUI-scaled screen).
        panelW = 150;
        int margin = 10;
        int topY = 28, bottomReserve = 38;
        panelX = width - panelW - margin;

        int availW = panelX - margin * 2;
        int availH = height - topY - bottomReserve;
        double aspect = (double) this.width / this.height;
        boxW = availW;
        boxH = (int) Math.round(boxW / aspect);
        if (boxH > availH) { boxH = availH; boxW = (int) Math.round(boxH * aspect); }
        boxX = margin;
        boxY = topY + (availH - boxH) / 2;

        rebuild();
    }

    private void rebuild() {
        clearWidgets();
        int x = panelX, w = panelW, y = 54, step = 24;

        addRenderableWidget(StyledButton.styledBuilder(Component.literal(toggleLabel("combat", hud.combatEnabled)), b -> {
            hud.combatEnabled = !hud.combatEnabled; config.save(); b.setMessage(Component.literal(toggleLabel("combat", hud.combatEnabled)));
        }).bounds(x, y, w, 20).build());
        y += step;

        addRenderableWidget(StyledButton.styledBuilder(Component.literal(toggleLabel("inv", hud.invEnabled)), b -> {
            hud.invEnabled = !hud.invEnabled; config.save(); b.setMessage(Component.literal(toggleLabel("inv", hud.invEnabled)));
        }).bounds(x, y, w, 20).build());
        y += step;

        addRenderableWidget(StyledButton.styledBuilder(Component.literal(toggleLabel("set", hud.setEnabled)), b -> {
            hud.setEnabled = !hud.setEnabled; config.save(); rebuild();   // rebuild to show/hide sub-options
        }).bounds(x, y, w, 20).build());
        y += step;

        // Set sub-options only when the set is enabled.
        if (hud.setEnabled) {
            addRenderableWidget(StyledButton.styledBuilder(Component.literal(orientationLabel()), b -> {
                hud.setVertical = !hud.setVertical; config.save(); b.setMessage(Component.literal(orientationLabel()));
            }).bounds(x, y, w, 20).build());
            y += step;

            addRenderableWidget(StyledButton.styledBuilder(Component.literal(toggleLabel("durability", hud.setDurability)), b -> {
                hud.setDurability = !hud.setDurability; config.save(); b.setMessage(Component.literal(toggleLabel("durability", hud.setDurability)));
            }).bounds(x, y, w, 20).build());
            y += step;

            addRenderableWidget(StyledButton.styledBuilder(Component.literal(toggleLabel("offhand", hud.setOffhand)), b -> {
                hud.setOffhand = !hud.setOffhand; config.save(); b.setMessage(Component.literal(toggleLabel("offhand", hud.setOffhand)));
            }).bounds(x, y, w, 20).build());
            y += step;
        }

        addRenderableWidget(StyledButton.styledBuilder(Component.translatable("inventory-organizer.button.save"),
            b -> Minecraft.getInstance().setScreen(parent)).bounds(x, height - 28, w, 20).build());
    }

    private String toggleLabel(String key, boolean on) {
        return Component.translatable("inventory-organizer.hud." + key).getString() + (on ? " §a✔" : " §7✘");
    }

    private String orientationLabel() {
        return Component.translatable(hud.setVertical
            ? "inventory-organizer.hud.orientation_vertical"
            : "inventory-organizer.hud.orientation_horizontal").getString();
    }

    // --- Per-element accessors (0 = combat, 1 = inv, 2 = set) ---
    private boolean enabled(int e) { return e == 0 ? hud.combatEnabled : e == 1 ? hud.invEnabled : hud.setEnabled; }
    private int setCount() { return hud.setOffhand ? 5 : 4; }
    private int baseW(int e) { return e == 0 ? HudRenderer.COMBAT_W : e == 1 ? HudRenderer.MINI_W : HudRenderer.setW(hud.setVertical, setCount()); }
    private int baseH(int e) { return e == 0 ? HudRenderer.COMBAT_H : e == 1 ? HudRenderer.MINI_H : HudRenderer.setH(hud.setVertical, setCount()); }
    private double getScale(int e) { return HudRenderer.clampScale(e == 0 ? hud.combatScale : e == 1 ? hud.invScale : hud.setScale); }
    private void setScale(int e, double v) { v = HudRenderer.clampScale(v); if (e == 0) hud.combatScale = v; else if (e == 1) hud.invScale = v; else hud.setScale = v; }
    private int elemW(int e) { return Math.max(1, (int) Math.round(baseW(e) * getScale(e))); }
    private int elemH(int e) { return Math.max(1, (int) Math.round(baseH(e) * getScale(e))); }
    private double getFx(int e) { return e == 0 ? hud.combatX : e == 1 ? hud.invX : hud.setX; }
    private double getFy(int e) { return e == 0 ? hud.combatY : e == 1 ? hud.invY : hud.setY; }
    private void setFx(int e, double v) { if (e == 0) hud.combatX = v; else if (e == 1) hud.invX = v; else hud.setX = v; }
    private void setFy(int e, double v) { if (e == 0) hud.combatY = v; else if (e == 1) hud.invY = v; else hud.setY = v; }

    /** Element top-left inside the preview box from its stored CENTRE fraction (clamped to the frame). */
    private int boxPx(double frac, int span, int size) {
        int p = (int) Math.round(frac * span) - size / 2;
        if (p > span - size) p = span - size;
        if (p < 0) p = 0;
        return p;
    }
    private int elemX(int e) { return boxX + boxPx(getFx(e), boxW, elemW(e)); }
    private int elemY(int e) { return boxY + boxPx(getFy(e), boxH, elemH(e)); }

    @Override
    public void extractRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
        // Backdrop + top title bar.
        context.fill(0, 0, width, height, 0xFF12121C);
        context.fill(0, 0, width, 24, 0xFF1A1A2E);
        context.fill(0, 24, width, 25, 0xFF55557A);
        context.fill(0, 25, width, 26, 0xFF0A0A12);
        context.centeredText(font, Component.literal("§e§l⚙ ").append(Component.translatable("inventory-organizer.hud.title")), width / 2, 8, 0xFFFFFFFF);

        // --- Preview frame ---
        context.text(font, Component.literal("§b").append(Component.translatable("inventory-organizer.hud.preview")), boxX, boxY - 11, 0xFFFFFFFF);
        context.fill(boxX - 3, boxY - 3, boxX + boxW + 3, boxY + boxH + 3, 0xFF2A2A44);   // outer
        context.fill(boxX - 2, boxY - 2, boxX + boxW + 2, boxY + boxH + 2, 0xFF55557A);   // bevel
        try {
            context.blit(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED, BG,
                boxX, boxY, 0f, 0f, boxW, boxH, BG_W, BG_H, BG_W, BG_H);
        } catch (Throwable t) {
            context.fill(boxX, boxY, boxX + boxW, boxY + boxH, 0xFF1A1A28);
        }
        context.fill(boxX, boxY, boxX + boxW, boxY + boxH, 0x18000000);                   // subtle dim for legibility
        drawCorners(context, boxX - 2, boxY - 2, boxW + 4, boxH + 4, 0xFFFFCC44);

        // Centre-line guides while dragging (only the axis we're snapped to).
        if (dragElem >= 0) {
            int gc = 0xFF44FFCC;
            if (snapV) context.fill(boxX + boxW / 2, boxY, boxX + boxW / 2 + 1, boxY + boxH, gc);
            if (snapH) context.fill(boxX, boxY + boxH / 2, boxX + boxW, boxY + boxH / 2 + 1, gc);
        }

        Player player = Minecraft.getInstance().player;
        // Draw each ENABLED element at its position, scaled (combat shows a placeholder here).
        for (int e = 0; e < 3; e++) {
            if (!enabled(e)) continue;
            int ex = elemX(e), ey = elemY(e);
            double sc = getScale(e);
            HudRenderer.pushScale(context, ex, ey, sc);
            if (e == 0) {
                HudRenderer.drawCombat(context, font, 0, 0, "20s");
            } else if (e == 1) {
                HudRenderer.drawMiniInv(context, font, 0, 0, player != null ? HudRenderer.mainSlots(player) : null);
            } else {
                HudRenderer.drawSet(context, font, 0, 0, hud.setVertical, hud.setDurability, setCount(),
                    player != null ? HudRenderer.setSlots(player, hud.setOffhand) : null);
            }
            HudRenderer.popScale(context);
            // Highlight + name tag for the element under the cursor (or being dragged).
            boolean hot = dragElem == e || (mouseX >= ex && mouseX < ex + elemW(e) && mouseY >= ey && mouseY < ey + elemH(e));
            if (hot) {
                int c = 0xFFFFCC44;
                context.fill(ex - 1, ey - 1, ex + elemW(e) + 1, ey, c);
                context.fill(ex - 1, ey + elemH(e), ex + elemW(e) + 1, ey + elemH(e) + 1, c);
                context.fill(ex - 1, ey, ex, ey + elemH(e), c);
                context.fill(ex + elemW(e), ey, ex + elemW(e) + 1, ey + elemH(e), c);
                String name = elemName(e) + " §7" + Math.round(getScale(e) * 100) + "%";
                int tw = font.width(name) + 6, tx = ex, ty = ey - 12;
                if (ty < boxY) ty = ey + elemH(e) + 2;
                if (tx + tw > boxX + boxW) tx = boxX + boxW - tw;
                if (tx < boxX) tx = boxX;
                context.fill(tx, ty, tx + tw, ty + 11, 0xE0000000);
                context.text(font, Component.literal("§e" + name), tx + 3, ty + 2, 0xFFFFFFFF);
            }
        }

        // Hints under the frame.
        context.text(font, Component.literal("§7").append(Component.translatable("inventory-organizer.hud.hint")), boxX, boxY + boxH + 7, 0xFFAAAAAA);
        context.text(font, Component.literal("§8").append(Component.translatable("inventory-organizer.hud.resize")), boxX, boxY + boxH + 18, 0xFF888888);

        // --- Right control panel ---
        int px0 = panelX - 8, py0 = 32, px1 = panelX + panelW + 8, py1 = height - 34;
        context.fill(px0, py0, px1, py1, 0xF0161622);
        context.fill(px0, py0, px1, py0 + 1, 0xFF55557A);
        context.fill(px0, py1 - 1, px1, py1, 0xFF55557A);
        context.fill(px0, py0, px0 + 1, py1, 0xFF55557A);
        context.fill(px1 - 1, py0, px1, py1, 0xFF55557A);
        context.centeredText(font, Component.literal("§b§l").append(Component.translatable("inventory-organizer.hud.elements")), (px0 + px1) / 2, 38, 0xFFFFFFFF);
        context.fill(px0 + 6, 50, px1 - 6, 51, 0xFF3A3A5A);
        // Divider before the set sub-options when they are shown (after the 3rd toggle at y=54+2*24=102).
        if (hud.setEnabled) context.fill(px0 + 6, 127, px1 - 6, 128, 0xFF2A2A44);

        super.extractRenderState(context, mouseX, mouseY, delta);
    }

    /** Gold L-shaped accents at the four corners of a rectangle. */
    private void drawCorners(GuiGraphicsExtractor ctx, int x, int y, int w, int h, int color) {
        int len = 7, t = 1;
        // top-left
        ctx.fill(x, y, x + len, y + t, color);            ctx.fill(x, y, x + t, y + len, color);
        // top-right
        ctx.fill(x + w - len, y, x + w, y + t, color);    ctx.fill(x + w - t, y, x + w, y + len, color);
        // bottom-left
        ctx.fill(x, y + h - t, x + len, y + h, color);    ctx.fill(x, y + h - len, x + t, y + h, color);
        // bottom-right
        ctx.fill(x + w - len, y + h - t, x + w, y + h, color); ctx.fill(x + w - t, y + h - len, x + w, y + h, color);
    }

    private String elemName(int e) {
        String key = e == 0 ? "combat" : e == 1 ? "inv" : "set";
        return Component.translatable("inventory-organizer.hud." + key).getString();
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent click, boolean bl) {
        if (super.mouseClicked(click, bl)) return true;
        if (click.button() == 0) {
            double mx = click.x(), my = click.y();
            // Topmost enabled element under the cursor becomes the drag target.
            for (int e = 2; e >= 0; e--) {
                if (!enabled(e)) continue;
                int ex = elemX(e), ey = elemY(e);
                if (mx >= ex && mx < ex + elemW(e) && my >= ey && my < ey + elemH(e)) {
                    dragElem = e; grabDX = mx - ex; grabDY = my - ey;
                    snapV = snapH = false;
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent click, double deltaX, double deltaY) {
        if (dragElem >= 0 && click.button() == 0) {
            int e = dragElem;
            int ew = elemW(e), eh = elemH(e);
            // Desired element CENTRE in box pixels (cursor minus grab offset, plus half the element).
            double cxPx = click.x() - boxX - grabDX + ew / 2.0;
            double cyPx = click.y() - boxY - grabDY + eh / 2.0;

            // Snap to the box centre lines: the element CENTRE, or its left/right (top/bottom) edge, lands
            // on the line. Approaching from a side the near edge snaps first, then (dragging further) the
            // centre — and free otherwise.
            snapV = snapH = false;
            double cx = boxW / 2.0, cy = boxH / 2.0;
            double bestX = cxPx, bestDX = SNAP + 1;
            for (double cand : new double[]{cx, cx + ew / 2.0, cx - ew / 2.0}) {
                double d = Math.abs(cxPx - cand);
                if (d < bestDX) { bestDX = d; bestX = cand; }
            }
            if (bestDX <= SNAP) { cxPx = bestX; snapV = true; }
            double bestY = cyPx, bestDY = SNAP + 1;
            for (double cand : new double[]{cy, cy + eh / 2.0, cy - eh / 2.0}) {
                double d = Math.abs(cyPx - cand);
                if (d < bestDY) { bestDY = d; bestY = cand; }
            }
            if (bestDY <= SNAP) { cyPx = bestY; snapH = true; }

            // Store the centre fraction, clamped so the element stays fully inside the frame.
            double halfFx = ew / 2.0 / boxW, halfFy = eh / 2.0 / boxH;
            setFx(e, Math.max(halfFx, Math.min(cxPx / boxW, 1 - halfFx)));
            setFy(e, Math.max(halfFy, Math.min(cyPx / boxH, 1 - halfFy)));
            return true;
        }
        return super.mouseDragged(click, deltaX, deltaY);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        // Resize the hovered element with the scroll wheel (0.1 per notch).
        for (int e = 2; e >= 0; e--) {
            if (!enabled(e)) continue;
            int ex = elemX(e), ey = elemY(e);
            if (mouseX >= ex && mouseX < ex + elemW(e) && mouseY >= ey && mouseY < ey + elemH(e)) {
                setScale(e, getScale(e) + (verticalAmount > 0 ? 0.1 : -0.1));
                config.save();
                return true;
            }
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent click) {
        if (dragElem >= 0 && click.button() == 0) {
            dragElem = -1;
            snapV = snapH = false;
            config.save();
            return true;
        }
        return super.mouseReleased(click);
    }

    @Override
    public void onClose() {
        config.save();
        Minecraft.getInstance().setScreen(parent);
    }
}
