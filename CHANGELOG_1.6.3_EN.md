# Inventory Organizer 1.6.3 — Changelog

A hotfix round on top of 1.6.2: two crashes/bugs found right after release, a security/anti-cheat hardening pass, and a big localization catch-up.

## 🐛 Bug fixes

- **Fixed a hard crash dragging the deposit ("send item to chest") slot into a screen corner.** Its position clamp only kept the slot itself on-screen, not the hint text drawn around it (the drag hint to its right, the two-line hint below it at rest) — close enough to an edge, that text rendered past the real screen bounds and crashed. The slot now keeps a wider, edge-aware margin so all of its surrounding text always stays fully on-screen.
- **Fixed the search box not accepting typing, and the list snapping back to the top, on the Inventory tab and the Trash editor.** Reported in [#6](https://github.com/Zefryxis/InOr/issues/6). The search box was being rebuilt from scratch every time the screen re-laid itself out (e.g. after a window resize), discarding whatever was typed and its focus — unlike the Groups screen, which already reused its search box correctly. Also removed a redundant per-frame search re-check that could re-trigger a scroll reset. The Inventory tab, Trash tab, and Groups screen all now keep the search box and scroll position stable.

## 🔒 Anti-cheat & security hardening

- **Hardened scroll-move against Fight Mode.** It was already blocked during active combat indirectly (via the same "Free mode only" check used by other Free-only features), but now has its own explicit, directly-named check — scroll-move is a near-instant item-move method that could otherwise look exactly like a macro to anti-cheat, so this makes sure it can never accidentally start working during combat even if the shared check's logic changes later.
- **Sustained scroll-move now uses a randomised cooldown gap instead of a fixed one** (55-75ms / 60-80ms instead of a perfectly metronomic 55ms / 60ms) — a fixed-interval packet cadence is exactly the pattern anti-cheat auto-clicker detectors are built to catch.
- **Added missing per-player rate limits to the warehouse link management packets** (upload/delete a link, view/change OST permissions), which previously had no anti-spam check at all and could be fired as fast as the network allows, forcing repeated disk writes on the server.
- Added a low-frequency self-heal sweep for an internal server-side bookkeeping ledger (which chest a crafting ingredient was borrowed from) that drops any entry left untouched for 10+ minutes — pure defensive hardening against a hypothetical future bug, doesn't touch any saved settings.
- Removed an unused, never-registered network payload left over from earlier development.

## 🌍 Localization

- **Localized ~96 previously hardcoded, English-only UI strings across all 9 languages.** Several screens (Kits, Custom Group editor/list, Tier/Sorting Order config, the main Inventory config screen, the Remote Crafting panel, the Item Names screen, and a few screen titles) had labels, status messages, tooltips, and several full "Guide" help-text blocks that were never wired into the language system — they always showed in English no matter your language setting. All of it is now properly translated.

## 🎬 Tutorial

- **Pressing Enter now skips the Tutorial's typewriter caption animation**, instantly revealing the full caption text instead of waiting for it to type out — the auto-advance read pad still gives you the same full time to read it afterwards.

---

**Version**: 1.6.3
**Platform**: Modrinth (Fabric) — MC 26.2
**Author**: Zefryxis
