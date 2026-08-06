package com.example.inventoryorganizer.config;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Scrollable list of bundle profiles. Each profile defines what item rules belong in a
 * particular bundle. During OI sort, physical bundles are matched to profiles by content
 * similarity. Bundles with no matching profile are left untouched.
 */
public class BundleProfileListScreen extends Screen {

    private final Screen parent;
    private final OrganizerConfig config;

    private int scroll = 0;
    private int maxScroll = 0;
    private final List<Button> rowButtons = new ArrayList<>();
    // Inline name-edit state: index of the profile being renamed, or -1.
    private int editingIndex = -1;
    private EditBox nameBox;

    private int listX, listY, listW, listH;
    private static final int ROW_H = 22;

    public BundleProfileListScreen(Screen parent) {
        super(Component.translatable("inventory-organizer.bundle_profiles.screen_title"));
        this.parent = parent;
        this.config = OrganizerConfig.get();
    }

    @Override
    protected void init() {
        // Counter-zoom (see GuiScaleCap) — at GUI Scale > 2, compute this fixed-pixel layout against a
        // bigger virtual width/height (as if GUI Scale were capped at 2) and shrink the whole render
        // pass back down at render time instead of reflowing rows for the real, cramped width/height.
        this.width = GuiScaleCap.vw(this.width);
        this.height = GuiScaleCap.vh(this.height);
        listW = Math.min(320, width - 40);
        listX = (width - listW) / 2;
        listY = 42;
        listH = height - listY - 36;

        // Back
        addRenderableWidget(StyledButton.styledBuilder(
                Component.translatable("inventory-organizer.button.back"),
                b -> Minecraft.getInstance().gui.setScreen(parent))
                .bounds(listX, height - 24, 60, 16).build());

        // Add profile
        addRenderableWidget(StyledButton.styledBuilder(
                Component.translatable("inventory-organizer.bundle.new_profile"),
                b -> {
                    int n = config.getBundleProfiles().size() + 1;
                    config.getBundleProfiles().add(new BundleProfile(
                            Component.translatable("inventory-organizer.bundle.profile_default_name",
                                    n).getString()));
                    config.save();
                    reinit();
                })
                .bounds(listX + listW - 110, height - 24, 110, 16).build());

        rebuildRows();
    }

    private void reinit() {
        editingIndex = -1;
        nameBox = null;
        clearWidgets();
        rowButtons.clear();
        init();
    }

    private void rebuildRows() {
        for (Button b : rowButtons) removeWidget(b);
        rowButtons.clear();
        if (nameBox != null) { removeWidget(nameBox); nameBox = null; }

        List<BundleProfile> profiles = config.getBundleProfiles();
        int visible = Math.max(1, listH / ROW_H);
        maxScroll = Math.max(0, profiles.size() - visible);
        scroll = Math.max(0, Math.min(scroll, maxScroll));

        int y = listY + 4;
        for (int i = scroll; i < profiles.size() && y + ROW_H <= listY + listH; i++) {
            final int idx = i;
            final BundleProfile p = profiles.get(i);
            int bh = 14;
            int by = y + (ROW_H - bh) / 2;
            int bx = listX + listW - 4;

            // Edit button → open editor
            int editW = 40;
            bx -= editW;
            Button edit = StyledButton.styledBuilder(
                    Component.translatable("inventory-organizer.profile.edit"),
                    b -> openEditor(idx))
                    .bounds(bx, by, editW, bh).build();
            addRenderableWidget(edit);
            rowButtons.add(edit);

            // Delete
            int delW = 24;
            bx -= delW + 2;
            Button del = StyledButton.styledBuilder(Component.literal("§cX"),
                    b -> {
                        config.getBundleProfiles().remove(idx);
                        config.save();
                        if (editingIndex == idx) editingIndex = -1;
                        reinit();
                    })
                    .bounds(bx, by, delW, bh).build();
            addRenderableWidget(del);
            rowButtons.add(del);

            // Rename (pencil icon) — click to start inline rename
            int renW = 24;
            bx -= renW + 2;
            final int rowY = y;
            Button ren = StyledButton.styledBuilder(Component.literal("✎"),
                    b -> startRename(idx, rowY))
                    .bounds(bx, by, renW, bh).build();
            addRenderableWidget(ren);
            rowButtons.add(ren);

            // Inline name box (only for the row being renamed)
            if (editingIndex == idx) {
                int nameX = listX + 4;
                int nameW = bx - nameX - 4;
                nameBox = new EditBox(font, nameX, by, nameW, bh,
                        Component.literal(p.getName()));
                nameBox.setValue(p.getName());
                nameBox.setMaxLength(48);
                nameBox.setResponder(val -> {
                    p.setName(val);
                    config.save();
                });
                addRenderableWidget(nameBox);
            }

            y += ROW_H;
        }
    }

    private void startRename(int idx, int rowY) {
        editingIndex = idx;
        rebuildRows();
        if (nameBox != null) nameBox.setFocused(true);
    }

    private void openEditor(int idx) {
        List<BundleProfile> profiles = config.getBundleProfiles();
        if (idx < 0 || idx >= profiles.size()) return;
        BundleProfile p = profiles.get(idx);

        // Build a transient 27-slot StoragePreset from the profile's rules.
        StoragePreset transient_ = new StoragePreset("§6Bundle: §f" + p.getName(), 27);
        List<String> rules = p.getRules();
        for (int i = 0; i < 27; i++) {
            transient_.setSlotRule(i, i < rules.size() ? rules.get(i) : "any");
        }

        Minecraft.getInstance().gui.setScreen(new VisualInventoryConfigScreen(this, transient_, () -> {
            // On save: write slot rules back to the profile — ONLY the slots the user actually set.
            // Bundle matching (matchesAnyBundleRule / bundleMatchesRule) treats a literal "any" rule
            // as "matches every item", so previously writing "any" for every untouched grid slot (26
            // of the 27, typically) meant almost every saved profile ended up with an unconditional
            // catch-all buried in its rule list — an item that failed the real rule (e.g. "t:pickaxe")
            // would still match a couple of slots later via one of those "any" placeholders, so a
            // profile effectively accepted ANY item regardless of what was actually configured. Only
            // append genuine (non-"any", non-empty) rules so the profile only matches what it says.
            List<String> out = new ArrayList<>();
            for (int i = 0; i < 27; i++) {
                String r = transient_.getSlotRule(i);
                if (!r.equals("any") && !r.isEmpty()) out.add(r);
            }
            p.setRules(out);
            config.save();
        }));
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY,
                                 double horizontalAmount, double verticalAmount) {
        mouseX = GuiScaleCap.mx(mouseX);
        mouseY = GuiScaleCap.my(mouseY);
        if (mouseX >= listX && mouseX < listX + listW &&
                mouseY >= listY && mouseY < listY + listH) {
            int prev = scroll;
            scroll = Math.max(0, Math.min(scroll + (verticalAmount > 0 ? -1 : 1), maxScroll));
            if (scroll != prev) rebuildRows();
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
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
        context.fill(0, 0, width, height, 0xFF151520);
        context.centeredText(font,
                Component.literal("§e§l" + Component.translatable(
                        "inventory-organizer.bundle.profile_list_title").getString()),
                width / 2, 6, 0xFFFFFFFF);

        String hint = Component.translatable("inventory-organizer.bundle.list_hint").getString();
        context.centeredText(font, Component.literal("§7" + hint), width / 2, 18, 0xFFAAAAAA);

        drawDecoratedPanel(context, listX - 4, listY - 4, listW + 8, listH + 8);

        List<BundleProfile> profiles = config.getBundleProfiles();
        if (profiles.isEmpty()) {
            String empty = Component.translatable("inventory-organizer.bundle.no_profiles").getString();
            context.centeredText(font, Component.literal("§7" + empty),
                    width / 2, listY + listH / 2 - 4, 0xFFAAAAAA);
        } else {
            int y = listY + 4;
            for (int i = scroll; i < profiles.size() && y + ROW_H <= listY + listH; i++) {
                BundleProfile p = profiles.get(i);
                boolean rowHover = mouseY >= y && mouseY < y + ROW_H
                        && mouseX >= listX && mouseX < listX + listW;
                if (rowHover) context.fill(listX, y, listX + listW, y + ROW_H, 0x22FFFFFF);

                if (editingIndex != i) {
                    // Count non-any rules as a summary
                    long ruleCount = p.getRules().stream()
                            .filter(r -> !r.isEmpty() && !r.equals("any")).count();
                    String label = "§f" + p.getName() + " §7(" + ruleCount + " rule"
                            + (ruleCount != 1 ? "s" : "") + ")";
                    context.text(font, Component.literal(label), listX + 6, y + (ROW_H - 8) / 2,
                            0xFFFFFFFF);
                }
                y += ROW_H;
            }
        }

        if (scroll > 0)
            context.centeredText(font, Component.literal("▲"), listX + listW / 2, listY - 2, 0xFFAAAAAA);
        if (scroll < maxScroll)
            context.centeredText(font, Component.literal("▼"), listX + listW / 2, listY + listH - 6,
                    0xFFAAAAAA);

        super.extractRenderState(context, mouseX, mouseY, delta);

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

    private void drawDecoratedPanel(GuiGraphicsExtractor context, int x, int y, int w, int h) {
        context.fill(x, y, x + w, y + h, 0xFF1A1A2E);
        context.fill(x + 1, y + 1, x + w - 1, y + h - 1, 0xFF16213E);
        context.fill(x + 1, y, x + w - 1, y + 1, 0xFF444466);
        context.fill(x + 1, y + h - 1, x + w - 1, y + h, 0xFF444466);
        context.fill(x, y + 1, x + 1, y + h - 1, 0xFF444466);
        context.fill(x + w - 1, y + 1, x + w, y + h - 1, 0xFF444466);
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().gui.setScreen(parent);
    }
}
