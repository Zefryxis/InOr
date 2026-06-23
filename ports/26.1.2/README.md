# Inventory Organizer Minecraft Mod

Egy egyszerű Minecraft mod, ami elrendezi az inventoryt egy gombnyomással.

## Funkciók

- **Organize Inventory gomb**: Megjelenik az inventory képernyőn a crafting rész alatt
- **Automatikus rendezés**: Az árucikkek név szerint, majd mennyiség szerint vannak rendezve
- **Kompatibilitás**: Minecraft 1.21.1 Fabric API

## Telepítés

1. Buildeld a modot:
   ```bash
   ./gradlew build
   ```

2. Másold a `build/libs/inventory-organizer-1.0.0.jar` fájlt a Minecraft mods mappájába

3. Indítsd el a Minecraftot Fabric Loaderrel

## Használat

1. Nyisd meg az inventoryt (E billentyű)
2. Kattints az "Organize Inventory" gombra a crafting felület alatt
3. Az inventory automatikusan rendezve lesz

## Fejlesztés

A mod Fabric API-t használ Minecraft 1.21.1-hez.
- Java 21 szükséges
- Fabric Loader 0.16.5+
- Fabric API 0.106.1+
