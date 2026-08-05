# Inventory Organizer 1.6.3 — Changelog

A quick hotfix on top of 1.6.2: a crash found right after release.

## 🐛 Bug fix

- **Fixed a hard crash dragging the deposit ("send item to chest") slot into a screen corner.** Its position clamp only kept the slot itself on-screen, not the hint text drawn around it (the drag hint to its right, the two-line hint below it at rest) — close enough to an edge, that text rendered past the real screen bounds and crashed. The slot now keeps a wider, edge-aware margin so all of its surrounding text always stays fully on-screen.

---

**Version**: 1.6.3
**Platform**: Modrinth (Fabric) — MC 26.2
**Author**: Zefryxis
