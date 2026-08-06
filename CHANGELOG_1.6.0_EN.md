# Inventory Organizer 1.6.0 — Changelog

A UX and reliability pass: the mod can now start dead simple and grow with you, sorting defaults got a research-backed tune-up, and a long-standing Bundle Profiles bug that made profiles match almost anything is fixed for good.

## 🎚️ Three complexity modes: Simple / Advanced / Expert

- **New global setting** controlling how much of the config is exposed at once:
  - **Simple** — only slot rules, bundles and basic toggles. Sorting and groups run on sensible built-in defaults. The easiest way to get started.
  - **Advanced** — adds custom groups and slot-tier (Ranks) editing. You decide what goes where; deep sort tuning stays on defaults.
  - **Expert** — everything is customizable, the full experience as before.
- **First-launch picker**: new installs are asked which mode to start in (Simple recommended), with a short explanation of each.
- **Switch anytime** from Special Settings, behind a confirmation dialog that explains what each level unlocks. Lowering the level only *hides* the advanced options — nothing is ever deleted, and everything reappears the moment you raise it again.

## 🎯 Research-backed sorting defaults

- **Enchantment priority reordered** using community consensus on enchant value (Mending/Unbreaking first, situational picks after, curses last). `fire_protection` — the weakest of the protection enchants — was additionally demoted to just above the curses.
- **Copper fixed in material ordering.** Copper tools/armor used to fall back to "unranked/last" until you opened the Ranks screen once; the runtime default order now includes copper (right after Iron) everywhere.
- These defaults are applied to **existing configs too**, not just fresh installs — no action needed.

## 🧺 Bundle Profiles — actually work now

This is the headline fix. Bundle Profiles looked configured but silently didn't behave as expected; both root causes are fixed:

- **Critical matching bug fixed:** saving a bundle profile used to write a literal `"any"` rule into every untouched slot of the profile editor grid. Since `"any"` means "matches everything," a profile with just one real rule (e.g. "Pickaxe only") would *also* silently accept anything else — which is why a "Pickaxes" bundle could end up collecting a trident. Only the rules you actually set are saved now, and existing profiles are cleaned up automatically on load.
- **Empty bundles can now be claimed by a profile.** Previously, a bundle profile could only recognize a bundle that already had at least one matching item inside it — a brand-new, empty bundle would never start filling. Profiles are now also assigned to empty bundles (in profile order) so a fresh bundle starts working immediately.
- **An unconfigured (empty) profile no longer claims a bundle.** If you haven't set any rules on a profile yet, it's skipped entirely — it won't grab a bundle and eject items that don't match nothing.
- **Removed the old default "Bundle" storage preset** (the third tab under Storage) — it was a leftover, non-functional mechanism from before Bundle Profiles existed. Bundle filling is now driven exclusively by the real Bundle Profiles feature.

## 🖥️ UI fixes and polish

- **Fixed the Storage tab selection highlight** landing on the wrong tab — the indicator's position math had drifted out of sync after the Bundle Profiles button was added and was never updated to match.
- **Dialogs now defensively size themselves** to the player's actual GUI Scale and window size, so warning/confirm boxes and the mode-picker never overflow the screen or overlap their own buttons, even at small resolutions or high GUI Scale.
- **Slightly larger text at GUI Scale 3–4** (a subtle, capped +2%/+4% bump) for readability — layout and click areas are unaffected, only the glyphs grow.
- Filled in 7 previously untranslated strings (Bundle Profiles screen, Switch setup title) that had silently fallen back to English in 8 of the 9 supported languages.

## 📖 Localization

- All new UI text (complexity modes, onboarding, mode-switch confirmation) is fully localized in all 9 supported languages: English, German, Spanish, French, Hungarian, Japanese, Portuguese (BR), Russian, Chinese (Simplified).

---

## ⏸️ A quick note on what's next

With 1.6.0, Inventory Organizer is in a good, stable place. We're going to slow down InOr development for a while — not because it's finished, but because we're turning our attention to building a **couple of other mods** alongside it. The plan is to eventually bring them together into a single, optional **bundle**, so you can grab everything in one place if you want to.

It means InOr updates will come less often for a bit, but it also means more (and more varied) content is coming your way down the line. Thanks for using the mod — see you in the next update!

---

**Version**: 1.6.0
**Platform**: Modrinth (Fabric) — MC 26.2
**Author**: Zefryxis
