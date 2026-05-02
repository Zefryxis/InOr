package com.example.inventoryorganizer.config;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.input.KeyInput;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class CustomGroupEditorScreen extends Screen {

    private static final int GRID_COLS = 9;
    private static final int GRID_ROWS = 10;
    private static final int GRID_SIZE = GRID_COLS * GRID_ROWS;

    private final Screen parent;
    private final OrganizerConfig config;
    private final String groupName;

    // Each cell: null/"" = empty, else = full item ID (e.g. "minecraft:diamond_sword")
    private final String[] cells = new String[GRID_SIZE];

    private int gridX, gridY, SLOT_W, SLOT_H;
    private int paletteX, paletteY, paletteW, paletteH;
    private int paletteScroll = 0;
    private int maxPaletteScroll = 0;

    private TextFieldWidget searchField;
    private String lastSearch = "";
    private String selectedItemId = null;
    private boolean showHelp = false;

    // Status message shown briefly after import / export.
    private String statusMessage = null;
    private long statusUntilMs = 0L;

    private final List<PaletteEntry> allEntries = new ArrayList<>();
    private List<PaletteEntry> filteredEntries = new ArrayList<>();

    private static class PaletteEntry {
        final String label;
        final String itemId;
        PaletteEntry(String label, String itemId) { this.label = label; this.itemId = itemId; }
    }

    public CustomGroupEditorScreen(Screen parent, String groupName) {
        super(Text.literal("Group Editor: " + groupName));
        this.parent = parent;
        this.config = OrganizerConfig.get();
        this.groupName = groupName;

        List<String> existing = config.getCustomGroup(groupName);
        for (int i = 0; i < GRID_SIZE && i < existing.size(); i++) {
            cells[i] = existing.get(i);
        }
        buildAllEntries();
    }

    private void buildAllEntries() {
        allEntries.clear();
        try {
            for (Identifier id : Registries.ITEM.getIds()) {
                String itemId = id.toString();
                if (itemId.equals("minecraft:air")) continue;
                String name = formatIdAsName(id.getPath());
                allEntries.add(new PaletteEntry(name, itemId));
            }
            allEntries.sort((a, b) -> a.label.compareToIgnoreCase(b.label));
        } catch (Exception ignored) {}
    }

    private static String formatIdAsName(String path) {
        String[] parts = path.split("_");
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (!part.isEmpty()) {
                if (sb.length() > 0) sb.append(" ");
                sb.append(Character.toUpperCase(part.charAt(0)));
                if (part.length() > 1) sb.append(part.substring(1));
            }
        }
        return sb.toString();
    }

    @Override
    protected void init() {
        super.init();

        int minPalette = 130;
        int margins = 30;
        int availableForGrid = width - minPalette - margins;
        int dynSlot = availableForGrid / GRID_COLS;
        SLOT_W = Math.max(20, Math.min(36, dynSlot));
        SLOT_H = SLOT_W;

        int margin = Math.max(4, width / 70);
        gridX = margin + 4;
        gridY = height / 8 + 4;

        int rightEdgeMargin = Math.max(10, width / 50);
        paletteW = Math.max(140, Math.min(260, width / 4));
        paletteX = width - paletteW - rightEdgeMargin;
        paletteY = gridY + 4;
        paletteH = height - paletteY - 44;

        searchField = new TextFieldWidget(textRenderer, paletteX, paletteY - 16, paletteW, 14, Text.literal("Search..."));
        searchField.setMaxLength(50);
        searchField.setPlaceholder(Text.literal("Search items..."));
        searchField.setEditableColor(0xFFFFFFFF);
        searchField.setChangedListener(t -> applyFilter());
        addDrawableChild(searchField);

        lastSearch = null; // force rebuild on init
        applyFilter();

        int btnY = height - 28;
        int btnW = 60;
        int gap = 4;
        int totalW = btnW * 5 + gap * 4;
        int startX = width / 2 - totalW / 2;

        addDrawableChild(StyledButton.styledBuilder(Text.literal("Save"), btn -> {
            saveGroup();
            // Auto-export: also write the text file so the user always has it on disk.
            try {
                Path target = GroupTextFile.fileForGroup(groupName);
                exportToFile(target);
            } catch (IOException e) {
                System.err.println("[InventoryOrganizer] Save auto-export failed: " + e);
                e.printStackTrace();
            }
            MinecraftClient.getInstance().setScreen(parent);
        }).dimensions(startX, btnY, btnW, 20).build());

        addDrawableChild(StyledButton.styledBuilder(Text.literal("Folder"), btn -> {
            GroupTextFile.openGroupsFolder();
            try {
                showStatus("\u00a7bFolder: \u00a7f" + GroupTextFile.getGroupsFolder().toAbsolutePath());
            } catch (IOException e) {
                showStatus("\u00a7cCannot open folder: " + e.getMessage());
            }
        }).dimensions(startX + (btnW + gap), btnY, btnW, 20).build());

        addDrawableChild(StyledButton.styledBuilder(Text.literal("Export"), btn -> {
            try {
                Path target = GroupTextFile.fileForGroup(groupName);
                exportToFile(target);
            } catch (IOException e) {
                showStatus("\u00a7cExport failed: " + e.getMessage());
            }
        }).dimensions(startX + (btnW + gap) * 2, btnY, btnW, 20).build());

        addDrawableChild(StyledButton.styledBuilder(Text.literal("Clear All"), btn -> {
            for (int i = 0; i < GRID_SIZE; i++) cells[i] = null;
        }).dimensions(startX + (btnW + gap) * 3, btnY, btnW, 20).build());

        addDrawableChild(StyledButton.styledBuilder(Text.literal("Back"), btn -> {
            MinecraftClient.getInstance().setScreen(parent);
        }).dimensions(startX + (btnW + gap) * 4, btnY, btnW, 20).build());

        addDrawableChild(StyledButton.styledBuilder(Text.literal("?"), btn -> {
            showHelp = !showHelp;
        }).dimensions(width - 24, 4, 20, 18).build());
    }

    private void saveGroup() {
        List<String> items = new ArrayList<>();
        for (String cell : cells) {
            if (cell != null && !cell.isEmpty()) items.add(cell);
        }
        config.setCustomGroup(groupName, items);
        config.save();
    }

    /** Drag-and-drop: dropping any text file onto this screen imports it. */
    @Override
    public void onFilesDropped(List<Path> paths) {
        if (paths == null || paths.isEmpty()) return;
        importFromFile(paths.get(0));
    }

    /** Read a text file's IDs and lay them out left-to-right, top-to-bottom from cell 0. */
    private void importFromFile(Path path) {
        try {
            List<String> ids = GroupTextFile.parseFromFile(path);
            for (int i = 0; i < GRID_SIZE; i++) cells[i] = null;
            int placed = 0;
            for (int i = 0; i < ids.size() && i < GRID_SIZE; i++) {
                cells[i] = ids.get(i);
                placed++;
            }
            int skipped = Math.max(0, ids.size() - GRID_SIZE);
            String msg = "\u00a7aImported " + placed + " items"
                       + (skipped > 0 ? " \u00a7e(" + skipped + " skipped, grid full)" : "");
            showStatus(msg);
        } catch (IOException e) {
            showStatus("\u00a7cImport failed: " + e.getMessage());
        }
    }

    /** Write the current non-empty cells to a text file, then auto-save the group too. */
    private void exportToFile(Path path) {
        try {
            List<String> items = new ArrayList<>();
            for (String cell : cells) {
                if (cell != null && !cell.isEmpty()) items.add(cell);
            }
            GroupTextFile.writeToFile(path, groupName, items);
            String abs = path.toAbsolutePath().toString();
            System.out.println("[InventoryOrganizer] Exported " + items.size() + " items to " + abs);
            showStatus("\u00a7aSaved " + items.size() + " items \u2192 \u00a7f" + abs);
        } catch (IOException e) {
            System.err.println("[InventoryOrganizer] Export failed: " + e);
            e.printStackTrace();
            showStatus("\u00a7cExport failed: " + e.getMessage());
        }
    }

    private void showStatus(String msg) {
        statusMessage = msg;
        statusUntilMs = System.currentTimeMillis() + 4000L;
    }

    private void applyFilter() {
        String search = searchField != null ? searchField.getText().trim().toLowerCase() : "";
        if (search.equals(lastSearch)) return;
        lastSearch = search;
        paletteScroll = 0;

        filteredEntries = new ArrayList<>();
        for (PaletteEntry e : allEntries) {
            if (search.isEmpty() || e.label.toLowerCase().contains(search) || e.itemId.toLowerCase().contains(search)) {
                filteredEntries.add(e);
            }
        }
        updatePaletteScroll();
        rebuildPaletteButtons();
    }

    private void updatePaletteScroll() {
        int visibleRows = paletteH / 22;
        maxPaletteScroll = Math.max(0, filteredEntries.size() - visibleRows);
        paletteScroll = Math.min(paletteScroll, maxPaletteScroll);
    }

    private void rebuildPaletteButtons() {
        updatePaletteScroll();
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta);

        context.drawCenteredTextWithShadow(textRenderer,
            Text.literal("\u00a7eGroup Editor: \u00a7f" + groupName), width / 2, 4, 0xFFFFFFFF);
        context.drawTextWithShadow(textRenderer,
            Text.literal("Select item from list, then click a cell to add it. Right-click to clear."),
            width / 2 - 160, 16, 0xFFAAAAAA);

        drawGrid(context, mouseX, mouseY);
        drawPaletteExtras(context, mouseX, mouseY);

        // Re-render searchField on top of the palette panel fill (fill covers widget drawn by super.render)
        if (searchField != null) searchField.render(context, mouseX, mouseY, delta);

        if (selectedItemId != null) {
            context.drawTextWithShadow(textRenderer, Text.literal(selectedItemId),
                mouseX + 12, mouseY - 4, 0xFF55FF55);
        }

        // Transient import / export status banner
        if (statusMessage != null) {
            if (System.currentTimeMillis() < statusUntilMs) {
                int tw = textRenderer.getWidth(statusMessage);
                int sx = width / 2 - tw / 2 - 6;
                int sy = height - 52;
                context.fill(sx, sy, sx + tw + 12, sy + 14, 0xCC000000);
                context.drawCenteredTextWithShadow(textRenderer, Text.literal(statusMessage),
                        width / 2, sy + 3, 0xFFFFFFFF);
            } else {
                statusMessage = null;
            }
        }

        if (showHelp) drawGuideOverlay(context);
    }

    private void drawGuideOverlay(DrawContext context) {
        int gw = 380, gh = 223;
        int gx = width / 2 - gw / 2;
        int gy = height / 2 - gh / 2;

        context.fill(0, 0, width, height, 0x88000000);
        context.fill(gx, gy, gx + gw, gy + gh, 0xFF1A1A2E);
        context.fill(gx, gy, gx + gw, gy + 2, 0xFF4466AA);
        context.fill(gx, gy + gh - 2, gx + gw, gy + gh, 0xFF4466AA);
        context.fill(gx, gy, gx + 2, gy + gh, 0xFF4466AA);
        context.fill(gx + gw - 2, gy, gx + gw, gy + gh, 0xFF4466AA);
        context.fill(gx + 4, gy + 4, gx + gw - 4, gy + 20, 0xFF111133);
        context.drawCenteredTextWithShadow(textRenderer,
            Text.literal("\u00a7e\u00a7lGroup Editor Guide"), width / 2, gy + 8, 0xFFFFFF55);

        int lx = gx + 12, ly = gy + 26, lh = 13;
        context.drawTextWithShadow(textRenderer, Text.literal("\u00a7b--- Custom Groups ---"), lx, ly, 0xFF55FFFF); ly += lh;
        context.drawTextWithShadow(textRenderer, Text.literal("\u00a7fGroups are item collections usable as slot rules."), lx, ly, 0xFFFFFFFF); ly += lh;
        context.drawTextWithShadow(textRenderer, Text.literal("\u00a77Use as rule: 'g:<name>' in the inventory config."), lx, ly, 0xFFAAAAAA); ly += lh + 4;
        context.drawTextWithShadow(textRenderer, Text.literal("\u00a7b--- How to use ---"), lx, ly, 0xFF55FFFF); ly += lh;
        context.drawTextWithShadow(textRenderer, Text.literal("\u00a7e[1] \u00a7fSearch for an item in the right panel"), lx, ly, 0xFFFFFFFF); ly += lh;
        context.drawTextWithShadow(textRenderer, Text.literal("\u00a7e[2] \u00a7fLeft-click a list entry to select it (blue highlight)"), lx, ly, 0xFFFFFFFF); ly += lh;
        context.drawTextWithShadow(textRenderer, Text.literal("\u00a7e[3] \u00a7fLeft-click a cell to place the selected item there"), lx, ly, 0xFFFFFFFF); ly += lh;
        context.drawTextWithShadow(textRenderer, Text.literal("\u00a7e[4] \u00a7fRight-click a cell to remove the item from it"), lx, ly, 0xFFFFFFFF); ly += lh;
        context.drawTextWithShadow(textRenderer, Text.literal("\u00a7e[5] \u00a7f'Clear All' removes all items from the grid at once"), lx, ly, 0xFFFFFFFF); ly += lh;
        context.drawTextWithShadow(textRenderer, Text.literal("\u00a7e[6] \u00a7f'Save' stores the group and returns to the list"), lx, ly, 0xFFFFFFFF); ly += lh + 6;

        context.fill(gx + 12, ly, gx + gw - 12, ly + 1, 0xFF444444); ly += 6;
        context.drawCenteredTextWithShadow(textRenderer,
            Text.literal("\u00a77Click outside or press \u00a7e[?]\u00a77 to close"),
            width / 2, ly, 0xFF888888);
    }

    private void drawGrid(DrawContext context, int mouseX, int mouseY) {
        int panelLeft = gridX - 8;
        int panelTop = gridY - 16;
        int panelW2 = GRID_COLS * SLOT_W + 16;
        int panelH2 = GRID_ROWS * SLOT_H + 24;
        context.fill(panelLeft, panelTop, panelLeft + panelW2, panelTop + panelH2, 0xFF1A1A1A);
        context.drawHorizontalLine(panelLeft, panelLeft + panelW2, panelTop, 0xFF666666);
        context.drawHorizontalLine(panelLeft, panelLeft + panelW2, panelTop + panelH2, 0xFF666666);
        context.drawVerticalLine(panelLeft, panelTop, panelTop + panelH2, 0xFF666666);
        context.drawVerticalLine(panelLeft + panelW2, panelTop, panelTop + panelH2, 0xFF666666);
        context.drawTextWithShadow(textRenderer, Text.literal("Items in Group"), panelLeft + 2, panelTop - 10, 0xFFFFAA00);

        for (int row = 0; row < GRID_ROWS; row++) {
            for (int col = 0; col < GRID_COLS; col++) {
                int idx = row * GRID_COLS + col;
                int x = gridX + col * SLOT_W;
                int y = gridY + row * SLOT_H;
                drawCell(context, x, y, idx, mouseX, mouseY);
            }
        }
    }

    private void drawCell(DrawContext context, int x, int y, int idx, int mouseX, int mouseY) {
        String itemId = cells[idx];
        boolean hasItem = itemId != null && !itemId.isEmpty();
        boolean hovered = mouseX >= x && mouseX < x + SLOT_W && mouseY >= y && mouseY < y + SLOT_H;

        int innerColor = hasItem ? 0xFF15253D : 0xFF3D3D3D;
        context.fill(x, y, x + SLOT_W, y + SLOT_H, 0xFF111111);
        context.fill(x + 1, y + 1, x + SLOT_W - 1, y + 3, 0xFF2A2A2A);
        context.fill(x + 1, y + 1, x + 3, y + SLOT_H - 1, 0xFF2A2A2A);
        context.fill(x + 1, y + SLOT_H - 3, x + SLOT_W - 1, y + SLOT_H - 1, 0xFF6A6A6A);
        context.fill(x + SLOT_W - 3, y + 1, x + SLOT_W - 1, y + SLOT_H - 1, 0xFF6A6A6A);
        context.fill(x + 3, y + 3, x + SLOT_W - 3, y + SLOT_H - 3, innerColor);
        if (hasItem) {
            context.fill(x + 3, y + 3, x + SLOT_W - 3, y + 5, 0xFF55AA55);
        }
        if (hovered) {
            context.fill(x + 3, y + 3, x + SLOT_W - 3, y + SLOT_H - 3, 0x55FFFF00);
            context.drawHorizontalLine(x + 2, x + SLOT_W - 3, y + 2, 0xFFFFFF00);
            context.drawHorizontalLine(x + 2, x + SLOT_W - 3, y + SLOT_H - 3, 0xFFFFFF00);
            context.drawVerticalLine(x + 2, y + 2, y + SLOT_H - 3, 0xFFFFFF00);
            context.drawVerticalLine(x + SLOT_W - 3, y + 2, y + SLOT_H - 3, 0xFFFFFF00);
        }

        if (hasItem) {
            try {
                Item item = Registries.ITEM.get(Identifier.of(itemId));
                if (item != null && item != Items.AIR) {
                    context.drawItem(new ItemStack(item), x + (SLOT_W - 16) / 2, y + (SLOT_H - 16) / 2);
                }
            } catch (Exception ignored) {}

            String label = itemId.contains(":") ? itemId.substring(itemId.indexOf(':') + 1).replace('_', ' ') : itemId;
            int maxW = SLOT_W - 4;
            while (label.length() > 1 && textRenderer.getWidth(label) > maxW)
                label = label.substring(0, label.length() - 1);
            context.drawTextWithShadow(textRenderer, Text.literal(label),
                x + (SLOT_W - textRenderer.getWidth(label)) / 2, y + 27, 0xFF55CC55);
        }
    }

    private void drawPaletteExtras(DrawContext context, int mouseX, int mouseY) {
        // Panel background + border
        context.fill(paletteX - 8, paletteY - 24, paletteX + paletteW + 8, paletteY + paletteH + 8, 0xFF1A1A1A);
        context.drawHorizontalLine(paletteX - 8, paletteX + paletteW + 8, paletteY - 24, 0xFF666666);
        context.drawHorizontalLine(paletteX - 8, paletteX + paletteW + 8, paletteY + paletteH + 8, 0xFF666666);
        context.drawVerticalLine(paletteX - 8, paletteY - 24, paletteY + paletteH + 8, 0xFF666666);
        context.drawVerticalLine(paletteX + paletteW + 8, paletteY - 24, paletteY + paletteH + 8, 0xFF666666);
        context.drawTextWithShadow(textRenderer, Text.literal("Item Palette"), paletteX, paletteY - 22, 0xFFFFAA00);

        // Scissor to palette bounds
        context.enableScissor(paletteX, paletteY, paletteX + paletteW, paletteY + paletteH);

        int rowH = 22;
        int visibleRows = paletteH / rowH;
        int startIdx = paletteScroll;
        int endIdx = Math.min(filteredEntries.size(), startIdx + visibleRows);

        for (int i = startIdx; i < endIdx; i++) {
            PaletteEntry entry = filteredEntries.get(i);
            int py = paletteY + (i - startIdx) * rowH;
            boolean hovered = mouseX >= paletteX && mouseX < paletteX + paletteW
                           && mouseY >= py && mouseY < py + rowH;
            boolean selected = entry.itemId.equals(selectedItemId);

            // Row background
            if (selected) context.fill(paletteX, py, paletteX + paletteW, py + rowH, 0xFF004488);
            else if (hovered) context.fill(paletteX, py, paletteX + paletteW, py + rowH, 0xFF333333);

            // Item icon
            try {
                Item item = Registries.ITEM.get(Identifier.of(entry.itemId));
                if (item != null && item != Items.AIR) {
                    context.drawItem(new ItemStack(item), paletteX + 2, py + 3);
                }
            } catch (Exception ignored) {}

            // Item label
            String shortLabel = entry.label.length() > 20 ? entry.label.substring(0, 20) + "." : entry.label;
            int textColor = selected ? 0xFFFFFFFF : hovered ? 0xFFFFFFFF : 0xFFCCCCCC;
            context.drawTextWithShadow(textRenderer, Text.literal(shortLabel), paletteX + 22, py + 7, textColor);
        }

        context.disableScissor();

        // Scrollbar
        if (maxPaletteScroll > 0) {
            int scrollBarH = Math.max(20, paletteH * paletteH / (paletteH + maxPaletteScroll * 22));
            int scrollBarY = paletteY + (int)((float)paletteScroll / maxPaletteScroll * (paletteH - scrollBarH));
            context.fill(paletteX + paletteW - 4, scrollBarY, paletteX + paletteW, scrollBarY + scrollBarH, 0xFF888888);
        }
    }

    @Override
    public boolean mouseClicked(Click click, boolean bl) {
        if (showHelp) {
            int gw = 380, gh = 223;
            int gx = width / 2 - gw / 2;
            int gy = height / 2 - gh / 2;
            if (click.x() < gx || click.x() > gx + gw || click.y() < gy || click.y() > gy + gh) {
                showHelp = false;
            }
            return true;
        }
        double mx = click.x(), my = click.y();
        int btn = click.button();

        // Palette click
        if (btn == 0 && mx >= paletteX && mx < paletteX + paletteW
                     && my >= paletteY && my < paletteY + paletteH) {
            int rowH = 22;
            int relY = (int)(my - paletteY);
            int idx = paletteScroll + relY / rowH;
            if (idx >= 0 && idx < filteredEntries.size()) {
                String clickedId = filteredEntries.get(idx).itemId;
                selectedItemId = clickedId.equals(selectedItemId) ? null : clickedId;
                return true;
            }
        }

        // Grid click
        for (int row = 0; row < GRID_ROWS; row++) {
            for (int col = 0; col < GRID_COLS; col++) {
                int idx = row * GRID_COLS + col;
                int x = gridX + col * SLOT_W;
                int y = gridY + row * SLOT_H;
                if (mx >= x && mx < x + SLOT_W && my >= y && my < y + SLOT_H) {
                    if (btn == 1) {
                        cells[idx] = null;
                    } else if (btn == 0 && selectedItemId != null) {
                        cells[idx] = selectedItemId;
                    }
                    return true;
                }
            }
        }
        return super.mouseClicked(click, bl);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (mouseX >= paletteX - 8 && mouseX <= paletteX + paletteW + 8) {
            paletteScroll = Math.max(0, Math.min(maxPaletteScroll, paletteScroll - (int) verticalAmount));
            rebuildPaletteButtons();
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    @Override
    public boolean keyPressed(KeyInput keyInput) {
        if (searchField != null && searchField.isFocused()) {
            boolean handled = searchField.keyPressed(keyInput);
            applyFilter();
            return handled;
        }
        return super.keyPressed(keyInput);
    }
}
