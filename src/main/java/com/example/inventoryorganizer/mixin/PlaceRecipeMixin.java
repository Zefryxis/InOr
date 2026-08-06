package com.example.inventoryorganizer.mixin;

import com.example.inventoryorganizer.warehouse.RemoteStock;
import com.example.inventoryorganizer.warehouse.WarehouseNet;
import it.unimi.dsi.fastutil.ints.IntList;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractCraftingMenu;
import net.minecraft.world.inventory.RecipeBookMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.PlacementInfo;
import net.minecraft.world.item.crafting.RecipeHolder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Iterator;
import java.util.List;

/**
 * Vanilla recipe-book integration (server side): when the player clicks a recipe in the recipe book at a
 * crafting table, top up the missing ingredients from their nearby chests BEFORE vanilla fills the grid.
 * Vanilla then fills the 3x3 grid from the (now stocked) inventory as usual.
 *
 * <p>{@code AbstractCraftingMenu.handlePlacement} is called unconditionally by
 * {@code ServerGamePacketListenerImpl.handlePlaceRecipe} (verified in the 26.1 bytecode), so this HEAD
 * hook runs on every recipe-book click. The chest list + preference come from {@link WarehouseNet}'s
 * CraftCtx cache (refreshed by the materials panel's periodic CraftStockQuery). All theft/reach guards
 * live in {@link RemoteStock}.
 */
@Mixin(AbstractCraftingMenu.class)
public abstract class PlaceRecipeMixin {

    @Inject(method = "handlePlacement", at = @At("HEAD"))
    private void inor$pullIngredientsFromChests(boolean useMaxItems, boolean isCreative, RecipeHolder<?> recipe,
                                                ServerLevel level, Inventory inv,
                                                CallbackInfoReturnable<RecipeBookMenu.PostPlaceAction> cir) {
        try {
            if (recipe == null) return;
            Player p = inv.player;
            if (!(p instanceof ServerPlayer player)) return;

            WarehouseNet.CraftCtx ctx = WarehouseNet.craftCtx(player.getUUID());
            if (ctx == null || ctx.chests().isEmpty()) return;

            // Switching recipes: the grid holds the PREVIOUS recipe's ingredients. Send the chest-borrowed
            // portion back to the chests FIRST (each chest OST'd) so it isn't dumped into the inventory —
            // then the pull below stocks the NEW recipe. (No-op on the first click / when nothing borrowed.)
            WarehouseNet.returnBorrowedFromGrid(player, level,
                    ((net.minecraft.world.inventory.AbstractContainerMenu) (Object) this).slots);

            PlacementInfo info = recipe.value().placementInfo();
            if (info == null || info.isImpossibleToPlace()) return;
            List<Ingredient> ingredients = info.ingredients();
            IntList slots = info.slotsToIngredientIndex();
            if (ingredients.isEmpty() || slots == null) return;

            int[] mult = new int[ingredients.size()];
            for (int i = 0; i < slots.size(); i++) {
                int idx = slots.getInt(i);
                if (idx >= 0 && idx < mult.length) mult[idx]++;
            }

            for (int k = 0; k < ingredients.size(); k++) {
                if (mult[k] <= 0) continue;
                Ingredient ing = ingredients.get(k);
                int have = inor$inventoryHave(inv, ing);
                int need = ctx.preferChests() ? mult[k] : Math.max(0, mult[k] - have);
                if (need <= 0) continue;

                int remaining = need;
                Iterator<Holder<Item>> it = ing.items().iterator();
                while (it.hasNext() && remaining > 0) {
                    Item item = it.next().value();
                    Identifier id = BuiltInRegistries.ITEM.getKey(item);
                    if (id == null) continue;
                    // Pull per chest so we remember WHICH chest each ingredient came from → the return can
                    // put it back into that exact chest.
                    for (net.minecraft.core.BlockPos chest : ctx.chests()) {
                        if (remaining <= 0) break;
                        int g = RemoteStock.withdrawFrom(player, level, chest, id.toString(), remaining);
                        if (g > 0) {
                            remaining -= g;
                            WarehouseNet.recordBorrow(player.getUUID(), id.toString(), chest, g);
                        }
                    }
                }
            }
        } catch (Throwable ignored) {}
    }

    @Unique
    private static int inor$inventoryHave(Inventory inv, Ingredient ing) {
        int sum = 0;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (!s.isEmpty() && ing.test(s)) sum += s.getCount();
        }
        return sum;
    }
}
