# Inventory Organizer 1.6.2 — Changelog

A fast community-requested fix: the Remote Crafting panel's layout is now configurable, so it can be moved out of the way of other mods (like JEI) that also dock to the screen's edge, plus a round of GUI-overlap and scroll-behavior fixes reported by players.

## 🧭 Configurable Remote Crafting HUD layout

Reported in [#4](https://github.com/Zefryxis/InOr/issues/4): the nearby-chest-contents panel next to the crafting/inventory screen always docked to the right, colliding with JEI's recipe panel. Layout is now configurable across three independent pieces:

- **Materials panel** (the search/qty/scroll controls and the scrollable chest-content list — these move together) — now toggleable between **left** or **right** of the GUI via a small `⇄` button on the panel. Automatically clears the vanilla recipe book (and its category tabs) when it's open on that side.
- **Quick-action buttons** (the existing K/S/OI/HUD/Wh row on the survival inventory screen) — now cycle between **above**, **below**, or **right** of the GUI via a small `⇅` button. When set to the right, they stack vertically instead of overflowing horizontally.
- **Deposit ("send item to chest") slot** — now **freely draggable anywhere on screen**: click its small move handle, drag, and release to drop it wherever you like.
- If the Materials panel and the quick-action buttons would both end up on the right at the same time, one is automatically nudged out of the way — no dead-end settings, no overlap.
- All positions persist per-config and are resolution/GUI-Scale independent.
- Fully localized in all 9 supported languages.

## 🐛 Bug fixes

- **Remote-crafting deposit now properly OSTs with the chest's real rules.** Depositing an item into a chest (holding an item and clicking the deposit slot, or cancelling a recipe) used to route by a blind "first chest/slot with room" check that ignored your configured slot rules entirely (tier, group, specific item, `cg:` custom groups) — so a rule like `cg:blocks` on a linked chest was silently ignored, and items only ever spilled into the "correct" chest once the first one filled up completely. It now routes the same way a manual OST/warehouse sort would: best-matching chest first, placed only in a rule-accepting slot.
- **Fixed placement landing in the wrong slot within the correct chest — for real this time.** An item could land in a designated specific slot, only to have a follow-up "tidy" cleanup pass immediately pull it back out and redrop it starting from slot 0, ignoring the rule entirely. Placement now ranks every accepting empty slot by specificity (exact item > type > group > generic) *throughout the whole process, including the final tidy* — this also improves the regular manual OST/warehouse sort, since it shares the same placement logic.
- Fixed a duplicate OI/K/S/Wh button row that briefly appeared on the crafting table and survival inventory screens alongside the existing one.
- Fixed the Materials search box (and its other header controls) not moving when the panel's left/right position was flipped — only the list itself used to move.
- **Fixed chest data bleeding between different worlds/servers.** The mod's config file is a single global JSON shared across every world you've ever played — known chests, per-chest profiles, and local warehouse-group links were stored as bare coordinates with no world/dimension identifier, so a chest at the same coordinates in two different worlds/servers (or even two dimensions on the same server) was treated as the same chest, mixing links and profiles together. All of these now carry a world+dimension tag; existing bindings from before this fix keep working everywhere (no forced reassignment, no data loss) — only newly-added ones are properly scoped going forward.
- **Scroll-to-move now only acts while the cursor is over the container's own panel.** Reported in [#5](https://github.com/Zefryxis/InOr/issues/5): scrolling anywhere on screen — including while trading with a villager or mid-craft — could move items in your hotbar/inventory. Scrolling now does nothing at all unless the mouse is actually over the vanilla inventory/chest panel, villager trading screens are excluded entirely, and result slots (crafting/furnace/trading output) can no longer be scrolled away. Also added a dedicated **Scroll-move** on/off toggle in Special Settings for anyone who'd rather turn the feature off completely.
- **Fixed overlapping buttons/text across the mod's screens at GUI Scale 3+.** Several screens pack a lot of controls onto one page, and at high GUI Scale (less logical space to work with) some of that could end up stacked on top of each other — most notably the Custom Group editor's Save button hiding behind the item grid. Rather than reflowing each screen's layout to fit whatever cramped space is left (tried before, never held up), every mod screen now renders itself "zoomed back out" past GUI Scale 2, so it always keeps at least as much room to work with as GUI Scale 2 provides, no matter how high your actual GUI Scale is set. GUI Scale 3 only gets zoomed back out halfway (to an effective 2.5), since some players sit at scale 3 specifically to see more detail — GUI Scale 4+ still gets the full treatment.
- **Fixed the Kits screen ending up confined to the top-left corner of the screen after a window resize or a live GUI Scale change at GUI Scale 3+.** It rebuilt its buttons at the wrong (real, uncapped) size on resize while still applying the counter-zoom shrink meant for the larger virtual size, instead of both agreeing.

---

**Version**: 1.6.2
**Platform**: Modrinth (Fabric) — MC 26.2
**Author**: Zefryxis
