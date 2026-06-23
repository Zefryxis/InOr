package com.example.inventoryorganizer;

import com.example.inventoryorganizer.config.OrganizerConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;

import java.net.InetSocketAddress;
import java.net.SocketAddress;

/**
 * Detects whether the current play environment is "private" (single player, LAN, Realms,
 * a known friendly host like Aternos, or a user-whitelisted host). Used by the master
 * Server-Friendly / Free switch: Free behaviour is only allowed in a private environment.
 *
 * Strict default-deny: anything we can't positively recognize as private is treated as a
 * public server, so the mod stays Server-Friendly there.
 */
public final class ServerEnvironment {

    /** Host substrings recognized as friendly hosting platforms (case-insensitive). */
    private static final String[] KNOWN_PRIVATE_HOST_SUBSTRINGS = { "aternos" };

    private ServerEnvironment() {}

    public static boolean isPrivateEnvironment() {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) return false;

        // Single player (also covers hosting a LAN world yourself).
        if (mc.hasSingleplayerServer()) return true;

        ServerData sd = mc.getCurrentServer();
        if (sd != null) {
            try { if (sd.isLan()) return true; } catch (Throwable ignored) {}
            try { if (sd.isRealm()) return true; } catch (Throwable ignored) {}
        }

        String host = serverHost(mc);
        if (host != null && !host.isEmpty()) {
            String lower = host.toLowerCase();
            for (String s : KNOWN_PRIVATE_HOST_SUBSTRINGS) {
                if (lower.contains(s)) return true;
            }
            for (String w : OrganizerConfig.get().getServerWhitelist()) {
                if (w != null && !w.isEmpty() && lower.contains(w.toLowerCase())) return true;
            }
        }
        return false;
    }

    /** Best-effort current server host/IP string, or null if unknown / single player. */
    public static String serverHost(Minecraft mc) {
        try {
            ServerData sd = mc.getCurrentServer();
            if (sd != null && sd.ip != null && !sd.ip.isEmpty()) return sd.ip;
        } catch (Throwable ignored) {}
        try {
            net.minecraft.client.multiplayer.ClientPacketListener conn = mc.getConnection();
            if (conn != null && conn.getConnection() != null) {
                SocketAddress addr = conn.getConnection().getRemoteAddress();
                if (addr instanceof InetSocketAddress isa) return isa.getHostString();
                if (addr != null) return addr.toString();
            }
        } catch (Throwable ignored) {}
        return null;
    }

    /**
     * Whether Free-style behaviour is permitted right now: a private environment AND not in
     * the fight-mode window (fight mode forces Server-Friendly for its 20-second duration).
     * The individual feature toggles (keybind mode = free, death auto-sort) are still gated
     * by this, so "free" only takes effect where it's allowed.
     */
    public static boolean canUseFree() {
        if (FightModeTracker.isActive()) return false;
        return isPrivateEnvironment();
    }
}
