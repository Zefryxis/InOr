package com.example.inventoryorganizer.config;

import java.util.HashMap;
import java.util.Map;

public class Kit {
    private String name;
    private Map<String, SlotRule> slotRules;
    private Map<String, String[]> preferences;

    public Kit() {
        this.name = "Kit";
        this.slotRules = new HashMap<>();
        this.preferences = new HashMap<>();
    }

    public Kit(String name, Map<String, SlotRule> slotRules, Map<String, String[]> preferences) {
        this.name = name;
        // Deep copy slot rules
        this.slotRules = new HashMap<>();
        for (Map.Entry<String, SlotRule> entry : slotRules.entrySet()) {
            this.slotRules.put(entry.getKey(), entry.getValue().copy());
        }
        // Deep copy preferences
        this.preferences = new HashMap<>();
        for (Map.Entry<String, String[]> entry : preferences.entrySet()) {
            this.preferences.put(entry.getKey(), entry.getValue().clone());
        }
    }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public Map<String, SlotRule> getSlotRules() { return slotRules; }
    public Map<String, String[]> getPreferences() { return preferences; }
}
