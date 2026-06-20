# Changelog

## 1.4.2 — 26.1 / 26.1.1 / 26.1.2

### Built-in Groups — Full Materialization

Previously the 22 built-in heuristic groups (weapons, tools, armor, blocks, food, utility,
valuables, potions, splash_potions, arrows, logs, boats, plants, stone, ores, cooked, rawfood,
nether, end, partial, redstone, creative) existed only as internal category matchers. They could
not be ranked against each other in Tier Order, and editing them in the Group Editor had no effect
on actual sorting behaviour.

**All 22 built-in groups are now converted into genuine custom groups on first load.** They appear
in the Group Editor for manual item adjustments, in the Tier Order right panel for drag-ranking,
and behave identically to any hand-made custom group in every system (slot rules, auto-refill,
chest-refill, storage sorting, icon display). A versioned migration flag ensures the conversion
runs exactly once; groups you delete stay deleted, and hand-edited groups are never overwritten by
the migration.

### Group Content — Authoritative Game-Data Generation

Instead of substring and category heuristics that missed hundreds of items and misclassified
others, eight groups are now generated directly from Minecraft's own data:

| Group | Source |
|---|---|
| **blocks** | `Block.byItem(item) != Blocks.AIR` — every placeable block (~1 100+ items) |
| **food** | `DataComponents.FOOD` component — every edible item |
| **tools** | `ItemTags.PICKAXES/AXES/SHOVELS/HOES` + shears, flint & steel, fishing rod, brush |
| **weapons** | `ItemTags.SWORDS` + bow, crossbow, trident, mace |
| **armor** | `ItemTags.HEAD/CHEST/LEG/FOOT_ARMOR` + elytra, shield |
| **arrows** | `ItemTags.ARROWS` |
| **logs** | `ItemTags.LOGS` (includes stripped logs, wood, hyphae, stripped hyphae) |
| **boats** | `ItemTags.BOATS` + `ItemTags.CHEST_BOATS` |

This approach is guaranteed complete for all vanilla items and automatically covers future modded
items that register the appropriate tags or components. The `blocks` group previously held ~540
items; it now covers every lerakható block in the game.

### Category Precision — Misclassified Items Fixed

Several brewing ingredients and mob-drop items were incorrectly landing in the `blocks` group
because the nether/end heuristic matched them via substring and then the pool logic mapped them to
`CAT_BLOCK`. Fixed with explicit ID overrides that run before the heuristic:

- **→ utility:** dragon breath, shulker shell, end crystal, blaze rod, blaze powder, ghast tear,
  magma cream, glowstone dust, nether star, nether wart *(block variants unaffected)*
- **chorus fruit** removed from the END category → classified as food via the `FOOD` component

These overrides are mirrored in both `InventorySorter` (client) and `SortLogic` (server) so
behaviour is consistent everywhere.

### Tier Order — Collapsible Group Sections

The right panel of the Tier Order screen now has **foldable sections** for every category and
group. All sections start collapsed (▶). Click the header to expand (▼). This makes the panel
usable with 22+ groups without endless scrolling.

Implementation detail: click rectangles for headers and toggle buttons are recorded during the
render pass and tested in `mouseClicked`, so there is no cursor-drift between the drawn position
and the hit area. Collapsed sections are skipped by the drag-reorder logic so dragging works
correctly even when most sections are folded.

### Auto-Refill — Group Rule Fix

After materialization, slot rules of the form `cg:blocks` were no longer matched by the refill
system because `SortLogic.matchRank` does not understand the `cg:` prefix. A new
`InventoryOrganizerClient.ruleMatchesItem(rule, stack)` helper resolves `cg:` rules by looking up
group membership directly (checks both `namespace:path` and bare `path` forms). Both
`refillMatches` and `doChestRefill` now use this helper, so group-ruled slots refill correctly.

### Auto-Refill Toggle

**Keybind:** a new `Auto-Refill: Toggle` keybind (unbound by default, assignable in Controls)
switches auto-refill on/off instantly. A coloured overlay message confirms the new state
(`§aAuto-refill: ON` / `§cAuto-refill: OFF`).

**Special Settings button:** a second toggle button appears in the Special Settings screen next to
the Remote Crafting source button. The two controls share the same `autoRefillEnabled` config
flag; changing one is immediately reflected in the other.

### Group Icons

Icons for `cg:` rules in the Slot Config and Tier Order screens now show meaningful items:

- For the 22 built-in groups, the curated icon defined for the equivalent `g:` rule is reused
  (e.g. a diamond sword for weapons, an iron pickaxe for tools, a grass block for blocks).
- For hand-made custom groups the icon is the first item in the group's member list.
- Fallback is still the chest icon when the group is empty or the item cannot be resolved.

### UI / Text Fixes

- **Full-inventory warning** added to the Slot Config screen header (centred, gold text). The same
  warning appears in the first section of the inline help panel.
- **"Next update" tail removed** from the warning text in all 9 languages.
- **Inline help panel** now word-wraps every line using `font.split()` to a dynamic inner width
  (`min(360, screenWidth − 20) − 16`). Panel height adjusts automatically, so no text ever
  overflows horizontally regardless of UI scale or language.
- **Custom Group list screen** cleaned up: the Hide/Show hidden system and built-in-row branches
  were removed (all groups are real custom groups now). Bottom bar: `[+ New] [Import] [Folder]
  [Back]`.

### Guide Updates (9 languages)

- New **"Full Inventory"** section added near the top of the guide: explains that the mod is
  optimised for a tidy inventory and warns players not to be surprised if a packed hotbar sorts
  imperfectly.
- **Groups section** updated to describe the materialized built-in groups and the collapsible
  sections in Tier Order.

### Security

- **Reveal-on-open enumeration risk closed.** Previously a malicious client could rapidly open
  chests to probe which linked profiles existed on the server by reading reveal-on-open responses.
  The server now validates that the requesting player has explicit view permission before sending
  any profile data; unknown or unauthorised containers receive an empty/denied response.
- Server-side `SortLogic` continues to use only heuristic matching (it never reads
  client-side custom group config), keeping the authority boundary intact.

### Developer Commands

Two new in-game debug commands, available in **single-player only** (or via the server console
with `<playerName>` argument):

- `/InOr developer` — prints a live summary of the mod's current state: active slot rules, loaded
  custom groups, per-chest profiles, config flags.
- `/InOr disk` — dumps persistent config from disk (group lists, preference keys, profile
  assignments) for offline inspection without restarting.

Both commands are invisible to other players (sent as system messages to the issuing player only).
On a dedicated server they are accepted only from the server console, not from in-game chat.

### Translation Updates (all 9 languages)

All the following keys were added or updated across English, Hungarian, German, Spanish, French,
Portuguese (BR), Russian, Chinese (Simplified) and Japanese:

| Key | Description |
|---|---|
| `key.inventory-organizer.refill_toggle` | Keybind name in Controls screen |
| `inventory-organizer.refill.on` | Overlay confirmation: refill enabled |
| `inventory-organizer.refill.off` | Overlay confirmation: refill disabled |
| `inventory-organizer.warn.fullinv` | Full-inventory warning (short, no "next update" tail) |
| `inventory-organizer.guide.fullinv.head` | Guide section header: "Full Inventory" |
| `inventory-organizer.guide.fullinv.t1` | Guide body: full-inventory warning explanation |
| `inventory-organizer.guide.groups.t1` | Updated: mentions materialized groups and ranks |
| `inventory-organizer.guide.groups.d1` | New: explains collapsible Tier Order sections |
| `inventory-organizer.button.refill_on` | Special Settings toggle label (on state) |
| `inventory-organizer.button.refill_off` | Special Settings toggle label (off state) |

### Migration Notes

If you are upgrading from 1.3.x the following one-time migrations run automatically on first
launch. No action is required.

| Version flag | What it does |
|---|---|
| v1 | Initial materialization of the 22 built-in groups |
| v2 | Regenerates groups with category-precision fixes (dragon breath, blaze rod, etc.) |
| v4 | Regenerates groups after a failed BlockItem-gate attempt was reverted; clears stale `cg_order_*` prefs |
| v5 | Regenerates `blocks` group using `Block.byItem` (all 1 100+ placeable blocks) |
| v6 | Regenerates food/tools/weapons/armor/arrows from Minecraft item tags |
| v7 | Regenerates logs/boats from `ItemTags.LOGS` / `BOATS` / `CHEST_BOATS` |

Fresh installs run all steps in sequence and land on v7 in a single launch.

---

## 1.3.0 — Per-Chest Profiles (26.1 / 26.1.1 / 26.1.2)

- **Per-chest profile system:** assign a named sorting preset to any chest, barrel or shulker box.
  The mod remembers the profile by chest name → coordinates → sign text (in priority order) so the
  correct preset is applied automatically every time you open that container.
- **Carrying mode:** while carrying items between chests the current profile follows the cursor.
- **Death-sort timing fix:** sorting after death now waits for the death screen to fully dismiss
  before running, preventing item scattering.
- **Stack merging improvements:** partial stacks of the same item are combined before sorting slots
  are assigned.

---

## 1.0.0 — 26.1 / 26.1.1 / 26.1.2 port

First release for Minecraft Java Edition 26.1.x — the first Mojang-mappings-only Minecraft version.
All previous features carried over from the 1.21.x line plus new fixes specific to 26.1.

### New / Changed
- **Full 26.1 / 26.1.1 / 26.1.2 support.** Identical jar works on all three patch versions (`"minecraft": "~26.1"`).
- **Draggable scrollbars** added to every scroll panel: Slot Config palette, Sorting Order (Tier Order) panel, Storage Config palette, Custom Group Editor palette. Wheel still works; grab the handle on the right or click the track to jump.
- **Restored dark-theme 3D-bevel buttons** (StyledButton) across every config screen. In earlier 1.21.11+ builds this was stubbed to a vanilla button; now uses `Button.extractContents` override so visuals match the 1.20–1.21 line pixel-for-pixel.
- **Group Editor guide overlay** (`?` button) updated with live file paths to the saved-groups folder and the `import/` drop folder, plus a 26.1 notes section.
- **Item palette icons in config screens** now render as flat 2D PNG textures, with smart fallbacks for items whose registry name differs from the texture filename. Covers slabs / stairs / walls / fences / fence gates / buttons / pressure plates / signs / hanging signs / doors / trapdoors that reuse base block textures (e.g. `mossy_cobblestone_slab` → `block/mossy_cobblestone.png`), singular→plural `_brick`/`_tile` (e.g. `mud_brick_wall` → `block/mud_bricks.png`), wood-shape items via planks (`oak_button` → `block/oak_planks.png`), color-based items via wool (`red_bed`/`red_banner`/`red_carpet` → `block/red_wool.png`), animated items via first-frame (`compass` → `compass_00`, `suspicious_sand` → `suspicious_sand_0`), wood/hyphae aliases (`birch_wood` → `birch_log`, `crimson_hyphae` → `crimson_stem`), waxed copper recursion (`waxed_oxidized_cut_copper_stairs` works without explicit entries), smooth variants (`smooth_sandstone` → `sandstone_top`), and dedicated overrides for 3D-only items (shield uses the vanilla 2D slot silhouette; copper chest variants use copper-patina textures; heads/skulls have flat icons; `dried_ghast` uses the hydration-0 face).

### 26.1 architectural notes (for users who hit edge cases)
- **`new ItemStack(Items.X)` no longer works outside a loaded world** in 26.1. The mod wraps everywhere with `Holder.direct(item)` to bypass the unbound-components NPE.
- **Custom screen item rendering** bypasses `context.item()` (which silently fails when registry components aren't bound) and blits the raw PNG via `context.blit(RenderPipelines.GUI_TEXTURED, ..., 16, 16, 16, 16)`.
- **Mixin target rename:** `InventoryScreenMixin` injects into `extractRenderState` (was `render`).
- **HUD overlay** uses the new `HudElementRegistry.attachElementAfter` API (replaces deprecated `HudRenderCallback`).

### Known limitations
- Tooltips on rules inside the Slot/Sorting/Storage config screens are temporarily disabled (`GuiGraphicsExtractor` has no direct tooltip method in 26.1). Vanilla item tooltips in normal inventory still work.
- A handful of 3D-only items (`tipped_arrow`, `crossbow`, `bow`) use approximation flat textures.
- Drag-and-drop file import is replaced by the **Folder** + **Import All** buttons in the Custom Group list screen.

---

## 1.0.0 — Initial Release (1.20–1.21 line)

- OI button on player inventory and Creative inventory screens
- Rule-based slot configuration: any, group, specific type, specific item, empty locked, bundle content, custom group
- Custom Group Editor with full item palette, search, export and drag-and-drop import
- Sorting Order Config screen for tier prioritization within each category
- Storage Sorting (OST button) with configurable presets for different container sizes
- Kits system for saving and restoring inventory loadouts
- Fight Mode: automatic rate-limiting during PvP with tamper-proof timer and HUD countdown
- Storage Preset system for chests, barrels, shulker boxes
- Translations: English, Hungarian, German, Spanish, French, Portuguese (BR), Russian, Chinese (Simplified), Japanese
- Supports Minecraft 1.20.1, 1.20.4, 1.20.6, 1.21.1, 1.21.4 through 1.21.11

### Known Issues
- Custom group export and import may have occasional bugs. If groups do not load correctly after import, recreate them manually or contact support.
