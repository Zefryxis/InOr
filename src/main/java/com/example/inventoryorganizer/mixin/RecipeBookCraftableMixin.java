package com.example.inventoryorganizer.mixin;

import com.example.inventoryorganizer.warehouse.WarehouseClient;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.StackedItemContents;
import net.minecraft.world.inventory.AbstractCraftingMenu;
import net.minecraft.world.inventory.RecipeBookMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Map;

/**
 * Vanilla recipe-book integration (client side): make the recipe book count the player's nearby chest
 * stock when deciding which recipes are craftable, so chest-craftable recipes light up green. We append
 * the cached chest stock to the recipe book's item counter right after vanilla fills it from the
 * inventory (and before craftability is recomputed). Only at crafting menus, only when the InOr handshake
 * says the subsystem is available — otherwise pure vanilla behaviour.
 */
@Mixin(net.minecraft.client.gui.screens.recipebook.RecipeBookComponent.class)
public abstract class RecipeBookCraftableMixin {

    @Shadow @Final private StackedItemContents stackedContents;
    @Shadow @Final protected RecipeBookMenu menu;

    // Inject RIGHT AFTER the inventory + grid are accounted into stackedContents but BEFORE
    // selectMatchingRecipes() recomputes craftability — otherwise the green decision is already made
    // (TAIL was too late). fillCraftSlotsStackedContents is the last fill before that decision.
    @Inject(method = "updateStackedContents",
            at = @At(value = "INVOKE",
                     target = "Lnet/minecraft/world/inventory/RecipeBookMenu;fillCraftSlotsStackedContents(Lnet/minecraft/world/entity/player/StackedItemContents;)V",
                     shift = At.Shift.AFTER))
    private void inor$countChestStock(CallbackInfo ci) {
        try {
            if (!WarehouseClient.isAvailable()) return;
            if (!(((Object) menu) instanceof AbstractCraftingMenu)) return;
            for (Map.Entry<String, Integer> e : WarehouseClient.getCraftStock().entrySet()) {
                if (e.getValue() == null || e.getValue() <= 0) continue;
                Identifier id = Identifier.tryParse(e.getKey());
                if (id == null) continue;
                Item item = BuiltInRegistries.ITEM.getValue(id);
                if (item == null || item == Items.AIR) continue;
                ItemStack s = new ItemStack(item);
                s.setCount(e.getValue());
                stackedContents.accountSimpleStack(s);
            }
        } catch (Throwable ignored) {}
    }
}
