package com.example.inventoryorganizer.mixin;

import com.example.inventoryorganizer.FightModeTracker;
import com.example.inventoryorganizer.InventorySorter;
import com.example.inventoryorganizer.config.KitsScreen;
import com.example.inventoryorganizer.config.VisualInventoryConfigScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(CreativeModeInventoryScreen.class)
public abstract class CreativeInventoryScreenMixin extends Screen {

    @Unique private Button cre$kButton = null;
    @Unique private Button cre$sButton = null;
    @Unique private Button cre$oiButton = null;
    @Unique private Button cre$hudButton = null;
    @Unique private Button cre$whButton = null;

    protected CreativeInventoryScreenMixin(Component title) {
        super(title);
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void addOrganizeButtons(CallbackInfo ci) {
        // Create the buttons unconditionally. The creative screen does NOT re-run init() on tab
        // switches, so gating creation on isInventoryOpen() at init time made the buttons appear
        // only when the inventory tab happened to be selected on open (and never afterwards / or
        // linger over other tabs). Instead we always build them and toggle .visible per frame.
        boolean invOpen = ((CreativeModeInventoryScreen)(Object)this).isInventoryOpen();

        int panelX = (this.width - 195) / 2;
        int panelY = (this.height - 136) / 2;
        // If not enough space above panel (incl. creative tabs ~28px + buttons 14px + margin), place below
        final int BTN_H = 14;
        boolean enoughSpaceAbove = panelY >= 44; // 28 tab height + 14 btn + 2 margin
        int buttonY = enoughSpaceAbove ? panelY - BTN_H - 28 : panelY + 136 + 4;
        int startX = panelX + 2;

        cre$kButton = Button.builder(
                Component.translatable("inventory-organizer.button.k"),
                button -> Minecraft.getInstance().setScreen(
                        new KitsScreen(Minecraft.getInstance().screen, false))
        ).bounds(startX, buttonY, 22, 14).build();
        cre$kButton.visible = invOpen;
        this.addRenderableWidget(cre$kButton);

        cre$sButton = Button.builder(
                Component.translatable("inventory-organizer.button.s"),
                button -> Minecraft.getInstance().setScreen(
                        new VisualInventoryConfigScreen(Minecraft.getInstance().screen))
        ).bounds(startX + 24, buttonY, 22, 14).build();
        cre$sButton.visible = invOpen;
        this.addRenderableWidget(cre$sButton);

        cre$oiButton = Button.builder(
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
        cre$oiButton.visible = invOpen;
        this.addRenderableWidget(cre$oiButton);
        if (FightModeTracker.isActive() && !FightModeTracker.canUseOI()) {
            cre$oiButton.active = false;
        }
        FightModeTracker.oiButtonRef = cre$oiButton;

        // HUD - open the HUD layout editor.
        cre$hudButton = Button.builder(
                Component.translatable("inventory-organizer.button.hud"),
                button -> Minecraft.getInstance().setScreen(
                        new com.example.inventoryorganizer.config.HudLayoutScreen(Minecraft.getInstance().screen))
        ).bounds(startX + 72, buttonY, 26, 14).build();
        cre$hudButton.visible = invOpen;
        this.addRenderableWidget(cre$hudButton);

        // Wh - Warehouse map (only when the server's InOr handshake says it's available).
        if (com.example.inventoryorganizer.warehouse.WarehouseClient.isAvailable()) {
            cre$whButton = Button.builder(
                    Component.translatable("inventory-organizer.button.wh"),
                    button -> Minecraft.getInstance().setScreen(
                            new com.example.inventoryorganizer.warehouse.WarehouseMapScreen(Minecraft.getInstance().screen))
            ).bounds(startX + 100, buttonY, 22, 14).build();
            cre$whButton.visible = invOpen;
            this.addRenderableWidget(cre$whButton);
        }
    }

    @Inject(method = "extractRenderState", at = @At("TAIL"))
    private void renderOICooldown(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        // Keep button visibility in sync with the currently-selected creative tab (the screen does
        // not re-init on tab switches, so this per-frame check is what shows/hides them correctly).
        boolean invOpen = ((CreativeModeInventoryScreen)(Object)this).isInventoryOpen();
        if (cre$kButton != null) cre$kButton.visible = invOpen;
        if (cre$sButton != null) cre$sButton.visible = invOpen;
        if (cre$oiButton != null) cre$oiButton.visible = invOpen;
        if (cre$hudButton != null) cre$hudButton.visible = invOpen;
        if (cre$whButton != null) cre$whButton.visible = invOpen;

        if (cre$oiButton == null || !invOpen || !FightModeTracker.isActive()) return;
        long remaining = FightModeTracker.remainingOICooldownMs();
        if (remaining <= 0) return;
        context.text(
            Minecraft.getInstance().font,
            Component.literal(remaining + "ms"),
            cre$oiButton.getX() + cre$oiButton.getWidth() + 3,
            cre$oiButton.getY() + 3,
            0xFFFF4444
        );
    }

}
