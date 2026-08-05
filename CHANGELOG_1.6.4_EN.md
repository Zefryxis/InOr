# Inventory Organizer 1.6.4 — Changelog

Another quick hotfix: search boxes and list scrolling not working right on a few config screens.

## 🐛 Bug fix

- **Fixed the search box not accepting typing, and the list snapping back to the top, on the Inventory tab and the Trash editor.** Reported in [#6](https://github.com/Zefryxis/InOr/issues/6). The search box was being rebuilt from scratch every time the screen re-laid itself out (e.g. after a window resize), discarding whatever was typed and its focus — unlike the Groups screen, which already reused its search box correctly. Also removed a redundant per-frame search re-check that could re-trigger a scroll reset. The Inventory tab, Trash tab, and Groups screen all now keep the search box and scroll position stable.

---

**Version**: 1.6.4
**Platform**: Modrinth (Fabric) — MC 26.2
**Author**: Zefryxis
