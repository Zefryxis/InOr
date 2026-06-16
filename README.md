# Inventory Organizer

![Minecraft 26.1+](https://img.shields.io/badge/Minecraft-26.1%2B-brightgreen)
![Fabric](https://img.shields.io/badge/Fabric-1.0-blue)
![License: ARR](https://img.shields.io/badge/License-All%20Rights%20Reserved-red)
[![Download on Modrinth](https://img.shields.io/modrinth/dt/inventory-organizer?label=Modrinth%20Downloads&color=00CC44)](https://modrinth.com/mod/inventory-organizer)

A Fabric mod for organizing your inventory and storage with one click, plus a server-aware multi-chest warehouse system, optional automations, and remote crafting.

> **Note:** This mod is published under an **All Rights Reserved (Source-Available)** license. See `LICENSE` for details.

---

## Features

### Sorting & Organization
- **OI (Organize Inventory):** one click sorts your whole inventory — identical items merge into full stacks
- **OST (Organize STorage):** same thing for open chests
- **Sort keybind:** switch between OI/OST with a single key
- **Stack consolidation:** scattered partial stacks merge into full ones
- **Kits:** save and load slot configurations

### Per-Chest Profiles
- Each chest remembers its own sort layout
- Identity via custom name, coordinates, or adjacent sign
- Profiles are **private and local** — never shared with other players

### Warehouses (Linked Chests)
- Link 2+ nearby chests into a warehouse and sort them all at once
- **Reveal-on-open:** on servers, other players only see your warehouse after they open one of its chests
- **Per-player OST permission:** control who can sort your linked chests (default: no one, unless you grant it)
- **Anti-theft server-side validation** — prevents linking other players' chests or stealing from foreign warehouses

### Automations (Free Mode)
- **Auto-refill:** marked hotbar slots refill themselves from your inventory
- **Chest refill:** top up hotbar slots from the open chest in one press
- **Deposit-all:** send back every stack that's already in the chest
- **Scroll moving:** directional scroll to move items between inventory halves or in/out of chests
- **Trash/Void:** mark unwanted items to drop on pickup

### Remote Crafting
- View and pull materials from nearby chests without walking over
- Range: 60 blocks at a crafting table (single-player), 15 blocks elsewhere

### Server-Friendly & Safe
- **Quick switch:** toggle between Server-Friendly and Free mode with one button
- **Free mode gating:** automations only work in private environments (single-player, LAN, Realms, whitelisted servers)
- **Fight mode:** real PvP forces Server-Friendly for ~20 seconds; keeps the mod fair in combat
- **Death auto-sort:** optionally re-sorts your inventory after respawn

### 9 Languages
English, Hungarian (Magyar), Deutsch, Español, Français, 中文, 日本語, Русский, Português.

---

## Installation

### Requirements
- **Minecraft:** 26.1, 26.1.1, or 26.1.2
- **Fabric Loader:** >= 0.19
- **Fabric API:** any recent version

### Download
**Official source:** [Modrinth — Inventory Organizer](https://modrinth.com/mod/inventory-organizer)

- Choose your Minecraft version (26.1 / 26.1.1 / 26.1.2) and download the `.jar`
- Drop it in your `mods/` folder
- Launch Minecraft with the Fabric profile

### Server Setup (Optional)
The mod works on vanilla servers (client-only), but to unlock warehouse sync and remote crafting:
- Add `inventory-organizer-<version>.jar` to the server's `mods/` folder
- Restart the server — the mod is silent until a client connects

---

## Quick Start

1. **Open your inventory** (`E`)
2. **Click the `S` button** (or bind a keybind) to open Slot Config
3. **Pick a rule from the right palette** (Tools, Weapons, Food, etc.) and **click a slot** to assign it
4. **Click Save** — your inventory now sorts to this layout
5. **Open a chest:** the mod auto-creates a profile for it; click **OST** to sort it the same way
6. **Open the Warehouse map:** `Wh` button (or keybind) to link 2+ chests together

For more details, open the in-game **Guide** button (`?` on any screen).

---

## How It Works

### Client-Side
All your profiles, slot rules, kits, and keybind settings stay **on your game**. Nothing is uploaded to a server unless you explicitly create a warehouse link.

### Server-Side (Optional)
If a server runs the mod:
- **Warehouse links** are stored server-side and persist across sessions
- **Reveal-on-open:** foreign links only appear in your map after you open one of their chests
- **OST permissions:** the owner can grant or revoke sort access per player (default: denied)
- **Anti-theft:** the server validates that you can't steal from other players' chests

On a vanilla server, the mod is completely silent — no packets, no features relying on server sync.

---

## Development

This repository contains the **full source code** for Inventory Organizer, provided for inspection, audit, and educational reference.

### Building
```bash
cd src/main
./gradlew build
```

Jars will be in `build/libs/`.

### License & Redistribution
**⚠️ This code is published under an All Rights Reserved (Source-Available) license.**

You are **permitted to**:
- Read and study this code for educational purposes
- Report bugs and request features via [Issues](https://github.com/Zefryxis/InventoryOrganizer/issues)
- Make private modifications for personal use

You are **NOT permitted to**:
- Redistribute or re-upload this mod under any name or platform
- Create a public fork or derivative version
- Claim ownership of this code
- Remove copyright notices or this license

**Official releases** are only published on:
- 🎮 [Modrinth](https://modrinth.com/mod/inventory-organizer) — downloads & updates
- 📦 [GitHub Releases](https://github.com/Zefryxis/InventoryOrganizer/releases) — source tags

For licensing exceptions or inquiries, contact the author.

---

## Support & Feedback

- 🐛 **Bug reports:** [GitHub Issues](https://github.com/Zefryxis/InventoryOrganizer/issues)
- 💬 **Questions & ideas:** [GitHub Discussions](https://github.com/Zefryxis/InventoryOrganizer/discussions)
- 📝 **Comments & reviews:** [Modrinth page](https://modrinth.com/mod/inventory-organizer)

---

## Credits

**Author:** Zefryxis

**Translations:** Community contributors (see in-game language files)

**Built with:** Fabric API, Minecraft modding community inspiration

---

## License

This source code is published under an **All Rights Reserved (Source-Available)** license. See the `LICENSE` file for full terms.

---

Ubdated on: 2026. 06. 16.
