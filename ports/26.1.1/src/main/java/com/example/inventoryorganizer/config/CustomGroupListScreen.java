package com.example.inventoryorganizer.config;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;

import java.util.List;

public class CustomGroupListScreen extends Screen {

    private final Screen parent;
    private final OrganizerConfig config;

    // Name input popup state
    private boolean showNameInput = false;
    private EditBox nameField;
    private String pendingEditGroup = null; // null = new group, non-null = rename existing

    // Status message after Import button
    private String statusMessage = null;
    private long statusUntilMs = 0L;

    // Search box (filters the group list by name)
    private EditBox searchField;

    // Scrolling
    private int scrollOffset = 0;
    private static final int LIST_TOP = 44;
    private static final int ROW_H = 22;

    /** How many rows fit between the list top and the bottom button row (height-dependent). */
    private int rowsVisible() {
        int bottomLimit = (height - 28) - 6; // bottom button row top, minus a small margin
        return Math.max(3, (bottomLimit - LIST_TOP) / ROW_H);
    }

    /** One row in the combined list (built-in groups first, then custom). */
    private static final class Row {
        final String name;
        final boolean builtin;
        Row(String name, boolean builtin) { this.name = name; this.builtin = builtin; }
    }

    private java.util.List<Row> buildRows() {
        // Built-in groups are now materialized as ordinary custom groups (see
        // OrganizerConfig.materializeBuiltinGroupsOnce), so they appear here in the single custom list
        // — fully editable, deletable, and rank-able like any hand-made group.
        java.util.List<Row> rows = new java.util.ArrayList<>();
        String q = (searchField != null) ? searchField.getValue().trim().toLowerCase() : "";
        for (String name : config.getCustomGroupNames()) {
            // Show ALL custom groups, including the materialized built-in ones — they are editable here
            // (the user can customize their items). Built-in groups are flagged so they can't be DELETED.
            boolean builtin = com.example.inventoryorganizer.InventorySorter.isBuiltinGroup(name);
            if (q.isEmpty() || name.toLowerCase().contains(q)) rows.add(new Row(name, builtin));
        }
        return rows;
    }

    public CustomGroupListScreen(Screen parent) {
        super(Component.literal("Custom Item Groups"));
        this.parent = parent;
        this.config = OrganizerConfig.get();
        // Defensive: make sure the built-in→custom migration has run before listing.
        this.config.materializeBuiltinGroupsOnce();
    }

    private void setStatus(String msg) {
        this.statusMessage = msg;
        this.statusUntilMs = System.currentTimeMillis() + 5000L;
    }

    /** Scan the import folder and add each .txt file as a new custom group. */
    private void importAllFromFolder() {
        try {
            java.util.List<GroupTextFile.ImportedGroup> imported = GroupTextFile.scanImportFolder();
            if (imported.isEmpty()) {
                setStatus("§eNo .txt files found in import folder.");
                return;
            }
            int added = 0, updated = 0;
            for (GroupTextFile.ImportedGroup g : imported) {
                if (g.itemIds.isEmpty()) continue;
                if (config.getCustomGroups().containsKey(g.groupName)) {
                    config.setCustomGroup(g.groupName, g.itemIds);
                    updated++;
                } else {
                    config.setCustomGroup(g.groupName, g.itemIds);
                    added++;
                }
            }
            config.save();
            setStatus("§aImported: " + added + " new, " + updated + " updated.");
            rebuildButtons();
        } catch (Exception e) {
            setStatus("§cImport failed: " + e.getMessage());
        }
    }

    @Override
    protected void init() {
        super.init();
        // Persist the search box across rebuilds: create it once, then re-add the SAME instance in
        // rebuildButtons() (which clears all widgets). Re-adding keeps its text, cursor and focus.
        if (searchField == null) {
            searchField = new EditBox(font, width / 2 - 100, 22, 200, 16, Component.literal("Search groups"));
            searchField.setMaxLength(32);
            searchField.setHint(Component.literal("Search groups…"));
            searchField.setResponder(t -> { scrollOffset = 0; rebuildButtons(); });
        } else {
            // width may have changed on resize
            searchField.setX(width / 2 - 100);
        }
        rebuildButtons();
    }

    private void rebuildButtons() {
        boolean searchWasFocused = searchField != null && searchField.isFocused();
        clearWidgets();
        if (searchField != null) {
            addRenderableWidget(searchField);
            if (searchWasFocused) setFocused(searchField); // keep typing alive across rebuilds
        }

        java.util.List<Row> rows = buildRows();
        int startY = LIST_TOP;
        int rowH = ROW_H;
        int rowsVis = rowsVisible();

        // Clamp scroll.
        int maxScroll = Math.max(0, rows.size() - rowsVis);
        if (scrollOffset > maxScroll) scrollOffset = maxScroll;
        if (scrollOffset < 0) scrollOffset = 0;

        int end = Math.min(rows.size(), scrollOffset + rowsVis);
        for (int i = scrollOffset; i < end; i++) {
            final Row row = rows.get(i);
            int y = startY + (i - scrollOffset) * rowH;

            addRenderableWidget(StyledButton.styledBuilder(Component.literal("\u270E Edit"), btn -> {
                config.save();
                // Edit as a NORMAL custom group (isBuiltin=false) so changes save to customGroups \u2014 the
                // store the sorter actually reads. The built-in flag only governs deletability.
                Minecraft.getInstance().setScreen(new CustomGroupEditorScreen(this, row.name, false));
            }).bounds(width / 2 - 130, y, 50, 18).build());

            // Built-in groups are editable but NOT deletable (they're permanent custom groups).
            if (!row.builtin) {
                addRenderableWidget(StyledButton.styledBuilder(Component.literal("\u2716 Delete"), btn -> {
                    config.deleteCustomGroup(row.name);
                    config.save();
                    rebuildButtons();
                }).bounds(width / 2 - 76, y, 50, 18).build());
            }
            // group name label drawn in render
        }

        // Scroll up/down buttons (right side) if list overflows.
        if (rows.size() > rowsVis) {
            addRenderableWidget(StyledButton.styledBuilder(Component.literal("\u25B2"), btn -> {
                scrollOffset = Math.max(0, scrollOffset - 1);
                rebuildButtons();
            }).bounds(width / 2 + 120, startY, 18, 18).build());
            addRenderableWidget(StyledButton.styledBuilder(Component.literal("\u25BC"), btn -> {
                scrollOffset = Math.min(maxScroll, scrollOffset + 1);
                rebuildButtons();
            }).bounds(width / 2 + 120, startY + (rowsVis - 1) * rowH, 18, 18).build());
        }

        // Single bottom row: [+ New] [Import] [Folder] [Back]
        // All on one line so nothing overlaps the scrollable list above.
        int btnY = height - 28;
        int gap = 4;
        int wNew = 70, wImp = 62, wFld = 46, wBack = 46;
        int total = wNew + wImp + wFld + wBack + gap * 3;
        int x = width / 2 - total / 2;

        addRenderableWidget(StyledButton.styledBuilder(Component.literal("+ New"), btn -> {
            showNameInput = true;
            pendingEditGroup = null;
            nameField = new EditBox(font, width / 2 - 80, height / 2 - 10, 160, 20, Component.literal("Group name"));
            nameField.setMaxLength(32);
            nameField.setTextColor(0xFFFFFFFF);
            nameField.setFocused(true);
            addRenderableWidget(nameField);
        }).bounds(x, btnY, wNew, 20).build());
        x += wNew + gap;

        addRenderableWidget(StyledButton.styledBuilder(Component.literal("Import All"), btn -> {
            importAllFromFolder();
        }).bounds(x, btnY, wImp, 20).build());
        x += wImp + gap;

        addRenderableWidget(StyledButton.styledBuilder(Component.literal("Folder"), btn -> {
            GroupTextFile.openImportFolder();
            setStatus("§7Opened import folder. Drop .txt files there, then click Import All.");
        }).bounds(x, btnY, wFld, 20).build());
        x += wFld + gap;

        addRenderableWidget(StyledButton.styledBuilder(Component.literal("Back"), btn -> {
            Minecraft.getInstance().setScreen(parent);
        }).bounds(x, btnY, wBack, 20).build());
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
        super.extractRenderState(context, mouseX, mouseY, delta);

        context.centeredText(font, Component.literal("Item Groups"), width / 2, 8, 0xFFFFFFFF);

        java.util.List<Row> rows = buildRows();
        int startY = LIST_TOP;
        int rowH = ROW_H;
        int end = Math.min(rows.size(), scrollOffset + rowsVisible());
        for (int i = scrollOffset; i < end; i++) {
            Row row = rows.get(i);
            int y = startY + (i - scrollOffset) * rowH;
            int itemCount = config.getCustomGroup(row.name).size();
            String label = "\u00a7e" + row.name + "\u00a7r \u00a77(" + itemCount + " items)";
            context.text(font, Component.literal(label), width / 2 - 20, y + 4, 0xFFFFFFFF);
        }

        // Status message after Import / Folder buttons
        if (statusMessage != null && System.currentTimeMillis() < statusUntilMs) {
            context.centeredText(font, Component.literal(statusMessage), width / 2, height - 50, 0xFFFFFFFF);
        }

        if (showNameInput && nameField != null) {
            int bx = width / 2 - 100;
            int by = height / 2 - 30;
            context.fill(bx, by, bx + 200, by + 80, 0xFF222222);
            context.horizontalLine(bx, bx + 200, by, 0xFF888888);
            context.horizontalLine(bx, bx + 200, by + 80, 0xFF888888);
            context.verticalLine(bx, by, by + 80, 0xFF888888);
            context.verticalLine(bx + 200, by, by + 80, 0xFF888888);
            context.centeredText(font, Component.literal("Enter group name:"), width / 2, by + 8, 0xFFFFFF55);
            context.centeredText(font, Component.literal("[Enter] confirm  [Esc] cancel"), width / 2, by + 60, 0xFF888888);
            // Re-render nameField on top of fill (was covered by fill after super.render)
            nameField.extractRenderState(context, mouseX, mouseY, delta);
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (!showNameInput) {
            int total = buildRows().size();
            int maxScroll = Math.max(0, total - rowsVisible());
            if (verticalAmount < 0) scrollOffset = Math.min(maxScroll, scrollOffset + 1);
            else if (verticalAmount > 0) scrollOffset = Math.max(0, scrollOffset - 1);
            rebuildButtons();
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    @Override
    public boolean keyPressed(KeyEvent keyEvent) {
        if (showNameInput && nameField != null) {
            if (keyEvent.key() == 256) { // Esc
                showNameInput = false;
                removeWidget(nameField);
                nameField = null;
                return true;
            }
            if (keyEvent.key() == 257 || keyEvent.key() == 335) { // Enter / numpad Enter
                String name = nameField.getValue().trim();
                if (!name.isEmpty()) {
                    showNameInput = false;
                    removeWidget(nameField);
                    nameField = null;
                    if (!config.getCustomGroups().containsKey(name)) {
                        config.setCustomGroup(name, new java.util.ArrayList<>());
                        config.save();
                    }
                    Minecraft.getInstance().setScreen(new CustomGroupEditorScreen(this, name));
                }
                return true;
            }
            return nameField.keyPressed(keyEvent);
        }
        return super.keyPressed(keyEvent);
    }
}
