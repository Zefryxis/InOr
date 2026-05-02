package com.example.inventoryorganizer.config;

import me.shedaniel.clothconfig2.api.ConfigBuilder;
import me.shedaniel.clothconfig2.api.ConfigCategory;
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.util.LinkedHashMap;
import java.util.Map;

public class ConfigScreenBuilder {

    // Cache: English name -> item ID mapping (built once)
    private static Map<String, String> nameToIdMap = null;
    private static Map<String, String> idToNameMap = null;

    private static void buildItemMaps() {
        if (nameToIdMap != null && !nameToIdMap.isEmpty()) return;
        nameToIdMap = new LinkedHashMap<>();
        idToNameMap = new LinkedHashMap<>();

        try {
            for (Identifier id : Registries.ITEM.getIds()) {
                String itemId = id.toString();
                String englishName = formatIdAsName(id.getPath());
                nameToIdMap.put(englishName, itemId);
                idToNameMap.put(itemId, englishName);
            }
        } catch (Exception ignored) {}
    }

    private static String formatIdAsName(String path) {
        String[] parts = path.split("_");
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (!part.isEmpty()) {
                if (sb.length() > 0) sb.append(" ");
                sb.append(Character.toUpperCase(part.charAt(0)));
                if (part.length() > 1) sb.append(part.substring(1));
            }
        }
        return sb.toString();
    }

    public static Screen build(Screen parent) {
        ConfigBuilder builder = ConfigBuilder.create()
                .setParentScreen(parent)
                .setTitle(Text.literal("Item Names Reference"));

        ConfigEntryBuilder entryBuilder = builder.entryBuilder();

        // === Item Names (reference list) ===
        ConfigCategory namesCategory = builder.getOrCreateCategory(Text.literal("Item Names"));

        buildItemMaps();

        namesCategory.addEntry(entryBuilder.startTextDescription(
                Text.literal("Total items: " + nameToIdMap.size() + "\nItems sorted A-Z. Click a letter group to expand it.")
        ).build());

        // Sort items alphabetically and group by first letter
        java.util.TreeMap<Character, java.util.List<Map.Entry<String, String>>> byLetter = new java.util.TreeMap<>();
        for (Map.Entry<String, String> entry : nameToIdMap.entrySet()) {
            char letter = Character.toUpperCase(entry.getKey().charAt(0));
            byLetter.computeIfAbsent(letter, k -> new java.util.ArrayList<>()).add(entry);
        }
        for (java.util.List<Map.Entry<String, String>> list : byLetter.values()) {
            list.sort(java.util.Comparator.comparing(e -> e.getKey().toLowerCase()));
        }

        for (Map.Entry<Character, java.util.List<Map.Entry<String, String>>> letterGroup : byLetter.entrySet()) {
            char letter = letterGroup.getKey();
            java.util.List<Map.Entry<String, String>> items = letterGroup.getValue();
            me.shedaniel.clothconfig2.impl.builders.SubCategoryBuilder sub =
                    entryBuilder.startSubCategory(Text.literal("\u00A7b\u00A7l" + letter + "\u00A7r (" + items.size() + " items)"));
            sub.setExpanded(false);
            for (Map.Entry<String, String> item : items) {
                sub.add(entryBuilder.startTextDescription(
                        Text.literal("\u00A7e" + item.getKey() + "\u00A7r = \u00A77" + item.getValue())
                ).build());
            }
            namesCategory.addEntry(sub.build());
        }

        return builder.build();
    }
}
