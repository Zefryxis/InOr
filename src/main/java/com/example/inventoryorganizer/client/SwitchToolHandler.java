package com.example.inventoryorganizer.client;

import com.example.inventoryorganizer.SortLogic;
import com.example.inventoryorganizer.config.OrganizerConfig;
import com.example.inventoryorganizer.config.SlotRule;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;

import java.util.List;

/**
 * "Switch" auto tool-swapper — SINGLE-PLAYER ONLY. Keeps the right tool for whatever the crosshair targets
 * in a designated hotbar "switch" slot.
 *
 * <p>The TARGET decides which tool category is wanted — exactly like vanilla's "fastest tool": a block by
 * its mineable tag (pickaxe/axe/shovel/hoe), and entities too (boat → axe, minecart → pickaxe, living/
 * hostile → sword). Each category has a fixed editable custom group (Pickaxes/Axes/Shovels/Hoes/Swords)
 * whose members — plus the matching vanilla tool tag — are the candidates; the per-category material RANKS
 * (preferences[category]) pick which piece, destroy speed breaks ties. The displaced item is returned to
 * its proper slot per the normal rules (its assigned slot, else any free slot).
 *
 * <p>SP-only because the swap mutates the integrated server's {@link ServerPlayer} inventory directly.
 */
public final class SwitchToolHandler {

    private SwitchToolHandler() {}

    private static String lastTargetKey = "";
    private static long lastMissingMsgMs = 0L;
    private static int lastSwapFrom = -1; // one-level undo for air="restore"
    private static int evictCheckTick = 0;
    private static boolean prevFightModeActive = false;
    private static long lastAutoServerSendMs = 0L; // paces auto-trigger sends to the server rate-limit (no flicker)

    // Set by the client thread just before a swap is dispatched; consumed by the mixin on next tick
    // to prevent the attack cooldown from resetting when the swap arrives at the client.
    private static volatile boolean switchSwapPending = false;

    /** Mark that a switch swap is about to happen. Called from client thread before server.execute(). */
    public static void markSwitchSwapPending() { switchSwapPending = true; }

    /** Consume the pending flag (returns true and clears if set). Called from the attack reset mixin. */
    public static boolean consumeSwitchSwapPending() {
        if (switchSwapPending) { switchSwapPending = false; return true; }
        return false;
    }

    public static void tick(Minecraft client) {
        try {
            if (client.player == null || client.level == null) return;
            if (client.gui.screen() != null) return;
            OrganizerConfig cfg = OrganizerConfig.get();
            if (!cfg.isSwitchEnabled()) { lastTargetKey = ""; prevFightModeActive = false; return; }

            // Fight mode transition: when combat activates, do one full OI to prep inventory,
            // then stop auto-switching (plain/button mode) for the duration of fight mode.
            // Works in SP and on InOr servers (sortInventory() routes correctly for both).
            boolean fightActive = com.example.inventoryorganizer.FightModeTracker.isActive();
            boolean isSp = client.hasSingleplayerServer();
            boolean serverAvail = com.example.inventoryorganizer.warehouse.WarehouseClient.isAvailable();
            boolean isPrivate = com.example.inventoryorganizer.ServerEnvironment.isPrivateEnvironment();
            if (fightActive && !prevFightModeActive && (isSp || serverAvail)) {
                com.example.inventoryorganizer.InventorySorter.sortInventory();
            }
            prevFightModeActive = fightActive;

            int switchSlotCfg = cfg.getSwitchSlot();
            if (switchSlotCfg < 0 || switchSlotCfg > 8) return;

            // Evict wrong (non-tool) items from the switch slot — checked every 20 ticks (1/sec)
            if (++evictCheckTick >= 20) {
                evictCheckTick = 0;
                if (isSp && !client.player.getInventory().getItem(switchSlotCfg).isEmpty()) {
                    MinecraftServer srvEvict = client.getSingleplayerServer();
                    if (srvEvict != null && !srvEvict.isPublished()) {
                        final int slot = switchSlotCfg;
                        srvEvict.execute(() -> evictWrongItemFromSwitchSlot(client, srvEvict, cfg, slot));
                    }
                }
            }

            // Determine target key (always tracked so button mode knows what changed)
            HitResult hit = client.hitResult;
            String key;
            if (hit instanceof EntityHitResult eh) {
                key = "entity:" + eh.getEntity().getId();
            } else if (hit instanceof BlockHitResult bh && hit.getType() == HitResult.Type.BLOCK) {
                key = "block:" + BuiltInRegistries.BLOCK.getKey(client.level.getBlockState(bh.getBlockPos()).getBlock());
            } else {
                key = "air";
            }

            // Auto trigger: SP (direct swap), InOr server (C2S handler), vanilla server (vanilla SWAP packet).
            // Disabled during fight mode to avoid anti-cheat flags.
            boolean triggerAuto = cfg.isSwitchTriggerAuto() && !fightActive;
            if (!triggerAuto) {
                lastTargetKey = key;
                return;
            }

            // Auto mode on a server: act on every distinct target change.
            if (!isSp) {
                if (!key.equals(lastTargetKey)) {
                    long nowMs = System.currentTimeMillis();
                    if (nowMs - lastAutoServerSendMs >= 100L) {
                        lastAutoServerSendMs = nowMs;
                        lastTargetKey = key;
                        onTriggerKeyPress(client);
                    }
                }
                return;
            }

            // Auto mode: SP direct swap
            MinecraftServer server = client.getSingleplayerServer();
            if (server == null || server.isPublished()) { lastTargetKey = ""; return; }

            String category = null;
            BlockState blockState = null;
            if (hit instanceof EntityHitResult eh) {
                category = entityToolCategory(eh.getEntity());
            } else if (hit instanceof BlockHitResult bh && hit.getType() == HitResult.Type.BLOCK) {
                blockState = client.level.getBlockState(bh.getBlockPos());
                category = blockMineableCategory(blockState);
            } else {
                String airMode = cfg.getSwitchAir();
                if (!key.equals(lastTargetKey)) {
                    if ("restore".equals(airMode) && lastSwapFrom >= 0) {
                        scheduleRestore(client, server, cfg.getSwitchSlot(), lastSwapFrom);
                        lastSwapFrom = -1;
                    } else if (isCategoryAir(airMode)) {
                        final String cat = airMode;
                        dispatchSwap(client, server, cfg, cfg.getSwitchSlot(), cat, null);
                    }
                }
                lastTargetKey = key;
                return;
            }

            if (key.equals(lastTargetKey)) return;
            lastTargetKey = key;
            if (category == null) return;

            final String cat = category;
            final BlockState state = blockState;
            dispatchSwap(client, server, cfg, cfg.getSwitchSlot(), cat, state);
        } catch (Throwable ignored) {}
    }

    /** Called when the switch trigger keybind is pressed. Works in both SP (direct) and on servers (C2S). */
    public static void onTriggerKeyPress(Minecraft client) {
        try {
            if (client.player == null || client.level == null) return;
            if (client.gui.screen() != null) return;
            OrganizerConfig cfg = OrganizerConfig.get();
            if (!cfg.isSwitchEnabled()) return;
            int switchSlot = cfg.getSwitchSlot();
            if (switchSlot < 0 || switchSlot > 8) return;

            HitResult hit = client.hitResult;
            MinecraftServer server = client.getSingleplayerServer();
            boolean isSp = server != null && !server.isPublished();

            boolean serverAvailKey = com.example.inventoryorganizer.warehouse.WarehouseClient.isAvailable();
            if (hit instanceof EntityHitResult eh) {
                String category = entityToolCategory(eh.getEntity());
                if (category == null) return;
                if (isSp) {
                    final String cat = category;
                    dispatchSwap(client, server, cfg, switchSlot, cat, null);
                } else if (serverAvailKey) {
                    markSwitchSwapPending();
                    int[] mv = clientComputeSwap(client, cfg, switchSlot, category, null);
                    if (mv != null) {
                        lastSwapFrom = mv[0];
                        net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.send(
                                new com.example.inventoryorganizer.warehouse.SwitchTriggerPayload(
                                        com.example.inventoryorganizer.warehouse.SwitchTriggerPayload.TYPE_APPLY,
                                        mv[0], switchSlot, mv[1], 0));
                    }
                } else {
                    markSwitchSwapPending();
                    int best = findBestToolSlot(client, cfg, switchSlot, category, null);
                    if (best >= 0) { lastSwapFrom = best; sendVanillaSwap(client, best, switchSlot); }
                }
            } else if (hit instanceof BlockHitResult bh && hit.getType() == HitResult.Type.BLOCK) {
                net.minecraft.core.BlockPos pos = bh.getBlockPos();
                BlockState blockState = client.level.getBlockState(pos);
                String category = blockMineableCategory(blockState);
                if (category == null) return;
                if (isSp) {
                    final String cat = category;
                    final BlockState state = blockState;
                    dispatchSwap(client, server, cfg, switchSlot, cat, state);
                } else if (serverAvailKey) {
                    markSwitchSwapPending();
                    int[] mv = clientComputeSwap(client, cfg, switchSlot, category, blockState);
                    if (mv != null) {
                        lastSwapFrom = mv[0];
                        net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.send(
                                new com.example.inventoryorganizer.warehouse.SwitchTriggerPayload(
                                        com.example.inventoryorganizer.warehouse.SwitchTriggerPayload.TYPE_APPLY,
                                        mv[0], switchSlot, mv[1], 0));
                    }
                } else {
                    markSwitchSwapPending();
                    int best = findBestToolSlot(client, cfg, switchSlot, category, blockState);
                    if (best >= 0) { lastSwapFrom = best; sendVanillaSwap(client, best, switchSlot); }
                }
            } else {
                // Air: restore or swap to configured category
                String airMode = cfg.getSwitchAir();
                if ("restore".equals(airMode) && lastSwapFrom >= 0) {
                    if (isSp) {
                        scheduleRestore(client, server, switchSlot, lastSwapFrom);
                    } else if (serverAvailKey) {
                        markSwitchSwapPending();
                        int homeSlot = clientPredictRestore(client, switchSlot, lastSwapFrom);
                        net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.send(
                                new com.example.inventoryorganizer.warehouse.SwitchTriggerPayload(
                                        com.example.inventoryorganizer.warehouse.SwitchTriggerPayload.TYPE_AIR,
                                        switchSlot, lastSwapFrom, homeSlot, 0));
                    } else {
                        markSwitchSwapPending();
                        sendVanillaSwap(client, lastSwapFrom, switchSlot);
                    }
                    lastSwapFrom = -1;
                } else if (isCategoryAir(airMode)) {
                    if (isSp) {
                        final String cat = airMode;
                        dispatchSwap(client, server, cfg, switchSlot, cat, null);
                    } else if (serverAvailKey) {
                        markSwitchSwapPending();
                        int[] mv = clientComputeSwap(client, cfg, switchSlot, airMode, null);
                        if (mv != null) {
                            lastSwapFrom = mv[0];
                            net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.send(
                                    new com.example.inventoryorganizer.warehouse.SwitchTriggerPayload(
                                            com.example.inventoryorganizer.warehouse.SwitchTriggerPayload.TYPE_APPLY,
                                            mv[0], switchSlot, mv[1], 0));
                        }
                    } else {
                        markSwitchSwapPending();
                        int best = findBestToolSlot(client, cfg, switchSlot, airMode, null);
                        if (best >= 0) { lastSwapFrom = best; sendVanillaSwap(client, best, switchSlot); }
                    }
                }
            }
        } catch (Throwable ignored) {}
    }

    /**
     * Client-side computation + optimistic apply for the server case: performs EXACTLY the same swap the
     * SP path (selectAndSwap) does — including OI-placing the displaced item in its proper home slot via
     * {@link #findHome} — but on the LOCAL inventory, so the held item changes instantly. Returns the slot
     * moves {@code {sourceSlot, homeSlot}} so the caller can tell the server to apply the identical moves
     * (home = -1 when the displaced item fully merged / nothing to place). Returns null if nothing swapped.
     *
     * <p>This is why the displaced item now lands correctly on servers: the CLIENT (which has the player's
     * slot rules) decides the home slot; the server just applies it. Cosmetic prediction — if the server's
     * authoritative apply ever differs, its sync corrects the view (brief flicker at worst, no item loss).
     */
    private static int[] clientComputeSwap(Minecraft client, OrganizerConfig cfg, int switchSlot,
                                           String category, BlockState state) {
        try {
            Inventory inv = client.player.getInventory();
            String groupName = OrganizerConfig.switchGroupName(category);
            List<String> extra = cfg.getCustomGroup(groupName);
            TagKey<Item> tag = vanillaTag(category);
            // Silk touch priority: if this block is in the silk touch list and a silk touch
            // candidate exists, prefer it over the plain "fastest tool".
            boolean needsSilk = needsSilkTouch(state, cfg);
            boolean hasSilkCandidate = false;
            if (needsSilk) {
                for (int i = 0; i < 36; i++) {
                    ItemStack s = inv.getItem(i);
                    if (!s.isEmpty() && isCandidate(s, tag, extra) && hasSilkTouch(s)) {
                        hasSilkCandidate = true; break;
                    }
                }
            }
            int best = -1, bestMat = Integer.MAX_VALUE;
            float bestSpeed = -1f;
            for (int i = 0; i < 36; i++) {
                ItemStack s = inv.getItem(i);
                if (s.isEmpty() || !isCandidate(s, tag, extra)) continue;
                if (needsSilk && hasSilkCandidate && !hasSilkTouch(s)) continue; // skip non-silk tools
                int mat = materialIndex(s, cfg, groupName);
                float speed = state != null ? s.getDestroySpeed(state) : 0f;
                if (mat < bestMat || (mat == bestMat && speed > bestSpeed)) {
                    best = i; bestMat = mat; bestSpeed = speed;
                }
            }
            if (best < 0 || best == switchSlot) return null;
            ItemStack tool = inv.getItem(best).copy();
            ItemStack displaced = inv.getItem(switchSlot).copy();
            inv.setItem(switchSlot, tool);
            inv.setItem(best, ItemStack.EMPTY);
            int home = -1;
            if (!displaced.isEmpty()) {
                for (int s = 0; s < 36 && !displaced.isEmpty(); s++) {
                    if (s == switchSlot) continue;
                    ItemStack t = inv.getItem(s);
                    if (t.isEmpty() || !ItemStack.isSameItemSameComponents(t, displaced)) continue;
                    int room = t.getMaxStackSize() - t.getCount();
                    if (room <= 0) continue;
                    int mv = Math.min(room, displaced.getCount());
                    t.grow(mv); displaced.shrink(mv); inv.setItem(s, t);
                }
                if (!displaced.isEmpty()) {
                    home = findHome(cfg, inv, displaced, switchSlot, best); // OI the displaced to its place
                    inv.setItem(home, displaced);
                }
            }
            return new int[]{best, home};
        } catch (Throwable ignored) { return null; }
    }

    /**
     * Client-side optimistic restore: tool goes back to fromSlot, the displaced item that was sitting
     * there gets OI'd to its proper home via findHome (same logic as forward swap). Returns the home
     * slot chosen for the displaced item so the caller can tell the server to match it (-1 = merged
     * away / nothing to place).
     */
    private static int clientPredictRestore(Minecraft client, int switchSlot, int fromSlot) {
        try {
            if (fromSlot < 0 || fromSlot > 35 || fromSlot == switchSlot) return -1;
            OrganizerConfig cfg = OrganizerConfig.get();
            Inventory inv = client.player.getInventory();
            ItemStack tool = inv.getItem(switchSlot).copy();
            ItemStack displaced = inv.getItem(fromSlot).copy();
            inv.setItem(fromSlot, tool);
            inv.setItem(switchSlot, ItemStack.EMPTY);
            if (!displaced.isEmpty()) {
                for (int s = 0; s < 36 && !displaced.isEmpty(); s++) {
                    if (s == switchSlot || s == fromSlot) continue;
                    ItemStack t = inv.getItem(s);
                    if (t.isEmpty() || !ItemStack.isSameItemSameComponents(t, displaced)) continue;
                    int room = t.getMaxStackSize() - t.getCount();
                    if (room <= 0) continue;
                    int mv = Math.min(room, displaced.getCount());
                    t.grow(mv); displaced.shrink(mv); inv.setItem(s, t);
                }
                if (!displaced.isEmpty()) {
                    int home = findHome(cfg, inv, displaced, switchSlot, fromSlot);
                    inv.setItem(home, displaced);
                    return home;
                }
            }
        } catch (Throwable ignored) {}
        return -1;
    }

    private static int findBestToolSlot(Minecraft client, OrganizerConfig cfg, int switchSlot,
                                        String category, BlockState state) {
        try {
            Inventory inv = client.player.getInventory();
            String groupName = OrganizerConfig.switchGroupName(category);
            List<String> extra = cfg.getCustomGroup(groupName);
            TagKey<Item> tag = vanillaTag(category);
            boolean needsSilk = needsSilkTouch(state, cfg);
            boolean hasSilkCandidate = false;
            if (needsSilk) {
                for (int i = 0; i < 36; i++) {
                    ItemStack s = inv.getItem(i);
                    if (!s.isEmpty() && isCandidate(s, tag, extra) && hasSilkTouch(s)) {
                        hasSilkCandidate = true; break;
                    }
                }
            }
            int best = -1, bestMat = Integer.MAX_VALUE;
            float bestSpeed = -1f;
            for (int i = 0; i < 36; i++) {
                ItemStack s = inv.getItem(i);
                if (s.isEmpty() || !isCandidate(s, tag, extra)) continue;
                if (needsSilk && hasSilkCandidate && !hasSilkTouch(s)) continue;
                int mat = materialIndex(s, cfg, groupName);
                float speed = state != null ? s.getDestroySpeed(state) : 0f;
                if (mat < bestMat || (mat == bestMat && speed > bestSpeed)) {
                    best = i; bestMat = mat; bestSpeed = speed;
                }
            }
            if (best < 0 || best == switchSlot) return -1;
            return best;
        } catch (Throwable ignored) { return -1; }
    }

    private static void sendVanillaSwap(Minecraft client, int sourceSlot, int switchSlot) {
        if (client.gameMode == null || client.player == null) return;
        // InventoryMenu container slot mapping:
        //   inv slot 0-8  (hotbar)  → container slot 36-44
        //   inv slot 9-35 (main)    → container slot 9-35
        int containerSlot = sourceSlot < 9 ? 36 + sourceSlot : sourceSlot;
        client.gameMode.handleContainerInput(
                client.player.inventoryMenu.containerId,
                containerSlot,
                switchSlot,
                net.minecraft.world.inventory.ContainerInput.SWAP,
                client.player
        );
    }

    /** Marks pending + dispatches selectAndSwap on the server thread. Always called from client thread. */
    private static void dispatchSwap(Minecraft client, MinecraftServer server, OrganizerConfig cfg,
                                     int slot, String cat, BlockState state) {
        markSwitchSwapPending();
        server.execute(() -> selectAndSwap(client, server, cfg, slot, cat, state));
    }

    /** Server-thread: find the best candidate (group + material ranks) and swap it into the switch slot. */
    private static void selectAndSwap(Minecraft client, MinecraftServer server, OrganizerConfig cfg,
                                      int switchSlot, String category, BlockState state) {
        try {
            ServerPlayer sp = server.getPlayerList().getPlayer(client.player.getUUID());
            if (sp == null) return;
            Inventory inv = sp.getInventory();

            String groupName = OrganizerConfig.switchGroupName(category);
            List<String> extra = cfg.getCustomGroup(groupName);
            TagKey<Item> tag = vanillaTag(category);

            boolean needsSilk = needsSilkTouch(state, cfg);
            boolean hasSilkCandidate = false;
            if (needsSilk) {
                for (int i = 0; i < 36; i++) {
                    ItemStack s = inv.getItem(i);
                    if (!s.isEmpty() && isCandidate(s, tag, extra) && hasSilkTouch(s)) {
                        hasSilkCandidate = true; break;
                    }
                }
            }
            int best = -1, bestMat = Integer.MAX_VALUE;
            float bestSpeed = -1f;
            for (int i = 0; i < 36; i++) {
                ItemStack s = inv.getItem(i);
                if (s.isEmpty() || !isCandidate(s, tag, extra)) continue;
                if (needsSilk && hasSilkCandidate && !hasSilkTouch(s)) continue;
                int mat = materialIndex(s, cfg, groupName);
                float speed = state != null ? s.getDestroySpeed(state) : 0f;
                if (mat < bestMat || (mat == bestMat && speed > bestSpeed)) {
                    best = i; bestMat = mat; bestSpeed = speed;
                }
            }
            if (best < 0) { notifyMissing(client); return; }
            if (best == switchSlot) return;

            ItemStack tool = inv.getItem(best).copy();
            ItemStack displaced = inv.getItem(switchSlot).copy();
            inv.setItem(switchSlot, tool);
            inv.setItem(best, ItemStack.EMPTY);                 // vacate the tool's source slot
            if (!displaced.isEmpty()) {
                // "OI that one item": merge into a matching stack first, then place into its proper slot.
                for (int s = 0; s < 36 && !displaced.isEmpty(); s++) {
                    if (s == switchSlot) continue;
                    ItemStack t = inv.getItem(s);
                    if (t.isEmpty() || !ItemStack.isSameItemSameComponents(t, displaced)) continue;
                    int room = t.getMaxStackSize() - t.getCount();
                    if (room <= 0) continue;
                    int mv = Math.min(room, displaced.getCount());
                    t.grow(mv); displaced.shrink(mv); inv.setItem(s, t);
                }
                if (!displaced.isEmpty()) {
                    int home = findHome(cfg, inv, displaced, switchSlot, best);
                    inv.setItem(home, displaced);
                }
            }
            lastSwapFrom = best;
            inv.setChanged();
        } catch (Throwable ignored) {}
    }

    private static void scheduleRestore(Minecraft client, MinecraftServer server, int switchSlot, int fromSlot) {
        markSwitchSwapPending();
        server.execute(() -> {
            try {
                ServerPlayer sp = server.getPlayerList().getPlayer(client.player.getUUID());
                if (sp == null) return;
                OrganizerConfig cfg = OrganizerConfig.get();
                Inventory inv = sp.getInventory();
                ItemStack tool = inv.getItem(switchSlot).copy();
                ItemStack displaced = inv.getItem(fromSlot).copy();
                inv.setItem(fromSlot, tool);
                inv.setItem(switchSlot, ItemStack.EMPTY);
                if (!displaced.isEmpty()) {
                    for (int s = 0; s < 36 && !displaced.isEmpty(); s++) {
                        if (s == switchSlot || s == fromSlot) continue;
                        ItemStack t = inv.getItem(s);
                        if (t.isEmpty() || !ItemStack.isSameItemSameComponents(t, displaced)) continue;
                        int room = t.getMaxStackSize() - t.getCount();
                        if (room <= 0) continue;
                        int mv = Math.min(room, displaced.getCount());
                        t.grow(mv); displaced.shrink(mv); inv.setItem(s, t);
                    }
                    if (!displaced.isEmpty()) {
                        int home = findHome(cfg, inv, displaced, switchSlot, fromSlot);
                        inv.setItem(home, displaced);
                    }
                }
                inv.setChanged();
            } catch (Throwable ignored) {}
        });
    }

    /** Where the displaced item should go: a slot whose rule matches it (its assigned home), else any free
     *  ANY slot, else the just-vacated tool source (always free). Mirrors a "shift-move it to its place". */
    private static int findHome(OrganizerConfig cfg, Inventory inv, ItemStack item, int switchSlot, int vacated) {
        int anyFree = -1;
        for (int s = 0; s < 36; s++) {
            if (s == switchSlot) continue;
            if (!inv.getItem(s).isEmpty()) continue;          // only empty slots
            SlotRule rule = cfg.getInventoryRule(s, true);    // Auto-mode rules (we're in auto mode)
            if (ruleMatches(rule, item)) return s;            // matching assigned slot — preferred
            if (anyFree < 0 && rule.getType() == SlotRule.Type.ANY) anyFree = s;
        }
        return anyFree >= 0 ? anyFree : vacated;
    }

    private static boolean ruleMatches(SlotRule rule, ItemStack stack) {
        try {
            String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
            switch (rule.getType()) {
                case SPECIFIC_ITEM: return id.equals(rule.getValue());
                case CUSTOM_GROUP: {
                    // Config-aware lookup: custom groups (incl. Pickaxes/Swords/...) must check the actual member list.
                    List<String> members = OrganizerConfig.get().getCustomGroup(rule.getValue());
                    if (!members.isEmpty()) {
                        String path = id.contains(":") ? id.substring(id.indexOf(':') + 1) : id;
                        return members.contains(id) || members.contains(path);
                    }
                    return SortLogic.matchesGroup(stack, rule.getValue());
                }
                case GROUP: return SortLogic.matchesGroup(stack, rule.getValue());
                case SPECIFIC: return id.contains(rule.getValue().toLowerCase());
                default: return false;
            }
        } catch (Throwable ignored) { return false; }
    }

    /** Candidate = matches the category's vanilla tool tag OR is in its (modded-additions) group. */
    private static boolean isCandidate(ItemStack s, TagKey<Item> tag, List<String> extra) {
        if (tag != null && s.is(tag)) return true;
        if (extra != null && !extra.isEmpty()) {
            String id = BuiltInRegistries.ITEM.getKey(s.getItem()).toString();
            String path = id.contains(":") ? id.substring(id.indexOf(':') + 1) : id;
            return extra.contains(id) || extra.contains(path);
        }
        return false;
    }

    private static TagKey<Item> vanillaTag(String category) {
        switch (category) {
            case "pickaxe": return ItemTags.PICKAXES;
            case "axe":     return ItemTags.AXES;
            case "shovel":  return ItemTags.SHOVELS;
            case "hoe":     return ItemTags.HOES;
            case "mob":     return ItemTags.SWORDS;
            default:        return null;
        }
    }

    /** Which tool an entity is broken/fought with fastest (by entity-type id, version-robust). */
    private static String entityToolCategory(Entity e) {
        String type = "";
        try { type = BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).getPath(); } catch (Throwable ignored) {}
        if (type.contains("boat") || type.contains("raft")) return "axe";   // boats/chest boats/rafts → axe
        if (type.contains("minecart")) return "pickaxe";                    // minecarts → pickaxe
        if (e instanceof LivingEntity) return "mob";                        // mobs → weapon (sword group)
        return null;
    }

    private static String blockMineableCategory(BlockState state) {
        if (state.is(BlockTags.MINEABLE_WITH_PICKAXE)) return "pickaxe";
        if (state.is(BlockTags.MINEABLE_WITH_AXE)) return "axe";
        if (state.is(BlockTags.MINEABLE_WITH_SHOVEL)) return "shovel";
        if (state.is(BlockTags.MINEABLE_WITH_HOE)) return "hoe";
        return null;
    }

    /**
     * Rank of the item for best-pick selection. Lower = better.
     * Priority: (1) explicit cg rank order for this switch group, (2) global material order substring match.
     */
    private static int materialIndex(ItemStack s, OrganizerConfig cfg, String groupName) {
        String id = BuiltInRegistries.ITEM.getKey(s.getItem()).toString();
        // 1) CG rank order (user-defined order within the Pickaxes/Axes/.../Swords group)
        String[] cgOrder = cfg.getPreference("cg_order_" + groupName);
        if (cgOrder != null && cgOrder.length > 0) {
            for (int i = 0; i < cgOrder.length; i++) {
                if (id.equals(cgOrder[i])) return i;
            }
            // Not in cg_order but is a candidate — treat as after ranked items
            return cgOrder.length;
        }
        // 2) Global material order (netherite > diamond > iron > ...)
        String[] matOrder = cfg.getPreference("sort_material_order");
        if (matOrder != null && matOrder.length > 0) {
            for (int i = 0; i < matOrder.length; i++) {
                if (matOrder[i] != null && !matOrder[i].isEmpty() && id.contains(matOrder[i])) return i;
            }
            return matOrder.length;
        }
        return 0;
    }

    /** Returns true if the air mode is one of the 5 tool categories (not keep/restore). */
    private static boolean isCategoryAir(String airMode) {
        return "pickaxe".equals(airMode) || "axe".equals(airMode) || "shovel".equals(airMode)
                || "hoe".equals(airMode) || "mob".equals(airMode);
    }

    private static int airCategoryIndex(String airMode) {
        String[] cats = com.example.inventoryorganizer.warehouse.SwitchTriggerPayload.AIR_CATEGORIES;
        for (int i = 0; i < cats.length; i++) if (cats[i].equals(airMode)) return i;
        return -1;
    }

    /**
     * Evicts a wrong item from the switch slot by OI-ing it to its proper place.
     * Called from server thread when the switch slot holds an item that is NOT
     * a switch candidate (not pickaxe/axe/shovel/hoe/sword).
     */
    public static void evictWrongItemFromSwitchSlot(Minecraft client, MinecraftServer server, OrganizerConfig cfg, int switchSlot) {
        try {
            ServerPlayer sp = server.getPlayerList().getPlayer(client.player.getUUID());
            if (sp == null) return;
            Inventory inv = sp.getInventory();
            ItemStack inSlot = inv.getItem(switchSlot);
            if (inSlot.isEmpty()) return;

            // Check if the item is any kind of switch candidate (any category)
            for (String cat : OrganizerConfig.SWITCH_CATEGORIES) {
                String grp = OrganizerConfig.switchGroupName(cat);
                List<String> extra = cfg.getCustomGroup(grp);
                TagKey<Item> tag = vanillaTag(cat);
                if (isCandidate(inSlot, tag, extra)) return; // it's a valid tool — don't evict
            }

            // It's a wrong item — find it a home
            ItemStack toEvict = inSlot.copy();
            // Merge into matching stacks first
            for (int s = 0; s < 36 && !toEvict.isEmpty(); s++) {
                if (s == switchSlot) continue;
                ItemStack t = inv.getItem(s);
                if (t.isEmpty() || !ItemStack.isSameItemSameComponents(t, toEvict)) continue;
                int room = t.getMaxStackSize() - t.getCount();
                if (room <= 0) continue;
                int mv = Math.min(room, toEvict.getCount());
                t.grow(mv); toEvict.shrink(mv); inv.setItem(s, t);
            }
            if (!toEvict.isEmpty()) {
                int home = findHome(cfg, inv, toEvict, switchSlot, -1);
                if (home >= 0 && home != switchSlot) {
                    inv.setItem(home, toEvict);
                    inv.setItem(switchSlot, ItemStack.EMPTY);
                    inv.setChanged();
                }
            } else {
                inv.setItem(switchSlot, ItemStack.EMPTY);
                inv.setChanged();
            }
        } catch (Throwable ignored) {}
    }

    private static void notifyMissing(Minecraft client) {
        long now = System.currentTimeMillis();
        if (now - lastMissingMsgMs < 3000L) return;
        lastMissingMsgMs = now;
        client.execute(() -> {
            if (client.gui != null) client.gui.hud.setOverlayMessage(
                    Component.translatable("inventory-organizer.switch.missing_tool"), false);
        });
    }

    // Blocks that drop NOTHING as an item when broken without Silk Touch.
    // (Blocks that drop a different item, e.g. grass_block→dirt, stone→cobblestone, are NOT here.)
    private static final java.util.Set<String> SILK_TOUCH_BLOCKS = java.util.Set.of(
        // Ice family: create a water source but drop no item without Silk Touch
        "minecraft:ice", "minecraft:packed_ice", "minecraft:blue_ice",
        // Amethyst buds (NOT cluster — that drops shards): drop nothing without Silk Touch
        "minecraft:small_amethyst_bud", "minecraft:medium_amethyst_bud", "minecraft:large_amethyst_bud",
        // Sculk family: drop only XP, no item, without Silk Touch
        "minecraft:sculk", "minecraft:sculk_sensor", "minecraft:sculk_catalyst",
        "minecraft:sculk_shrieker", "minecraft:sculk_vein",
        // Bee Nest: drops nothing (bees escape, nest lost) without Silk Touch
        "minecraft:bee_nest",
        // Turtle Egg: breaks and drops nothing without Silk Touch
        "minecraft:turtle_egg"
    );

    /**
     * True when a Silk Touch tool should be preferred because this block drops NOTHING without it.
     * Glass variants are matched via ID substring ("glass"). User can add extra blocks via the
     * "switch_silk_touch_blocks" config preference (e.g. for modded blocks).
     */
    private static boolean needsSilkTouch(BlockState state, OrganizerConfig cfg) {
        if (state == null) return false;
        String blockId = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
        if (SILK_TOUCH_BLOCKS.contains(blockId)) return true;
        // All glass variants drop nothing without Silk Touch (glass, stained_glass, glass_pane, tinted_glass…)
        String path = blockId.contains(":") ? blockId.substring(blockId.indexOf(':') + 1) : blockId;
        if (path.contains("glass")) return true;
        // User-added extras (modded blocks or personal preference)
        String[] extra = cfg.getPreference("switch_silk_touch_blocks");
        if (extra != null) for (String b : extra) if (blockId.equals(b)) return true;
        return false;
    }

    /** True if the item has the Silk Touch enchantment (any level). */
    private static boolean hasSilkTouch(ItemStack stack) {
        if (stack.isEmpty()) return false;
        try {
            net.minecraft.world.item.enchantment.ItemEnchantments enc =
                net.minecraft.world.item.enchantment.EnchantmentHelper.getEnchantmentsForCrafting(stack);
            for (var entry : enc.entrySet()) {
                String id = entry.getKey().unwrapKey()
                        .map(k -> k.identifier().toString()).orElse("");
                String path = id.contains(":") ? id.substring(id.indexOf(':') + 1) : id;
                if ("silk_touch".equals(path) && entry.getIntValue() > 0) return true;
            }
        } catch (Throwable ignored) {}
        return false;
    }
}
