# Inventory Organizer 1.5.2 — Changelog

## 🔁 Auto Tool Switch — Teljes Szerver Támogatás

Az 1.5.1-ben a switch szerveren csak gombbal, korlátozottan ment. Az 1.5.2-vel a switch szerveren is **ugyanúgy működik, mint single-playerben**.

### ✨ Új / Javított

- **Automatikus (célkereszt-követő) mód szerveren is.** Eddig az auto-trigger SP-only volt; mostantól InOr-szerveren is vált magától, ahogy blokkra / mobra / levegőre nézel.
- **Új switch szerver-architektúra: a kliens számol, a szerver alkalmaz.** A kliens (akinél megvan a config, a slot-szabályok és a csoportok) kiszámolja a teljes swap-ot — pont úgy, mint single-playerben —, és a szervernek a kész slot-műveleteket küldi (`TYPE_APPLY`: forrás-slot + hová kerüljön a kiszorított item). A szerver csak validálja (minden a játékos saját inventory-ja, 0–35) és alkalmazza. Így a kliens és a szerver állapota mindig azonos.
- **A kiszorított item OI-val a helyére kerül.** Amikor a switch beteszi a szerszámot, a kézben lévő cucc mostantól a **saját slot-szabályod szerinti helyére** rendeződik (nem a kiürült slotba). Ezt eddig a szerver nem tudta megtenni (nincs nála a slot-szabályaid másolata) — most a kliens számolja ki.
- **SP-szerű sebesség (kliens-oldali predikció).** A kliens azonnal, lokálisan elvégzi a swap-ot (instant kép), a szerver pedig megerősíti — nincs érezhető várakozás a hálózati körre. (A predikció kozmetikai: ha valaha eltérne, a szerver szinkronja korrigál, item nem veszik el.)
- **„Restore" szerveren — javítva.** Levegőre nézve a szerszám a **valódi forrás-slotjába** kerül vissza. A kliens és a szerver ugyanazt a forrás-slotot használja (a szerver jegyzi is), így nem kerülhet rossz helyre.
- **Air-kategória (pl. „Sword") szerveren.** Levegőre nézve a beállított kategóriára vált (a felesleges kliens-throttle eltávolítva, ami eddig eldobta a gyors air-váltást).
- **Ütés-cooldown megtartás szerveren.** A switch-elt fegyver azonnal ütésre kész — a swap önmagában nem nullázza a cooldown-t (a tényleges ütés továbbra is, ahogy kell). A szerver-oldali handler is megőrzi a cooldown-t, nem csak a kliens.

### ⚔️ Fight mód

- Harc kezdetén egy OI lefut (inventory rendberakás), majd az auto-switch kikapcsol (gomb-mód) a harc idejére, hogy ne tűnjön makrónak; harc végén visszaáll.

### 🌍 Lokalizáció

- A refill / preset overlay-üzenetek hardkódolt szövegből fordítási kulcsokba kerültek (egy korábban magyarul ragadt sorral együtt), mind a 9 nyelven (EN, HU, DE, ES, FR, JA, PT-BR, RU, ZH).

### 🛡️ Biztonság / Helyesség

- **Switch-slot per-játékos** dedikált szerveren: a slot a kliensből jön (0–8 validálva), így mindenki a sajátját használja. A swap kizárólag a kérő **saját** inventory-ját érinti, count-megőrző, rate-limitelt (max 10 swap/sec).
- A sortoló mostantól szerveren is az Auto-switch mód szabálykészletét használja, ha abban a módban vagy (eddig SP-only volt).
- Elavult reach-komment javítva (5 blokk, `MAX_REACH_SQ = 25`).

### 📦 Repó

- A 26.1 / 26.1.1 / 26.1.2 forrás bekerült verziókövetésbe a `ports/` alá.

---

**Verzió**: 1.5.2
**Platform**: Modrinth (Fabric) — MC 26.1 / 26.1.1 / 26.1.2
**Szerző**: Zefryxis
