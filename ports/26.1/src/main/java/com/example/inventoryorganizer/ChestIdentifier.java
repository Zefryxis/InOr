package com.example.inventoryorganizer;

import com.example.inventoryorganizer.config.OrganizerConfig;
import com.example.inventoryorganizer.config.StoragePreset;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.PlainTextContents;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.phys.BlockHitResult;

import java.util.ArrayList;
import java.util.List;

/**
 * Client-side helper that maps the chest currently being opened to a per-chest sorting profile.
 *
 * <p>"Carrying mode": every per-chest profile is persistent and considered <i>carried</i> by the
 * mod. A profile becomes <b>active</b> only while its chest is open (resolved here on open) and
 * returns to carrying mode on close ({@link #clearActive()}). When no profile matches the opened
 * chest, the active profile is none ({@code -1}) and OST falls back to the size-based default.
 *
 * <p>Identity is resolved in priority order: (1) anvil custom name (survives relocation),
 * (2) exact coordinates (both halves of a double chest are stored/checked), (3) adjacent-sign text.
 * See {@link OrganizerConfig#findProfileFor}.
 */
public final class ChestIdentifier {

    private ChestIdentifier() {}

    /** Id of the profile active for the currently open chest, or -1 for none (fallback to default). */
    private static int activeProfileId = -1;

    /** Context of the currently open chest, captured on open so the "New profile? → Yes" flow can bind it. */
    private static String currentCustomName = null;
    private static String currentSignText = null;
    private static List<int[]> currentPositions = new ArrayList<>();
    private static int currentSize = 27;
    private static boolean currentHasContext = false;

    /** True once the user clicked "Nothing" for the current chest, so we stop prompting until reopen. */
    private static boolean promptDismissed = false;

    // ===== Lifecycle =====

    /**
     * Resolve the profile for a chest that just opened. Stores chest context for later binding and
     * sets the active profile (or -1 if none matches).
     *
     * @param title    the container screen title ({@code screen.getTitle()})
     * @param hit      the block hit result captured when the screen opened (may be null/stale)
     * @param rowCount container rows (3 = 27 slots, 6 = 54 slots)
     * @return the matched profile, or null when the chest is unbound (caller shows the New-profile prompt)
     */
    /**
     * Variant of {@link #onChestOpen} for GENERIC (modded) containers whose storage size is an explicit
     * slot count that need not be a multiple of 9 (e.g. a 6×4 = 24-slot modded chest). Sets the chest
     * context + records it as known, and matches an existing profile by name/position so OST and the
     * profile picker work the same as for vanilla chests.
     */
    public static StoragePreset onChestOpenSized(Component title, BlockHitResult hit, int size) {
        if (hit == null && currentHasContext && !currentPositions.isEmpty()) {
            return localActiveProfile();
        }
        promptDismissed = false;
        currentSize = Math.max(1, size);
        currentCustomName = extractCustomName(title);
        currentPositions = computePositions(hit);
        currentSignText = computeSignText(currentPositions);
        currentHasContext = true;
        boolean learned = false;
        for (int[] cp : currentPositions) {
            if (cp.length == 3 && OrganizerConfig.get().addKnownChest(cp[0], cp[1], cp[2])) learned = true;
        }
        if (learned) OrganizerConfig.get().save();
        StoragePreset match = OrganizerConfig.get().findProfileFor(currentCustomName, currentPositions, currentSignText);
        activeProfileId = (match != null) ? match.getId() : -1;
        return match;
    }

    public static StoragePreset onChestOpen(Component title, BlockHitResult hit, int rowCount) {
        // Resize-safety: when the window is resized the screen re-inits and this runs again, but the
        // block hit result may be gone. Re-deriving from a null hit would WIPE the chest's binding and
        // break the label/buttons. If we already have context (same chest still open), keep it.
        if (hit == null && currentHasContext && !currentPositions.isEmpty()) {
            return localActiveProfile();
        }
        promptDismissed = false;
        currentSize = Math.max(1, rowCount) * 9;
        currentCustomName = extractCustomName(title);
        currentPositions = computePositions(hit);
        currentSignText = computeSignText(currentPositions);
        currentHasContext = true;

        // Remember that the player has actually opened this chest, so the warehouse map can show
        // only chests the player knows about (and never ones marked "Nothing").
        boolean learned = false;
        for (int[] cp : currentPositions) {
            if (cp.length == 3 && OrganizerConfig.get().addKnownChest(cp[0], cp[1], cp[2])) learned = true;
        }
        if (learned) OrganizerConfig.get().save();

        StoragePreset match = OrganizerConfig.get().findProfileFor(currentCustomName, currentPositions, currentSignText);
        if (match != null) {
            // Keep the profile's grid in sync with the physical chest size (e.g. single↔double
            // conversion). setSize only adds missing "any" slots — it never deletes saved rules.
            if ((currentSize == 27 || currentSize == 54) && match.getSize() != currentSize) {
                match.setSize(currentSize);
                OrganizerConfig.get().save();
            }
            // If matched by custom name, the chest may have been relocated — re-point its
            // coordinates to the current spot (name is the authoritative key, so we replace
            // rather than accumulate stale positions). Position-only matches are already current.
            if (currentCustomName != null && !currentPositions.isEmpty()) {
                match.setPositions(new ArrayList<>(currentPositions));
                OrganizerConfig.get().save();
            }
            // An adjacent sign is the source of truth for both the match key AND the display name —
            // keep the profile's sign text and its name in sync with the current sign.
            if (currentSignText != null && !currentSignText.isEmpty()) {
                boolean changed = false;
                if (!currentSignText.equals(match.getSignText())) { match.setSignText(currentSignText); changed = true; }
                if (!currentSignText.equals(match.getName()))     { match.setName(currentSignText);     changed = true; }
                if (changed) OrganizerConfig.get().save();
            }
        }
        activeProfileId = (match != null) ? match.getId() : -1;
        // On a server, refresh which links are visible/revealed so the UI knows if this chest belongs
        // to another player's link (then it's sort-only — no profiling).
        if (com.example.inventoryorganizer.warehouse.WarehouseClient.isAvailable()) {
            com.example.inventoryorganizer.warehouse.WarehouseClient.requestLinkMap();
        }
        return match;
    }

    /** Reset active + context state. Call when the container screen closes. */
    public static void clearActive() {
        activeProfileId = -1;
        currentCustomName = null;
        currentSignText = null;
        currentPositions = new ArrayList<>();
        currentHasContext = false;
        promptDismissed = false;
    }

    // ===== Active profile queries =====

    /** The active per-chest profile, or null when the chest is unbound. Profiles are purely local. */
    public static StoragePreset getActiveProfile() {
        return localActiveProfile();
    }

    private static StoragePreset localActiveProfile() {
        if (activeProfileId < 0) return null;
        return OrganizerConfig.get().getProfileById(activeProfileId);
    }

    // ===== Foreign-link state (server-shared links) =====

    /**
     * True when the currently open chest belongs to ANOTHER player's warehouse link (revealed to us).
     * Such a chest is sort-only: we may OST the whole link but never create/edit a profile on it.
     */
    public static boolean isCurrentChestForeignLink() {
        for (int[] cp : currentPositions) {
            if (cp.length == 3 && com.example.inventoryorganizer.warehouse.WarehouseClient
                    .isForeignLinkChest(new BlockPos(cp[0], cp[1], cp[2]))) return true;
        }
        return false;
    }

    /** Owner display name of the foreign link the open chest belongs to (for the label). */
    public static String getForeignLinkOwner() {
        for (int[] cp : currentPositions) {
            if (cp.length != 3) continue;
            String n = com.example.inventoryorganizer.warehouse.WarehouseClient
                    .foreignLinkOwner(new BlockPos(cp[0], cp[1], cp[2]));
            if (n != null && !n.isEmpty()) return n;
        }
        return "";
    }

    /** True when you may NOT profile/bind the open chest (it's a foreign link member → sort-only). */
    public static boolean isCurrentChestReadOnly() {
        return isCurrentChestForeignLink();
    }

    /** Stable chest key regardless of which half of a double chest was opened (lexicographic min). */
    public static BlockPos canonicalPos() {
        BlockPos best = null;
        for (int[] cp : currentPositions) {
            if (cp.length != 3) continue;
            BlockPos p = new BlockPos(cp[0], cp[1], cp[2]);
            if (best == null
                    || p.getX() < best.getX()
                    || (p.getX() == best.getX() && p.getY() < best.getY())
                    || (p.getX() == best.getX() && p.getY() == best.getY() && p.getZ() < best.getZ())) {
                best = p;
            }
        }
        return best;
    }

    public static boolean isPromptDismissed() { return promptDismissed; }

    /**
     * The user chose "Nothing" for the currently open chest: remember it as a non-storage chest so
     * it's hidden from the warehouse map (and detached from any link). Also dismisses the prompt.
     */
    public static void markCurrentChestAsNothing() {
        OrganizerConfig cfg = OrganizerConfig.get();
        for (int[] cp : currentPositions) {
            if (cp.length == 3) cfg.markNothingChest(cp[0], cp[1], cp[2]);
        }
        cfg.save();
        promptDismissed = true;
    }

    public static boolean hasContext() { return currentHasContext; }
    public static int getCurrentSize() { return currentSize; }
    public static String getCurrentCustomName() { return currentCustomName; }
    public static String getCurrentSignText() { return currentSignText; }

    /** A snapshot copy of the current chest's position(s) — safe to keep after the chest closes. */
    public static List<int[]> getCurrentPositions() { return new ArrayList<>(currentPositions); }

    // ===== New-profile creation (the "Yes" flow) =====

    /**
     * Create a fresh per-chest profile bound to the currently open chest (by custom name and/or
     * coordinates), make it active, and persist. Returns the new profile.
     */
    public static StoragePreset createAndBindProfile() {
        if (isCurrentChestReadOnly()) return null; // can't profile a chest whose owner's profile is visible
        OrganizerConfig cfg = OrganizerConfig.get();
        int ordinal = countBoundProfiles(cfg) + 1;
        // If the chest has an adjacent sign, name the profile after it; otherwise "N. chest".
        String name = (currentSignText != null && !currentSignText.isEmpty())
                ? currentSignText : defaultProfileName(ordinal);
        StoragePreset p = cfg.addProfile(name, currentSize);
        bindCurrentChestTo(p);
        cfg.save();
        activeProfileId = p.getId();
        promptDismissed = true;
        return p;
    }

    /**
     * Re-point the current chest's identity onto an existing (carried) profile — used by picker mode.
     * The profile keeps its layout but adopts this chest's name/coordinates and size, and becomes active.
     */
    public static void bindCurrentChestTo(StoragePreset p) {
        if (p == null || !currentHasContext) return;
        if (isCurrentChestReadOnly()) return; // can't bind a chest whose owner's profile is visible
        if (currentCustomName != null) p.setCustomName(currentCustomName);
        if (currentSignText != null) p.setSignText(currentSignText);
        for (int[] pos : currentPositions) p.addPosition(pos[0], pos[1], pos[2]);
        if (currentSize == 27 || currentSize == 54) p.setSize(currentSize);
        OrganizerConfig.get().save();
        activeProfileId = p.getId();
        promptDismissed = true;
        // Profiles are purely local now — nothing to publish. (Links are shared separately.)
    }

    private static int countBoundProfiles(OrganizerConfig cfg) {
        int n = 0;
        for (StoragePreset p : cfg.getStoragePresets()) {
            if (!p.isDefault()) n++;
        }
        return n;
    }

    public static String defaultProfileName(int ordinal) {
        try {
            return Component.translatable("inventory-organizer.profile.default_name", ordinal).getString();
        } catch (Throwable t) {
            return ordinal + ". chest";
        }
    }

    // ===== Identity extraction =====

    /**
     * Extract the chest's anvil custom name, or null for a vanilla (un-renamed) chest.
     * A custom name is plain literal text ({@link PlainTextContents.LiteralContents}); a vanilla
     * default is a {@code TranslatableContents} (e.g. {@code container.chest}).
     */
    public static String extractCustomName(Component title) {
        if (title == null) return null;
        try {
            if (title.getContents() instanceof PlainTextContents.LiteralContents) {
                String s = title.getString();
                return (s != null && !s.isEmpty()) ? s : null;
            }
        } catch (Throwable ignored) {}
        return null;
    }

    /**
     * Compute the chest's block position(s). For a double chest, both halves are returned so the
     * binding is precise; matching itself also tolerates ±1 block to cover single↔double conversion.
     */
    private static List<int[]> computePositions(BlockHitResult hit) {
        List<int[]> out = new ArrayList<>();
        if (hit == null) return out;
        BlockPos pos = hit.getBlockPos();
        if (pos == null) return out;
        out.add(new int[]{pos.getX(), pos.getY(), pos.getZ()});
        Level level = Minecraft.getInstance().level;
        if (level != null) {
            BlockPos partner = doubleChestPartner(level, pos);
            if (partner != null) out.add(new int[]{partner.getX(), partner.getY(), partner.getZ()});
        }
        return out;
    }

    /** The other half of a double chest at {@code pos}, or null when it's single / not a chest. */
    public static BlockPos doubleChestPartner(Level level, BlockPos pos) {
        try {
            BlockState st = level.getBlockState(pos);
            if (!(st.getBlock() instanceof ChestBlock) || !st.hasProperty(ChestBlock.TYPE)
                    || st.getValue(ChestBlock.TYPE) == ChestType.SINGLE) {
                return null;
            }
            // Primary: vanilla connected direction, validated to actually point at a chest.
            BlockPos partner = pos.relative(ChestBlock.getConnectedDirection(st));
            if (level.getBlockState(partner).getBlock() instanceof ChestBlock) return partner;
            // Fallback: a horizontally-adjacent double-type chest (resilient if the above misfires).
            for (Direction d : new Direction[]{Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST}) {
                BlockPos n = pos.relative(d);
                BlockState ns = level.getBlockState(n);
                if (ns.getBlock() instanceof ChestBlock && ns.hasProperty(ChestBlock.TYPE)
                        && ns.getValue(ChestBlock.TYPE) != ChestType.SINGLE) {
                    return n;
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    /**
     * Read the text of a sign placed against the chest, used as an identity key. Scans every
     * face-neighbour of BOTH halves of a double chest — the partner half is re-derived here from
     * the world, so a sign on either half is found regardless of which half was opened.
     * Returns null when there is no adjacent sign with text.
     */
    private static String computeSignText(List<int[]> positions) {
        try {
            Minecraft mc = Minecraft.getInstance();
            Level level = mc.level;
            if (level == null || positions == null || positions.isEmpty()) return null;

            // Full chest footprint: the given position(s) + the partner half of any double chest.
            java.util.LinkedHashSet<BlockPos> footprint = new java.util.LinkedHashSet<>();
            for (int[] cp : positions) {
                if (cp.length != 3) continue;
                BlockPos p = new BlockPos(cp[0], cp[1], cp[2]);
                footprint.add(p);
                BlockPos partner = doubleChestPartner(level, p);
                if (partner != null) footprint.add(partner);
            }

            // Scan each footprint block's 6 face-neighbours for a sign (skip the chest blocks).
            for (BlockPos cb : footprint) {
                for (Direction d : Direction.values()) {
                    BlockPos np = cb.relative(d);
                    if (footprint.contains(np)) continue;
                    BlockEntity be = level.getBlockEntity(np);
                    if (be instanceof SignBlockEntity sign) {
                        String t = readSign(sign);
                        if (t != null && !t.isEmpty()) return t;
                    }
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static String readSign(SignBlockEntity sign) {
        String front = joinSignText(sign.getFrontText());
        if (front != null && !front.isEmpty()) return front;
        return joinSignText(sign.getBackText());
    }

    /** Join the non-empty lines of a sign face into a single trimmed identity string. */
    private static String joinSignText(SignText text) {
        if (text == null) return null;
        StringBuilder sb = new StringBuilder();
        try {
            for (Component line : text.getMessages(false)) {
                if (line == null) continue;
                String s = line.getString().trim();
                if (!s.isEmpty()) {
                    if (sb.length() > 0) sb.append(' ');
                    sb.append(s);
                }
            }
        } catch (Throwable ignored) {}
        return sb.length() > 0 ? sb.toString() : null;
    }
}
