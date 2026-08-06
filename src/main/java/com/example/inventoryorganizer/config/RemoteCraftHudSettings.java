package com.example.inventoryorganizer.config;

/**
 * Persisted layout settings for the Remote Crafting panel (nearby-chest-contents list shown next to
 * the crafting/inventory GUI). Split into three independently positionable pieces so the panel can be
 * moved out of JEI's (or any other right-docked mod's) way instead of always colliding with it:
 *
 * <ul>
 *   <li>the scrollable/searchable <b>chest section</b> — left or right of the GUI;</li>
 *   <li>the <b>buttons section</b> (search/qty/scroll controls) — above, below, or right of the GUI;</li>
 *   <li>the <b>deposit ("send item to chest")</b> button — freely draggable anywhere on screen.</li>
 * </ul>
 *
 * <p>Plain public fields so Gson serializes it directly as part of {@link OrganizerConfig}, mirroring
 * {@link HudSettings}'s convention. Defaults reproduce the mod's original fixed behaviour exactly, so
 * existing installs see no visual change until the player touches the new position controls.
 */
public class RemoteCraftHudSettings {

    public enum ChestPos { LEFT, RIGHT }
    public enum ButtonsPos { ABOVE, BELOW, RIGHT }

    public ChestPos chestPos = ChestPos.RIGHT;
    public ButtonsPos buttonsPos = ButtonsPos.BELOW;

    /** False = the deposit button sits at its legacy auto-computed spot; true = the player dragged it. */
    public boolean depositMoved = false;
    /** Fraction (0..1) of the SCREEN, centre of the button — only meaningful when {@link #depositMoved}. */
    public double depositX = 0.5, depositY = 0.85;

    /**
     * Set the chest section's side, auto-resolving a RIGHT/RIGHT clash with the buttons section by
     * bumping the buttons section to BELOW (the least disruptive alternative — it doesn't need to
     * shrink the chest list's width the way RIGHT does).
     */
    public void setChestPos(ChestPos p) {
        chestPos = p;
        if (p == ChestPos.RIGHT && buttonsPos == ButtonsPos.RIGHT) buttonsPos = ButtonsPos.BELOW;
    }

    /**
     * Set the buttons section's side, auto-resolving a RIGHT/RIGHT clash with the chest section by
     * bumping the chest section to LEFT.
     */
    public void setButtonsPos(ButtonsPos p) {
        buttonsPos = p;
        if (p == ButtonsPos.RIGHT && chestPos == ChestPos.RIGHT) chestPos = ChestPos.LEFT;
    }
}
