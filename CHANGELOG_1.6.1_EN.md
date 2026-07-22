# Inventory Organizer 1.6.1 — Changelog

A small but meaningful update: a brand-new in-game Tutorial for players who are still finding their footing with the mod, plus a fix for a false-positive bug affecting vanilla containers.

## 🎬 New: interactive Tutorial

- **A short, animated walkthrough** of how Inventory Organizer actually thinks — drawn live on a faux config-panel backdrop (not a video file), with typewriter captions and a real hotbar/slot look throughout.
- Covers, in order: assigning an exact item to a slot, automatic sorting via Ranks, how the Material → Enchant → Durability priority order decides between two items (with a concrete Netherite vs. Diamond pickaxe example), same-tier slot tie-breaking, linked-chest/Warehouse behaviour, Bundle Profile pairing, potion slot rules, and custom-group member ranking.
- Includes a **quick-reference cheat-sheet table** near the end covering all three "which criterion decided" scenarios at a glance, plus an explicit note that a criterion being first in the priority list does **not** mean the others are ignored — it just gets the first say, and only hands the decision to the next one in line when it's tied.
- **Auto-plays once on first launch** (right after the complexity-mode picker), and is replayable anytime from a new **"▶ Tutorial"** button in the Guide.
- Ends with an honest disclaimer: this is a helper, not magic — it won't always get everything perfectly right, and bugs may still turn up. Players are pointed to the [issue tracker](https://github.com/Zefryxis/InOr/issues) to report anything they find.
- Fully localized in all 9 supported languages.

## 🐛 Bug fix

- **Vanilla Dispenser, Dropper, and Hopper were wrongly flagged as "modded" containers.** The generic (modded) chest-like container detector only excluded vanilla chests, shulker boxes, and the player/creative/crafting screens — anything else with an unrestricted, storage-sized slot layout got OST/profile buttons and a "Modded chest — profile may be buggy" warning slapped on it. Since Dispenser/Dropper/Hopper use their own screen classes rather than the vanilla chest screen, they fell through and got this treatment despite being completely vanilla. They are now correctly excluded and ignored entirely.

---

**Version**: 1.6.1
**Platform**: Modrinth (Fabric) — MC 26.2
**Author**: Zefryxis
