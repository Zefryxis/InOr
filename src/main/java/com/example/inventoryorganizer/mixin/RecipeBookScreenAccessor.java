package com.example.inventoryorganizer.mixin;

import net.minecraft.client.gui.screens.inventory.AbstractRecipeBookScreen;
import net.minecraft.client.gui.screens.recipebook.RecipeBookComponent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Exposes the private {@code recipeBookComponent} field of {@link AbstractRecipeBookScreen} (the
 * crafting table and survival inventory screens both extend it) so {@code RemoteCraftPanel} can check
 * whether the vanilla recipe book is currently open — and by how much (its fixed {@code IMAGE_WIDTH})
 * — to avoid overlapping it when the Materials panel is positioned on the left.
 */
@Mixin(AbstractRecipeBookScreen.class)
public interface RecipeBookScreenAccessor {
    @Accessor("recipeBookComponent")
    RecipeBookComponent<?> inorRecipeBookComponent();
}
