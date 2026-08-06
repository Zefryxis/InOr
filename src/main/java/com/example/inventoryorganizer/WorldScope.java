package com.example.inventoryorganizer;

import net.minecraft.client.Minecraft;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;

/**
 * A stable client-side identity for "which world/server am I currently in", used to scope
 * position-based chest data (known chests, per-chest profiles, links) so they don't bleed between
 * unrelated worlds. Combines two things:
 *
 * <ul>
 *   <li><b>World/server identity</b> — for singleplayer, the save folder's absolute path (unique per
 *       save); for multiplayer, the server address the player connected with. Without this, the mod's
 *       config (a single global JSON file, {@code config/inventory-organizer.json}, shared across every
 *       world and server the player has EVER played on) had no way to tell two different worlds apart
 *       at all — a chest at the same coordinates in two different SP saves or two different servers
 *       would be treated as literally the same chest.</li>
 *   <li><b>Dimension</b> — Overworld/Nether/End (or any modded dimension) within that same world/server,
 *       since the same coordinates can also collide across dimensions.</li>
 * </ul>
 *
 * <p>Existing (pre-this-feature) saved data has no such key at all — see the "legacy = wildcard,
 * matches any world" handling in {@link com.example.inventoryorganizer.config.OrganizerConfig} and
 * {@link com.example.inventoryorganizer.config.StoragePreset} rather than a forced, potentially wrong
 * one-time reassignment.
 */
public final class WorldScope {
    private WorldScope() {}

    // TEMPORARY diagnostic: logs whenever the computed key actually CHANGES, so a reported "chests
    // mix between dimensions" case can be traced from the client log without guessing further.
    private static String lastLoggedKey = null;
    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("inventory-organizer/WorldScope");

    /** Combined world/server + dimension key for {@code level}, or {@code null} if it can't be
     *  determined (defensive — callers should treat null the same as an unscoped/legacy entry). */
    public static String key(Level level) {
        String result;
        try {
            String world = worldIdentity();
            result = (world == null || level == null) ? null : world + "|" + level.dimension().identifier();
        } catch (Throwable t) {
            result = null;
        }
        if (!java.util.Objects.equals(result, lastLoggedKey)) {
            lastLoggedKey = result;
            LOGGER.info("[WorldScope] key changed -> {}", result);
        }
        return result;
    }

    /** Just the world/server part (no dimension) — used where only cross-world collisions matter. */
    private static String worldIdentity() {
        Minecraft mc = Minecraft.getInstance();
        try {
            if (mc.hasSingleplayerServer() && mc.getSingleplayerServer() != null) {
                return "sp:" + mc.getSingleplayerServer()
                        .getWorldPath(LevelResource.ROOT)
                        .toAbsolutePath().normalize();
            }
            if (mc.getCurrentServer() != null && mc.getCurrentServer().ip != null) {
                return "mp:" + mc.getCurrentServer().ip;
            }
        } catch (Throwable ignored) {}
        return null;
    }
}
