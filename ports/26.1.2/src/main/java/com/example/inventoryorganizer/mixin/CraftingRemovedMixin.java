package com.example.inventoryorganizer.mixin;

import com.example.inventoryorganizer.warehouse.WarehouseNet;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.CraftingMenu;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Closing a crafting table (ESC / E / right-click back to the table) → send the chest-borrowed ingredients
 * still in the crafting grid back to the player's nearby chests instead of letting vanilla dump them into
 * the inventory. {@code removed} is declared on CraftingMenu (verified in the 26.1 bytecode); its HEAD runs
 * before vanilla's clearContainer, so the grid is still intact. Only the chest-borrowed amount is returned
 * (hand-placed items stay), and only when a live remote-craft context exists — ordinary crafting untouched.
 *
 * <p>Lives in a NON-REQUIRED mixin config: if the target ever moves, the mixin is silently skipped (the
 * manual empty-hand "grid → chest" deposit still works) — it can never crash the game.
 */
@Mixin(CraftingMenu.class)
public abstract class CraftingRemovedMixin {

    @Inject(method = "removed", at = @At("HEAD"))
    private void inor$returnGridToChestsOnClose(Player player, CallbackInfo ci) {
        try {
            if (!(player instanceof ServerPlayer sp)) return;
            if (!(sp.level() instanceof ServerLevel level)) return;
            WarehouseNet.returnBorrowedFromGrid(sp, level, ((AbstractContainerMenu) (Object) this).slots);
        } catch (Throwable ignored) {}
    }
}
