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
                btn -> Minecraft.getInstance().setScreen(new HudLayoutScreen(this))
        ).bounds(width / 2 - 50, height - 52, 100, 20).build());

        addRenderableWidget(StyledButton.styledBuilder(Component.literal("Back"),
                btn -> Minecraft.getInstance().setScreen(parent)
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

        context.centeredText(font, Component.literal("§eSpecial Settings"), width / 2, 14, 0xFFFFFFFF);

        // Row 1 labels + descriptions
        context.text(font, Component.literal("§bKeybind mode"), leftX, 30, 0xFFFFFFFF);
        context.text(font, Component.literal("§bSort keybind action"), rightX, 30, 0xFFFFFFFF);
        drawWrapped(context, keybindModeDesc(), leftX, 64, COL_W, keybindDescColor(), 3);
        drawWrapped(context, sortActionDescription(), rightX, 64, COL_W, 0xFFAAAAAA, 3);

        // Row 2 labels + descriptions
        context.text(font, Component.literal("§bDeath auto-sort"), leftX, 92, 0xFFFFFFFF);
        context.text(font, Component.literal("§bTrash mode"), rightX, 92, 0xFFFFFFFF);
        drawWrapped(context, config.isTrashOverflowOnly()
                ? "Keeps up to one stack of each trashed item; only the excess is dropped."
                : "Drops every item matching the Trash list as soon as you pick it up.",
                rightX, 126, COL_W, 0xFFAAAAAA, 3);
        drawWrapped(context, deathDesc(), leftX, 126, COL_W, config.isDeathSortEnabled() ? 0xFFFF8844 : 0xFFAAAAAA, 3);

        // Whitelist label
        context.text(font, Component.literal("§bFree-mode server whitelist"), leftX, 158, 0xFFFFFFFF);

        if (!config.isWhitelistEnabled()) {
            // Feature hidden by default — show only the explanatory hint next to the OFF toggle.
            drawWrapped(context, "Advanced opt-in feature, hidden by default. Enable it to whitelist servers for Free mode — at your own risk.",
                    rightX, 172, COL_W, 0xFFAAAAAA, 3);
        } else {
            java.util.List<String> wl = config.getServerWhitelist();
            if (wl.isEmpty()) {
                context.text(font, Component.literal("§7No servers whitelisted yet."), leftX, 196, 0xFFAAAAAA);
            } else {
                for (int i = 0; i < wl.size() && i < 4; i++) {
                    context.text(font, Component.literal("§f" + wl.get(i)), leftX, 196 + i * 20 + 5, 0xFFFFFFFF);
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
        context.centeredText(font, Component.literal(envLine), width / 2, height - 90, 0xFFFFFFFF);

        if (showHelp) drawHelpOverlay(context);
        if (showWhitelistConfirm) drawWhitelistConfirm(context, mouseX, mouseY);
    }

    /** Bounds of the confirm Yes/No buttons (computed identically in render + click handling). */
    private int[][] confirmButtonRects() {
        int boxW = 360, boxH = 168;
        int boxX = (width - boxW) / 2, boxY = (height - boxH) / 2;
        int bw = 110, bh = 20, by = boxY + boxH - 28;
        return new int[][]{
            { boxX + boxW / 2 - bw - 6, by, bw, bh },  // Yes
            { boxX + boxW / 2 + 6, by, bw, bh }         // No
        };
    }

    private void drawWhitelistConfirm(GuiGraphicsExtractor context, int mouseX, int mouseY) {
        int boxW = 360, boxH = 168;
        int boxX = (width - boxW) / 2, boxY = (height - boxH) / 2;
        context.fill(0, 0, width, height, 0x99000000);
        context.fill(boxX, boxY, boxX + boxW, boxY + boxH, 0xF01A1A1A);
        context.horizontalLine(boxX, boxX + boxW - 1, boxY, 0xFFFFCC44);
        context.horizontalLine(boxX, boxX + boxW - 1, boxY + boxH - 1, 0xFFFFCC44);
        context.verticalLine(boxX, boxY, boxY + boxH - 1, 0xFFFFCC44);
        context.verticalLine(boxX + boxW - 1, boxY, boxY + boxH - 1, 0xFFFFCC44);
        context.centeredText(font, Component.literal("§e⚠ ").append(
                Component.translatable("inventory-organizer.special.wl_confirm_title")), width / 2, boxY + 10, 0xFFFFFFFF);

        String text = Component.translatable("inventory-organizer.special.wl_confirm_body").getString();
        int textX = boxX + 12, textY = boxY + 28, textW = boxW - 24, line = 0;
        for (String p : text.split("\n")) {
            if (p.isEmpty()) { line++; continue; }
            for (String wrapped : wrappedLines(p, textW)) {
                context.text(font, Component.literal("§f" + wrapped), textX, textY + line * LINE_H, 0xFFCCCCCC);
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
        ctx.centeredText(font, Component.literal(label), x + w / 2, y + (h - 8) / 2, textColor);
    }

    private void drawHelpOverlay(GuiGraphicsExtractor context) {
        int boxW = 350, boxH = 156;
        int boxX = (width - boxW) / 2, boxY = (height - boxH) / 2;
        context.fill(0, 0, width, height, 0x99000000);
        context.fill(boxX, boxY, boxX + boxW, boxY + boxH, 0xF01A1A1A);
        context.horizontalLine(boxX, boxX + boxW - 1, boxY, 0xFFCCCCCC);
        context.horizontalLine(boxX, boxX + boxW - 1, boxY + boxH - 1, 0xFFCCCCCC);
        context.verticalLine(boxX, boxY, boxY + boxH - 1, 0xFFCCCCCC);
        context.verticalLine(boxX + boxW - 1, boxY, boxY + boxH - 1, 0xFFCCCCCC);
        context.centeredText(font, Component.literal("§eServer-Friendly / Free"), width / 2, boxY + 8, 0xFFFFFFFF);

        String text =
            "Each toggle here works on its own. The quick-switch on the slot config screen just "
          + "flips them between the safe preset and the free preset for you.\n"
          + "\n"
          + "Free-style behaviour (keybinds anywhere, death auto-sort) only takes effect in PRIVATE "
          + "environments: single player, LAN, Realms, Aternos, or a server you add below. "
          + "Anything else stays Server Friendly. Fight mode forces Server Friendly for 20s.";

        int textX = boxX + 10, textY = boxY + 22, textW = boxW - 20, line = 0;
        for (String p : text.split("\n")) {
            if (p.isEmpty()) { line++; continue; }
            for (String wrapped : wrappedLines(p, textW)) {
                context.text(font, Component.literal(wrapped), textX, textY + line * LINE_H, 0xFFCCCCCC);
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
            context.text(font, Component.literal(lines.get(i)), x, y + i * LINE_H, color);
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
            int boxW = 350, boxH = 156;
            int boxX = (width - boxW) / 2, boxY = (height - boxH) / 2;
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
        return super.mouseClicked(click, bl);
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(parent);
    }
}
