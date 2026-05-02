package com.example.inventoryorganizer.mixin;

import com.example.inventoryorganizer.StorageSorter;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.GenericContainerScreen;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Injects into HandledScreen.init() (which GenericContainerScreen inherits)
 * and adds the OST button only when the actual runtime type is GenericContainerScreen.
 */
@Mixin(HandledScreen.class)
public abstract class GenericContainerScreenMixin extends Screen {

    @Unique private ButtonWidget gcs$ostButton = null;
    @Unique private int gcs$btnX = 0;
    @Unique private int gcs$btnY = 0;

    protected GenericContainerScreenMixin(Text title) {
        super(title);
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void addOstButton(CallbackInfo ci) {
        if (!((Object) this instanceof GenericContainerScreen gcs)) return;

        // Use actual container height based on row count (matches GenericContainerScreen constructor)
        int rows = gcs.getScreenHandler().getRows();
        int bgHeight = 114 + rows * 18; // e.g. 3 rows = 168, 6 rows = 222
        int guiW = 176;
        int btnX = (this.width - guiW) / 2 + guiW - 30;
        int panelY = (this.height - bgHeight) / 2;
        int btnY = panelY - 18;

        gcs$ostButton = ButtonWidget.builder(
            Text.literal("OST"),
            button -> {
                MinecraftClient client = MinecraftClient.getInstance();
                if (client.player != null) {
                    ScreenHandler sh = client.player.currentScreenHandler;
                    int syncId = sh.syncId;
                    int containerSize = gcs.getScreenHandler().getRows() * 9;
                    StorageSorter.sortContainer(containerSize, syncId);
                }
            }
        ).dimensions(btnX, btnY, 28, 14).build();
        gcs$btnX = btnX;
        gcs$btnY = btnY;
        this.addDrawableChild(gcs$ostButton);
    }

}
