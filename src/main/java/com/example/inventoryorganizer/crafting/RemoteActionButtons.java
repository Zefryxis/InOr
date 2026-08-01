package com.example.inventoryorganizer.crafting;

import com.example.inventoryorganizer.InventoryOrganizerClient;
import com.example.inventoryorganizer.config.OrganizerConfig;
import com.example.inventoryorganizer.config.RemoteCraftHudSettings;
import com.example.inventoryorganizer.warehouse.WarehouseClient;
import com.example.inventoryorganizer.warehouse.WarehouseMapScreen;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Utility action-button group for the crafting table and survival inventory screens: OI (organize
 * inventory), Kit, Settings, and Warehouse map — the same quick-action idea the chest screen offers,
 * minus anything that needs an actual open chest (OST, deposit, refill, per-chest profiles).
 *
 * <p>Independent of {@link RemoteCraftPanel}'s chest-list section (which keeps its own search/qty/
 * scroll controls glued to it — those are NOT part of this group). Position (above/below/right of the
 * GUI) is shared with the chest list's side via {@link RemoteCraftHudSettings}, auto-resolving a clash
 * if both would land on the right (see {@link RemoteCraftHudSettings#setButtonsPos}). When positioned
 * to the right, the buttons stack VERTICALLY — a horizontal row wouldn't fit there.
 */
public final class RemoteActionButtons {

    private static final int GUI_W = 176, GUI_H = 166, GAP = 4;
    private static final int ROW_BTN_W = 40, ROW_BTN_H = 14;
    private static final int COL_BTN_W = 46, COL_BTN_H = 14;

    private final AbstractContainerScreen<?> screen;
    private final List<Button> buttons = new ArrayList<>();

    public RemoteActionButtons(AbstractContainerScreen<?> screen) {
        this.screen = screen;
        rebuild();
    }

    /** Re-run the layout (e.g. after the shared position setting changed, whether from this group's own
     *  cycle button or from {@link RemoteCraftPanel}'s chest-position cycle auto-flipping this one). */
    public void relayout() { rebuild(); }

    private void rebuild() {
        for (Button b : buttons) Screens.getWidgets(screen).remove(b);
        buttons.clear();

        int guiLeft = (screen.width - GUI_W) / 2;
        int guiTop = (screen.height - GUI_H) / 2;
        try {
            com.example.inventoryorganizer.mixin.ContainerScreenAccessor acc =
                    (com.example.inventoryorganizer.mixin.ContainerScreenAccessor) screen;
            guiLeft = acc.inorLeftPos();
            guiTop = acc.inorTopPos();
        } catch (Throwable ignored) {}
        int guiRight = guiLeft + GUI_W;
        int guiBottom = guiTop + GUI_H;

        RemoteCraftHudSettings s = OrganizerConfig.get().getRemoteCraftHud();
        boolean vertical = s.buttonsPos == RemoteCraftHudSettings.ButtonsPos.RIGHT;

        List<Button> made = new ArrayList<>();
        made.add(cycleButton());
        made.add(oiButton());
        made.add(kitButton());
        made.add(settingsButton());
        if (WarehouseClient.isAvailable()) made.add(whButton());

        if (vertical) {
            int x = guiRight + GAP, y = guiTop;
            for (Button b : made) { b.setPosition(x, y); b.setWidth(COL_BTN_W); y += COL_BTN_H + 2; }
        } else {
            int y = s.buttonsPos == RemoteCraftHudSettings.ButtonsPos.ABOVE
                    ? guiTop - GAP - ROW_BTN_H : guiBottom + GAP;
            int x = guiLeft;
            for (Button b : made) { b.setPosition(x, y); b.setWidth(ROW_BTN_W); x += ROW_BTN_W + 2; }
        }

        for (Button b : made) { Screens.getWidgets(screen).add(b); buttons.add(b); }
    }

    private Button cycleButton() {
        return Button.builder(Component.literal("⇅"), b -> {
            RemoteCraftHudSettings s = OrganizerConfig.get().getRemoteCraftHud();
            RemoteCraftHudSettings.ButtonsPos next = switch (s.buttonsPos) {
                case ABOVE -> RemoteCraftHudSettings.ButtonsPos.BELOW;
                case BELOW -> RemoteCraftHudSettings.ButtonsPos.RIGHT;
                case RIGHT -> RemoteCraftHudSettings.ButtonsPos.ABOVE;
            };
            s.setButtonsPos(next);
            OrganizerConfig.get().save();
            InventoryOrganizerClient.relayoutRemoteCraftUi();
        }).bounds(0, 0, ROW_BTN_W, ROW_BTN_H)
                .tooltip(Tooltip.create(Component.translatable("inventory-organizer.remotecraft.cycle_buttons_pos.tooltip")))
                .build();
    }

    private Button oiButton() {
        return Button.builder(Component.translatable("inventory-organizer.button.oi"),
                b -> InventoryOrganizerClient.doOI(Minecraft.getInstance(), "free"))
                .bounds(0, 0, ROW_BTN_W, ROW_BTN_H).build();
    }

    private Button kitButton() {
        return Button.builder(Component.translatable("inventory-organizer.button.k"),
                b -> InventoryOrganizerClient.handleScreenOpenKeybind(Minecraft.getInstance(), "free", true))
                .bounds(0, 0, ROW_BTN_W, ROW_BTN_H).build();
    }

    private Button settingsButton() {
        return Button.builder(Component.translatable("inventory-organizer.button.s"),
                b -> InventoryOrganizerClient.handleScreenOpenKeybind(Minecraft.getInstance(), "free", false))
                .bounds(0, 0, ROW_BTN_W, ROW_BTN_H).build();
    }

    private Button whButton() {
        return Button.builder(Component.translatable("inventory-organizer.button.wh"), b -> {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null) mc.player.closeContainer();
            mc.gui.setScreen(new WarehouseMapScreen(null));
        }).bounds(0, 0, ROW_BTN_W, ROW_BTN_H).build();
    }
}
