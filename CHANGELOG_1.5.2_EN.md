# Inventory Organizer 1.5.2 — Changelog

## 🔁 Auto Tool Switch — Full Server Support

In 1.5.1 the switch worked on servers only in a limited, button-triggered way. As of 1.5.2 it works on servers **just like in single-player**.

### ✨ New / Fixed

- **Automatic (crosshair-following) mode on servers too.** The auto-trigger used to be single-player only; now it switches by itself on InOr servers as you look at blocks / mobs / air.
- **New switch server architecture: the client computes, the server applies.** The client (which has the config, slot rules and groups) computes the entire swap — exactly as in single-player — and sends the server the finished slot moves (`TYPE_APPLY`: source slot + where the displaced item should go). The server only validates the indices (all the player's own inventory, 0–35) and applies them. Client and server state are therefore always identical.
- **The displaced item is OI'd back to its proper slot.** When the switch puts a tool in your hand, the item that was there now lands in its **proper slot per your own slot rules** (not the vacated slot). The server couldn't do this before (it has no copy of your slot rules) — the client computes it now.
- **SP-like speed (client-side prediction).** The client performs the swap locally and instantly (immediate visual), and the server confirms it — no noticeable wait for the network round-trip. (The prediction is cosmetic: if it ever diverges, the server's sync corrects it; no item loss.)
- **"Restore" on servers — fixed.** Looking at air returns the tool to its **real source slot**. The client and server use the same source slot (the server tracks it too), so it can't end up in the wrong place.
- **Air category (e.g. "Sword") on servers.** Looking at air switches to the configured category (the stray client throttle that used to drop fast air switches was removed).
- **Attack cooldown preserved on servers.** A switched weapon is ready to strike immediately — the swap itself no longer resets the cooldown (an actual attack still does, as it should). The server-side handler preserves the cooldown too, not just the client.

### ⚔️ Fight Mode

- When combat starts, one OI runs (tidy the inventory), then auto-switch turns off (button mode) for the duration of the fight so it can't look like a macro; it returns to auto when combat ends.

### 🌍 Localization

- The refill / preset overlay messages were moved from hardcoded strings (including one that was stuck in Hungarian) to translation keys, across all 9 languages (EN, HU, DE, ES, FR, JA, PT-BR, RU, ZH).

### 🛡️ Security / Correctness

- **Per-player switch slot** on dedicated servers: the slot comes from the client (validated 0–8), so everyone uses their own. The swap only ever touches the requesting player's **own** inventory, is count-conserving and rate-limited (max 10 swaps/sec).
- The sorter now uses the Auto-switch mode rule set on servers too when you're in that mode (it used to be single-player only).
- Fixed a stale reach comment (5 blocks, `MAX_REACH_SQ = 25`).

### 📦 Repository

- The 26.1 / 26.1.1 / 26.1.2 source was brought into version control under `ports/`.

---

**Version**: 1.5.2
**Platform**: Modrinth (Fabric) — MC 26.1 / 26.1.1 / 26.1.2
**Author**: Zefryxis
