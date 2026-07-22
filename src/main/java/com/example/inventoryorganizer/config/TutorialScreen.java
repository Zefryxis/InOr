package com.example.inventoryorganizer.config;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Animated, in-engine "Tutorial": a scripted sequence of scenes that visually demonstrate how the
 * mod's slot-rule and Ranks systems actually decide where items go. It is NOT a video file — Minecraft
 * has no video decoder — it draws item icons + captions on a faux config-panel backdrop and advances on
 * a timer, so it reads like a short screencast of the mod's own UI that the player can watch without
 * touching anything.
 *
 * <p>Auto-plays once on first launch (chained after the complexity-mode picker; see
 * VisualInventoryConfigScreen's deferred first-run hooks) and is replayable anytime from the Tutorial
 * button. The player can leave at any moment via the ✕ (top-left) skip button, step with Back / Next,
 * or just watch — each caption types itself out slowly so there's plenty of time to both read the
 * caption AND look at the animation, then the scene auto-advances after a generous pause.
 *
 * <p>Every claim in the captions is verified against the real comparator/assignment code (material →
 * enchant → durability short-circuit, deterministic slot-index tie-break, bundle profile pairing), so
 * the tutorial never teaches behaviour the mod doesn't actually have.
 */
public class TutorialScreen extends Screen {

    private final Screen parent;

    // Real screenshots dropped in by the mod author for scenes that reference an actual screen/button
    // (the "Names" screen in scene 2) — much clearer than a drawn approximation.
    private static final Identifier TEX_NAMES_SCREEN =
            Identifier.fromNamespaceAndPath("inventory-organizer", "textures/gui/tutorial_names.png");
    private static final int TEX_NAMES_SCREEN_W = 1918, TEX_NAMES_SCREEN_H = 972;
    private static final Identifier TEX_NAMES_BUTTON =
            Identifier.fromNamespaceAndPath("inventory-organizer", "textures/gui/tutorial_names_button.png");
    private static final int TEX_NAMES_BUTTON_W = 117, TEX_NAMES_BUTTON_H = 39;

    // --- timing (generous per user feedback: previous pacing was a "speedrun") ---
    private static final long MS_PER_CHAR = 55L;      // caption typewriter speed (was 30 — much slower)
    private static final long READ_PAD_MS = 11000L;   // dwell after typing finishes (was 4800 — over 2x longer)
    private long sceneStartMs;
    private int scene = 0;
    private static final int SCENE_COUNT = 13;

    // --- icon scale: drawItemIcon blits a fixed 16x16 texture, so we wrap it in a pose scale to get
    // a much more visible, "look here" sized icon instead of a tiny 16px sprite lost in empty space. ---
    private static final float ICON_SCALE = 2.4f;
    private static final int ICON_SZ = (int) (16 * ICON_SCALE); // ~38px on-screen footprint, non-hotbar icons
    private static final int PREF_SLOT_SZ = ICON_SZ + 6;        // preferred slot square, clamped per-frame below

    // Per-frame hotbar-row slot size: the 9-slot row is clamped to fit stageW at any window size (the
    // preferred size can overflow a narrow window otherwise), computed once in extractRenderState.
    private int hbSlotSz = PREF_SLOT_SZ;

    // --- layout (computed per frame, clamped to the screen) ---
    private static final int LINE_H = 11;
    private int stageX, stageY, stageW, stageH; // the faux "config panel" area scenes draw into
    private int captionY, captionW;

    // Buttons kept as fields so their labels/visibility can update per scene.
    private Button backBtn, nextBtn;

    public TutorialScreen(Screen parent) {
        super(Component.translatable("inventory-organizer.tutorial.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        super.init();
        this.sceneStartMs = System.currentTimeMillis();
        rebuildButtons();
    }

    private void rebuildButtons() {
        clearWidgets();

        // ✕ Skip — top-left, as requested. Leaves the tutorial immediately.
        addRenderableWidget(StyledButton.styledBuilder(Component.literal("✕"), b -> onClose())
                .bounds(6, 6, 18, 18).build());

        // Back / Next along the bottom for manual pacing (auto-advance still runs, just much slower now).
        backBtn = addRenderableWidget(StyledButton.styledBuilder(
                Component.translatable("inventory-organizer.tutorial.back"), b -> prevScene())
                .bounds(width / 2 - 104, height - 26, 100, 20).build());
        nextBtn = addRenderableWidget(StyledButton.styledBuilder(nextLabel(), b -> nextScene())
                .bounds(width / 2 + 4, height - 26, 100, 20).build());
        backBtn.active = scene > 0;
    }

    private Component nextLabel() {
        return Component.translatable(scene >= SCENE_COUNT - 1
                ? "inventory-organizer.tutorial.done"
                : "inventory-organizer.tutorial.next");
    }

    private void nextScene() {
        if (scene >= SCENE_COUNT - 1) { onClose(); return; }
        scene++;
        sceneStartMs = System.currentTimeMillis();
        nextBtn.setMessage(nextLabel());
        backBtn.active = scene > 0;
    }

    private void prevScene() {
        if (scene <= 0) return;
        scene--;
        sceneStartMs = System.currentTimeMillis();
        nextBtn.setMessage(nextLabel());
        backBtn.active = scene > 0;
    }

    private String caption() {
        return Component.translatable("inventory-organizer.tutorial.s" + (scene + 1) + ".caption").getString();
    }

    private String head() {
        return Component.translatable("inventory-organizer.tutorial.s" + (scene + 1) + ".head").getString();
    }

    // ---------------------------------------------------------------------
    // Render
    // ---------------------------------------------------------------------

    @Override
    public void extractRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
        // Solid backdrop (a real screencast feel), not the blurred pause overlay.
        context.fill(0, 0, width, height, 0xFF12121C);

        // Layout is CAPTION-FIRST and bottom-anchored: the caption block is reserved just above the
        // buttons based on how many lines the FULL caption wraps to at this width (stable during the
        // typewriter reveal), and the faux stage fills whatever space is left above it — now sized MUCH
        // more generously (bigger panel, bigger icons) per feedback that the old scenes looked cramped
        // and mostly empty.
        captionW = Math.min(460, Math.max(200, width - 32));
        int buttonsTop = height - 30;
        int capLines = countCaptionLines(caption(), captionW);
        int capH = Math.max(LINE_H, capLines * LINE_H);
        stageY = 46;
        captionY = Math.max(stageY + 90, buttonsTop - capH - 6);
        stageW = Math.min(460, Math.max(220, width - 40));
        stageX = (width - stageW) / 2;
        stageH = Math.max(90, (captionY - 8) - stageY);

        // Clamp the 9-slot hotbar row's slot size so it always fits stageW (the preferred size can
        // overflow on narrow windows otherwise).
        int gap = 3;
        int maxByWidth = (stageW - 8 * gap) / 9;
        hbSlotSz = Math.max(20, Math.min(PREF_SLOT_SZ, maxByWidth));

        // Title + progress dots share the top row; the per-scene heading gets its own row below,
        // with enough clearance that the dots never dip into its text.
        GuiTextScale.centeredText(context, font, Component.literal("§e").append(
                Component.translatable("inventory-organizer.tutorial.title")), width / 2, 8, 0xFFFFFFFF);

        // Progress dots.
        drawProgress(context);

        GuiTextScale.centeredText(context, font, Component.literal("§b" + head()), width / 2, 30, 0xFFBFD8FF);

        // Faux panel frame. Scene 2's screenshot uses a wider-than-usual box (see bigNamesBoxW), so
        // the panel backdrop has to widen to match it there or the picture spills out past its edges.
        int panelX = stageX - 6, panelW = stageW + 12;
        if (scene == 2) {
            int bigW = bigNamesBoxW();
            panelX = (width - bigW) / 2 - 6;
            panelW = bigW + 12;
        }
        drawPanel(context, panelX, stageY - 6, panelW, stageH + 12);

        long now = System.currentTimeMillis();
        long elapsed = now - sceneStartMs;
        float t = Math.min(1f, elapsed / 1400f); // 0..1 intro ease for icon slides (slower, easier to follow)

        drawScene(context, scene, t);
        drawCaption(context, elapsed);

        // Auto-advance once the caption has fully typed out + a long read pad (except the last scene).
        String cap = caption();
        long typeMs = cap.length() * MS_PER_CHAR;
        long autoAdvanceAt = typeMs + READ_PAD_MS;
        if (scene < SCENE_COUNT - 1) {
            if (elapsed > autoAdvanceAt) {
                nextScene();
            } else {
                drawAutoAdvanceCountdown(context, autoAdvanceAt - elapsed);
            }
        }

        super.extractRenderState(context, mouseX, mouseY, delta); // widgets on top
    }

    /** Small "Next in Ns" hint near the Next button so the reader knows how long they still have
     *  before auto-advance kicks in (manual Back/Next always still works regardless). */
    private void drawAutoAdvanceCountdown(GuiGraphicsExtractor context, long remainingMs) {
        int secs = (int) Math.max(0, (remainingMs + 999) / 1000);
        String txt = Component.translatable("inventory-organizer.tutorial.autoadvance", secs).getString();
        int tw = font.width(txt);
        int x = width / 2 + 4 + (nextBtn != null ? nextBtn.getWidth() / 2 : 50) - tw / 2;
        int y = height - 26 - 12;
        GuiTextScale.text(context, font, Component.literal("§7" + txt), x, y, 0xFF9A9AA8);
    }

    /** Width of the enlarged Names-screenshot box in scene 2 — pulled out to a method so the panel
     *  backdrop (drawn before drawScene runs) can size itself to match exactly. */
    private int bigNamesBoxW() {
        return Math.min(width - 24, 760);
    }

    private void drawProgress(GuiGraphicsExtractor context) {
        int dot = 5, gap = 4;
        int total = SCENE_COUNT * dot + (SCENE_COUNT - 1) * gap;
        int x = (width - total) / 2, y = 20;
        for (int i = 0; i < SCENE_COUNT; i++) {
            int col = i == scene ? 0xFFFFCC44 : (i < scene ? 0xFF6688AA : 0xFF44445A);
            context.fill(x, y, x + dot, y + dot, col);
            x += dot + gap;
        }
    }

    /** Typewriter caption: reveal a growing prefix of the raw text, then word-wrap what's revealed. */
    private void drawCaption(GuiGraphicsExtractor context, long elapsed) {
        String full = caption();
        int shown = (int) Math.min(full.length(), Math.max(0, elapsed / MS_PER_CHAR));
        String revealed = full.substring(0, shown);
        int x = (width - captionW) / 2;
        int y = captionY;
        for (String para : revealed.split("\n")) {
            if (para.isEmpty()) { y += LINE_H; continue; }
            for (String line : wrappedLines(para, captionW)) {
                GuiTextScale.text(context, font, Component.literal("§f" + line), x, y, 0xFFE8E8F0);
                y += LINE_H;
            }
        }
    }

    // ---------------------------------------------------------------------
    // Scenes — each draws inside the stage rect, now with 2.4x-scaled icons and a real hotbar-row and more
    // breathing room so nothing looks like a tiny lost sprite in a big empty panel. Item enchants/
    // durability are shown as LABELS next to the icon (drawItemIcon blits a plain texture; it renders
    // no glint or durability bar on its own).
    // ---------------------------------------------------------------------

    private void drawScene(GuiGraphicsExtractor context, int s, float t) {
        int cx = stageX + stageW / 2;
        int cy = stageY + stageH / 2;
        switch (s) {
            case 0 -> { // Welcome
                drawIcon(context, VisualInventoryConfigScreen.safeIcon(Items.CHEST), cx - ICON_SZ / 2, cy - ICON_SZ - 6);
                centeredMini(context, "§7Inventory Organizer", cx, cy + 14);
            }
            case 1 -> { // Assign a specific item to a slot — real hotbar-row look, one slot highlighted
                int[] xs = hotbarRow();
                int rowY = hotbarY();
                int hi = 4; // the slot we're configuring, dead centre of the row
                int hIconSz = hbSlotSz - 6;
                for (int i = 0; i < xs.length; i++) drawMCSlot(context, xs[i], rowY, i == hi);
                int fromX = stageX + stageW - hIconSz - 14;
                int ix = (int) lerp(fromX, xs[hi] + (hbSlotSz - hIconSz) / 2, t);
                drawIconSized(context, VisualInventoryConfigScreen.safeIcon(Items.DIAMOND_PICKAXE), ix, rowY + (hbSlotSz - hIconSz) / 2, hIconSz);
                centeredMini(context, "§bDiamond Pickaxe §7— rule: this exact item", cx, rowY + hbSlotSz + 16);
            }
            case 2 -> { // Names screen — a real annotated screenshot (arrow pointing at the Names
                        // button), scaled to fit the stage while keeping its aspect ratio, plus a
                        // small inset close-up of the button itself in the corner for extra clarity.
                int bigW = bigNamesBoxW();
                int bigX = (width - bigW) / 2;
                int[] fit = drawTextureFitIn(context, TEX_NAMES_SCREEN, TEX_NAMES_SCREEN_W, TEX_NAMES_SCREEN_H,
                        bigX, stageY, bigW, stageH);
                // Crop 6px off the right and 2px off the bottom of the source button image — it
                // has a sliver of unrelated UI on those edges — before scaling it up into the inset.
                int srcW = TEX_NAMES_BUTTON_W - 6, srcH = TEX_NAMES_BUTTON_H - 2;
                int insetW = Math.min(70, stageW / 6);
                int insetH = insetW * srcH / srcW;
                int insetX = stageX + stageW - insetW - 6;
                int insetY = stageY + stageH - insetH - 6;
                context.fill(insetX - 3, insetY - 3, insetX + insetW + 3, insetY + insetH + 3, 0xE0000000);
                context.horizontalLine(insetX - 3, insetX + insetW + 2, insetY - 3, 0xFFFFCC44);
                context.horizontalLine(insetX - 3, insetX + insetW + 2, insetY + insetH + 2, 0xFFFFCC44);
                context.verticalLine(insetX - 3, insetY - 3, insetY + insetH + 2, 0xFFFFCC44);
                context.verticalLine(insetX + insetW + 2, insetY - 3, insetY + insetH + 2, 0xFFFFCC44);
                drawTextureRegion(context, TEX_NAMES_BUTTON, insetX, insetY, insetW, insetH,
                        0f, 0f, srcW, srcH, TEX_NAMES_BUTTON_W, TEX_NAMES_BUTTON_H);
            }
            case 3 -> { // Automatic mode — same hotbar row, now holding a plain type rule
                int[] xs = hotbarRow();
                int rowY = hotbarY();
                int hi = 4;
                int hIconSz = hbSlotSz - 6;
                for (int i = 0; i < xs.length; i++) drawMCSlot(context, xs[i], rowY, i == hi);
                drawIconSized(context, VisualInventoryConfigScreen.safeIcon(Items.IRON_PICKAXE), xs[hi] + (hbSlotSz - hIconSz) / 2, rowY + (hbSlotSz - hIconSz) / 2, hIconSz);
                centeredMini(context, "§7rule: any pickaxe", cx, rowY + hbSlotSz + 16);
            }
            case 4 -> { // Ranks — the real "Sort Priority" section look: a gold "▼ Sort Priority"
                        // header (SortingOrderConfigScreen.drawSectionHeader) above the exact numbered,
                        // white drag-list rows it draws for the criteria order.
                int bx = cx - 90, by = stageY + 16;
                GuiTextScale.text(context, font, Component.literal("§6▼ Sort Priority"), bx, by, 0xFFFFAA00);
                by += 15;
                drawPriorityRow(context, bx, by, "1. Material");
                by += 15;
                drawPriorityRow(context, bx, by, "2. Enchant");
                by += 15;
                drawPriorityRow(context, bx, by, "3. Durability");
            }
            case 5 -> drawVersus(context, t, true);   // Materials first → Netherite wins
            case 6 -> drawVersus(context, t, false);  // Enchantments first → Diamond wins
            case 7 -> { // Two pickaxe slots + tier tie-break — real hotbar row, two slots highlighted
                int[] xs = hotbarRow();
                int rowY = hotbarY();
                int i1 = 3, i2 = 5;
                int hIconSz = hbSlotSz - 6;
                int hIconOff = (hbSlotSz - hIconSz) / 2;
                for (int i = 0; i < xs.length; i++) drawMCSlot(context, xs[i], rowY, i == i1 || i == i2);
                miniLabel(context, "§7Tier 1", xs[i1] + 2, rowY - 12);
                miniLabel(context, "§7Tier 2", xs[i2] + 2, rowY - 12);
                drawIconSized(context, VisualInventoryConfigScreen.safeIcon(Items.NETHERITE_PICKAXE), xs[i1] + hIconOff, rowY + hIconOff, hIconSz);
                drawIconSized(context, VisualInventoryConfigScreen.safeIcon(Items.DIAMOND_PICKAXE), xs[i2] + hIconOff, rowY + hIconOff, hIconSz);
                // The exact green tier-number badge VisualInventoryConfigScreen.drawStorageSlot draws
                // in a slot's top-left corner ("§a" + tier, 0xFF55FF55).
                GuiTextScale.text(context, font, Component.literal("§a1"), xs[i1] + 3, rowY + 3, 0xFF55FF55);
                GuiTextScale.text(context, font, Component.literal("§a2"), xs[i2] + 3, rowY + 3, 0xFF55FF55);
                centeredMini(context, "§7higher rank → lower tier slot", cx, rowY + hbSlotSz + 18);
            }
            case 8 -> { // Warehouse — linked chests as one
                int chX = cx - PREF_SLOT_SZ - 16, chY = cy - PREF_SLOT_SZ / 2 - 6;
                drawIcon(context, VisualInventoryConfigScreen.safeIcon(Items.CHEST), chX, chY);
                drawIcon(context, VisualInventoryConfigScreen.safeIcon(Items.CHEST), chX + PREF_SLOT_SZ + 32, chY);
                drawLink(context, chX + ICON_SZ, chY + ICON_SZ / 2, chX + PREF_SLOT_SZ + 32, chY + ICON_SZ / 2);
                centeredMini(context, "§7linked = one warehouse", cx, chY + ICON_SZ + 18);
            }
            case 9 -> { // Bundles + profiles
                int bY = cy - PREF_SLOT_SZ / 2 - 8;
                int bx1 = cx - ICON_SZ - 30, bx2 = cx + 30;
                drawIcon(context, VisualInventoryConfigScreen.safeIcon(Items.BUNDLE), bx1, bY);
                drawIcon(context, VisualInventoryConfigScreen.safeIcon(Items.BUNDLE), bx2, bY);
                miniLabel(context, "§eProfile A", bx1 - 4, bY - 14);
                miniLabel(context, "§eProfile B", bx2 - 4, bY - 14);
                centeredMini(context, "§7profiles pair to bundles", cx, bY + ICON_SZ + 18);
            }
            case 10 -> { // Potions + groups
                int py = cy - ICON_SZ / 2 - 6;
                drawIcon(context, VisualInventoryConfigScreen.safeIcon(Items.POTION), cx - ICON_SZ - 24, py);
                drawIcon(context, VisualInventoryConfigScreen.safeIcon(Items.WHEAT_SEEDS), cx + 24, py);
                centeredMini(context, "§7potion rules · group ranks", cx, py + ICON_SZ + 18);
            }
            case 11 -> { // Quick-reference table: rank order -> which criterion actually decided ->
                        // winner, covering all three possible "first" criteria, plus a note on the
                        // cascade rule and a bundle-pairing footnote — almost the whole scene is table.
                int tx = stageX + 10, ty = stageY + 14;
                int col1 = Math.min(stageW * 42 / 100, 175);
                int col2 = Math.min(stageW * 27 / 100, 95);
                GuiTextScale.text(context, font, Component.literal("§6Rank order"), tx, ty, 0xFFFFAA00);
                GuiTextScale.text(context, font, Component.literal("§6Decided by"), tx + col1, ty, 0xFFFFAA00);
                GuiTextScale.text(context, font, Component.literal("§6Wins"), tx + col1 + col2, ty, 0xFFFFAA00);
                ty += 11;
                context.horizontalLine(tx, tx + col1 + col2 + 55, ty - 3, 0xFF444455);
                ty += 5;
                drawTableRow3(context, tx, col1, col2, ty, "Material›Enchant›Durability", "§7Material", "§fNetherite");
                ty += 13;
                drawTableRow3(context, tx, col1, col2, ty, "Enchant›Material›Durability", "§7Enchant", "§fDiamond");
                ty += 13;
                drawTableRow3(context, tx, col1, col2, ty, "Durability›Material›Enchant", "§7Durability", "§fDiamond");
                ty += 20;
                centeredMini(context, "§7A criterion only decides when the ones above it are tied -",
                        cx, ty);
                centeredMini(context, "§7otherwise a higher-ranked one already settled it",
                        cx, ty + 11);
                ty += 24;
                centeredMini(context, "§7Bundles work the same way: best content match first,",
                        cx, ty);
                centeredMini(context, "§7then any leftover profiles pair with empty bundles in list order",
                        cx, ty + 11);
            }
            case 12 -> { // Closing
                drawIcon(context, VisualInventoryConfigScreen.safeIcon(Items.NETHER_STAR), cx - ICON_SZ / 2, cy - ICON_SZ - 6);
                centeredMini(context, "§7Have fun!", cx, cy + 14);
            }
            default -> {}
        }
    }

    /**
     * Two-pickaxe versus panel used by the Materials-first and Enchantments-first scenes: the two
     * contestants sit above a real hotbar-row (same look as scenes 1/3/7), and the winner slides into
     * the highlighted slot in that row.
     */
    private void drawVersus(GuiGraphicsExtractor context, float t, boolean materialsFirst) {
        int cx = stageX + stageW / 2;
        int topY = stageY + 6;
        int leftX = stageX + 30, rightX = stageX + stageW - ICON_SZ - 30;

        // Contestants
        drawIcon(context, VisualInventoryConfigScreen.safeIcon(Items.NETHERITE_PICKAXE), leftX, topY);
        miniLabel(context, "§8Netherite", leftX - 6, topY + ICON_SZ + 4);
        miniLabel(context, "§850% dur · Efficiency", leftX - 6, topY + ICON_SZ + 16);

        drawIcon(context, VisualInventoryConfigScreen.safeIcon(Items.DIAMOND_PICKAXE), rightX, topY);
        int rightEdge = Math.min(rightX + ICON_SZ + 6, stageX + stageW - 4);
        miniLabelRight(context, "§bDiamond", rightEdge, topY + ICON_SZ + 4);
        miniLabelRight(context, "§b100% dur · Mending", rightEdge, topY + ICON_SZ + 16);

        // The real hotbar row lower in the stage; the winner slides into the highlighted (centre) slot.
        int[] xs = hotbarRow();
        int rowY = stageY + stageH - hbSlotSz - 8;
        int hi = 4;
        int hIconSz = hbSlotSz - 6;
        int hIconOff = (hbSlotSz - hIconSz) / 2;
        for (int i = 0; i < xs.length; i++) drawMCSlot(context, xs[i], rowY, i == hi);
        int fromX = materialsFirst ? leftX : rightX;
        int ix = (int) lerp(fromX, xs[hi] + hIconOff, t);
        ItemStack winner = VisualInventoryConfigScreen.safeIcon(
                materialsFirst ? Items.NETHERITE_PICKAXE : Items.DIAMOND_PICKAXE);
        drawIconSized(context, winner, ix, rowY + hIconOff, hIconSz);
        miniLabel(context, materialsFirst ? "§b1. Materials" : "§d1. Enchantments", cx - 40, rowY - 14);
    }

    /** Centered 9-slot hotbar-row x positions, matching the real slot-config grid's proportions. */
    private int[] hotbarRow() {
        int gap = 3;
        int total = 9 * hbSlotSz + 8 * gap;
        int startX = stageX + (stageW - total) / 2;
        int[] xs = new int[9];
        for (int i = 0; i < 9; i++) xs[i] = startX + i * (hbSlotSz + gap);
        return xs;
    }

    private int hotbarY() {
        return stageY + (stageH - hbSlotSz) / 2 + 6;
    }

    // ---------------------------------------------------------------------
    // Small drawing helpers
    // ---------------------------------------------------------------------

    /**
     * Draws a screenshot texture scaled to fit inside the stage rect (letterboxed, aspect-ratio
     * preserved, centred). Returns {x, y, w, h} of the drawn rect in case a scene needs to anchor
     * something relative to it (e.g. an inset close-up in a corner).
     */
    private int[] drawTextureFit(GuiGraphicsExtractor context, Identifier tex, int texW, int texH) {
        return drawTextureFitIn(context, tex, texW, texH, stageX, stageY, stageW, stageH);
    }

    /** Same as {@link #drawTextureFit} but lets the caller supply a custom box, e.g. one wider than
     *  the standard stage rect, for scenes that want the screenshot shown as large as possible. */
    private int[] drawTextureFitIn(GuiGraphicsExtractor context, Identifier tex, int texW, int texH,
                                    int boxX, int boxY, int boxW, int boxH) {
        float aspect = texW / (float) texH;
        int w = boxW, h = Math.round(w / aspect);
        if (h > boxH) { h = boxH; w = Math.round(h * aspect); }
        int x = boxX + (boxW - w) / 2;
        int y = boxY + (boxH - h) / 2;
        drawTextureRegion(context, tex, x, y, w, h, 0f, 0f, texW, texH, texW, texH);
        return new int[]{x, y, w, h};
    }

    /** Draws a (possibly cropped) region of a texture scaled to an arbitrary destination size, using
     *  a pose-transform scale rather than relying on blit's 1:1 crop-only semantics. */
    private void drawTextureRegion(GuiGraphicsExtractor context, Identifier tex, int destX, int destY,
                                    int destW, int destH, float srcX, float srcY, int srcW, int srcH,
                                    int texW, int texH) {
        context.pose().pushMatrix();
        context.pose().translate(destX, destY);
        context.pose().scale(destW / (float) srcW, destH / (float) srcH);
        context.blit(RenderPipelines.GUI_TEXTURED, tex, 0, 0, srcX, srcY, srcW, srcH, texW, texH);
        context.pose().popMatrix();
    }

    /** Draws an item icon at ICON_SCALE (drawItemIcon itself always blits a fixed 16x16 texture). */
    private void drawIcon(GuiGraphicsExtractor context, ItemStack stack, int x, int y) {
        drawIconSized(context, stack, x, y, ICON_SZ);
    }

    /** Draws an item icon scaled to an arbitrary on-screen size (used for hotbar-row icons, which are
     *  sized to whatever the per-frame clamped slot size allows rather than the fixed ICON_SZ). */
    private void drawIconSized(GuiGraphicsExtractor context, ItemStack stack, int x, int y, int size) {
        if (stack == null || stack.isEmpty()) return;
        context.pose().pushMatrix();
        context.pose().translate(x, y);
        context.pose().scale(size / 16f);
        VisualInventoryConfigScreen.drawItemIcon(context, stack, 0, 0);
        context.pose().popMatrix();
    }

    private void drawPanel(GuiGraphicsExtractor context, int x, int y, int w, int h) {
        context.fill(x, y, x + w, y + h, 0xFF1A1A2E);
        int b = 0xFF3A3A5A;
        context.horizontalLine(x, x + w - 1, y, b);
        context.horizontalLine(x, x + w - 1, y + h - 1, b);
        context.verticalLine(x, y, y + h - 1, b);
        context.verticalLine(x + w - 1, y, y + h - 1, b);
    }

    /**
     * A real Minecraft-style inventory slot bevel — the exact same sunken 3D look used by the real
     * slot-config screen (VisualInventoryConfigScreen.drawMCSlot), scaled proportionally to the current
     * per-frame hotbar slot size instead of the fixed 36px it uses. {@code highlight} draws the same
     * blue selection frame the real screen uses when a slot is currently selected.
     */
    private void drawMCSlot(GuiGraphicsExtractor context, int x, int y, boolean highlight) {
        int size = hbSlotSz;
        int inset = Math.max(2, size / 12);
        context.fill(x, y, x + size, y + size, 0xFF111111);
        context.fill(x + 1, y + 1, x + size - 1, y + 1 + inset, 0xFF2A2A2A);
        context.fill(x + 1, y + 1, x + 1 + inset, y + size - 1, 0xFF2A2A2A);
        context.fill(x + 1, y + size - 1 - inset, x + size - 1, y + size - 1, 0xFF6A6A6A);
        context.fill(x + size - 1 - inset, y + 1, x + size - 1, y + size - 1, 0xFF6A6A6A);
        context.fill(x + 1 + inset, y + 1 + inset, x + size - 1 - inset, y + size - 1 - inset, 0xFF3D3D3D);
        if (highlight) {
            context.fill(x + 1 + inset, y + 1 + inset, x + size - 1 - inset, y + size - 1 - inset, 0x4400AAFF);
            context.horizontalLine(x + inset, x + size - 1 - inset, y + inset, 0xFF00AAFF);
            context.horizontalLine(x + inset, x + size - 1 - inset, y + size - 1 - inset, 0xFF00AAFF);
            context.verticalLine(x + inset, y + inset, y + size - 1 - inset, 0xFF00AAFF);
            context.verticalLine(x + size - 1 - inset, y + inset, y + size - 1 - inset, 0xFF00AAFF);
        }
    }

    /** A Sort Priority list row, in the exact plain-white style SortingOrderConfigScreen draws them. */
    private void drawPriorityRow(GuiGraphicsExtractor context, int x, int y, String label) {
        GuiTextScale.text(context, font, Component.literal(label), x + 4, y, 0xFFFFFFFF);
    }

    /** One row of the quick-reference table in scene 11: a plain white left column (rank order) and
     *  a right column that already carries its own colour codes (winner + reason in grey). */
    private void drawTableRow(GuiGraphicsExtractor context, int x, int col2, int y, String left, String right) {
        GuiTextScale.text(context, font, Component.literal("§f" + left), x, y, 0xFFE8E8F0);
        GuiTextScale.text(context, font, Component.literal(right), x + col2, y, 0xFFFFFFFF);
    }

    /** Three-column row for the quick-reference table (scene 11): rank order, which criterion actually
     *  decided that scenario, and the resulting winner. */
    private void drawTableRow3(GuiGraphicsExtractor context, int x, int col1, int col2, int y,
                               String order, String decidedBy, String winner) {
        GuiTextScale.text(context, font, Component.literal("§f" + order), x, y, 0xFFE8E8F0);
        GuiTextScale.text(context, font, Component.literal(decidedBy), x + col1, y, 0xFFFFFFFF);
        GuiTextScale.text(context, font, Component.literal(winner), x + col1 + col2, y, 0xFFFFFFFF);
    }

    private void drawLink(GuiGraphicsExtractor context, int x1, int y, int x2, int y2) {
        context.fill(x1, y, x2, y + 2, 0xFF55FF99);
    }

    private void miniLabel(GuiGraphicsExtractor context, String text, int x, int y) {
        GuiTextScale.text(context, font, Component.literal(text), x, y, 0xFFCCCCDD);
    }

    /** Like {@link #miniLabel} but anchored by its RIGHT edge — used for labels sitting on the right
     *  side of the versus panel, since a left-anchored label there can run past the stage edge once
     *  the text gets long (e.g. "100% dur · Mending"). */
    private void miniLabelRight(GuiGraphicsExtractor context, String text, int rightEdge, int y) {
        int w = font.width(Component.literal(text));
        GuiTextScale.text(context, font, Component.literal(text), rightEdge - w, y, 0xFFCCCCDD);
    }

    private void centeredMini(GuiGraphicsExtractor context, String text, int cx, int y) {
        GuiTextScale.centeredText(context, font, Component.literal(text), cx, y, 0xFFCCCCDD);
    }

    private static float lerp(float a, float b, float t) { return a + (b - a) * t; }

    /** Total wrapped-line count of a (possibly multi-paragraph) caption at the given width. */
    private int countCaptionLines(String text, int maxWidth) {
        int n = 0;
        for (String para : text.split("\n")) {
            n += para.isEmpty() ? 1 : wrappedLines(para, maxWidth).size();
        }
        return Math.max(1, n);
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
        Minecraft.getInstance().gui.setScreen(parent);
    }
}
