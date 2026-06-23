package com.example.inventoryorganizer;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;

import java.util.List;

/**
 * Side-agnostic item categorisation + warehouse routing primitives. Contains NO client-only
 * references, so it is safe to load on a dedicated server (unlike {@link InventorySorter}, which
 * imports {@code net.minecraft.client.Minecraft}).
 *
 * <p>The category heuristic mirrors {@link InventorySorter}'s (kept in sync deliberately; the client
 * sorter keeps its own copy so this extraction can't regress it). The server-side warehouse engine
 * routes items using only the per-chest slot-rule strings the client sends over the wire, matched
 * here against item categories — no client config is read server-side.
 */
public final class SortLogic {

    private SortLogic() {}

    // ===== Categories (must match InventorySorter's values) =====
    public static final int CAT_WEAPON = 0;
    public static final int CAT_TOOL = 1;
    public static final int CAT_ARMOR = 2;
    public static final int CAT_VALUABLE = 3;
    public static final int CAT_BLOCK = 4;
    public static final int CAT_FOOD = 5;
    public static final int CAT_UTILITY = 6;
    public static final int CAT_POTION = 7;
    public static final int CAT_MISC = 8;
    public static final int CAT_LOG = 9;
    public static final int CAT_BOAT = 10;
    public static final int CAT_PLANT = 11;
    public static final int CAT_STONE = 12;
    public static final int CAT_ORE = 13;
    public static final int CAT_COOKED = 14;
    public static final int CAT_RAW_FOOD = 15;
    public static final int CAT_NETHER = 16;
    public static final int CAT_END = 17;
    public static final int CAT_PARTIAL = 18;
    public static final int CAT_REDSTONE = 19;
    public static final int CAT_CREATIVE = 20;
    public static final int CAT_ARROW = 21;
    public static final int CAT_SPLASH_POTION = 22;

    public static String getItemId(ItemStack stack) {
        Identifier id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        return id.toString();
    }

    /** Base potion effect of a potion stack (long_/strong_ stripped), or null. Server-safe. */
    public static String basePotionEffect(ItemStack stack) {
        try {
            PotionContents comp = stack.get(DataComponents.POTION_CONTENTS);
            if (comp == null || comp.potion().isEmpty()) return null;
            String id = comp.potion().get().unwrapKey().map(k -> k.identifier().getPath()).orElse("");
            if (id.isEmpty()) return null;
            if (id.startsWith("long_")) id = id.substring(5);
            else if (id.startsWith("strong_")) id = id.substring(7);
            return id;
        } catch (Throwable t) {
            return null;
        }
    }

    public static boolean matchesPotionEffect(ItemStack stack, String effect) {
        String base = basePotionEffect(stack);
        return base != null && base.equals(effect);
    }

    // ===== Warehouse routing =====

    /**
     * The best (lowest) priority rank of any slot rule that matches the item:
     * {@code 0} = specific-item rule, {@code 1} = item-type rule, {@code 2} = group rule,
     * {@code Integer.MAX_VALUE} = no rule matches. Ranks the chest across a linked group so the most
     * specific matching slot wins, regardless of which physical chest it lives in.
     */
    public static int matchRank(List<String> rules, ItemStack stack) {
        if (rules == null || rules.isEmpty()) return Integer.MAX_VALUE;
        String id = getItemId(stack);
        int best = Integer.MAX_VALUE;
        for (String rule : rules) {
            if (rule == null || rule.equals("any") || rule.equals("empty") || rule.isEmpty()) continue;
            int r = Integer.MAX_VALUE;
            if (rule.startsWith("g:")) {
                if (matchesGroupName(stack, rule.substring(2))) r = 2;
            } else if (rule.startsWith("cg:")) {
                if (matchesGroupName(stack, rule.substring(3))) r = 2;
            } else if (rule.startsWith("t:")) {
                if (matchesItemType(id, rule.substring(2))) r = 1;
            } else if (rule.startsWith("pot:")) {
                if (matchesPotionEffect(stack, rule.substring(4))) r = 0;
            } else if (rule.contains(":")) {
                if (matchesSpecificItem(id, rule)) r = 0;
            }
            if (r < best) best = r;
            if (best == 0) break;
        }
        return best;
    }

    /** True when the rule list has any specific (non-"any"/"empty") rule — i.e. it's not an overflow chest. */
    public static boolean hasSpecificRules(List<String> rules) {
        if (rules == null) return false;
        for (String rule : rules) {
            if (rule != null && !rule.isEmpty() && !rule.equals("any") && !rule.equals("empty")) return true;
        }
        return false;
    }

    // ===== Double-chest geometry (server-safe; takes a Level, no Minecraft client access) =====

    /** The other half of a double chest at {@code pos}, or null when it's single / not a chest. */
    public static BlockPos doubleChestPartner(Level level, BlockPos pos) {
        try {
            BlockState st = level.getBlockState(pos);
            if (!(st.getBlock() instanceof ChestBlock) || !st.hasProperty(ChestBlock.TYPE)
                    || st.getValue(ChestBlock.TYPE) == ChestType.SINGLE) {
                return null;
            }
            BlockPos partner = pos.relative(ChestBlock.getConnectedDirection(st));
            if (level.getBlockState(partner).getBlock() instanceof ChestBlock) return partner;
            for (Direction d : new Direction[]{Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST}) {
                BlockPos n = pos.relative(d);
                BlockState ns = level.getBlockState(n);
                if (ns.getBlock() instanceof ChestBlock && ns.hasProperty(ChestBlock.TYPE)
                        && ns.getValue(ChestBlock.TYPE) != ChestType.SINGLE) {
                    return n;
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    // ===== Item-type / specific-item matching =====

    /** Match item ID against a type name, handling ambiguous cases like axe/pickaxe. */
    public static boolean matchesItemType(String itemId, String type) {
        if (type.equals("axe")) {
            return itemId.contains("_axe") && !itemId.contains("pickaxe");
        }
        if (type.equals("arrow")) {
            return itemId.equals("minecraft:arrow");
        }
        if (type.equals("potion")) {
            return itemId.equals("minecraft:potion");
        }
        return itemId.contains(type);
    }

    private static final String[] COLOR_PREFIXES = {
        "white_", "orange_", "magenta_", "light_blue_", "yellow_", "lime_",
        "pink_", "gray_", "light_gray_", "cyan_", "purple_", "blue_",
        "brown_", "green_", "red_", "black_"
    };

    private static final String[] COLOR_FAMILIES = {
        "bundle", "shulker_box", "bed", "banner", "candle", "carpet",
        "concrete", "concrete_powder", "wool", "terracotta",
        "glazed_terracotta", "stained_glass", "stained_glass_pane",
        "dye"
    };

    private static String getColorFamily(String itemId) {
        String path = itemId.contains(":") ? itemId.substring(itemId.indexOf(':') + 1) : itemId;
        for (String family : COLOR_FAMILIES) {
            if (path.equals(family)) return family;
        }
        for (String color : COLOR_PREFIXES) {
            if (path.startsWith(color)) {
                String remainder = path.substring(color.length());
                for (String family : COLOR_FAMILIES) {
                    if (remainder.equals(family)) return family;
                }
            }
        }
        return null;
    }

    /** Match a pool item against a SPECIFIC_ITEM rule, with color-family support. */
    public static boolean matchesSpecificItem(String poolItemId, String ruleValue) {
        if (poolItemId.equals(ruleValue)) return true;
        String ruleFamily = getColorFamily(ruleValue);
        if (ruleFamily == null) return false;
        String rulePath = ruleValue.contains(":") ? ruleValue.substring(ruleValue.indexOf(':') + 1) : ruleValue;
        if (rulePath.equals(ruleFamily)) {
            String poolFamily = getColorFamily(poolItemId);
            return ruleFamily.equals(poolFamily);
        }
        return false;
    }

    // ===== Group / category matching =====

    public static int groupNameToCategory(String group) {
        if (group == null) return CAT_MISC;
        if (group.equals("weapons"))  return CAT_WEAPON;
        if (group.equals("tools"))    return CAT_TOOL;
        if (group.equals("armor"))    return CAT_ARMOR;
        if (group.equals("valuables")) return CAT_VALUABLE;
        if (group.equals("blocks"))   return CAT_BLOCK;
        if (group.equals("food"))     return CAT_FOOD;
        if (group.equals("utility"))  return CAT_UTILITY;
        if (group.equals("potions"))  return CAT_POTION;
        if (group.equals("logs"))     return CAT_LOG;
        if (group.equals("boats"))    return CAT_BOAT;
        if (group.equals("plants"))   return CAT_PLANT;
        if (group.equals("stone"))    return CAT_STONE;
        if (group.equals("ores"))     return CAT_ORE;
        if (group.equals("cooked"))   return CAT_COOKED;
        if (group.equals("rawfood"))  return CAT_RAW_FOOD;
        if (group.equals("nether"))   return CAT_NETHER;
        if (group.equals("end"))      return CAT_END;
        if (group.equals("partial"))  return CAT_PARTIAL;
        if (group.equals("redstone")) return CAT_REDSTONE;
        if (group.equals("creative")) return CAT_CREATIVE;
        if (group.equals("arrows"))         return CAT_ARROW;
        if (group.equals("splash_potions")) return CAT_SPLASH_POTION;
        return CAT_MISC;
    }

    /**
     * Authoritative server-side group membership test, kept in lock-step with the client's
     * {@code InventorySorter.getDefaultItemsForGroup} generation. For the eight broad groups the
     * client builds from the game's own data (block mapping, item tags, the food component), the
     * server matches with the SAME data instead of the substring heuristic — otherwise warehouse
     * routing would disagree with the client's group contents (e.g. the heuristic only knows ~540 of
     * the ~1100 placeable blocks, so most blocks would miss their "blocks" chest and spill to
     * overflow). All of these APIs (ItemTags, DataComponents, Block.byItem) are common, not
     * client-only, so this stays dedicated-server-safe. Other groups fall back to the heuristic, which
     * is exactly what the client uses to generate them too.
     */
    /**
     * Resolve a group NAME (from a {@code g:}/{@code cg:} rule) against the player's ACTUAL edited group
     * membership when available, falling back to the data heuristic otherwise. On single-player / an
     * integrated server the warehouse runs in the same JVM as the client, so {@link OrganizerConfig} holds
     * the real, user-edited group contents — this makes warehouse routing honour exactly what the player
     * put in a group (e.g. a chest added to "blocks", a vanilla axe added to "weapons"), just like the
     * inventory sorter. On a dedicated server that config is absent/empty, so we use {@link #matchesGroup}
     * (the tag/component heuristic) and stay dedicated-safe — no behaviour change there.
     */
    /** Active player's synced custom groups, set per server-side sort (dedicated server has no client config). */
    private static final ThreadLocal<java.util.Map<String, java.util.List<String>>> ACTIVE_GROUPS = new ThreadLocal<>();

    /** Set before a server-side sort so {@link #matchesGroupName} can resolve the sorting player's groups. */
    public static void setActiveGroups(java.util.Map<String, java.util.List<String>> groups) { ACTIVE_GROUPS.set(groups); }
    public static void clearActiveGroups() { ACTIVE_GROUPS.remove(); }

    private static boolean matchesGroupName(ItemStack stack, String name) {
        try {
            // 1) Dedicated server: the sorting player's groups synced from their client (set by WarehouseEngine).
            java.util.Map<String, java.util.List<String>> active = ACTIVE_GROUPS.get();
            java.util.List<String> members = active != null ? active.get(name) : null;
            // 2) Single-player / integrated server: the local config holds the real edited groups.
            if (members == null || members.isEmpty()) {
                members = com.example.inventoryorganizer.config.OrganizerConfig.get().getCustomGroup(name);
            }
            if (members != null && !members.isEmpty()) {
                String id = getItemId(stack);
                String path = id.contains(":") ? id.substring(id.indexOf(':') + 1) : id;
                return members.contains(id) || members.contains(path);
            }
        } catch (Throwable ignored) {}
        return matchesGroup(stack, name);
    }

    public static boolean matchesGroup(ItemStack stack, String groupName) {
        if (groupName == null || groupName.isEmpty()) return false;
        String sid = getItemId(stack);
        switch (groupName) {
            case "blocks":
                // Full 1×1×1 solid blocks only (must match InventorySorter.getDefaultItemsForGroup).
                return isFullSolidBlock(stack) && !stack.has(DataComponents.FOOD);
            case "food":
                return stack.has(DataComponents.FOOD) || isAnyId(sid, FOOD_IDS);
            case "arrows":
                return stack.is(net.minecraft.tags.ItemTags.ARROWS);
            case "logs":
                return stack.is(net.minecraft.tags.ItemTags.LOGS);
            case "boats":
                return stack.is(net.minecraft.tags.ItemTags.BOATS)
                    || stack.is(net.minecraft.tags.ItemTags.CHEST_BOATS);
            case "tools":
                return stack.is(net.minecraft.tags.ItemTags.PICKAXES)
                    || stack.is(net.minecraft.tags.ItemTags.AXES)
                    || stack.is(net.minecraft.tags.ItemTags.SHOVELS)
                    || stack.is(net.minecraft.tags.ItemTags.HOES)
                    || isAnyId(sid, TOOL_EXTRA_IDS);
            case "weapons":
                return stack.is(net.minecraft.tags.ItemTags.SWORDS)
                    || stack.is(net.minecraft.tags.ItemTags.AXES)
                    || isAnyId(sid, "bow", "crossbow", "trident", "mace");
            case "armor":
                return stack.is(net.minecraft.tags.ItemTags.HEAD_ARMOR)
                    || stack.is(net.minecraft.tags.ItemTags.CHEST_ARMOR)
                    || stack.is(net.minecraft.tags.ItemTags.LEG_ARMOR)
                    || stack.is(net.minecraft.tags.ItemTags.FOOT_ARMOR)
                    || isAnyId(sid, "elytra", "shield");
            default:
                return categoryMatches(stack, groupNameToCategory(groupName));
        }
    }

    /**
     * Usable utility items in the "tools" group beyond the mining-tool tags. MUST stay identical to
     * {@link InventorySorter#TOOL_EXTRA_IDS} (client generation) — change both together.
     */
    static final String[] TOOL_EXTRA_IDS = {
        "shears", "flint_and_steel", "fishing_rod", "brush",
        "ender_pearl", "ender_eye", "fire_charge", "snowball", "egg", "experience_bottle",
        "spyglass", "goat_horn", "lead", "name_tag", "saddle", "carrot_on_a_stick", "warped_fungus_on_a_stick",
        "bundle", "compass", "recovery_compass", "clock", "map", "filled_map",
        "bucket", "water_bucket", "lava_bucket", "milk_bucket", "powder_snow_bucket",
        "cod_bucket", "salmon_bucket", "pufferfish_bucket", "tropical_fish_bucket", "axolotl_bucket", "tadpole_bucket",
        "book", "writable_book", "written_book", "knowledge_book",
        "firework_rocket", "totem_of_undying", "honeycomb", "ink_sac", "glow_ink_sac", "armor_stand"
    };

    /** Every vanilla edible item — id fallback for "food" (FOOD component unreliable in 26.1). MUST stay
     *  identical to {@link InventorySorter#FOOD_IDS}. */
    static final String[] FOOD_IDS = {
        "apple", "golden_apple", "enchanted_golden_apple", "golden_carrot", "carrot", "potato",
        "baked_potato", "poisonous_potato", "beetroot", "beetroot_soup", "bread", "cookie",
        "melon_slice", "dried_kelp", "sweet_berries", "glow_berries", "honey_bottle", "chorus_fruit",
        "pumpkin_pie", "mushroom_stew", "rabbit_stew", "suspicious_stew",
        "beef", "cooked_beef", "porkchop", "cooked_porkchop", "mutton", "cooked_mutton",
        "chicken", "cooked_chicken", "rabbit", "cooked_rabbit", "cod", "cooked_cod",
        "salmon", "cooked_salmon", "tropical_fish", "pufferfish", "rotten_flesh", "spider_eye"
    };

    /** True when the full item id ({@code minecraft:x}) path equals any of the given vanilla paths. */
    private static boolean isAnyId(String sid, String... paths) {
        String path = sid.contains(":") ? sid.substring(sid.indexOf(':') + 1) : sid;
        for (String p : paths) if (path.equals(p)) return true;
        return false;
    }

    /** True when this stack places an actual block in the world (mapping-stable; no instanceof). */
    private static boolean isPlaceableBlock(ItemStack stack) {
        try {
            return net.minecraft.world.level.block.Block.byItem(stack.getItem())
                != net.minecraft.world.level.block.Blocks.AIR;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * True when this stack places a FULL 1×1×1 solid block (collision-shape full cube). Must match
     * {@link InventorySorter}'s isFullSolidBlock so warehouse routing agrees with the "blocks" group.
     */
    private static boolean isFullSolidBlock(ItemStack stack) {
        try {
            net.minecraft.world.level.block.Block b = net.minecraft.world.level.block.Block.byItem(stack.getItem());
            if (b == net.minecraft.world.level.block.Blocks.AIR) return false;
            return b.defaultBlockState().isCollisionShapeFullBlock(
                net.minecraft.world.level.EmptyBlockGetter.INSTANCE, net.minecraft.core.BlockPos.ZERO);
        } catch (Throwable t) {
            return false;
        }
    }

    public static boolean categoryMatches(ItemStack stack, int targetCat) {
        if (targetCat == CAT_SPLASH_POTION) {
            return getItemId(stack).equals("minecraft:splash_potion");
        }
        int cat = getCategory(stack);
        if (cat == targetCat) return true;
        return poolCategoryOf(cat) == targetCat;
    }

    public static int poolCategoryOf(int cat) {
        switch (cat) {
            case CAT_COOKED:
            case CAT_RAW_FOOD:
                return CAT_FOOD;
            case CAT_LOG:
            case CAT_STONE:
            case CAT_NETHER:
            case CAT_END:
                return CAT_BLOCK;
            case CAT_ORE:
                return CAT_VALUABLE;
            case CAT_BOAT:
            case CAT_REDSTONE:
                return CAT_UTILITY;
            default:
                return cat;
        }
    }

    public static int getCategory(ItemStack stack) {
        String id = getItemId(stack);

        if (id.contains("command_block") || id.equals("minecraft:barrier") ||
                id.equals("minecraft:structure_block") || id.equals("minecraft:structure_void") ||
                id.equals("minecraft:jigsaw") || id.equals("minecraft:debug_stick") ||
                id.equals("minecraft:light")) {
            return CAT_CREATIVE;
        }

        // --- Explicit ITEM overrides (must run before the broad substring heuristics below) ---
        // End-dimension mob-drop / loot ITEMS no later heuristic catches → pin to END. (The nether
        // brewing/mob items are NOT here: the nether substring heuristic below maps them to CAT_NETHER.)
        // Mirrors InventorySorter.
        switch (id) {
            case "minecraft:dragon_breath":
            case "minecraft:shulker_shell":
            case "minecraft:end_crystal":
            case "minecraft:dragon_head":
            case "minecraft:chorus_fruit":
                return CAT_END;
            default:
                break;
        }

        if (id.contains("_ore") || id.equals("minecraft:ancient_debris") ||
                id.equals("minecraft:raw_iron") || id.equals("minecraft:raw_gold") || id.equals("minecraft:raw_copper") ||
                id.equals("minecraft:raw_iron_block") || id.equals("minecraft:raw_gold_block") || id.equals("minecraft:raw_copper_block") ||
                id.equals("minecraft:iron_ingot") || id.equals("minecraft:gold_ingot") || id.equals("minecraft:copper_ingot") ||
                id.equals("minecraft:netherite_ingot") || id.equals("minecraft:netherite_scrap") ||
                id.equals("minecraft:iron_nugget") || id.equals("minecraft:gold_nugget") ||
                id.equals("minecraft:diamond") || id.equals("minecraft:emerald") ||
                id.equals("minecraft:coal") || id.equals("minecraft:lapis_lazuli") ||
                id.equals("minecraft:amethyst_shard") || id.equals("minecraft:quartz") ||
                id.equals("minecraft:diamond_block") || id.equals("minecraft:emerald_block") ||
                id.equals("minecraft:gold_block") || id.equals("minecraft:iron_block") ||
                id.equals("minecraft:copper_block") || id.equals("minecraft:coal_block") ||
                id.equals("minecraft:lapis_block") || id.equals("minecraft:netherite_block") ||
                id.equals("minecraft:amethyst_block") || id.equals("minecraft:quartz_block")) {
            return CAT_ORE;
        }

        if (id.contains("cooked_") || id.equals("minecraft:baked_potato")) {
            return CAT_COOKED;
        }

        if (id.equals("minecraft:beef") || id.equals("minecraft:porkchop") ||
                id.equals("minecraft:chicken") || id.equals("minecraft:mutton") ||
                id.equals("minecraft:rabbit") || id.equals("minecraft:cod") ||
                id.equals("minecraft:salmon") || id.equals("minecraft:tropical_fish")) {
            return CAT_RAW_FOOD;
        }

        if (id.equals("minecraft:redstone") || id.contains("redstone_torch") ||
                id.contains("redstone_lamp") || id.equals("minecraft:repeater") ||
                id.equals("minecraft:comparator") || id.contains("piston") ||
                id.equals("minecraft:observer") || id.equals("minecraft:lever") ||
                id.contains("tripwire_hook") || id.equals("minecraft:hopper") ||
                id.equals("minecraft:dropper") || id.equals("minecraft:dispenser") ||
                id.contains("daylight_detector") || id.equals("minecraft:target") ||
                id.equals("minecraft:note_block") || id.contains("_rail") ||
                id.equals("minecraft:rail") || id.equals("minecraft:tnt") ||
                id.contains("redstone_block")) {
            return CAT_REDSTONE;
        }
        // Trapped chest is a storage block (groups with the blocks/chests), not redstone.

        if (id.contains("_boat") || id.contains("_raft")) {
            return CAT_BOAT;
        }

        if (id.contains("_slab") || id.contains("_stairs") || id.contains("_fence") ||
                id.contains("_trapdoor") || id.contains("_door") ||
                id.contains("_pressure_plate") || id.contains("_button") ||
                id.contains("_pane") || id.contains("iron_bars") || id.contains("_wall")) {
            return CAT_PARTIAL;
        }

        {
            String path = id.contains(":") ? id.substring(id.indexOf(':') + 1) : id;
            if (path.endsWith("_log") || path.endsWith("_wood") || path.endsWith("_stem") ||
                    path.equals("bamboo_block")) {
                return CAT_LOG;
            }
        }

        if (id.contains("sapling") || id.contains("_flower") ||
                id.equals("minecraft:dandelion") || id.equals("minecraft:poppy") ||
                id.contains("_tulip") || id.contains("orchid") || id.contains("bluet") ||
                id.contains("allium") || id.contains("lily_of_the_valley") ||
                id.contains("oxeye_daisy") || id.contains("cornflower") ||
                id.contains("sunflower") || id.contains("lilac") || id.contains("peony") ||
                id.contains("rose_bush") || id.contains("_fern") ||
                id.contains("dead_bush") || id.contains("kelp") ||
                id.contains("lily_pad") || id.contains("dripleaf") ||
                id.contains("spore_blossom") || id.contains("cactus") ||
                (id.contains("bamboo") && !id.contains("bamboo_block")) ||
                id.contains("sugar_cane") || id.contains("vine") ||
                id.contains("glow_lichen") || id.contains("hanging_roots") ||
                id.contains("nether_sprouts") || id.contains("twisting_vines") ||
                id.contains("weeping_vines") || id.contains("torchflower") ||
                id.contains("pitcher") || id.contains("sea_pickle") ||
                id.contains("short_grass") || id.contains("tall_grass") ||
                id.contains("large_fern") || id.contains("seagrass") ||
                (id.contains("azalea") && !id.contains("leaves")) ||
                (id.contains("mushroom") && !id.contains("mushroom_block") && !id.contains("mushroom_stew")) ||
                id.contains("eyeblossom") || id.contains("wildflowers") ||
                id.contains("firefly_bush") || id.contains("leaf_litter") ||
                id.equals("minecraft:bush") || id.contains("dry_grass") ||
                id.contains("pink_petals")) {
            return CAT_PLANT;
        }

        if (id.equals("minecraft:stone") || id.equals("minecraft:cobblestone") ||
                id.contains("granite") || id.contains("diorite") || id.contains("andesite") ||
                id.contains("sandstone") || id.contains("mossy_cobblestone") ||
                id.contains("stone_bricks") || id.contains("calcite") ||
                id.contains("dripstone") || id.contains("tuff") || id.contains("deepslate") ||
                id.contains("prismarine") || id.equals("minecraft:obsidian") ||
                id.contains("bedrock") || id.contains("smooth_stone") ||
                id.contains("chiseled_stone") || id.equals("minecraft:gravel") ||
                id.equals("minecraft:sand") || id.contains("red_sand")) {
            return CAT_STONE;
        }

        if (id.contains("dried_ghast") ||
                id.contains("netherrack") || id.contains("nether_brick") ||
                id.contains("nether_wart") || id.contains("magma_block") ||
                id.contains("soul_sand") || id.contains("soul_soil") ||
                id.contains("glowstone") || id.contains("blaze_rod") ||
                id.contains("blaze_powder") || id.contains("ghast_tear") ||
                id.contains("nether_star") || id.contains("nether_quartz") ||
                id.contains("crimson") || id.contains("warped") ||
                id.contains("basalt") || id.contains("blackstone") ||
                id.contains("nylium") || id.contains("shroomlight") ||
                id.contains("wart_block") || id.contains("crying_obsidian") ||
                id.contains("respawn_anchor") || id.contains("magma_cream") ||
                id.contains("soul_torch") || id.contains("soul_lantern")) {
            return CAT_NETHER;
        }

        if (id.contains("end_stone") || id.contains("purpur") || id.contains("end_rod") ||
                (id.contains("chorus_") && !id.equals("minecraft:chorus_fruit")) ||
                id.equals("minecraft:dragon_egg")) {
            return CAT_END;
        }

        if (id.contains("sword") && !id.contains("_block") && !id.contains("_ore") ||
                id.contains("bow") && !id.contains("cross") && !id.contains("bowl") ||
                id.contains("crossbow") || id.contains("trident") || id.contains("mace") ||
                id.contains("spear")) {
            return CAT_WEAPON;
        }
        if (id.contains("pickaxe") || id.contains("_axe") ||
                id.contains("shovel") || id.contains("hoe") ||
                id.contains("shears") || id.contains("flint_and_steel") ||
                id.contains("fishing_rod")) {
            return CAT_TOOL;
        }
        if (id.contains("helmet") || id.contains("chestplate") || id.contains("leggings") ||
                id.contains("boots") || id.contains("shield") || id.contains("elytra") ||
                id.contains("turtle_helmet")) {
            return CAT_ARMOR;
        }

        if (id.contains("resin") || id.contains("vault") ||
                id.contains("decorated_pot") || id.contains("creaking_heart") ||
                id.contains("_block") || id.contains("plank") || id.contains("log") ||
                id.contains("_wood") ||
                id.contains("stone") || id.contains("dirt") || id.contains("sand") ||
                id.contains("gravel") || id.contains("glass") && !id.contains("glass_bottle") || id.contains("brick") ||
                id.contains("concrete") || id.contains("wool") || id.contains("terracotta") ||
                id.contains("slab") || id.contains("stairs") || id.contains("wall") ||
                id.contains("fence") || id.contains("door") || id.contains("pane") ||
                id.contains("copper") || id.contains("deepslate") || id.contains("tuff") ||
                id.contains("mud") || id.contains("clay") || id.contains("coral") ||
                id.contains("sculk") || id.contains("prismarine") || id.contains("sea_lantern") ||
                id.contains("netherrack") || id.contains("nether_brick") ||
                id.contains("basalt") || id.contains("blackstone") ||
                id.contains("soul_sand") || id.contains("soul_soil") ||
                id.contains("nylium") || id.contains("shroomlight") ||
                id.contains("wart_block") || id.contains("crying_obsidian") ||
                id.contains("end_stone") || id.contains("purpur") ||
                id.contains("_ore") || id.contains("obsidian") ||
                id.contains("moss") || id.contains("dripstone") || id.contains("calcite") ||
                id.contains("amethyst_block") || id.contains("budding_amethyst") ||
                id.contains("ice") || id.contains("hay_block") ||
                id.contains("sponge") || id.contains("magma_block") ||
                id.contains("barrel") || id.contains("chest") ||
                id.contains("crafting_table") || id.contains("furnace") ||
                id.contains("smoker") || id.contains("blast_furnace") ||
                id.contains("smithing_table") || id.contains("fletching_table") ||
                id.contains("cartography_table") || id.contains("loom") ||
                id.contains("bookshelf") || id.contains("beacon") ||
                id.contains("dispenser") || id.contains("dropper") ||
                id.contains("observer") || id.contains("piston") ||
                id.contains("tnt") || id.contains("target") ||
                id.contains("daylight_detector") || id.contains("note_block") ||
                id.contains("jukebox") || id.contains("redstone_lamp") ||
                id.contains("bamboo_mosaic") ||
                id.contains("mangrove_roots") || id.contains("cherry_log") ||
                id.contains("leaves") || id.contains("mushroom_block") ||
                id.contains("mycelium") || id.contains("podzol") ||
                id.contains("farmland") || id.contains("grass_block") ||
                id.contains("shulker_box") ||
                id.contains("lodestone") || id.contains("respawn_anchor") ||
                id.contains("bedrock")) {
            return CAT_BLOCK;
        }
        if (id.contains("lantern") || id.contains("campfire") ||
                id.contains("candle") || id.contains("chain") ||
                id.contains("bell") || id.contains("lightning_rod") || id.contains("end_rod") ||
                id.contains("anvil") || id.contains("cauldron") ||
                id.contains("grindstone") || id.contains("stonecutter") ||
                id.contains("composter") || id.contains("brewing_stand") ||
                id.contains("enchanting_table") || id.contains("lectern") ||
                id.contains("conduit") || id.contains("hopper") ||
                id.contains("rail") || id.contains("repeater") ||
                id.contains("comparator") ||
                id.contains("_bed") || id.contains("banner") || id.contains("carpet") ||
                id.contains("_sign") || id.contains("_head") || id.contains("skull") ||
                id.contains("sapling") || id.contains("azalea") ||
                id.contains("dripleaf") || id.contains("cobweb") ||
                id.contains("ladder") || id.contains("scaffolding") ||
                id.contains("_trapdoor") || id.contains("pressure_plate") ||
                id.contains("lever") || id.contains("tripwire_hook") || id.contains("button")) {
            return CAT_UTILITY;
        }
        if (isFood(id)) {
            return CAT_FOOD;
        }
        if (id.equals("minecraft:potion") || id.equals("minecraft:splash_potion") ||
                id.equals("minecraft:lingering_potion")) {
            return CAT_POTION;
        }
        if (id.equals("minecraft:arrow") || id.equals("minecraft:spectral_arrow") ||
                id.equals("minecraft:tipped_arrow")) {
            return CAT_ARROW;
        }
        if (id.contains("torch") || id.contains("bucket") || id.contains("compass") ||
                id.contains("clock") || id.contains("map") || id.contains("lead") ||
                id.contains("name_tag") || id.contains("saddle") || id.contains("ender_pearl") ||
                id.contains("ender_eye") || id.contains("firework") || id.contains("arrow") ||
                id.contains("rocket") || id.contains("bone_meal") || id.contains("dye") ||
                id.contains("book") || id.contains("paper") || id.contains("string") ||
                id.contains("stick") || id.contains("feather") ||
                id.contains("bundle") || id.contains("spyglass") ||
                id.contains("goat_horn") || id.contains("music_disc") ||
                id.contains("spawn_egg") ||
                id.contains("experience_bottle") || id.contains("glass_bottle") ||
                id.contains("painting") || id.contains("item_frame") ||
                id.contains("armor_stand") || id.contains("minecart") ||
                id.contains("boat") || id.contains("raft") ||
                id.contains("snowball") || id.contains("egg") ||
                id.contains("fire_charge") ||
                id.contains("wind_charge") || id.contains("breeze_rod") ||
                id.contains("trial_key") || id.contains("ominous_bottle") ||
                id.contains("harness") || id.equals("minecraft:heavy_core") ||
                id.equals("minecraft:golden_apple") || id.equals("minecraft:enchanted_golden_apple")) {
            return CAT_UTILITY;
        }
        return CAT_MISC;
    }

    private static boolean isFood(String id) {
        if (id.equals("minecraft:golden_apple") || id.equals("minecraft:enchanted_golden_apple")) return false;
        if (id.contains("apple")) return true;
        if (id.contains("bread")) return true;
        if (id.contains("beef")) return true;
        if (id.contains("pork")) return true;
        if (id.contains("mutton")) return true;
        if (id.contains("cod")) return true;
        if (id.contains("salmon")) return true;
        if (id.contains("carrot")) return true;
        if (id.contains("melon_slice")) return true;
        if (id.contains("cookie")) return true;
        if (id.contains("pie")) return true;
        if (id.contains("stew")) return true;
        if (id.contains("soup")) return true;
        if (id.contains("berr")) return true;
        if (id.contains("cooked_")) return true;
        if (id.contains("honey_bottle")) return true;
        if (id.contains("chorus_fruit")) return true;
        if (id.contains("chicken")   && !id.contains("_egg"))                          return true;
        if (id.contains("potato")    && !id.contains("poison"))                        return true;
        if (id.contains("dried_kelp") && !id.contains("_block"))                       return true;
        if (id.contains("rabbit")    && !id.contains("_hide") && !id.contains("_foot")) return true;
        if (id.contains("beetroot")  && !id.contains("_seeds"))                        return true;
        return false;
    }
}
