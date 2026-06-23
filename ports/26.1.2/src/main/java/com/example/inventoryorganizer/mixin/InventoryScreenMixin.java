package com.example.inventoryorganizer.mixin;

import com.example.inventoryorganizer.FightModeTracker;
import com.example.inventoryorganizer.InventorySorter;
import com.example.inventoryorganizer.config.ConfigScreenBuilder;
import com.example.inventoryorganizer.config.KitsScreen;
import com.example.inventoryorganizer.config.VisualInventoryConfigScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(InventoryScreen.class)
public abstract class InventoryScreenMixin extends AbstractContainerScreen<InventoryMenu> {

    @Unique private Button oi$button = null;
    @Unique private Button oi$kButton = null;
    @Unique private Button oi$sButton = null;
    @Unique private Button oi$hudButton = null;
    @Unique private Button oi$whButton = null;

    private InventoryScreenMixin(InventoryMenu handler, Inventory inventory, Component title) {
        super(handler, inventory, title);
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void addOrganizeButton(CallbackInfo ci) {
        // Place buttons above the inventory panel; if not enough vertical space, place to the right of it
        final int BTN_H = 14;
        final int PANEL_W = 176;

        boolean enoughSpaceAbove = this.topPos >= (BTN_H + 4);
        int buttonY = enoughSpaceAbove ? this.topPos - BTN_H - 2 : this.topPos + 4;
        int startX  = enoughSpaceAbove ? this.leftPos + 2         : this.leftPos + PANEL_W + 4;

        // K - Kits
        oi$kButton = Button.builder(
                Component.translatable("inventory-organizer.button.k"),
                button -> Minecraft.getInstance().setScreen(
                        new KitsScreen(Minecraft.getInstance().screen, false))
        ).bounds(startX, buttonY, 22, 14).build();
        this.addRenderableWidget(oi$kButton);

        // S - Visual Slot Config
        oi$sButton = Button.builder(
                Component.translatable("inventory-organizer.button.s"),
                button -> Minecraft.getInstance().setScreen(
                        new VisualInventoryConfigScreen(Minecraft.getInstance().screen))
        ).bounds(startX + 24, buttonY, 22, 14).build();
        this.addRenderableWidget(oi$sButton);

        // OI - Organize Inventory (fight mode aware)
        oi$button = Button.builder(
                Component.translatable("inventory-organizer.button.oi"),
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
        ).bounds(startX + 48, buttonY, 22, 14).build();
        this.addRenderableWidget(oi$button);
        // Preserve fight-mode OI cooldown across screen reopens.
        if (FightModeTracker.isActive() && !FightModeTracker.canUseOI()) {
            oi$button.active = false;
        }
        FightModeTracker.oiButtonRef = oi$button;

        // HUD - open the HUD layout editor.
        oi$hudButton = Button.builder(
                Component.translatable("inventory-organizer.button.hud"),
                button -> Minecraft.getInstance().setScreen(
                        new com.example.inventoryorganizer.config.HudLayoutScreen(Minecraft.getInstance().screen))
        ).bounds(startX + 72, buttonY, 26, 14).build();
        this.addRenderableWidget(oi$hudButton);

        // Wh - open the Warehouse map (only when the server's InOr handshake says it's available).
        if (com.example.inventoryorganizer.warehouse.WarehouseClient.isAvailable()) {
            oi$whButton = Button.builder(
                    Component.translatable("inventory-organizer.button.wh"),
                    button -> Minecraft.getInstance().setScreen(
                            new com.example.inventoryorganizer.warehouse.WarehouseMapScreen(Minecraft.getInstance().screen))
            ).bounds(startX + 100, buttonY, 22, 14).build();
            this.addRenderableWidget(oi$whButton);
        }
    }

    @Inject(method = "extractRenderState", at = @At("TAIL"))
    private void renderOICooldown(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        if (oi$button == null || !FightModeTracker.isActive()) return;
        long remaining = FightModeTracker.remainingOICooldownMs();
        if (remaining <= 0) return;
        context.text(
            Minecraft.getInstance().font,
            Component.literal(remaining + "ms"),
            oi$button.getX() + oi$button.getWidth() + 3,
            oi$button.getY() + 3,
            0xFFFF4444
        );
    }

}
