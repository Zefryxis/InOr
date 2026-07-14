package com.example.inventoryorganizer;

import com.example.inventoryorganizer.config.HudSettings;
import com.example.inventoryorganizer.config.OrganizerConfig;
import com.example.inventoryorganizer.warehouse.WarehouseClient;
import com.example.inventoryorganizer.warehouse.WarehouseLinks;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Hidden diagnostic command {@code /InOr developer} and {@code /InOr disk}, output visible only to the
 * player who runs it (a client command is handled locally and never reaches the server, so it works in
 * single player AND on any server with no server-side install). Each player only ever sees their OWN
 * client-side data — config, HUD, runtime state and on-disk files — so it's harmless on multiplayer.
 */
public final class DevCommandsClient {

    private DevCommandsClient() {}

    private static String modVersion() {
        return FabricLoader.getInstance().getModContainer("inventory-organizer")
                .map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("?");
    }

    private static long safeSize(Path f) {
        try { return Files.size(f); } catch (Throwable t) { return -1; }
    }

    public static void registerClient() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, access) -> {
            dispatcher.register(
                ClientCommands.literal("InOr")
                    .then(ClientCommands.literal("developer")
                        .executes(ctx -> { dump(ctx.getSource(), false); return 1; }))
                    .then(ClientCommands.literal("disk")
                        .executes(ctx -> { dump(ctx.getSource(), true); return 1; })));
        });
    }

    private static void dump(FabricClientCommandSource src, boolean disk) {
        // Works in SP and on any server — handled fully client-side, output only to this player.
        for (String line : (disk ? diskLines() : developerLines())) {
            src.sendFeedback(Component.literal(line));
        }
    }

    private static List<String> developerLines() {
        List<String> l = new ArrayList<>();
        OrganizerConfig cfg = OrganizerConfig.get();
        Minecraft mc = Minecraft.getInstance();
        l.add("§b§l[InOr] Developer — futásidő");
        l.add("§7Mod: §f" + modVersion());
        try {
            String env = mc.hasSingleplayerServer() ? "Egyjátékos"
                    : (ServerEnvironment.isPrivateEnvironment() ? "Privát" : "Publikus");
            l.add("§7Környezet: §f" + env + " §7· host: §f" + ServerEnvironment.serverHost(mc)
                    + " §7· Free engedélyezett: §f" + ServerEnvironment.canUseFree());
            l.add("§7Warehouse kézfogás (szerver fut InOr-t): §f" + WarehouseClient.isAvailable());
            l.add("§7Keybind mód: §f" + cfg.getKeybindMode() + " §7· Sort akció: §f" + cfg.getSortAction());
            l.add("§7Fight mód: §f" + FightModeTracker.isActive()
                    + " §7· hátralévő: §f" + (FightModeTracker.remainingMs() / 1000) + "s");
            l.add("§7Death auto-sort: §f" + cfg.isDeathSortEnabled()
                    + " §7· Trash overflow-only: §f" + cfg.isTrashOverflowOnly()
                    + " §7· Whitelist: §f" + cfg.isWhitelistEnabled());
            l.add("§7Craft forrás ládát preferál: §f" + cfg.isCraftPreferChests());
            HudSettings h = cfg.getHud();
            l.add("§7HUD — harc: " + onOff(h.combatEnabled) + " §f(" + pct(h.combatScale) + ")"
                    + " §7mini-inv: " + onOff(h.invEnabled) + " §f(" + pct(h.invScale) + ")"
                    + " §7szett: " + onOff(h.setEnabled) + " §f(" + pct(h.setScale) + ")");
            l.add("§7Számok — Kit: §f" + cfg.getKits().size()
                    + " §7· Profil: §f" + cfg.getStoragePresets().size()
                    + " §7· Ismert láda: §f" + cfg.getKnownChests().size()
                    + " §7· 'Nothing' láda: §f" + cfg.getNothingChests().size());
            l.add("§7Számok — Trash szabály: §f" + cfg.getTrashRules().size()
                    + " §7· Egyedi csoport: §f" + cfg.getCustomGroupNames().size()
                    + " §7· Warehouse csoport: §f" + cfg.getWarehouseGroups().size()
                    + " §7· Whitelist szerver: §f" + cfg.getServerWhitelist().size());
        } catch (Throwable t) {
            l.add("§c(hiba: " + t + ")");
        }
        return l;
    }

    private static List<String> diskLines() {
        List<String> l = new ArrayList<>();
        OrganizerConfig cfg = OrganizerConfig.get();
        l.add("§b§l[InOr] Disk — eltárolt adatok");
        l.add("§7Mod: §f" + modVersion());
        try {
            File cf = new File("config/inventory-organizer.json");
            l.add("§7Config: §f" + cf.getAbsolutePath());
            l.add("§7  létezik: §f" + cf.exists() + " §7méret: §f" + (cf.exists() ? cf.length() : 0) + " B");
            Path wf = WarehouseLinks.file();
            boolean wex = Files.exists(wf);
            l.add("§7Warehouse links: §f" + wf);
            l.add("§7  létezik: §f" + wex + " §7méret: §f" + (wex ? safeSize(wf) : 0) + " B");
            l.add("§7Perzisztált — Profil: §f" + cfg.getStoragePresets().size()
                    + " §7· Kit: §f" + cfg.getKits().size()
                    + " §7· Ismert láda: §f" + cfg.getKnownChests().size()
                    + " §7· 'Nothing' láda: §f" + cfg.getNothingChests().size());
            l.add("§7Perzisztált — Egyedi csoport: §f" + cfg.getCustomGroupNames().size()
                    + " §7· Warehouse csoport: §f" + cfg.getWarehouseGroups().size()
                    + " §7· Trash szabály: §f" + cfg.getTrashRules().size());
        } catch (Throwable t) {
            l.add("§c(hiba: " + t + ")");
        }
        return l;
    }

    private static String onOff(boolean b) { return b ? "§aBE" : "§7KI"; }
    private static String pct(double scale) { return Math.round(scale * 100) + "%"; }
}
