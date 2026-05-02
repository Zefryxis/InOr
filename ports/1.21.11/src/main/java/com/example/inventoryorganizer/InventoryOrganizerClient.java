package com.example.inventoryorganizer;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
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
                // getAttacker() returns whoever last damaged this entity (player or their projectile owner)
                net.minecraft.entity.Entity attacker = client.player.getAttacker();
                if (attacker instanceof PlayerEntity) {
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
