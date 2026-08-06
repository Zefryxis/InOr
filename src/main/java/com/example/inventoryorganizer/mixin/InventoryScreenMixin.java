package com.example.inventoryorganizer.mixin;

import com.example.inventoryorganizer.FightModeTracker;
import com.example.inventoryorganizer.InventorySorter;
import com.example.inventoryorganizer.config.ConfigScreenBuilder;
import com.example.inventoryorganizer.config.KitsScreen;
import com.example.inventoryorganizer.config.OrganizerConfig;
import com.example.inventoryorganizer.config.RemoteCraftHudSettings;
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
    @Unique private Button oi$cycleButton = null;

    private InventoryScreenMixin(InventoryMenu handler, Inventory inventory, Component title) {
        super(handler, inventory, title);
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void addOrganizeButton(CallbackInfo ci) {
        // K - Kits
        oi$kButton = Button.builder(
                Component.translatable("inventory-organizer.button.k"),
                button -> Minecraft.getInstance().gui.setScreen(
                        new KitsScreen(Minecraft.getInstance().gui.screen(), false))
        ).bounds(0, 0, 22, 14).build();
        this.addRenderableWidget(oi$kButton);

        // S - Visual Slot Config
        oi$sButton = Button.builder(
                Component.translatable("inventory-organizer.button.s"),
                button -> Minecraft.getInstance().gui.setScreen(
                        new VisualInventoryConfigScreen(Minecraft.getInstance().gui.screen()))
        ).bounds(0, 0, 22, 14).build();
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
        ).bounds(0, 0, 22, 14).build();
        this.addRenderableWidget(oi$button);
        // Preserve fight-mode OI cooldown across screen reopens.
        if (FightModeTracker.isActive() && !FightModeTracker.canUseOI()) {
            oi$button.active = false;
        }
        FightModeTracker.oiButtonRef = oi$button;

        // HUD - open the HUD layout editor.
        oi$hudButton = Button.builder(
                Component.translatable("inventory-organizer.button.hud"),
                button -> Minecraft.getInstance().gui.setScreen(
                        new com.example.inventoryorganizer.config.HudLayoutScreen(Minecraft.getInstance().gui.screen()))
        ).bounds(0, 0, 26, 14).build();
        this.addRenderableWidget(oi$hudButton);

        // Wh - open the Warehouse map (only when the server's InOr handshake says it's available).
        if (com.example.inventoryorganizer.warehouse.WarehouseClient.isAvailable()) {
            oi$whButton = Button.builder(
                    Component.translatable("inventory-organizer.button.wh"),
                    button -> Minecraft.getInstance().gui.setScreen(
                            new com.example.inventoryorganizer.warehouse.WarehouseMapScreen(Minecraft.getInstance().gui.screen()))
            ).bounds(0, 0, 22, 14).build();
            this.addRenderableWidget(oi$whButton);
        }

        // Position-cycle button: move this whole row above/below/right of the inventory panel (right =
        // stacked vertically, since a horizontal row wouldn't fit there). Shared setting with
        // RemoteCraftPanel's chest-list side — a RIGHT/RIGHT clash auto-flips whichever changed last.
        oi$cycleButton = Button.builder(Component.literal("⇅"), button -> {
            RemoteCraftHudSettings s = OrganizerConfig.get().getRemoteCraftHud();
            RemoteCraftHudSettings.ButtonsPos next = switch (s.buttonsPos) {
                case ABOVE -> RemoteCraftHudSettings.ButtonsPos.BELOW;
                case BELOW -> RemoteCraftHudSettings.ButtonsPos.RIGHT;
                case RIGHT -> RemoteCraftHudSettings.ButtonsPos.ABOVE;
            };
            s.setButtonsPos(next);
            OrganizerConfig.get().save();
            com.example.inventoryorganizer.InventoryOrganizerClient.relayoutRemoteCraftUi();
        }).bounds(0, 0, 12, 11)
                .tooltip(net.minecraft.client.gui.components.Tooltip.create(
                        Component.translatable("inventory-organizer.remotecraft.cycle_buttons_pos.tooltip")))
                .build();
        this.addRenderableWidget(oi$cycleButton);

        oi$reposition();
    }

    /**
     * Lay out the K/S/OI/HUD/Wh row (+ its own cycle button) according to the shared
     * {@link RemoteCraftHudSettings#buttonsPos}: ABOVE/BELOW are a horizontal row spanning the panel
     * width; RIGHT stacks the buttons VERTICALLY (a horizontal row would run off-screen there). Called
     * once from init() and again every frame from the render inject, so it always reflects the current
     * setting even when changed elsewhere (e.g. RemoteCraftPanel's own chest-position cycle button).
     */
    @Unique
    private void oi$reposition() {
        if (oi$kButton == null) return;
        final int BTN_H = 14, PANEL_W = 176, GAP = 4;
        RemoteCraftHudSettings s = OrganizerConfig.get().getRemoteCraftHud();

        java.util.List<Button> row = new java.util.ArrayList<>();
        row.add(oi$cycleButton);
        row.add(oi$kButton);
        row.add(oi$sButton);
        row.add(oi$button);
        row.add(oi$hudButton);
        if (oi$whButton != null) row.add(oi$whButton);

        if (s.buttonsPos == RemoteCraftHudSettings.ButtonsPos.RIGHT) {
            int x = this.leftPos + PANEL_W + GAP;
            int y = this.topPos;
            for (Button b : row) { b.setPosition(x, y); y += BTN_H + 2; }
        } else {
            int y = s.buttonsPos == RemoteCraftHudSettings.ButtonsPos.ABOVE
                    ? this.topPos - BTN_H - 2 : this.topPos + this.imageHeight + 2;
            int x = this.leftPos + 2;
            for (Button b : row) { b.setPosition(x, y); x += b.getWidth() + 2; }
        }
    }

    @Inject(method = "extractRenderState", at = @At("TAIL"))
    private void renderOICooldown(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        oi$reposition();
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
