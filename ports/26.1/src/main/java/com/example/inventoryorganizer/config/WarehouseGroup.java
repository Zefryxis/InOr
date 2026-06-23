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

    public List<int[]> getPositions() {
        if (positions == null) positions = new ArrayList<>();
        return positions;
    }

    public boolean contains(int x, int y, int z) {
        for (int[] p : getPositions()) {
            if (p.length == 3 && p[0] == x && p[1] == y && p[2] == z) return true;
        }
        return false;
    }

    public void add(int x, int y, int z) {
        if (!contains(x, y, z)) getPositions().add(new int[]{x, y, z});
    }

    public void remove(int x, int y, int z) {
        getPositions().removeIf(p -> p.length == 3 && p[0] == x && p[1] == y && p[2] == z);
    }
}
