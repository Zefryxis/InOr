package com.example.inventoryorganizer;

/** Interface injected into ShulkerBoxBlockEntity so server code can read/write the mod UUID. */
public interface ShulkerIdHolder {
    String inor$getShulkerId();
    void inor$setShulkerId(String id);
}
