package com.example.inventoryorganizer.warehouse;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Common + server-side networking for the warehouse subsystem (simplified link model).
 *
 * <p>Chest profiles are purely local/private to each client — the server never stores them. The only
 * shared, server-side concept is the {@link WarehouseLinks LINK}: a group of chests an owner exposes
 * so that other players who open one of its chests can OST the whole group with the owner's rules
 * (reveal-on-open), without ever editing it.
 *
 * <p>{@link #registerCommon()} registers the payload types and MUST run on both sides.
 * {@link #registerServer()} wires the server-side receivers (harmless to register on the client — the
 * events only fire server-side, e.g. on the integrated server in single-player).
 */
public final class WarehouseNet {

    private static final org.slf4j.Logger LOGGER =
            org.slf4j.LoggerFactory.getLogger("inventory-organizer/Warehouse");

    /** Handshake protocol version. Bump when the warehouse packet set changes incompatibly. */
    public static final int PROTOCOL = 2;

    /** Anti-spam: minimum gap between accepted sort requests per player (ms). */
    private static final long SORT_COOLDOWN_MS = 300L;
    private static final Map<UUID, Long> LAST_SORT = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> LAST_WITHDRAW = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> LAST_SWITCH = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> LAST_MAP_QUERY = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> LAST_SYNC_GROUPS = new ConcurrentHashMap<>();

    /** Per-player nearby-chest list + craft-source preference, refreshed by CraftStockQuery (~every 0.5s
     *  while a crafting screen is open). Used by the vanilla recipe-book place hook (PlaceRecipeMixin). */
    public record CraftCtx(List<BlockPos> chests, boolean preferChests, long ts) {}
    private static final Map<UUID, CraftCtx> CRAFT_CTX = new ConcurrentHashMap<>();

    /** The player's recent craft context (chest list + preference), or null if stale (&gt;5s) / unknown. */
    public static CraftCtx craftCtx(UUID id) {
        CraftCtx c = CRAFT_CTX.get(id);
        if (c == null || System.currentTimeMillis() - c.ts() > 5000L) return null;
        return c;
    }

    // Per-player tally of what the recipe-book pull borrowed FROM the chests for the current (not-yet-
    // crafted) grid, REMEMBERING the source chest of each pull. Lets the return send each ingredient back
    // to the exact chest it came from, and only chest-sourced amounts (hand-placed items stay).
    private static final class Borrow {
        final String id; final BlockPos chest; int count;
        Borrow(String id, BlockPos chest, int count) { this.id = id; this.chest = chest; this.count = count; }
    }
    private static final Map<UUID, List<Borrow>> BORROWED = new ConcurrentHashMap<>();

    /** Record that {@code count} of {@code itemId} was pulled from the chest at {@code chest}. */
    public static void recordBorrow(UUID id, String itemId, BlockPos chest, int count) {
        if (id == null || itemId == null || chest == null || count <= 0) return;
        List<Borrow> list = BORROWED.computeIfAbsent(id, k -> java.util.Collections.synchronizedList(new ArrayList<>()));
        synchronized (list) {
            for (Borrow b : list) if (b.id.equals(itemId) && b.chest.equals(chest)) { b.count += count; return; }
            list.add(new Borrow(itemId, chest.immutable(), count));
        }
    }

    /**
     * Return the chest-borrowed ingredients still in the crafting grid to the EXACT chest each was pulled
     * from (each chest OST'd), capped per (item, chest) by how much was borrowed — so hand-placed items
     * stay put. If the source chest is unreachable/full, the remainder spills to any nearby chest. Reads
     * the grid while it's still intact (recipe-switch HEAD / crafting-table {@code removed} HEAD). Clears.
     */
    public static void returnBorrowedFromGrid(ServerPlayer player, ServerLevel level,
                                              java.util.List<net.minecraft.world.inventory.Slot> slots) {
        if (player == null || level == null || slots == null) return;
        List<Borrow> borrowed = BORROWED.remove(player.getUUID());
        if (borrowed == null || borrowed.isEmpty()) return;
        CraftCtx ctx = craftCtx(player.getUUID()); // fallback chest list if a source is gone/full
        for (Borrow b : borrowed) {
            int need = b.count;
            for (net.minecraft.world.inventory.Slot s : slots) {
                if (need <= 0) break;
                if (!(s.container instanceof net.minecraft.world.inventory.CraftingContainer)) continue;
                net.minecraft.world.item.ItemStack in = s.getItem();
                if (in.isEmpty()) continue;
                String sid = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(in.getItem()).toString();
                if (!sid.equals(b.id)) continue;
                int take = Math.min(need, in.getCount());
                net.minecraft.world.item.ItemStack moved = in.copy();
                moved.setCount(take);
                // Source chest first; whatever doesn't fit spills to any nearby chest.
                net.minecraft.world.item.ItemStack leftover = RemoteStock.depositInto(player, level, b.chest, moved);
                if (!leftover.isEmpty() && ctx != null && !ctx.chests().isEmpty()) {
                    leftover = RemoteStock.deposit(player, level, ctx.chests(), leftover);
                }
                int deposited = take - leftover.getCount();
                if (deposited > 0) {
                    in.shrink(deposited);
                    s.set(in.isEmpty() ? net.minecraft.world.item.ItemStack.EMPTY : in);
                    need -= deposited;
                }
            }
        }
    }

    /** Distance guard: you can only affect chests this near (blocks). */
    private static final double MAX_REACH_SQR = 60.0 * 60.0;

    /** Reveal-on-open anti-enumeration: a foreign link is only revealed for a claimed "opened" chest
     *  once the player has actually been at it (this tight, hand-reach distance corresponds to standing
     *  at the chest, not just somewhere near the base). */
    private static final double REVEAL_REACH_SQR = 8.0 * 8.0;
    /** Per-player set (session-scoped) of chest positions the player has genuinely been at — used to
     *  validate the client's claimed "opened" list before revealing another owner's link. */
    private static final Map<UUID, Set<Long>> REVEAL_SEEN = new ConcurrentHashMap<>();

    private WarehouseNet() {}

    /** Register payload types. Run from the common entrypoint so both sides agree on the wire format. */
    public static void registerCommon() {
        // Clientbound (S2C)
        PayloadTypeRegistry.clientboundPlay().register(WarehouseHelloPayload.TYPE, WarehouseHelloPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(LinkMapDataPayload.TYPE, LinkMapDataPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(OstRosterPayload.TYPE, OstRosterPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(CraftStockPayload.TYPE, CraftStockPayload.CODEC);
        // Serverbound (C2S)
        PayloadTypeRegistry.serverboundPlay().register(SortWarehousePayload.TYPE, SortWarehousePayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(WarehouseMapQueryPayload.TYPE, WarehouseMapQueryPayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(UploadLinkPayload.TYPE, UploadLinkPayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(DeleteLinkPayload.TYPE, DeleteLinkPayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(SortLinkPayload.TYPE, SortLinkPayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(RequestOstRosterPayload.TYPE, RequestOstRosterPayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(SetOstPermPayload.TYPE, SetOstPermPayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(CraftStockQueryPayload.TYPE, CraftStockQueryPayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(CraftWithdrawPayload.TYPE, CraftWithdrawPayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(CraftDepositPayload.TYPE, CraftDepositPayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(CraftReturnGridPayload.TYPE, CraftReturnGridPayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(SyncGroupsPayload.TYPE, SyncGroupsPayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(SwitchTriggerPayload.TYPE, SwitchTriggerPayload.CODEC);
    }

    /** Per-player custom-group membership (name → ordered ids) synced from the client, for server-side
     *  warehouse/OST group routing on a dedicated server (where the server's own config is empty). */
    private static final Map<UUID, Map<String, List<String>>> PLAYER_GROUPS = new ConcurrentHashMap<>();

    /** The custom groups the given player last synced, or null/empty if none (use the heuristic then). */
    public static Map<String, List<String>> playerGroups(UUID id) {
        return id == null ? null : PLAYER_GROUPS.get(id);
    }

    /** Server-side wiring: greet joiners + handle warehouse link + sort requests. */
    public static void registerServer() {
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            ServerPlayNetworking.send(handler.player, new WarehouseHelloPayload(PROTOCOL));
            // Remember every joiner so link owners can grant them OST permission (the picker roster).
            try {
                ServerPlayer p = handler.player;
                if (WarehouseLinks.get().recordPlayer(p.getUUID().toString(), p.getName().getString())) {
                    WarehouseLinks.get().save();
                }
            } catch (Throwable ignored) {}
        });

        // Owner sorts their OWN warehouse group: the client supplies each chest's rules directly.
        ServerPlayNetworking.registerGlobalReceiver(SortWarehousePayload.TYPE, (payload, context) -> {
            ServerPlayer player = context.player();
            if (player == null) return;
            if (onCooldown(player)) { LOGGER.info("[Warehouse] sort(own) ignored: on cooldown"); return; }
            ServerLevel level = (ServerLevel) player.level();
            level.getServer().execute(() -> {
                try {
                    String dim = level.dimension().identifier().toString();
                    String uuid = player.getUUID().toString();
                    LOGGER.info("[Warehouse] sort(own) request: {} chest(s)", payload.chests().size());
                    // Distinct chest count: a single-chest request is just an in-place sort (items stay
                    // in that one chest, no theft possible). A MULTI-chest request moves items between
                    // chests, so we only allow it across the player's OWN server-side link — a hacked
                    // client must not route a neighbour's items into its own chest by listing arbitrary
                    // nearby positions.
                    java.util.Set<BlockPos> distinct = new HashSet<>();
                    for (ChestRules cr : payload.chests()) if (cr.pos() != null) distinct.add(cr.pos());
                    boolean multi = distinct.size() > 1;
                    java.util.List<ChestRules> safe = new ArrayList<>();
                    for (ChestRules cr : payload.chests()) {
                        if (cr.pos() == null) continue;
                        // Never touch another player's linked chest.
                        if (WarehouseLinks.get().isForeignLinkChest(dim, cr.pos(), uuid)) continue;
                        // Cross-chest moves only within the player's own link.
                        if (multi && !uuid.equals(WarehouseLinks.get().ownerOfLinkAt(dim, cr.pos()))) continue;
                        safe.add(cr);
                    }
                    LOGGER.info("[Warehouse] sort(own): {} of {} chest(s) passed safety (multi={})",
                            safe.size(), payload.chests().size(), multi);
                    if (!safe.isEmpty()) {
                        com.example.inventoryorganizer.WarehouseEngine.sortGroup(player, level, safe);
                    } else {
                        LOGGER.warn("[Warehouse] sort(own) did nothing: no chest passed the safety checks "
                                + "(multi-chest needs an uploaded link owned by you).");
                    }
                } catch (Throwable t) {
                    LOGGER.error("[Warehouse] sort(own) threw", t);
                }
            });
        });

        // A (possibly non-owner) player sorts the whole link containing anyPos, using the OWNER's
        // stored rules. Reveal-on-open lets anyone who opened a link chest trigger this.
        ServerPlayNetworking.registerGlobalReceiver(SortLinkPayload.TYPE, (payload, context) -> {
            ServerPlayer player = context.player();
            if (player == null) return;
            if (onCooldown(player)) { LOGGER.info("[Warehouse] sort(link) ignored: on cooldown"); return; }
            ServerLevel level = (ServerLevel) player.level();
            BlockPos pos = payload.anyPos();
            level.getServer().execute(() -> {
                try {
                    if (pos.getCenter().distanceToSqr(player.position()) > MAX_REACH_SQR) {
                        LOGGER.warn("[Warehouse] sort(link) bail: too far from {} (>60 blocks)", pos);
                        return;
                    }
                    String dim = level.dimension().identifier().toString();
                    if (!WarehouseLinks.get().canOst(dim, pos, player.getUUID().toString())) {
                        LOGGER.warn("[Warehouse] sort(link) bail: no OST permission at {} (not owner/granted)", pos);
                        return;
                    }
                    List<ChestRules> rules = WarehouseLinks.get().rulesFor(dim, pos);
                    if (rules == null || rules.isEmpty()) {
                        LOGGER.warn("[Warehouse] sort(link) bail: no stored link rules at {} — link not uploaded? "
                                + "(create/refresh the link so its per-chest rules are published)", pos);
                        return;
                    }
                    LOGGER.info("[Warehouse] sort(link): {} chest(s) with stored rules at {}", rules.size(), pos);
                    com.example.inventoryorganizer.WarehouseEngine.sortGroup(player, level, rules);
                } catch (Throwable t) {
                    LOGGER.error("[Warehouse] sort(link) threw", t);
                }
            });
        });

        // Map open / chest open: tell the client which link chests it may see (own + revealed foreign).
        ServerPlayNetworking.registerGlobalReceiver(WarehouseMapQueryPayload.TYPE, (payload, context) -> {
            ServerPlayer player = context.player();
            if (player == null) return;
            long _mqNow = System.currentTimeMillis();
            Long _mqLast = LAST_MAP_QUERY.get(player.getUUID());
            if (_mqLast != null && _mqNow - _mqLast < 500L) return; // max 2 map queries/sec
            LAST_MAP_QUERY.put(player.getUUID(), _mqNow);
            ServerLevel level = (ServerLevel) player.level();
            level.getServer().execute(() -> {
                try {
                    String dim = level.dimension().identifier().toString();
                    String uuid = player.getUUID().toString();
                    // Anti-enumeration: a hacked client could otherwise reveal another player's WHOLE link
                    // (every chest position + owner name) by sending GUESSED coordinates it never opened.
                    // Only honour a claimed "opened" position once the player has genuinely been at it
                    // (within hand reach + may-interact); we remember that for the session so the map still
                    // shows it from afar afterwards. The player's OWN links are always visible regardless
                    // (handled in visibleTo) — this gate only affects FOREIGN reveal-on-open.
                    Set<Long> seen = REVEAL_SEEN.computeIfAbsent(player.getUUID(), k -> ConcurrentHashMap.newKeySet());
                    Set<String> known = new HashSet<>();
                    for (BlockPos p : payload.known()) {
                        if (p == null) continue;
                        boolean atItNow = p.getCenter().distanceToSqr(player.position()) <= REVEAL_REACH_SQR
                                && level.mayInteract(player, p);
                        if (atItNow) seen.add(p.asLong());
                        if (atItNow || seen.contains(p.asLong())) {
                            known.add(p.getX() + "," + p.getY() + "," + p.getZ());
                        }
                    }
                    WarehouseLinks.VisibleLinks v = WarehouseLinks.get().visibleTo(dim, uuid, known);
                    ServerPlayNetworking.send(player,
                            new LinkMapDataPayload(v.own(), v.foreign(), v.foreignOwners()));
                } catch (Throwable ignored) {}
            });
        });

        // Owner uploads/refreshes a link (positions + their per-chest rules).
        ServerPlayNetworking.registerGlobalReceiver(UploadLinkPayload.TYPE, (payload, context) -> {
            ServerPlayer player = context.player();
            if (player == null) return;
            ServerLevel level = (ServerLevel) player.level();
            level.getServer().execute(() -> {
                try {
                    List<BlockPos> positions = new ArrayList<>();
                    List<List<String>> rules = new ArrayList<>();
                    for (ChestRules cr : payload.chests()) {
                        // Never trust the client: only accept chests within reach AND ones the player
                        // could actually open by hand (respects spawn protection / world border) — you
                        // can't "claim" a chest into a link that you couldn't interact with.
                        if (cr.pos().getCenter().distanceToSqr(player.position()) > MAX_REACH_SQR) continue;
                        if (!level.mayInteract(player, cr.pos())) continue;
                        positions.add(cr.pos().immutable());
                        rules.add(new ArrayList<>(cr.rules()));
                    }
                    if (positions.size() < 2) return;
                    String dim = level.dimension().identifier().toString();
                    // Anti-theft: refuse to absorb a chest that already belongs to another player's link.
                    if (WarehouseLinks.get().anyPositionInOtherOwnersLink(dim, positions, player.getUUID().toString())) return;
                    WarehouseLinks.get().upsert(player.getUUID().toString(), player.getName().getString(),
                            payload.name(), dim, positions, rules);
                    WarehouseLinks.get().save();
                } catch (Throwable ignored) {}
            });
        });

        // Owner deletes the link containing anyPos.
        ServerPlayNetworking.registerGlobalReceiver(DeleteLinkPayload.TYPE, (payload, context) -> {
            ServerPlayer player = context.player();
            if (player == null) return;
            ServerLevel level = (ServerLevel) player.level();
            level.getServer().execute(() -> {
                try {
                    String dim = level.dimension().identifier().toString();
                    if (WarehouseLinks.get().delete(player.getUUID().toString(), dim, payload.anyPos())) {
                        WarehouseLinks.get().save();
                    }
                } catch (Throwable ignored) {}
            });
        });

        // Owner opens the OST-permission picker for a link → send the roster + each player's grant.
        ServerPlayNetworking.registerGlobalReceiver(RequestOstRosterPayload.TYPE, (payload, context) -> {
            ServerPlayer player = context.player();
            if (player == null) return;
            ServerLevel level = (ServerLevel) player.level();
            level.getServer().execute(() -> {
                try { sendOstRoster(player, payload.anyPos()); } catch (Throwable ignored) {}
            });
        });

        // Owner grants/revokes a player's OST permission on a link.
        ServerPlayNetworking.registerGlobalReceiver(SetOstPermPayload.TYPE, (payload, context) -> {
            ServerPlayer player = context.player();
            if (player == null) return;
            ServerLevel level = (ServerLevel) player.level();
            level.getServer().execute(() -> {
                try {
                    String dim = level.dimension().identifier().toString();
                    if (WarehouseLinks.get().setOst(player.getUUID().toString(), dim, payload.anyPos(),
                            payload.target(), payload.allow())) {
                        WarehouseLinks.get().save();
                    }
                    sendOstRoster(player, payload.anyPos()); // echo fresh state to the owner
                } catch (Throwable ignored) {}
            });
        });

        // Remote crafting: aggregate the player's nearby chests' contents and reply with the stock list.
        ServerPlayNetworking.registerGlobalReceiver(CraftStockQueryPayload.TYPE, (payload, context) -> {
            ServerPlayer player = context.player();
            if (player == null) return;
            // Cache the nearby chest list + preference for the vanilla recipe-book place hook.
            CRAFT_CTX.put(player.getUUID(),
                    new CraftCtx(new ArrayList<>(payload.chests()), payload.preferChests(), System.currentTimeMillis()));
            ServerLevel level = (ServerLevel) player.level();
            level.getServer().execute(() -> {
                try {
                    // Per-chest breakdown → flat parallel lists (chests/names/sizes + flattened ids/counts).
                    List<RemoteStock.ChestStock> per = RemoteStock.aggregatePerChest(player, level, payload.chests());
                    List<BlockPos> cPos = new ArrayList<>();
                    List<String> cNames = new ArrayList<>();
                    List<Integer> cSizes = new ArrayList<>();
                    List<String> ids = new ArrayList<>();
                    List<Integer> counts = new ArrayList<>();
                    for (RemoteStock.ChestStock cs : per) {
                        if (ids.size() >= CraftStockPayload.MAX) break;
                        cPos.add(cs.pos());
                        cNames.add(cs.name());
                        int n = 0;
                        for (Map.Entry<String, Integer> e : cs.items().entrySet()) {
                            if (ids.size() >= CraftStockPayload.MAX) break;
                            ids.add(e.getKey());
                            counts.add(e.getValue());
                            n++;
                        }
                        cSizes.add(n);
                    }
                    ServerPlayNetworking.send(player, new CraftStockPayload(cPos, cNames, cSizes, ids, counts));
                } catch (Throwable ignored) {}
            });
        });

        // Remote crafting: pull an item out of the nearby chests into the player's inventory.
        ServerPlayNetworking.registerGlobalReceiver(CraftWithdrawPayload.TYPE, (payload, context) -> {
            ServerPlayer player = context.player();
            if (player == null) return;
            long now = System.currentTimeMillis();
            Long last = LAST_WITHDRAW.get(player.getUUID());
            if (last != null && now - last < 50L) return; // light anti-spam
            LAST_WITHDRAW.put(player.getUUID(), now);
            ServerLevel level = (ServerLevel) player.level();
            level.getServer().execute(() -> {
                try {
                    RemoteStock.withdrawFrom(player, level, payload.source(), payload.itemId(), payload.amount());
                } catch (Throwable ignored) {}
            });
        });

        // Remote crafting: deposit the stack the player is holding on the cursor into a nearby chest, then
        // OST that chest. Server-authoritative: we read the held stack from the open menu (a hacked client
        // can't deposit items it isn't holding) and re-validate reach/interaction in RemoteStock.
        ServerPlayNetworking.registerGlobalReceiver(CraftDepositPayload.TYPE, (payload, context) -> {
            ServerPlayer player = context.player();
            if (player == null) return;
            long now = System.currentTimeMillis();
            Long last = LAST_WITHDRAW.get(player.getUUID());
            if (last != null && now - last < 50L) return; // shared light anti-spam with withdraw
            LAST_WITHDRAW.put(player.getUUID(), now);
            ServerLevel level = (ServerLevel) player.level();
            level.getServer().execute(() -> {
                try {
                    net.minecraft.world.item.ItemStack carried = player.containerMenu.getCarried();
                    if (carried.isEmpty()) return;
                    net.minecraft.world.item.ItemStack leftover =
                            RemoteStock.deposit(player, level, payload.chests(), carried);
                    player.containerMenu.setCarried(leftover);
                    player.containerMenu.broadcastFullState(); // resync cursor (+ the sorted chest if open)
                } catch (Throwable ignored) {}
            });
        });

        // Remote crafting: cancel the recipe — send every ingredient in the open crafting grid back to the
        // nearby chests (each stack → a chest, each chest OST'd), leaving any leftover in the grid.
        ServerPlayNetworking.registerGlobalReceiver(CraftReturnGridPayload.TYPE, (payload, context) -> {
            ServerPlayer player = context.player();
            if (player == null) return;
            long now = System.currentTimeMillis();
            Long last = LAST_WITHDRAW.get(player.getUUID());
            if (last != null && now - last < 50L) return; // shared light anti-spam
            LAST_WITHDRAW.put(player.getUUID(), now);
            ServerLevel level = (ServerLevel) player.level();
            level.getServer().execute(() -> {
                try {
                    if (!(player.containerMenu instanceof net.minecraft.world.inventory.AbstractCraftingMenu menu)) return;
                    // Same chest-borrowed-only return as the auto switch/close paths (hand-placed items stay).
                    returnBorrowedFromGrid(player, level, menu.slots);
                    menu.broadcastFullState();
                } catch (Throwable ignored) {}
            });
        });

        // Client syncs its custom-group membership (name → ids) so server-side group routing honours what
        // the player edited locally. Re-cap defensively when reading the flat lists.
        ServerPlayNetworking.registerGlobalReceiver(SyncGroupsPayload.TYPE, (payload, context) -> {
            ServerPlayer player = context.player();
            if (player == null) return;
            long _sgNow = System.currentTimeMillis();
            Long _sgLast = LAST_SYNC_GROUPS.get(player.getUUID());
            if (_sgLast != null && _sgNow - _sgLast < 1000L) return; // max 1 group sync/sec
            LAST_SYNC_GROUPS.put(player.getUUID(), _sgNow);
            try {
                Map<String, List<String>> groups = new java.util.HashMap<>();
                List<String> names = payload.names();
                List<Integer> sizes = payload.sizes();
                List<String> ids = payload.ids();
                // Validate: group names must match known switch groups only
                java.util.Set<String> knownGroups = new java.util.HashSet<>(
                    java.util.Arrays.asList(
                        com.example.inventoryorganizer.config.OrganizerConfig.switchGroupName("pickaxe"),
                        com.example.inventoryorganizer.config.OrganizerConfig.switchGroupName("axe"),
                        com.example.inventoryorganizer.config.OrganizerConfig.switchGroupName("shovel"),
                        com.example.inventoryorganizer.config.OrganizerConfig.switchGroupName("hoe"),
                        com.example.inventoryorganizer.config.OrganizerConfig.switchGroupName("mob")
                    )
                );
                int p = 0;
                for (int gi = 0; gi < names.size() && gi < SyncGroupsPayload.MAX_GROUPS; gi++) {
                    String gname = names.get(gi);
                    if (!knownGroups.contains(gname)) { // skip unknown/injected group names
                        int n = gi < sizes.size() ? Math.max(0, sizes.get(gi)) : 0;
                        p += n; continue;
                    }
                    int n = gi < sizes.size() ? Math.max(0, sizes.get(gi)) : 0;
                    List<String> members = new ArrayList<>();
                    for (int k = 0; k < n && p < ids.size(); k++, p++) {
                        String id = ids.get(p);
                        // Item IDs must look like namespaced ids (contain ':')
                        if (id != null && id.contains(":")) members.add(id);
                    }
                    groups.put(gname, members);
                }
                if (groups.isEmpty()) PLAYER_GROUPS.remove(player.getUUID());
                else PLAYER_GROUPS.put(player.getUUID(), groups);
            } catch (Throwable ignored) {}
        });

        // Switch trigger: player pressed the switch keybind — server validates target + performs the swap.
        // Rate limited (max 4 swaps/sec), range checked (5 blocks), fully server-authoritative.
        ServerPlayNetworking.registerGlobalReceiver(SwitchTriggerPayload.TYPE, (payload, context) -> {
            ServerPlayer player = context.player();
            if (player == null) return;
            long now = System.currentTimeMillis();
            Long last = LAST_SWITCH.get(player.getUUID());
            if (last != null && now - last < 250L) return; // max 4 swaps/sec
            LAST_SWITCH.put(player.getUUID(), now);
            ServerLevel level = (ServerLevel) player.level();
            level.getServer().execute(() -> {
                try {
                    com.example.inventoryorganizer.config.OrganizerConfig cfg =
                            com.example.inventoryorganizer.config.OrganizerConfig.get();
                    // switchSlot comes from the CLIENT (validated 0-8) so each player uses THEIR OWN slot
                    // on a dedicated server — the server's own global config is irrelevant to a remote
                    // player. Safe: the swap only ever touches the requesting player's own inventory (they
                    // could rearrange it in a GUI anyway), so a client-chosen slot cannot steal anything.
                    // (No server-side isSwitchEnabled() gate: the client only sends when it has switch on.)
                    int switchSlot = (payload.targetType() == SwitchTriggerPayload.TYPE_BLOCK)
                            ? payload.entityId() : payload.x();
                    if (switchSlot < 0 || switchSlot > 8) return;

                    String category = null;
                    net.minecraft.world.level.block.state.BlockState blockState = null;

                    if (payload.targetType() == SwitchTriggerPayload.TYPE_BLOCK) {
                        BlockPos pos = new BlockPos(payload.x(), payload.y(), payload.z());
                        // Range check: player must be within 5 blocks (MAX_REACH_SQ = 25)
                        if (player.blockPosition().distSqr(pos) > SwitchTriggerPayload.MAX_REACH_SQ) return;
                        blockState = level.getBlockState(pos);
                        if (blockState.is(net.minecraft.tags.BlockTags.MINEABLE_WITH_PICKAXE)) category = "pickaxe";
                        else if (blockState.is(net.minecraft.tags.BlockTags.MINEABLE_WITH_AXE)) category = "axe";
                        else if (blockState.is(net.minecraft.tags.BlockTags.MINEABLE_WITH_SHOVEL)) category = "shovel";
                        else if (blockState.is(net.minecraft.tags.BlockTags.MINEABLE_WITH_HOE)) category = "hoe";
                    } else if (payload.targetType() == SwitchTriggerPayload.TYPE_ENTITY) {
                        net.minecraft.world.entity.Entity e = level.getEntity(payload.entityId());
                        if (e == null) return;
                        if (player.distanceToSqr(e) > SwitchTriggerPayload.MAX_REACH_SQ) return;
                        String etype = "";
                        try { etype = net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).getPath(); } catch (Throwable ignored2) {}
                        if (etype.contains("boat") || etype.contains("raft")) category = "axe";
                        else if (etype.contains("minecart")) category = "pickaxe";
                        else if (e instanceof net.minecraft.world.entity.LivingEntity) category = "mob";
                    } else if (payload.targetType() == SwitchTriggerPayload.TYPE_AIR) {
                        // Restore: swap the client's switchSlot ↔ fromSlot. Both indices are validated and
                        // only address the requesting player's own inventory, so there is nothing to steal.
                        int fromSlot = payload.y();
                        if (fromSlot < 0 || fromSlot > 35 || fromSlot == switchSlot) return;
                        net.minecraft.world.entity.player.Inventory inv = player.getInventory();
                        net.minecraft.world.item.ItemStack a = inv.getItem(switchSlot).copy();
                        net.minecraft.world.item.ItemStack b = inv.getItem(fromSlot).copy();
                        inv.setItem(switchSlot, b); inv.setItem(fromSlot, a);
                        inv.setChanged();
                        return;
                    } else if (payload.targetType() == SwitchTriggerPayload.TYPE_AIR_CAT) {
                        int catIdx = payload.entityId();
                        if (catIdx < 0 || catIdx >= SwitchTriggerPayload.AIR_CATEGORIES.length) return;
                        category = SwitchTriggerPayload.AIR_CATEGORIES[catIdx];
                    }

                    if (category == null) return;

                    // Find best tool (same logic as SwitchToolHandler.selectAndSwap)
                    net.minecraft.world.entity.player.Inventory inv = player.getInventory();
                    Map<String, List<String>> groups = playerGroups(player.getUUID());
                    String groupName = com.example.inventoryorganizer.config.OrganizerConfig.switchGroupName(category);
                    List<String> extra = groups.getOrDefault(groupName,
                            cfg.getCustomGroup(groupName));
                    net.minecraft.tags.TagKey<net.minecraft.world.item.Item> tag = switchVanillaTag(category);

                    int best = -1;
                    int bestMat = Integer.MAX_VALUE;
                    float bestSpeed = -1f;
                    String[] cgOrder = cfg.getPreference("cg_order_" + groupName);
                    String[] matOrder = cfg.getPreference("sort_material_order");

                    for (int i = 0; i < 36; i++) {
                        net.minecraft.world.item.ItemStack s = inv.getItem(i);
                        if (s.isEmpty()) continue;
                        boolean candidate = (tag != null && s.is(tag));
                        if (!candidate && extra != null) {
                            String sid = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(s.getItem()).toString();
                            String spath = sid.contains(":") ? sid.substring(sid.indexOf(':') + 1) : sid;
                            candidate = extra.contains(sid) || extra.contains(spath);
                        }
                        if (!candidate) continue;
                        String sid = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(s.getItem()).toString();
                        int mat = rankMaterial(sid, cgOrder, matOrder);
                        float speed = blockState != null ? s.getDestroySpeed(blockState) : 0f;
                        if (mat < bestMat || (mat == bestMat && speed > bestSpeed)) {
                            best = i; bestMat = mat; bestSpeed = speed;
                        }
                    }
                    if (best < 0 || best == switchSlot) return;

                    net.minecraft.world.item.ItemStack tool = inv.getItem(best).copy();
                    net.minecraft.world.item.ItemStack displaced = inv.getItem(switchSlot).copy();
                    inv.setItem(switchSlot, tool);
                    inv.setItem(best, net.minecraft.world.item.ItemStack.EMPTY);
                    if (!displaced.isEmpty()) {
                        // Merge into matching stack first
                        for (int s = 0; s < 36 && !displaced.isEmpty(); s++) {
                            if (s == switchSlot) continue;
                            net.minecraft.world.item.ItemStack t = inv.getItem(s);
                            if (t.isEmpty() || !net.minecraft.world.item.ItemStack.isSameItemSameComponents(t, displaced)) continue;
                            int room = t.getMaxStackSize() - t.getCount();
                            if (room <= 0) continue;
                            int mv = Math.min(room, displaced.getCount());
                            t.grow(mv); displaced.shrink(mv); inv.setItem(s, t);
                        }
                        if (!displaced.isEmpty()) {
                            // Place in the vacated tool slot
                            inv.setItem(best, displaced);
                        }
                    }
                    inv.setChanged();
                } catch (Throwable ignored) {}
            });
        });

        // Forget cooldown entries when players leave so the maps can't grow unbounded.
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            LAST_SORT.remove(handler.player.getUUID());
            LAST_WITHDRAW.remove(handler.player.getUUID());
            CRAFT_CTX.remove(handler.player.getUUID());
            REVEAL_SEEN.remove(handler.player.getUUID());
            BORROWED.remove(handler.player.getUUID());
            PLAYER_GROUPS.remove(handler.player.getUUID());
            LAST_SWITCH.remove(handler.player.getUUID());
            LAST_MAP_QUERY.remove(handler.player.getUUID());
            LAST_SYNC_GROUPS.remove(handler.player.getUUID());
        });
    }

    private static net.minecraft.tags.TagKey<net.minecraft.world.item.Item> switchVanillaTag(String cat) {
        switch (cat) {
            case "pickaxe": return net.minecraft.tags.ItemTags.PICKAXES;
            case "axe":     return net.minecraft.tags.ItemTags.AXES;
            case "shovel":  return net.minecraft.tags.ItemTags.SHOVELS;
            case "hoe":     return net.minecraft.tags.ItemTags.HOES;
            case "mob":     return net.minecraft.tags.ItemTags.SWORDS;
            default:        return null;
        }
    }

    private static int rankMaterial(String id, String[] cgOrder, String[] matOrder) {
        if (cgOrder != null && cgOrder.length > 0) {
            for (int i = 0; i < cgOrder.length; i++) if (id.equals(cgOrder[i])) return i;
            return cgOrder.length;
        }
        if (matOrder != null && matOrder.length > 0) {
            for (int i = 0; i < matOrder.length; i++)
                if (matOrder[i] != null && !matOrder[i].isEmpty() && id.contains(matOrder[i])) return i;
            return matOrder.length;
        }
        return 0;
    }

    /** Resolve + send the OST roster for the link at {@code pos} to its owner (server thread). */
    private static void sendOstRoster(ServerPlayer player, BlockPos pos) {
        ServerLevel level = (ServerLevel) player.level();
        String dim = level.dimension().identifier().toString();
        String uuid = player.getUUID().toString();
        WarehouseLinks links = WarehouseLinks.get();
        // Only the link's owner may see/manage its OST roster.
        if (!uuid.equals(links.ownerOfLinkAt(dim, pos))) return;
        Set<String> allowed = new HashSet<>(links.ostAllowed(dim, pos));
        List<String> uuids = new ArrayList<>();
        List<String> names = new ArrayList<>();
        List<Boolean> flags = new ArrayList<>();
        for (WarehouseLinks.RosterPlayer rp : links.roster(uuid)) {
            uuids.add(rp.uuid());
            names.add(rp.name());
            flags.add(allowed.contains(rp.uuid()));
        }
        ServerPlayNetworking.send(player, new OstRosterPayload(pos, uuids, names, flags));
    }

    /** Shared sort anti-spam: true when this player's request comes in faster than the cooldown. */
    private static boolean onCooldown(ServerPlayer player) {
        long now = System.currentTimeMillis();
        Long last = LAST_SORT.get(player.getUUID());
        if (last != null && now - last < SORT_COOLDOWN_MS) return true;
        LAST_SORT.put(player.getUUID(), now);
        return false;
    }
}
