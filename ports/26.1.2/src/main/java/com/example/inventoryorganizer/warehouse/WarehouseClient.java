package com.example.inventoryorganizer.warehouse;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Client-side warehouse state (simplified link model).
 *
 * <p>Chest profiles are now purely local/private (see {@code OrganizerConfig}/{@code ChestIdentifier});
 * the server only shares LINKS. This class tracks the availability handshake, the set of link chests
 * the server says we may see (our own links + foreign links revealed to us), and the remote-crafting
 * stock. Warehouse UI gates on {@link #isAvailable()}.
 */
public final class WarehouseClient {

    private static final Logger LOGGER = LoggerFactory.getLogger("inventory-organizer/Warehouse");

    private static volatile boolean available = false;

    // Link visibility from the server (for the warehouse map + chest UI). Keys = "x,y,z".
    private static final Set<String> ownLinkKeys = ConcurrentHashMap.newKeySet();      // chests of links I own
    private static final Set<String> foreignLinkKeys = ConcurrentHashMap.newKeySet();  // revealed others' links
    private static final Map<String, String> foreignOwnerByKey = new ConcurrentHashMap<>();
    private static final List<BlockPos> foreignLinkPositions = new ArrayList<>();      // revealed others' link chests
    private static volatile int linkVersion = 0;  // bumped on each server link update → map rebuilds

    private WarehouseClient() {}

    private static String k(BlockPos p) { return p.getX() + "," + p.getY() + "," + p.getZ(); }

    /**
     * True when this is a real multiplayer context (dedicated server, or a LAN-published world), so the
     * server-side link sharing is active. Single-player (not published) keeps everything local.
     */
    public static boolean useServerProfiles() {
        if (!available) return false;
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) return false;
        if (!mc.hasSingleplayerServer()) return true;
        net.minecraft.client.server.IntegratedServer s = mc.getSingleplayerServer();
        return s != null && s.isPublished();
    }

    /**
     * True when the warehouse subsystem can be used. The server's handshake sets {@link #available};
     * single-player is always available because the integrated server runs this same mod.
     */
    public static boolean isAvailable() {
        if (available) return true;
        Minecraft mc = Minecraft.getInstance();
        return mc != null && mc.hasSingleplayerServer();
    }

    // ===== Link visibility =====

    public static boolean isOwnLinkChest(BlockPos p) { return p != null && ownLinkKeys.contains(k(p)); }
    public static boolean isForeignLinkChest(BlockPos p) { return p != null && foreignLinkKeys.contains(k(p)); }
    public static String foreignLinkOwner(BlockPos p) { return p == null ? "" : foreignOwnerByKey.getOrDefault(k(p), ""); }
    /** All revealed foreign-link chests (shown on the map even if never opened here). */
    public static List<BlockPos> getForeignLinkChests() { return new ArrayList<>(foreignLinkPositions); }
    public static int linkVersion() { return linkVersion; }
    /** Back-compat alias used by the map screen's rebuild trigger. */
    public static int dataVersion() { return linkVersion; }

    /** Ask the server which link chests we may see (our own links + foreign links revealed to us). */
    public static void requestLinkMap() {
        if (!available) return;
        com.example.inventoryorganizer.config.OrganizerConfig cfg =
                com.example.inventoryorganizer.config.OrganizerConfig.get();
        List<BlockPos> known = new ArrayList<>();
        for (int[] q : cfg.getKnownChests()) {
            if (q.length == 3 && !cfg.isNothingChest(q[0], q[1], q[2])) known.add(new BlockPos(q[0], q[1], q[2]));
        }
        if (known.isEmpty()) return;
        if (known.size() > WarehouseMapQueryPayload.MAX) known = known.subList(0, WarehouseMapQueryPayload.MAX);
        ClientPlayNetworking.send(new WarehouseMapQueryPayload(known));
    }

    /** Owner uploads/refreshes a link: member positions + their per-chest slot rules. */
    public static void uploadLink(String name, List<BlockPos> positions, List<List<String>> rules) {
        if (!available || positions == null || positions.size() < 2) return;
        List<ChestRules> chests = new ArrayList<>();
        for (int i = 0; i < positions.size(); i++) {
            List<String> r = i < rules.size() && rules.get(i) != null ? rules.get(i) : new ArrayList<>();
            chests.add(new ChestRules(positions.get(i).immutable(), new ArrayList<>(r)));
        }
        ClientPlayNetworking.send(new UploadLinkPayload(name != null ? name : "", chests));
    }

    /** Owner deletes the link that contains {@code anyPos}. */
    public static void deleteLink(BlockPos anyPos) {
        if (!available || anyPos == null) return;
        ClientPlayNetworking.send(new DeleteLinkPayload(anyPos.immutable()));
    }

    /** Sort the whole link containing {@code anyPos} with the owner's stored rules (works for non-owners). */
    public static void sortLink(BlockPos anyPos) {
        if (!available || anyPos == null) return;
        syncGroups();
        ClientPlayNetworking.send(new SortLinkPayload(anyPos.immutable()));
    }

    /** Content hash of the last groups we sent, so we don't re-upload the (large) membership unchanged.
     *  Reset to a sentinel on disconnect → the next handshake always re-syncs to the fresh server. */
    private static int lastGroupsHash = Integer.MIN_VALUE;

    /**
     * Push this client's custom-group membership (name → ids) to the server so server-side warehouse/OST
     * sorting honours the groups the player edited locally. Sent on the warehouse handshake and right
     * before each sort, but only actually transmitted when the membership CHANGED since the last send
     * (the "blocks" group alone is ~1 100 ids, so re-sending every sort would be wasteful). No-op when the
     * server doesn't run the mod.
     */
    public static void syncGroups() {
        if (!available) return;
        try {
            java.util.Map<String, java.util.List<String>> groups =
                    com.example.inventoryorganizer.config.OrganizerConfig.get().getCustomGroups();
            java.util.List<String> names = new ArrayList<>();
            java.util.List<Integer> sizes = new ArrayList<>();
            java.util.List<String> ids = new ArrayList<>();
            for (java.util.Map.Entry<String, java.util.List<String>> e : groups.entrySet()) {
                if (names.size() >= SyncGroupsPayload.MAX_GROUPS) break;
                java.util.List<String> mem = e.getValue() != null ? e.getValue() : java.util.List.of();
                int n = 0;
                for (String id : mem) {
                    if (ids.size() >= SyncGroupsPayload.MAX_IDS) break;
                    ids.add(id); n++;
                }
                names.add(e.getKey());
                sizes.add(n);
            }
            int hash = (31 * names.hashCode() + sizes.hashCode()) * 31 + ids.hashCode();
            if (hash == lastGroupsHash) return; // unchanged since last send — skip the upload
            lastGroupsHash = hash;
            ClientPlayNetworking.send(new SyncGroupsPayload(names, sizes, ids));
        } catch (Throwable ignored) {}
    }

    // ===== OST-permission picker (owner grants per-player OST on a link) =====

    private static volatile OstRosterPayload ostRoster = null;
    private static volatile int ostRosterVersion = 0;

    public static void requestOstRoster(BlockPos anyPos) {
        if (!available || anyPos == null) return;
        ostRoster = null;
        ClientPlayNetworking.send(new RequestOstRosterPayload(anyPos.immutable()));
    }

    public static OstRosterPayload getOstRoster() { return ostRoster; }
    public static int ostRosterVersion() { return ostRosterVersion; }

    public static void setOstPerm(BlockPos anyPos, String targetUuid, boolean allow) {
        if (!available || anyPos == null || targetUuid == null) return;
        ClientPlayNetworking.send(new SetOstPermPayload(anyPos.immutable(), targetUuid, allow));
    }

    /** Register the client receivers for the server hello + link map + craft stock; reset on disconnect. */
    public static void registerClient() {
        ClientPlayNetworking.registerGlobalReceiver(WarehouseHelloPayload.TYPE, (payload, context) -> {
            available = true;
            LOGGER.info("Warehouse subsystem available on this server (protocol {})", payload.protocol());
            // Push our custom-group membership so server-side group routing matches our local edits.
            syncGroups();
        });
        ClientPlayNetworking.registerGlobalReceiver(LinkMapDataPayload.TYPE, (payload, context) -> {
            ownLinkKeys.clear();
            foreignLinkKeys.clear();
            foreignOwnerByKey.clear();
            foreignLinkPositions.clear();
            for (BlockPos p : payload.ownLinkChests()) ownLinkKeys.add(k(p));
            java.util.List<BlockPos> fp = payload.foreignLinkChests();
            java.util.List<String> fn = payload.foreignOwnerNames();
            for (int i = 0; i < fp.size(); i++) {
                BlockPos p = fp.get(i);
                foreignLinkKeys.add(k(p));
                foreignLinkPositions.add(p);
                foreignOwnerByKey.put(k(p), i < fn.size() ? fn.get(i) : "");
            }
            linkVersion++;
        });
        ClientPlayNetworking.registerGlobalReceiver(OstRosterPayload.TYPE, (payload, context) -> {
            ostRoster = payload;
            ostRosterVersion++;
        });
        ClientPlayNetworking.registerGlobalReceiver(CraftStockPayload.TYPE, (payload, context) -> {
            craftStock.clear();
            java.util.List<ChestStockView> chests = new java.util.ArrayList<>();
            java.util.List<String> ids = payload.itemIds();
            java.util.List<Integer> counts = payload.itemCounts();
            int p = 0; // running index into the flattened item lists
            for (int ci = 0; ci < payload.chests().size(); ci++) {
                int n = ci < payload.sizes().size() ? payload.sizes().get(ci) : 0;
                java.util.LinkedHashMap<String, Integer> items = new java.util.LinkedHashMap<>();
                for (int k = 0; k < n && p < ids.size(); k++, p++) {
                    int c = p < counts.size() ? counts.get(p) : 0;
                    items.put(ids.get(p), c);
                    craftStock.merge(ids.get(p), c, Integer::sum); // aggregated (recipe-book green highlight)
                }
                String name = ci < payload.names().size() ? payload.names().get(ci) : "Chest";
                chests.add(new ChestStockView(payload.chests().get(ci), name, items));
            }
            craftChests = chests;
            craftStockVersion++;
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            available = false;
            lastGroupsHash = Integer.MIN_VALUE; // force a fresh group-sync on the next server's handshake
            ownLinkKeys.clear();
            foreignLinkKeys.clear();
            foreignOwnerByKey.clear();
            foreignLinkPositions.clear();
            craftStock.clear();
            craftChests = new ArrayList<>();
            ostRoster = null;
            linkVersion++;
            craftStockVersion++;
            ostRosterVersion++;
        });
    }

    // ===== Remote crafting: nearby chest stock =====

    private static final Map<String, Integer> craftStock = new ConcurrentHashMap<>(); // itemId -> total (aggregated)
    private static volatile int craftStockVersion = 0;

    /** One chest's stock as seen by the client (position, display name, ordered item→count). */
    public record ChestStockView(BlockPos pos, String name, java.util.LinkedHashMap<String, Integer> items) {}
    private static volatile List<ChestStockView> craftChests = new ArrayList<>();
    /** Per-chest stock for the materials panel (sections + names + per-chest withdraw). */
    public static List<ChestStockView> getCraftChests() { return new ArrayList<>(craftChests); }

    /**
     * Remote-fetch reach in blocks: 60 only in true single player AND at a crafting table; 15 otherwise
     * (the plain inventory, or any server). The open screen tells us the context.
     */
    public static double craftReach() {
        Minecraft mc = Minecraft.getInstance();
        boolean trueSp = mc.hasSingleplayerServer()
                && mc.getSingleplayerServer() != null && !mc.getSingleplayerServer().isPublished();
        boolean atTable = mc.screen instanceof net.minecraft.client.gui.screens.inventory.CraftingScreen;
        return (trueSp && atTable) ? 60.0 : 15.0;
    }

    /** Known chests within craft reach of the player (the candidate list sent to the server). */
    private static List<BlockPos> nearbyKnownChests() {
        List<BlockPos> out = new ArrayList<>();
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return out;
        net.minecraft.world.phys.Vec3 me = mc.player.position();
        double rSqr = craftReach() * craftReach();
        com.example.inventoryorganizer.config.OrganizerConfig cfg =
                com.example.inventoryorganizer.config.OrganizerConfig.get();
        for (int[] q : cfg.getKnownChests()) {
            if (q.length != 3 || cfg.isNothingChest(q[0], q[1], q[2])) continue;
            BlockPos p = new BlockPos(q[0], q[1], q[2]);
            if (p.getCenter().distanceToSqr(me) <= rSqr) out.add(p);
            if (out.size() >= CraftStockQueryPayload.MAX) break;
        }
        return out;
    }

    /** Ask the server for the combined contents of the player's nearby known chests. */
    public static void requestCraftStock() {
        // Only ever talk to a server that confirmed the mod via the handshake (never a vanilla server).
        if (!available) return;
        List<BlockPos> near = nearbyKnownChests();
        if (near.isEmpty()) { craftStock.clear(); craftChests = new ArrayList<>(); craftStockVersion++; return; }
        // Crafting always pulls ingredients from nearby chests by design (the old toggle was removed).
        ClientPlayNetworking.send(new CraftStockQueryPayload(near, true));
    }

    /** Pull {@code amount} of {@code itemId} from ONE specific chest into the inventory. */
    public static void withdraw(String itemId, int amount, BlockPos source) {
        if (!available || itemId == null || amount <= 0 || source == null) return;
        ClientPlayNetworking.send(new CraftWithdrawPayload(itemId, amount, source));
    }

    /** Deposit the stack currently held on the cursor into a nearby chest (server reads the held stack). */
    public static void depositCarried() {
        if (!available) return;
        List<BlockPos> near = nearbyKnownChests();
        if (near.isEmpty()) return;
        ClientPlayNetworking.send(new CraftDepositPayload(near));
    }

    /** Cancel the recipe: send all crafting-grid ingredients back to nearby chests (each chest OST'd). */
    public static void returnGrid() {
        if (!available) return;
        List<BlockPos> near = nearbyKnownChests();
        if (near.isEmpty()) return;
        ClientPlayNetworking.send(new CraftReturnGridPayload(near));
    }

    public static Map<String, Integer> getCraftStock() { return new HashMap<>(craftStock); }
    public static int craftStockVersion() { return craftStockVersion; }

    // ===== Owner warehouse sort (own links) =====

    /** You can't sort chests further than this from yourself (matches the server's guard). */
    public static final double MAX_REACH = 60.0;

    /**
     * Ask the server to sort the given chests as one warehouse group, sending each chest's local
     * per-chest profile rules. Used by the OWNER (who has the rules locally). Non-owners use
     * {@link #sortLink(BlockPos)} instead.
     */
    public static void requestSort(List<BlockPos> positions) {
        if (!available || positions == null || positions.isEmpty()) return;
        Minecraft mc = Minecraft.getInstance();
        net.minecraft.world.phys.Vec3 me = mc.player != null ? mc.player.position() : null;
        com.example.inventoryorganizer.config.OrganizerConfig cfg =
                com.example.inventoryorganizer.config.OrganizerConfig.get();
        List<ChestRules> out = new ArrayList<>();
        for (BlockPos p : positions) {
            if (me != null && p.getCenter().distanceToSqr(me) > MAX_REACH * MAX_REACH) continue;
            List<String> rules = new ArrayList<>();
            com.example.inventoryorganizer.config.StoragePreset profile = cfg.findProfileFor(
                    null, java.util.List.of(new int[]{p.getX(), p.getY(), p.getZ()}), null);
            if (profile != null) {
                int size = profile.getSize();
                for (int i = 0; i < size; i++) rules.add(profile.getSlotRule(i));
            }
            out.add(new ChestRules(p.immutable(), rules));
        }
        if (out.isEmpty()) return;
        syncGroups();
        ClientPlayNetworking.send(new SortWarehousePayload(out));
    }

    /**
     * Build the per-chest local rules for a set of link positions and upload the link to the server,
     * so other players can later reveal + OST it. Called by the owner when (re)linking chests.
     */
    public static void publishLink(String name, List<BlockPos> positions) {
        if (!available || positions == null || positions.size() < 2) return;
        com.example.inventoryorganizer.config.OrganizerConfig cfg =
                com.example.inventoryorganizer.config.OrganizerConfig.get();
        List<List<String>> rules = new ArrayList<>();
        for (BlockPos p : positions) {
            List<String> r = new ArrayList<>();
            com.example.inventoryorganizer.config.StoragePreset profile = cfg.findProfileFor(
                    null, java.util.List.of(new int[]{p.getX(), p.getY(), p.getZ()}), null);
            if (profile != null) {
                int size = profile.getSize();
                for (int i = 0; i < size; i++) r.add(profile.getSlotRule(i));
            }
            rules.add(r);
        }
        uploadLink(name, positions, rules);
    }
}
