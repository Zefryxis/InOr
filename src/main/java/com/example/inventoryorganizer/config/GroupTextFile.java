package com.example.inventoryorganizer.config;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import net.minecraft.util.Util;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Plain-text import / export for custom item groups.
 *
 * Files live in a fixed folder inside the Minecraft directory:
 *   <gameDir>/inventory-organizer-groups/
 *
 * Export writes there directly (no file picker — JFileChooser is unreliable in
 * fullscreen MC). Import is drag-and-drop only, handled in the editor screen.
 *
 * File format (lenient on import):
 *   - One Minecraft item ID per data line (e.g. "minecraft:diamond_sword").
 *   - The "minecraft:" prefix is added automatically if missing.
 *   - Blank lines, lines starting with "#" or "//" and any line that is not a
 *     known item ID are silently ignored.
 */
public final class GroupTextFile {

    private static final String FOLDER_NAME = "inventory-organizer-groups";

    private GroupTextFile() {}

    /** Returns the groups folder path, creating it if needed. */
    public static Path getGroupsFolder() throws IOException {
        Path dir = FabricLoader.getInstance().getGameDir().resolve(FOLDER_NAME);
        if (!Files.exists(dir)) Files.createDirectories(dir);
        return dir;
    }

    /** Build the export target path for a given group name (file is sanitized). */
    public static Path fileForGroup(String groupName) throws IOException {
        String safe = groupName.replaceAll("[^A-Za-z0-9._-]+", "_");
        if (safe.isEmpty()) safe = "group";
        return getGroupsFolder().resolve(safe + ".txt");
    }

    /** Open the groups folder in the host OS file manager. */
    public static void openGroupsFolder() {
        try {
            Path dir = getGroupsFolder();
            Util.getOperatingSystem().open(dir.toUri());
        } catch (Exception ignored) {}
    }

    /** Parse a text file: returns valid item IDs in file order, skipping junk lines. */
    public static List<String> parseFromFile(Path path) throws IOException {
        List<String> ids = new ArrayList<>();
        for (String raw : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            String line = raw.trim();
            if (line.isEmpty()) continue;
            if (line.startsWith("#") || line.startsWith("//")) continue;
            if (line.startsWith("- ")) line = line.substring(2).trim();
            if (line.length() >= 2 && line.startsWith("\"") && line.endsWith("\"")) {
                line = line.substring(1, line.length() - 1).trim();
            }
            if (line.endsWith(",")) line = line.substring(0, line.length() - 1).trim();
            if (!line.contains(":")) line = "minecraft:" + line;
            try {
                Identifier id = Identifier.of(line);
                Item item = Registries.ITEM.get(id);
                if (item != null && item != Items.AIR) {
                    ids.add(line);
                }
            } catch (Exception ignored) {}
        }
        return ids;
    }

    /** Write item IDs to a text file, one per line, with a small comment header. */
    public static void writeToFile(Path path, String groupName, List<String> ids) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("# Inventory Organizer — custom group export\n");
        sb.append("# Group: ").append(groupName).append("\n");
        sb.append("# One item ID per line. '#' starts a comment.\n");
        sb.append("\n");
        for (String id : ids) {
            sb.append(id).append('\n');
        }
        Files.write(path, sb.toString().getBytes(StandardCharsets.UTF_8));
    }
}
