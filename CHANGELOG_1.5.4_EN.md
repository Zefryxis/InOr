# Inventory Organizer 1.5.4 — Changelog

A reliability + performance pass: shulker boxes keep their profile identity through break/place, potion slot rules finally behave the way you'd expect, and the mod is noticeably lighter on weaker machines.

## 🧭 Version Support Policy

**From here on we move forward with Minecraft 26.2 and up. Versions 26.1, 26.1.1 and 26.1.2 will no longer receive updates — only on community request.**

The large code differences between Minecraft versions make it hard to keep updating all of them continuously. 26.1, 26.1.1 and 26.1.2 differed only very slightly, which is why we were able to maintain those three together — but we are now stepping up to 26.2.

## 📦 Shulker profiles survive break → pickup → place

- **Every shulker with a profile now keeps a stable ID** when you break it, carry it, and place it elsewhere — its per-shulker layout is no longer lost on pickup.
- Under the hood this uses a custom item component plus loot-table overrides for all 17 shulker colors, so the ID rides along on the item itself (MC 26.2's shulker loot table only copies a fixed whitelist of vanilla components, which is why custom data was being dropped before).

## 🧪 Potion slot rules

- **A potion rule (e.g. `pot:fire_resistance`) now matches all three bottle types** — drinkable, splash and lingering — of that effect, instead of silently matching nothing.
- **Bottle-type preference in Ranks.** Use **Ranks → Potion Type Order** to choose which bottle type wins when more than one is available.
- Fixed several related sorting bugs: the first placement pass now matches `pot:` rules correctly; hotbar-to-hotbar potion moves no longer grab the wrong potion (the effect is verified, not just the item ID); and a potion sitting in an unruled hotbar slot can now be moved into a specific main-inventory rule slot.

## ⚡ Performance (lighter on weak machines)

- **Auto-refill and trash/void scans are throttled** to run a few times per second instead of every single client tick. Both already had their own action cooldowns, so responsiveness is unchanged — but the idle inventory scan that ran 20×/second is gone, which was the main continuous CPU drain.
- **Fewer registry lookups during a sort.** The item-ID lookup in the swap routines is now computed once per slot instead of two-to-three times, cutting redundant work on every sort.
- **Color-variant matching is now O(1).** Resolving an item's color family (wool, concrete, shulker box, bundle, …) used a nested scan over every color × family on each call; it's now a single precomputed lookup. Same results, far less work.
- Hoisted a string allocation out of the group-matching inner loop. Sorting results are byte-for-byte identical — only wasted work was removed.

## 📖 Guide & localization

- **New guide section: "Potion slot rules"**, explaining the all-bottle-type matching and the Ranks bottle-type preference. Added in all 9 languages.
- **Fixed broken quote characters in the German and Hungarian language files.** A stray straight quote inside several translated strings made those files invalid JSON, which could cause the entire German/Hungarian guide to fall back to English. Both files now parse cleanly and render with proper typographic quotes.

---

**Version**: 1.5.4
**Platform**: Modrinth (Fabric) — MC 26.2
**Author**: Zefryxis
