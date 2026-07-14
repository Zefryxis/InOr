package com.example.inventoryorganizer.mixin;

import com.example.inventoryorganizer.client.SwitchToolHandler;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Prevents the attack cooldown from resetting when the switch system swaps an item
 * into the player's hand. Without this, every swap causes a cooldown reset.
 *
 * Player.tick() detects held-item changes and calls resetAttackStrengthTicker().
 * We intercept that call: if the switch system just triggered the change, skip the
 * reset so the cooldown keeps its current value (already ticking up in inventory).
 */
@Mixin(Player.class)
public abstract class PlayerItemSwitchMixin {

    @Redirect(
        method = "tick",
        at = @At(value = "INVOKE",
                 target = "Lnet/minecraft/world/entity/player/Player;resetAttackStrengthTicker()V")
    )
    private void onItemChangeResetAttack(Player self) {
        // Skip the reset when the switch system put this item in hand — so the weapon stays ready to
        // strike (the swap itself shouldn't reset the cooldown; actually attacking still does, via
        // Player.attack(), which is a different method we don't touch).
        //   - CLIENT: a switch swap we performed locally set the client pending flag (SP + visual).
        //   - SERVER: the server-side switch handler marked this player ready (authoritative damage).
        // The SwitchToolHandler reference stays inside the client branch (short-circuit) so it is never
        // loaded on a dedicated server, which has no client classes.
        boolean skip;
        if (self.level().isClientSide()) {
            skip = SwitchToolHandler.consumeSwitchSwapPending();
        } else {
            skip = com.example.inventoryorganizer.warehouse.WarehouseNet.consumeSwitchReady(self.getUUID());
        }
        if (skip) return;
        // Normal path: replicate resetAttackStrengthTicker() (sets both tickers to 0)
        PlayerAttackTickerAccessor acc = (PlayerAttackTickerAccessor)(Object) self;
        acc.setAttackStrengthTicker(0);
        acc.setItemSwapTicker(0);
    }
}
