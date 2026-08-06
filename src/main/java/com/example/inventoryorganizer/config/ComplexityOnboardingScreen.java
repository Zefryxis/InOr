package com.example.inventoryorganizer.config;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * First-run onboarding screen: asks the player which complexity mode they want the mod to start in.
 * Shown once, the first time the config UI is opened (see VisualInventoryConfigScreen's deferred
 * onboarding hook). A choice is always recorded — even pressing Esc picks the recommended Simple
 * mode and marks the first run as done, so this screen never appears again unless the config is reset.
 *
 * Modes (see {@link OrganizerConfig}):
 *   Simple   — one curated default system; only slot rules, bundles and basic toggles are shown.
 *   Advanced — Simple + custom groups and slot-tier (ranks) editing.
 *   Expert   — everything customizable (the full, legacy experience).
 *
 * Layout is computed defensively (see {@link #layout()}): the box width is clamped to the current
 * GUI-scaled screen (any GUI Scale / window size, including the vanilla-minimum ~320-wide screen),
 * and each row's height is derived from how many lines its description ACTUALLY wraps to at that
 * width — so a narrow screen that forces more line-wraps grows the rows instead of letting text spill
 * into the next button. The whole stack is then vertically centred and clamped so it never starts
 * above the intro text or runs past the bottom edge.
 */
public class ComplexityOnboardingScreen extends Screen {

    private final Screen next;
    private final OrganizerConfig config;

    private static final int PREFERRED_BOX_W = 300;
    private static final int SCREEN_MARGIN = 16;
    private static final int LINE_H = 11;
    private static final int BTN_H = 20;

    public ComplexityOnboardingScreen(Screen next) {
        super(Component.translatable("inventory-organizer.onboarding.title"));
        this.next = next;
        this.config = OrganizerConfig.get();
    }

    /** Computed, screen-clamped layout: box position/width, and each row's y + height. */
    private static final class Layout {
        int boxX, boxW;
        int introBottomY;
        int[] rowY = new int[3];
        int[] rowH = new int[3];
    }

    private Layout layout() {
        Layout l = new Layout();
        l.boxW = Math.min(PREFERRED_BOX_W, Math.max(140, width - SCREEN_MARGIN * 2));
        l.boxX = Math.max(4, (width - l.boxW) / 2);

        // Title (1 line, y=22) + intro (up to 2 lines starting y=38) — reserve real space for it.
        int introLines = Math.min(2, wrappedLines(
                Component.translatable("inventory-organizer.onboarding.intro").getString(), l.boxW + 40).size());
        l.introBottomY = 38 + Math.max(1, introLines) * LINE_H + 8;

        String[] descKeys = {
                "inventory-organizer.onboarding.simple_desc",
                "inventory-organizer.onboarding.advanced_desc",
                "inventory-organizer.onboarding.expert_desc"
        };
        int[] descLines = new int[3];
        int totalRowsH = 0;
        for (int i = 0; i < 3; i++) {
            descLines[i] = Math.max(1, wrappedLines(Component.translatable(descKeys[i]).getString(), l.boxW).size());
            l.rowH[i] = BTN_H + 2 + descLines[i] * LINE_H + 10; // button + gap + description + trailing gap
            totalRowsH += l.rowH[i];
        }

        // Centre the row stack in the space below the intro; clamp so it never starts above the intro
        // and the last row never runs past the bottom margin (grows upward from center if it must).
        int available = Math.max(0, height - l.introBottomY - SCREEN_MARGIN);
        int startY = l.introBottomY + Math.max(0, (available - totalRowsH) / 2);
        int maxStartY = Math.max(l.introBottomY, height - SCREEN_MARGIN - totalRowsH);
        startY = Math.min(startY, maxStartY);

        int y = startY;
        for (int i = 0; i < 3; i++) {
            l.rowY[i] = y;
            y += l.rowH[i];
        }
        return l;
    }

    @Override
    protected void init() {
        super.init();
        // Counter-zoom (see GuiScaleCap) — at GUI Scale > 2, layout() computes against a bigger virtual
        // width/height (as if GUI Scale were capped at 2) and the whole render pass is shrunk back down
        // at render time (see extractRenderState/mouseClicked/mouseReleased/mouseDragged below).
        this.width = GuiScaleCap.vw(this.width);
        this.height = GuiScaleCap.vh(this.height);
        Layout l = layout();

        addRenderableWidget(StyledButton.styledBuilder(
                Component.translatable("inventory-organizer.onboarding.simple")
                        .append(" ")
                        .append(Component.translatable("inventory-organizer.onboarding.recommended")),
                btn -> choose(OrganizerConfig.MODE_SIMPLE)
        ).bounds(l.boxX, l.rowY[0], l.boxW, BTN_H).build());

        addRenderableWidget(StyledButton.styledBuilder(
                Component.translatable("inventory-organizer.onboarding.advanced"),
                btn -> choose(OrganizerConfig.MODE_ADVANCED)
        ).bounds(l.boxX, l.rowY[1], l.boxW, BTN_H).build());

        addRenderableWidget(StyledButton.styledBuilder(
                Component.translatable("inventory-organizer.onboarding.expert"),
                btn -> choose(OrganizerConfig.MODE_EXPERT)
        ).bounds(l.boxX, l.rowY[2], l.boxW, BTN_H).build());
    }

    private void choose(String mode) {
        config.setComplexityMode(mode);
        config.setFirstRunDone(true);
        config.save();
        Minecraft.getInstance().gui.setScreen(next);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
        float guiScaleCapF = GuiScaleCap.renderFactor();
        if (guiScaleCapF != 1f) {
            mouseX = (int) GuiScaleCap.mx(mouseX);
            mouseY = (int) GuiScaleCap.my(mouseY);
            context.pose().pushMatrix();
            context.pose().scale(guiScaleCapF);
        }
        super.extractRenderState(context, mouseX, mouseY, delta);
        Layout l = layout();

        GuiTextScale.centeredText(context, font, Component.literal("§e").append(
                Component.translatable("inventory-organizer.onboarding.title")), width / 2, 22, 0xFFFFFFFF);
        drawWrappedCentered(context, Component.translatable("inventory-organizer.onboarding.intro").getString(),
                width / 2, 38, l.boxW + 40, 0xFFCCCCCC, 2);

        String[] descKeys = {
                "inventory-organizer.onboarding.simple_desc",
                "inventory-organizer.onboarding.advanced_desc",
                "inventory-organizer.onboarding.expert_desc"
        };
        for (int i = 0; i < 3; i++) {
            drawWrapped(context, Component.translatable(descKeys[i]).getString(),
                    l.boxX, l.rowY[i] + BTN_H + 2, l.boxW, 0xFFAAAAAA, 6);
        }

        if (guiScaleCapF != 1f) context.pose().popMatrix();
    }

    /** Remaps a real (vanilla-delivered) mouse event into virtual space (see GuiScaleCap / init()). */
    private net.minecraft.client.input.MouseButtonEvent toVirtual(net.minecraft.client.input.MouseButtonEvent e) {
        if (GuiScaleCap.renderFactor() == 1f) return e;
        return new net.minecraft.client.input.MouseButtonEvent(
                GuiScaleCap.mx(e.x()), GuiScaleCap.my(e.y()), e.buttonInfo());
    }

    @Override
    public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent click, boolean bl) {
        return super.mouseClicked(toVirtual(click), bl);
    }

    @Override
    public boolean mouseReleased(net.minecraft.client.input.MouseButtonEvent click) {
        return super.mouseReleased(toVirtual(click));
    }

    @Override
    public boolean mouseDragged(net.minecraft.client.input.MouseButtonEvent click, double dragX, double dragY) {
        float f = GuiScaleCap.renderFactor();
        double s = f == 1f ? 1.0 : (1.0 / f);
        return super.mouseDragged(toVirtual(click), dragX * s, dragY * s);
    }

    private void drawWrapped(GuiGraphicsExtractor context, String text, int x, int y, int maxWidth, int color, int maxLines) {
        java.util.List<String> lines = wrappedLines(text, maxWidth);
        int n = Math.min(lines.size(), maxLines);
        for (int i = 0; i < n; i++) {
            GuiTextScale.text(context, font, lines.get(i), x, y + i * LINE_H, color);
        }
    }

    private void drawWrappedCentered(GuiGraphicsExtractor context, String text, int cx, int y, int maxWidth, int color, int maxLines) {
        java.util.List<String> lines = wrappedLines(text, maxWidth);
        int n = Math.min(lines.size(), maxLines);
        for (int i = 0; i < n; i++) {
            GuiTextScale.centeredText(context, font, lines.get(i), cx, y + i * LINE_H, color);
        }
    }

    private java.util.List<String> wrappedLines(String text, int maxWidth) {
        java.util.List<String> out = new java.util.ArrayList<>();
        if (text == null || text.isEmpty()) return out;
        StringBuilder cur = new StringBuilder();
        for (String w : text.split(" ")) {
            String candidate = cur.length() == 0 ? w : cur + " " + w;
            if (font.width(candidate) > maxWidth && cur.length() > 0) {
                out.add(cur.toString());
                cur = new StringBuilder(w);
            } else {
                cur.setLength(0);
                cur.append(candidate);
            }
        }
        if (cur.length() > 0) out.add(cur.toString());
        return out;
    }

    @Override
    public void onClose() {
        // Esc still records a choice — default a brand-new user to the recommended Simple mode so
        // the onboarding doesn't reappear and they land in the easiest experience.
        choose(OrganizerConfig.MODE_SIMPLE);
    }
}
