package com.example.inventoryorganizer.config;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A storage layout (slot rules + tier assignments) that can be applied to a container.
 *
 * <p>Two kinds exist:
 * <ul>
 *   <li><b>Defaults</b> ({@code isDefault == true}): the built-in "Container", "Large Chest" and
 *       "Bundle" layouts. They are protected (cannot be renamed or deleted) and act as the
 *       size-based fallback when an opened chest has no per-chest profile bound to it.</li>
 *   <li><b>Per-chest profiles</b> ({@code isDefault == false}): user-created layouts bound to a
 *       physical chest, matched by {@link #customName} (anvil name) or {@link #positions}
 *       (coordinates). A profile is "carried" by the mod until its chest is opened.</li>
 * </ul>
 *
 * <p>{@link #id} is stable for the lifetime of the profile and is what tier assignments are keyed
 * by ({@code tier_order_storage_<id>} in {@link OrganizerConfig}). The three defaults keep ids
 * 0/1/2 so existing saved tier data stays valid.
 */
public class StoragePreset {
    private String name;
    private int size; // 27 = chest, 54 = large chest
    private Map<String, String> slotRules = new HashMap<>();
    private Map<String, Integer> tierAssignments = new HashMap<>();

    // ===== Per-chest profile fields (defaults leave these at their neutral values) =====
    private int id = -1;                  // stable identity; -1 until assigned
    private boolean isDefault = false;    // protected built-in (Container/Large Chest/Bundle)
    private String customName = null;     // anvil custom name this profile is bound to (match key)
    private String signText = null;       // text of an adjacent sign this profile is bound to (match key)
    private List<int[]> positions = null; // chest BlockPos(es) this profile is bound to ([x,y,z])

    public StoragePreset() {
        this.name = "Preset";
        this.size = 27;
    }

    public StoragePreset(String name, int size) {
        this.name = name;
        this.size = size;
        for (int i = 0; i < size; i++) {
            slotRules.put(String.valueOf(i), "any");
        }
    }

    public String getName() { return name != null ? name : "Preset"; }
    public void setName(String name) { this.name = name; }

    public int getSize() { return size > 0 ? size : 27; }
    public void setSize(int size) {
        this.size = size;
        if (slotRules == null) slotRules = new HashMap<>();
        for (int i = 0; i < size; i++) {
            slotRules.putIfAbsent(String.valueOf(i), "any");
        }
    }

    public Map<String, String> getSlotRulesMap() {
        if (slotRules == null) slotRules = new HashMap<>();
        return slotRules;
    }

    public Map<String, Integer> getTierAssignmentsMap() {
        if (tierAssignments == null) tierAssignments = new HashMap<>();
        return tierAssignments;
    }

    public String getSlotRule(int slot) {
        if (slotRules == null) return "any";
        return slotRules.getOrDefault(String.valueOf(slot), "any");
    }

    public void setSlotRule(int slot, String rule) {
        if (slotRules == null) slotRules = new HashMap<>();
        slotRules.put(String.valueOf(slot), rule);
    }

    public Integer getTier(int slot) {
        if (tierAssignments == null) return null;
        return tierAssignments.get(String.valueOf(slot));
    }

    public void setTier(int slot, int tier) {
        if (tierAssignments == null) tierAssignments = new HashMap<>();
        tierAssignments.put(String.valueOf(slot), tier);
    }

    public void removeTier(int slot) {
        if (tierAssignments != null) tierAssignments.remove(String.valueOf(slot));
    }

    // ===== Per-chest profile accessors =====

    public int getId() { return id; }
    public void setId(int id) { this.id = id; }

    public boolean isDefault() { return isDefault; }
    public void setDefault(boolean isDefault) { this.isDefault = isDefault; }

    public String getCustomName() { return customName; }
    public void setCustomName(String customName) {
        this.customName = (customName != null && !customName.isEmpty()) ? customName : null;
    }

    public String getSignText() { return signText; }
    public void setSignText(String signText) {
        this.signText = (signText != null && !signText.isEmpty()) ? signText : null;
    }

    /** Match by adjacent-sign text (case-insensitive, trimmed). */
    public boolean matchesSign(String sign) {
        return signText != null && sign != null && signText.equalsIgnoreCase(sign.trim());
    }

    public List<int[]> getPositions() {
        if (positions == null) positions = new ArrayList<>();
        return positions;
    }

    public void setPositions(List<int[]> positions) { this.positions = positions; }

    public void clearPositions() {
        if (positions != null) positions.clear();
    }

    /** Bind this profile to a block position (deduplicated). Used when first attaching to a chest. */
    public void addPosition(int x, int y, int z) {
        List<int[]> list = getPositions();
        for (int[] p : list) {
            if (p.length == 3 && p[0] == x && p[1] == y && p[2] == z) return;
        }
        list.add(new int[]{x, y, z});
    }

    /** True when this profile is bound to a chest by name, sign or coordinates (i.e. not a bare default). */
    public boolean isBound() {
        return (customName != null && !customName.isEmpty())
                || (signText != null && !signText.isEmpty())
                || (positions != null && !positions.isEmpty());
    }

    /** Match by anvil custom name (case-sensitive, exact) — survives relocation. */
    public boolean matchesName(String chestCustomName) {
        return customName != null && !customName.isEmpty() && customName.equals(chestCustomName);
    }

    /** Match by coordinates within {@code tolerance} blocks on each axis (covers single↔double conversion). */
    public boolean matchesPosition(int x, int y, int z, int tolerance) {
        if (positions == null) return false;
        for (int[] p : positions) {
            if (p.length != 3) continue;
            if (Math.abs(p[0] - x) <= tolerance
                    && Math.abs(p[1] - y) <= tolerance
                    && Math.abs(p[2] - z) <= tolerance) {
                return true;
            }
        }
        return false;
    }

    /** Deep copy used for duplication. The copy is never a default and starts unbound. */
    public StoragePreset copy() {
        StoragePreset c = new StoragePreset();
        c.name = this.name;
        c.size = this.size;
        c.slotRules = new HashMap<>(getSlotRulesMap());
        c.tierAssignments = new HashMap<>(getTierAssignmentsMap());
        c.isDefault = false;
        c.customName = null;     // a duplicate is not yet attached to any chest
        c.signText = null;
        c.positions = new ArrayList<>();
        // id is assigned by OrganizerConfig when the copy is added
        return c;
    }
}
