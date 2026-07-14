package com.example.inventoryorganizer.config;

import com.example.inventoryorganizer.ChestIdentifier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Scrollable list of storage profiles.
 *
 * <p>The first three rows are the protected defaults (Container / Large Chest / Bundle) — the
 * "basics" that act as the fallback for any chest with no per-chest profile. They can be edited
 * but not renamed, resized or deleted. Below them are the user's per-chest profiles, each of which
 * can be edited, duplicated or deleted.
 *
 * <p>When opened from an open chest (a bind context is supplied), each user profile also offers
 * "Bind here", and a "+ New profile" entry creates one bound to that chest — this is the picker
 * flow for re-attaching a carried profile to a different chest.
 */
public class ChestProfileListScreen extends Screen {

    private final Screen parent;
    private final OrganizerConfig config;

    // Optional bind context captured from an open chest (size 0 = opened from settings, no chest).
    private final String bindName;
    private final List<int[]> bindPositions;
    private final int bindSize;
    private final String bindSign;

    private int scroll = 0;
    private int maxScroll = 0;
    private final List<Button> rowButtons = new ArrayList<>();

    private int listX, listY, listW, listH;
    private static final int ROW_H = 20;

    public ChestProfileListScreen(Screen parent) {
        this(parent, null, null, 0, null);
    }

    public ChestProfileListScreen(Screen parent, String bindName, List<int[]> bindPositions, int bindSize, String bindSign) {
        super(Component.literal("Chest Profiles"));
        this.parent = parent;
        this.config = OrganizerConfig.get();
        this.bindName = bindName;
        this.bindPositions = bindPositions;
        this.bindSize = bindSize;
        this.bindSign = bindSign;
    }

    private boolean hasBindContext() { return bindSize > 0; }

    @Override
    protected void init() {
        listW = Math.min(320, width - 40);
        listX = (width - listW) / 2;
        listY = 48;
        listH = height - listY - 36;

        // Back
        addRenderableWidget(StyledButton.styledBuilder(Component.translatable("inventory-organizer.button.back"),
            b -> Minecraft.getInstance().setScreen(parent)).bounds(listX, height - 24, 60, 16).build());

        // + New profile (only meaningful when a chest is open to bind it to)
        if (hasBindContext()) {
            addRenderableWidget(StyledButton.styledBuilder(Component.translatable("inventory-organizer.profile.new_here"),
                b -> {
                    int ordinal = countUserProfiles() + 1;
                    StoragePreset p = config.addProfile(ChestIdentifier.defaultProfileName(ordinal), bindSize);
                    bindTo(p);
                    Minecraft.getInstance().setScreen(new VisualInventoryConfigScreen(this, config.indexOfProfile(p)));
                }).bounds(listX + listW - 130, height - 24, 130, 16).build());
        }

        rebuildRows();
    }

    private void reinit() {
        clearWidgets();
        rowButtons.clear();
        init();
    }

    private int countUserProfiles() {
        int n = 0;
        for (StoragePreset p : config.getStoragePresets()) if (!p.isDefault()) n++;
        return n;
    }

    private void rebuildRows() {
        for (Button b : rowButtons) removeWidget(b);
        rowButtons.clear();

        List<StoragePreset> profiles = config.getStoragePresets();
        int visible = Math.max(1, listH / ROW_H);
        maxScroll = Math.max(0, profiles.size() - visible);
        scroll = Math.max(0, Math.min(scroll, maxScroll));

        int y = listY + 2;
        for (int i = scroll; i < profiles.size() && y + ROW_H <= listY + listH; i++) {
            final StoragePreset p = profiles.get(i);
            int bx = listX + listW - 4;
            int bh = 14;
            int by = y + (ROW_H - bh) / 2;

            // Buttons are laid out right-to-left.
            // Edit (always available)
            int editW = 40;
            bx -= editW;
            Button edit = StyledButton.styledBuilder(Component.translatable("inventory-organizer.profile.edit"),
                b -> Minecraft.getInstance().setScreen(new VisualInventoryConfigScreen(this, config.indexOfProfile(p))))
                .bounds(bx, by, editW, bh).build();
            addRenderableWidget(edit);
            rowButtons.add(edit);

            if (!p.isDefault()) {
                // Delete
                int delW = 24;
                bx -= delW + 2;
                Button del = StyledButton.styledBuilder(Component.literal("§cX"),
                    b -> { config.removeProfile(p); config.save(); rebuildRows(); })
                    .bounds(bx, by, delW, bh).build();
                addRenderableWidget(del);
                rowButtons.add(del);

                // Duplicate
                int dupW = 36;
                bx -= dupW + 2;
                Button dup = StyledButton.styledBuilder(Component.translatable("inventory-organizer.profile.dup"),
                    b -> { config.duplicateProfile(p); config.save(); rebuildRows(); })
                    .bounds(bx, by, dupW, bh).build();
                addRenderableWidget(dup);
                rowButtons.add(dup);

                // Bind here (only when a chest is open)
                if (hasBindContext()) {
                    int bindW = 52;
                    bx -= bindW + 2;
                    Button bind = StyledButton.styledBuilder(Component.translatable("inventory-organizer.profile.bind"),
                        b -> { bindTo(p); rebuildRows(); })
                        .bounds(bx, by, bindW, bh).build();
                    addRenderableWidget(bind);
                    rowButtons.add(bind);
                }
            }

            y += ROW_H;
        }
    }

    /** Bind a profile to the chest captured in the bind context (snapshot, chest may be closed now). */
    private void bindTo(StoragePreset p) {
        if (p == null || p.isDefault() || !hasBindContext()) return;
        if (bindName != null && !bindName.isEmpty()) p.setCustomName(bindName);
        if (bindSign != null && !bindSign.isEmpty()) { p.setSignText(bindSign); p.setName(bindSign); }
        if (bindPositions != null) {
            for (int[] q : bindPositions) if (q.length == 3) p.addPosition(q[0], q[1], q[2]);
        }
        if (bindSize == 27 || bindSize == 54) p.setSize(bindSize);
        config.save();
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (mouseX >= listX && mouseX < listX + listW && mouseY >= listY && mouseY < listY + listH) {
            int prev = scroll;
            scroll = Math.max(0, Math.min(scroll + (verticalAmount > 0 ? -1 : 1), maxScroll));
            if (scroll != prev) rebuildRows();
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
        context.fill(0, 0, width, height, 0xFF151520);
        context.centeredText(font, Component.literal("§e§l" + Component.translatable("inventory-organizer.profile.list_title").getString()),
            width / 2, 6, 0xFFFFFFFF);

        if (hasBindContext()) {
            String ctx = bindName != null && !bindName.isEmpty()
                ? bindName
                : Component.translatable("inventory-organizer.profile.this_chest").getString();
            context.centeredText(font,
                Component.translatable("inventory-organizer.profile.binding_to", ctx),
                width / 2, 22, 0xFFFFCC55);
        }

        drawDecoratedPanel(context, listX - 4, listY - 4, listW + 8, listH + 8);

        // Row text (buttons draw themselves)
        List<StoragePreset> profiles = config.getStoragePresets();
        int y = listY + 2;
        for (int i = scroll; i < profiles.size() && y + ROW_H <= listY + listH; i++) {
            StoragePreset p = profiles.get(i);
            boolean rowHover = mouseY >= y && mouseY < y + ROW_H && mouseX >= listX && mouseX < listX + listW;
            if (rowHover) context.fill(listX, y, listX + listW, y + ROW_H, 0x22FFFFFF);

            String label;
            if (p.isDefault()) {
                label = "§b" + p.getName() + " §8[" + Component.translatable("inventory-organizer.profile.default_tag").getString() + "]";
            } else {
                String bound = p.isBound() ? " §a●" : " §8○";
                label = "§f" + p.getName() + " §7(" + p.getSize() + ")" + bound;
            }
            context.text(font, Component.literal(label), listX + 4, y + (ROW_H - 8) / 2, 0xFFFFFFFF);
            y += ROW_H;
        }

        // Scroll hints
        if (scroll > 0) context.centeredText(font, Component.literal("▲"), listX + listW / 2, listY - 2, 0xFFAAAAAA);
        if (scroll < maxScroll) context.centeredText(font, Component.literal("▼"), listX + listW / 2, listY + listH - 6, 0xFFAAAAAA);

        super.extractRenderState(context, mouseX, mouseY, delta);
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
        Minecraft.getInstance().setScreen(parent);
    }
}
