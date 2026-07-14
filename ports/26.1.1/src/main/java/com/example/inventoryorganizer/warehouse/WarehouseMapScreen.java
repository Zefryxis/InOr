package com.example.inventoryorganizer.warehouse;

import com.example.inventoryorganizer.ChestIdentifier;
import com.example.inventoryorganizer.config.OrganizerConfig;
import com.example.inventoryorganizer.config.StoragePreset;
import com.example.inventoryorganizer.config.StyledButton;
import com.example.inventoryorganizer.config.VisualInventoryConfigScreen;
import com.example.inventoryorganizer.config.WarehouseGroup;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Top-down map of the chests the player has opened. Click chests to select them into a warehouse
 * group, then Link them; afterwards pressing OST at any linked chest sorts the whole group.
 *
 * <p>Data source is {@link OrganizerConfig#getKnownChests()} (chests the player has actually opened),
 * minus any marked "Nothing". A double chest is shown as a single unit. Each chest shows its bound
 * profile name; each link shows its group colour, connectors and name.
 */
public class WarehouseMapScreen extends Screen {

    private final Screen parent;

    /** One logical storage unit on the map: a single chest, or both halves of a double chest. */
    private static final class MapChest {
        final List<BlockPos> positions = new ArrayList<>(); // 1 (single) or 2 (double)
        BlockPos key;          // stable selection key (first position)
        double wx, wz;         // world-space centre (midpoint for a double)
        boolean isDouble;
        boolean doubleAlongX;  // true = the two halves run east-west (X); false = north-south (Z)
        String profileName;    // bound profile name, or null
        int groupIndex = -1;   // index into the warehouse groups list, or -1
        int screenDy = 0;      // vertical screen offset so vertically-stacked chests don't overlap
        boolean stacked = false; // true if it shares an X,Z column with another chest (show Y level)
        int y;                 // representative block Y (for the level label)
        boolean foreign = false; // another player's SHARED chest (from the server, may be unopened here)
        boolean generic = false; // an unidentified container from another mod (drawn with a ring marker)
    }

    private final List<MapChest> chests = new ArrayList<>();
    private final Set<BlockPos> selected = new LinkedHashSet<>();
    private double centerX, centerZ;

    private double scale = 9.0;            // pixels per block
    private int mapX, mapY, mapW, mapH;    // map viewport rect
    private String status = "";           // transient feedback line (e.g. "too far to link")
    private boolean helpOpen = false;
    private int lastDataVersion = -1;     // rebuild the map when server visibility data updates

    /** Chests further apart than this (blocks) can't be linked into one warehouse group. */
    private static final double MAX_LINK_DIST = 15.0;

    // ----- Theme palette -----
    private static final int COL_SCREEN_BG   = 0xFF14141E;
    private static final int COL_HEADER      = 0xFF21213A;
    private static final int COL_ACCENT      = 0xFFFFC83D; // gold
    private static final int COL_PANEL_FILL  = 0xFF0B0B14;
    private static final int COL_BEVEL_LIGHT = 0xFF4A4A68;
    private static final int COL_BEVEL_DARK  = 0xFF050509;
    private static final int COL_SLOT_FILL   = 0xFF2E2E3E;
    private static final int COL_SLOT_LIGHT  = 0xFF595979;
    private static final int COL_SLOT_DARK   = 0xFF12121A;
    private static final int COL_GRID        = 0x14FFFFFF;
    private static final int COL_GRID_MAJOR  = 0x28FFFFFF;
    private static final int COL_SUB         = 0xFF8C8CA6;
    private static final int COL_HOVER       = 0xFFFFFFFF;

    private static final int[] GROUP_COLORS = {
        0xFF55AAFF, 0xFF55FF99, 0xFFFF77DD, 0xFFFFAA44, 0xFFAA88FF, 0xFF66DDDD, 0xFFFFDD55
    };

    public WarehouseMapScreen(Screen parent) {
        super(Component.literal("Warehouse Map"));
        this.parent = parent;
        WarehouseClient.requestLinkMap(); // ask the server which link chests we may see (own + revealed)
        lastDataVersion = WarehouseClient.dataVersion();
        buildChests();
    }

    /** A chest is "managed" (shown on the map) if it has a bound profile or belongs to a link. */
    private static boolean isManagedChest(OrganizerConfig cfg, BlockPos p) {
        int x = p.getX(), y = p.getY(), z = p.getZ();
        for (com.example.inventoryorganizer.config.StoragePreset sp : cfg.getStoragePresets()) {
            if (sp.isDefault()) continue;
            java.util.List<int[]> pos = sp.getPositions();
            if (pos == null) continue;
            for (int[] q : pos) {
                if (q.length == 3 && q[0] == x && q[1] == y && q[2] == z) return true; // has a bound profile
            }
        }
        if (cfg.findWarehouseGroupFor(x, y, z) != null) return true;   // in a local link
        if (WarehouseClient.isOwnLinkChest(p)) return true;            // server says it's our link
        if (WarehouseClient.isForeignLinkChest(p)) return true;        // revealed foreign link
        if (cfg.isGenericChest(x, y, z)) return true;                  // unidentified modded container
        return false;
    }

    /**
     * Build the map units from the player's known (opened) chests, excluding "Nothing" chests, and
     * combining the two halves of a double chest into one unit.
     */
    private void buildChests() {
        chests.clear();
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (mc.player == null) return;
        centerX = mc.player.getX();
        centerZ = mc.player.getZ();

        OrganizerConfig cfg = OrganizerConfig.get();
        List<WarehouseGroup> groups = cfg.getWarehouseGroups();

        // Every MANAGED chest appears and STAYS until you delete its profile / unlink it. We seed
        // directly from the PERSISTED profile + link positions (not just the opened-chest list), so the
        // chests survive relogs and chunk (un)loads. There is intentionally NO auto-prune: a chest is
        // only removed from the map when you remove its profile or unlink it — never automatically just
        // because its chunk happens to be loaded and momentarily out of sync.
        LinkedHashSet<BlockPos> known = new LinkedHashSet<>();
        // (a) every non-default profile's bound position(s)
        for (com.example.inventoryorganizer.config.StoragePreset sp : cfg.getStoragePresets()) {
            if (sp.isDefault() || sp.getPositions() == null) continue;
            for (int[] q : sp.getPositions()) {
                if (q.length == 3 && !cfg.isNothingChest(q[0], q[1], q[2])) known.add(new BlockPos(q[0], q[1], q[2]));
            }
        }
        // (b) every linked chest (local warehouse groups)
        for (WarehouseGroup g : groups) {
            for (int[] q : g.getPositions()) {
                if (q.length == 3 && !cfg.isNothingChest(q[0], q[1], q[2])) known.add(new BlockPos(q[0], q[1], q[2]));
            }
        }
        // (c) any other opened chest the server marks as a link (own/foreign-revealed)
        for (int[] p : cfg.getKnownChests()) {
            if (p.length != 3 || cfg.isNothingChest(p[0], p[1], p[2])) continue;
            BlockPos bp = new BlockPos(p[0], p[1], p[2]);
            if (isManagedChest(cfg, bp)) known.add(bp);
        }

        Set<BlockPos> visited = new java.util.HashSet<>();
        for (BlockPos pos : known) {
            if (visited.contains(pos)) continue;
            BlockPos partner = partnerOf(level, pos, known);
            MapChest u = new MapChest();
            u.positions.add(pos);
            visited.add(pos);
            if (partner != null && !partner.equals(pos)) {
                u.positions.add(partner);
                visited.add(partner);
                u.isDouble = true;
                u.doubleAlongX = pos.getX() != partner.getX(); // halves differ in X → east-west
                u.wx = (pos.getX() + partner.getX()) / 2.0 + 0.5;
                u.wz = (pos.getZ() + partner.getZ()) / 2.0 + 0.5;
            } else {
                u.wx = pos.getX() + 0.5;
                u.wz = pos.getZ() + 0.5;
            }
            u.key = pos;
            u.y = pos.getY();
            u.profileName = resolveProfileName(cfg, u.positions);
            u.groupIndex = resolveGroupIndex(groups, u.positions);
            for (BlockPos q : u.positions) if (cfg.isGenericChest(q.getX(), q.getY(), q.getZ())) { u.generic = true; break; }
            chests.add(u);
        }

        // Other players' revealed LINK chests (from the server) — show them even if never opened here.
        java.util.List<BlockPos> foreign = WarehouseClient.getForeignLinkChests();
        Set<BlockPos> foreignSet = new java.util.HashSet<>(foreign);
        for (BlockPos fpos : foreign) {
            if (visited.contains(fpos) || cfg.isNothingChest(fpos.getX(), fpos.getY(), fpos.getZ())) continue;
            visited.add(fpos);
            MapChest u = new MapChest();
            u.foreign = true;
            u.positions.add(fpos);
            BlockPos partner = partnerOf(level, fpos, foreignSet);
            if (partner != null && !partner.equals(fpos) && !visited.contains(partner)) {
                u.positions.add(partner);
                visited.add(partner);
                u.isDouble = true;
                u.doubleAlongX = fpos.getX() != partner.getX();
                u.wx = (fpos.getX() + partner.getX()) / 2.0 + 0.5;
                u.wz = (fpos.getZ() + partner.getZ()) / 2.0 + 0.5;
            } else {
                u.wx = fpos.getX() + 0.5;
                u.wz = fpos.getZ() + 0.5;
            }
            u.key = fpos;
            u.y = fpos.getY();
            String nm = WarehouseClient.foreignLinkOwner(fpos);
            u.profileName = (nm != null && !nm.isEmpty()) ? nm : null;
            u.groupIndex = -1;
            chests.add(u);
        }

        // Vertical stacks: chests sharing the same X,Z column overlap on a 2D top-down map. Fan them
        // out vertically on screen (centred on the column) and flag them so we can show the Y level.
        Map<Long, List<MapChest>> columns = new HashMap<>();
        for (MapChest u : chests) {
            long key = (((long) Math.round(u.wx * 2)) << 32) ^ (Math.round(u.wz * 2) & 0xffffffffL);
            columns.computeIfAbsent(key, k -> new ArrayList<>()).add(u);
        }
        for (List<MapChest> col : columns.values()) {
            if (col.size() < 2) continue;
            col.sort((a, b) -> Integer.compare(b.y, a.y)); // highest Y first (top of the tower)
            for (int i = 0; i < col.size(); i++) {
                MapChest u = col.get(i);
                u.stacked = true;
                u.screenDy = (int) Math.round((i - (col.size() - 1) / 2.0) * 22);
            }
        }
    }

    /** Other half of a double chest at {@code pos}: from the world when loaded, else by adjacency among known. */
    private BlockPos partnerOf(ClientLevel level, BlockPos pos, Set<BlockPos> known) {
        if (level != null && level.hasChunkAt(pos)) {
            return ChestIdentifier.doubleChestPartner(level, pos); // null = genuinely single
        }
        for (Direction d : new Direction[]{Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST}) {
            BlockPos n = pos.relative(d);
            if (known.contains(n)) return n;
        }
        return null;
    }

    private String resolveProfileName(OrganizerConfig cfg, List<BlockPos> positions) {
        List<int[]> ps = new ArrayList<>();
        for (BlockPos p : positions) ps.add(new int[]{p.getX(), p.getY(), p.getZ()});
        StoragePreset p = cfg.findProfileFor(null, ps, null);
        return p != null ? p.getName() : null;
    }

    private int resolveGroupIndex(List<WarehouseGroup> groups, List<BlockPos> positions) {
        for (int gi = 0; gi < groups.size(); gi++) {
            for (BlockPos p : positions) {
                if (groups.get(gi).contains(p.getX(), p.getY(), p.getZ())) return gi;
            }
        }
        return -1;
    }

    @Override
    protected void init() {
        mapW = Math.min(360, width - 40);
        mapH = Math.min(252, height - 86);
        mapX = (width - mapW) / 2;
        mapY = 44;

        int by = mapY + mapH + 10;
        int gap = 5;
        int bw = (mapW - gap * 5) / 6;
        addRenderableWidget(StyledButton.styledBuilder(
                Component.translatable("inventory-organizer.warehouse.link"),
                b -> linkSelected()).bounds(mapX, by, bw, 16).build());
        addRenderableWidget(StyledButton.styledBuilder(
                Component.translatable("inventory-organizer.warehouse.unlink"),
                b -> unlinkSelected()).bounds(mapX + (bw + gap), by, bw, 16).build());
        addRenderableWidget(StyledButton.styledBuilder(
                Component.translatable("inventory-organizer.warehouse.ost_perms"),
                b -> ostPermsSelected()).bounds(mapX + (bw + gap) * 2, by, bw, 16).build());
        addRenderableWidget(StyledButton.styledBuilder(
                Component.translatable("inventory-organizer.warehouse.sort_selected"),
                b -> sortSelected()).bounds(mapX + (bw + gap) * 3, by, bw, 16).build());
        addRenderableWidget(StyledButton.styledBuilder(
                Component.translatable("inventory-organizer.warehouse.clear"),
                b -> { selected.clear(); status = ""; }).bounds(mapX + (bw + gap) * 4, by, bw, 16).build());
        addRenderableWidget(StyledButton.styledBuilder(
                Component.translatable("inventory-organizer.button.back"),
                b -> Minecraft.getInstance().setScreen(parent))
                .bounds(mapX + (bw + gap) * 5, by, bw, 16).build());

        // "?" help toggle, top-right aligned with the header.
        addRenderableWidget(StyledButton.styledBuilder(
                Component.translatable("inventory-organizer.button.help"),
                b -> helpOpen = !helpOpen).bounds(mapX + mapW - 14, mapY - 17, 14, 14).build());
    }

    private int selectedUnitCount() {
        int n = 0;
        for (MapChest u : chests) if (selected.contains(u.key)) n++;
        return n;
    }

    /** All world positions (both halves of doubles) belonging to the currently-selected units. */
    private List<int[]> selectedPositions() {
        List<int[]> ps = new ArrayList<>();
        for (MapChest u : chests) {
            if (!selected.contains(u.key)) continue;
            for (BlockPos p : u.positions) ps.add(new int[]{p.getX(), p.getY(), p.getZ()});
        }
        return ps;
    }

    /** Link the selected chests into one persistent group (OST at any member then sorts all of them). */
    private void linkSelected() {
        List<MapChest> sel = new ArrayList<>();
        for (MapChest u : chests) if (selected.contains(u.key)) sel.add(u);
        if (sel.size() < 2) { status = "§7Select at least 2 chests to link"; return; }
        // Anti-theft: you can never link another player's chests (their revealed link members).
        for (MapChest u : sel) {
            for (BlockPos p : u.positions) {
                if (u.foreign || WarehouseClient.isForeignLinkChest(p)) {
                    status = "§cCan't link another player's chests";
                    return;
                }
            }
        }
        if (!withinLinkRange(sel)) { status = "§cChests too far apart (max " + (int) MAX_LINK_DIST + " blocks)"; return; }
        OrganizerConfig.get().addWarehouseGroup(selectedPositions());
        OrganizerConfig.get().save();
        // Mirror the link to the server so other players can reveal + OST it (with our rules).
        java.util.List<BlockPos> linkPositions = new ArrayList<>();
        for (int[] p : selectedPositions()) linkPositions.add(new BlockPos(p[0], p[1], p[2]));
        WarehouseClient.publishLink("", linkPositions);
        selected.clear();
        status = "§aLinked " + sel.size() + " chests";
        buildChests();
    }

    /**
     * The selected chests must form one cluster where every chest is within {@link #MAX_LINK_DIST}
     * blocks of another in the group (connected graph), so a warehouse can chain across nearby
     * chests but never link an isolated, far-away one.
     */
    private boolean withinLinkRange(List<MapChest> sel) {
        Set<Integer> reached = new java.util.HashSet<>();
        java.util.ArrayDeque<Integer> queue = new java.util.ArrayDeque<>();
        reached.add(0); queue.add(0);
        while (!queue.isEmpty()) {
            int i = queue.poll();
            for (int j = 0; j < sel.size(); j++) {
                if (!reached.contains(j) && unitDist(sel.get(i), sel.get(j)) <= MAX_LINK_DIST) {
                    reached.add(j); queue.add(j);
                }
            }
        }
        return reached.size() == sel.size();
    }

    /** Open the OST-permission picker for a selected OWN link (lets the owner grant players OST). */
    private void ostPermsSelected() {
        for (MapChest u : chests) {
            if (!selected.contains(u.key)) continue;
            for (BlockPos p : u.positions) {
                if (WarehouseClient.isOwnLinkChest(p)) {
                    Minecraft.getInstance().setScreen(
                            new com.example.inventoryorganizer.config.OstPermScreen(this, p));
                    return;
                }
            }
        }
        status = "§7Select one of your linked chests first";
    }

    /** Remove the selected chests from whatever link they belong to. */
    private void unlinkSelected() {
        OrganizerConfig cfg = OrganizerConfig.get();
        for (int[] p : selectedPositions()) {
            cfg.detachFromGroups(p[0], p[1], p[2]);
            WarehouseClient.deleteLink(new BlockPos(p[0], p[1], p[2])); // drop the server-side link too
        }
        cfg.save();
        selected.clear();
        status = "§7Unlinked";
        buildChests();
    }

    /**
     * Sort the selected chests. If a selected chest is part of a LINK (warehouse group), the WHOLE
     * group is sorted, not just that chest — so clicking any linked chest sorts the entire warehouse.
     */
    private void sortSelected() {
        if (selected.isEmpty()) { status = "§7Select chests to sort"; return; }
        OrganizerConfig cfg = OrganizerConfig.get();
        LinkedHashSet<BlockPos> bps = new LinkedHashSet<>();
        for (MapChest u : chests) {
            if (!selected.contains(u.key)) continue;
            BlockPos any = u.positions.get(0);
            // Another player's revealed link: the server sorts it with the owner's stored rules.
            if (u.foreign || WarehouseClient.isForeignLinkChest(any)) {
                WarehouseClient.sortLink(any);
                continue;
            }
            WarehouseGroup g = cfg.findWarehouseGroupFor(any.getX(), any.getY(), any.getZ());
            if (g != null && g.getPositions().size() >= 2) {
                for (int[] q : g.getPositions()) bps.add(new BlockPos(q[0], q[1], q[2]));
            } else {
                for (BlockPos p : u.positions) bps.add(p);
            }
        }
        if (!bps.isEmpty()) WarehouseClient.requestSort(new ArrayList<>(bps));
        Minecraft.getInstance().setScreen(parent);
    }

    // ---- world ↔ screen ----
    private int sx(double worldX) { return mapX + mapW / 2 + (int) Math.round((worldX - centerX) * scale); }
    private int sy(double worldZ) { return mapY + mapH / 2 + (int) Math.round((worldZ - centerZ) * scale); }

    /** Screen position of a unit, including its vertical-stack fan offset. */
    private int cx(MapChest u) { return sx(u.wx); }
    private int cy(MapChest u) { return sy(u.wz) + u.screenDy; }

    // Tile half-extent: a double chest is elongated along its real axis (east-west = wide, north-south = tall).
    private int halfW(MapChest u) { return (u.isDouble && u.doubleAlongX) ? 18 : 10; }
    private int halfH(MapChest u) { return (u.isDouble && !u.doubleAlongX) ? 18 : 10; }

    /** 3D block distance between two units (uses their centres + first-position Y). */
    private static double unitDist(MapChest a, MapChest b) {
        double dx = a.wx - b.wx, dz = a.wz - b.wz;
        double dy = a.positions.get(0).getY() - b.positions.get(0).getY();
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private MapChest chestAt(int mx, int my) {
        // Iterate in reverse so the topmost-drawn tile in a fan wins overlapping hits.
        for (int i = chests.size() - 1; i >= 0; i--) {
            MapChest u = chests.get(i);
            int x = cx(u), y = cy(u);
            int hw = halfW(u), hh = halfH(u);
            if (mx >= x - hw && mx <= x + hw && my >= y - hh && my <= y + hh) return u;
        }
        return null;
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent click, boolean bl) {
        // Any click closes the help overlay first.
        if (helpOpen) { helpOpen = false; return true; }
        int mx = (int) click.x(), my = (int) click.y();
        if (click.button() == 0 && mx >= mapX && mx < mapX + mapW && my >= mapY && my < mapY + mapH) {
            MapChest c = chestAt(mx, my);
            if (c != null) {
                if (!selected.remove(c.key)) selected.add(c.key);
                status = "";
                return true;
            }
        }
        return super.mouseClicked(click, bl);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (!helpOpen && mouseX >= mapX && mouseX < mapX + mapW && mouseY >= mapY && mouseY < mapY + mapH) {
            // Allow zooming out far enough to see the whole 60-block sort range (so distant chests
            // can be selected + sorted), but not endlessly.
            scale = Math.max(2.0, Math.min(24.0, scale + (verticalAmount > 0 ? 1.0 : -1.0)));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
        // Rebuild when the server sends fresh visibility data (hidden/shared chests).
        if (WarehouseClient.dataVersion() != lastDataVersion) {
            lastDataVersion = WarehouseClient.dataVersion();
            buildChests();
        }

        // Backdrop + header bar with gold accent line.
        context.fill(0, 0, width, height, COL_SCREEN_BG);
        context.fill(0, 0, width, 34, COL_HEADER);
        context.fill(0, 34, width, 35, COL_ACCENT);
        VisualInventoryConfigScreen.drawItemIcon(context, new ItemStack(Items.CHEST), width / 2 - 78, 7);
        context.centeredText(font,
                Component.literal("§l" + Component.translatable("inventory-organizer.warehouse.map_title").getString()),
                width / 2, 13, 0xFFFFFFFF);

        // Sunken map panel.
        drawSunken(context, mapX - 2, mapY - 2, mapW + 4, mapH + 4, COL_PANEL_FILL);
        // Clip-ish: everything below draws inside; coordinates are guarded per-element.
        drawGrid(context);

        // Player marker (centre) — diamond.
        int px = mapX + mapW / 2, py = mapY + mapH / 2;
        // 60-block sort-reach ring: chests outside it can't be sorted.
        drawCircleOutline(context, px, py, (int) Math.round(60 * scale), 0x44FFC83D);
        drawDiamond(context, px, py, 4, 0xFF55FF66);
        drawDiamondOutline(context, px, py, 4, 0xFF1E5E26);

        // Links + group names.
        List<WarehouseGroup> groups = OrganizerConfig.get().getWarehouseGroups();
        Map<Integer, int[]> groupCentroid = new HashMap<>();
        Map<Integer, Integer> groupMemberCount = new HashMap<>();
        for (MapChest u : chests) {
            if (u.groupIndex < 0) continue;
            int[] acc = groupCentroid.computeIfAbsent(u.groupIndex, k -> new int[]{0, 0});
            acc[0] += cx(u); acc[1] += cy(u);
            groupMemberCount.merge(u.groupIndex, 1, Integer::sum);
        }
        for (Map.Entry<Integer, int[]> e : groupCentroid.entrySet()) {
            int gi = e.getKey();
            int n = groupMemberCount.getOrDefault(gi, 1);
            int ccx = e.getValue()[0] / n, ccy = e.getValue()[1] / n;
            int color = GROUP_COLORS[gi % GROUP_COLORS.length];
            for (MapChest u : chests) {
                if (u.groupIndex == gi) drawThickLine(context, cx(u), cy(u), ccx, ccy, color);
            }
            // central node
            if (inMap(ccx, ccy)) {
                context.fill(ccx - 2, ccy - 2, ccx + 2, ccy + 2, color);
                context.fill(ccx - 1, ccy - 1, ccx + 1, ccy + 1, 0xFF000000);
            }
            String gname = gi < groups.size() && groups.get(gi).name != null && !groups.get(gi).name.isEmpty()
                    ? groups.get(gi).name
                    : Component.translatable("inventory-organizer.warehouse.link").getString() + " " + (gi + 1);
            int tw = font.width(gname);
            int lx = ccx - tw / 2, ly = ccy - 14;
            if (inMap(ccx, ccy)) {
                context.fill(lx - 3, ly - 2, lx + tw + 3, ly + 9, 0xCC0A0A12); // name pill
                drawBorder(context, lx - 3, ly - 2, tw + 6, 11, color);
                context.text(font, Component.literal(gname), lx, ly, color);
            }
        }

        // Chests as raised slot tiles with the chest icon. A double chest's tile is elongated along
        // the chest's REAL axis: east-west doubles are wide, north-south doubles are tall.
        MapChest hovered = null;
        for (MapChest u : chests) {
            int x = cx(u), y = cy(u);
            int hw = halfW(u), hh = halfH(u);
            if (x + hw < mapX || x - hw > mapX + mapW || y + hh < mapY || y - hh > mapY + mapH) continue;
            boolean sel = selected.contains(u.key);
            boolean hov = mouseX >= x - hw && mouseX <= x + hw && mouseY >= y - hh && mouseY <= y + hh;
            if (hov) hovered = u;

            // Vertical-stack guide: a faint line connecting the fanned tiles of one column.
            if (u.stacked) context.fill(x - 1, sy(u.wz) - 1, x + 1, y, 0x33FFFFFF);

            // Raised tile (slot look).
            drawRaised(context, x - hw, y - hh, hw * 2, hh * 2, COL_SLOT_FILL, COL_SLOT_LIGHT, COL_SLOT_DARK);

            // Group accent frame, or a blue frame for another player's shared chest.
            if (u.foreign) {
                drawBorder(context, x - hw, y - hh, hw * 2, hh * 2, 0xFF4499FF);
            } else if (u.groupIndex >= 0) {
                int gc = GROUP_COLORS[u.groupIndex % GROUP_COLORS.length];
                drawBorder(context, x - hw, y - hh, hw * 2, hh * 2, gc);
            }

            // Chest icon(s): an unidentified modded container shows a RING marker (we can't resolve a
            // block/item icon for it); otherwise the chest item icon (a double draws two along its axis).
            if (u.generic) {
                drawRing(context, x, y, 7, 0xFFFFD24D);
                drawRing(context, x, y, 4, 0x99FFD24D);
            } else if (u.isDouble && u.doubleAlongX) {
                VisualInventoryConfigScreen.drawItemIcon(context, new ItemStack(Items.CHEST), x - 17, y - 8);
                VisualInventoryConfigScreen.drawItemIcon(context, new ItemStack(Items.CHEST), x + 1, y - 8);
            } else if (u.isDouble) {
                VisualInventoryConfigScreen.drawItemIcon(context, new ItemStack(Items.CHEST), x - 8, y - 17);
                VisualInventoryConfigScreen.drawItemIcon(context, new ItemStack(Items.CHEST), x - 8, y + 1);
            } else {
                VisualInventoryConfigScreen.drawItemIcon(context, new ItemStack(Items.CHEST), x - 8, y - 8);
            }

            // Selection / hover highlight.
            if (sel) {
                context.fill(x - hw + 1, y - hh + 1, x + hw - 1, y + hh - 1, 0x44FFC83D);
                drawBorder(context, x - hw - 1, y - hh - 1, hw * 2 + 2, hh * 2 + 2, COL_ACCENT);
                drawBorder(context, x - hw, y - hh, hw * 2, hh * 2, COL_ACCENT);
            } else if (hov) {
                drawBorder(context, x - hw, y - hh, hw * 2, hh * 2, COL_HOVER);
            }

            // Y-level badge for vertically-stacked chests (so you can tell the tower apart).
            if (u.stacked) {
                String yl = "y" + u.y;
                int lw = font.width(yl);
                context.fill(x + hw - lw - 3, y - hh, x + hw, y - hh + 10, 0xCC0A0A12);
                context.text(font, Component.literal("§e" + yl), x + hw - lw - 1, y - hh + 1, 0xFFFFE066);
            }

            // Link badge: a small green dot = this chest is part of one of our shared links (others
            // who open it can sort the whole link).
            boolean sh = false;
            for (BlockPos bp : u.positions) if (WarehouseClient.isOwnLinkChest(bp)) { sh = true; break; }
            if (sh) {
                context.fill(x - hw + 1, y - hh + 1, x - hw + 6, y - hh + 6, 0xFF115511);
                context.fill(x - hw + 2, y - hh + 2, x - hw + 5, y - hh + 5, 0xFF55FF66);
                context.fill(x - hw + 3, y - hh + 3, x - hw + 4, y - hh + 4, 0xFF0A2A0A);
            }

            // Name under the tile. Generic (unidentified) containers get a small ring before the name
            // (or just the ring + "?" when they have no profile yet).
            if (u.generic || (u.profileName != null && !u.profileName.isEmpty())) {
                String raw = (u.profileName != null && !u.profileName.isEmpty()) ? u.profileName : "?";
                if (raw.length() > 14) raw = raw.substring(0, 13) + "…";
                String txt = u.generic ? ("§e○ §f" + raw) : ("§f" + raw);
                context.centeredText(font, Component.literal(txt), x, y + hh + 1, 0xFFFFFFFF);
            }
        }

        // Footer: stats pill + hint + status.
        String info = "§7Chests §f" + chests.size() + "   §7Selected §e" + selectedUnitCount()
                + "   §7Links §b" + groups.size();
        int iw = font.width(info.replaceAll("§.", ""));
        int spx = width / 2 - iw / 2;
        int sty = mapY + mapH + 30;
        context.fill(spx - 6, sty - 3, spx + iw + 6, sty + 9, 0xCC0A0A12);
        drawBorder(context, spx - 6, sty - 3, iw + 12, 12, 0xFF333350);
        context.text(font, Component.literal(info), spx, sty, 0xFFFFFFFF);

        context.centeredText(font, Component.translatable("inventory-organizer.warehouse.map_hint"),
                width / 2, sty + 13, COL_SUB);
        if (!status.isEmpty()) {
            context.centeredText(font, Component.literal(status), width / 2, sty + 24, 0xFFFFFFFF);
        }

        // Hover tooltip (drawn above chests, below widgets).
        if (hovered != null && !helpOpen) drawChestTooltip(context, hovered, mouseX, mouseY, groups);

        super.extractRenderState(context, mouseX, mouseY, delta);

        if (helpOpen) drawHelp(context);
    }

    private boolean inMap(int x, int y) {
        return x >= mapX && x <= mapX + mapW && y >= mapY && y <= mapY + mapH;
    }

    private void drawChestTooltip(GuiGraphicsExtractor context, MapChest u, int mouseX, int mouseY, List<WarehouseGroup> groups) {
        List<String> lines = new ArrayList<>();
        lines.add("§f" + (u.generic ? "§e○ Unknown container (modded)" : (u.isDouble ? "Double chest" : "Chest")));
        lines.add("§7Profile: " + (u.profileName != null ? "§f" + u.profileName : "§8none (size default)"));
        if (u.groupIndex >= 0) {
            int gc = GROUP_COLORS[u.groupIndex % GROUP_COLORS.length];
            String gname = u.groupIndex < groups.size() && groups.get(u.groupIndex).name != null
                    && !groups.get(u.groupIndex).name.isEmpty()
                    ? groups.get(u.groupIndex).name : "Link " + (u.groupIndex + 1);
            lines.add(String.format("§7Linked: %s", "§r" + colHex(gc) + gname));
        } else {
            lines.add("§8Not linked");
        }
        BlockPos p0 = u.positions.get(0);
        lines.add("§8" + p0.getX() + ", " + p0.getY() + ", " + p0.getZ());

        int w = 0;
        for (String s : lines) w = Math.max(w, font.width(s.replaceAll("§.", "")));
        int h = lines.size() * 10 + 6;
        int tx = mouseX + 10, ty = mouseY + 6;
        if (tx + w + 8 > width) tx = mouseX - w - 14;
        if (ty + h > height) ty = height - h - 2;
        context.fill(tx - 4, ty - 4, tx + w + 4, ty + h - 4, 0xF00B0B16); // dark tooltip bg
        drawBorder(context, tx - 4, ty - 4, w + 8, h, 0xFF3A3A5A);
        int yy = ty;
        for (String s : lines) {
            context.text(font, Component.literal(s), tx, yy, 0xFFFFFFFF);
            yy += 10;
        }
    }

    /** Minecraft §-colour code closest to a packed ARGB (rough — just to tint group names in tooltip). */
    private static String colHex(int argb) {
        return "§f"; // keep it simple/readable; group colour shown via the pill on the map itself
    }

    private void drawHelp(GuiGraphicsExtractor context) {
        context.fill(0, 0, width, height, 0xCC000000);
        int pw = Math.min(360, width - 40), ph = Math.min(220, height - 40);
        int x = (width - pw) / 2, y = (height - ph) / 2;
        drawSunken(context, x, y, pw, ph, 0xFF14141F);
        context.fill(x, y, x + pw, y + 18, COL_HEADER);
        context.fill(x, y + 18, x + pw, y + 19, COL_ACCENT);
        context.centeredText(font,
                Component.literal("§l" + Component.translatable("inventory-organizer.warehouse.help_title").getString()),
                x + pw / 2, y + 5, 0xFFFFFFFF);

        String body = Component.translatable("inventory-organizer.warehouse.help_body").getString();
        int ty = y + 26;
        for (String line : body.split("\n")) {
            context.text(font, Component.literal(line), x + 10, ty, 0xFFCFCFE0);
            ty += 11;
        }
        context.centeredText(font, Component.literal("§8click anywhere to close"),
                x + pw / 2, y + ph - 12, COL_SUB);
    }

    // ===== drawing helpers =====

    private void drawGrid(GuiGraphicsExtractor context) {
        int step = 8;
        long startX = (long) Math.floor((centerX - (mapW / 2.0) / scale) / step) * step;
        for (double wx = startX; sx(wx) <= mapX + mapW; wx += step) {
            int x = sx(wx);
            if (x >= mapX && x <= mapX + mapW) {
                boolean major = ((long) Math.floor(wx)) % 16 == 0;
                context.verticalLine(x, mapY, mapY + mapH, major ? COL_GRID_MAJOR : COL_GRID);
            }
        }
        long startZ = (long) Math.floor((centerZ - (mapH / 2.0) / scale) / step) * step;
        for (double wz = startZ; sy(wz) <= mapY + mapH; wz += step) {
            int yy = sy(wz);
            if (yy >= mapY && yy <= mapY + mapH) {
                boolean major = ((long) Math.floor(wz)) % 16 == 0;
                context.horizontalLine(mapX, mapX + mapW, yy, major ? COL_GRID_MAJOR : COL_GRID);
            }
        }
    }

    /** Raised tile: light top/left edge, dark bottom/right edge (a slot that pops out). */
    private void drawRaised(GuiGraphicsExtractor c, int x, int y, int w, int h, int fill, int light, int dark) {
        c.fill(x, y, x + w, y + h, fill);
        c.fill(x, y, x + w, y + 1, light);
        c.fill(x, y, x + 1, y + h, light);
        c.fill(x, y + h - 1, x + w, y + h, dark);
        c.fill(x + w - 1, y, x + w, y + h, dark);
    }

    /** Sunken panel: dark top/left edge, light bottom/right edge (recessed into the screen). */
    private void drawSunken(GuiGraphicsExtractor c, int x, int y, int w, int h, int fill) {
        c.fill(x, y, x + w, y + h, fill);
        c.fill(x, y, x + w, y + 1, COL_BEVEL_DARK);
        c.fill(x, y, x + 1, y + h, COL_BEVEL_DARK);
        c.fill(x, y + h - 1, x + w, y + h, COL_BEVEL_LIGHT);
        c.fill(x + w - 1, y, x + w, y + h, COL_BEVEL_LIGHT);
    }

    private void drawBorder(GuiGraphicsExtractor context, int x, int y, int w, int h, int color) {
        context.fill(x, y, x + w, y + 1, color);
        context.fill(x, y + h - 1, x + w, y + h, color);
        context.fill(x, y, x + 1, y + h, color);
        context.fill(x + w - 1, y, x + w, y + h, color);
    }

    /** Faint circle outline (midpoint algorithm), clipped to the map panel. */
    private void drawCircleOutline(GuiGraphicsExtractor c, int cx, int cy, int r, int color) {
        if (r < 2) return;
        int x = r, y = 0, err = 1 - r;
        while (x >= y) {
            plotClipped(c, cx + x, cy + y, color); plotClipped(c, cx - x, cy + y, color);
            plotClipped(c, cx + x, cy - y, color); plotClipped(c, cx - x, cy - y, color);
            plotClipped(c, cx + y, cy + x, color); plotClipped(c, cx - y, cy + x, color);
            plotClipped(c, cx + y, cy - x, color); plotClipped(c, cx - y, cy - x, color);
            y++;
            if (err < 0) err += 2 * y + 1;
            else { x--; err += 2 * (y - x) + 1; }
        }
    }

    private void plotClipped(GuiGraphicsExtractor c, int x, int y, int color) {
        if (x >= mapX && x < mapX + mapW && y >= mapY && y < mapY + mapH) c.fill(x, y, x + 1, y + 1, color);
    }

    /** Midpoint-circle outline (clipped to the map), used as the marker for unidentified containers. */
    private void drawRing(GuiGraphicsExtractor c, int cx, int cy, int r, int color) {
        int x = r, y = 0, err = 1 - r;
        while (x >= y) {
            plotClipped(c, cx + x, cy + y, color); plotClipped(c, cx - x, cy + y, color);
            plotClipped(c, cx + x, cy - y, color); plotClipped(c, cx - x, cy - y, color);
            plotClipped(c, cx + y, cy + x, color); plotClipped(c, cx - y, cy + x, color);
            plotClipped(c, cx + y, cy - x, color); plotClipped(c, cx - y, cy - x, color);
            y++;
            if (err < 0) err += 2 * y + 1;
            else { x--; err += 2 * (y - x) + 1; }
        }
    }

    private void drawDiamond(GuiGraphicsExtractor c, int cx, int cy, int r, int color) {
        for (int dy = -r; dy <= r; dy++) {
            int dx = r - Math.abs(dy);
            c.fill(cx - dx, cy + dy, cx + dx + 1, cy + dy + 1, color);
        }
    }

    private void drawDiamondOutline(GuiGraphicsExtractor c, int cx, int cy, int r, int color) {
        for (int i = 0; i < r; i++) {
            c.fill(cx - (r - i), cy - i - 1, cx - (r - i) + 1, cy - i, color);
            c.fill(cx + (r - i) - 1, cy - i - 1, cx + (r - i), cy - i, color);
            c.fill(cx - (r - i), cy + i, cx - (r - i) + 1, cy + i + 1, color);
            c.fill(cx + (r - i) - 1, cy + i, cx + (r - i), cy + i + 1, color);
        }
    }

    /** A thicker, slightly glowing connector line (dark underglow + bright core), clipped to the map. */
    private void drawThickLine(GuiGraphicsExtractor ctx, int x0, int y0, int x1, int y1, int color) {
        int glow = (color & 0x00FFFFFF) | 0x55000000;
        plotLine(ctx, x0, y0 + 1, x1, y1 + 1, glow);
        plotLine(ctx, x0 + 1, y0, x1 + 1, y1, glow);
        plotLine(ctx, x0, y0, x1, y1, color);
    }

    private void plotLine(GuiGraphicsExtractor ctx, int x0, int y0, int x1, int y1, int color) {
        int dx = Math.abs(x1 - x0), dy = Math.abs(y1 - y0);
        int sx2 = x0 < x1 ? 1 : -1, sy2 = y0 < y1 ? 1 : -1;
        int err = dx - dy, guard = 0;
        while (guard++ < 6000) {
            if (x0 >= mapX && x0 < mapX + mapW && y0 >= mapY && y0 < mapY + mapH) {
                ctx.fill(x0, y0, x0 + 1, y0 + 1, color);
            }
            if (x0 == x1 && y0 == y1) break;
            int e2 = 2 * err;
            if (e2 > -dy) { err -= dy; x0 += sx2; }
            if (e2 < dx) { err += dx; y0 += sy2; }
        }
    }

    private int tickCounter = 0;

    @Override
    public void tick() {
        // Periodically refresh shared/hidden data so chests others share appear while the map is open.
        if ((++tickCounter % 20) == 0) WarehouseClient.requestLinkMap();
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(parent);
    }
}
