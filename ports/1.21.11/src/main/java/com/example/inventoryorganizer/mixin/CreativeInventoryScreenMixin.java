package com.example.inventoryorganizer.mixin;

import com.example.inventoryorganizer.InventorySorter;
import com.example.inventoryorganizer.config.KitsScreen;
import com.example.inventoryorganizer.config.VisualInventoryConfigScreen;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.CreativeInventoryScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(CreativeInventoryScreen.class)
public abstract class CreativeInventoryScreenMixin extends Screen {

    @Unique private ButtonWidget cre$kButton = null;
    @Unique private ButtonWidget cre$sButton = null;
    @Unique private ButtonWidget cre$oiButton = null;

    protected CreativeInventoryScreenMixin(Text title) {
        super(title);
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void addOrganizeButtons(CallbackInfo ci) {
        if (!((CreativeInventoryScreen)(Object)this).isInventoryTabSelected()) return;

        int panelX = (this.width - 195) / 2;
        int panelY = (this.height - 136) / 2;
        // If not enough space above panel (incl. creative tabs ~28px + buttons 14px + margin), place below
        final int BTN_H = 14;
        boolean enoughSpaceAbove = panelY >= 44; // 28 tab height + 14 btn + 2 margin
        int buttonY = enoughSpaceAbove ? panelY - BTN_H - 28 : panelY + 136 + 4;
        int startX = panelX + 2;

        cre$kButton = ButtonWidget.builder(
                Text.literal("K"),
                button -> MinecraftClient.getInstance().setScreen(
                        new KitsScreen(MinecraftClient.getInstance().currentScreen))
        ).dimensions(startX, buttonY, 22, 14).build();
        this.addDrawableChild(cre$kButton);

        cre$sButton = ButtonWidget.builder(
                Text.literal("S"),
                button -> MinecraftClient.getInstance().setScreen(
                        new VisualInventoryConfigScreen(MinecraftClient.getInstance().currentScreen))
        ).dimensions(startX + 24, buttonY, 22, 14).build();
        this.addDrawableChild(cre$sButton);

        cre$oiButton = ButtonWidget.builder(
                Text.literal("OI"),
                button -> InventorySorter.sortInventory()
        ).dimensions(startX + 48, buttonY, 22, 14).build();
        this.addDrawableChild(cre$oiButton);
    }

}
