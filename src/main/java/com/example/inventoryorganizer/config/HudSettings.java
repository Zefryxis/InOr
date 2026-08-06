package com.example.inventoryorganizer.config;

/**
 * Persisted layout/visibility settings for the configurable HUD overlay. Each element has an on/off
 * flag and a fractional screen position (0..1 of the GUI-scaled width/height), so positions stay put
 * across resolution and window-size changes. Everything is OFF by default — a fresh install shows
 * nothing, not even the combat indicator.
 *
 * <p>Plain public fields so Gson serializes it directly as part of {@link OrganizerConfig}.
 */
public class HudSettings {

    // Combat indicator (only drawn during real combat, at the configured spot).
    public boolean combatEnabled = false;
    public double combatX = 0.5, combatY = 0.5;
    public double combatScale = 1.0;

    // Mini-inventory: the 27 main storage slots (no hotbar), shown as a 9x3 grid.
    public boolean invEnabled = false;
    public double invX = 0.5, invY = 0.5;
    public double invScale = 1.0;

    // Worn set + offhand (helmet/chest/legs/feet/offhand). Icons move together.
    public boolean setEnabled = false;
    public double setX = 0.5, setY = 0.5;
    public double setScale = 1.0;
    public boolean setVertical = true;     // true = vertical column, false = horizontal row
    public boolean setDurability = false;  // draw vanilla-style durability bars on the set icons
    public boolean setOffhand = true;      // include the offhand slot as the 5th icon
}
