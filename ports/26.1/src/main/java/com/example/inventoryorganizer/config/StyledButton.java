package com.example.inventoryorganizer.config;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

/**
 * 26.1 port of the dark Minecraft-themed button used across the mod.
 *
 * 1.21.11 made AbstractWidget.renderWidget final, but the parent abstract
 * Button class still exposes extractContents(GuiGraphicsExtractor, ...) for
 * subclass-driven painting. We extend Button directly and override
 * extractContents to draw the same raised 3D bevel + dark fill the
 * older-version StyledButton did.
 */
public class StyledButton extends Button {

    protected StyledButton(int x, int y, int width, int height,
                           Component message, OnPress onPress) {
        super(x, y, width, height, message, onPress, DEFAULT_NARRATION);
    }

    public static Builder styledBuilder(Component message, OnPress onPress) {
        return new Builder(message, onPress);
    }

    @Override
    protected void extractContents(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
        boolean hovered = this.isHoveredOrFocused();
        boolean enabled = this.active;

        int x = getX(), y = getY(), w = getWidth(), h = getHeight();

        // 1. Outer black frame
        context.fill(x, y, x + w, y + h, 0xFF111111);

        // 2. Raised bevel: top + left = lighter highlight
        int hiColor = enabled ? 0xFF686868 : 0xFF383838;
        context.fill(x + 1, y + 1, x + w - 1, y + 3, hiColor);
        context.fill(x + 1, y + 1, x + 3, y + h - 1, hiColor);

        // 3. Raised bevel: bottom + right = darker shadow
        int shColor = enabled ? 0xFF1A1A1A : 0xFF111111;
        context.fill(x + 1, y + h - 3, x + w - 1, y + h - 1, shColor);
        context.fill(x + w - 3, y + 1, x + w - 1, y + h - 1, shColor);

        // 4. Inner background
        int bgColor;
        if (!enabled) bgColor = 0xFF232328;
        else if (hovered) bgColor = 0xFF4A4A5E;
        else bgColor = 0xFF36363F;
        context.fill(x + 3, y + 3, x + w - 3, y + h - 3, bgColor);

        // 5. Subtle top shine on hover
        if (hovered && enabled) {
            context.fill(x + 3, y + 3, x + w - 3, y + 5, 0x22FFFFFF);
        }

        // 6. Text (centered with shadow)
        int textColor = !enabled ? 0xFF686868 : hovered ? 0xFFFFFFFF : 0xFFCCCCCC;
        context.centeredText(
            Minecraft.getInstance().font,
            getMessage(),
            x + w / 2,
            y + (h - 8) / 2,
            textColor
        );
    }

    public static final class Builder {
        private final Component message;
        private final OnPress onPress;
        private int x, y, w = 150, h = 20;

        public Builder(Component message, OnPress onPress) {
            this.message = message;
            this.onPress = onPress;
        }

        public Builder bounds(int x, int y, int w, int h) {
            this.x = x; this.y = y; this.w = w; this.h = h;
            return this;
        }

        public StyledButton build() {
            return new StyledButton(x, y, w, h, message, onPress);
        }
    }
}
