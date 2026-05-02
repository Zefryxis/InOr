package com.example.inventoryorganizer.config;

public class SlotRule {
    public enum Type {
        ANY,
        GROUP,
        SPECIFIC,      // general type like "sword", "pickaxe", "axe"
        SPECIFIC_ITEM, // exact item like "minecraft:diamond_sword"
        EMPTY_LOCKED,  // slot stays empty, sorter never places items here
        BUNDLE_CONTENT, // slot holds a bundle; value = content rule (e.g. "g:food")
        CUSTOM_GROUP   // user-defined item group; value = group name
    }

    private Type type;
    private String value; // For GROUP: "weapons", "tools", "armor", "blocks", "food", "utility", "valuables"
                          // For SPECIFIC: type name like "sword", "pickaxe"
                          // For SPECIFIC_ITEM: full item id like "minecraft:diamond_sword"

    public SlotRule() {
        this.type = Type.ANY;
        this.value = "";
    }

    public SlotRule(Type type, String value) {
        this.type = type;
        this.value = value;
    }

    public Type getType() { return type; }
    public void setType(Type type) { this.type = type; }
    public String getValue() { return value; }
    public void setValue(String value) { this.value = value; }

    public SlotRule copy() {
        return new SlotRule(this.type, this.value);
    }

    /** Returns a short human-readable text representation of this rule */
    public String toText() {
        switch (type) {
            case EMPTY_LOCKED: return "empty";
            case GROUP: return "g:" + value;
            case SPECIFIC: return "t:" + value;
            case SPECIFIC_ITEM: return value;
            case BUNDLE_CONTENT: return "b:" + value;
            case CUSTOM_GROUP: return "cg:" + value;
            default: return "any";
        }
    }

    /** Creates a SlotRule from its text representation */
    public static SlotRule fromText(String text) {
        if (text == null || text.isEmpty() || text.equals("any")) return new SlotRule();
        if (text.equals("empty")) return new SlotRule(Type.EMPTY_LOCKED, "");
        if (text.startsWith("g:")) return new SlotRule(Type.GROUP, text.substring(2));
        if (text.startsWith("t:")) return new SlotRule(Type.SPECIFIC, text.substring(2));
        if (text.startsWith("b:")) return new SlotRule(Type.BUNDLE_CONTENT, text.substring(2));
        if (text.startsWith("cg:")) return new SlotRule(Type.CUSTOM_GROUP, text.substring(3));
        if (text.contains(":")) return new SlotRule(Type.SPECIFIC_ITEM, text);
        return new SlotRule(Type.SPECIFIC, text);
    }
}
