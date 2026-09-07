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

**1.3 Culture, race and class** — *done*

An Identity panel under the attributes and skills: race, subrace, class,
culture, background and gender, plus a deity for priests and an order for
paladins. All six are `[Persistent]` enums on `CharacterStats`, so they ride
the ordinary scalar path and are written on Save with everything else — no new
handler.

Three things the decompile settled:

- **`RacialBodyType` is not editable and must not be written.** `Awake()` sets
  it to `CharacterRace` for anyone who is not godlike, and for a godlike it
  holds the body underneath — the test save's Moon Godlike player really does
  carry an Aumaua body.
- **Only base attributes are re-copied from a companion's prefab** by
  `Restored()`, so identity sticks for companions exactly as skills do.
- The equipment caveat is real and the game does not clean up after it.
  `RepairSaveLoadEquipmentErrors()` handles the deprecated Cape and locked
  slots only, so an item left in a slot that stops existing is simply
  unreachable. The panel names the item and says to unequip it in the
  Inventory tab rather than moving it silently.

Subrace is scoped to race (the godlike set is exactly what
`SubraceIsGodlike()` tests), and a race change carries the subrace with it.
Verified in the game: a companion changed to a Mountain Dwarf Cipher of Rauatai
reads back exactly that on the character sheet.

**1.3b What each choice does** — *done*

Every dropdown now carries a line saying what it is worth, and the panel ends
with the totals the game's own sheet will show.

The reason this works at all: none of it is baked in at character creation.
`GetAttributeScore()` adds `RaceAbilityAdjustment` and
`CultureAbilityAdjustment` to the stored `BaseX` on every read, and
`CalculateSkillInternal()` adds `ClassSkillAdjustment` (behind an
`IsPlayableClass()` guard) and `BackgroundSkillAdjustment` to the rank. All
four are `static int[,]` fields on `CharacterStats`, copied verbatim into
`save/IdentityCatalog` — the two column orders differ and neither is the order
the sheet shows, so reading a row under the wrong one relabels every bonus
without ever looking wrong.

Two halves are not in the assembly. A subrace's racial ability is one row of
the game's own `racial` progression table, which `AbilityCatalog` already
carries, so the Moon Godlike line reads *Silver Tide* with the game's own
description. Deity and paladin-order dispositions are inspector arrays on the
`Religion` behaviour attached to InGameGlobal, so `tools/identity-extract`
pulls them into `itemdata/identity.json` alongside the ±20/40/60% ladder
`GetCurrentBonusMultiplier()` applies — for the player character alone.

Verified against the game: a 15th-level Moon Godlike Paladin of the Goldpact
Knights reads 21/10/13/14/12/19 and Stealth 2, Athletics 13, Lore 2,
Mechanics 0, Survival 7 in both the editor's panel and the character sheet,
and the sheet's own "Favored Dispositions: Stoic, Rational" matches what the
Order line predicts.

Turned up on the way: `Restored()` re-derives every `XBonus` and
`<Skill>Bonus` from the applied status effects on load, so a value written
straight into one of those fields does not survive a reload. Nothing in the
panel depends on it, but the Console tab's `Skill` command did — it wrote
`<Skill>Bonus` exactly as the in-game command does, which is right for a live
console and useless in a save. It now sets the stored points for a rank.

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

**2.2 Stronghold editor** — *the upgrades and scalars are done; hirelings and
prisoners are not*

Shipped as a Stronghold tab laid out like the game's own screen: the upgrade
list down the middle, prestige and security gauges in a rail on the right. It
refuses to touch a save where the player has not taken Caed Nua yet
(`SerializedIsActivated`), and deliberately does not offer to grant it — the
quest that hands over the keep also moves the party, advances its own state and
spawns the steward, none of which a flag flip would do.

The catch that shaped the whole thing: **Prestige and Security are plain
persisted scalars.** `CompleteBuildingUpgrade()` adds an upgrade's own
adjustments once, when it is built, and `DestroyUpgrade()` takes them back off;
neither is ever recomputed from `m_upgradesBuilt`. Appending to that list alone
would produce a stronghold with upgrades it gets no credit for. So the editor
does the game's arithmetic itself, which means it needs the game's numbers — 25
upgrades' worth of cost, build time, prestige, security, prerequisite, icon and
`UpgradeCompletedGlobalVariableName`, none of which are in the save. They live
on the `Stronghold` behaviour attached to the `InGameGlobal` prefab, and
`tools/stronghold-extract` pulls them out. Demolishing also cascades: taking
down an upgrade takes everything built on top of it, the way the game's own
tree requires.

Verified in the game: demolishing the Curio Shop and banking 7 turns produced a
save the game loads with Prestige 47 (down the +1 it was worth), Security 44,
every other upgrade still Completed, and the Curio Shop offered for purchase
again at 1,800cp / 2 days — exactly the price the catalog carries.

**Still to do:** hirelings and prisoners are shown read-only. Both are lists of
manufactured objects rather than enum values — a `StrongholdHireling` carries a
`CharacterStats` prefab reference — so adding one is the item-minting problem
again. Dismissing and releasing are symmetric with demolishing (remove from the
list, subtract the adjustments) and would be the cheap half.
*Effort: medium for the remainder. Risk: low for removals, medium for minting.*

**2.3 Grimoire editor** — *done*

A Grimoire tab: eight chapters of four slots, laid out like the game's own
grimoire, with the 114 wizard spells underneath to add from.

The shape turned out to be the easiest structural list in the save.
`SerializedSpellNames` is a flat `List<string>` of spell prefab names with no
cross-references, no UUIDs and no parallel structure — the same shape as
`m_upgradesBuilt`. The class's other `[Persistent]` member, `SerializedSpells`,
is a `SpellChapter[8]` of object references and comes back all-null in every
real save, so it is dead weight.

What needed care was the arithmetic the game does silently: the names setter
resolves each prefab, files it under its own `SpellLevel`, and drops anything
past the fourth at that level without a word. `GrimoireManager.plan()` mirrors
that exactly, so the editor cannot show a spell the next load would discard.

Two things measuring changed about the design. A grimoire is an **item**, so
the view lists books rather than characters — and a real mid-game save holds
**32** of them, almost all looted enemy books in the stash, which is why the
rail sorts equipped first and scrolls. And the inventory payload's containers
are `{component, maxItems, items}` rather than bare arrays, including the
stash; treating one as an array left the save view half-drawn.

Verified in the game: a level-8 spell removed from Aloth's grimoire in the
editor shows as chapters I-VII full and VIII holding one on the game's own
grimoire screen.

**2.4 Companion portrait picker** — *done*

A modal off the character view's own portrait: the portraits on the player's
own install, in a grid of the party-bar thumbnails, filtered by the folders
the game keeps them in and searchable.

It really is a small win, because a portrait is two plain strings and nothing
else. `Portrait` has exactly two `[Persistent]` fields — `m_textureLargePath`
and `m_textureSmallPath`, relative to `PillarsOfEternity_Data` — and
`Portrait.Start()` loads whatever they name, only deriving a path from
`CompanionInstanceID` when one is empty. `LoadTexture2DFromPathCallback` reads
the file straight off disk, which is why custom portraits have always worked
in this game and why the editor lists the directory rather than a fixed table.

Being scalars, they need no manager and no Apply step: generalising
`ChangesSaver.updateCharacter` over the component name was enough, and the
picker's edit is written by Save like any attribute change.

Two things the design turns on. Both halves are always written together — the
large is the character sheet, the small is the party bar — and a portrait
whose `_sm` counterpart is missing is not offered at all, because the game
would load the large one happily and hand the bar a blank white texture. And
the grid is paged at 40: the shipped game has 118 pairs and the small images
alone are about 1.9 MB, well past what one JCEF reply carries.

Verified in the game: giving the Watcher the Grieving Mother's portrait shows
her face in both the party bar and the character sheet, sheet otherwise
untouched.

Turned up on the way: Bootstrap's own `button.close { padding: 0 }` left an
11×21 hit target in **every** dialog, not just the party one that had already
been patched. `.modal button.close` now fixes them all.

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

Related, and already fixed: `Restored()` re-derives every `XBonus` and
`<Skill>Bonus` from the applied status effects, so the Console tab's `Skill`
command — which wrote `<Skill>Bonus`, faithfully copying the in-game console
command — produced a change no load would keep. It now sets the skill's stored
points for a rank, the way the Skills panel does. Worth a systematic look for
other fields with the same shape.
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
5. ~~Stronghold editor (2.2)~~ upgrades and scalars done; hirelings and
   prisoners still read-only
5b. Vendor cleanup (2.1) — deferred: needs `.lvl` read/write first, and has to
    be a reviewable list rather than a purge (see above)
6. ~~Culture / race / class (1.3)~~ done, with what each choice is
   worth shown under it (1.3b)
7. ~~Grimoire editor (2.3)~~ done
7b. ~~Companion portraits (2.4)~~ done
8. **Save validation pass (3.2)** ← next
9. **Then** compatibility: multi-store detection (4.1), Mac, faster conversion
10. Delete the auto-updater and bootstrapper whenever convenient
