# Inventory Organizer 1.6.2 — Changelog

A fast community-requested fix: the Remote Crafting panel's layout is now configurable, so it can be moved out of the way of other mods (like JEI) that also dock to the screen's edge.

## 🧭 Configurable Remote Crafting HUD layout

Reported in [#4](https://github.com/Zefryxis/InOr/issues/4): the nearby-chest-contents panel next to the crafting/inventory screen always docked to the right, colliding with JEI's recipe panel. Layout is now configurable across three independent pieces:

- **Materials panel** (the search/qty/scroll controls and the scrollable chest-content list — these move together) — now toggleable between **left** or **right** of the GUI via a small `⇄` button on the panel.
- **Quick-action buttons** (the existing K/S/OI/HUD/Wh row on the survival inventory screen) — now cycle between **above**, **below**, or **right** of the GUI via a small `⇅` button. When set to the right, they stack vertically instead of overflowing horizontally.
- **Deposit ("send item to chest") slot** — now **freely draggable anywhere on screen**: click its small move handle, drag, and release to drop it wherever you like.
- If the Materials panel and the quick-action buttons would both end up on the right at the same time, one is automatically nudged out of the way — no dead-end settings, no overlap.
- All positions persist per-config and are resolution/GUI-Scale independent.
- Fully localized in all 9 supported languages.

---

**Version**: 1.6.2
**Platform**: Modrinth (Fabric) — MC 26.2
**Author**: Zefryxis
