package com.example.inventoryorganizer.mixin;

import com.example.inventoryorganizer.InventoryOrganizerClient;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Hooks scroll on container screens. {@link AbstractContainerScreen#mouseScrolled} overrides
 * {@code Screen.mouseScrolled} WITHOUT calling super, so Fabric's {@code ScreenMouseEvents} never
 * fire here — we must inject directly to drive the shift-scroll loot + inventory scroll-sort features.
 */
@Mixin(AbstractContainerScreen.class)
public abstract class ContainerScrollMixin {

    @Inject(method = "mouseScrolled(DDDD)Z", at = @At("HEAD"), cancellable = true)
    private void inor$onScroll(double mouseX, double mouseY, double horizontalAmount, double verticalAmount,
                               CallbackInfoReturnable<Boolean> cir) {
        AbstractContainerScreen<?> self = (AbstractContainerScreen<?>) (Object) this;
        if (InventoryOrganizerClient.handleContainerScroll(self, verticalAmount)) {
            cir.setReturnValue(true); // consumed
        }
    }
}
