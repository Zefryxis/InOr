package com.example.inventoryorganizer.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.util.Util;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * JSON-based import / export for {@link Kit} presets.
 * Files live in <gameDir>/inventory-organizer-kits/. Export writes there
 * directly; import auto-scans the import/ subfolder.
 *
 * Each kit is serialized as a single .json file: name + slot rules + preferences.
 * Custom group DEFINITIONS are NOT included (those have their own group import/export).
 * Slot rules that reference custom groups (cg:name) are still written — the user must
 * import the matching group separately if it isn't already present.
 */
public final class KitFile {

    private static final String FOLDER_NAME = "inventory-organizer-kits";
    private static final String IMPORT_FOLDER_NAME = "import";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private KitFile() {}

    public static Path getKitsFolder() throws IOException {
        Path dir = FabricLoader.getInstance().getGameDir().resolve(FOLDER_NAME);
        if (!Files.exists(dir)) Files.createDirectories(dir);
        return dir;
    }

    /** Folder where users drop .json files they want imported. */
    public static Path getImportFolder() throws IOException {
        Path dir = getKitsFolder().resolve(IMPORT_FOLDER_NAME);
        if (!Files.exists(dir)) Files.createDirectories(dir);
        return dir;
    }

    public static Path fileForKit(String kitName) throws IOException {
        String safe = kitName.replaceAll("[^A-Za-z0-9._-]+", "_");
        if (safe.isEmpty()) safe = "kit";
        return getKitsFolder().resolve(safe + ".json");
    }

    public static void openKitsFolder() {
        try { Util.getPlatform().openUri(getKitsFolder().toUri()); } catch (Exception ignored) {}
    }

    public static void openImportFolder() {
        try { Util.getPlatform().openUri(getImportFolder().toUri()); } catch (Exception ignored) {}
    }

    /** Write a single kit to its own JSON file in the kits folder. */
    public static void exportKit(Kit kit) throws IOException {
        Path path = fileForKit(kit.getName());
        String json = GSON.toJson(kit);
        Files.write(path, json.getBytes(StandardCharsets.UTF_8));
    }

    /** Export every kit in the config to its own file. Returns the number of files written. */
    public static int exportAll(List<Kit> kits) {
        int n = 0;
        for (Kit k : kits) {
            try { exportKit(k); n++; } catch (Exception ignored) {}
        }
        return n;
    }

    /** Result of scanning a single import file. */
    public static final class ImportedKit {
        public final Kit kit;
        public final Path sourcePath;
        public ImportedKit(Kit kit, Path sourcePath) {
            this.kit = kit;
            this.sourcePath = sourcePath;
        }
    }

    /** Parse a .json file as a Kit. Returns null on failure. */
    public static Kit parseFromFile(Path path) {
        try {
            String json = Files.readString(path, StandardCharsets.UTF_8);
            Kit kit = GSON.fromJson(json, Kit.class);
            if (kit == null || kit.getName() == null || kit.getName().isEmpty()) {
                // Fallback: use the filename (without extension) as the kit name.
                String fname = path.getFileName().toString();
                if (fname.toLowerCase().endsWith(".json")) fname = fname.substring(0, fname.length() - 5);
                if (kit != null) kit.setName(fname);
            }
            return kit;
        } catch (Exception ignored) {
            return null;
        }
    }

    /** Scan the import/ folder for .json files. Returns a list of ImportedKit. */
    public static List<ImportedKit> scanImportFolder() {
        List<ImportedKit> result = new ArrayList<>();
        try {
            Path dir = getImportFolder();
            if (!Files.exists(dir)) return result;
            try (java.util.stream.Stream<Path> stream = Files.list(dir)) {
                stream.filter(p -> p.toString().toLowerCase().endsWith(".json"))
                      .forEach(p -> {
                          Kit k = parseFromFile(p);
                          if (k != null) result.add(new ImportedKit(k, p));
                      });
            }
        } catch (Exception ignored) {}
        return result;
    }
}
