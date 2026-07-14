package com.example.inventoryorganizer.config;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * "Switch" rules screen (opened from the inventory config's Auto-switch mode). The target itself decides
 * which tool category is needed (vanilla fastest-tool, blocks AND entities); here you only:
 *  - edit the five fixed tool groups (Pickaxes/Axes/Shovels/Hoes/Swords) to ADD modded tools, and
 *  - set the on-air behaviour.
 * Which piece is chosen among a category's items is decided by the per-category material RANKS (Tier Order).
 */
public class SwitchRanksScreen extends Screen {

    /** category → fixed group name + a label key. */
    private static final String[][] GROUPS = {
            {"Pickaxes", "grp_pickaxe"}, {"Axes", "grp_axe"}, {"Shovels", "grp_shovel"},
            {"Hoes", "grp_hoe"}, {"Swords", "grp_sword"}
    };
    private static final String[] AIR_CYCLE = {"keep", "restore", "pickaxe", "axe", "shovel", "hoe", "mob"};

    private final Screen parent;
    private final OrganizerConfig config;
    private String air;

    public SwitchRanksScreen(Screen parent) {
        super(Component.translatable("inventory-organizer.switch.setup_title"));
        this.parent = parent;
        this.config = OrganizerConfig.get();
        this.air = config.getSwitchAir();
        config.materializeSwitchGroupsOnce(); // ensure the 5 tool groups are full, normal cg's
        config.save();
    }

    @Override
    protected void init() {
        super.init();
        int x = width / 2 - 150;
        int y = 44;

        for (String[] g : GROUPS) {
            final String name = g[0];
            addRenderableWidget(StyledButton.styledBuilder(
                    Component.translatable("inventory-organizer.switch." + g[1])
                            .append(Component.literal(" §7(" + config.getCustomGroup(name).size() + ")")),
                    b -> { save(); Minecraft.getInstance().setScreen(new CustomGroupEditorScreen(this, name)); })
                    .bounds(x, y, 300, 18).build());
            y += 22;
        }
        y += 8;

        addRenderableWidget(StyledButton.styledBuilder(airLabel(),
                b -> { air = cycle(AIR_CYCLE, air); b.setMessage(airLabel()); }).bounds(x, y, 300, 18).build());

        addRenderableWidget(StyledButton.styledBuilder(Component.translatable("inventory-organizer.button.save"),
                b -> { save(); Minecraft.getInstance().setScreen(parent); }).bounds(width / 2 - 104, height - 28, 100, 20).build());
        addRenderableWidget(StyledButton.styledBuilder(Component.translatable("inventory-organizer.button.back"),
                b -> { save(); Minecraft.getInstance().setScreen(parent); }).bounds(width / 2 + 4, height - 28, 100, 20).build());
    }

    private void save() { config.setSwitchAir(air); config.save(); }

    private static String cycle(String[] arr, String cur) {
        for (int i = 0; i < arr.length; i++) if (arr[i].equals(cur)) return arr[(i + 1) % arr.length];
        return arr[0];
    }

    private Component airLabel() {
        String key = "inventory-organizer.switch.air." + air;
        return Component.translatable("inventory-organizer.switch.air").append(Component.literal(": §e")).append(Component.translatable(key));
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
        super.extractRenderState(context, mouseX, mouseY, delta);
        context.centeredText(font, Component.translatable("inventory-organizer.switch.setup_title"), width / 2, 10, 0xFFFFFF55);
        context.centeredText(font, Component.translatable("inventory-organizer.switch.groups_hint"), width / 2, 28, 0xFFAAAAAA);
    }

    @Override
    public void onClose() { save(); Minecraft.getInstance().setScreen(parent); }
}
