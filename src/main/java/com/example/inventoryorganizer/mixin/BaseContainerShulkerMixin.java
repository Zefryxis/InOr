package com.example.inventoryorganizer.mixin;

import com.example.inventoryorganizer.InOrComponents;
import com.example.inventoryorganizer.ShulkerIdHolder;
import net.minecraft.core.component.DataComponentGetter;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.world.level.block.entity.BaseContainerBlockEntity;
import net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Hooks into BaseContainerBlockEntity (where the methods actually live) to
 * propagate the mod's shulker UUID through the break→item→place cycle via
 * the custom DataComponentType. Only acts when the block entity is a shulker box.
 */
@Mixin(BaseContainerBlockEntity.class)
public abstract class BaseContainerShulkerMixin {

    @Inject(method = "collectImplicitComponents", at = @At("TAIL"))
    private void inor$collectShulkerId(DataComponentMap.Builder builder, CallbackInfo ci) {
        if (!((Object) this instanceof ShulkerBoxBlockEntity)) return;
        String id = ((ShulkerIdHolder) this).inor$getShulkerId();
        if (id != null && !id.isEmpty()) {
            builder.set(InOrComponents.SHULKER_ID, id);
        }
    }

    @Inject(method = "applyImplicitComponents", at = @At("TAIL"))
    private void inor$applyShulkerId(DataComponentGetter input, CallbackInfo ci) {
        if (!((Object) this instanceof ShulkerBoxBlockEntity)) return;
        String id = input.get(InOrComponents.SHULKER_ID);
        if (id != null && !id.isEmpty()) {
            ((ShulkerIdHolder) this).inor$setShulkerId(id);
        }
    }
}
