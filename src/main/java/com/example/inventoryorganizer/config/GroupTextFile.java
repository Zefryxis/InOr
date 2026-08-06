package com.example.inventoryorganizer.config;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Util;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Plain-text import / export for custom item groups.
 * Files live in <gameDir>/inventory-organizer-groups/. Export writes there
 * directly; import auto-scans the import/ subfolder.
 */
public final class GroupTextFile {

    private static final String FOLDER_NAME = "inventory-organizer-groups";
    private static final String IMPORT_FOLDER_NAME = "import";

    private GroupTextFile() {}

    public static Path getGroupsFolder() throws IOException {
        Path dir = FabricLoader.getInstance().getGameDir().resolve(FOLDER_NAME);
        if (!Files.exists(dir)) Files.createDirectories(dir);
        return dir;
    }

    /** Folder where users drop .txt files they want imported. Auto-scanned on demand. */
    public static Path getImportFolder() throws IOException {
        Path dir = getGroupsFolder().resolve(IMPORT_FOLDER_NAME);
        if (!Files.exists(dir)) Files.createDirectories(dir);
        return dir;
    }

    public static Path fileForGroup(String groupName) throws IOException {
        String safe = groupName.replaceAll("[^A-Za-z0-9._-]+", "_");
        if (safe.isEmpty()) safe = "group";
        return getGroupsFolder().resolve(safe + ".txt");
    }

    public static void openGroupsFolder() {
        try {
            Path dir = getGroupsFolder();
            Util.getPlatform().openUri(dir.toUri());
        } catch (Exception ignored) {}
    }

    public static void openImportFolder() {
        try {
            Path dir = getImportFolder();
            Util.getPlatform().openUri(dir.toUri());
        } catch (Exception ignored) {}
    }

    /**
     * Scan the import/ folder for .txt files and parse them.
     * Returns a list of ImportedGroup records (groupName + item IDs).
     * The group name is the filename without the .txt extension.
     */
    public static List<ImportedGroup> scanImportFolder() {
        List<ImportedGroup> result = new ArrayList<>();
        try {
            Path dir = getImportFolder();
            if (!Files.exists(dir)) return result;
            try (java.util.stream.Stream<Path> stream = Files.list(dir)) {
                stream.filter(p -> p.toString().toLowerCase().endsWith(".txt"))
                      .forEach(p -> {
                          try {
                              String name = p.getFileName().toString();
                              if (name.toLowerCase().endsWith(".txt")) name = name.substring(0, name.length() - 4);
                              List<String> ids = parseFromFile(p);
                              result.add(new ImportedGroup(name, ids, p));
                          } catch (Exception ignored) {}
                      });
            }
        } catch (Exception ignored) {}
        return result;
    }

    /** Result of scanning a single import file. */
    public static final class ImportedGroup {
        public final String groupName;
        public final List<String> itemIds;
        public final Path sourcePath;

        public ImportedGroup(String groupName, List<String> itemIds, Path sourcePath) {
            this.groupName = groupName;
            this.itemIds = itemIds;
            this.sourcePath = sourcePath;
        }
    }

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
                Identifier id = Identifier.parse(line);
                Item item = BuiltInRegistries.ITEM.getValue(id);
                if (item != null && item != Items.AIR) {
                    ids.add(line);
                }
            } catch (Exception ignored) {}
        }
        return ids;
    }

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
