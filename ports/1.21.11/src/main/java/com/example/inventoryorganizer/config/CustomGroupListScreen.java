package com.example.inventoryorganizer.config;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.input.KeyInput;
import net.minecraft.text.Text;

import java.util.List;

public class CustomGroupListScreen extends Screen {

    private final Screen parent;
    private final OrganizerConfig config;

    // Name input popup state
    private boolean showNameInput = false;
    private TextFieldWidget nameField;
    private String pendingEditGroup = null; // null = new group, non-null = rename existing

    public CustomGroupListScreen(Screen parent) {
        super(Text.literal("Custom Item Groups"));
        this.parent = parent;
        this.config = OrganizerConfig.get();
    }

    @Override
    protected void init() {
        super.init();
        rebuildButtons();
    }

    private void rebuildButtons() {
        clearChildren();

        List<String> names = config.getCustomGroupNames();
        int startY = 30;
        int rowH = 24;

        for (int i = 0; i < names.size(); i++) {
            final String name = names.get(i);
            int y = startY + i * rowH;

            addDrawableChild(StyledButton.styledBuilder(Text.literal("\u270E Edit"), btn -> {
                config.save();
                MinecraftClient.getInstance().setScreen(new CustomGroupEditorScreen(this, name));
            }).dimensions(width / 2 - 100, y, 60, 18).build());

            addDrawableChild(StyledButton.styledBuilder(Text.literal("\u2716 Delete"), btn -> {
                config.deleteCustomGroup(name);
                config.save();
                rebuildButtons();
            }).dimensions(width / 2 - 36, y, 60, 18).build());

            // group name label drawn in render
        }

        int bottomY = height - 28;
        addDrawableChild(StyledButton.styledBuilder(Text.literal("+ New Group"), btn -> {
            showNameInput = true;
            pendingEditGroup = null;
            nameField = new TextFieldWidget(textRenderer, width / 2 - 80, height / 2 - 10, 160, 20, Text.literal("Group name"));
            nameField.setMaxLength(32);
            nameField.setEditableColor(0xFFFFFFFF);
            nameField.setFocused(true);
            addDrawableChild(nameField);
        }).dimensions(width / 2 - 56, bottomY, 80, 20).build());

        addDrawableChild(StyledButton.styledBuilder(Text.literal("Back"), btn -> {
            MinecraftClient.getInstance().setScreen(parent);
        }).dimensions(width / 2 + 28, bottomY, 50, 20).build());
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta);

        context.drawCenteredTextWithShadow(textRenderer, Text.literal("Custom Item Groups"), width / 2, 8, 0xFFFFFFFF);

        List<String> names = config.getCustomGroupNames();
        int startY = 30;
        int rowH = 24;
        for (int i = 0; i < names.size(); i++) {
            String name = names.get(i);
            int y = startY + i * rowH;
            int itemCount = config.getCustomGroup(name).size();
            context.drawTextWithShadow(textRenderer,
                Text.literal("\u00a7e" + name + "\u00a7r \u00a77(" + itemCount + " items)"),
                width / 2 + 28, y + 4, 0xFFFFFFFF);
        }

        if (names.isEmpty()) {
            context.drawCenteredTextWithShadow(textRenderer,
                Text.literal("\u00a77No custom groups yet. Click \"+ New Group\" to create one."),
                width / 2, height / 2 - 20, 0xFFAAAAAA);
        }

        if (showNameInput && nameField != null) {
            int bx = width / 2 - 100;
            int by = height / 2 - 30;
            context.fill(bx, by, bx + 200, by + 80, 0xFF222222);
            context.drawHorizontalLine(bx, bx + 200, by, 0xFF888888);
            context.drawHorizontalLine(bx, bx + 200, by + 80, 0xFF888888);
            context.drawVerticalLine(bx, by, by + 80, 0xFF888888);
            context.drawVerticalLine(bx + 200, by, by + 80, 0xFF888888);
            context.drawCenteredTextWithShadow(textRenderer, Text.literal("Enter group name:"), width / 2, by + 8, 0xFFFFFF55);
            context.drawCenteredTextWithShadow(textRenderer, Text.literal("[Enter] confirm  [Esc] cancel"), width / 2, by + 60, 0xFF888888);
            // Re-render nameField on top of fill (was covered by fill after super.render)
            nameField.render(context, mouseX, mouseY, delta);
        }
    }

    @Override
    public boolean keyPressed(KeyInput keyInput) {
        if (showNameInput && nameField != null) {
            if (keyInput.key() == 256) { // Esc
                showNameInput = false;
                remove(nameField);
                nameField = null;
                return true;
            }
            if (keyInput.key() == 257 || keyInput.key() == 335) { // Enter / numpad Enter
                String name = nameField.getText().trim();
                if (!name.isEmpty()) {
                    showNameInput = false;
                    remove(nameField);
                    nameField = null;
                    if (!config.getCustomGroups().containsKey(name)) {
                        config.setCustomGroup(name, new java.util.ArrayList<>());
                        config.save();
                    }
                    MinecraftClient.getInstance().setScreen(new CustomGroupEditorScreen(this, name));
                }
                return true;
            }
            return nameField.keyPressed(keyInput);
        }
        return super.keyPressed(keyInput);
    }
}
