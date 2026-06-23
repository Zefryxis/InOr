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
        // Only intercept on client side, and only when the switch system is responsible
        if (self.level().isClientSide() && SwitchToolHandler.consumeSwitchSwapPending()) {
            // Skip the reset — the ticker keeps ticking as if the item was held all along
            return;
        }
        // Normal path: replicate resetAttackStrengthTicker() (sets both tickers to 0)
        PlayerAttackTickerAccessor acc = (PlayerAttackTickerAccessor)(Object) self;
        acc.setAttackStrengthTicker(0);
        acc.setItemSwapTicker(0);
    }
}
