package com.example.inventoryorganizer.mixin;

import com.example.inventoryorganizer.ShulkerIdHolder;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Adds a mod-assigned UUID field to every shulker box block entity.
 *
 * Persistence paths:
 *  1. World save/load: saveAdditional / loadAdditional (chunk data)
 *  2. Break→item→place: handled by BaseContainerShulkerMixin which injects into
 *     BaseContainerBlockEntity.collectImplicitComponents / applyImplicitComponents,
 *     where these methods actually reside in MC 26.2.
 */
@Mixin(ShulkerBoxBlockEntity.class)
public abstract class ShulkerBoxBlockEntityMixin implements ShulkerIdHolder {

    @Unique private String inorShulkerId = null;

    @Inject(method = "saveAdditional", at = @At("TAIL"))
    private void inor$save(ValueOutput output, CallbackInfo ci) {
        if (inorShulkerId != null) output.putString("InOrShulkerId", inorShulkerId);
    }

    @Inject(method = "loadAdditional", at = @At("TAIL"))
    private void inor$load(ValueInput input, CallbackInfo ci) {
        inorShulkerId = input.getString("InOrShulkerId").orElse(null);
    }

    @Override public String inor$getShulkerId() { return inorShulkerId; }
    @Override public void inor$setShulkerId(String id) {
        inorShulkerId = id;
        ((BlockEntity) (Object) this).setChanged();
    }
}
