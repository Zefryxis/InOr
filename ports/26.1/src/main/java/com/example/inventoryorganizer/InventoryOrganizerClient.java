package com.example.inventoryorganizer;

import com.example.inventoryorganizer.config.KitsScreen;
import com.example.inventoryorganizer.config.OrganizerConfig;
import com.example.inventoryorganizer.config.StoragePreset;
import com.example.inventoryorganizer.config.VisualInventoryConfigScreen;
import com.example.inventoryorganizer.hud.HudRenderer;
import com.example.inventoryorganizer.warehouse.WarehouseClient;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.resources.Identifier;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;

public class InventoryOrganizerClient implements ClientModInitializer {

    private static final org.slf4j.Logger LOGGER =
            org.slf4j.LoggerFactory.getLogger("inventory-organizer/Client");

    // Public so other classes (e.g. UI) can read current bindings if needed.
    public static KeyMapping SORT_KEY;
    public static KeyMapping KITS_KEY;
    public static KeyMapping SETTINGS_KEY;
    public static KeyMapping WAREHOUSE_KEY; // TEMP Phase-1 test trigger: sort nearby chests as a warehouse
    public static KeyMapping CHEST_REFILL_KEY;  // manual: top up the ↻-marked slots from the open chest
    public static KeyMapping CYCLE_PRESET_KEY;  // cycle through saved Kits (slot-config presets)
    public static KeyMapping REFILL_TOGGLE_KEY; // toggle auto-refill on/off
    public static KeyMapping SWITCH_TRIGGER_KEY; // manually trigger the Switch tool swap

    // While a chest screen is open (multiplayer): re-run its profile UI when the server's resolution
    // changes, and periodically re-resolve, so an owner hiding/sharing is detected near-instantly.
    private static Runnable chestProfileRefresh = null;
    private static int chestProfileVersionSeen = 0;
    private static com.example.inventoryorganizer.crafting.RemoteCraftPanel craftPanel = null;
    private static net.minecraft.client.gui.screens.Screen craftHookedScreen = null;
    private static int lastGuiLeftPos = Integer.MIN_VALUE; // detect GUI shift (recipe book toggle) to re-layout overlays
    private static int chestResolveTick = 0;

    /** Custom Controls-menu category — appears as its own section labeled "InOr". */
    public static final KeyMapping.Category INVORG_CATEGORY = KeyMapping.Category.register(
            Identifier.fromNamespaceAndPath("inventory-organizer", "main"));

    /**
     * Last hit result captured when a ContainerScreen opened — used to reopen the chest after
     * the BOTH-action keybind has closed it to sort the player inventory.
     */
    private static net.minecraft.world.phys.BlockHitResult lastChestHitResult = null;

    @Override
    public void onInitializeClient() {

        // --- Warehouse: listen for the server's "InOr present" handshake (enables warehouse UI) ---
        WarehouseClient.registerClient();

        // --- Hidden diagnostic command (single-player form: /InOr developer | /InOr disk) ---
        DevCommandsClient.registerClient();

        // --- One-time migration: turn the former heuristic built-in groups into real, editable custom
        // groups (so they appear in the ranks screen and behave exactly like a hand-made group). Runs
        // here because the item registry is available at client init. ---
        com.example.inventoryorganizer.config.OrganizerConfig.get().materializeBuiltinGroupsOnce();

        // --- Register keybinds (all unbound by default; user assigns in vanilla Controls menu) ---
        // SORT_KEY is the "smart" sort key — its action is configurable in mod settings:
        //   oi_only / ost_only / smart (default) / both
        SORT_KEY = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.inventory-organizer.sort",
                InputConstants.Type.KEYSYM,
                InputConstants.UNKNOWN.getValue(),
                INVORG_CATEGORY));
        KITS_KEY = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.inventory-organizer.kits",
                InputConstants.Type.KEYSYM,
                InputConstants.UNKNOWN.getValue(),
                INVORG_CATEGORY));
        SETTINGS_KEY = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.inventory-organizer.settings",
                InputConstants.Type.KEYSYM,
                InputConstants.UNKNOWN.getValue(),
                INVORG_CATEGORY));
        WAREHOUSE_KEY = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.inventory-organizer.warehouse",
                InputConstants.Type.KEYSYM,
                InputConstants.UNKNOWN.getValue(),
                INVORG_CATEGORY));
        CHEST_REFILL_KEY = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.inventory-organizer.chest_refill",
                InputConstants.Type.KEYSYM,
                InputConstants.UNKNOWN.getValue(),
                INVORG_CATEGORY));
        CYCLE_PRESET_KEY = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.inventory-organizer.cycle_preset",
                InputConstants.Type.KEYSYM,
                InputConstants.UNKNOWN.getValue(),
                INVORG_CATEGORY));
        REFILL_TOGGLE_KEY = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.inventory-organizer.refill_toggle",
                InputConstants.Type.KEYSYM,
                InputConstants.UNKNOWN.getValue(),
                INVORG_CATEGORY));
        SWITCH_TRIGGER_KEY = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.inventory-organizer.switch_trigger",
                InputConstants.Type.KEYSYM,
                InputConstants.UNKNOWN.getValue(),
                INVORG_CATEGORY));

        // --- Fight Mode: detect being hit by a player ---
        // hurtTime jumps when damaged; we verify the attacker is a Player.
        int[] prevHurtTime = {0};
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.player == null) { prevHurtTime[0] = 0; return; }

            // Re-enable OI button once the 130ms cooldown expires (replaces removed tick() inject)
            Button btn = FightModeTracker.oiButtonRef;
            if (btn != null && !btn.active && FightModeTracker.canUseOI()) {
                btn.active = true;
            }

            // --- Death auto-sort tracking ---
            DeathSortTracker.tick(client);

            // --- Keybind polling ---
            // While the player is typing in the Materials search/qty box, DRAIN every keybind without
            // acting — so no sort/kits/settings/etc. fires from the letters they're typing.
            boolean typing = craftPanel != null && craftPanel.isTyping();
            // The user's keybind mode (inv_only/free/disabled) is honoured, but "free" only
            // takes effect where allowed (private environment, not in fight mode).
            String kbMode = OrganizerConfig.get().getKeybindMode();
            if (!"disabled".equals(kbMode)) {
                String effMode = ("free".equals(kbMode) && ServerEnvironment.canUseFree()) ? "free" : "inventory_only";
                String action = OrganizerConfig.get().getSortAction();
                while (SORT_KEY.consumeClick()) { if (!typing) handleSortKeybind(client, effMode, action); }
                while (KITS_KEY.consumeClick()) { if (!typing) handleScreenOpenKeybind(client, effMode, true); }
                while (SETTINGS_KEY.consumeClick()) { if (!typing) handleScreenOpenKeybind(client, effMode, false); }
            }

            // --- Auto-refill marked slots (free mode, no screen open) ---
            tickAutoRefill(client);

            // --- Trash/void: drop items matching the trash list (free mode, no screen open) ---
            tickTrash(client);

            // --- Switch: auto-swap or keybind-triggered tool swap ---
            com.example.inventoryorganizer.client.SwitchToolHandler.tick(client);
            while (SWITCH_TRIGGER_KEY.consumeClick()) {
                com.example.inventoryorganizer.client.SwitchToolHandler.onTriggerKeyPress(client);
            }

            // Cycle slot-config presets (saved Kits). Independent of keybind mode.
            while (CYCLE_PRESET_KEY.consumeClick()) { if (!typing) doCyclePreset(client); }

            // Toggle auto-refill on/off (independent of keybind mode).
            while (REFILL_TOGGLE_KEY.consumeClick()) {
                if (typing) continue;
                OrganizerConfig cfg = OrganizerConfig.get();
                boolean on = !cfg.isAutoRefillEnabled();
                cfg.setAutoRefillEnabled(on);
                cfg.save();
                // Diagnostic: why might refill not fire? Show the gating state.
                int marked = 0; for (int i = 0; i < 36; i++) if (cfg.isSlotRefill(i)) marked++;
                LOGGER.info("[Refill] toggle -> {} | markedSlots={} | freeMode={} | screenOpen={}",
                        on ? "ON" : "OFF", marked, ServerEnvironment.canUseFree(), client.screen != null);
                if (marked == 0) LOGGER.warn("[Refill] No slots are marked with the ↻ refill flag — auto-refill "
                        + "only restocks slots you've marked. Mark a slot's refill toggle in the slot config.");
                if (client.gui != null) client.gui.setOverlayMessage(
                        Component.translatable(on ? "inventory-organizer.refill.on" : "inventory-organizer.refill.off"), false);
            }

            // Warehouse: open the top-down chest map (independent of keybind mode; needs the server's handshake).
            while (WAREHOUSE_KEY.consumeClick()) {
                if (!typing && WarehouseClient.isAvailable()) {
                    client.setScreen(new com.example.inventoryorganizer.warehouse.WarehouseMapScreen(client.screen));
                }
            }

            // While a chest screen is open on a server, keep its link state fresh so a chest that
            // gets revealed (foreign link) is reflected within ~0.5s (not only on the next open).
            if (chestProfileRefresh != null && WarehouseClient.useServerProfiles()) {
                if ((++chestResolveTick % 10) == 0) WarehouseClient.requestLinkMap();
                if (WarehouseClient.linkVersion() != chestProfileVersionSeen) {
                    chestProfileVersionSeen = WarehouseClient.linkVersion();
                    chestProfileRefresh.run();
                }
            }

            // Remote-crafting materials panel: keep the nearby-chest stock fresh while the table is open.
            if (craftPanel != null) craftPanel.tick();

            // Universal resize guard: whenever the open screen's cached size no longer matches the
            // window's current GUI-scaled size (e.g. dragging the window border), force a re-init so all
            // widgets re-lay-out. Minecraft normally does this itself, but this catches any case where
            // that didn't happen. Screen.width/height are public; resize() re-runs init() + AFTER_INIT.
            if (client.screen != null) {
                int gw = client.getWindow().getGuiScaledWidth();
                int gh = client.getWindow().getGuiScaledHeight();
                if (client.screen.width != gw || client.screen.height != gh) {
                    try { client.screen.resize(gw, gh); } catch (Throwable ignored) {}
                }
            }

            // GUI-shift follow: toggling the recipe book (the "little book") slides the inventory/
            // crafting GUI sideways WITHOUT re-initialising the screen, so our injected buttons and the
            // materials panel would be left behind. Detect the leftPos change and re-init the screen,
            // which re-lays-out every overlay into the new position. (Window-drag resize already re-inits.)
            if (client.screen instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?> acs) {
                try {
                    int lp = ((com.example.inventoryorganizer.mixin.ContainerScreenAccessor) (Object) acs).inorLeftPos();
                    if (lastGuiLeftPos != Integer.MIN_VALUE && lp != lastGuiLeftPos) {
                        acs.resize(client.getWindow().getGuiScaledWidth(), client.getWindow().getGuiScaledHeight());
                    }
                    lastGuiLeftPos = lp;
                } catch (Throwable ignored) {}
            } else {
                lastGuiLeftPos = Integer.MIN_VALUE;
            }

            // Self-heal the materials panel: if a crafting/inventory screen is open and the warehouse is
            // available, but the panel got lost (null or bound to a stale screen — e.g. after toggling the
            // recipe book a few times), force a re-init so AFTER_INIT rebuilds it. Fixes the vanishing
            // withdraw/deposit icons. No-op once a valid panel exists for the current screen (no loop).
            if (client.screen instanceof net.minecraft.client.gui.screens.inventory.CraftingScreen
                    || client.screen instanceof InventoryScreen) {
                boolean needHeal = craftPanel == null || craftPanel.screen() != client.screen || !craftPanel.isAttached();
                if (WarehouseClient.isAvailable() && needHeal) {
                    try {
                        client.screen.resize(client.getWindow().getGuiScaledWidth(),
                                client.getWindow().getGuiScaledHeight());
                    } catch (Throwable ignored) {}
                }
            }

            int cur = client.player.hurtTime;
            if (cur > prevHurtTime[0]) {
                // getAttacker() returns whoever last damaged this entity (player or their projectile owner).
                // Guard against stale references: require the attacker to be alive and within a plausible
                // combat range, otherwise non-PvP damage (fall, lava, mobs) after a prior PvP event
                // would incorrectly re-trigger fight mode.
                net.minecraft.world.entity.Entity attacker = client.player.getLastHurtByMob();
                if (attacker instanceof Player && attacker.isAlive() && !attacker.isRemoved()
                        && attacker.distanceToSqr(client.player) < 4096.0) {
                    FightModeTracker.triggerCombat();
                }
            }
            prevHurtTime[0] = cur;
        });

        // --- Fight Mode: detect attacking another player ---
        AttackEntityCallback.EVENT.register((player, world, hand, entity, hitResult) -> {
            if (entity instanceof Player) {
                FightModeTracker.triggerCombat();
            }
            return InteractionResult.PASS;
        });

        // --- OST + OI buttons on ContainerScreen (chests, shulkers, etc.) ---
        // Uses ScreenEvents instead of a mixin — ContainerScreen inherits init()
        // from AbstractContainerScreen and doesn't redeclare it, so Mixin can't target it directly.
        ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
            if (!(screen instanceof ContainerScreen gcs)) return;

            // Capture chest hit result so we can reopen it later (e.g. after "both" action keybind)
            // and so the per-chest profile can be resolved by coordinates.
            net.minecraft.world.phys.BlockHitResult bhr =
                (client.hitResult instanceof net.minecraft.world.phys.BlockHitResult b) ? b : null;
            if (bhr != null) lastChestHitResult = bhr;

            int rows = gcs.getMenu().getRowCount();
            int bgHeight = 114 + rows * 18;
            int guiW = 176;
            int btnX = (screen.width - guiW) / 2 + guiW - 30;
            int panelX = (screen.width - guiW) / 2;
            int panelY = (screen.height - bgHeight) / 2;
            int btnY = panelY - 18;

            // OST button — sorts the chest contents
            Button ostButton = Button.builder(
                Component.translatable("inventory-organizer.button.ost"),
                button -> {
                    Minecraft mc = Minecraft.getInstance();
                    if (mc.player != null) {
                        if (tryWarehouseGroupSort()) return; // linked chest → sort the whole warehouse
                        AbstractContainerMenu sh = mc.player.containerMenu;
                        int syncId = sh.containerId;
                        int containerSize = gcs.getMenu().getRowCount() * 9;
                        StorageSorter.sortContainer(containerSize, syncId);
                    }
                }
            ).bounds(btnX, btnY, 28, 14).build();
            net.fabricmc.fabric.api.client.screen.v1.Screens.getWidgets(screen).add(ostButton);

            // OI button — placed 2px to the LEFT of OST. Sorts player inventory.
            // Server only accepts inventoryMenu clicks while inventoryMenu is the active container,
            // so we send a proper container-close packet (via closeContainer), then open the inventory
            // screen so the user sees the sort happening, then run the sort.
            Button oiButton = Button.builder(
                Component.translatable("inventory-organizer.button.oi"),
                button -> {
                    Minecraft mc = Minecraft.getInstance();
                    if (mc.player == null) return;
                    if (FightModeTracker.isActive() && !FightModeTracker.canUseOI()) return;
                    // Proper ESC-style close: sends ServerboundContainerClose + resets containerMenu to inventoryMenu.
                    mc.player.closeContainer();
                    // Open inventory screen so user sees the sort animation.
                    mc.setScreen(new InventoryScreen(mc.player));
                    if (FightModeTracker.isActive()) {
                        FightModeTracker.markOIUsed();
                        InventorySorter.sortInventoryFightMode();
                    } else {
                        InventorySorter.sortInventory();
                    }
                }
            ).bounds(btnX - 28, btnY, 22, 14).build();
            net.fabricmc.fabric.api.client.screen.v1.Screens.getWidgets(screen).add(oiButton);

            // Deposit-all-matching button (free mode) — every inventory stack whose item already exists
            // in this chest jumps in. Vanilla-style quick "restock the chest". Placed at the far left of
            // the button row (left of Wh/Pr) so it never overlaps the other buttons.
            Button depButton = Button.builder(
                Component.translatable("inventory-organizer.button.deposit"),
                button -> doDepositAllMatching(Minecraft.getInstance())
            ).bounds(btnX - 106, btnY, 24, 14).build();
            net.fabricmc.fabric.api.client.screen.v1.Screens.getWidgets(screen).add(depButton);

            // Chest-refill button (free mode) — tops up ALL ↻-marked slots from this chest.
            Button refillButton = Button.builder(
                Component.translatable("inventory-organizer.button.refill"),
                button -> doChestRefill(Minecraft.getInstance())
            ).bounds(btnX - 134, btnY, 22, 14).build();
            net.fabricmc.fabric.api.client.screen.v1.Screens.getWidgets(screen).add(refillButton);
            // Shift-scroll looting + inventory scroll-sort are driven by ContainerScrollMixin (the Fabric
            // ScreenMouseEvents don't fire on container screens, which don't call super.mouseScrolled).

            // --- Per-chest profile: resolve the binding for this chest ---
            // Sets the active profile (or none → OST falls back to the size-based default).
            ChestIdentifier.onChestOpen(gcs.getTitle(), bhr, rows);
            // Refresh link visibility from the server so we know whether this chest belongs to another
            // player's link (then it's sort-only — we won't offer to profile it).
            if (com.example.inventoryorganizer.warehouse.WarehouseClient.isAvailable()) {
                com.example.inventoryorganizer.warehouse.WarehouseClient.requestLinkMap();
            }

            // Profiles button (left of OI) — opens the profile list to bind a carried profile to
            // this chest (picker mode). Opening a full screen closes the container, so we snapshot
            // the chest context first.
            Button profilesBtn = Button.builder(
                Component.translatable("inventory-organizer.profile.profiles_btn_short"),
                button -> {
                    Minecraft mc = Minecraft.getInstance();
                    String cname = ChestIdentifier.getCurrentCustomName();
                    java.util.List<int[]> cpos = ChestIdentifier.getCurrentPositions();
                    int csize = ChestIdentifier.getCurrentSize();
                    String csign = ChestIdentifier.getCurrentSignText();
                    if (mc.player != null) mc.player.closeContainer();
                    mc.setScreen(new com.example.inventoryorganizer.config.ChestProfileListScreen(null, cname, cpos, csize, csign));
                }
            ).bounds(btnX - 50, btnY, 16, 14).build();
            net.fabricmc.fabric.api.client.screen.v1.Screens.getWidgets(screen).add(profilesBtn);

            // Wh button (left of Profiles) — opens the warehouse map. Only when the warehouse is
            // available (server runs InOr / single-player). Opening a full screen closes the container.
            if (com.example.inventoryorganizer.warehouse.WarehouseClient.isAvailable()) {
                Button whBtn = Button.builder(
                    Component.translatable("inventory-organizer.button.wh"),
                    button -> {
                        Minecraft mc = Minecraft.getInstance();
                        if (mc.player != null) mc.player.closeContainer();
                        mc.setScreen(new com.example.inventoryorganizer.warehouse.WarehouseMapScreen(null));
                    }
                ).bounds(btnX - 76, btnY, 20, 14).build();
                net.fabricmc.fabric.api.client.screen.v1.Screens.getWidgets(screen).add(whBtn);
            }

            // Label above the chest: shows the active profile, or a "New profile? [Yes][Nothing]"
            // prompt when the chest is unbound. Uses plain widgets (no render hook needed).
            final net.minecraft.client.gui.components.StringWidget profileLabel =
                new net.minecraft.client.gui.components.StringWidget(panelX, panelY - 31, 110, 10, Component.empty(), client.font);

            final Runnable[] refresh = new Runnable[1];

            Button yesBtn = Button.builder(
                Component.translatable("inventory-organizer.profile.new_yes"),
                b -> { ChestIdentifier.createAndBindProfile(); if (refresh[0] != null) refresh[0].run(); }
            ).bounds(panelX + 90, panelY - 32, 30, 12).build();

            Button noBtn = Button.builder(
                Component.translatable("inventory-organizer.profile.new_no"),
                b -> { ChestIdentifier.markCurrentChestAsNothing(); if (refresh[0] != null) refresh[0].run(); }
            ).bounds(panelX + 122, panelY - 32, 50, 12).build();

            refresh[0] = () -> {
                boolean foreignLink = ChestIdentifier.isCurrentChestForeignLink();
                StoragePreset act = ChestIdentifier.getActiveProfile();
                yesBtn.visible = false; yesBtn.active = false;
                noBtn.visible = false;  noBtn.active = false;
                // Can't profile another player's link chest — hide the Profiles button there.
                profilesBtn.visible = !foreignLink;
                if (foreignLink) {
                    // Another player's warehouse link, revealed to us: OST sorts the whole link, but we
                    // can't profile it. Show a read-only "X's warehouse — sort only" label.
                    profileLabel.setMessage(Component.translatable(
                            "inventory-organizer.profile.foreign_link", ChestIdentifier.getForeignLinkOwner()));
                    profileLabel.visible = true;
                } else if (act != null) {
                    profileLabel.setMessage(Component.translatable("inventory-organizer.profile.organizing_label", act.getName()));
                    profileLabel.visible = true;
                } else if (!ChestIdentifier.isPromptDismissed()) {
                    profileLabel.setMessage(Component.translatable("inventory-organizer.profile.new_prompt"));
                    profileLabel.visible = true;
                    yesBtn.visible = true; yesBtn.active = true;
                    noBtn.visible = true;  noBtn.active = true;
                } else {
                    profileLabel.visible = false;
                }
            };

            net.fabricmc.fabric.api.client.screen.v1.Screens.getWidgets(screen).add(profileLabel);
            net.fabricmc.fabric.api.client.screen.v1.Screens.getWidgets(screen).add(yesBtn);
            net.fabricmc.fabric.api.client.screen.v1.Screens.getWidgets(screen).add(noBtn);
            refresh[0].run();
            // Let the client tick re-run this when the server's profile resolution updates (MP).
            chestProfileRefresh = refresh[0];
            chestProfileVersionSeen = com.example.inventoryorganizer.warehouse.WarehouseClient.linkVersion();

            // Returning to "carrying mode" when the chest closes.
            ScreenEvents.remove(screen).register(s -> { ChestIdentifier.clearActive(); chestProfileRefresh = null; });
        });

        // --- Remote item fetcher: materials panel on the crafting table AND the survival inventory ---
        // (NOT the creative inventory — CreativeModeInventoryScreen is neither of these classes.)
        // At a crafting table in SP the reach is 60 blocks; in the plain inventory (or any server) it's 15.
        ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
            boolean ok = screen instanceof net.minecraft.client.gui.screens.inventory.CraftingScreen
                    || screen instanceof InventoryScreen;
            if (!ok || !WarehouseClient.isAvailable()) return;
            // Rebuild the panel (and its widgets) every init — including on window resize AND on the
            // recipe-book toggle, both of which re-run init(). IMPORTANT: Fabric CLEARS all per-screen
            // event subscribers (afterExtract/allowMouseScroll/allowKeyPress/remove) on every init, so we
            // MUST re-register them here every time — not once. The previous "register only once" guard
            // caused the render hook to be dropped after a recipe-book toggle, which made the panel's
            // frame + item icons vanish (the panel object was still alive, but nothing drew it).
            craftPanel = new com.example.inventoryorganizer.crafting.RemoteCraftPanel(
                    (net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?>) screen);
            craftHookedScreen = screen;
            net.fabricmc.fabric.api.client.screen.v1.ScreenEvents.afterExtract(screen).register((sc, context, mx, my, delta) -> {
                if (craftPanel != null) craftPanel.render(context);
            });
            // Mouse-wheel scrolling while the cursor is over the materials list.
            net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents.allowMouseScroll(screen).register((sc, mx, my, hAmt, vAmt) -> {
                if (craftPanel != null && craftPanel.isOverList(mx, my)) {
                    craftPanel.scrollBy(vAmt > 0 ? -1 : 1);
                    return false; // consume — don't pass the scroll to the screen/hotbar
                }
                return true;
            });
            // While typing in the Materials search/qty box, swallow keys that would otherwise fire a
            // vanilla bind (close inventory with E, hotbar-swap with 1-9, drop with Q, …). Edit keys
            // (backspace/arrows/home/end/enter/tab), Esc, and Ctrl-combos pass through so editing works;
            // the actual characters still arrive via charTyped. So you can type anything freely.
            net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents.allowKeyPress(screen).register((sc, keyEvent) -> {
                if (craftPanel == null || !craftPanel.isTyping()) return true;
                int key = keyEvent.key();
                boolean ctrl = (keyEvent.modifiers() & 0x2) != 0; // GLFW_MOD_CONTROL
                boolean editKey = key == 256 /*esc*/ || key == 257 /*enter*/ || key == 258 /*tab*/
                        || key == 259 /*backspace*/ || key == 261 /*delete*/
                        || (key >= 262 && key <= 265) /*arrows*/ || key == 268 /*home*/ || key == 269 /*end*/;
                return editKey || ctrl; // allow editing/escape/shortcuts; cancel everything else
            });
            ScreenEvents.remove(screen).register(s -> { craftPanel = null; craftHookedScreen = null; });
        });

        // --- Generic (modded) chest-like containers ---------------------------------------------------
        // The rich ContainerScreen handler above only fires for the vanilla generic container screen. Many
        // mods use their OWN screen class. This lighter, SEPARATE path adds OST/OI to any chest-LIKE
        // AbstractContainerScreen (last 36 slots = player inventory, the rest a single storage grid that's
        // a positive multiple of 9 — this excludes furnaces/machines), records the world position as a
        // "generic" chest so it shows on the warehouse map with a ring marker, and leaves everything else
        // (the vanilla path, the player inventory, creative, crafting) completely untouched.
        ScreenEvents.AFTER_INIT.register((client, screen, sw, sh) -> {
            if (!(screen instanceof AbstractContainerScreen<?> acs)) return;
            if (screen instanceof ContainerScreen) return;          // vanilla chests: handled above
            if (screen instanceof InventoryScreen) return;          // player inventory
            if (screen instanceof net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen) return;
            if (screen instanceof net.minecraft.client.gui.screens.inventory.CraftingScreen) return;
            int kind = classifyContainer(acs);
            if (kind == CONTAINER_NONE) return;     // machine / not a storage GUI → leave alone

            net.minecraft.world.phys.BlockHitResult bhr =
                    (client.hitResult instanceof net.minecraft.world.phys.BlockHitResult b) ? b : null;

            if (kind == CONTAINER_SPECIAL) {
                // Storage-sized but has special/function slots — we must NOT reorder it. Show the notice
                // RIGHT WHERE the OST button would be (a label across the top of the GUI), so it's visible.
                com.example.inventoryorganizer.mixin.ContainerScreenAccessor sacc =
                        (com.example.inventoryorganizer.mixin.ContainerScreenAccessor) (Object) acs;
                net.minecraft.client.gui.components.StringWidget note = new net.minecraft.client.gui.components.StringWidget(
                        sacc.inorLeftPos(), sacc.inorTopPos() - 14, sacc.inorImageWidth(), 12,
                        Component.translatable("inventory-organizer.generic.special_short"), client.font);
                net.fabricmc.fabric.api.client.screen.v1.Screens.getWidgets(screen).add(note);
                return;
            }

            // PLAIN storage of `kind` slots. Record it (known + generic for the map ring), set the chest
            // context so OST uses any bound profile, and offer OST / Pr (profile) / OI.
            final int chestSize = kind;
            if (bhr != null) {
                net.minecraft.core.BlockPos bp = bhr.getBlockPos();
                OrganizerConfig.get().addKnownChest(bp.getX(), bp.getY(), bp.getZ());
                if (OrganizerConfig.get().addGenericChest(bp.getX(), bp.getY(), bp.getZ())) OrganizerConfig.get().save();
                ChestIdentifier.onChestOpenSized(screen.getTitle(), bhr, chestSize);
            }

            com.example.inventoryorganizer.mixin.ContainerScreenAccessor acc =
                    (com.example.inventoryorganizer.mixin.ContainerScreenAccessor) (Object) acs;
            int btnX = acc.inorLeftPos() + acc.inorImageWidth() - 30;
            int btnY = acc.inorTopPos() - 18;

            Button gOst = Button.builder(Component.translatable("inventory-organizer.button.ost"), b -> {
                Minecraft mc = Minecraft.getInstance();
                if (mc.player == null) return;
                StorageSorter.sortContainer(chestSize, mc.player.containerMenu.containerId);
            }).bounds(btnX, btnY, 28, 14).build();
            net.fabricmc.fabric.api.client.screen.v1.Screens.getWidgets(screen).add(gOst);

            Button gOi = Button.builder(Component.translatable("inventory-organizer.button.oi"), b -> {
                Minecraft mc = Minecraft.getInstance();
                if (mc.player == null) return;
                if (FightModeTracker.isActive() && !FightModeTracker.canUseOI()) return;
                mc.player.closeContainer();
                mc.setScreen(new InventoryScreen(mc.player));
                if (FightModeTracker.isActive()) { FightModeTracker.markOIUsed(); InventorySorter.sortInventoryFightMode(); }
                else InventorySorter.sortInventory();
            }).bounds(btnX - 28, btnY, 22, 14).build();
            net.fabricmc.fabric.api.client.screen.v1.Screens.getWidgets(screen).add(gOi);

            // Pr — bind/create a profile for this container (only when opened by clicking a world block).
            if (bhr != null) {
                Button gPr = Button.builder(Component.translatable("inventory-organizer.profile.profiles_btn_short"), b -> {
                    Minecraft mc = Minecraft.getInstance();
                    String cname = ChestIdentifier.getCurrentCustomName();
                    java.util.List<int[]> cpos = ChestIdentifier.getCurrentPositions();
                    int csize = ChestIdentifier.getCurrentSize();
                    String csign = ChestIdentifier.getCurrentSignText();
                    if (mc.player != null) mc.player.closeContainer();
                    mc.setScreen(new com.example.inventoryorganizer.config.ChestProfileListScreen(null, cname, cpos, csize, csign));
                }).bounds(btnX - 50, btnY, 16, 14).build();
                net.fabricmc.fabric.api.client.screen.v1.Screens.getWidgets(screen).add(gPr);

                // "New profile?" prompt above the chest (same as vanilla), PLUS a warning that profiling a
                // modded container may behave oddly. Only when opened by clicking a world block.
                int left = acc.inorLeftPos(), top = acc.inorTopPos(), iw = acc.inorImageWidth();
                final net.minecraft.client.gui.components.StringWidget label =
                        new net.minecraft.client.gui.components.StringWidget(left, top - 32, iw, 10, Component.empty(), client.font);
                final net.minecraft.client.gui.components.StringWidget warn =
                        new net.minecraft.client.gui.components.StringWidget(left, top - 46, iw, 10,
                                Component.translatable("inventory-organizer.generic.profile_warn"), client.font);
                final Runnable[] refresh = new Runnable[1];
                Button yes = Button.builder(Component.translatable("inventory-organizer.profile.new_yes"),
                        b -> { ChestIdentifier.createAndBindProfile(); if (refresh[0] != null) refresh[0].run(); })
                        .bounds(left + iw / 2 - 32, top - 33, 30, 12).build();
                Button no = Button.builder(Component.translatable("inventory-organizer.profile.new_no"),
                        b -> { ChestIdentifier.markCurrentChestAsNothing(); if (refresh[0] != null) refresh[0].run(); })
                        .bounds(left + iw / 2 + 2, top - 33, 44, 12).build();
                refresh[0] = () -> {
                    com.example.inventoryorganizer.config.StoragePreset act = ChestIdentifier.getActiveProfile();
                    yes.visible = yes.active = false; no.visible = no.active = false; warn.visible = false;
                    if (act != null) {
                        label.setMessage(Component.translatable("inventory-organizer.profile.organizing_label", act.getName()));
                        label.visible = true;
                    } else if (!ChestIdentifier.isPromptDismissed()) {
                        label.setMessage(Component.translatable("inventory-organizer.profile.new_prompt"));
                        label.visible = true;
                        yes.visible = yes.active = true; no.visible = no.active = true; warn.visible = true;
                    } else {
                        label.visible = false;
                    }
                };
                net.fabricmc.fabric.api.client.screen.v1.Screens.getWidgets(screen).add(label);
                net.fabricmc.fabric.api.client.screen.v1.Screens.getWidgets(screen).add(warn);
                net.fabricmc.fabric.api.client.screen.v1.Screens.getWidgets(screen).add(yes);
                net.fabricmc.fabric.api.client.screen.v1.Screens.getWidgets(screen).add(no);
                refresh[0].run();
            }

            // Drop the chest context when this screen closes (return to carrying mode), like vanilla chests.
            ScreenEvents.remove(screen).register(s -> ChestIdentifier.clearActive());
        });

        // --- Per-screen keybind dispatch ---
        // When a screen is open, vanilla routes key events to Screen.keyPressed() instead of
        // KeyMapping.click(). So our ClientTickEvents poll of consumeClick() never fires while
        // any screen is open (chest, inv, etc.). We hook ScreenKeyboardEvents on every screen
        // to detect matching key presses and dispatch our handlers manually.
        ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
            net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents.allowKeyPress(screen).register(
                (s, ev) -> {
                    OrganizerConfig cfg = OrganizerConfig.get();
                    String kbMode = cfg.getKeybindMode();
                    if ("disabled".equals(kbMode)) return true;
                    String mode = ("free".equals(kbMode) && ServerEnvironment.canUseFree()) ? "free" : "inventory_only";
                    if (!SORT_KEY.isUnbound() && SORT_KEY.matches(ev)) {
                        handleSortKeybind(client, mode, cfg.getSortAction());
                        return false; // consume
                    }
                    if (!KITS_KEY.isUnbound() && KITS_KEY.matches(ev)) {
                        handleScreenOpenKeybind(client, mode, true);
                        return false;
                    }
                    if (!SETTINGS_KEY.isUnbound() && SETTINGS_KEY.matches(ev)) {
                        handleScreenOpenKeybind(client, mode, false);
                        return false;
                    }
                    // Chest-refill: while a chest is open, top up all ↻-marked slots from it.
                    if (!CHEST_REFILL_KEY.isUnbound() && CHEST_REFILL_KEY.matches(ev) && s instanceof ContainerScreen) {
                        doChestRefill(client);
                        return false;
                    }
                    return true; // pass through
                }
            );
        });

        // --- Configurable HUD overlay ---
        // 26.1 uses HudElementRegistry instead of the deprecated HudRenderCallback. A single element
        // draws every ENABLED piece at its configured fractional position. All pieces are off by
        // default (see HudSettings); the combat indicator additionally shows only during real combat.
        HudElementRegistry.attachElementAfter(
            VanillaHudElements.MISC_OVERLAYS,
            Identifier.fromNamespaceAndPath("inventory-organizer", "inor_hud"),
            (extractor, deltaTracker) -> {
                Minecraft mc = Minecraft.getInstance();
                if (mc.player == null) return;
                com.example.inventoryorganizer.config.HudSettings hud = OrganizerConfig.get().getHud();
                int w = mc.getWindow().getGuiScaledWidth();
                int h = mc.getWindow().getGuiScaledHeight();

                // Combat indicator — only during REAL combat (any server), at the configured spot.
                if (hud.combatEnabled && FightModeTracker.isCombatActive()) {
                    double sc = HudRenderer.clampScale(hud.combatScale);
                    int ew = (int) Math.round(HudRenderer.COMBAT_W * sc), eh = (int) Math.round(HudRenderer.COMBAT_H * sc);
                    int x = HudRenderer.clampPos(hud.combatX, w, ew);
                    int y = HudRenderer.clampPos(hud.combatY, h, eh);
                    String secs = ((FightModeTracker.remainingMs() / 1000) + 1) + "s";
                    HudRenderer.pushScale(extractor, x, y, sc);
                    HudRenderer.drawCombat(extractor, mc.font, 0, 0, secs);
                    HudRenderer.popScale(extractor);
                }
                // Mini-inventory: the 27 main slots.
                if (hud.invEnabled) {
                    double sc = HudRenderer.clampScale(hud.invScale);
                    int ew = (int) Math.round(HudRenderer.MINI_W * sc), eh = (int) Math.round(HudRenderer.MINI_H * sc);
                    int x = HudRenderer.clampPos(hud.invX, w, ew);
                    int y = HudRenderer.clampPos(hud.invY, h, eh);
                    HudRenderer.pushScale(extractor, x, y, sc);
                    HudRenderer.drawMiniInv(extractor, mc.font, 0, 0, HudRenderer.mainSlots(mc.player));
                    HudRenderer.popScale(extractor);
                }
                // Worn set (+ optional offhand, optional durability bars).
                if (hud.setEnabled) {
                    int count = hud.setOffhand ? 5 : 4;
                    double sc = HudRenderer.clampScale(hud.setScale);
                    int ew = (int) Math.round(HudRenderer.setW(hud.setVertical, count) * sc);
                    int eh = (int) Math.round(HudRenderer.setH(hud.setVertical, count) * sc);
                    int x = HudRenderer.clampPos(hud.setX, w, ew);
                    int y = HudRenderer.clampPos(hud.setY, h, eh);
                    HudRenderer.pushScale(extractor, x, y, sc);
                    HudRenderer.drawSet(extractor, mc.font, 0, 0, hud.setVertical, hud.setDurability,
                            count, HudRenderer.setSlots(mc.player, hud.setOffhand));
                    HudRenderer.popScale(extractor);
                }
            }
        );
    }

    // Result of looking at a modded container screen: how InOr should treat it.
    static final int CONTAINER_NONE = 0;     // not a storage GUI (machine / unknown) → ignore silently
    static final int CONTAINER_SPECIAL = -1; // storage-sized but has special/restricted slots → can't sort

    /**
     * Classify a modded container screen. Returns:
     * <ul>
     *   <li>a positive number = PLAIN storage of that many slots (safe to sort + profile);</li>
     *   <li>{@link #CONTAINER_SPECIAL} (-1) = looks like storage but has special/function slots (e.g. an
     *       "Echo Chest" with an upgrade/charge slot) → we must NOT reorder it, just tell the user;</li>
     *   <li>{@link #CONTAINER_NONE} (0) = not a storage GUI at all (furnace/brewing/machine, or no player
     *       inventory tail) → ignore silently.</li>
     * </ul>
     * Rule: the last 36 slots must be the player inventory. The remaining "storage" slots (≥9, or it's a
     * small machine → NONE) are PLAIN only if EVERY one is an ordinary slot — full stack size and accepts
     * an arbitrary item. Any restricted/upgrade/filter/output slot ⇒ SPECIAL.
     */
    private static int classifyContainer(AbstractContainerScreen<?> acs) {
        try {
            AbstractContainerMenu menu = acs.getMenu();
            java.util.List<net.minecraft.world.inventory.Slot> slots = menu.slots;
            int n = slots.size();
            if (n < 36 + 1) return CONTAINER_NONE;
            int storage = n - 36;
            // The last 36 must be the player inventory; the storage block must NOT be.
            for (int i = n - 36; i < n; i++) {
                if (!(slots.get(i).container instanceof net.minecraft.world.entity.player.Inventory)) return CONTAINER_NONE;
            }
            for (int i = 0; i < storage; i++) {
                if (slots.get(i).container instanceof net.minecraft.world.entity.player.Inventory) return CONTAINER_NONE;
            }
            if (storage < 9 || storage > 9 * 13) return CONTAINER_NONE; // tiny = machine; absurd = bail
            // Every storage slot must be an ordinary, unrestricted full-stack slot, or it's "special".
            ItemStack probe = new ItemStack(Items.COBBLESTONE);
            for (int i = 0; i < storage; i++) {
                net.minecraft.world.inventory.Slot s = slots.get(i);
                if (s.getMaxStackSize() < 64) return CONTAINER_SPECIAL;     // upgrade/charge/filter slot
                try { if (!s.mayPlace(probe)) return CONTAINER_SPECIAL; }    // input/output/restricted slot
                catch (Throwable t) { return CONTAINER_SPECIAL; }
            }
            return storage; // PLAIN storage
        } catch (Throwable t) {
            return CONTAINER_NONE;
        }
    }

    /**
     * SORT keybind handler. The action setting decides what the keybind does:
     *   "oi_only"  — always sort player inventory only
     *   "ost_only" — always sort storage container only (no-op if no chest screen open)
     *   "smart"    — chest screen open → sort chest; else sort player inv (default)
     *   "both"     — chest screen open → sort chest AND sort player inv; else sort player inv
     */
    private static void handleSortKeybind(Minecraft client, String mode, String action) {
        if (client.player == null || client.gameMode == null) return;
        boolean chestOpen = client.screen instanceof ContainerScreen;
        switch (action) {
            case "oi_only":
                doOI(client, mode);
                break;
            case "ost_only":
                doOST(client);
                break;
            case "both":
                if (chestOpen) {
                    // Capture the chest hit result NOW before any close happens — we need it to reopen later.
                    net.minecraft.world.phys.BlockHitResult chestHit = lastChestHitResult;
                    doOST(client); // sort chest contents (chest stays open after this)
                    // OI part: properly close chest, sort player inv, then reopen the chest by simulating use-key.
                    boolean canOI = !FightModeTracker.isActive() || FightModeTracker.canUseOI();
                    if (canOI) {
                        client.player.closeContainer(); // proper server-side close (sends close packet + resets containerMenu)
                        if (FightModeTracker.isActive()) {
                            FightModeTracker.markOIUsed();
                            InventorySorter.sortInventoryFightMode();
                        } else {
                            InventorySorter.sortInventory();
                        }
                        // Reopen the chest as if user pressed their Use key (right-click) on it.
                        if (chestHit != null) {
                            client.gameMode.useItemOn(client.player, net.minecraft.world.InteractionHand.MAIN_HAND, chestHit);
                        }
                    }
                } else {
                    doOI(client, mode);
                }
                break;
            case "smart":
            default:
                if (chestOpen) doOST(client);
                else doOI(client, mode);
                break;
        }
    }

    // Auto-refill: last non-empty Item seen in each marked slot, so we know what to refill it with.
    private static final net.minecraft.world.item.Item[] refillLastItem = new net.minecraft.world.item.Item[36];
    private static long lastRefillMs = 0L;
    private static final long REFILL_COOLDOWN_MS = 120L;
    private static String lastRefillNote = "";
    private static long lastRefillNoteMs = 0L;

    /** Throttled action-bar diagnostic for the refill (so the user can see what it's doing). */
    private static void refillNote(Minecraft client, net.minecraft.network.chat.Component msg) {
        long now = System.currentTimeMillis();
        if (client.gui == null) return;
        String key = msg.getString();
        if (key.equals(lastRefillNote) && now - lastRefillNoteMs < 2000L) return;
        lastRefillNote = key;
        lastRefillNoteMs = now;
        client.gui.setOverlayMessage(msg, false);
    }

    /**
     * Auto-refill marked slots in free mode. When an empty marked slot is found, it's refilled with a
     * matching item (the slot's rule if specific, else the last item that was there). The source is the
     * OPEN CHEST if one is open (so you can stock up just by opening the chest), otherwise the rest of
     * your inventory. Hotbar targets use a single SWAP click (reliable, no cursor); main-inventory
     * targets use pickup/place. One refill per cooldown.
     */
    private static void tickAutoRefill(Minecraft client) {
        if (client.player == null) return;
        // Works during normal play AND with the survival inventory or a chest open (acts on the open menu
        // so it never desyncs the chest). Other screens (creative, anvil…) are skipped.
        if (!refillTrashScreenOk(client)) return;

        OrganizerConfig cfg = OrganizerConfig.get();
        if (!cfg.isAutoRefillEnabled()) return; // master switch off (toggled by REFILL_TOGGLE_KEY)
        int markedCount = 0;
        for (int i = 0; i < 36; i++) if (cfg.isSlotRefill(i)) markedCount++;
        if (markedCount == 0) return;
        if (!ServerEnvironment.canUseFree()) { refillNote(client, Component.translatable("inventory-organizer.refill.free_only")); return; }

        net.minecraft.world.entity.player.Inventory inv = client.player.getInventory();
        net.minecraft.world.inventory.AbstractContainerMenu menu = client.player.containerMenu;
        if (menu == null) return;
        int syncId = menu.containerId;
        long now = System.currentTimeMillis();
        if (now - lastRefillMs < REFILL_COOLDOWN_MS) return;
        if (!menu.getCarried().isEmpty()) return; // cursor busy

        for (int slot = 0; slot < 36; slot++) {
            if (!cfg.isSlotRefill(slot)) { refillLastItem[slot] = null; continue; }
            ItemStack here = inv.getItem(slot);
            if (!here.isEmpty()) { refillLastItem[slot] = here.getItem(); continue; } // remember what belongs here

            // Empty marked slot → find a matching source in the inventory. Rule-based first
            // (e.g. t:sword → any sword), else the last item that was here.
            String ruleText = cfg.getSlotRule(slot).toText();
            boolean ruleSpecific = !(ruleText.equals("any") || ruleText.equals("empty") || ruleText.isEmpty());
            java.util.List<String> ruleOne = ruleSpecific ? java.util.List.of(ruleText) : null;
            net.minecraft.world.item.Item last = refillLastItem[slot];
            if (ruleOne == null && last == null) continue;

            int src = findInvRefillSource(inv, slot, ruleOne, last);
            if (src < 0) continue;

            int fromMenuSlot = playerSlotInOpenMenu(menu, inv, src);
            if (fromMenuSlot < 0) continue;

            if (slot <= 8) {
                // Hotbar target → one atomic SWAP (button = hotbar index = the target slot). Valid in any menu.
                client.gameMode.handleContainerInput(syncId, fromMenuSlot, slot,
                        net.minecraft.world.inventory.ContainerInput.SWAP, client.player);
                refillNote(client, Component.translatable("inventory-organizer.refill.slot_done", slot));
            } else {
                // Main-inventory target → pickup source, place at target (both mapped to the open menu).
                int toMenuSlot = playerSlotInOpenMenu(menu, inv, slot);
                if (toMenuSlot < 0) continue;
                client.gameMode.handleContainerInput(syncId, fromMenuSlot, 0, net.minecraft.world.inventory.ContainerInput.PICKUP, client.player);
                client.gameMode.handleContainerInput(syncId, toMenuSlot, 0, net.minecraft.world.inventory.ContainerInput.PICKUP, client.player);
                if (!menu.getCarried().isEmpty()) {
                    client.gameMode.handleContainerInput(syncId, fromMenuSlot, 0, net.minecraft.world.inventory.ContainerInput.PICKUP, client.player);
                }
            }
            lastRefillMs = now;
            return; // one refill per tick
        }
    }

    /**
     * Inventory slot (0-35, excl. target) holding a refill match. Tier-aware: it pulls from the
     * LOWEST-priority slot first — an untiered slot, else the highest tier NUMBER (tier 1 = best, kept
     * last) — so restocking a slot never cannibalises a higher-ranked one. Among equal priority it
     * prefers a non-refill-marked slot, so we don't empty another slot that itself wants to stay stocked.
     */
    private static int findInvRefillSource(net.minecraft.world.entity.player.Inventory inv, int targetSlot,
                                           java.util.List<String> ruleOne, net.minecraft.world.item.Item last) {
        OrganizerConfig cfg = OrganizerConfig.get();
        java.util.List<Integer> cand = new java.util.ArrayList<>();
        for (int i = 0; i < 36; i++) {
            if (i != targetSlot && refillMatches(inv.getItem(i), ruleOne, last)) cand.add(i);
        }
        if (cand.isEmpty()) return -1;
        cand.sort((a, b) -> {
            // 1) unmarked slots before refill-marked ones (don't drain another stocked slot)
            int ma = cfg.isSlotRefill(a) ? 1 : 0, mb = cfg.isSlotRefill(b) ? 1 : 0;
            if (ma != mb) return Integer.compare(ma, mb);
            // 2) lowest priority first = highest tier number first (untiered = MAX = most preferred)
            int ta = slotTier(cfg, a), tb = slotTier(cfg, b);
            if (ta != tb) return Integer.compare(tb, ta);
            // 3) stable: lower main-inv slot index first (matches the old 9-35 then 0-8 feel)
            return Integer.compare(a < 9 ? a + 100 : a, b < 9 ? b + 100 : b);
        });
        return cand.get(0);
    }

    /** Configured tier number for a slot (1 = best). Untiered slots return MAX_VALUE (lowest priority). */
    private static int slotTier(OrganizerConfig cfg, int slot) {
        String[] t = cfg.getPreference("tier_order");
        if (t == null) return Integer.MAX_VALUE;
        String pre = "slot_" + slot + "_tier_";
        for (String e : t) if (e.startsWith(pre)) {
            try { return Integer.parseInt(e.substring(pre.length())); } catch (NumberFormatException ignored) {}
        }
        return Integer.MAX_VALUE;
    }

    private static boolean refillMatches(ItemStack s, java.util.List<String> ruleOne, net.minecraft.world.item.Item last) {
        if (s.isEmpty()) return false;
        if (ruleOne != null) return ruleMatchesItem(ruleOne.get(0), s);
        return s.getItem() == last;
    }

    /**
     * True when {@code rule} matches the stack. Understands {@code cg:} (custom/materialized groups),
     * which {@link com.example.inventoryorganizer.SortLogic#matchRank} does not — that gap was why
     * refill stopped working for the built-in groups (e.g. cg:blocks) after they were materialized.
     */
    static boolean ruleMatchesItem(String rule, ItemStack s) {
        if (s.isEmpty() || rule == null || rule.isEmpty()) return false;
        // BOTH cg: and g: resolve against the group's ACTUAL edited member list. The built-in groups are
        // materialized as real custom groups, so "g:blocks" and "cg:blocks" mean the same group's members.
        // This is why a chest/refill/trash respects exactly what you put in a group (e.g. a chest you added
        // to "blocks", or a vanilla axe added to "weapons") — instead of the SortLogic heuristic, which
        // ignores edits, excludes non-cube blocks like chests, and depends on flaky 26.1 item tags.
        if (rule.startsWith("cg:") || rule.startsWith("g:")) {
            String name = rule.substring(rule.indexOf(':') + 1);
            java.util.List<String> members = OrganizerConfig.get().getCustomGroup(name);
            if (!members.isEmpty()) {
                String id = com.example.inventoryorganizer.SortLogic.getItemId(s);
                String path = id.contains(":") ? id.substring(id.indexOf(':') + 1) : id;
                return members.contains(id) || members.contains(path);
            }
            // Group not materialized as a custom group (e.g. g:misc catch-all) → heuristic fallback.
        }
        return com.example.inventoryorganizer.SortLogic.matchRank(java.util.List.of(rule), s) != Integer.MAX_VALUE;
    }

    private static long lastTrashMs = 0L;
    private static final long TRASH_COOLDOWN_MS = 100L;

    /**
     * Drop any inventory item matching the user's trash/void list. Free mode only. Acts on the currently
     * open menu (works with no screen, the survival inventory, or a chest open — never desyncing the
     * chest). One drop per cooldown to stay gentle.
     */
    private static void tickTrash(Minecraft client) {
        if (client.player == null || !refillTrashScreenOk(client)) return;
        if (!ServerEnvironment.canUseFree()) return;
        java.util.List<String> trash = OrganizerConfig.get().getTrashRules();
        if (trash.isEmpty()) return;
        long now = System.currentTimeMillis();
        if (now - lastTrashMs < TRASH_COOLDOWN_MS) return;
        net.minecraft.world.inventory.AbstractContainerMenu menu = client.player.containerMenu;
        if (menu == null || !menu.getCarried().isEmpty()) return;

        boolean overflowOnly = OrganizerConfig.get().isTrashOverflowOnly();
        net.minecraft.world.entity.player.Inventory inv = client.player.getInventory();
        int syncId = menu.containerId;
        for (int slot = 0; slot < 36; slot++) {
            ItemStack s = inv.getItem(slot);
            if (s.isEmpty()) continue;
            // Match via ruleMatchesItem (NOT SortLogic.matchRank): it resolves cg: against the group's
            // ACTUAL edited member list, while matchRank uses the tag/heuristic that ignores user edits.
            boolean isTrash = false;
            for (String rule : trash) { if (ruleMatchesItem(rule, s)) { isTrash = true; break; } }
            if (!isTrash) continue;
            // Overflow mode: keep the single fullest slot of this item, only drop the others.
            if (overflowOnly && slot == fullestSlotOf(inv, s.getItem())) continue;
            int menuSlot = playerSlotInOpenMenu(menu, inv, slot);
            if (menuSlot < 0) continue;
            // Drop the whole stack (THROW, button 1 = full stack) from this slot, via the open menu.
            client.gameMode.handleContainerInput(syncId, menuSlot, 1,
                    net.minecraft.world.inventory.ContainerInput.THROW, client.player);
            lastTrashMs = now;
            return; // one drop per tick
        }
    }

    /** Inventory slot (0-35) holding the most of {@code item} (first on ties), or -1 if none. */
    private static int fullestSlotOf(net.minecraft.world.entity.player.Inventory inv, net.minecraft.world.item.Item item) {
        int best = -1, bestCount = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack s = inv.getItem(i);
            if (!s.isEmpty() && s.getItem() == item && s.getCount() > bestCount) { bestCount = s.getCount(); best = i; }
        }
        return best;
    }

    private static long lastLootMs = 0L;
    private static int lastLootContainerId = -1;
    private static final long LOOT_COOLDOWN_MS = 55L;

    /** True while either Shift key is physically held (26.1 has no Screen.hasShiftDown()). */
    private static boolean isShiftDown(Minecraft client) {
        return InputConstants.isKeyDown(client.getWindow(), org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT_SHIFT)
                || InputConstants.isKeyDown(client.getWindow(), org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT_SHIFT);
    }

    /**
     * Scroll handler for container screens (driven by {@link com.example.inventoryorganizer.mixin.ContainerScrollMixin}).
     * In a chest: Shift+scroll over a slot loots it (quick-move). In the player inventory: a plain
     * scroll over a slot sorts one item into place. Free mode only. Returns true to consume the scroll.
     */
    public static boolean handleContainerScroll(net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?> screen, double vAmount) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null) return false;
        // Never hijack the scroll wheel in the creative inventory: it's used to scroll the item list /
        // pull items out, so we leave it entirely to vanilla.
        if (screen instanceof net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen) return false;
        if (!ServerEnvironment.canUseFree()) return false;
        boolean shift = isShiftDown(client);

        if (shift) {
            // Shift+scroll = OI (organize inventory), one item per notch. Only safe when the player
            // inventory is the active container (a chest's active menu isn't the inventoryMenu).
            if (screen instanceof InventoryScreen) return tryInventoryScrollSort(client);
            return false;
        }

        // Plain scroll: if the cursor is over an item, quick-move THAT item (hover override). Otherwise
        // do a directional one-item transfer between the screen's two halves (see tryDirectionalScroll).
        net.minecraft.world.inventory.Slot hovered =
                ((com.example.inventoryorganizer.mixin.ContainerScreenAccessor) screen).inorGetHoveredSlot();
        if (hovered != null && hovered.hasItem()) {
            return tryLootScroll(client, hovered);
        }
        return tryDirectionalScroll(client, screen, vAmount > 0);
    }

    /**
     * Directional scroll (no item under the cursor): move ONE item between the screen's upper and lower
     * halves with a shift-click, one per notch.
     * <ul>
     *   <li><b>Chest:</b> scroll up deposits a player item into the chest; scroll down withdraws the
     *       chest's lowest item into the inventory.</li>
     *   <li><b>Inventory:</b> the hotbar is the lower half, the main inventory the upper half. Scroll up
     *       sends the leftmost hotbar item up to the main inventory; scroll down sends the lowest main
     *       item down to the hotbar.</li>
     * </ul>
     */
    private static boolean tryDirectionalScroll(Minecraft client,
            net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?> screen, boolean up) {
        net.minecraft.world.inventory.AbstractContainerMenu menu = client.player.containerMenu;
        if (menu == null) return false;
        int size = menu.slots.size();
        int target = -1;

        if (screen instanceof InventoryScreen) {
            // InventoryMenu: 9-35 main inventory (upper), 36-44 hotbar (lower).
            if (up) {                                   // lower → upper: leftmost hotbar item
                for (int i = 36; i <= 44 && i < size; i++) if (menu.slots.get(i).hasItem()) { target = i; break; }
            } else {                                    // upper → lower: lowest main-inventory item
                for (int i = 35; i >= 9; i--) if (i < size && menu.slots.get(i).hasItem()) { target = i; break; }
            }
        } else {
            // Chest menu: [0, chestSize) chest (upper), [chestSize, size) player inventory (lower).
            int chestSize = size - 36;
            if (chestSize <= 0) return false;
            if (up) {                                   // deposit: first player item (hotbar first, then main)
                for (int i = chestSize + 27; i < size; i++) if (menu.slots.get(i).hasItem()) { target = i; break; }
                if (target < 0) for (int i = chestSize; i < chestSize + 27 && i < size; i++)
                    if (menu.slots.get(i).hasItem()) { target = i; break; }
            } else {                                    // withdraw: lowest chest item
                for (int i = chestSize - 1; i >= 0; i--) if (menu.slots.get(i).hasItem()) { target = i; break; }
            }
        }
        if (target < 0) return false; // nothing to move → let vanilla handle the scroll (e.g. recipe book)

        int syncId = menu.containerId;
        if (syncId != lastLootContainerId) { lastLootContainerId = syncId; lastLootMs = 0L; }
        long now = System.currentTimeMillis();
        if (now - lastLootMs < LOOT_COOLDOWN_MS) return true; // consume, just don't spam
        lastLootMs = now;
        client.gameMode.handleContainerInput(syncId, target, 0,
                net.minecraft.world.inventory.ContainerInput.QUICK_MOVE, client.player);
        return true;
    }

    /**
     * Shift-scroll looting: quick-move the hovered slot (vanilla shift-click semantics, so a chest slot
     * flies to the inventory and vice-versa). One slot per scroll notch, lightly rate-limited.
     */
    private static boolean tryLootScroll(Minecraft client, net.minecraft.world.inventory.Slot slot) {
        if (slot == null || !slot.hasItem()) return false;
        int syncId = client.player.containerMenu.containerId;
        // The first scroll in a freshly opened container must never be eaten by the anti-spam cooldown
        // (which carries over from the previous container) — reset it when the container changes. This
        // is the "first use doesn't react, then works" case, most visible on servers.
        if (syncId != lastLootContainerId) {
            lastLootContainerId = syncId;
            lastLootMs = 0L;
        }
        long now = System.currentTimeMillis();
        if (now - lastLootMs < LOOT_COOLDOWN_MS) return true; // still consume, just don't spam moves
        lastLootMs = now;
        client.gameMode.handleContainerInput(syncId, slot.index, 0,
                net.minecraft.world.inventory.ContainerInput.QUICK_MOVE, client.player);
        return true;
    }

    private static long lastInvScrollSortMs = 0L;
    private static final long INV_SCROLL_SORT_COOLDOWN_MS = 60L;

    /**
     * Inventory scroll-sort: move ONE out-of-place item into its configured slot per scroll notch
     * (reuses the one-swap path used by fight mode). Lightly rate-limited. Returns true if handled.
     */
    private static boolean tryInventoryScrollSort(Minecraft client) {
        if (client.player == null) return false;
        long now = System.currentTimeMillis();
        if (now - lastInvScrollSortMs < INV_SCROLL_SORT_COOLDOWN_MS) return true; // consume, skip
        lastInvScrollSortMs = now;
        InventorySorter.sortInventoryFightMode(); // performs exactly one swap
        return true;
    }

    /**
     * Manual chest-refill (button / keybind): top up EVERY ↻-marked slot to a full stack, pulling the
     * matching item from the open chest. Applies to all marked slots at once. Free mode only.
     */
    private static void doChestRefill(Minecraft client) {
        if (client.player == null || !(client.screen instanceof ContainerScreen)) return;
        if (!ServerEnvironment.canUseFree()) {
            refillNote(client, Component.translatable("inventory-organizer.refill.free_only"));
            return;
        }
        OrganizerConfig cfg = OrganizerConfig.get();
        AbstractContainerMenu menu = client.player.containerMenu;
        int chestSize = menu.slots.size() - 36;
        if (chestSize <= 0) return;
        int syncId = menu.containerId;
        net.minecraft.world.entity.player.Inventory inv = client.player.getInventory();
        int filled = 0;

        for (int slot = 0; slot < 36; slot++) {
            if (!cfg.isSlotRefill(slot)) continue;
            if (!inv.getItem(slot).isEmpty()) continue; // only fill EMPTY marked slots (no cursor-heavy top-up)
            if (!menu.getCarried().isEmpty()) break;    // safety: never act with a busy cursor

            // What to refill with: the slot rule if specific, else the last item that was here.
            String ruleText = cfg.getSlotRule(slot).toText();
            boolean ruleSpecific = !(ruleText.equals("any") || ruleText.equals("empty") || ruleText.isEmpty());
            java.util.List<String> ruleOne = ruleSpecific ? java.util.List.of(ruleText) : null;
            net.minecraft.world.item.Item last = refillLastItem[slot];
            if (ruleOne == null && last == null) continue;

            // Pick the LARGEST matching chest stack (so the slot gets as full a stack as possible).
            int best = -1, bestCount = 0;
            for (int c = 0; c < chestSize; c++) {
                ItemStack s = menu.getSlot(c).getItem();
                if (s.isEmpty()) continue;
                boolean match = (ruleOne != null)
                        ? ruleMatchesItem(ruleOne.get(0), s)
                        : s.getItem() == last;
                if (match && s.getCount() > bestCount) { bestCount = s.getCount(); best = c; }
            }
            if (best < 0) continue;

            if (slot <= 8) {
                // Hotbar target → one clean SWAP (no cursor involved).
                client.gameMode.handleContainerInput(syncId, best, slot, net.minecraft.world.inventory.ContainerInput.SWAP, client.player);
            } else {
                // Main-inventory target → pickup + place into the empty target.
                int target = invToChestScreenSlot(slot, chestSize);
                client.gameMode.handleContainerInput(syncId, best, 0, net.minecraft.world.inventory.ContainerInput.PICKUP, client.player);
                client.gameMode.handleContainerInput(syncId, target, 0, net.minecraft.world.inventory.ContainerInput.PICKUP, client.player);
                if (!menu.getCarried().isEmpty()) {
                    client.gameMode.handleContainerInput(syncId, best, 0, net.minecraft.world.inventory.ContainerInput.PICKUP, client.player);
                }
            }
            filled++;
        }

        // Safety net: never leave anything on the cursor (that's what landed items in "random" slots).
        if (!menu.getCarried().isEmpty()) {
            for (int i = 0; i < menu.slots.size() && !menu.getCarried().isEmpty(); i++) {
                if (menu.getSlot(i).getItem().isEmpty()) {
                    client.gameMode.handleContainerInput(syncId, i, 0, net.minecraft.world.inventory.ContainerInput.PICKUP, client.player);
                }
            }
        }
        refillNote(client, filled > 0
                ? Component.translatable("inventory-organizer.refill.chest_done", filled)
                : Component.translatable("inventory-organizer.refill.no_marked"));
    }

    /**
     * Deposit every inventory stack whose item already exists in the open chest (vanilla-style "deposit
     * all matching"). Free mode only. Uses QUICK_MOVE so partial stacks merge.
     */
    private static void doDepositAllMatching(Minecraft client) {
        if (client.player == null || !(client.screen instanceof ContainerScreen)) return;
        if (!ServerEnvironment.canUseFree()) return;
        AbstractContainerMenu menu = client.player.containerMenu;
        int total = menu.slots.size();
        int chestSize = total - 36;
        if (chestSize <= 0) return;
        // Collect the item types currently in the chest.
        java.util.HashSet<net.minecraft.world.item.Item> inChest = new java.util.HashSet<>();
        for (int c = 0; c < chestSize; c++) {
            ItemStack s = menu.getSlot(c).getItem();
            if (!s.isEmpty()) inChest.add(s.getItem());
        }
        if (inChest.isEmpty()) return;
        int syncId = menu.containerId;
        // Quick-move each player slot whose item the chest already holds.
        for (int ps = chestSize; ps < total; ps++) {
            ItemStack s = menu.getSlot(ps).getItem();
            if (s.isEmpty() || !inChest.contains(s.getItem())) continue;
            client.gameMode.handleContainerInput(syncId, ps, 0,
                    net.minecraft.world.inventory.ContainerInput.QUICK_MOVE, client.player);
        }
    }

    /** Screen-slot index of a player inventory slot (0-35) inside an open chest menu of {@code chestSize}. */
    private static int invToChestScreenSlot(int invSlot, int chestSize) {
        if (invSlot >= 0 && invSlot <= 8) return chestSize + 27 + invSlot; // hotbar last
        return chestSize + (invSlot - 9);                                  // main inventory rows
    }

    /**
     * Menu-slot index for a player inventory slot (0-35) in the CURRENTLY OPEN menu, or -1 if not found.
     * Works for any menu — the survival inventory, an open chest/container, or the default (no-screen)
     * menu — by matching the slot's actual backing container + container-slot, so sending clicks here
     * targets the right menu and never desyncs an open chest.
     */
    private static int playerSlotInOpenMenu(AbstractContainerMenu menu,
                                            net.minecraft.world.entity.player.Inventory inv, int playerSlot) {
        for (int i = 0; i < menu.slots.size(); i++) {
            net.minecraft.world.inventory.Slot sl = menu.slots.get(i);
            if (sl.container == inv && sl.getContainerSlot() == playerSlot) return i;
        }
        return -1;
    }

    /** True if auto-refill/trash may act with the current screen: no screen, survival inventory, or a
     *  chest/container. Other screens (creative, anvil, crafting table…) are left alone. */
    private static boolean refillTrashScreenOk(Minecraft client) {
        net.minecraft.client.gui.screens.Screen s = client.screen;
        return s == null
                || s instanceof net.minecraft.client.gui.screens.inventory.InventoryScreen
                || s instanceof ContainerScreen;
    }

    private static int presetCycleIndex = 0;

    /** Load the next saved Kit (slot-config preset), wrapping around, and show its name on the action bar. */
    private static void doCyclePreset(Minecraft client) {
        if (client.player == null) return;
        OrganizerConfig cfg = OrganizerConfig.get();
        java.util.List<com.example.inventoryorganizer.config.Kit> kits = cfg.getKits();
        if (kits.isEmpty()) {
            if (client.gui != null) client.gui.setOverlayMessage(Component.translatable("inventory-organizer.preset.none"), false);
            return;
        }
        presetCycleIndex = (presetCycleIndex + 1) % kits.size();
        com.example.inventoryorganizer.config.Kit kit = kits.get(presetCycleIndex);
        cfg.loadKit(kit, false);
        cfg.save();
        if (client.gui != null) client.gui.setOverlayMessage(Component.translatable("inventory-organizer.preset.loaded", kit.getName()), false);
    }

    /** Sort player inventory (mirrors InventoryScreenMixin OI button logic, including fight-mode gating). */
    private static void doOI(Minecraft client, String mode) {
        // Context guard
        if ("inventory_only".equals(mode)) {
            if (!(client.screen instanceof InventoryScreen)) return;
        } else { // "free"
            // If a non-player container is open (chest, shulker, etc.), close it properly first —
            // server rejects clicks against the inventoryMenu sync id while another container is active.
            // closeContainer() sends ServerboundContainerClose and resets containerMenu, like pressing ESC.
            // (setScreen(null) alone is buggy: client clears screen but server still thinks the container is open.)
            if (client.screen instanceof AbstractContainerScreen<?>
                    && !(client.screen instanceof InventoryScreen)) {
                client.player.closeContainer();
            }
        }
        if (FightModeTracker.isActive()) {
            if (!FightModeTracker.canUseOI()) return;
            FightModeTracker.markOIUsed();
            Button btn = FightModeTracker.oiButtonRef;
            if (btn != null) btn.active = false;
            InventorySorter.sortInventoryFightMode();
        } else {
            InventorySorter.sortInventory();
        }
    }

    /** Sort the currently open storage container. No-op unless a chest-style screen is open. */
    private static void doOST(Minecraft client) {
        if (!(client.screen instanceof ContainerScreen gcs)) return;
        if (tryWarehouseGroupSort()) return; // linked chest → sort the whole warehouse instead
        AbstractContainerMenu sh = client.player.containerMenu;
        int syncId = sh.containerId;
        int containerSize = gcs.getMenu().getRowCount() * 9;
        StorageSorter.sortContainer(containerSize, syncId);
    }

    /**
     * If the open chest belongs to a warehouse link, ask the server to sort the WHOLE group and
     * return true (so the caller skips the single-chest OST). Else return false.
     */
    private static boolean tryWarehouseGroupSort() {
        if (!WarehouseClient.isAvailable()) return false;
        java.util.List<int[]> positions = ChestIdentifier.getCurrentPositions();
        if (positions.isEmpty()) return false;
        // Foreign link (another player's warehouse, revealed to us): the server sorts the whole link
        // using the owner's stored rules — we just trigger it by any of its positions.
        if (ChestIdentifier.isCurrentChestForeignLink()) {
            net.minecraft.core.BlockPos c = ChestIdentifier.canonicalPos();
            if (c != null) { WarehouseClient.sortLink(c); return true; }
        }
        OrganizerConfig cfg = OrganizerConfig.get();
        for (int[] p : positions) {
            com.example.inventoryorganizer.config.WarehouseGroup g = cfg.findWarehouseGroupFor(p[0], p[1], p[2]);
            if (g != null && g.getPositions().size() >= 2) {
                java.util.List<net.minecraft.core.BlockPos> bps = new java.util.ArrayList<>();
                for (int[] q : g.getPositions()) bps.add(new net.minecraft.core.BlockPos(q[0], q[1], q[2]));
                WarehouseClient.requestSort(bps);
                return true;
            }
        }
        return false;
    }

    /** Kits / Settings keybind: open the corresponding screen. */
    private static void handleScreenOpenKeybind(Minecraft client, String mode, boolean isKits) {
        if (client.player == null) return;
        if ("inventory_only".equals(mode)) {
            // Only open from inventory screen, matching the K/S button availability.
            if (!(client.screen instanceof InventoryScreen)) return;
        }
        // In FREE mode the screen can open from anywhere — user keeps it open and closes with ESC.
        net.minecraft.client.gui.screens.Screen parent = client.screen;
        if (isKits) {
            client.setScreen(new KitsScreen(parent, false));
        } else {
            client.setScreen(new VisualInventoryConfigScreen(parent));
        }
    }
}
