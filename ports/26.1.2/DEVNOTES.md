# InventoryOrganizerMod – Fejlesztői Megjegyzések

## Projektinformáció

- **Minecraft verzió:** 1.21.10 (Java Edition)
- **Mod keretrendszer:** Fabric + Fabric API `0.115.6+1.21.1`
- **Loom verzió:** Fabric Loom 1.7-SNAPSHOT
- **Java:** JDK 21 (`C:\Program Files\Eclipse Adoptium\jdk-21.0.10.7-hotspot`)
- **Mod ID:** `inventory-organizer`
- **Main class:** `com.example.inventoryorganizer`
- **Config fájl:** `config/inventory-organizer.json`

### Build & Deploy parancs

```powershell
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-21.0.10.7-hotspot"; .\gradlew.bat build
Copy-Item "build\libs\inventory-organizer-1.0.0.jar" "C:\Users\mihal\AppData\Roaming\ModrinthApp\profiles\Test\mods\inventory-organizer-1.0.0.jar" -Force
```

---

## ⚠️ KRITIKUS ISMERT PROBLÉMA #1 – ARGB vs RGB Szín Formátum

### Érintett verzió
Minecraft **1.21.6 és újabb** (beleértve 1.21.10-et).

### Leírás
A Fabric API `DrawContext` szövegrenderelő metódusai (`drawTextWithShadow`, `drawCenteredTextWithShadow`) **1.21.6 előtt RGB (3 bájtos)** formátumú szín paramétert vártak. **1.21.6-tól kezdve ARGB (4 bájtos)** formátumot várnak.

Ez azt jelenti, hogy ha az `alpha` byte nincs megadva (vagy 0), a szöveg **teljesen láthatatlan** lesz futásidőben, még akkor is, ha fordításkor nem ad hibát.

### A hiba tünete
- A szöveg egyáltalán nem jelenik meg a képernyőn
- Nincsen fordítási hiba
- Nincsen futásidei hiba/crash
- A `context.fill()` és `drawHorizontalLine()` stb. fill-típusú rajzolók **nem érintettek** – azok mindig ARGB-t vártak

### Technikai magyarázat

Java `int` 32 bites. A `0xFFFFFF` értéke decimálisan `16777215`. Ha a Minecraft ezt ARGB-ként értelmezi:
- **Alpha byte:** `0x00` → **0/255 = teljesen átlátszó**
- **Red byte:** `0xFF` = 255
- **Green byte:** `0xFF` = 255
- **Blue byte:** `0xFF` = 255

Eredmény: teljesen átlátszó fehér → **láthatatlan szöveg**.

### Helyes vs Hibás példák

```java
// ❌ HIBÁS – alpha = 0x00 = átlátszó → LÁTHATATLAN
context.drawTextWithShadow(tr, text, x, y, 0xFFFFFF);   // fehér, de átlátszó
context.drawTextWithShadow(tr, text, x, y, 0xAAAAAA);   // szürke, de átlátszó
context.drawTextWithShadow(tr, text, x, y, 0xFFFF55);   // sárga, de átlátszó
context.drawTextWithShadow(tr, text, x, y, 0x55FFFF);   // cián, de átlátszó

// ✅ HELYES – alpha = 0xFF = teljesen látható
context.drawTextWithShadow(tr, text, x, y, 0xFFFFFFFF);  // fehér
context.drawTextWithShadow(tr, text, x, y, 0xFFAAAAAA);  // szürke
context.drawTextWithShadow(tr, text, x, y, 0xFFFFFF55);  // sárga
context.drawTextWithShadow(tr, text, x, y, 0xFF55FFFF);  // cián

// ✅ HELYES – getMaterialColor() visszatérési értékek (8 jegyű)
case "netherite": return 0xFFFF5555;   // A=FF, R=FF, G=55, B=55 → piros
case "diamond":   return 0xFF55FFFF;   // A=FF, R=55, G=FF, B=FF → cián
case "iron":      return 0xFFFFFFFF;   // A=FF fehér
case "gold":      return 0xFFFFFF55;   // A=FF sárga
case "stone":     return 0xFFAAAAAA;   // A=FF szürke
case "wood":      return 0xFFAA7744;   // A=FF barna
case "leather":   return 0xFFCC8855;   // A=FF tan
case "chain":     return 0xFF999999;   // A=FF közép-szürke
```

### Szabály – Kötelező betartani

> **Minden `drawTextWithShadow` és `drawCenteredTextWithShadow` hívásban a szín paraméternek 8 jegyű hexadecimális ARGB értéknek KELL lennie (`0xFFxxxxxx` alakú, ahol az első `FF` az alpha).**

### Formátum kódok (`§e`, `§f`, stb.) és az alpha

Ha `Text.literal("\u00a7eText")` formátum kódot használsz (pl. `§e` = sárga), a szöveg karaktereinek színét a formátum kód határozza meg. **DE** a `color` paraméter alpha értéke akkor is befolyásolja a láthatóságot. Ha `alpha = 0x00`, a szöveg láthatatlan lesz még akkor is, ha a szöveg maga tartalmaz `§e§l` formátum kódot.

```java
// ❌ Formátum kóddal sem működik, ha az alpha nulla:
context.drawTextWithShadow(tr, Text.literal("\u00a7eSárga szöveg"), x, y, 0xFFFF55); // láthatatlan!

// ✅ Formátum kóddal, helyes alpha:
context.drawTextWithShadow(tr, Text.literal("\u00a7eSárga szöveg"), x, y, 0xFFFFFFFF); // látható, §e sárgán
```

---

## ⚠️ KRITIKUS ISMERT PROBLÉMA #2 – `replace_all` Veszélye Szín Értékeknél

### Leírás
Ha `replace_all: true`-val próbálsz 6 jegyű hex értéket 8 jegyűre javítani, az **részleges egyezés** miatt elronthat meglévő, már helyes 8 jegyű értékeket.

### Konkrét példa ami megtörtént

A `0xFFFFFF` → `0xFFFFFFFF` replace_all rontotta el a `0xFFFFFF00` értéket:

```
Eredeti (helyes, 8 jegyű ARGB):  0xFFFFFF00   (A=FF, R=FF, G=FF, B=00 → opaque sárga)
replace_all utáni (TÖRÖTT):       0xFFFFFFFF00  (10 jegyű → Java int túlcsordulás = fordítási hiba!)
```

Hasonlóan, `0x55FFFF` → `0xFF55FFFF` replace_all rontotta el a `0x55FFFF00` értéket:

```
Eredeti (helyes):  0x55FFFF00   (A=55 semi-transparent, R=FF, G=FF, B=00 → félig átlátszó sárga)
replace_all utáni: 0xFF55FFFF00 (10 jegyű → TÖRÖTT!)
```

### Szabály – Kötelező betartani

> **Szín értékek javításakor SOHA ne használj `replace_all: true`-t olyan mintákra, amelyek részleges egyezést okozhatnak más, már helyes hex értékeken belül.**
>
> Mindig **célzott, soronkénti** cserét végezz, ahol az egész érintett sort megadod old_string-ként.

---

## ⚠️ KRITIKUS ISMERT PROBLÉMA #3 – Obfuszkált Minecraft API-k

Az alábbi API-k **futásidőben obfuszkáltak** és nem használhatók 1.21.10-ben:

| Tiltott API | Hiba típusa | Alternatíva |
|---|---|---|
| `instanceof SwordItem` / `PickaxeItem` stb. | Rossz detektálás | String alapú item ID |
| `DataComponentTypes.FOOD` | Obfuszkált | String alapú food detektálás |
| `Item.getName()` | `NoSuchMethodError: method_7848` | `Registries.ITEM.getId()` |
| `Registries.BLOCK.get()` | Obfuszkált | Manuális string alapú block detektálás |

**Biztonságos API-k:** `ItemStack`, `PlayerInventory`, `Registries.ITEM.getId()`, `Registries.ITEM.getIds()`, `Identifier`, `MinecraftClient`, `ItemStack.areItemsAndComponentsEqual()`, `EnchantmentHelper.getEnchantments()`, `SlotActionType`, `clickSlot()`

---

## Projekt Struktúra (Fontos fájlok)

```
src/main/java/com/example/inventoryorganizer/
├── InventoryOrganizerMod.java       – Belépési pont, keybind regisztráció
├── InventorySorter.java             – Rendező logika
├── config/
│   ├── OrganizerConfig.java         – JSON config (showHelp, kitok, stb.)
│   ├── VisualInventoryConfigScreen.java  – Slot hozzárendelő screen
│   ├── SortingOrderConfigScreen.java     – Tier Order config screen
│   ├── KitsScreen.java              – Kit mentés/betöltés screen
│   ├── StyledButton.java            – Egyedi gomb stílus (3D sunken)
│   └── ConfigScreenBuilder.java     – Cloth Config2 integráció (item nevek)
```

---

## UI Rendering – Fontos tudnivalók

### DrawContext metódusok és szín formátum összefoglalás

| Metódus | Szín formátum (1.21.10) | Megjegyzés |
|---|---|---|
| `context.fill(x1,y1,x2,y2, color)` | ARGB 8 jegyű | Mindig ARGB volt |
| `context.drawHorizontalLine(...)` | ARGB 8 jegyű | Mindig ARGB volt |
| `context.drawVerticalLine(...)` | ARGB 8 jegyű | Mindig ARGB volt |
| `context.drawTextWithShadow(tr, text, x, y, color)` | **ARGB 8 jegyű** | **1.21.6 előtt RGB volt!** |
| `context.drawCenteredTextWithShadow(tr, text, x, y, color)` | **ARGB 8 jegyű** | **1.21.6 előtt RGB volt!** |

### StyledButton szín formátum

A `StyledButton.renderWidget()` metódusban a szöveg szín is 8 jegyű ARGB:

```java
int textColor = !enabled ? 0xFF686868 : hovered ? 0xFFFFFFFF : 0xFFCCCCCC;
```

### Hover és selected effektek – meglévő helyes 8-jegyű fill színek (NE VÁLTOZTASD!)

```java
// VisualInventoryConfigScreen – drawMCSlot hover
context.fill(..., 0x55FFFF00);     // A=55 (semi), R=FF, G=FF, B=00 = félig átlátszó sárga
context.drawHorizontalLine(..., 0xFFFFFF00); // A=FF, sárga border

// SortingOrderConfigScreen – drawMCSlot selected
context.fill(..., 0x44FFFF00);     // A=44 (semi), sárga tint selected slot-on
context.drawHorizontalLine(..., 0xFFFFFF00); // sárga border selected slot-on
context.fill(..., 0x33FFFFFF);     // A=33, fehér tint hover-on (nem sárga!)
```

---

## Guide Overlay Rendszer

### Működés
- **`[?]` gomb:** Minden config screenen jobb felső sarokba van elhelyezve (`width - 24, 4, 20x18px`)
- **Toggle:** `config.setShowHelp(!config.isShowHelp())` → `config.save()`
- **Render:** A guide overlay a `render()` metódus LEGVÉGÉN van rajzolva, hogy mindent eltakarjon
- **Dimmer:** `context.fill(0, 0, width, height, 0x88000000)` → félig átlátszó fekete háttér
- **Panel:** `drawDecoratedPanel(context, gx, gy, gw, gh)`
- **Szöveg:** `context.drawTextWithShadow(...)` – **8 jegyű ARGB színekkel!**

### Guide szöveg formátum kódok (§ karakterek)

```java
// §e = sárga, §f = fehér, §7 = szürke, §b = cián, §a = zöld, §l = félkövér
Text.literal("\u00a7e[1] \u00a7fSzöveg itt")   // Sárga "[1]" + fehér szöveg
Text.literal("\u00a7b--- Fejléc ---")            // Cián fejléc
Text.literal("\u00a77Megjegyzés")                // Szürke megjegyzés
```

---

## Kategória Sorrend (InventorySorter)

```
Weapon > Tool > Armor > Valuable > Block (string list) > Food > Utility (torch, bundle) > Misc
```

---

## Mixin

- `InventoryScreen.init()`-be injectál → "OI" gomb hozzáadása (`x+150, y+52`)
- Inventory megnyitásakor triggerel az auto-sort

---

*Utolsó frissítés: 2026-04-11*
