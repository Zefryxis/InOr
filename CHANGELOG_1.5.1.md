# Inventory Organizer 1.5.1 — Changelog

## 🎯 Auto Tool Switch — Teljes Szerver Támogatás

### ✨ Új Funkciók

#### Auto Tool Switch Rendszer (kibővítve)
- **Air kategória támogatása**: A "levegőre nézve" beállítás most támogatja a specifikus szerszám-kategóriákat (csákány/balta/ásó/kapa/kard), nem csak "tartsd meg / állítsd vissza"
- **Szerver-mód (Gomb-trigger)**: Az Auto Switch mostantól **szerveren is működik** gomb-trigger módban:
  - `TYPE_AIR`: Item restore (csere-slot ↔ eredeti slot csere)
  - `TYPE_AIR_CAT`: Specifikus kategóriára váltás szerveroldalon
  - Teljes szerver-oldali validáció és swap logika
- **Switch Fül szerkesztés**: Mód választó (Auto / Sima), trigger beállítás (Auto / Gomb), air-viselkedés
- **Ütés-cooldown megtartás**: Az Auto módban a megváltozó eszközök cooldown-ja már a kézben nem resetelődik — az inventory-ban számolja az ütési készenlétet

### 🛡️ Biztonsági Javítások

#### Kritikus
- **TYPE_AIR slot kontroll**: Szerver mostantól **nem bízik** a kliens által küldött `swSlot` értékben; a switch slot mindig a szerver config-ból jön
- **SyncGroupsPayload validáció**: Ismeretlen csoportnév-eket a szerver eldobja; csak az ismert switch-csoportok fogadódnak
- **Item ID validáció**: A group tagok kötelezően `namespace:id` formátumúak (pl. `minecraft:diamond_pickaxe`)

#### Közepes
- **Rate limiting**:
  - Switch: max 4 swap/sec (volt: 10)
  - WarehouseMapQuery: max 2 lekérdezés/sec (volt: korlátlan)
  - SyncGroups: max 1 szinkronizálás/sec (volt: korlátlan)
- **Reach távolság**: 5 blokk (volt: 6 — vanilla reach ~4.5)
- **Memory cleanup**: Kilépő játékosok rate-limit map-jai automatikusan törlődnek

### 🐛 Hibamegoldások

#### Move Switch Gomb Overlap
- A gombok már nem lógnak bele az inventory gridbe alacsony felbontáson
- Grid Y-koordináta: `Math.max(70, height / 8)` (volt: 50)

#### Switch Slot Item Eviction
- Nem a switch-hez való itemek automatikusan eltávolítódnak a switch slotból
- Beépített szerszám-kategóriák (pickaxes/axes/shovels/hoes/swords) ellenőrzése
- OI-ző integrációval otthonába helyezkedik az elutasított item

#### Kits Load/Save Módmemória
- Kit betöltés mostantól az aktív módba (Auto / Sima) betölt, nem ezek közül keveredve

#### Attack Cooldown Ticker
- Új mixin páros (`PlayerAttackTickerAccessor` + `PlayerItemSwitchMixin`):
  - Hozzáférés az `LivingEntity.attackStrengthTicker` és `itemSwapTicker` privát fieldekhez
  - Item-csere az auto switch miatt **nem reseteli** a cooldown-t — továbbszámlál az inventory-ban
  - `@Redirect` a `Player.tick()` `resetAttackStrengthTicker()` hívásán az item-csere detektálásához

### 📖 Dokumentáció

#### Guide Szekció — "Auto Tool Switch"
9 nyelven (EN, DE, ES, FR, HU, JA, PT-BR, RU, ZH):
- Hogyan jelölhetsz ki csere-slotot és storage-slotokat
- Kategória-konfigurálás (Ranks szekció)
- Trigger módok (Auto SP-ben, gomb mindenhol)
- Attack cooldown-ok az inventory-ban

### 📝 Technikai Részletek

#### Kliens-Szerver Komunikáció
- **SwitchTriggerPayload** kiterjesztés:
  - `TYPE_AIR` → `x=switchSlot, y=fromSlot` (restore swap)
  - `TYPE_AIR_CAT` → `x=switchSlot, entityId=categoryIndex` (kategória-csere)
- **WarehouseNet.java** új handlerek:
  - TYPE_AIR: szerver-oldali item swap validációval
  - TYPE_AIR_CAT: kategória-alapú selectAndSwap szerver-szálán
- **Mixin kompatibilitas**: Fabric 0.19.3+, MC 26.1–26.1.2 (Mojang mappings)

#### Konfig-ország
- `switch_enabled` → switch aktív
- `switch_slot` → hotbar csere-slot (0–8)
- `switch_trigger` → "auto" vagy "button"
- `switch_air` → "keep" / "restore" / "pickaxe" / "axe" / "shovel" / "hoe" / "mob"
- Rack-szinten: blokk-felülírások, mob-fegyver, anyag-sorrend (öröklés a meglévő rendből)

### 🔧 Telepítés Megjegyzések

**Gradle Clean Build szükséges** az MD5 hashe megváltozásához (cache-elési okokból).

Mind a 3 verzióban egyforma: `inventory-organizer-1.5.1-mc[VERSION].jar`
- MC 26.1 (1.21.3)
- MC 26.1.1 (1.21.3)
- MC 26.1.2 (1.21.4)

---

## 📋 Összefoglalás

| Terület | Mit | Jelleg |
|---------|-----|--------|
| **Auto Switch** | Szerver-támogatás, kategóriák, gomb-trigger | ✨ Feature |
| **Attack Cooldown** | Mixin-alapú ticker megtartás | 🔧 Javítás |
| **Biztonsági audit** | Rate limiting, validáció, cleanup | 🛡️ Biztonság |
| **UI/UX** | Move gomb overlap fix, grid-Layout | 🐛 Javítás |
| **Dokumentáció** | 9 nyelvű guide szekció | 📖 Tartalom |

**Kompatibilitás**: Fabric 0.19.3+, MC Java 25+, Mojang Mappings

---

**Verzió**: 1.5.1  
**Kiadás dátuma**: 2026-06-22  
**Platform**: Modrinth (Fabric)  
**Szerző**: Zefryxis
