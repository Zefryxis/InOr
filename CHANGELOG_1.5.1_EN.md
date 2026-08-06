# Inventory Organizer 1.5.1 — Changelog

## 🎯 Auto Tool Switch — Full Server Support

### ✨ New Features

#### Auto Tool Switch System (Expanded)
- **Air Category Support**: The "on air" setting now supports specific tool categories (pickaxe/axe/shovel/hoe/sword), not just "keep / restore"
- **Server Mode (Button-Trigger)**: Auto Switch now works **on servers** in button-trigger mode:
  - `TYPE_AIR`: Item restore (swap switch-slot ↔ original slot)
  - `TYPE_AIR_CAT`: Swap to specific category server-side
  - Full server-side validation and swap logic
- **Switch Tab Editor**: Mode selector (Auto / Plain), trigger setting (Auto / Button), air-behavior config
- **Attack Cooldown Preservation**: In Auto mode, swapped items keep their cooldown ticker counting in inventory — no reset when they reach hand

### 🛡️ Security Fixes

#### Critical
- **TYPE_AIR Slot Control**: Server no longer trusts client-sent `swSlot` value; switch slot always comes from server config
- **SyncGroupsPayload Validation**: Unknown group names rejected by server; only known switch-groups accepted
- **Item ID Validation**: Group members must be `namespace:id` format (e.g., `minecraft:diamond_pickaxe`)

#### Medium
- **Rate Limiting**:
  - Switch: max 4 swaps/sec (was: 10)
  - WarehouseMapQuery: max 2 queries/sec (was: unlimited)
  - SyncGroups: max 1 sync/sec (was: unlimited)
- **Reach Distance**: 5 blocks (was: 6 — vanilla reach ~4.5)
- **Memory Cleanup**: Disconnecting players' rate-limit maps auto-purged

### 🐛 Bug Fixes

#### Move Switch Button Overlap
- Buttons no longer overflow into inventory grid at low resolutions
- Grid Y-coordinate: `Math.max(70, height / 8)` (was: 50)

#### Switch Slot Item Eviction
- Non-tool items auto-removed from switch slot
- Built-in tool category checks (pickaxes/axes/shovels/hoes/swords)
- Evicted items OI'd to their proper home slot

#### Kits Load/Save Mode Memory
- Kit loading now correctly loads into active mode (Auto / Plain), not mixed between them

#### Attack Cooldown Ticker
- New mixin pair (`PlayerAttackTickerAccessor` + `PlayerItemSwitchMixin`):
  - Access to private `LivingEntity.attackStrengthTicker` and `itemSwapTicker` fields
  - Item swaps via auto-switch **don't reset** cooldown — ticker keeps counting in inventory
  - `@Redirect` on `Player.tick()` `resetAttackStrengthTicker()` call for item-change detection

### 📖 Documentation

#### Guide Section — "Auto Tool Switch"
In 9 languages (EN, DE, ES, FR, HU, JA, PT-BR, RU, ZH):
- How to designate switch-slot and storage-slots
- Category configuration (Ranks section)
- Trigger modes (Auto in single-player, button everywhere)
- Attack cooldown ticking in inventory

### 📝 Technical Details

#### Client-Server Communication
- **SwitchTriggerPayload** extended:
  - `TYPE_AIR` → `x=switchSlot, y=fromSlot` (restore swap)
  - `TYPE_AIR_CAT` → `x=switchSlot, entityId=categoryIndex` (category swap)
- **WarehouseNet.java** new handlers:
  - TYPE_AIR: server-side item swap with validation
  - TYPE_AIR_CAT: category-based selectAndSwap on server thread
- **Mixin Compatibility**: Fabric 0.19.3+, MC 26.1–26.1.2 (Mojang Mappings)

#### Configuration
- `switch_enabled` → switch active
- `switch_slot` → hotbar switch-slot (0–8)
- `switch_trigger` → "auto" or "button"
- `switch_air` → "keep" / "restore" / "pickaxe" / "axe" / "shovel" / "hoe" / "mob"
- Ranks-level: block overrides, mob weapon, material order (inherits from existing system)

### 🔧 Installation Notes

**Gradle clean build required** for MD5 hash changes (caching reasons).

Identical across all 3 versions: `inventory-organizer-1.5.1-mc[VERSION].jar`
- MC 26.1 (1.21.3)
- MC 26.1.1 (1.21.3)
- MC 26.1.2 (1.21.4)

---

## 📋 Summary

| Area | What | Type |
|------|------|------|
| **Auto Switch** | Server support, categories, button-trigger | ✨ Feature |
| **Attack Cooldown** | Mixin-based ticker preservation | 🔧 Fix |
| **Security Audit** | Rate limiting, validation, cleanup | 🛡️ Security |
| **UI/UX** | Move button overlap fix, grid layout | 🐛 Fix |
| **Documentation** | 9-language guide section | 📖 Content |

**Compatibility**: Fabric 0.19.3+, MC Java 25+, Mojang Mappings

---

**Version**: 1.5.1  
**Release Date**: June 22, 2026  
**Platform**: Modrinth (Fabric)  
**Author**: Zefryxis
