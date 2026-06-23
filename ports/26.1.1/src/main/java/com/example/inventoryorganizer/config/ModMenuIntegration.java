package com.example.inventoryorganizer.config;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

/**
 * Registers the mod's main config screen with ModMenu so users can open it
 * from the Mods → Inventory Organizer page in the ModMenu UI.
 */
public class ModMenuIntegration implements ModMenuApi {
    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return parent -> new VisualInventoryConfigScreen(parent);
    }
}
