package com.example.inventoryorganizer.config;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.input.KeyInput;
import net.minecraft.text.Text;

import java.util.List;

public class KitsScreen extends Screen {

    private final Screen parent;
    private final OrganizerConfig config;
    private TextFieldWidget nameField;
    private int scrollOffset = 0;
    private String statusMessage = null;
    private int statusTicks = 0;
    private boolean showHelp = false;

    public KitsScreen(Screen parent) {
        super(Text.literal("Kits"));
        this.parent = parent;
        this.config = OrganizerConfig.get();
    }

    @Override
    protected void init() {
        super.init();
        rebuildWidgets();
    }

    private void rebuildWidgets() {
        clearChildren();

        int centerX = width / 2;
        int y = 10;

        // Title label
        StyledButton titleBtn = StyledButton.styledBuilder(
                Text.literal("Kits - Save and load presets"),
                btn -> {}
        ).dimensions(centerX - 130, y, 260, 20).build();
        titleBtn.active = false;
        addDrawableChild(titleBtn);
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
            StyledButton nameBtn = StyledButton.styledBuilder(
                    Text.literal(kit.getName() + " (" + ruleCount + " rules)"),
                    btn -> {}
            ).dimensions(centerX - 150, y, 130, 20).build();
            nameBtn.active = false;
            addDrawableChild(nameBtn);

            // Load button
            addDrawableChild(StyledButton.styledBuilder(
                    Text.literal("Load"),
                    btn -> {
                        config.loadKit(config.getKits().get(kitIndex));
                        config.save();
                        showStatus("Loaded: " + config.getKits().get(kitIndex).getName());
                    }
            ).dimensions(centerX - 15, y, 50, 20).build());

            // Save to button
            final String saveKitName = kit.getName();
            addDrawableChild(StyledButton.styledBuilder(
                    Text.literal("Save to"),
                    btn -> {
                        config.saveToKit(kitIndex);
                        config.save();
                        showStatus("Saved to: " + saveKitName);
                    }
            ).dimensions(centerX + 40, y, 55, 20).build());

            // Delete button
            final String delKitName = kit.getName();
            addDrawableChild(StyledButton.styledBuilder(
                    Text.literal("Delete"),
                    btn -> {
                        config.deleteKit(kitIndex);
                        config.save();
                        if (scrollOffset > 0 && scrollOffset >= config.getKits().size()) {
                            scrollOffset--;
                        }
                        showStatus("Deleted: " + delKitName);
                    }
            ).dimensions(centerX + 100, y, 50, 20).build());

            y += 25;
        }

        // Scroll buttons if needed
        if (kits.size() > 5) {
            if (scrollOffset > 0) {
                addDrawableChild(StyledButton.styledBuilder(
                        Text.literal("\u25B2"),
                        btn -> { scrollOffset--; rebuildWidgets(); }
                ).dimensions(centerX + 155, 35, 20, 20).build());
            }
            if (endKit < kits.size()) {
                addDrawableChild(StyledButton.styledBuilder(
                        Text.literal("\u25BC"),
                        btn -> { scrollOffset++; rebuildWidgets(); }
                ).dimensions(centerX + 155, y - 25, 20, 20).build());
            }
        }

        if (kits.isEmpty()) {
            StyledButton emptyBtn = StyledButton.styledBuilder(
                    Text.literal("No kits yet - create one below!"),
                    btn -> {}
            ).dimensions(centerX - 120, y, 240, 20).build();
            emptyBtn.active = false;
            addDrawableChild(emptyBtn);
            y += 25;
        }

        y += 10;

        // Create new kit section
        nameField = new TextFieldWidget(textRenderer, centerX - 150, y, 200, 20, Text.literal("Kit name"));
        nameField.setPlaceholder(Text.literal("Enter kit name..."));
        nameField.setMaxLength(30);
        addDrawableChild(nameField);

        addDrawableChild(StyledButton.styledBuilder(
                Text.literal("+ Create"),
                btn -> {
                    String name = nameField.getText().trim();
                    if (!name.isEmpty()) {
                        config.saveCurrentAsKit(name);
                        config.save();
                        nameField.setText("");
                        showStatus("Created: " + name);
                    }
                }
        ).dimensions(centerX + 55, y, 70, 20).build());

        // Status message (shown temporarily after actions)
        if (statusMessage != null) {
            StyledButton statusBtn = StyledButton.styledBuilder(
                    Text.literal(statusMessage),
                    btn -> {}
            ).dimensions(centerX - 100, height - 55, 200, 20).build();
            statusBtn.active = false;
            addDrawableChild(statusBtn);
        }

        // Save button
        addDrawableChild(StyledButton.styledBuilder(
                Text.literal("Save"),
                btn -> {
                    config.save();
                    showStatus("Settings saved!");
                }
        ).dimensions(centerX - 100, height - 30, 90, 20).build());

        // Back button
        addDrawableChild(StyledButton.styledBuilder(
                Text.literal("Back"),
                btn -> {
                    MinecraftClient.getInstance().setScreen(parent);
                }
        ).dimensions(centerX + 10, height - 30, 90, 20).build());

        // Help toggle button
        addDrawableChild(StyledButton.styledBuilder(Text.literal("?"), btn -> {
            showHelp = !showHelp;
        }).dimensions(width - 24, 4, 20, 18).build());
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta);
        if (showHelp) drawGuideOverlay(context);
    }

    private void drawGuideOverlay(DrawContext context) {
        int gw = 380, gh = 205;
        int gx = width / 2 - gw / 2;
        int gy = height / 2 - gh / 2;

        context.fill(0, 0, width, height, 0x88000000);
        context.fill(gx, gy, gx + gw, gy + gh, 0xFF1A1A2E);
        context.fill(gx, gy, gx + gw, gy + 2, 0xFF4466AA);
        context.fill(gx, gy + gh - 2, gx + gw, gy + gh, 0xFF4466AA);
        context.fill(gx, gy, gx + 2, gy + gh, 0xFF4466AA);
        context.fill(gx + gw - 2, gy, gx + gw, gy + gh, 0xFF4466AA);
        context.fill(gx + 4, gy + 4, gx + gw - 4, gy + 20, 0xFF111133);
        context.drawCenteredTextWithShadow(textRenderer,
            Text.literal("\u00a7e\u00a7lKits Guide"), width / 2, gy + 8, 0xFFFFFF55);

        int lx = gx + 12, ly = gy + 26, lh = 13;
        context.drawTextWithShadow(textRenderer, Text.literal("\u00a7b--- What are Kits? ---"), lx, ly, 0xFF55FFFF); ly += lh;
        context.drawTextWithShadow(textRenderer, Text.literal("\u00a7fKits save your current slot rules (item assignments)."), lx, ly, 0xFFFFFFFF); ly += lh;
        context.drawTextWithShadow(textRenderer, Text.literal("\u00a77They do NOT save the actual inventory contents."), lx, ly, 0xFFAAAAAA); ly += lh + 4;
        context.drawTextWithShadow(textRenderer, Text.literal("\u00a7b--- Actions ---"), lx, ly, 0xFF55FFFF); ly += lh;
        context.drawTextWithShadow(textRenderer, Text.literal("\u00a7e[1] \u00a7f'+ Create' \u00a77\u2013 type a name, press Enter or button to save"), lx, ly, 0xFFFFFFFF); ly += lh;
        context.drawTextWithShadow(textRenderer, Text.literal("\u00a7e[2] \u00a7f'Load' \u00a77\u2013 loads the kit's rules into the active config"), lx, ly, 0xFFFFFFFF); ly += lh;
        context.drawTextWithShadow(textRenderer, Text.literal("\u00a7e[3] \u00a7f'Save to' \u00a77\u2013 overwrites the kit with current rules"), lx, ly, 0xFFFFFFFF); ly += lh;
        context.drawTextWithShadow(textRenderer, Text.literal("\u00a7e[4] \u00a7f'Delete' \u00a77\u2013 permanently removes the kit"), lx, ly, 0xFFFFFFFF); ly += lh + 6;

        context.fill(gx + 12, ly, gx + gw - 12, ly + 1, 0xFF444444); ly += 6;
        context.drawCenteredTextWithShadow(textRenderer,
            Text.literal("\u00a77Click outside or press \u00a7e[?]\u00a77 to close"),
            width / 2, ly, 0xFF888888);
    }

    @Override
    public boolean mouseClicked(Click click, boolean bl) {
        if (showHelp) {
            int gw = 380, gh = 205;
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
    public boolean keyPressed(KeyInput keyInput) {
        // Enter key creates kit instantly
        if (keyInput.key() == 257 && nameField != null && nameField.isFocused()) {
            String name = nameField.getText().trim();
            if (!name.isEmpty()) {
                config.saveCurrentAsKit(name);
                config.save();
                nameField.setText("");
                showStatus("Created: " + name);
                return true;
            }
        }
        return super.keyPressed(keyInput);
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
    public void close() {
        MinecraftClient.getInstance().setScreen(parent);
    }
}
