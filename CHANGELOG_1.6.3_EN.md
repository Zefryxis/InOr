# Inventory Organizer 1.6.3 — Changelog

A quick hotfix round on top of 1.6.2: two crashes/bugs found right after release.

## 🐛 Bug fixes

- **Fixed a hard crash dragging the deposit ("send item to chest") slot into a screen corner.** Its position clamp only kept the slot itself on-screen, not the hint text drawn around it (the drag hint to its right, the two-line hint below it at rest) — close enough to an edge, that text rendered past the real screen bounds and crashed. The slot now keeps a wider, edge-aware margin so all of its surrounding text always stays fully on-screen.
- **Fixed the search box not accepting typing, and the list snapping back to the top, on the Inventory tab and the Trash editor.** Reported in [#6](https://github.com/Zefryxis/InOr/issues/6). The search box was being rebuilt from scratch every time the screen re-laid itself out (e.g. after a window resize), discarding whatever was typed and its focus — unlike the Groups screen, which already reused its search box correctly. Also removed a redundant per-frame search re-check that could re-trigger a scroll reset. The Inventory tab, Trash tab, and Groups screen all now keep the search box and scroll position stable.

---

**Version**: 1.6.3
**Platform**: Modrinth (Fabric) — MC 26.2
**Author**: Zefryxis
