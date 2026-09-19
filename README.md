# Eternity Keeper

A save editor for **Pillars of Eternity**: characters, inventory, abilities,
grimoires, the stronghold, the party, and more. It works with the current
version of the game (tested with Steam v3.9.5).

This is a fork of [Eternity Keeper](https://bitbucket.org/Fyorl/eternity-keeper)
by Kim Mantas, which stopped at 0.21a in 2016. See [CHANGELOG.md](CHANGELOG.md)
for everything added since.

> Unofficial fan project, not affiliated with Obsidian Entertainment or Paradox
> Interactive. It contains no game files: item names, icons and other game data
> are read from your own installation.

## Download and run (Windows)

1. Download `EternityKeeper-<version>-win64.zip` from the releases page.
2. Extract the whole zip anywhere, keeping every folder in it.
3. Run `Eternity Keeper.exe`.

Nothing needs installing: the zip carries the Java 8 runtime and the embedded
browser the editor uses. On first launch it finds your game and saves, then
offers to read item names and icons from your install (a few minutes, once).

Windows is the supported platform for 1.0. Linux builds compile but are
untested; macOS has no build yet.

**Your saves are safe.** The editor never writes to the save you opened: Save
always writes a new save file, which the game lists beside the original. Keep
a backup of `%USERPROFILE%\Saved Games\Pillars of Eternity` anyway until you
have loaded an edited save in the game.

## What it edits

- **Characters**: attributes, skills (as the ranks the game shows), experience,
  race, subrace, class, culture, background, deity and paladin order, with what
  each does to the character sheet; portraits from your install; names.
- **Inventory**: every party member's pack, equipment and the shared stash,
  with real names and icons. Move, equip and unequip items under the game's own
  slot, class and race rules; change stack sizes; add any item in the game;
  sell junk in bulk.
- **Abilities and talents** a character could learn, including the abilities a
  talent grants.
- **Grimoires**, four spells to a level, laid out like the game's spellbook.
- **The stronghold**: build and demolish upgrades with the game's own Prestige
  and Security arithmetic; turns, debt and taxes.
- **The party**: swap companions with the stronghold roster from anywhere, and
  resurrect dead companions, including their failed quest.
- **Console**: re-enable achievements, and run the console commands a save can
  represent (experience, attributes, skills, money, globals, stronghold).
- Difficulty, Expert Mode, Trial of Iron, turn-based mode, and party money.
- Import and export characters as `.chr` files.
- Convert a save for Steam and GOG builds older than the 2017 Unity update.

The **Raw** tab shows every stored number on a character. Most of them have
never been tested in the game; prefer the dedicated panels.

## Reporting a bug

Open an issue and attach `eternity.log`. It is in `%APPDATA%\Eternity Keeper`,
and Settings shows the exact path. Say what you did, what you expected and what
happened; the save itself helps most of all.

## Building from source

You need a **Java 8 JDK** and **Maven 3**. The embedded browser's natives are
Java 8 era, so a newer JDK builds but will not run the editor.

```bash
mvn install -Pwin64
run.bat
```

`-Pwin64` is required: the browser dependency is profile-scoped. `run.bat`
starts `target/eternity-keeper.jar` with a JDK 8 (it prefers
`..\tools\jdk8u492-b09` beside the checkout, else `java` on `PATH`) and keeps
settings and the log in the checkout rather than in `%APPDATA%`.

Useful system properties:

| Property | Effect |
|---|---|
| `-Dek.data=<folder>` | Where settings, the log and game data live |
| `-Dek.ui=<folder>` | Load the UI from this folder |
| `-Dek.debugPort=13002` | Open the embedded browser's DevTools port (the UI tests use it) |
| `-Dek.extractor=<program>` | The game-data reader to run (`.py` runs under Python) |

### Game data

`tools/gamedata/extract_gamedata.py` reads the item and ability catalogs,
progression tables, stronghold upgrades and deities out of a game install with
[UnityPy](https://github.com/K0lb3/UnityPy). The editor runs it itself (from a
checkout, under `python`), but it also works by hand:

```bash
pip install -r tools/gamedata/requirements.txt
python tools/gamedata/extract_gamedata.py --game "<install folder>" --out "<folder>"
```

See [tools/gamedata/README.md](tools/gamedata/README.md) for what it reads and why.

### Tests

```bash
mvn test -Pwin64
node src/test/js/SaveMergeTest.js
```

The scripted UI suites, which drive a running editor over the DevTools
protocol, are in [tools/ui-tests](tools/ui-tests/README.md).

### Making a release

```powershell
pwsh tools/release/build-release.ps1
```

Builds the jar and `Eternity Keeper.exe`, freezes the game-data reader with
PyInstaller, and zips them with a Java 8 runtime into
`target/release/EternityKeeper-<version>-win64.zip`.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md). [ROADMAP.md](ROADMAP.md) has the plan.

## How the saves work

A `.savegame` is a heavily compressed zip: `MobileObjects.save` (the world
state), one `.lvl` file per visited area, `saveinfo.xml` and a screenshot. The
world state is serialized with the .NET library
[SharpSerializer](https://github.com/polenter/SharpSerializer), which
`uk.me.mantas.eternity.serializer` reimplements in Java; `TypeMap.java` maps
every C# type a save can hold. The game's saves are compressed harder than
default zip settings, so the files the editor writes are somewhat larger than
the game's own.

Every item ever sold to a vendor stays in the save, inside the `.lvl` files,
which is one reason saves grow as a game goes on.

## Acknowledgements

The application icon is by
[Alexander Loginov](http://alexanderloginov.deviantart.com/). Contributors are
listed in [CONTRIBUTORS](CONTRIBUTORS), third-party software in
[THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md).

## License

GNU General Public License v3 or later. See [LICENSE](LICENSE).
