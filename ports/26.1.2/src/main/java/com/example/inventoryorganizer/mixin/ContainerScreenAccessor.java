package com.example.inventoryorganizer.mixin;

import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Exposes the protected {@code hoveredSlot} field of {@link AbstractContainerScreen} so the
 * shift-scroll "looting" feature can quick-move whatever slot the cursor is over.
 */
@Mixin(AbstractContainerScreen.class)
public interface ContainerScreenAccessor {
    @Accessor("hoveredSlot")
    Slot inorGetHoveredSlot();

    @Accessor("leftPos")
    int inorLeftPos();

    @Accessor("topPos")
    int inorTopPos();

    @Accessor("imageWidth")
    int inorImageWidth();

    @Accessor("imageHeight")
    int inorImageHeight();
}
