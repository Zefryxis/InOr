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
        super(Component.literal("Kits"));
        this.parent = parent;
        this.config = OrganizerConfig.get();
        this.autoMode = autoMode;
    }

    @Override
    protected void init() {
        super.init();
        rebuildWidgets();
    }

    protected void rebuildWidgets() {
        clearWidgets();

        int centerX = width / 2;
        int y = 10;

        // Title label
        Button titleBtn = StyledButton.styledBuilder(
                Component.literal("Kits - Save and load presets"),
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
                    Component.literal(kit.getName() + " (" + ruleCount + " rules)"),
                    btn -> {}
            ).bounds(centerX - 150, y, 130, 20).build();
            nameBtn.active = false;
            addRenderableWidget(nameBtn);

            // Load button
            addRenderableWidget(StyledButton.styledBuilder(
                    Component.literal("Load"),
                    btn -> {
                        config.loadKit(config.getKits().get(kitIndex), autoMode);
                        config.save();
                        showStatus("Loaded: " + config.getKits().get(kitIndex).getName());
                    }
            ).bounds(centerX - 15, y, 50, 20).build());

            // Save to button
            final String saveKitName = kit.getName();
            addRenderableWidget(StyledButton.styledBuilder(
                    Component.literal("Save to"),
                    btn -> {
                        config.saveToKit(kitIndex, autoMode);
                        config.save();
                        showStatus("Saved to: " + saveKitName);
                    }
            ).bounds(centerX + 40, y, 55, 20).build());

            // Delete button
            final String delKitName = kit.getName();
            addRenderableWidget(StyledButton.styledBuilder(
                    Component.literal("Delete"),
                    btn -> {
                        config.deleteKit(kitIndex);
                        config.save();
                        if (scrollOffset > 0 && scrollOffset >= config.getKits().size()) {
                            scrollOffset--;
                        }
                        showStatus("Deleted: " + delKitName);
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
                    Component.literal("No kits yet - create one below!"),
                    btn -> {}
            ).bounds(centerX - 120, y, 240, 20).build();
            emptyBtn.active = false;
            addRenderableWidget(emptyBtn);
            y += 25;
        }

        y += 10;

        // Create new kit section
        nameField = new EditBox(font, centerX - 150, y, 200, 20, Component.literal("Kit name"));
        nameField.setHint(Component.literal("Enter kit name..."));
        nameField.setMaxLength(30);
        addRenderableWidget(nameField);

        addRenderableWidget(StyledButton.styledBuilder(
                Component.literal("+ Create"),
                btn -> {
                    String name = nameField.getValue().trim();
                    if (!name.isEmpty()) {
                        config.saveCurrentAsKit(name, autoMode);
                        config.save();
                        nameField.setValue("");
                        showStatus("Created: " + name);
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
                Component.literal("Export Data"),
                btn -> {
                    if (OrganizerConfig.exportBackup()) {
                        String where = "kits folder";
                        try { where = OrganizerConfig.backupFile().toString(); } catch (Exception ignored) {}
                        showStatus("Exported all data → " + where);
                    } else {
                        showStatus("§cExport failed");
                    }
                }
        ).bounds(centerX - 115, height - 55, 90, 20).build());

        addRenderableWidget(StyledButton.styledBuilder(
                Component.literal("Import Data"),
                btn -> {
                    if (OrganizerConfig.importBackup()) {
                        scrollOffset = 0;
                        showStatus("§aImported all data (restored).");
                        rebuildWidgets();
                    } else {
                        showStatus("§eNo backup file found in kits folder.");
                    }
                }
        ).bounds(centerX - 20, height - 55, 90, 20).build());

        addRenderableWidget(StyledButton.styledBuilder(
                Component.literal("Folder"),
                btn -> KitFile.openKitsFolder()
        ).bounds(centerX + 75, height - 55, 45, 20).build());

        // Save button
        addRenderableWidget(StyledButton.styledBuilder(
                Component.literal("Save"),
                btn -> {
                    config.save();
                    showStatus("Settings saved!");
                }
        ).bounds(centerX - 100, height - 30, 90, 20).build());

        // Back button
        addRenderableWidget(StyledButton.styledBuilder(
                Component.literal("Back"),
                btn -> {
                    Minecraft.getInstance().setScreen(parent);
                }
        ).bounds(centerX + 10, height - 30, 90, 20).build());

        // Help toggle button
        addRenderableWidget(StyledButton.styledBuilder(Component.literal("?"), btn -> {
            showHelp = !showHelp;
        }).bounds(width - 24, 4, 20, 18).build());
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
        super.extractRenderState(context, mouseX, mouseY, delta);
        if (showHelp) drawGuideOverlay(context);
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
            Component.literal("\u00a7e\u00a7lKits Guide"), width / 2, gy + 8, 0xFFFFFF55);

        int lx = gx + 12, ly = gy + 26, lh = 13;
        context.text(font, Component.literal("\u00a7b--- What are Kits? ---"), lx, ly, 0xFF55FFFF); ly += lh;
        context.text(font, Component.literal("\u00a7fKits save your current slot rules (item assignments)."), lx, ly, 0xFFFFFFFF); ly += lh;
        context.text(font, Component.literal("\u00a77They do NOT save the actual inventory contents."), lx, ly, 0xFFAAAAAA); ly += lh + 4;

        context.text(font, Component.literal("\u00a7b--- Actions ---"), lx, ly, 0xFF55FFFF); ly += lh;
        context.text(font, Component.literal("\u00a7e[1] \u00a7f'+ Create' \u00a77\u2013 type a name, press Enter or button to save"), lx, ly, 0xFFFFFFFF); ly += lh;
        context.text(font, Component.literal("\u00a7e[2] \u00a7f'Load' \u00a77\u2013 loads the kit's rules into the active config"), lx, ly, 0xFFFFFFFF); ly += lh;
        context.text(font, Component.literal("\u00a7e[3] \u00a7f'Save to' \u00a77\u2013 overwrites the kit with current rules"), lx, ly, 0xFFFFFFFF); ly += lh;
        context.text(font, Component.literal("\u00a7e[4] \u00a7f'Delete' \u00a77\u2013 permanently removes the kit"), lx, ly, 0xFFFFFFFF); ly += lh + 6;

        context.text(font, Component.literal("\u00a7b--- Full backup (Export / Import Data) ---"), lx, ly, 0xFF55FFFF); ly += lh;
        context.text(font, Component.literal("\u00a7e[5] \u00a7f'Export Data' \u00a77\u2013 saves ALL mod data to one backup file"), lx, ly, 0xFFFFFFFF); ly += lh;
        context.text(font, Component.literal("\u00a77    (groups + contents, profiles, slot rules, kits, HUD, settings)"), lx, ly, 0xFFAAAAAA); ly += lh;
        context.text(font, Component.literal("\u00a7e[6] \u00a7f'Import Data' \u00a77\u2013 restores everything from that backup file"), lx, ly, 0xFFFFFFFF); ly += lh;
        context.text(font, Component.literal("\u00a7e[7] \u00a7f'Folder' \u00a77\u2013 opens the folder holding the backup file"), lx, ly, 0xFFFFFFFF); ly += lh + 4;
        context.text(font, Component.literal("\u00a77Move setups between worlds/PCs: Export Data, copy the file, Import."), lx, ly, 0xFFAAAAAA); ly += lh;
        context.text(font, Component.literal("\u00a77Import REPLACES your current settings. Per-group sharing is on Groups."), lx, ly, 0xFFAAAAAA); ly += lh + 4;

        // Live paths so users can find the folders without guessing.
        String kitsPath = "(folder not yet created)";
        String importPath = "(folder not yet created)";
        try { kitsPath = KitFile.getKitsFolder().toString(); } catch (Exception ignored) {}
        try { importPath = KitFile.getImportFolder().toString(); } catch (Exception ignored) {}
        context.text(font, Component.literal("\u00a77Kits: \u00a78" + truncatePath(kitsPath, 65)), lx, ly, 0xFF888888); ly += lh;
        context.text(font, Component.literal("\u00a77Import: \u00a78" + truncatePath(importPath, 65)), lx, ly, 0xFF888888); ly += lh + 4;

        context.fill(gx + 12, ly, gx + gw - 12, ly + 1, 0xFF444444); ly += 6;
        context.centeredText(font,
            Component.literal("\u00a77Click outside or press \u00a7e[?]\u00a77 to close"),
            width / 2, ly, 0xFF888888);
    }

    private static String truncatePath(String path, int maxChars) {
        if (path == null) return "";
        if (path.length() <= maxChars) return path;
        return "..." + path.substring(path.length() - (maxChars - 3));
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent click, boolean bl) {
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
                showStatus("Created: " + name);
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
        Minecraft.getInstance().setScreen(parent);
    }
}
