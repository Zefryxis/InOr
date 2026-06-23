package com.example.inventoryorganizer.warehouse;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Server-side store of warehouse LINKS in the simplified model. A link groups several chests owned by
 * one player, together with that owner's per-chest sort rules. A link is, by default, only visible to
 * its owner; once any other player OPENS one of its chests the whole link is revealed to them and they
 * may OST it (using the owner's stored rules) — but never create or edit a profile on it.
 *
 * <p>Individual (non-linked) chest profiles are NOT stored here: they are purely local/private to each
 * client. This store only exists to make cross-player link sharing + reveal-on-open work on a server.
 * Server-safe (no client classes); accessed only from the server thread.
 */
public final class WarehouseLinks {

    private static final Logger LOGGER = LoggerFactory.getLogger("inventory-organizer/Warehouse");
    private static WarehouseLinks instance;

    static final class Link {
        String owner;                 // creator UUID
        String ownerName;             // display name (for the "X's warehouse" label)
        String name;                  // link name
        String dim;                   // dimension id
        List<int[]> positions = new ArrayList<>();        // {x,y,z} per member chest
        List<List<String>> rules = new ArrayList<>();     // parallel to positions: owner's slot rules
        List<String> allowOst = new ArrayList<>();        // UUIDs (besides owner) allowed to OST this link

        boolean contains(int x, int y, int z) {
            for (int[] p : positions) {
                if (p.length == 3 && p[0] == x && p[1] == y && p[2] == z) return true;
            }
            return false;
        }
    }

    private static final class Data {
        List<Link> links = new ArrayList<>();
        Map<String, String> roster = new HashMap<>();     // every joined player: UUID -> last name
    }

    /** A known player: UUID + last-seen display name (for the OST-permission picker). */
    public record RosterPlayer(String uuid, String name) {}

    private Data data = new Data();

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path FILE =
            FabricLoader.getInstance().getConfigDir().resolve("inventory-organizer-warehouse-links.json");

    private WarehouseLinks() {}

    public static WarehouseLinks get() {
        if (instance == null) {
            instance = new WarehouseLinks();
            instance.load();
        }
        return instance;
    }

    /**
     * Create or replace the caller's link. Any existing link owned by {@code owner} in this dimension
     * that overlaps the new positions is removed first, so an owner has exactly one link per chest set.
     */
    public synchronized void upsert(String owner, String ownerName, String name, String dim,
                                    List<BlockPos> positions, List<List<String>> rules) {
        if (owner == null || positions == null || positions.size() < 2) return;
        // Carry over OST grants from the owner's previous overlapping link, then drop it.
        List<String> carriedOst = new ArrayList<>();
        for (Link l : data.links) {
            if (owner.equals(l.owner) && dimEq(l.dim, dim) && overlaps(l, positions)) carriedOst.addAll(l.allowOst);
        }
        data.links.removeIf(l -> owner.equals(l.owner) && dimEq(l.dim, dim) && overlaps(l, positions));
        Link link = new Link();
        link.owner = owner;
        link.ownerName = ownerName != null ? ownerName : "";
        link.name = name != null ? name : "";
        link.dim = dim;
        for (int i = 0; i < positions.size(); i++) {
            BlockPos p = positions.get(i);
            link.positions.add(new int[]{p.getX(), p.getY(), p.getZ()});
            link.rules.add(i < rules.size() && rules.get(i) != null ? new ArrayList<>(rules.get(i)) : new ArrayList<>());
        }
        for (String u : carriedOst) if (!link.allowOst.contains(u)) link.allowOst.add(u);
        data.links.add(link);
    }

    /**
     * True when any of {@code positions} already belongs to a link owned by SOMEONE ELSE. The server
     * uses this to reject an upload that would absorb another player's (linked) chest — anti-theft.
     */
    public synchronized boolean anyPositionInOtherOwnersLink(String dim, List<BlockPos> positions, String owner) {
        for (Link l : data.links) {
            if (!dimEq(l.dim, dim) || owner.equals(l.owner)) continue;
            for (BlockPos p : positions) {
                if (l.contains(p.getX(), p.getY(), p.getZ())) return true;
            }
        }
        return false;
    }

    /** True when {@code pos} belongs to a link owned by someone OTHER than {@code viewer}. */
    public synchronized boolean isForeignLinkChest(String dim, BlockPos pos, String viewer) {
        Link l = findLinkAt(dim, pos);
        return l != null && !l.owner.equals(viewer);
    }

    /** Whether {@code viewer} may OST the link containing {@code pos}: the owner, or a granted player. */
    public synchronized boolean canOst(String dim, BlockPos pos, String viewer) {
        Link l = findLinkAt(dim, pos);
        if (l == null) return true;                       // not a link → normal single-chest sort
        if (l.owner.equals(viewer)) return true;          // owner always may
        return l.allowOst.contains(viewer);               // others only if explicitly granted
    }

    /** Owner grants/revokes OST permission for {@code target} on the link containing {@code pos}. */
    public synchronized boolean setOst(String owner, String dim, BlockPos pos, String target, boolean allow) {
        Link l = findLinkAt(dim, pos);
        if (l == null || !l.owner.equals(owner) || target == null || target.equals(owner)) return false;
        if (allow) {
            if (l.allowOst.contains(target)) return false;
            l.allowOst.add(target);
        } else {
            if (!l.allowOst.remove(target)) return false;
        }
        return true;
    }

    /** The set of UUIDs (besides the owner) currently allowed to OST the link containing {@code pos}. */
    public synchronized List<String> ostAllowed(String dim, BlockPos pos) {
        Link l = findLinkAt(dim, pos);
        return l != null ? new ArrayList<>(l.allowOst) : new ArrayList<>();
    }

    /** Owner UUID of the link containing {@code pos}, or null. */
    public synchronized String ownerOfLinkAt(String dim, BlockPos pos) {
        Link l = findLinkAt(dim, pos);
        return l != null ? l.owner : null;
    }

    // ===== Player roster (for the OST-permission picker) =====

    public synchronized boolean recordPlayer(String uuid, String name) {
        if (uuid == null) return false;
        if (data.roster == null) data.roster = new HashMap<>();
        String prev = data.roster.put(uuid, name != null ? name : "");
        return prev == null || !prev.equals(name);
    }

    public synchronized List<RosterPlayer> roster(String exclude) {
        List<RosterPlayer> out = new ArrayList<>();
        if (data.roster == null) return out;
        for (Map.Entry<String, String> e : data.roster.entrySet()) {
            if (e.getKey().equals(exclude)) continue;
            out.add(new RosterPlayer(e.getKey(), e.getValue()));
        }
        return out;
    }

    /** Delete the owner's link that contains {@code pos}. Returns true if something was removed. */
    public synchronized boolean delete(String owner, String dim, BlockPos pos) {
        return data.links.removeIf(l -> owner.equals(l.owner) && dimEq(l.dim, dim)
                && l.contains(pos.getX(), pos.getY(), pos.getZ()));
    }

    /** The link in {@code dim} that contains {@code pos}, or null. */
    public synchronized Link findLinkAt(String dim, BlockPos pos) {
        for (Link l : data.links) {
            if (dimEq(l.dim, dim) && l.contains(pos.getX(), pos.getY(), pos.getZ())) return l;
        }
        return null;
    }

    /** Build the engine's per-chest rules for the whole link containing {@code pos} (owner's rules). */
    public synchronized List<ChestRules> rulesFor(String dim, BlockPos pos) {
        Link l = findLinkAt(dim, pos);
        if (l == null) return null;
        List<ChestRules> out = new ArrayList<>();
        for (int i = 0; i < l.positions.size(); i++) {
            int[] p = l.positions.get(i);
            List<String> r = i < l.rules.size() ? l.rules.get(i) : new ArrayList<>();
            out.add(new ChestRules(new BlockPos(p[0], p[1], p[2]), r != null ? r : new ArrayList<>()));
        }
        return out;
    }

    /** Result of resolving which link chests a viewer may see on the map. */
    public record VisibleLinks(List<BlockPos> own, List<BlockPos> foreign, List<String> foreignOwners) {}

    /**
     * For a viewer in {@code dim}: every chest of links they own, plus every chest of OTHERS' links
     * that intersect {@code knownKeys} (reveal-on-open). {@code knownKeys} holds "x,y,z" strings of the
     * chests the viewer has opened.
     */
    public synchronized VisibleLinks visibleTo(String dim, String viewer, Set<String> knownKeys) {
        List<BlockPos> own = new ArrayList<>();
        List<BlockPos> foreign = new ArrayList<>();
        List<String> foreignOwners = new ArrayList<>();
        for (Link l : data.links) {
            if (!dimEq(l.dim, dim)) continue;
            boolean mine = viewer != null && viewer.equals(l.owner);
            if (mine) {
                for (int[] p : l.positions) own.add(new BlockPos(p[0], p[1], p[2]));
                continue;
            }
            // Foreign link: revealed only if the viewer has opened at least one of its chests.
            boolean revealed = false;
            for (int[] p : l.positions) {
                if (knownKeys.contains(p[0] + "," + p[1] + "," + p[2])) { revealed = true; break; }
            }
            if (!revealed) continue;
            for (int[] p : l.positions) {
                foreign.add(new BlockPos(p[0], p[1], p[2]));
                foreignOwners.add(l.ownerName != null ? l.ownerName : "");
            }
        }
        return new VisibleLinks(own, foreign, foreignOwners);
    }


    /** The on-disk links file (used by the client {@code /InOr disk} diagnostic to report its path/size). */
    public static Path file() { return FILE; }

    private static boolean overlaps(Link l, List<BlockPos> positions) {
        for (BlockPos p : positions) {
            if (l.contains(p.getX(), p.getY(), p.getZ())) return true;
        }
        return false;
    }

    private static boolean dimEq(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }

    public void load() {
        try {
            if (Files.exists(FILE)) {
                Data d = GSON.fromJson(Files.readString(FILE), Data.class);
                if (d != null && d.links != null) data = d;
            }
        } catch (Throwable t) {
            LOGGER.warn("Could not load warehouse links: {}", t.toString());
        }
    }

    public synchronized void save() {
        try {
            Files.createDirectories(FILE.getParent());
            Files.writeString(FILE, GSON.toJson(data));
        } catch (Throwable t) {
            LOGGER.warn("Could not save warehouse links: {}", t.toString());
        }
    }
}
