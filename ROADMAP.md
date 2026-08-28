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
| **Item catalog** | 2,156 items with real names, icons, stack sizes, slot rules, class restrictions, quality tiers |
| **Abilities tab** | Per-character abilities, spells and talents, with a class-aware browser |
| **Ability catalog** | 1,440 abilities, spells and talents plus all 26 progression tables |
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

## 3. Feature roadmap

Features come first; store/platform compatibility follows in §4.

### Phase 1 — character progression

**1.1 Skills editor** — ✅ **done**
Athletics, Stealth, Lore, Mechanics, Survival and Crafting are already on the
proven scalar write path — they just have no friendly UI. Worth knowing: the
save stores cumulative **points**, while the character sheet shows the derived
**rank** (rank N costs N(N+1)/2 points), so the editor must show both or edits
will look like they did nothing.
Shipped: rank-based editing with the point total shown alongside, unspent points,
and confirmation from the decompiled source that skills are *not* prefab-reset for
companions (only the six base attributes are). Verified against a real save —
Mechanics 0 → rank 5 wrote 15 points.

**1.2 Talents and abilities** — ✅ **done**
Add and remove abilities, spells and talents, with the browser restricted to
what each character could actually take (straight from the game's own
`AbilityProgressionTable`, including per-companion tables). The extractor did
generalise: the same script now emits `abilities.json` (1,440 entries) and
`progression.json` alongside the item catalog. Verified end-to-end in the
running editor against a level-16 party — 620 abilities and talents read back
with full catalog coverage, and a staged talent applied and reloaded correctly.

**1.3 Culture, race and class**
Enums already reach the UI. Caveat to handle: changing race or class changes
which equipment slots exist (godlike lose the head slot, non-wizards lose the
grimoire), so the editor must deal with gear that becomes illegal.
*Effort: medium. Risk: medium.*

### Phase 2 — world and inventory

**2.1 Vendor cleanup** — *re-scoped after measuring; blocked on new save-pipeline
support, and the one-button version is unsafe*

The premise is half right. Vendor stock does accumulate: a mid-game save carries
**68 stores holding 3,772 items**. But two things measured on a real save change
what can be built.

*It is not in the file the editor edits.* A scan of that save found **0 stores in
`MobileObjects.save`** — every one of the 68 lives in a `.lvl` area file. Those
use the identical serializer and all 164 of them read in 9.9s (77 MB), so the
data is reachable, but `ChangesSaver` has only ever re-written the world state
and copies the area files through untouched. Editing them means teaching the
save pipeline to rewrite `.lvl` files, which is a piece of work in its own right
and carries the risk of a 77 MB blast radius rather than a 13 MB one.

*A blanket purge would destroy real content.* `Store.RegenerateItems()` only
destroys and re-adds the items named in that store's `RegenerationItemTable`.
Anything else in a store's `ItemList` — including the unique items a player has
not bought yet, and everything they sold and might want back — is there
permanently and never regenerates. "Delete all vendor stock" would quietly
remove purchasable uniques from the save.

So this should not be a button. The shape that survives both findings is a
**reviewable per-store list**: read the `.lvl` files, show what each store holds
with catalog names and prices, and let the player delete what they choose,
defaulting to nothing. Worth splitting into (a) `.lvl` read/write in the save
pipeline and (b) the store browser on top of it.
*Effort: high, most of it in (a). Risk: medium — a new class of file to write.*

**2.1b Bulk tidy-up and sell — requested on Reddit** — *done*

Shipped in the Inventory tab: a sell mode that turns every pack and the stash
into a selection and credits the party's money with what a store would pay, and
a "Tidy stacks" button that repacks each container up to the game's own cap.
Prices come from the catalog, which the extractor now fills in properly (1,658
of 2,156 items priced, 177 of them `FullValueSell`).

"Select junk" ended up much narrower than first planned. Price alone sweeps up
the party's whole stock of potions, and MISC is worse — the game files
grimoires, pets, hides and lockpicks there next to the lore books. Junk is
therefore unenchanted worn gear only: WEAPONS, ARMOR or CLOTHING, under the
1000cp an enchantment costs, not unique or soulbound, and never something that
goes in a grimoire or pet slot.

Verified end to end: 13 items sold for 270cp produced a file with exactly 13
fewer packets and 13 fewer inventory entries, the purse up by exactly 270, and
the game itself then loaded it showing 1,000,313cp and the two unsold items
still in the stash.

The original note, kept because the reasoning still applies:

A natural companion to vendor cleanup, and every piece is already here:
`InventoryManager` removes an item in the three places it lives, and currency is
a scalar the currency editor already writes.

What the game says, from the decompiled `Item`:

    GetDefaultSellValue() = floor(GetValue() * 0.2), or floor(GetValue())
                            when the item's FullValueSell flag is set

so a dump-everything button should pay a fifth of value, the way a store does.
Two things need doing first:

- The catalog carries no prices. `Value` is a `CurrencyValue` struct
  (`{"v": 50.0}`) and the extractor's number check silently dropped every one —
  fixed in the script, but the catalogs need regenerating to pick it up. It now
  records `fullValueSell` too.
- `Equippable.GetValue()` adds each item mod's `Cost * ItemModCostMultiplier`
  (doubled for two-handers), so an enchanted weapon is worth well above its base
  value. Pricing from the base alone would quietly short-change exactly the
  items a player most wants to sell. Extracting mod costs is the honest version.

Worth deciding before building: "stack identical items" only means something up
to `MaxStackSize` — 501 of 2,156 items stack at all, and weapons and armour
never do. The sensible reading of the request is *select a set, delete it,
credit the purse*, with stacking as tidy-up where the cap allows.
*Effort: medium, most of it in the catalog. Risk: low — deletion and a scalar.*

**2.2 Stronghold editor**
Prestige, security, debt and turns are plain scalars already extracted.
Upgrades, hirelings and prisoners are structural lists; `PartyManager` already
mutates `SerializedStoredGuids`.
*Effort: medium. Risk: low for the scalars.*

**2.3 Grimoire editor**
Which spells sit in which grimoire. Depends on 1.2's ability catalog.
*Effort: medium. Risk: medium.*

**2.4 Companion portrait picker**
Currently a manual file-shuffle. The editor already reads the portrait
directory. Good small win.
*Effort: low. Risk: low.*

### Phase 3 — deeper save surgery

**3.1 Quest editing beyond restore**
`QuestTrackerBlob` already reads and patches tracker state. Full editing needs
an NRBF *writer* for `SerializedActiveQuests`.
*Effort: high. Risk: high.*

**3.2 Save validation pass**
Assert the invariants already learned before writing: every `InstanceID.Guid`
equals its own `ObjectID`, no duplicate ObjectIDs, list lengths match counts,
every `SerializedItemList` GUID resolves. This would have caught the
item-minting aliasing bug immediately instead of only in-game.
*Effort: low. Risk: none — read-only checks. Recommended early despite the phase.*

**3.3 Save comparison** — diff two saves and show what changed.
**3.4 Undo within a session** — an undo stack over the staged model.

---

## 4. Compatibility (after the features)

### 4.1 Detect the game across every store

Today `Configuration.installationLocations` is a hardcoded list checked **only on
the system drive** (`GetDefaultSaveLocation:130`). A Steam library on `D:` — the
most common setup — is never found, so users must set the path by hand.

Saves are less of a problem: every desktop store writes to
`%USERPROFILE%\Saved Games\Pillars of Eternity`. The install path is what
matters, because that's where portraits and the item catalog come from.

Detection order, first hit wins:

1. **Explicit setting** — always respected.
2. **Steam** — read `steamapps/libraryfolders.vdf` (found via
   `HKCU\Software\Valve\Steam\SteamPath`), then check every library. Solves
   multi-drive setups generally rather than guessing paths.
3. **GOG** — `HKLM\SOFTWARE\WOW6432Node\GOG.com\Games\*` carries `path`.
4. **Epic** — parse `C:\ProgramData\Epic\EpicGamesLauncher\Data\Manifests\*.item`.
5. **Any drive, known layouts** — repeat the existing list across every fixed
   drive. This alone fixes most cases.
6. **Microsoft Store / Game Pass** — detect and explain; the `gameflt` kernel
   driver blocks reads. (Windows Store *saves* are already convertible.)

*Effort: medium. Risk: low — detection only, manual override intact.*

### 4.2 Mac support — asked for on Reddit
Build and bundle JCEF for macOS. Concretely, what is missing today:

- `pom.xml` has profiles for `win64`, `win32` and `linux64` only, and
  `lib/native/` holds the matching three. There is no macOS JCEF/JOGL native
  bundle, so the app cannot start there at all.
- Everything above the browser layer is plain Java 8 and portable. Save paths
  are the other platform-specific piece: macOS keeps saves under
  `~/Library/Application Support/Pillars of Eternity/`, which the detection work
  in 4.1 has to cover.
- The item and ability catalogs come from the game's own asset bundles, so they
  work anywhere the game is installed.

This is the single most requested thing from outside, and it is packaging work
rather than save-format work — worth pulling forward if the goal is other people
using the editor rather than just this fork.

### 4.3 Faster Windows Store conversion

### 4.4 Bundle the item catalog extractor
So users aren't asked to install Python.

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

## 6. Order of work

Features first, per the project owner's direction; compatibility afterwards.

1. ~~Commit the current work.~~ done
2. ~~Skills editor (1.1)~~ done
3. ~~Talents and abilities (1.2)~~ done
4. ~~Bulk tidy-up and sell (2.1b)~~ done
5. **Stronghold editor (2.2)** ← next
5b. Vendor cleanup (2.1) — deferred: needs `.lvl` read/write first, and has to
    be a reviewable list rather than a purge (see above)
6. Culture / race / class (1.3)
7. Grimoire editor (2.3), companion portraits (2.4)
8. Save validation pass (3.2) — cheap, pull earlier if bugs bite
9. **Then** compatibility: multi-store detection (4.1), Mac, faster conversion
10. Delete the auto-updater and bootstrapper whenever convenient
