package com.example.inventoryorganizer.mixin;

import com.example.inventoryorganizer.config.VisualInventoryConfigScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsScreen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(OptionsScreen.class)
public abstract class OptionsScreenMixin extends Screen {

    @Unique private Button opt$inorButton = null;

    protected OptionsScreenMixin(Component title) {
        super(title);
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void addInorSettingsButton(CallbackInfo ci) {
        // Find the bottom-most Button (Done) — language-independent
        Button doneBtn = null;
        int maxBtnY = -1;
        for (var element : this.children()) {
            if (element instanceof Button btn && btn != opt$inorButton) {
                if (btn.getY() > maxBtnY) {
                    maxBtnY = btn.getY();
                    doneBtn = btn;
                }
            }
        }

        int btnW, btnX, btnY;
        if (doneBtn != null) {
            int doneRight = doneBtn.getX() + doneBtn.getWidth();
            int spaceRight = this.width - 5 - doneRight - 5;
            if (spaceRight >= 80) {
                // Place to the right of Done at the same Y — never overlaps
                btnW = Math.min(160, spaceRight);
                btnX = doneRight + 5;
                btnY = doneBtn.getY();
            } else {
                // Not enough space right — place below Done
                btnW = Math.min(200, this.width - 20);
                btnX = this.width / 2 - btnW / 2;
                btnY = doneBtn.getY() + doneBtn.getHeight() + 4;
            }
        } else {
            btnW = Math.min(200, this.width - 20);
            btnX = this.width / 2 - btnW / 2;
            btnY = this.height - 50;
        }

        opt$inorButton = Button.builder(
                Component.translatable("inventory-organizer.button.inor_settings"),
                button -> Minecraft.getInstance().setScreen(
                        new VisualInventoryConfigScreen(Minecraft.getInstance().screen))
        ).bounds(btnX, btnY, btnW, 20).build();
        this.addRenderableWidget(opt$inorButton);
    }

}
