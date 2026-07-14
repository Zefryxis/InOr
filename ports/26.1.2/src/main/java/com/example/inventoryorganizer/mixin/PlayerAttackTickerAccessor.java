package com.example.inventoryorganizer.mixin;

import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(LivingEntity.class)
public interface PlayerAttackTickerAccessor {
    @Accessor("attackStrengthTicker")
    int getAttackStrengthTicker();
    @Accessor("attackStrengthTicker")
    void setAttackStrengthTicker(int value);

    @Accessor("itemSwapTicker")
    int getItemSwapTicker();
    @Accessor("itemSwapTicker")
    void setItemSwapTicker(int value);
}
