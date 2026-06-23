package com.example.inventoryorganizer.config;

import com.example.inventoryorganizer.warehouse.OstRosterPayload;
import com.example.inventoryorganizer.warehouse.WarehouseClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Owner-only picker: for one warehouse link, toggle per-player whether each known player may OST it.
 * Default is OFF — a revealed link is visible but not sortable until the owner grants OST here.
 *
 * <p>All layout is (re)built in {@link #init()}, so it survives window resizes. The roster comes from
 * the server ({@link WarehouseClient#requestOstRoster}); each toggle echoes a fresh roster back.
 */
public class OstPermScreen extends Screen {

    private final Screen parent;
    private final BlockPos linkPos;
    private int rosterSeen = -1;

    public OstPermScreen(Screen parent, BlockPos linkPos) {
        super(Component.translatable("inventory-organizer.ost.title"));
        this.parent = parent;
        this.linkPos = linkPos;
    }

    @Override
    protected void init() {
        WarehouseClient.requestOstRoster(linkPos);
        rosterSeen = WarehouseClient.ostRosterVersion();
        buildRows();
    }

    private void buildRows() {
        clearWidgets();
        int cx = width / 2;
        int top = 50;
        int rowH = 22;

        OstRosterPayload r = WarehouseClient.getOstRoster();
        List<String> uuids = r != null ? r.uuids() : new ArrayList<>();
        List<String> names = r != null ? r.names() : new ArrayList<>();
        List<Boolean> allowed = r != null ? r.allowed() : new ArrayList<>();

        int maxRows = Math.max(1, (height - top - 50) / rowH);
        for (int i = 0; i < uuids.size() && i < maxRows; i++) {
            final String uuid = uuids.get(i);
            final boolean on = i < allowed.size() && allowed.get(i);
            String name = i < names.size() ? names.get(i) : uuid;
            int y = top + i * rowH;
            Button row = Button.builder(
                    Component.literal((on ? "§a✔ " : "§7✘ ") + name + " §8— "
                            + Component.translatable(on ? "inventory-organizer.ost.can" : "inventory-organizer.ost.cannot").getString()),
                    b -> { WarehouseClient.setOstPerm(linkPos, uuid, !on); }
            ).bounds(cx - 130, y, 260, 20).build();
            addRenderableWidget(row);
        }

        addRenderableWidget(Button.builder(
                Component.translatable("inventory-organizer.button.back"),
                b -> Minecraft.getInstance().setScreen(parent))
                .bounds(cx - 60, height - 34, 120, 20).build());
    }

    @Override
    public void tick() {
        // Rebuild when the server sends an updated roster (e.g. after a toggle).
        if (WarehouseClient.ostRosterVersion() != rosterSeen) {
            rosterSeen = WarehouseClient.ostRosterVersion();
            buildRows();
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
        // Consistent styled backdrop (matches HelpScreen): solid dark fill + a header bar with divider.
        context.fill(0, 0, width, height, 0xFF12121C);
        context.fill(0, 0, width, 40, 0xFF1A1A28);
        context.fill(0, 40, width, 41, 0xFF3A3A5A);
        context.centeredText(font, Component.translatable("inventory-organizer.ost.title"), width / 2, 14, 0xFFFFE066);
        context.centeredText(font, Component.translatable("inventory-organizer.ost.subtitle"), width / 2, 27, 0xFF9090A0);

        OstRosterPayload r = WarehouseClient.getOstRoster();
        if (r == null || r.uuids().isEmpty()) {
            context.centeredText(font, Component.translatable("inventory-organizer.ost.empty"), width / 2, height / 2 - 4, 0xFFAAAAAA);
        }
        super.extractRenderState(context, mouseX, mouseY, delta); // widgets on top
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(parent);
    }
}
