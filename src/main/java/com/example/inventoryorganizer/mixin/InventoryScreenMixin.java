package com.example.inventoryorganizer.mixin;

import com.example.inventoryorganizer.FightModeTracker;
import com.example.inventoryorganizer.InventorySorter;
import com.example.inventoryorganizer.config.ConfigScreenBuilder;
import com.example.inventoryorganizer.config.KitsScreen;
import com.example.inventoryorganizer.config.VisualInventoryConfigScreen;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.screen.PlayerScreenHandler;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(InventoryScreen.class)
public abstract class InventoryScreenMixin extends HandledScreen<PlayerScreenHandler> {

    @Unique private ButtonWidget oi$button = null;
    @Unique private ButtonWidget oi$kButton = null;
    @Unique private ButtonWidget oi$sButton = null;

    private InventoryScreenMixin(PlayerScreenHandler handler, PlayerInventory inventory, Text title) {
        super(handler, inventory, title);
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void addOrganizeButton(CallbackInfo ci) {
        // Place buttons above the inventory panel; if not enough vertical space, place to the right of it
        final int BTN_H = 14;
        final int PANEL_W = 176;

        boolean enoughSpaceAbove = this.y >= (BTN_H + 4);
        int buttonY = enoughSpaceAbove ? this.y - BTN_H - 2 : this.y + 4;
        int startX  = enoughSpaceAbove ? this.x + 2         : this.x + PANEL_W + 4;

        // K - Kits
        oi$kButton = ButtonWidget.builder(
                Text.translatable("inventory-organizer.button.k"),
                button -> MinecraftClient.getInstance().setScreen(
                        new KitsScreen(MinecraftClient.getInstance().currentScreen))
        ).dimensions(startX, buttonY, 22, 14).build();
        this.addDrawableChild(oi$kButton);

        // S - Visual Slot Config
        oi$sButton = ButtonWidget.builder(
                Text.translatable("inventory-organizer.button.s"),
                button -> MinecraftClient.getInstance().setScreen(
                        new VisualInventoryConfigScreen(MinecraftClient.getInstance().currentScreen))
        ).dimensions(startX + 24, buttonY, 22, 14).build();
        this.addDrawableChild(oi$sButton);

        // OI - Organize Inventory (fight mode aware)
        oi$button = ButtonWidget.builder(
                Text.translatable("inventory-organizer.button.oi"),
                button -> {
                    if (FightModeTracker.isActive()) {
                        if (!FightModeTracker.canUseOI()) return;
                        FightModeTracker.markOIUsed();
                        button.active = false; // disable immediately – re-enabled by ClientTickEvents after 130ms
                        InventorySorter.sortInventoryFightMode();
                    } else {
                        InventorySorter.sortInventory();
                    }
                }
        ).dimensions(startX + 48, buttonY, 22, 14).build();
        this.addDrawableChild(oi$button);
        // Preserve fight-mode OI cooldown across screen reopens.
        if (FightModeTracker.isActive() && !FightModeTracker.canUseOI()) {
            oi$button.active = false;
        }
        FightModeTracker.oiButtonRef = oi$button;
    }

    @Inject(method = "render", at = @At("TAIL"))
    private void renderOICooldown(DrawContext context, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        if (oi$button == null || !FightModeTracker.isActive()) return;
        long remaining = FightModeTracker.remainingOICooldownMs();
        if (remaining <= 0) return;
        context.drawTextWithShadow(
            MinecraftClient.getInstance().textRenderer,
            Text.literal(remaining + "ms"),
            oi$button.getX() + oi$button.getWidth() + 3,
            oi$button.getY() + 3,
            0xFFFF4444
        );
    }

}
