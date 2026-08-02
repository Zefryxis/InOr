package com.example.inventoryorganizer.config;

import java.util.ArrayList;
import java.util.List;

/**
 * A persistent "link" between chests: pressing OST at any member chest sorts the whole group as one
 * warehouse. Stored client-side in {@link OrganizerConfig}. Public fields for simple Gson handling.
 */
public class WarehouseGroup {

    public String name = "";
    public List<int[]> positions = new ArrayList<>(); // each = {x, y, z}
    // World/dimension key PARALLEL to `positions` (same index) — this whole config is ONE GLOBAL file
    // shared across every world/server the player has ever played on, so bare x,y,z is ambiguous once
    // links exist in more than one world. A missing/short entry (pre-existing configs) predates this
    // feature and is treated as a WILDCARD (matches in any world) rather than force-assigning it.
    public List<String> positionWorlds = new ArrayList<>();

    public List<int[]> getPositions() {
        if (positions == null) positions = new ArrayList<>();
        return positions;
    }

    public List<String> getPositionWorlds() {
        if (positionWorlds == null) positionWorlds = new ArrayList<>();
        return positionWorlds;
    }

    private static String currentWorldKey() {
        try {
            return com.example.inventoryorganizer.WorldScope.key(net.minecraft.client.Minecraft.getInstance().level);
        } catch (Throwable t) {
            return null;
        }
    }

    public boolean contains(int x, int y, int z) {
        String current = currentWorldKey();
        List<int[]> pos = getPositions();
        List<String> worlds = getPositionWorlds();
        for (int i = 0; i < pos.size(); i++) {
            int[] p = pos.get(i);
            if (p.length != 3 || p[0] != x || p[1] != y || p[2] != z) continue;
            String tag = (i < worlds.size() && worlds.get(i) != null && !worlds.get(i).isEmpty()) ? worlds.get(i) : null;
            if (tag == null || current == null || tag.equals(current)) return true;
        }
        return false;
    }

    public void add(int x, int y, int z) {
        if (contains(x, y, z)) return;
        getPositions().add(new int[]{x, y, z});
        String w = currentWorldKey();
        getPositionWorlds().add(w != null ? w : "");
    }

    public void remove(int x, int y, int z) {
        List<int[]> pos = getPositions();
        List<String> worlds = getPositionWorlds();
        for (int i = pos.size() - 1; i >= 0; i--) {
            int[] p = pos.get(i);
            if (p.length == 3 && p[0] == x && p[1] == y && p[2] == z) {
                pos.remove(i);
                if (i < worlds.size()) worlds.remove(i);
            }
        }
    }
}
