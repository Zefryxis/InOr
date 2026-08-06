package com.example.inventoryorganizer.crafting;

import com.example.inventoryorganizer.config.OrganizerConfig;
import com.example.inventoryorganizer.config.RemoteCraftHudSettings;
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
 *
 * <p>The chest section (search/qty/scroll + the scrollable list — these always move together, see
 * {@link RemoteCraftHudSettings#chestPos}) can sit on the left or right of the GUI, so the panel can be
 * moved out of the way of other mods (e.g. JEI) that also dock to the right. The freely-draggable
 * <b>deposit</b> ("send item to chest") slot is positioned independently. The separate utility action
 * buttons (OI/Kit/Settings/Wh) live in {@link RemoteActionButtons}, not here.
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
    private Button upBtn, downBtn, cycleBtn;

    // Chest section: header/search/qty/scroll + the scrollable list. All glued together, left or right.
    private int panelX, panelY, panelW;
    private int listTopY, listBottomY;         // vertical bounds of the scrollable list (for hit-testing scroll)
    private int visRows;

    // Deposit slot (put a held item here → into a chest); freely draggable independently of the above.
    private int depositX, depositY;
    private static final int DEP = 18;         // deposit slot size
    private Button depositBtn;
    private Button moveToggleBtn;
    private boolean dragMode = false;
    private boolean dragWasDown = false;
    private int dragDepositX, dragDepositY;    // live position while actively dragging

    private int scroll = 0;
    private int stockSeen = -1;
    private int tick = 0;
    private List<Row> allRows = new ArrayList<>();

    public RemoteCraftPanel(AbstractContainerScreen<?> screen) {
        this.screen = screen;
        layout();
        Minecraft mc = Minecraft.getInstance();

        search = new EditBox(mc.font, panelX, panelY, panelW - 32, 14, Component.translatable("inventory-organizer.remote_craft.search_field"));
        search.setHint(Component.translatable("inventory-organizer.remote_craft.search_hint"));
        search.setResponder(s -> { scroll = 0; rebuild(); });
        Screens.getWidgets(screen).add(search);

        upBtn = Button.builder(Component.literal("▲"), b -> {
            scroll = Math.max(0, scroll - 1); rebuild();
        }).bounds(panelX + panelW - 30, panelY, 14, 14).build();
        Screens.getWidgets(screen).add(upBtn);
        downBtn = Button.builder(Component.literal("▼"), b -> {
            scroll++; rebuild();
        }).bounds(panelX + panelW - 15, panelY, 14, 14).build();
        Screens.getWidgets(screen).add(downBtn);

        // Qty box (how many to pull per click). Default 1.
        qty = new EditBox(mc.font, panelX + 26, panelY + 16, 44, 14, Component.translatable("inventory-organizer.remote_craft.qty_field"));
        qty.setValue("1");
        qty.setMaxLength(5);
        Screens.getWidgets(screen).add(qty);

        // Position-cycle button (tiny, top-right of the header): move the whole chest section to the
        // other side, e.g. to get out of JEI's way. (The survival-inventory OI/Kit/Settings/Wh row has
        // its own separate above/below/right cycle, in InventoryScreenMixin.)
        cycleBtn = Button.builder(Component.literal("⇄"), b -> {
            RemoteCraftHudSettings s = OrganizerConfig.get().getRemoteCraftHud();
            s.setChestPos(s.chestPos == RemoteCraftHudSettings.ChestPos.RIGHT
                    ? RemoteCraftHudSettings.ChestPos.LEFT : RemoteCraftHudSettings.ChestPos.RIGHT);
            OrganizerConfig.get().save();
            com.example.inventoryorganizer.InventoryOrganizerClient.relayoutRemoteCraftUi();
        }).bounds(panelX + panelW - 14, panelY - 12, 14, 11)
                .tooltip(net.minecraft.client.gui.components.Tooltip.create(
                        Component.translatable("inventory-organizer.remotecraft.cycle_chest_pos.tooltip")))
                .build();
        Screens.getWidgets(screen).add(cycleBtn);

        // Deposit slot: a click while holding an item on the cursor sends the held stack to a nearby chest
        // (the server reads the cursor stack and sorts the chest). The button is invisible-ish under the
        // slot graphic drawn in render(); clicking the region triggers the deposit.
        depositBtn = Button.builder(Component.literal(""), b -> {
            // Holding an item → deposit it into a chest. Empty hand → "cancel recipe": send the whole
            // crafting grid back to the chests (each ingredient → a chest, each chest OST'd).
            boolean holding = mc.player != null && !mc.player.containerMenu.getCarried().isEmpty();
            if (holding) WarehouseClient.depositCarried();
            else WarehouseClient.returnGrid();
            WarehouseClient.requestCraftStock();
        }).bounds(depositX, depositY, DEP, DEP).build();
        Screens.getWidgets(screen).add(depositBtn);

        // Move handle: click to arm free-drag mode (deposit slot then follows the cursor each frame,
        // via the GLFW mouse-button poll in updateDrag(), until the mouse is released).
        moveToggleBtn = Button.builder(Component.literal("✥"), b -> {
            dragMode = true;
            dragWasDown = true; // the click that triggered this IS the current press — avoid a false release edge
            depositBtn.visible = false; depositBtn.active = false;
            moveToggleBtn.visible = false;
        }).bounds(depositX + DEP - 6, depositY - 6, 9, 9)
                .tooltip(net.minecraft.client.gui.components.Tooltip.create(
                        Component.translatable("inventory-organizer.remotecraft.move_deposit.tooltip")))
                .build();
        Screens.getWidgets(screen).add(moveToggleBtn);

        WarehouseClient.requestCraftStock();
        rebuild();
    }

    /** GUI dimensions of a crafting/inventory screen (vanilla constants). */
    private static final int GUI_W = 176, GUI_H = 166;
    private static final int GAP = 4, MIN_W = 86, MAX_W = 160, MARGIN = 4;

    // The deposit slot draws extra text AROUND itself (the "release to drop" hint to its right while
    // dragging, and the two-line "→ chest" / "(empty hand = grid→chest)" hint centred below it at rest)
    // that the plain slot-only MARGIN above doesn't account for. Letting the slot itself get within
    // MARGIN of an edge let that surrounding text render (and get scissor-clipped) past the real screen
    // bounds, which this custom render pipeline treats as a hard crash rather than a silent clip — so the
    // deposit slot gets its own, more generous clamp on every axis to keep ALL of that text on-screen.
    private static final int DEP_MARGIN_SIDE = 100;   // room for the drag hint / centred label halves
    private static final int DEP_MARGIN_TOP = 10;     // room for the move-handle sitting just above the slot
    private static final int DEP_MARGIN_BOTTOM = 32;  // room for the two-line label below the slot

    private void layout() {
        int guiLeft = (screen.width - GUI_W) / 2;
        int guiTop = (screen.height - GUI_H) / 2;
        try {
            com.example.inventoryorganizer.mixin.ContainerScreenAccessor acc =
                    (com.example.inventoryorganizer.mixin.ContainerScreenAccessor) screen;
            guiLeft = acc.inorLeftPos();
            guiTop = acc.inorTopPos();
        } catch (Throwable ignored) {}

        // layout() re-runs on every screen init — including window resize AND the recipe-book toggle
        // (which shifts leftPos sideways) — so reading the live leftPos here keeps everything attached
        // to the GUI at any resolution / GUI scale. (screen.width/height are already in scaled GUI
        // units, so widths sized from them adapt to the GUI-Scale option automatically.)
        RemoteCraftHudSettings s = OrganizerConfig.get().getRemoteCraftHud();
        int guiRight = guiLeft + GUI_W;

        int avail;
        if (s.chestPos == RemoteCraftHudSettings.ChestPos.LEFT) {
            // The vanilla recipe book (crafting table AND survival inventory both have one) opens
            // directly to the left of the GUI, in the same space we'd otherwise use — without this,
            // the panel would render right on top of it. Push further left by the book's fixed width
            // whenever it's open.
            int bookExtra = 0;
            try {
                if (screen instanceof net.minecraft.client.gui.screens.inventory.AbstractRecipeBookScreen<?> rb) {
                    net.minecraft.client.gui.screens.recipebook.RecipeBookComponent<?> comp =
                            ((com.example.inventoryorganizer.mixin.RecipeBookScreenAccessor) rb).inorRecipeBookComponent();
                    if (comp != null && comp.isVisible()) {
                        // +30 for the category/group tabs that stick out further left of the book's
                        // own image — IMAGE_WIDTH alone doesn't cover them.
                        bookExtra = net.minecraft.client.gui.screens.recipebook.RecipeBookComponent.IMAGE_WIDTH + 30 + GAP;
                    }
                }
            } catch (Throwable ignored) {}

            avail = guiLeft - GAP - MARGIN - bookExtra;
            panelW = Math.max(MIN_W, Math.min(MAX_W, avail));
            panelX = guiLeft - GAP - panelW - bookExtra;
            if (panelX < MARGIN) panelX = MARGIN; // overflow guard: slight GUI overlap beats clipping off-screen
        } else {
            avail = screen.width - guiRight - GAP - MARGIN;
            panelW = Math.max(MIN_W, Math.min(MAX_W, avail));
            panelX = guiRight + GAP;
            if (panelX + panelW + MARGIN > screen.width) {
                panelX = Math.max(MARGIN, screen.width - panelW - MARGIN);
            }
        }

        panelY = Math.max(16, guiTop);
        listTopY = panelY + 34;
        listBottomY = screen.height - 8;
        visRows = Math.max(3, Math.min(14, (listBottomY - listTopY) / ROW_H));

        // Deposit slot: legacy spot is directly UNDER the GUI (unless the player has dragged it
        // elsewhere), centred on the 176-wide GUI, so it tracks the GUI when the recipe book slides it.
        if (!s.depositMoved) {
            depositX = guiLeft + (GUI_W - DEP) / 2;
            depositY = guiTop + GUI_H + 3;
        } else {
            depositX = (int) Math.round(s.depositX * screen.width) - DEP / 2;
            depositY = (int) Math.round(s.depositY * screen.height) - DEP / 2;
            depositX = Math.max(DEP_MARGIN_SIDE, Math.min(screen.width - DEP - DEP_MARGIN_SIDE, depositX));
            depositY = Math.max(DEP_MARGIN_TOP, Math.min(screen.height - DEP - DEP_MARGIN_BOTTOM, depositY));
        }
        if (depositBtn != null) depositBtn.setPosition(depositX, depositY);
        if (moveToggleBtn != null) moveToggleBtn.setPosition(depositX + DEP - 6, depositY - 6);

        // Re-sync the header widgets' positions too — layout() only recomputes panelX/panelY/panelW as
        // plain fields, it doesn't move the ALREADY-CREATED search/qty/scroll/cycle widgets on its own.
        // Without this, flipping chestPos moved the drawn list/frame but left the search box etc. behind.
        if (search != null) {
            search.setPosition(panelX, panelY);
            search.setWidth(panelW - 32);
            upBtn.setPosition(panelX + panelW - 30, panelY);
            downBtn.setPosition(panelX + panelW - 15, panelY);
            qty.setPosition(panelX + 26, panelY + 16);
            cycleBtn.setPosition(panelX + panelW - 14, panelY - 12);
        }
    }

    /** Re-run layout + rebuild (e.g. after a position setting changed elsewhere that might also affect
     *  this panel's chest side, such as the RIGHT/RIGHT auto-flip conflict rule). */
    public void relayout() { layout(); rebuild(); }

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
    public void render(GuiGraphicsExtractor context, int mouseX, int mouseY) {
        updateDrag(mouseX, mouseY);

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
        context.text(mc.font, Component.translatable("inventory-organizer.remote_craft.materials_header", reach), panelX, panelY - 11, 0xFFFFFFFF);
        context.text(mc.font, Component.translatable("inventory-organizer.remote_craft.qty_label"), panelX, panelY + 19, 0xFFAAAAAA);
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
            context.text(mc.font, Component.translatable("inventory-organizer.remote_craft.scroll_hint"), panelX + panelW - 40, panelY + 19, 0xFF888888);
        }

        renderDeposit(context);
    }

    private void renderDeposit(GuiGraphicsExtractor context) {
        Minecraft mc = Minecraft.getInstance();
        int x = dragMode ? dragDepositX : depositX;
        int y = dragMode ? dragDepositY : depositY;
        // Deposit slot, drawn on top of its (click-catching) button so it looks like a real vanilla slot.
        // MC slot styling: dark inset + bevel.
        context.fill(x - 1, y - 1, x + DEP + 1, y + DEP + 1, 0xFF373737);          // outer frame
        context.fill(x, y, x + DEP, y + DEP, 0xFF8B8B8B);                          // light face
        context.fill(x, y, x + DEP - 1, y + DEP - 1, 0xFF373737);                  // top-left shadow
        context.fill(x + 1, y + 1, x + DEP - 1, y + DEP - 1, 0xFF8B8B8B);          // bottom-right light
        context.fill(x + 1, y + 1, x + DEP - 2, y + DEP - 2, 0xFF2B2B33);          // inner well (slightly blue-dark)
        // Downward arrow hint = "drop a held item here → it goes to a nearby chest".
        context.text(mc.font, Component.literal("§b⬇"), x + (DEP - mc.font.width("⬇")) / 2, y + 5, 0xFF66CCFF);
        if (dragMode) {
            Component lbl = Component.translatable("inventory-organizer.remote_craft.release_to_drop");
            context.text(mc.font, lbl, x + DEP + 4, y + 4, 0xFFFFDD55);
        } else {
            // Label centred under the slot. Two uses: drop a held item to stash it, or click empty-handed
            // to send the whole crafting grid back to the chests (cancel the recipe).
            Component lbl = Component.translatable("inventory-organizer.remote_craft.to_chest");
            context.text(mc.font, lbl, x + DEP / 2 - mc.font.width(lbl.getString()) / 2, y + DEP + 2, 0xFFAAAAAA);
            Component lbl2 = Component.translatable("inventory-organizer.remote_craft.empty_hand_hint");
            context.text(mc.font, lbl2, x + DEP / 2 - mc.font.width(lbl2.getString()) / 2, y + DEP + 12, 0xFF888888);
        }
    }

    /**
     * While {@link #dragMode} is armed, follow the cursor each frame (polling the raw GLFW mouse-button
     * state, since Button.onPress only fires once on press and this needs to track the button being
     * HELD across frames). On release, persist the new position as a screen-fraction (resolution-
     * independent, same convention as the existing configurable HUD overlay) and exit drag mode.
     */
    private void updateDrag(int mouseX, int mouseY) {
        if (!dragMode) return;
        boolean down;
        try {
            long win = Minecraft.getInstance().getWindow().handle();
            down = org.lwjgl.glfw.GLFW.glfwGetMouseButton(win, org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_1)
                    == org.lwjgl.glfw.GLFW.GLFW_PRESS;
        } catch (Throwable t) {
            down = false; // defensive: never let a raw GLFW query crash the render pass
        }
        dragDepositX = Math.max(DEP_MARGIN_SIDE, Math.min(screen.width - DEP - DEP_MARGIN_SIDE, mouseX - DEP / 2));
        dragDepositY = Math.max(DEP_MARGIN_TOP, Math.min(screen.height - DEP - DEP_MARGIN_BOTTOM, mouseY - DEP / 2));
        if (!down && dragWasDown) {
            RemoteCraftHudSettings s = OrganizerConfig.get().getRemoteCraftHud();
            s.depositMoved = true;
            s.depositX = (dragDepositX + DEP / 2.0) / screen.width;
            s.depositY = (dragDepositY + DEP / 2.0) / screen.height;
            OrganizerConfig.get().save();
            dragMode = false;
            layout();
            rebuild();
            if (depositBtn != null) { depositBtn.visible = true; depositBtn.active = true; }
            if (moveToggleBtn != null) moveToggleBtn.visible = true;
        }
        dragWasDown = down;
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
