package com.example.inventoryorganizer.config;

import java.util.HashMap;
import java.util.Map;

public class StoragePreset {
    private String name;
    private int size; // 27 = chest, 54 = large chest
    private Map<String, String> slotRules = new HashMap<>();
    private Map<String, Integer> tierAssignments = new HashMap<>();

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
}
