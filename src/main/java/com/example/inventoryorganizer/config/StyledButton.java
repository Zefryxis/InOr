package com.example.inventoryorganizer.config;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

/**
 * A custom-styled button that matches the mod's dark Minecraft-inspired UI theme.
 * Raised 3D bevel (lighter top/left, darker bottom/right), dark background.
 */
public class StyledButton extends ButtonWidget {

    private StyledButton(int x, int y, int width, int height, Text message, PressAction onPress) {
        super(x, y, width, height, message, onPress, DEFAULT_NARRATION_SUPPLIER);
    }

    public static StyledButton.Builder styledBuilder(Text message, PressAction onPress) {
        return new StyledButton.Builder(message, onPress);
    }

    @Override
    protected void renderWidget(DrawContext context, int mouseX, int mouseY, float delta) {
        boolean hovered = this.isSelected();
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

        // 6. Text
        int textColor = !enabled ? 0xFF686868 : hovered ? 0xFFFFFFFF : 0xFFCCCCCC;
        context.drawCenteredTextWithShadow(
            MinecraftClient.getInstance().textRenderer,
            getMessage(),
            x + w / 2,
            y + (h - 8) / 2,
            textColor
        );
    }

    public static class Builder {
        private final Text message;
        private final PressAction onPress;
        private int x, y, w = 150, h = 20;

        public Builder(Text message, PressAction onPress) {
            this.message = message;
            this.onPress = onPress;
        }

        public StyledButton.Builder dimensions(int x, int y, int w, int h) {
            this.x = x; this.y = y; this.w = w; this.h = h;
            return this;
        }

        public StyledButton build() {
            return new StyledButton(x, y, w, h, message, onPress);
        }
    }
}
