package com.example.inventoryorganizer;

import com.example.inventoryorganizer.warehouse.WarehouseNet;
import net.fabricmc.api.ModInitializer;

/**
 * Common entrypoint — runs on BOTH the client and the (integrated/dedicated) server. Hosts the
 * server-side + common parts of the warehouse subsystem. Keep this strictly server-safe: do NOT
 * reference any client-only class from here (it would crash a dedicated server on class-load).
 */
public class InventoryOrganizer implements ModInitializer {
    public static final String MOD_ID = "inventory-organizer";

    @Override
    public void onInitialize() {
        // Warehouse handshake: register payload types (both sides) + greet joining players (server).
        WarehouseNet.registerCommon();
        WarehouseNet.registerServer();
    }
}
