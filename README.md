# Inventory Organizer

A client-side Fabric mod for Minecraft that sorts your inventory automatically with a single button press.
Every slot in your inventory can be assigned a rule — by item category, item type, or exact item ID.
The sorter runs entirely on your client and works on any server without any server-side installation.

---

## Features

**OI Button — One-click inventory sort**
An OI button appears on the player inventory screen and on the Creative inventory screen.
Pressing it moves every item to its configured target slot using standard Minecraft slot-click packets,
identical to what the game sends when you drag items manually.

**Slot Rules**
Each of the 36 inventory and hotbar slots can be assigned one of the following rules:

- `any` — no restriction, used as general overflow space
- `g:category` — accepts any item from a category (weapons, tools, armor, food, blocks, valuables, utility, potions)
- `t:type` — accepts any item of a specific type regardless of material (sword, pickaxe, axe, bow...)
- `minecraft:item_id` — locks the slot to one exact item
- `empty` — the slot is always kept empty, the sorter never places anything here
- `b:rule` — places a bundle in the slot and applies a content rule to what goes inside it
- `cg:GroupName` — accepts any item from a custom group you defined yourself

**Custom Groups**
The Custom Group Editor is a searchable full-screen palette of every item in the game.
You build named lists of items, save them, and reference them in slot rules using the `cg:` prefix.
Groups can be exported as plain text files and imported back by dragging the file onto the editor screen.

**Sorting Order**
Within each category, item tiers are sorted in an order you define.
The Sorting Order Config screen lets you drag tiers up and down within each category pool.

**Storage Sorting**
An OST button appears on chests, barrels, shulker boxes, and other containers.
Storage Presets define rule layouts for containers of specific sizes (9, 27, 54 slots, etc.).
When you press OST, the sorter arranges the container contents according to the matching preset.

**Kits**
Kits let you snapshot your current inventory layout under a name and restore it later.
Useful for switching between a mining kit, a combat kit, and a building kit instantly.

**Fight Mode**
When you hit a player or are hit by one, Fight Mode activates for 20 seconds.
While active, the OI button is rate-limited: it moves one item per press with a randomised
130–179 ms cooldown between presses, mimicking a human clicking during combat.
A HUD indicator with a countdown timer appears in the top-left corner of your screen.
Fight Mode is designed to keep the mod compatible with server anti-cheat plugins during PvP.

---

## Server Compatibility

This mod is 100% client-side. The server does not need it installed and does not know it is present.
All item movements use the same vanilla slot-click packets the game already uses for manual dragging.
No custom packets are sent, no server memory is modified, no items are duplicated.

The mod works safely on vanilla servers, Paper, Purpur, and servers running standard anti-cheat plugins.
During PvP, Fight Mode automatically limits sorting speed to a human-realistic rate.

If your server rules explicitly forbid inventory macro or auto-sort tools, check them before using this mod.
The vast majority of servers have no such rules.

---

## Installation

1. Install [Fabric Loader](https://fabricmc.net/use/) for your Minecraft version.
2. Download [Fabric API](https://modrinth.com/mod/fabric-api) and place it in your mods folder.
3. Download [ModMenu](https://modrinth.com/mod/modmenu) (recommended, for accessing config in-game).
4. Download the Inventory Organizer jar for your Minecraft version and place it in your mods folder.
5. Launch the game. Press E to open your inventory — the OI button will be there.

Cloth Config is bundled inside the mod jar, no separate install is required.

---

## Supported Versions

| Minecraft | Status |
|-----------|--------|
| 1.21.11 | Actively developed |
| 1.21.10 | Supported |
| 1.21.9 | Available |
| 1.21.8 | Available |
| 1.21.7 | Available |
| 1.21.6 | Available |
| 1.21.5 | Available |
| 1.21.4 | Available |
| 1.21.1 | Available |
| 1.20.6 | Available |
| 1.20.4 | Available |
| 1.20.1 | Available |
| 1.19.4 and below | Not supported |

New features and bug fixes are developed for 1.21.10 and 1.21.11 only.
Older versions are provided as-is and will not receive backported updates.
Your config, slot rules, custom groups, kits, and storage presets carry over automatically when you update.

---

## Dependencies

| Dependency | Required |
|------------|----------|
| Fabric Loader | Yes |
| Fabric API | Yes |
| Cloth Config | Bundled (no separate install needed) |
| ModMenu | Recommended |

---

## Language Support

English, Hungarian, German, Spanish, French, Portuguese (BR), Russian, Chinese (Simplified), Japanese.

---

## License

MIT — see [LICENSE](LICENSE) for details.
