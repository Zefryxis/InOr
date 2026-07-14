package com.example.inventoryorganizer.crafting;

import com.example.inventoryorganizer.config.VisualInventoryConfigScreen;
import com.example.inventoryorganizer.warehouse.WarehouseClient;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Remote-crafting materials panel attached to the crafting table screen. Shows the combined contents
 * of the player's nearby known chests (icon + name + count) as a scrollable, searchable list; clicking
 * a row withdraws the chosen amount (the "Qty" box) of that item into the player's inventory.
 *
 * <p>Built from Button/EditBox widgets (reliable clicks) plus an {@code afterExtract} render pass that
 * draws the item icons, header and labels on top.
 */
public final class RemoteCraftPanel {

    private static final int ROW_H = 18;

    /** One line in the panel: either a chest-name HEADER or an ITEM row (withdraw from that chest). */
    private static final class Row {
        final boolean header;
        final String text;                 // header: chest name
        final String id; final int count;  // item: registry id + count in this chest
        final net.minecraft.core.BlockPos chest;
        Row(String headerText) { this.header = true; this.text = headerText; this.id = null; this.count = 0; this.chest = null; }
        Row(String id, int count, net.minecraft.core.BlockPos chest) { this.header = false; this.text = null; this.id = id; this.count = count; this.chest = chest; }
    }

    private final AbstractContainerScreen<?> screen;
    private final List<Button> rowButtons = new ArrayList<>();
    private final List<int[]> rowIcons = new ArrayList<>(); // [iconX, iconY] per visible item row, for the render pass
    private final List<String> rowIds = new ArrayList<>();   // item id per visible item row (icon)
    private final List<int[]> headerDraw = new ArrayList<>(); // [x, y] per visible header row
    private final List<String> headerText = new ArrayList<>();
    private EditBox search;
    private EditBox qty;
    private int panelX, panelY, panelW, visRows;
    private int listTopY, listBottomY;         // vertical bounds of the scrollable list (for hit-testing scroll)
    private int depositX, depositY;            // the deposit slot (put a held item here → into a chest)
    private static final int DEP = 18;         // deposit slot size
    private int scroll = 0;
    private int stockSeen = -1;
    private int tick = 0;
    private List<Row> allRows = new ArrayList<>();

    public RemoteCraftPanel(AbstractContainerScreen<?> screen) {
        this.screen = screen;
        layout();
        Minecraft mc = Minecraft.getInstance();

        search = new EditBox(mc.font, panelX, panelY, panelW - 32, 14, Component.literal("Search"));
        search.setHint(Component.literal("§7Search…"));
        search.setResponder(s -> { scroll = 0; rebuild(); });
        Screens.getWidgets(screen).add(search);

        Screens.getWidgets(screen).add(Button.builder(Component.literal("▲"), b -> {
            scroll = Math.max(0, scroll - 1); rebuild();
        }).bounds(panelX + panelW - 30, panelY, 14, 14).build());
        Screens.getWidgets(screen).add(Button.builder(Component.literal("▼"), b -> {
            scroll++; rebuild();
        }).bounds(panelX + panelW - 15, panelY, 14, 14).build());

        // Qty box (how many to pull per click). Default 1.
        qty = new EditBox(mc.font, panelX + 26, panelY + 16, 44, 14, Component.literal("Qty"));
        qty.setValue("1");
        qty.setMaxLength(5);
        Screens.getWidgets(screen).add(qty);

        // Deposit slot: a click while holding an item on the cursor sends the held stack to a nearby chest
        // (the server reads the cursor stack and sorts the chest). The button is invisible-ish under the
        // slot graphic drawn in render(); clicking the region triggers the deposit.
        Screens.getWidgets(screen).add(Button.builder(Component.literal(""), b -> {
            // Holding an item → deposit it into a chest. Empty hand → "cancel recipe": send the whole
            // crafting grid back to the chests (each ingredient → a chest, each chest OST'd).
            boolean holding = mc.player != null && !mc.player.containerMenu.getCarried().isEmpty();
            if (holding) WarehouseClient.depositCarried();
            else WarehouseClient.returnGrid();
            WarehouseClient.requestCraftStock();
        }).bounds(depositX, depositY, DEP, DEP).build());

        WarehouseClient.requestCraftStock();
        rebuild();
    }

    /** GUI dimensions of a crafting/inventory screen (vanilla constants). */
    private static final int GUI_W = 176, GUI_H = 166;

    private void layout() {
        int guiLeft = (screen.width - GUI_W) / 2;
        int guiTop = (screen.height - GUI_H) / 2;
        try {
            com.example.inventoryorganizer.mixin.ContainerScreenAccessor acc =
                    (com.example.inventoryorganizer.mixin.ContainerScreenAccessor) screen;
            guiLeft = acc.inorLeftPos();
            guiTop = acc.inorTopPos();
        } catch (Throwable ignored) {}

        // Glue the panel to the GUI's RIGHT edge and let it MOVE WITH the GUI. layout() re-runs on every
        // screen init — including window resize AND the recipe-book toggle (which shifts leftPos sideways)
        // — so reading the live leftPos here keeps the panel attached to the GUI at any resolution / GUI
        // scale. (screen.width/height are already in scaled GUI units, so widths sized from them adapt to
        // the GUI-Scale option automatically.)
        final int GAP = 4, MIN_W = 86, MAX_W = 160, MARGIN = 4;
        int guiRight = guiLeft + GUI_W;
        // Adaptive width: fill the free space between the GUI's right edge and the screen edge, clamped.
        int avail = screen.width - guiRight - GAP - MARGIN;
        panelW = Math.max(MIN_W, Math.min(MAX_W, avail));
        panelX = guiRight + GAP;
        // Overflow guard for tiny resolutions / large GUI scale: if the panel would run off the right
        // edge, pull it left so it stays fully on-screen (slight GUI overlap beats being clipped away).
        if (panelX + panelW + MARGIN > screen.width) {
            panelX = Math.max(MARGIN, screen.width - panelW - MARGIN);
        }

        panelY = Math.max(16, guiTop);
        listTopY = panelY + 34;
        listBottomY = screen.height - 8;
        visRows = Math.max(3, Math.min(14, (listBottomY - listTopY) / ROW_H));
        // Deposit slot: directly UNDER the GUI (the player inventory), centred on the 176-wide GUI, so it
        // tracks the GUI when the recipe book slides it sideways (layout() re-runs on every re-init).
        depositX = guiLeft + (GUI_W - DEP) / 2;
        depositY = guiTop + GUI_H + 3;
    }

    /** The screen this panel is attached to (used by the client to self-heal a lost panel). */
    public AbstractContainerScreen<?> screen() { return screen; }

    /** True while the player is typing in the search or qty box — used to suppress keybinds. */
    public boolean isTyping() {
        return (search != null && search.isFocused()) || (qty != null && qty.isFocused());
    }

    /** True if our widgets are still attached to the screen (false after a re-layout that cleared them). */
    public boolean isAttached() {
        return search != null && Screens.getWidgets(screen).contains(search);
    }


    private int amount() {
        try { return Math.max(1, Math.min(2304, Integer.parseInt(qty.getValue().trim()))); }
        catch (Exception e) { return 1; }
    }

    /** Called each client tick while this crafting screen is open: refresh the stock periodically. */
    public void tick() {
        // Refresh ~4x/second so the chest stock (green highlight) and the server-side chest-list cache
        // used by the recipe-book place hook are fresh — avoids "not detected on the first click".
        if ((++tick % 5) == 0) WarehouseClient.requestCraftStock();
        if (WarehouseClient.craftStockVersion() != stockSeen) {
            stockSeen = WarehouseClient.craftStockVersion();
            rebuild();
        }
    }

    /** Scroll the list by {@code d} rows (called by the screen's mouse-wheel hook when over the panel). */
    public void scrollBy(int d) {
        int max = Math.max(0, allRows.size() - visRows);
        int ns = Math.max(0, Math.min(scroll + d, max));
        if (ns != scroll) { scroll = ns; rebuild(); }
    }

    /** Is the given screen position inside the scrollable list area? (for the mouse-wheel hook). */
    public boolean isOverList(double mx, double my) {
        return mx >= panelX - 4 && mx <= panelX + panelW + 4 && my >= listTopY - 2 && my <= listBottomY;
    }

    private void rebuild() {
        for (Button b : rowButtons) Screens.getWidgets(screen).remove(b);
        rowButtons.clear();
        rowIcons.clear();
        rowIds.clear();
        headerDraw.clear();
        headerText.clear();

        // Build the flat row model: one HEADER per chest, then its ITEM rows (filtered by search).
        String q = search != null ? search.getValue().trim().toLowerCase() : "";
        boolean searching = !q.isEmpty();
        allRows = new ArrayList<>();
        for (WarehouseClient.ChestStockView cv : WarehouseClient.getCraftChests()) {
            List<Map.Entry<String, Integer>> items = new ArrayList<>();
            for (Map.Entry<String, Integer> e : cv.items().entrySet()) {
                if (e.getValue() <= 0) continue;
                if (searching && !displayName(e.getKey()).toLowerCase().contains(q)
                        && !e.getKey().toLowerCase().contains(q)) continue;
                items.add(e);
            }
            // While searching, hide chests with no match. With no search, show EVERY chest (even empty).
            if (searching && items.isEmpty()) continue;
            items.sort(Comparator.comparing(e -> displayName(e.getKey())));
            String name = profileName(cv.pos(), cv.name());        // prefer the per-chest PROFILE name
            allRows.add(new Row("§6▸ §e" + name + (items.isEmpty() ? " §8(empty)" : "")));
            for (Map.Entry<String, Integer> e : items) allRows.add(new Row(e.getKey(), e.getValue(), cv.pos()));
        }

        int maxScroll = Math.max(0, allRows.size() - visRows);
        scroll = Math.max(0, Math.min(scroll, maxScroll));

        int y = listTopY;
        for (int i = scroll; i < allRows.size() && i < scroll + visRows; i++) {
            Row r = allRows.get(i);
            if (r.header) {
                headerDraw.add(new int[]{panelX + 1, y + 5});
                headerText.add(clip(r.text, panelW + 24));
            } else {
                final String id = r.id;
                final net.minecraft.core.BlockPos chest = r.chest;
                String label = "  §e" + r.count + "§7x §f" + clip(displayName(id), panelW);
                Button row = Button.builder(Component.literal(label), b -> {
                    WarehouseClient.withdraw(id, amount(), chest); // withdraw from THIS chest
                    WarehouseClient.requestCraftStock();
                }).bounds(panelX, y, panelW, ROW_H).build();
                Screens.getWidgets(screen).add(row);
                rowButtons.add(row);
                rowIcons.add(new int[]{panelX + 2, y + (ROW_H - 16) / 2});
                rowIds.add(id);
            }
            y += ROW_H;
        }
    }

    /** Render pass (afterExtract): decorated panel, header, Qty label, chest-name headers and item icons. */
    public void render(GuiGraphicsExtractor context) {
        Minecraft mc = Minecraft.getInstance();
        // Decorated FRAME only (no body fill — a fill here is drawn over the buttons and dims them).
        int bx = panelX - 4, by = panelY - 14, bw = panelW + 8, bh = (listBottomY + 4) - by;
        context.fill(bx, by, bx + bw, by + 1, 0xFFD8A24A);                          // top accent
        context.fill(bx, by + bh - 1, bx + bw, by + bh, 0xFFD8A24A);                // bottom accent
        context.fill(bx, by, bx + 1, by + bh, 0xFF6E5430);                          // left edge
        context.fill(bx + bw - 1, by, bx + bw, by + bh, 0xFF6E5430);                // right edge
        // Separator under the search/qty header area.
        context.fill(bx + 2, listTopY - 3, bx + bw - 2, listTopY - 2, 0x66D8A24A);

        int reach = (int) WarehouseClient.craftReach();
        context.text(mc.font, Component.literal("§6§lMaterials §7(" + reach + "m)"), panelX, panelY - 11, 0xFFFFFFFF);
        context.text(mc.font, Component.literal("§7Qty:"), panelX, panelY + 19, 0xFFAAAAAA);
        // Chest-name section headers.
        for (int i = 0; i < headerDraw.size(); i++) {
            int[] xy = headerDraw.get(i);
            context.fill(panelX - 2, xy[1] - 4, panelX + panelW, xy[1] - 3, 0x55D8A24A); // thin rule above name
            context.text(mc.font, Component.literal(headerText.get(i)), xy[0], xy[1], 0xFFFFE0A0);
        }
        // Item icons.
        for (int i = 0; i < rowIcons.size(); i++) {
            ItemStack icon = stackOf(rowIds.get(i));
            if (!icon.isEmpty()) {
                int[] xy = rowIcons.get(i);
                VisualInventoryConfigScreen.drawItemIcon(context, icon, xy[0], xy[1]);
            }
        }
        // Scroll hint when there's more than fits.
        if (allRows.size() > visRows) {
            context.text(mc.font, Component.literal("§7scroll ⬍"), panelX + panelW - 40, panelY + 19, 0xFF888888);
        }

        // Deposit slot, drawn on top of its (click-catching) button so it looks like a real vanilla slot.
        // Sits directly under the inventory and tracks the GUI. MC slot styling: dark inset + bevel.
        int x = depositX, y = depositY;
        context.fill(x - 1, y - 1, x + DEP + 1, y + DEP + 1, 0xFF373737);          // outer frame
        context.fill(x, y, x + DEP, y + DEP, 0xFF8B8B8B);                          // light face
        context.fill(x, y, x + DEP - 1, y + DEP - 1, 0xFF373737);                  // top-left shadow
        context.fill(x + 1, y + 1, x + DEP - 1, y + DEP - 1, 0xFF8B8B8B);          // bottom-right light
        context.fill(x + 1, y + 1, x + DEP - 2, y + DEP - 2, 0xFF2B2B33);          // inner well (slightly blue-dark)
        // Downward arrow hint = "drop a held item here → it goes to a nearby chest".
        context.text(mc.font, Component.literal("§b⬇"), x + (DEP - mc.font.width("⬇")) / 2, y + 5, 0xFF66CCFF);
        // Label centred under the slot. Two uses: drop a held item to stash it, or click empty-handed to
        // send the whole crafting grid back to the chests (cancel the recipe).
        Component lbl = Component.literal("§7→ chest");
        context.text(mc.font, lbl, x + DEP / 2 - mc.font.width(lbl.getString()) / 2, y + DEP + 2, 0xFFAAAAAA);
        Component lbl2 = Component.literal("§8(empty hand = grid→chest)");
        context.text(mc.font, lbl2, x + DEP / 2 - mc.font.width(lbl2.getString()) / 2, y + DEP + 12, 0xFF888888);
    }

    /** The per-chest PROFILE name bound to this position (mod storage profile), else the server fallback. */
    private static String profileName(net.minecraft.core.BlockPos pos, String fallback) {
        try {
            com.example.inventoryorganizer.config.StoragePreset p =
                    com.example.inventoryorganizer.config.OrganizerConfig.get()
                            .findProfileFor(null, java.util.List.of(new int[]{pos.getX(), pos.getY(), pos.getZ()}), null);
            if (p != null && p.getName() != null && !p.getName().isEmpty()) return p.getName();
        } catch (Throwable ignored) {}
        return fallback;
    }

    private static ItemStack stackOf(String id) {
        Identifier rid = Identifier.tryParse(id);
        if (rid == null) return ItemStack.EMPTY;
        Item item = BuiltInRegistries.ITEM.getValue(rid);
        return VisualInventoryConfigScreen.safeIcon(item);
    }

    /** Pretty name from a registry id ("minecraft:oak_planks" → "Oak Planks"). */
    private static String displayName(String id) {
        String path = id.contains(":") ? id.substring(id.indexOf(':') + 1) : id;
        String[] parts = path.split("_");
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p.isEmpty()) continue;
            sb.append(Character.toUpperCase(p.charAt(0))).append(p.substring(1)).append(' ');
        }
        return sb.toString().trim();
    }

    private static String clip(String s, int panelW) {
        int max = Math.max(6, (panelW - 46) / 6);
        return s.length() > max ? s.substring(0, max - 1) + "…" : s;
    }
}
