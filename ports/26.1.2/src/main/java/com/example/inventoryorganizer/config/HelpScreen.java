package com.example.inventoryorganizer.config;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.util.ArrayList;
import java.util.List;

/**
 * Full, scrollable feature guide for Inventory Organizer. Opened automatically the first time the
 * settings are entered, from the "Guide" button, and from the "?" button.
 *
 * <p>The raw content is plain colour-coded strings; on {@link #init()} every line is word-wrapped to
 * the current panel width (so nothing ever overflows horizontally) and re-wrapped on resize. Layout
 * lives entirely in {@code init()}, so it is resize-safe.
 */
public class HelpScreen extends Screen {

    private final Screen parent;
    private final List<String> raw = new ArrayList<>();   // colour-coded source lines
    private final List<FormattedCharSequence> wrapped = new ArrayList<>(); // word-wrapped for the box

    private int scroll = 0;
    private int maxScroll = 0;
    private int boxX, boxY, boxW, boxH, visibleLines;
    private static final int LINE_H = 11;

    public HelpScreen(Screen parent) {
        super(Component.literal("Inventory Organizer — Guide"));
        this.parent = parent;
        build();
    }

    private static final String GP = "inventory-organizer.guide.";
    private void h(String key) { raw.add("§b§l" + tr(key)); }   // section header
    private void t(String key) { raw.add("§f" + tr(key)); }     // normal line
    private void d(String key) { raw.add("§7" + tr(key)); }     // dim detail
    private void gap() { raw.add(""); }
    /** Resolve a guide line to the active language (falls back to en_us when a key is missing). */
    private static String tr(String key) { return Component.translatable(GP + key).getString(); }

    private void build() {
        h("welcome.head");  t("welcome.t1");  d("welcome.d1");  gap();
        h("fullinv.head");  t("fullinv.t1");  gap();
        h("slots.head");    t("slots.t1");    t("slots.t2");    t("slots.t3"); d("slots.d1"); gap();
        h("refill.head");   t("refill.t1");   t("refill.t2");   d("refill.d1"); gap();
        h("chestrefill.head"); t("chestrefill.t1"); gap();
        h("deposit.head");  t("deposit.t1");  gap();
        h("sorting.head");  t("sorting.t1");  t("sorting.t2");  d("sorting.d1"); gap();
        h("scroll.head");   t("scroll.t1");   t("scroll.t2");   gap();
        h("trash.head");    t("trash.t1");    d("trash.d1");    gap();
        h("storage.head");  t("storage.t1");  t("storage.t2");  d("storage.d1"); gap();
        h("groups.head");   t("groups.t1");   t("groups.t2");   t("groups.t3"); d("groups.d1"); d("groups.d2"); gap();
        h("special.head");  t("special.t1");  t("special.t2");  gap();
        h("crafting.head"); t("crafting.t1"); t("crafting.t2"); t("crafting.t3"); t("crafting.t4"); d("crafting.d1"); gap();
        h("hud.head");      t("hud.t1");      t("hud.t2");      t("hud.t3"); d("hud.d1"); gap();
        h("switch.head");    t("switch.t1");    t("switch.t2");    t("switch.t3"); d("switch.d1"); gap();
        h("warehouse.head"); t("warehouse.t1"); d("warehouse.d1"); gap();
        h("keybinds.head"); t("keybinds.t1"); d("keybinds.d1"); gap();
    }

    @Override
    protected void init() {
        boxW = Math.min(380, width - 40);
        boxX = (width - boxW) / 2;
        boxY = 34;
        boxH = height - boxY - 40;
        visibleLines = Math.max(1, (boxH - 10) / LINE_H);

        // Word-wrap every source line to the inner width, so text never spills out of the panel.
        wrapped.clear();
        int inner = boxW - 14;
        for (String s : raw) {
            if (s.isEmpty()) { wrapped.add(FormattedCharSequence.EMPTY); continue; }
            wrapped.addAll(font.split(Component.literal(s), inner));
        }
        maxScroll = Math.max(0, wrapped.size() - visibleLines);
        scroll = Math.max(0, Math.min(scroll, maxScroll));

        addRenderableWidget(StyledButton.styledBuilder(Component.translatable("inventory-organizer.button.back"),
            b -> Minecraft.getInstance().setScreen(parent)).bounds(width / 2 - 50, height - 28, 100, 18).build());
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        scroll = Math.max(0, Math.min(scroll + (verticalAmount > 0 ? -1 : 1), maxScroll));
        return true;
    }

    @Override
    public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent click, boolean bl) {
        if (super.mouseClicked(click, bl)) return true; // let the Back button etc. handle it first
        // Click anywhere OUTSIDE the text panel closes the guide.
        double mx = click.x(), my = click.y();
        if (mx < boxX || mx > boxX + boxW || my < boxY || my > boxY + boxH) {
            onClose();
            return true;
        }
        return false;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
        context.fill(0, 0, width, height, 0xFF12121C);
        context.centeredText(font, Component.literal("§e§lInventory Organizer §7— §fGuide"), width / 2, 14, 0xFFFFFFFF);

        // Panel: a thin border + darker inner fill.
        context.fill(boxX - 2, boxY - 2, boxX + boxW + 2, boxY + boxH + 2, 0xFF3A3A5A);
        context.fill(boxX, boxY, boxX + boxW, boxY + boxH, 0xFF1A1A28);

        int y = boxY + 5;
        for (int i = scroll; i < wrapped.size() && i < scroll + visibleLines; i++) {
            context.text(font, wrapped.get(i), boxX + 7, y, 0xFFFFFFFF);
            y += LINE_H;
        }

        // Scroll hints, centred just outside the panel so they never overlap the text.
        if (scroll > 0) context.centeredText(font, Component.literal("§7▲"), width / 2, boxY - 12, 0xFFAAAAAA);
        if (scroll < maxScroll) context.centeredText(font, Component.literal("§7▼ scroll for more"), width / 2, boxY + boxH + 4, 0xFFAAAAAA);

        super.extractRenderState(context, mouseX, mouseY, delta);
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(parent);
    }
}
