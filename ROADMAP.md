# Eternity Keeper — state of the project and where it goes next

A working map of what exists today, what should be cleaned up, and what to build
next. Kept next to the code so it stays honest.

---

## 1. Where the project actually is

~17,200 lines of Java, 163 passing tests, a jQuery/Bootstrap UI running on an
embedded Chromium (JCEF), and a save format that has been reverse-engineered far
enough to mint objects the game accepts.

### Shipped and verified in-game

| Area | State |
|---|---|
| Character attributes, skills, raw variables | Works. Companion **base attributes** can't stick — the game re-copies them from the prefab on load, so the UI locks them |
| Character import/export (`.chr`) | Works, including overwrite-in-place |
| Party management | Move companions between active party and stronghold roster |
| Resurrect dead companions | Donor transplant, validated against a 12-save death matrix |
| Quest restore on resurrection | Patches the NRBF quest-tracker blob; byte-exact against 11 real pairs |
| Console tab | Achievements toggle, save-representable console commands, in-game command reference |
| Difficulty editor | Difficulty, Expert, Trial of Iron, Tactical mode |
| Currency editor | Works |
| **Inventory tab** | Per-character packs, equipment, quick items, weapon sets, stash, item browser |
| **Item catalog** | 2,184 items with real names, 969 icons, stack sizes, slot rules, class restrictions, quality tiers |
| Polish/UTF-8 save names | Reads `sceneTitle` from `saveinfo.xml` |
| Light/dark themes | Both audited |
| Windows Store → Steam/GOG conversion | Works, slow |

### Known limitations, stated plainly

- **Auto-updater is dead code.** ~591 lines across three handlers plus `Updates.js`.
- **Game install detection only scans the system drive** — a library on `D:` is
  never found automatically (see §4).
- **The item catalog is generated offline** by a Python script; the editor
  degrades to prettified file names without it.
- **The game's own UI sprites are not extractable** — item icons are fine, but the
  inventory chrome lives in an `InventoryAtlas` whose sprite rects aren't readable.
- **Mac is unsupported** — JCEF natives aren't bundled.

---

## 2. Cleanup

### Done

- Removed `bin/` — 240 stale `.class` files committed to git; Maven builds to `target/`.
- Removed `EternityBootstrapper/.vs/` — committed IDE state, including a binary `.suo`.
- Added `bin/` and `.vs/` to `.gitignore`.

### Proposed, not done (needs a decision)

| Item | Size | Recommendation |
|---|---|---|
| Auto-updater (`CheckForUpdates`, `DownloadUpdate`, `CheckDownloadProgress`, `Updates.js`, the updates dialog) | ~591 LOC | **Delete.** It has been broken for years and every doc says "don't build on it". Removing it also removes the only reason `EternityBootstrapper/` exists |
| `EternityBootstrapper/` (C# launcher) | ~950 KB | Delete **with** the updater, since it exists to support it |
| `README.md` | — | Rewrite: it still lists shipped features under "Planned" |

> **Note:** a large amount of work is currently uncommitted (24 new files, 17
> modified). Committing before further refactoring is strongly advised.

---

## 3. Next features, in priority order

### Tier 1 — finish what's started

**1.1 Multi-store game detection** (see §4 — biggest correctness win)

**1.2 Skills and talents editor**
Skills already work on the tested scalar path; they're just not surfaced in a
friendly UI. Talents/abilities need prefab knowledge — the same catalog trick
used for items should work on the ability bundles.
*Effort: medium. Risk: low for skills, medium for talents.*

**1.3 Vendor cleanup**
Every item ever sold to a vendor is stored forever. Deleting them shrinks saves
and speeds up load/save. The purge machinery already exists (`Resurrector`,
`InventoryManager`).
*Effort: low-medium. Risk: low — it's deletion of provably unreferenced objects.*

**1.4 Stronghold editor**
Prestige, security, debt, turns are plain scalars already extracted. Upgrades,
hirelings and prisoners are structural lists; `PartyManager` already mutates
`SerializedStoredGuids`.
*Effort: medium. Risk: low for scalars.*

### Tier 2 — new capability

**2.1 Culture, race and class editing**
Enums already shipped to the UI. **Caveat worth testing first:** changing race
changes which equipment slots exist (godlike lose the head slot), and changing
class changes grimoire access — the editor would need to handle gear that
becomes illegal.

**2.2 Grimoire editor**
Spell contents per grimoire. Needs ability-prefab knowledge, same as talents.

**2.3 Quest editing beyond restore**
`QuestTrackerBlob` can already read and patch tracker state. Full editing needs
an NRBF *writer* for `SerializedActiveQuests`. *Effort: high.*

**2.4 Companion portrait picker**
Currently a manual file-shuffle. The editor already reads the portrait directory.
*Effort: low. Good first issue.*

### Tier 3 — platform and quality

**3.1 Mac support** — build and bundle JCEF for macOS.
**3.2 Faster Windows Store conversion.**
**3.3 Bundle the item catalog extractor** so users aren't asked to run Python.

---

## 4. Support every store, not just Steam

Today `Configuration.installationLocations` is a hardcoded list checked **only on
the system drive** (`GetDefaultSaveLocation:130`). A Steam library on `D:` — the
most common setup — is never found. Users must set the path by hand.

Saves are less of a problem: every desktop store writes to
`%USERPROFILE%\Saved Games\Pillars of Eternity`. The install path is what matters,
because that's where portraits and the item catalog come from.

**Proposed detection order, first hit wins:**

1. **Explicit setting** — whatever the user chose, always respected.
2. **Steam, properly.** Read `steamapps/libraryfolders.vdf` from the Steam install
   (found via `HKCU\Software\Valve\Steam\SteamPath`), then check every library for
   `steamapps/common/Pillars of Eternity`. This solves multi-drive libraries
   generally rather than guessing paths.
3. **GOG.** `HKLM\SOFTWARE\WOW6432Node\GOG.com\Games\*` carries `path` per game;
   fall back to `GOG Games\Pillars of Eternity` on each drive.
4. **Epic.** Parse `C:\ProgramData\Epic\EpicGamesLauncher\Data\Manifests\*.item`
   (JSON) and match `DisplayName`/`InstallLocation`.
5. **Any drive, known layouts.** Repeat the existing list across every fixed
   drive rather than only the system drive — this alone fixes most cases.
6. **Microsoft Store / Game Pass.** Detection only; the `gameflt` kernel driver
   blocks reads from the mounted volume. Should say so plainly rather than fail
   silently. (Note: Windows Store *saves* are already convertible.)

**Verification:** a unit test per store with a faked filesystem/registry layer,
plus a manual check on this machine's real `D:\Steam` install.

*Effort: medium. Risk: low — detection only, with the manual override intact.*

---

## 5. Ideas worth considering

Not requested, offered for the record.

- **Backup-before-save.** The editor never overwrites originals, but Steam Cloud
  has deleted saves during testing on this project before. A one-click backup
  folder would be cheap insurance.
- **Save comparison.** Diff two saves and show what changed — the single most
  useful thing when working out whether an edit took effect.
- **Validation pass before writing.** Assert the invariants already learned:
  every `InstanceID.Guid` equals its own `ObjectID`, no duplicate ObjectIDs,
  list lengths agree with counts, every `SerializedItemList` GUID resolves to a
  real packet. This would have caught the item-minting aliasing bug immediately
  instead of only surfacing in-game.
- **Undo within a session.** Changes are staged then applied; an undo stack over
  the staged model is achievable.
- **Bulk party operations** — heal all, level all, refill camping supplies.
- **Search across everything** — one box that finds a character, item or global.
- **Preset/loadout export** — share a fully-equipped character as a file.

---

## 6. Suggested order of work

1. Commit the current work.
2. Delete the auto-updater and the bootstrapper.
3. Rewrite the README to match reality.
4. Multi-store detection (§4).
5. Save validation pass (§5) — cheap, and protects everything after it.
6. Vendor cleanup.
7. Skills editor, then stronghold scalars.
8. Talents/grimoires once ability prefabs are catalogued.
