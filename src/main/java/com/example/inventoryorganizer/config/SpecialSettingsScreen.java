package com.example.inventoryorganizer.config;

import com.example.inventoryorganizer.ServerEnvironment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

/**
 * Special Settings sub-screen. Individual toggles (still independently editable; the master
 * quick-switch on the slot config screen is just a shortcut that flips them together):
 *   - Keybind mode (Inventory only / Free / Off)
 *   - Death auto-sort (On / Off)
 *   - Sort keybind action (Smart / OI only / OST only / Both)
 *   - Free-mode server whitelist
 */
public class SpecialSettingsScreen extends Screen {

    private final Screen parent;
    private final OrganizerConfig config;

    private static final int COL_W = 195;
    private static final int COL_GAP = 16;
    private static final int LINE_H = 11;

    private boolean showHelp = false;
    private boolean showWhitelistConfirm = false;
    private boolean showModeConfirm = false;
    private String pendingMode = null;

    public SpecialSettingsScreen(Screen parent) {
        super(Component.literal("Special Settings"));
        this.parent = parent;
        this.config = OrganizerConfig.get();
    }

    private int leftX() { return (width - (COL_W * 2 + COL_GAP)) / 2; }
    private int rightX() { return leftX() + COL_W + COL_GAP; }

    @Override
    protected void init() {
        super.init();
        rebuild();
    }

    private void rebuild() {
        clearWidgets();
        int leftX = leftX(), rightX = rightX();

        // Row 1 buttons (y=42): keybind mode (left), sort action (right)
        addRenderableWidget(StyledButton.styledBuilder(Component.literal(keybindModeLabel()), btn -> {
            String cur = config.getKeybindMode();
            String next = "inventory_only".equals(cur) ? "free" : "free".equals(cur) ? "disabled" : "inventory_only";
            config.setKeybindMode(next);
            config.save();
            btn.setMessage(Component.literal(keybindModeLabel()));
        }).bounds(leftX, 42, COL_W, 20).build());

        addRenderableWidget(StyledButton.styledBuilder(Component.literal(sortActionLabel()), btn -> {
            String cur = config.getSortAction();
            String next = "smart".equals(cur) ? "oi_only" : "oi_only".equals(cur) ? "ost_only"
                        : "ost_only".equals(cur) ? "both" : "smart";
            config.setSortAction(next);
            config.save();
            btn.setMessage(Component.literal(sortActionLabel()));
        }).bounds(rightX, 42, COL_W, 20).build());

        // Row 2 buttons (y=104): death auto-sort (left), trash mode (right)
        addRenderableWidget(StyledButton.styledBuilder(Component.literal(deathLabel()), btn -> {
            config.setDeathSortEnabled(!config.isDeathSortEnabled());
            config.save();
            btn.setMessage(Component.literal(deathLabel()));
        }).bounds(leftX, 104, COL_W, 20).build());

        addRenderableWidget(StyledButton.styledBuilder(Component.literal(trashModeLabel()), btn -> {
            config.setTrashOverflowOnly(!config.isTrashOverflowOnly());
            config.save();
            btn.setMessage(Component.literal(trashModeLabel()));
        }).bounds(rightX, 104, COL_W, 20).build());

        // Whitelist feature toggle (hidden behind an explicit "use at your own risk" opt-in, y=170).
        boolean wlOn = config.isWhitelistEnabled();
        addRenderableWidget(StyledButton.styledBuilder(Component.literal(whitelistToggleLabel()), btn -> {
            if (config.isWhitelistEnabled()) {
                config.setWhitelistEnabled(false);
                config.save();
                rebuild();
            } else {
                // Turning it ON requires acknowledging the warning first.
                showWhitelistConfirm = true;
                rebuild();
            }
        }).bounds(leftX, 170, COL_W, 20).build());

        // The rest of the whitelist UI only exists once the feature is enabled.
        if (wlOn) {
            Minecraft mc = Minecraft.getInstance();
            String host = ServerEnvironment.serverHost(mc);
            if (host != null && !host.isEmpty() && !mc.hasSingleplayerServer()) {
                final String h = host;
                boolean already = config.getServerWhitelist().contains(h);
                addRenderableWidget(StyledButton.styledBuilder(
                        Component.literal(already ? "This server is whitelisted" : "Add this server to whitelist"),
                        btn -> { config.addServerWhitelist(h); config.save(); rebuild(); }
                ).bounds(rightX, 170, COL_W, 20).build());
            }

            // Whitelist entries with Remove buttons (y=196+).
            java.util.List<String> wl = config.getServerWhitelist();
            for (int i = 0; i < wl.size() && i < 4; i++) {
                final String entry = wl.get(i);
                addRenderableWidget(StyledButton.styledBuilder(Component.literal("Remove"),
                        btn -> { config.removeServerWhitelist(entry); config.save(); rebuild(); }
                ).bounds(rightX + COL_W - 60, 196 + i * 20, 60, 18).build());
            }
        }

        // The whitelist confirm dialog (Yes/No) is drawn + click-handled manually (see
        // drawWhitelistConfirm / mouseClicked) so its buttons render ON TOP of the dimming overlay.

        // Complexity mode switcher — cycling it doesn't apply immediately; it opens a confirm dialog
        // that explains lowering complexity only HIDES advanced options (nothing is deleted).
        addRenderableWidget(StyledButton.styledBuilder(Component.literal(complexityLabel()), btn -> {
            pendingMode = nextComplexityMode(config.getComplexityMode());
            showModeConfirm = true;
            rebuild();
        }).bounds(leftX, 232, COL_W, 20).build());

        addRenderableWidget(StyledButton.styledBuilder(Component.literal("?"), btn -> {
            showHelp = !showHelp;
        }).bounds(width - 24, 4, 20, 18).build());

        // Auto-refill master switch. Shares the same config flag as the "Toggle Auto-Refill" keybind,
        // so the two stay perfectly in sync. (Crafting now always pulls from nearby chests — the old
        // craft-source toggle was removed, chests are the source by design.)
        addRenderableWidget(StyledButton.styledBuilder(Component.literal(autoRefillLabel()), btn -> {
            config.setAutoRefillEnabled(!config.isAutoRefillEnabled());
            config.save();
            btn.setMessage(Component.literal(autoRefillLabel()));
        }).bounds(width / 2 - 74, height - 76, 148, 20).build());

        addRenderableWidget(StyledButton.styledBuilder(Component.translatable("inventory-organizer.button.hud"),
                btn -> Minecraft.getInstance().gui.setScreen(new HudLayoutScreen(this))
        ).bounds(width / 2 - 50, height - 52, 100, 20).build());

        addRenderableWidget(StyledButton.styledBuilder(Component.literal("Back"),
                btn -> Minecraft.getInstance().gui.setScreen(parent)
        ).bounds(width / 2 - 50, height - 28, 100, 20).build());
    }

    private String whitelistToggleLabel() {
        return config.isWhitelistEnabled() ? "Whitelist: ON ⚠" : "Whitelist: OFF";
    }

    private String trashModeLabel() {
        return config.isTrashOverflowOnly() ? "Trash: Keep 1 stack" : "Trash: Drop all";
    }

    private String autoRefillLabel() {
        return Component.translatable(config.isAutoRefillEnabled()
                ? "inventory-organizer.refill.on"
                : "inventory-organizer.refill.off").getString();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
        super.extractRenderState(context, mouseX, mouseY, delta);
        int leftX = leftX(), rightX = rightX();

        GuiTextScale.centeredText(context, font, "§eSpecial Settings", width / 2, 14, 0xFFFFFFFF);

        // Row 1 labels + descriptions
        GuiTextScale.text(context, font, "§bKeybind mode", leftX, 30, 0xFFFFFFFF);
        GuiTextScale.text(context, font, "§bSort keybind action", rightX, 30, 0xFFFFFFFF);
        drawWrapped(context, keybindModeDesc(), leftX, 64, COL_W, keybindDescColor(), 3);
        drawWrapped(context, sortActionDescription(), rightX, 64, COL_W, 0xFFAAAAAA, 3);

        // Row 2 labels + descriptions
        GuiTextScale.text(context, font, "§bDeath auto-sort", leftX, 92, 0xFFFFFFFF);
        GuiTextScale.text(context, font, "§bTrash mode", rightX, 92, 0xFFFFFFFF);
        drawWrapped(context, config.isTrashOverflowOnly()
                ? "Keeps up to one stack of each trashed item; only the excess is dropped."
                : "Drops every item matching the Trash list as soon as you pick it up.",
                rightX, 126, COL_W, 0xFFAAAAAA, 3);
        drawWrapped(context, deathDesc(), leftX, 126, COL_W, config.isDeathSortEnabled() ? 0xFFFF8844 : 0xFFAAAAAA, 3);

        // Whitelist label
        GuiTextScale.text(context, font, "§bFree-mode server whitelist", leftX, 158, 0xFFFFFFFF);

        if (!config.isWhitelistEnabled()) {
            // Feature hidden by default — show only the explanatory hint next to the OFF toggle.
            drawWrapped(context, "Advanced opt-in feature, hidden by default. Enable it to whitelist servers for Free mode — at your own risk.",
                    rightX, 172, COL_W, 0xFFAAAAAA, 3);
        } else {
            java.util.List<String> wl = config.getServerWhitelist();
            if (wl.isEmpty()) {
                GuiTextScale.text(context, font, "§7No servers whitelisted yet.", leftX, 196, 0xFFAAAAAA);
            } else {
                for (int i = 0; i < wl.size() && i < 4; i++) {
                    GuiTextScale.text(context, font, "§f" + wl.get(i), leftX, 196 + i * 20 + 5, 0xFFFFFFFF);
                }
            }
        }

        // Environment status near the bottom.
        Minecraft mc = Minecraft.getInstance();
        String host = ServerEnvironment.serverHost(mc);
        String envLine;
        if (mc.hasSingleplayerServer()) {
            envLine = "§7Here: §aSingle player — Free allowed";
        } else if (ServerEnvironment.isPrivateEnvironment()) {
            envLine = "§7Here: §a" + (host == null ? "private" : host) + " — Free allowed";
        } else {
            envLine = "§7Here: §c" + (host == null ? "unknown" : host) + " — public, Server Friendly forced";
        }
        // Centred just ABOVE the Craft-source button (height-76) so it never overlaps the HUD button row.
        GuiTextScale.centeredText(context, font, Component.literal(envLine), width / 2, height - 90, 0xFFFFFFFF);

        // Complexity mode label + description (left column, its own row at y=232).
        GuiTextScale.text(context, font, Component.literal("§b").append(
                Component.translatable("inventory-organizer.special.complexity_label")), leftX, 220, 0xFFFFFFFF);
        drawWrapped(context, complexityDesc(), rightX, 234, COL_W, 0xFFAAAAAA, 3);

        if (showHelp) drawHelpOverlay(context);
        if (showWhitelistConfirm) drawWhitelistConfirm(context, mouseX, mouseY);
        if (showModeConfirm) drawModeConfirm(context, mouseX, mouseY);
    }

    // Larger, richer confirm box for the complexity switch — it explains WHY the levels exist and what
    // each one is, so the choice feels intentional rather than a random toggle.
    // These are the PREFERRED size at a normal window; actual on-screen size is clamped defensively
    // (see modeBoxDims()) so it never exceeds the current GUI-scaled screen, at any GUI Scale or
    // window size — including the vanilla-minimum 320-wide scaled screen at GUI Scale 1.
    private static final int MODE_BOX_W = 440;
    private static final int MODE_SCREEN_MARGIN = 16; // never touch the screen edge, any scale

    /**
     * Computes the actual on-screen box rect for the mode-confirm dialog, clamped to fit the current
     * screen and grown tall enough to fit every wrapped text block (so shrinking the width at a small
     * GUI Scale — which forces more line-wraps — can never push text past the box border or into the
     * Yes/No buttons). Returns {boxX, boxY, boxW, boxH}.
     */
    private int[] modeBoxDims() {
        int boxW = Math.min(MODE_BOX_W, Math.max(160, width - MODE_SCREEN_MARGIN * 2));
        int tw = boxW - 28;

        // Two-pass: measure the wrapped line count each block will actually need at this width,
        // so the box grows instead of clipping/overlapping when text wraps more at small widths.
        int contentLines = 0;
        contentLines += wrappedLines(Component.translatable("inventory-organizer.special.mode_confirm_intro").getString(), tw).size();
        contentLines += wrappedLines(Component.translatable("inventory-organizer.special.mode_confirm_simple").getString(), tw).size();
        contentLines += wrappedLines(Component.translatable("inventory-organizer.special.mode_confirm_advanced").getString(), tw).size();
        contentLines += wrappedLines(Component.translatable("inventory-organizer.special.mode_confirm_expert").getString(), tw).size();
        String footerText = Component.translatable("inventory-organizer.special.mode_confirm_footer",
                complexityModeName(pendingMode)).getString();
        contentLines += wrappedLines(footerText, tw).size();

        // top title(28) + content lines + two 5px gaps + bottom button row & padding(38)
        int desiredH = 28 + contentLines * LINE_H + 10 + 38;
        int boxH = Math.min(Math.max(140, desiredH), Math.max(120, height - MODE_SCREEN_MARGIN * 2));

        int boxX = Math.max(4, (width - boxW) / 2);
        int boxY = Math.max(4, (height - boxH) / 2);
        return new int[]{boxX, boxY, boxW, boxH};
    }

    /** Yes/No rects for the mode-confirm box — computed identically in render + click handling. */
    private int[][] modeConfirmButtonRects() {
        int[] d = modeBoxDims();
        int boxX = d[0], boxY = d[1], boxW = d[2], boxH = d[3];
        // Buttons shrink to fit narrow boxes instead of overflowing past the box edges.
        int bw = Math.min(120, (boxW - 40) / 2), bh = 20, by = boxY + boxH - 28;
        return new int[][]{
            { boxX + boxW / 2 - bw - 8, by, bw, bh },  // Yes
            { boxX + boxW / 2 + 8, by, bw, bh }         // No
        };
    }

    private void drawModeConfirm(GuiGraphicsExtractor context, int mouseX, int mouseY) {
        int[] d = modeBoxDims();
        int boxX = d[0], boxY = d[1], boxW = d[2], boxH = d[3];
        context.fill(0, 0, width, height, 0x99000000);
        context.fill(boxX, boxY, boxX + boxW, boxY + boxH, 0xF01A1A1A);
        context.horizontalLine(boxX, boxX + boxW - 1, boxY, 0xFFFFCC44);
        context.horizontalLine(boxX, boxX + boxW - 1, boxY + boxH - 1, 0xFFFFCC44);
        context.verticalLine(boxX, boxY, boxY + boxH - 1, 0xFFFFCC44);
        context.verticalLine(boxX + boxW - 1, boxY, boxY + boxH - 1, 0xFFFFCC44);
        GuiTextScale.centeredText(context, font, Component.literal("§e⚠ ").append(
                Component.translatable("inventory-organizer.special.mode_confirm_title")), boxX + boxW / 2, boxY + 10, 0xFFFFFFFF);

        int tx = boxX + 14, tw = boxW - 28;
        int y = boxY + 28;
        // "These levels aren't arbitrary…" — the framing.
        y = drawBlock(context, Component.translatable("inventory-organizer.special.mode_confirm_intro").getString(),
                tx, y, tw, 0xFFCCCCCC);
        y += 5;
        // Each level, its coloured name baked into the localized string.
        y = drawBlock(context, Component.translatable("inventory-organizer.special.mode_confirm_simple").getString(), tx, y, tw, 0xFFBBBBBB);
        y = drawBlock(context, Component.translatable("inventory-organizer.special.mode_confirm_advanced").getString(), tx, y, tw, 0xFFBBBBBB);
        y = drawBlock(context, Component.translatable("inventory-organizer.special.mode_confirm_expert").getString(), tx, y, tw, 0xFFBBBBBB);
        y += 5;
        // The target + the "only hides, never deletes" reassurance.
        drawBlock(context, Component.translatable("inventory-organizer.special.mode_confirm_footer",
                complexityModeName(pendingMode)).getString(), tx, y, tw, 0xFFFFDD88);

        int[][] r = modeConfirmButtonRects();
        drawDialogButton(context, r[0], Component.translatable("inventory-organizer.special.wl_yes").getString(), 0xFF55FF55, mouseX, mouseY);
        drawDialogButton(context, r[1], Component.translatable("inventory-organizer.special.wl_no").getString(), 0xFFFF5555, mouseX, mouseY);
    }

    /** Draws a word-wrapped block starting at y; returns the y just below the last line. */
    private int drawBlock(GuiGraphicsExtractor context, String text, int x, int y, int maxWidth, int color) {
        for (String line : wrappedLines(text, maxWidth)) {
            GuiTextScale.text(context, font, line, x, y, color);
            y += LINE_H;
        }
        return y;
    }

    // ---- Complexity mode helpers ----

    private static String nextComplexityMode(String cur) {
        if (OrganizerConfig.MODE_SIMPLE.equals(cur)) return OrganizerConfig.MODE_ADVANCED;
        if (OrganizerConfig.MODE_ADVANCED.equals(cur)) return OrganizerConfig.MODE_EXPERT;
        return OrganizerConfig.MODE_SIMPLE;
    }

    private String complexityModeName(String mode) {
        String key = OrganizerConfig.MODE_SIMPLE.equals(mode) ? "inventory-organizer.special.complexity_simple"
                   : OrganizerConfig.MODE_ADVANCED.equals(mode) ? "inventory-organizer.special.complexity_advanced"
                   : "inventory-organizer.special.complexity_expert";
        return Component.translatable(key).getString();
    }

    private String complexityLabel() {
        return Component.translatable("inventory-organizer.special.complexity_label").getString()
                + ": " + complexityModeName(config.getComplexityMode());
    }

    private String complexityDesc() {
        String mode = config.getComplexityMode();
        if (OrganizerConfig.MODE_SIMPLE.equals(mode))
            return "Only slot rules, bundles and basic toggles are shown. Ranks and groups use curated defaults.";
        if (OrganizerConfig.MODE_ADVANCED.equals(mode))
            return "Adds custom groups and slot-tier (ranks) editing. Deep sort tuning stays on defaults.";
        return "Everything is customizable — the full experience.";
    }

    /**
     * Clamps a preferred dialog size to the current screen (any GUI Scale/window size) and grows the
     * height to fit the given body text wrapped at the clamped width, so small scales that force extra
     * line-wraps never push text past the box or into the Yes/No row. Returns {boxX, boxY, boxW, boxH}.
     */
    private int[] clampedDialogDims(int preferredW, int preferredH, String... bodyTexts) {
        int boxW = Math.min(preferredW, Math.max(160, width - MODE_SCREEN_MARGIN * 2));
        int tw = boxW - 24;
        int lines = 0;
        for (String body : bodyTexts) {
            for (String p : body.split("\n")) {
                lines += p.isEmpty() ? 1 : wrappedLines(p, tw).size();
            }
        }
        int desiredH = 28 + lines * LINE_H + 34; // title + body + bottom padding/buttons
        int boxH = Math.min(Math.max(preferredH, desiredH), Math.max(120, height - MODE_SCREEN_MARGIN * 2));
        int boxX = Math.max(4, (width - boxW) / 2);
        int boxY = Math.max(4, (height - boxH) / 2);
        return new int[]{boxX, boxY, boxW, boxH};
    }

    /** Bounds of the confirm Yes/No buttons (computed identically in render + click handling). */
    private int[][] confirmButtonRects() {
        int[] d = clampedDialogDims(360, 168, Component.translatable("inventory-organizer.special.wl_confirm_body").getString());
        int boxX = d[0], boxY = d[1], boxW = d[2], boxH = d[3];
        int bw = Math.min(110, (boxW - 32) / 2), bh = 20, by = boxY + boxH - 28;
        return new int[][]{
            { boxX + boxW / 2 - bw - 6, by, bw, bh },  // Yes
            { boxX + boxW / 2 + 6, by, bw, bh }         // No
        };
    }

    private void drawWhitelistConfirm(GuiGraphicsExtractor context, int mouseX, int mouseY) {
        int[] d = clampedDialogDims(360, 168, Component.translatable("inventory-organizer.special.wl_confirm_body").getString());
        int boxX = d[0], boxY = d[1], boxW = d[2], boxH = d[3];
        context.fill(0, 0, width, height, 0x99000000);
        context.fill(boxX, boxY, boxX + boxW, boxY + boxH, 0xF01A1A1A);
        context.horizontalLine(boxX, boxX + boxW - 1, boxY, 0xFFFFCC44);
        context.horizontalLine(boxX, boxX + boxW - 1, boxY + boxH - 1, 0xFFFFCC44);
        context.verticalLine(boxX, boxY, boxY + boxH - 1, 0xFFFFCC44);
        context.verticalLine(boxX + boxW - 1, boxY, boxY + boxH - 1, 0xFFFFCC44);
        GuiTextScale.centeredText(context, font, Component.literal("§e⚠ ").append(
                Component.translatable("inventory-organizer.special.wl_confirm_title")), boxX + boxW / 2, boxY + 10, 0xFFFFFFFF);

        String text = Component.translatable("inventory-organizer.special.wl_confirm_body").getString();
        int textX = boxX + 12, textY = boxY + 28, textW = boxW - 24, line = 0;
        for (String p : text.split("\n")) {
            if (p.isEmpty()) { line++; continue; }
            for (String wrapped : wrappedLines(p, textW)) {
                GuiTextScale.text(context, font, "§f" + wrapped, textX, textY + line * LINE_H, 0xFFCCCCCC);
                line++;
            }
        }

        // Buttons drawn ON TOP of the overlay so they're clearly visible (the old vanilla widgets were
        // rendered underneath the dimming and looked faded).
        int[][] r = confirmButtonRects();
        drawDialogButton(context, r[0], Component.translatable("inventory-organizer.special.wl_yes").getString(), 0xFF55FF55, mouseX, mouseY);
        drawDialogButton(context, r[1], Component.translatable("inventory-organizer.special.wl_no").getString(), 0xFFFF5555, mouseX, mouseY);
    }

    private void drawDialogButton(GuiGraphicsExtractor ctx, int[] rect, String label, int textColor, int mx, int my) {
        int x = rect[0], y = rect[1], w = rect[2], h = rect[3];
        boolean hover = mx >= x && mx < x + w && my >= y && my < y + h;
        ctx.fill(x, y, x + w, y + h, hover ? 0xFF45456A : 0xFF2A2A42);
        ctx.horizontalLine(x, x + w - 1, y, 0xFFAAAACC);
        ctx.horizontalLine(x, x + w - 1, y + h - 1, 0xFFAAAACC);
        ctx.verticalLine(x, y, y + h - 1, 0xFFAAAACC);
        ctx.verticalLine(x + w - 1, y, y + h - 1, 0xFFAAAACC);
        GuiTextScale.centeredText(ctx, font, label, x + w / 2, y + (h - 8) / 2, textColor);
    }

    private static final String HELP_OVERLAY_TEXT =
            "Each toggle here works on its own. The quick-switch on the slot config screen just "
          + "flips them between the safe preset and the free preset for you.\n"
          + "\n"
          + "Free-style behaviour (keybinds anywhere, death auto-sort) only takes effect in PRIVATE "
          + "environments: single player, LAN, Realms, Aternos, or a server you add below. "
          + "Anything else stays Server Friendly. Fight mode forces Server Friendly for 20s.";

    /** Shared by draw + click-outside-to-close so both agree on the (screen-clamped) box rect. */
    private int[] helpOverlayDims() {
        return clampedDialogDims(350, 156, HELP_OVERLAY_TEXT);
    }

    private void drawHelpOverlay(GuiGraphicsExtractor context) {
        int[] d = helpOverlayDims();
        int boxX = d[0], boxY = d[1], boxW = d[2], boxH = d[3];
        context.fill(0, 0, width, height, 0x99000000);
        context.fill(boxX, boxY, boxX + boxW, boxY + boxH, 0xF01A1A1A);
        context.horizontalLine(boxX, boxX + boxW - 1, boxY, 0xFFCCCCCC);
        context.horizontalLine(boxX, boxX + boxW - 1, boxY + boxH - 1, 0xFFCCCCCC);
        context.verticalLine(boxX, boxY, boxY + boxH - 1, 0xFFCCCCCC);
        context.verticalLine(boxX + boxW - 1, boxY, boxY + boxH - 1, 0xFFCCCCCC);
        GuiTextScale.centeredText(context, font, "§eServer-Friendly / Free", boxX + boxW / 2, boxY + 8, 0xFFFFFFFF);

        int textX = boxX + 10, textY = boxY + 22, textW = boxW - 20, line = 0;
        for (String p : HELP_OVERLAY_TEXT.split("\n")) {
            if (p.isEmpty()) { line++; continue; }
            for (String wrapped : wrappedLines(p, textW)) {
                GuiTextScale.text(context, font, wrapped, textX, textY + line * LINE_H, 0xFFCCCCCC);
                line++;
            }
        }
    }

    // ---- Labels & descriptions ----

    private String keybindModeLabel() {
        String m = config.getKeybindMode();
        if ("free".equals(m)) return "Mode: Free ⚠";
        if ("disabled".equals(m)) return "Mode: Off";
        return "Mode: Inventory only";
    }

    private int keybindDescColor() {
        return "free".equals(config.getKeybindMode()) ? 0xFFFF8844 : 0xFFAAAAAA;
    }

    private String keybindModeDesc() {
        String m = config.getKeybindMode();
        if ("free".equals(m)) return "Keybinds fire anywhere — but only in private environments (see below). Considered a macro on servers; use at your own risk.";
        if ("disabled".equals(m)) return "All keybinds disabled. The in-inventory buttons still work.";
        return "Keybinds only fire while the matching screen is open. Safe on all servers.";
    }

    private String deathLabel() {
        return config.isDeathSortEnabled() ? "Death auto-sort: ON ⚠" : "Death auto-sort: OFF";
    }

    private String deathDesc() {
        return config.isDeathSortEnabled()
            ? "After you die, once 4 of your items are back, it sorts automatically. Only in private environments."
            : "Off. You sort manually after dying.";
    }

    private String sortActionLabel() {
        String a = config.getSortAction();
        if ("oi_only".equals(a)) return "Action: OI only";
        if ("ost_only".equals(a)) return "Action: OST only";
        if ("both".equals(a)) return "Action: Both";
        return "Action: Smart";
    }

    private String sortActionDescription() {
        String a = config.getSortAction();
        if ("oi_only".equals(a)) return "Always sorts the player inventory only — never the chest.";
        if ("ost_only".equals(a)) return "Always sorts the open chest only. Nothing when no chest is open.";
        if ("both".equals(a)) return "Sorts BOTH chest and inventory in one press. Brief flash at chests.";
        return "Chest open → sort chest, otherwise sort inventory. Recommended.";
    }

    private void drawWrapped(GuiGraphicsExtractor context, String text, int x, int y, int maxWidth, int color, int maxLines) {
        java.util.List<String> lines = wrappedLines(text, maxWidth);
        int n = Math.min(lines.size(), maxLines);
        for (int i = 0; i < n; i++) {
            GuiTextScale.text(context, font, lines.get(i), x, y + i * LINE_H, color);
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
    public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent click, boolean bl) {
        if (showHelp) {
            int[] d = helpOverlayDims();
            int boxX = d[0], boxY = d[1], boxW = d[2], boxH = d[3];
            double mx = click.x(), my = click.y();
            if (mx < boxX || mx >= boxX + boxW || my < boxY || my >= boxY + boxH) showHelp = false;
            return true;
        }
        // Modal confirm: only the Yes/No buttons respond — clicks elsewhere are swallowed so the
        // user can't touch the settings behind the dialog without making a choice.
        if (showWhitelistConfirm) {
            int[][] r = confirmButtonRects();
            double mx = click.x(), my = click.y();
            if (mx >= r[0][0] && mx < r[0][0] + r[0][2] && my >= r[0][1] && my < r[0][1] + r[0][3]) {
                config.setWhitelistEnabled(true);
                config.save();
                showWhitelistConfirm = false;
                rebuild();
            } else if (mx >= r[1][0] && mx < r[1][0] + r[1][2] && my >= r[1][1] && my < r[1][1] + r[1][3]) {
                showWhitelistConfirm = false;
                rebuild();
            }
            return true;
        }
        // Modal confirm for the complexity-mode switch — same modal semantics as the whitelist dialog.
        if (showModeConfirm) {
            int[][] r = modeConfirmButtonRects();
            double mx = click.x(), my = click.y();
            if (mx >= r[0][0] && mx < r[0][0] + r[0][2] && my >= r[0][1] && my < r[0][1] + r[0][3]) {
                if (pendingMode != null) config.setComplexityMode(pendingMode);
                config.save();
                showModeConfirm = false;
                pendingMode = null;
                rebuild();
            } else if (mx >= r[1][0] && mx < r[1][0] + r[1][2] && my >= r[1][1] && my < r[1][1] + r[1][3]) {
                showModeConfirm = false;
                pendingMode = null;
                rebuild();
            }
            return true;
        }
        return super.mouseClicked(click, bl);
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().gui.setScreen(parent);
    }
}
