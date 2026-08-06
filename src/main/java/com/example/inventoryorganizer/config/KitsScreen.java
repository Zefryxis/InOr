package com.example.inventoryorganizer.config;

import net.minecraft.client.Minecraft;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;

import java.util.List;

public class KitsScreen extends Screen {

    private final Screen parent;
    private final OrganizerConfig config;
    private final boolean autoMode;
    private EditBox nameField;
    private int scrollOffset = 0;
    private String statusMessage = null;
    private int statusTicks = 0;
    private boolean showHelp = false;

    public KitsScreen(Screen parent, boolean autoMode) {
        super(Component.translatable("inventory-organizer.kits.screen_title"));
        this.parent = parent;
        this.config = OrganizerConfig.get();
        this.autoMode = autoMode;
    }

    @Override
    protected void init() {
        super.init();
        this.width = GuiScaleCap.vw(this.width);
        this.height = GuiScaleCap.vh(this.height);
        rebuildWidgets();
    }

    @Override
    public void resize(int width, int height) {
        this.width = GuiScaleCap.vw(width);
        this.height = GuiScaleCap.vh(height);
        rebuildWidgets();
    }

    protected void rebuildWidgets() {
        clearWidgets();

        int centerX = width / 2;
        int y = 10;

        // Title label
        Button titleBtn = StyledButton.styledBuilder(
                Component.translatable("inventory-organizer.kits.subtitle"),
                btn -> {}
        ).bounds(centerX - 130, y, 260, 20).build();
        titleBtn.active = false;
        addRenderableWidget(titleBtn);
        y += 28;

        // Existing kits
        List<Kit> kits = config.getKits();
        int startKit = scrollOffset;
        int endKit = Math.min(kits.size(), scrollOffset + 5);

        for (int i = startKit; i < endKit; i++) {
            Kit kit = kits.get(i);
            final int kitIndex = i;
            int ruleCount = 0;
            for (SlotRule sr : kit.getSlotRules().values()) {
                if (sr.getType() != SlotRule.Type.ANY) ruleCount++;
            }

            // Kit name label
            Button nameBtn = StyledButton.styledBuilder(
                    Component.translatable("inventory-organizer.kits.entry_label", kit.getName(), ruleCount),
                    btn -> {}
            ).bounds(centerX - 150, y, 130, 20).build();
            nameBtn.active = false;
            addRenderableWidget(nameBtn);

            // Load button
            addRenderableWidget(StyledButton.styledBuilder(
                    Component.translatable("inventory-organizer.kits.load"),
                    btn -> {
                        config.loadKit(config.getKits().get(kitIndex), autoMode);
                        config.save();
                        showStatusTr("inventory-organizer.kits.status_loaded", config.getKits().get(kitIndex).getName());
                    }
            ).bounds(centerX - 15, y, 50, 20).build());

            // Save to button
            final String saveKitName = kit.getName();
            addRenderableWidget(StyledButton.styledBuilder(
                    Component.translatable("inventory-organizer.kits.save_to"),
                    btn -> {
                        config.saveToKit(kitIndex, autoMode);
                        config.save();
                        showStatusTr("inventory-organizer.kits.status_saved_to", saveKitName);
                    }
            ).bounds(centerX + 40, y, 55, 20).build());

            // Delete button
            final String delKitName = kit.getName();
            addRenderableWidget(StyledButton.styledBuilder(
                    Component.translatable("inventory-organizer.kits.delete"),
                    btn -> {
                        config.deleteKit(kitIndex);
                        config.save();
                        if (scrollOffset > 0 && scrollOffset >= config.getKits().size()) {
                            scrollOffset--;
                        }
                        showStatusTr("inventory-organizer.kits.status_deleted", delKitName);
                    }
            ).bounds(centerX + 100, y, 50, 20).build());

            y += 25;
        }

        // Scroll buttons if needed
        if (kits.size() > 5) {
            if (scrollOffset > 0) {
                addRenderableWidget(StyledButton.styledBuilder(
                        Component.literal("\u25B2"),
                        btn -> { scrollOffset--; rebuildWidgets(); }
                ).bounds(centerX + 155, 35, 20, 20).build());
            }
            if (endKit < kits.size()) {
                addRenderableWidget(StyledButton.styledBuilder(
                        Component.literal("\u25BC"),
                        btn -> { scrollOffset++; rebuildWidgets(); }
                ).bounds(centerX + 155, y - 25, 20, 20).build());
            }
        }

        if (kits.isEmpty()) {
            Button emptyBtn = StyledButton.styledBuilder(
                    Component.translatable("inventory-organizer.kits.empty"),
                    btn -> {}
            ).bounds(centerX - 120, y, 240, 20).build();
            emptyBtn.active = false;
            addRenderableWidget(emptyBtn);
            y += 25;
        }

        y += 10;

        // Create new kit section
        nameField = new EditBox(font, centerX - 150, y, 200, 20, Component.translatable("inventory-organizer.kits.name_field"));
        nameField.setHint(Component.translatable("inventory-organizer.kits.name_hint"));
        nameField.setMaxLength(30);
        addRenderableWidget(nameField);

        addRenderableWidget(StyledButton.styledBuilder(
                Component.translatable("inventory-organizer.kits.create"),
                btn -> {
                    String name = nameField.getValue().trim();
                    if (!name.isEmpty()) {
                        config.saveCurrentAsKit(name, autoMode);
                        config.save();
                        nameField.setValue("");
                        showStatusTr("inventory-organizer.kits.status_created", name);
                    }
                }
        ).bounds(centerX + 55, y, 70, 20).build());

        // Status message (shown temporarily after actions) — above the import/export row
        if (statusMessage != null) {
            Button statusBtn = StyledButton.styledBuilder(
                    Component.literal(statusMessage),
                    btn -> {}
            ).bounds(centerX - 100, height - 80, 200, 20).build();
            statusBtn.active = false;
            addRenderableWidget(statusBtn);
        }

        // --- Whole-mod backup row (above Save/Back) ---
        // ONE backup file holds EVERYTHING (groups+contents, profiles, slot rules, kits, HUD, prefs).
        // (Per-group import/export still lives on the Groups screen.)
        addRenderableWidget(StyledButton.styledBuilder(
                Component.translatable("inventory-organizer.kits.export_data"),
                btn -> {
                    if (OrganizerConfig.exportBackup()) {
                        String where = tr("inventory-organizer.kits.status_kits_folder");
                        try { where = OrganizerConfig.backupFile().toString(); } catch (Exception ignored) {}
                        showStatusTr("inventory-organizer.kits.status_exported", where);
                    } else {
                        showStatusTr("inventory-organizer.kits.status_export_failed");
                    }
                }
        ).bounds(centerX - 115, height - 55, 90, 20).build());

        addRenderableWidget(StyledButton.styledBuilder(
                Component.translatable("inventory-organizer.kits.import_data"),
                btn -> {
                    if (OrganizerConfig.importBackup()) {
                        scrollOffset = 0;
                        showStatusTr("inventory-organizer.kits.status_imported");
                        rebuildWidgets();
                    } else {
                        showStatusTr("inventory-organizer.kits.status_no_backup");
                    }
                }
        ).bounds(centerX - 20, height - 55, 90, 20).build());

        addRenderableWidget(StyledButton.styledBuilder(
                Component.translatable("inventory-organizer.kits.folder"),
                btn -> KitFile.openKitsFolder()
        ).bounds(centerX + 75, height - 55, 45, 20).build());

        // Save button
        addRenderableWidget(StyledButton.styledBuilder(
                Component.translatable("inventory-organizer.kits.save"),
                btn -> {
                    config.save();
                    showStatusTr("inventory-organizer.kits.status_settings_saved");
                }
        ).bounds(centerX - 100, height - 30, 90, 20).build());

        // Back button
        addRenderableWidget(StyledButton.styledBuilder(
                Component.translatable("inventory-organizer.kits.back"),
                btn -> {
                    Minecraft.getInstance().gui.setScreen(parent);
                }
        ).bounds(centerX + 10, height - 30, 90, 20).build());

        // Help toggle button
        addRenderableWidget(StyledButton.styledBuilder(Component.literal("?"), btn -> {
            showHelp = !showHelp;
        }).bounds(width - 24, 4, 20, 18).build());
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
        if (showHelp) drawGuideOverlay(context);

        if (guiScaleCapF != 1f) context.pose().popMatrix();
    }

    private void drawGuideOverlay(GuiGraphicsExtractor context) {
        int gw = 420, gh = 308;
        int gx = width / 2 - gw / 2;
        int gy = height / 2 - gh / 2;

        context.fill(0, 0, width, height, 0x88000000);
        context.fill(gx, gy, gx + gw, gy + gh, 0xFF1A1A2E);
        context.fill(gx, gy, gx + gw, gy + 2, 0xFF4466AA);
        context.fill(gx, gy + gh - 2, gx + gw, gy + gh, 0xFF4466AA);
        context.fill(gx, gy, gx + 2, gy + gh, 0xFF4466AA);
        context.fill(gx + gw - 2, gy, gx + gw, gy + gh, 0xFF4466AA);
        context.fill(gx + 4, gy + 4, gx + gw - 4, gy + 20, 0xFF111133);
        context.centeredText(font,
            Component.translatable("inventory-organizer.kits.guide_title"), width / 2, gy + 8, 0xFFFFFF55);

        int lx = gx + 12, ly = gy + 26, lh = 13;
        context.text(font, Component.translatable("inventory-organizer.kits.guide_what_head"), lx, ly, 0xFF55FFFF); ly += lh;
        context.text(font, Component.translatable("inventory-organizer.kits.guide_what_1"), lx, ly, 0xFFFFFFFF); ly += lh;
        context.text(font, Component.translatable("inventory-organizer.kits.guide_what_2"), lx, ly, 0xFFAAAAAA); ly += lh + 4;

        context.text(font, Component.translatable("inventory-organizer.kits.guide_actions_head"), lx, ly, 0xFF55FFFF); ly += lh;
        context.text(font, Component.translatable("inventory-organizer.kits.guide_action_create"), lx, ly, 0xFFFFFFFF); ly += lh;
        context.text(font, Component.translatable("inventory-organizer.kits.guide_action_load"), lx, ly, 0xFFFFFFFF); ly += lh;
        context.text(font, Component.translatable("inventory-organizer.kits.guide_action_save_to"), lx, ly, 0xFFFFFFFF); ly += lh;
        context.text(font, Component.translatable("inventory-organizer.kits.guide_action_delete"), lx, ly, 0xFFFFFFFF); ly += lh + 6;

        context.text(font, Component.translatable("inventory-organizer.kits.guide_backup_head"), lx, ly, 0xFF55FFFF); ly += lh;
        context.text(font, Component.translatable("inventory-organizer.kits.guide_backup_export"), lx, ly, 0xFFFFFFFF); ly += lh;
        context.text(font, Component.translatable("inventory-organizer.kits.guide_backup_export_detail"), lx, ly, 0xFFAAAAAA); ly += lh;
        context.text(font, Component.translatable("inventory-organizer.kits.guide_backup_import"), lx, ly, 0xFFFFFFFF); ly += lh;
        context.text(font, Component.translatable("inventory-organizer.kits.guide_backup_folder"), lx, ly, 0xFFFFFFFF); ly += lh + 4;
        context.text(font, Component.translatable("inventory-organizer.kits.guide_backup_move"), lx, ly, 0xFFAAAAAA); ly += lh;
        context.text(font, Component.translatable("inventory-organizer.kits.guide_backup_replace"), lx, ly, 0xFFAAAAAA); ly += lh + 4;

        // Live paths so users can find the folders without guessing.
        String kitsPath = "(folder not yet created)";
        String importPath = "(folder not yet created)";
        try { kitsPath = KitFile.getKitsFolder().toString(); } catch (Exception ignored) {}
        try { importPath = KitFile.getImportFolder().toString(); } catch (Exception ignored) {}
        context.text(font, Component.translatable("inventory-organizer.kits.guide_path_kits", truncatePath(kitsPath, 65)), lx, ly, 0xFF888888); ly += lh;
        context.text(font, Component.translatable("inventory-organizer.kits.guide_path_import", truncatePath(importPath, 65)), lx, ly, 0xFF888888); ly += lh + 4;

        context.fill(gx + 12, ly, gx + gw - 12, ly + 1, 0xFF444444); ly += 6;
        context.centeredText(font,
            Component.translatable("inventory-organizer.kits.guide_close_hint"),
            width / 2, ly, 0xFF888888);
    }

    private static String truncatePath(String path, int maxChars) {
        if (path == null) return "";
        if (path.length() <= maxChars) return path;
        return "..." + path.substring(path.length() - (maxChars - 3));
    }

    /** Remaps a real (vanilla-delivered) mouse event into virtual space (see GuiScaleCap / init()). */
    private MouseButtonEvent toVirtual(MouseButtonEvent e) {
        if (GuiScaleCap.renderFactor() == 1f) return e;
        return new MouseButtonEvent(GuiScaleCap.mx(e.x()), GuiScaleCap.my(e.y()), e.buttonInfo());
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent click) {
        return super.mouseReleased(toVirtual(click));
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent click, double dragX, double dragY) {
        float f = GuiScaleCap.renderFactor();
        double s = f == 1f ? 1.0 : (1.0 / f);
        return super.mouseDragged(toVirtual(click), dragX * s, dragY * s);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent click, boolean bl) {
        click = toVirtual(click);
        if (showHelp) {
            int gw = 420, gh = 308;
            int gx = width / 2 - gw / 2;
            int gy = height / 2 - gh / 2;
            if (click.x() < gx || click.x() > gx + gw || click.y() < gy || click.y() > gy + gh) {
                showHelp = false;
            }
            return true;
        }
        return super.mouseClicked(click, bl);
    }

    @Override
    public boolean keyPressed(KeyEvent keyEvent) {
        // Enter key creates kit instantly
        if (keyEvent.key() == 257 && nameField != null && nameField.isFocused()) {
            String name = nameField.getValue().trim();
            if (!name.isEmpty()) {
                config.saveCurrentAsKit(name, autoMode);
                config.save();
                nameField.setValue("");
                showStatusTr("inventory-organizer.kits.status_created", name);
                return true;
            }
        }
        return super.keyPressed(keyEvent);
    }

    private boolean kitNameExists(String name) {
        for (Kit k : config.getKits()) {
            if (k.getName().equals(name)) return true;
        }
        return false;
    }

    private void showStatus(String message) {
        statusMessage = message;
        statusTicks = 60; // ~3 seconds at 20 tps
        rebuildWidgets();
    }

    /** Resolves a translation key (with optional format args) and shows it as the status message. */
    private void showStatusTr(String key, Object... args) {
        showStatus(tr(key, args));
    }

    private static String tr(String key, Object... args) {
        return Component.translatable(key, args).getString();
    }

    @Override
    public void tick() {
        super.tick();
        if (statusTicks > 0) {
            statusTicks--;
            if (statusTicks == 0) {
                statusMessage = null;
                rebuildWidgets();
            }
        }
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().gui.setScreen(parent);
    }
}
