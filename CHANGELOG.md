# Changelog

## 1.6.2-beta — 26.2

A fast community-requested fix: the Remote Crafting panel's layout is now configurable, so it can be moved out of the way of other mods (like JEI) that also dock to the screen's edge. Released as a beta — tested thoroughly in single-player, dedicated-server testing still pending.

### Configurable Remote Crafting HUD layout
- Reported in [#4](https://github.com/Zefryxis/InOr/issues/4): the nearby-chest-contents panel always docked to the right, colliding with JEI's recipe panel. Split into three independently positionable pieces: the Materials panel — search/qty/scroll + chest-content list (left/right, `⇄` button, clears the vanilla recipe book and its tabs when open on that side), the existing K/S/OI/HUD/Wh quick-action row (above/below/right, `⇅` button, stacks vertically when right), and the deposit slot (freely draggable anywhere on screen via its move handle).
- If the Materials panel and the quick-action buttons would both end up on the right, one auto-flips out of the way — no dead-end settings, no overlap.
- Positions persist per-config, independent of resolution/GUI Scale. Fully localized in all 9 languages.

### Bug fixes
- Remote-crafting deposit now properly OSTs using the chest's real configured slot rules (tier/group/specific item/`cg:` custom groups) instead of a blind first-available-slot check that ignored them entirely.
- Fixed placement landing in the wrong slot within the correct chest — the item would land in its designated slot, then an immediate follow-up "tidy" pass would pull it back out and redrop it starting from slot 0. Placement now ranks by specificity throughout, including the final tidy — also improves the regular manual OST/warehouse sort, which shares the same placement logic.
- Fixed chest data bleeding between different worlds/servers: known chests, per-chest profiles, and local warehouse-group links now carry a world+dimension tag (the config file is one global JSON shared across every world ever played, and previously stored bare coordinates only). Existing bindings keep working everywhere; only newly-added ones are properly scoped.
- Fixed a duplicate OI/K/S/Wh button row briefly appearing alongside the existing one.
- Fixed the Materials search box not moving along with the panel when its side was flipped.
- Scroll-to-move now only acts while the cursor is over the container's own panel — reported in [#5](https://github.com/Zefryxis/InOr/issues/5), scrolling anywhere on screen (including mid-trade or mid-craft) could move hotbar/inventory items. Villager trading screens are excluded entirely, crafting/furnace/trading result slots can't be scrolled away, and a Scroll-move on/off toggle was added in Special Settings.
- Fixed overlapping buttons/text across the mod's screens at GUI Scale 3+ (also from #5) — every mod screen now counter-zooms past GUI Scale 2 instead of reflowing, so it always keeps at least as much layout room as GUI Scale 2 provides.

## 1.6.1 — 26.2

A small but meaningful update: a brand-new in-game Tutorial for players still finding their footing with the mod, plus a fix for a false-positive bug affecting vanilla containers.

### New: interactive Tutorial
- A short, animated in-engine walkthrough of how Inventory Organizer thinks — exact-item slot rules, automatic sorting via Ranks (with a concrete Netherite vs. Diamond pickaxe example), same-tier tie-breaking, Warehouse linking, Bundle Profile pairing, potion rules, and custom-group ranking.
- Includes a quick-reference cheat-sheet table covering all three "which criterion decided" scenarios, plus an explicit note that being first in the priority list doesn't mean the other criteria are ignored — it just gets the first say, handing off to the next one only on a tie.
- Auto-plays once on first launch, and is replayable anytime from a new "▶ Tutorial" button in the Guide.
- Ends with an honest disclaimer and a link to the [issue tracker](https://github.com/Zefryxis/InOr/issues) for reporting bugs.
- Fully localized in all 9 supported languages.

### Bug fix
- Vanilla Dispenser, Dropper, and Hopper were wrongly flagged as "modded" containers (OST/profile buttons + a bogus "Modded chest" warning), because they use their own screen classes rather than the vanilla chest screen and fell through the modded-container detector. Now correctly excluded and ignored.

## 1.6.0 — 26.2

A UX and reliability pass: the mod can now start dead simple and grow with you, sorting defaults got a research-backed tune-up, and a long-standing Bundle Profiles bug that made profiles match almost anything is fixed for good.

### Three complexity modes: Simple / Advanced / Expert
- New global setting controlling how much of the config is exposed: **Simple** (slot rules, bundles, basic toggles; sorting/groups on curated defaults), **Advanced** (+ custom groups and slot-tier/Ranks editing), **Expert** (everything, as before).
- First-launch picker asks new installs which mode to start in (Simple recommended). Switch anytime from Special Settings behind a confirmation dialog — lowering the level only *hides* options, nothing is deleted.

### Research-backed sorting defaults
- Enchantment priority reordered by community-consensus value (Mending/Unbreaking first, curses last); `fire_protection` additionally demoted to just above the curses as the weakest protection enchant.
- Copper fixed in the material ordering (used to fall back to "unranked/last" until the Ranks screen was opened once).
- Applied to existing configs automatically, not just fresh installs.

### Bundle Profiles — actually work now
- **Critical fix:** saving a bundle profile used to write a literal `"any"` (match-everything) rule into every untouched editor slot, so a profile with one real rule (e.g. "Pickaxe only") would also silently accept anything else. Only rules you actually set are saved now; existing profiles are cleaned up automatically.
- Empty bundles can now be claimed by a profile (previously only a bundle that already had a matching item inside could ever be recognized — a fresh bundle never started filling).
- An unconfigured (empty) profile no longer claims a bundle or ejects its contents.
- Removed the old default "Bundle" storage preset tab — a non-functional leftover from before Bundle Profiles existed. Bundle filling is now exclusively driven by the real Bundle Profiles feature.

### UI fixes and polish
- Fixed the Storage tab selection highlight landing on the wrong tab (position math had drifted out of sync after the Bundle Profiles button was added).
- Dialogs (warnings, confirmations, the mode picker) now defensively size themselves to the player's GUI Scale/window size, so they never overflow the screen or overlap their own buttons.
- Slightly larger text at GUI Scale 3–4 for readability (capped +2%/+4% bump; layout and click areas unaffected).
- Filled in 7 previously untranslated strings (Bundle Profiles screen, Switch setup title) across 8 of the 9 supported languages.

### Localization
- All new UI text (complexity modes, onboarding, mode-switch confirmation) fully localized in all 9 supported languages.

### A note on what's next
Inventory Organizer is in a good, stable place with 1.6.0. We're slowing down InOr development for a while to work on a couple of **other mods**, with the plan to eventually bring them together into a single, optional **bundle**. InOr updates will come less often for a bit — but more (and more varied) content is on the way.

## 1.5.4 — 26.2

A reliability + performance pass.

### Version Support Policy
- **From here on we update Minecraft 26.2 and up. Versions 26.1, 26.1.1 and 26.1.2 no longer receive support — only on community request.** The large code differences between versions make it hard to keep updating all of them; 26.1 / 26.1.1 / 26.1.2 differed only very slightly so we could maintain those three together, but we are now moving up to 26.2.

### Shulker profiles
- Shulkers with a profile now keep a stable ID through break → pickup → place (custom item component + loot-table overrides for all 17 colors), so a carried shulker no longer loses its per-shulker layout.

### Potion slot rules
- A `pot:` rule (e.g. `pot:fire_resistance`) now matches all three bottle types — drinkable, splash, lingering — of that effect.
- **Ranks → Potion Type Order** sets which bottle type is preferred when several are available.
- Fixed related sorting bugs: first-pass `pot:` matching, hotbar-to-hotbar grabbing the wrong potion (effect is now verified, not just the item ID), and moving a potion from an unruled hotbar slot into a specific main-inventory rule slot.

### Performance (lighter on weak machines)
- Auto-refill and trash scans are throttled to a few times per second instead of every client tick — the idle inventory scan that ran 20×/second is gone, with no change in responsiveness (both already self-cooldown).
- Sort swap routines compute each item ID once per slot instead of repeatedly.
- Color-variant family matching (wool, concrete, shulker box, bundle, …) is now an O(1) precomputed lookup instead of a per-call nested scan. Identical results.
- A string allocation was hoisted out of the group-matching inner loop. Sort results are unchanged — only wasted work removed.

### Guide & localization
- New guide section "Potion slot rules" in all 9 languages.
- Fixed stray straight-quote characters that made the German and Hungarian language files invalid JSON (which could drop the whole German/Hungarian guide to English). Both now parse cleanly.

## 1.5.2 — 26.2+

**Version Support Policy Update:** Starting from Minecraft 26.2, we will only maintain the three most recent Minecraft versions going forward. Versions 26.1, 26.1.1, and 26.1.2 will no longer receive updates, though we may backport critical fixes upon community request.

**Reason:** The significant codebase changes introduced in 26.2 make it increasingly difficult to maintain multiple older versions simultaneously. While versions 26.1, 26.1.1, and 26.1.2 shared minimal differences and could be supported together, the jump to 26.2 represents a breaking point in the API that requires substantial refactoring. Going forward, supporting only the latest three versions allows us to allocate resources more efficiently while maintaining code quality and development velocity.

### Compatibility
- Updated all mixin accessors and event handlers for Minecraft 26.2+ API changes.
- Verified warehouse, crafting, sorting, and group systems across 26.2 environments.

## 1.5.1 — 26.1 / 26.1.1 / 26.1.2

- **Fix: custom groups now work on dedicated servers.** Server-side warehouse/OST sorting ignored items you hand-added to a group (e.g. a vanilla axe in "weapons", a chest in "blocks") because the server couldn't see your locally-edited groups. The client now syncs its group membership to the server (on join and before each sort, re-sent only when it changed), so group routing matches your edits everywhere — single-player and multiplayer alike.
- Polish: removed leftover diagnostic logging (materials panel + per-item warehouse sort spam) so server/client logs stay clean.

### Switch — automatic tool swapper (single-player)

A new **Auto switch** mode for the inventory config. When enabled (single-player only), the mod keeps the
best tool for whatever your crosshair is on in a designated **switch slot**, drawing tools from a pool of
**storage slots**:

- Look at a block → the right tool (the block's mineable category, or your per-block override), picking the
  fastest available piece. Look at a hostile mob → a weapon (sword by default).
- The two modes (**Plain** / **Auto switch**) share the same block/group/item slot rules — only the tools
  and the switch/storage slots differ. The base layout always has 1 switch + 1 storage slot (movable, not
  deletable); add more storage slots as needed.
- **Tools & Rules** screen: choose which tool types the swapper manages, the mob weapon (sword/axe/highest
  damage), the on-air behaviour (keep / restore), and per-block tool overrides (e.g. "stone → axe").
- If a needed tool exists nowhere, an on-screen message tells you to add it or remove that slot.

Single-player only by design (the swap runs on the integrated server's inventory, desync-free); on servers
the mode is inactive. Fully localised in 9 languages.

## 1.5.0 — 26.1 / 26.1.1 / 26.1.2

A big update centred on **remote crafting** plus a rebuilt sorting engine.

### Remote crafting
- **Materials panel** next to the crafting/inventory GUI: lists every nearby chest and its contents, with search and a Qty box. Click a line to pull that item from that chest. The panel sticks to the GUI and follows it when the recipe book opens, and scales to any resolution / GUI scale.
- **Recipe-book integration**: at a crafting table, recipes light up green when nearby chests hold the missing ingredients; clicking pulls the parts and fills the grid. "Craft source" picks chests-first or inventory-first.
- **Deposit slot** under the inventory: drop a held stack to stash it in a nearby chest (auto-sorted), or click empty-handed to send the whole grid back — each ingredient returns to the exact chest it came from (on cancel, close, or recipe switch).

### Sorting engine rebuild
- Compute → execute → **verify + retry** loop (max 5 passes/phase) so a full inventory sorts reliably; a player message names any item that truly has no free slot.
- Built-in group contents generated from item IDs (reliable where tags aren't), so arrows, food, weapons, logs, boats, armor etc. are complete; `cg:` rules now resolve everywhere (slots, refill, bundle, trash, warehouse).
- **Bundles** are recognised and filled; armor/equipment is no longer needlessly picked up (no equip sound).

### Other
- Trash and auto-refill now work with a chest or inventory open; refill respects higher-ranked slots.
- Tier Order right panel cleaned up (old non-group sections removed); kit loading fixed.
- **Security**: closed a double-chest theft gap (the non-linked half of a foreign-linked double chest could be drained); recipe-book panel no longer vanishes after toggling the book.
- Guide updated and fully localised in 9 languages.

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
