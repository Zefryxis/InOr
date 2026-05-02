package com.example.inventoryorganizer;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.GenericContainerScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;

public class InventoryOrganizerClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {

        // --- Fight Mode: detect being hit by a player ---
        // hurtTime jumps when damaged; we verify the attacker is a PlayerEntity.
        int[] prevHurtTime = {0};
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.player == null) { prevHurtTime[0] = 0; return; }

            // Re-enable OI button once the 130ms cooldown expires (replaces removed tick() inject)
            ButtonWidget btn = FightModeTracker.oiButtonRef;
            if (btn != null && !btn.active && FightModeTracker.canUseOI()) {
                btn.active = true;
            }

            int cur = client.player.hurtTime;
            if (cur > prevHurtTime[0]) {
                // getAttacker() returns whoever last damaged this entity (player or their projectile owner).
                // Guard against stale references: require the attacker to be alive and within a plausible
                // combat range, otherwise non-PvP damage (fall, lava, mobs) after a prior PvP event
                // would incorrectly re-trigger fight mode.
                net.minecraft.entity.Entity attacker = client.player.getAttacker();
                if (attacker instanceof PlayerEntity && attacker.isAlive() && !attacker.isRemoved()
                        && attacker.squaredDistanceTo(client.player) < 4096.0) {
                    FightModeTracker.triggerCombat();
                }
            }
            prevHurtTime[0] = cur;
        });

        // --- Fight Mode: detect attacking another player ---
        AttackEntityCallback.EVENT.register((player, world, hand, entity, hitResult) -> {
            if (entity instanceof PlayerEntity) {
                FightModeTracker.triggerCombat();
            }
            return ActionResult.PASS;
        });

        // --- OST button on GenericContainerScreen (chests, shulkers, etc.) ---
        // Uses ScreenEvents instead of a mixin — GenericContainerScreen inherits init()
        // from HandledScreen and doesn't redeclare it, so Mixin can't target it directly.
        ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
            if (!(screen instanceof GenericContainerScreen gcs)) return;

            int rows = gcs.getScreenHandler().getRows();
            int bgHeight = 114 + rows * 18;
            int guiW = 176;
            int btnX = (screen.width - guiW) / 2 + guiW - 30;
            int panelY = (screen.height - bgHeight) / 2;
            int btnY = panelY - 18;

            ButtonWidget ostButton = ButtonWidget.builder(
                Text.translatable("inventory-organizer.button.ost"),
                button -> {
                    MinecraftClient mc = MinecraftClient.getInstance();
                    if (mc.player != null) {
                        ScreenHandler sh = mc.player.currentScreenHandler;
                        int syncId = sh.syncId;
                        int containerSize = gcs.getScreenHandler().getRows() * 9;
                        StorageSorter.sortContainer(containerSize, syncId);
                    }
                }
            ).dimensions(btnX, btnY, 28, 14).build();
            net.fabricmc.fabric.api.client.screen.v1.Screens.getButtons(screen).add(ostButton);
        });

        // --- Fight Mode: HUD icon (top-left corner) ---
        HudRenderCallback.EVENT.register((context, tickCounter) -> {
            if (!FightModeTracker.isActive()) return;
            MinecraftClient client = MinecraftClient.getInstance();
            if (client.player == null) return;
            long remaining = FightModeTracker.remainingMs();

            int ix = 4, iy = 4;
            // Semi-transparent red background behind icon
            context.fill(ix - 2, iy - 2, ix + 18, iy + 18, 0xAAFF2222);
            // Sword icon
            context.drawItem(new ItemStack(Items.IRON_SWORD), ix, iy);
            // Countdown text next to icon
            String secs = ((remaining / 1000) + 1) + "s";
            context.drawTextWithShadow(client.textRenderer, Text.literal(secs), ix + 20, iy + 4, 0xFFFF4444);
        });
    }
}
