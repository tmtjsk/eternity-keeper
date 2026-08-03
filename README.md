# COMPATIBLE WITH THE LATEST VERSION OF PILLARS OF ETERNITY (2025+)
**This fork has been updated and tested to work seamlessly with the latest Steam/GOG versions of Pillars of Eternity.**

A Pillars of Eternity save editor and character importer/exporter.

# Dependencies
Several dependencies are already bundled with the project. In order to build and run the project you will also need the following:

* [Apache Maven](https://maven.apache.org/)
* [JDK 1.8](http://www.oracle.com/technetwork/java/javase/downloads/jdk8-downloads-2133151.html) or above

# Support
Windows and Linux. The game itself is found automatically in the usual Steam and
GOG locations on the system drive; if your library lives on another drive or you
use Epic, set the path by hand in the settings dialog for now — broader detection
is the next thing on the roadmap.

# Contributing
This is a clone of the code from https://bitbucket.org/Fyorl/eternity-keeper/src/master/ but with
- the commit history revised to strip symbols from all the linux shared libraries (otherwise they were too large for github)
- some alpha changes from ktully which haven't been tidied up for upstream yet

1. Fork this repository.
2. Clone your forked repository.
3. Make changes.
4. Write tests.
5. Submit a pull request.

# Building
This project uses maven so building it should be relatively straightforward.

	mvn install -P<platform>

Where *<platform>* is one of `win32`, `win64`, or `linux64`.

# Running
Assuming you've already built the project and have a `target` directory now and have your Java 8 executable in your `PATH`, you can use `run.bat` or `run.sh` to run the project.
**Note:** Some users have reported segmentation faults when attempting to run Eternity Keeper using the OpenJDK. If you experience the same issue, switching to the Oracle JDK may resolve it.

# Converting Windows Store save files to Steam/GoG
Saves from the Windows Store/GamePass game build cannot be loaded by Steam or GoG game builds.

This alpha release includes initial code by ktully allowing you to generate Steam/GoG compatible saves from Windows saves.

1. Run Eternity Keeper as Normal
1. Close the settings dialog if it appears
1. Enter the path to your Windows Store saves in 'Save Folder' box and click 'Search' to refresh the savegame list.
    * Typically your Windows Store saves are in a numeric subdirectory like `%USERPROFILE%\Saved Games\Pillars of Eternity\1234567890987654`.
1. Click on a Windows Store save file to load it
1. Progress indicator on the selected file will spin as the file is opened and converted - this may take a few minutes (depending on your hardware and on how much progress you had made in the game)
1. When conversion has completed, the save file list will refresh showing a new `( converted)` save
1. Quit Eternity Keeper
1. Open your Windows Store save directory in Windows Explorer and move the converted savefile into `%USERPROFILE%\Saved Games\Pillars of Eternity\`, so that the Steam/GoG version of the game can find it.

**Note:** Keep a backup of your original unconverted save file(s) - the conversion process could conceivably cause the game to crash or have other unanticipated bugs later.


# Features

* Windows and Linux support
* Modify character attributes, skills and all raw numeric variables
* Modify character names (including companions)
* Character importing/exporting (`Character > Export character to file` saves a `.chr` file; `Character > Import character from file` adds a previously exported character to the currently open save, anchored next to the player). If the imported character already exists in the target save, Eternity Keeper asks for confirmation and overwrites in place instead of duplicating
* Light and dark mode (toggle in the top-right corner)
* Party management from anywhere (`Character > Party management`): move companions between the active party and the stronghold roster without travelling to Caed Nua
* **Resurrect dead companions** — dead companions are detected from the save's own death flags and revived by transplanting a donor record, including clearing the death flags, restoring their auto-failed personal quest, and bringing back Sagani's pet
* **Inventory tab** — a near-replica of the game's inventory screen. Every party member's own 16-slot pack, their equipment, quick items and weapon sets, plus the shared stash. Real item names and icons pulled from your own game install. Click to pick an item up, click again to drop it; double-click a stackable to set its quantity
* **Add any item in the game** — a browsable, searchable catalog of all 2,184 items; adding one mints a real object the game loads
* **Equipment rules are enforced** — the editor refuses illegal gear the same way the game does: godlike have no head slot, only wizards carry a grimoire, only the player has a pet slot, and class-restricted items stay restricted
* **Console tab** — re-enable achievements, and run the save-representable Pillars console commands (experience, attributes, skills, money, globals, stronghold) plus a searchable reference of the in-game-only ones
* Edit difficulty level (difficulty, Expert Mode, Trial of Iron, Tactical mode)
* Edit party currency
* Save dialog shows the exact file name and folder that will be written, with a folder picker
* Converts Windows Store saves to Steam/GOG format

**Note about the Raw tab**: The 'Raw' tab is a dump of all a character's stats from the save file. Changing most of these has not been tested and could result in a corrupt save. Eternity Keeper never overwrites your save files — it always writes a new, edited one.

## Item names and icons

The Inventory tab shows real item names and icons. These live inside the game's
Unity asset bundles, which Java 8 can't realistically parse, so they're extracted
once by a helper script from **your own game installation** (the same policy the
editor already follows for portraits):

    cd tools/itemdata-extract
    pip install UnityPy
    python extract_catalog.py

The result lands outside the repository and is picked up automatically. Without
it, everything still works — item names just fall back to prettified file names.

# Planned Features

See [ROADMAP.md](ROADMAP.md) for the full plan, including priorities and effort
estimates. In short:

* Detect the game across every store (Steam libraries on any drive, GOG, Epic), not just the system drive
* Skills and talents editor
* Vendor cleanup (delete everything you sold to vendors to shrink saves)
* Stronghold editor
* Culture, race and class editing
* Grimoire editor
* Mac support
* Faster Windows Store conversion

# Eternity Keeper as a platform
One of the long-term goals of this project will be to allow people to create and edit items, talents, quests, etc. using an intuitive UI and to save them in a format that allows them to be inserted into a saved game that Pillars of Eternity will recognise.

Some work was already done towards allowing characters to be imported and exported (just like in the IE games) but it turned out a bit more fiddly than initially imagined and was disabled for this release.

All of the data for the above is present in saved game files though so it should all be eventually possible.

# Technical Stuff
There's nothing stopping the code being built on Macs, however the JCEF dependencies are not pre-built and bundled with the source code in the repository yet so you will have to build those too.

# What I learned about save files
I figured some people might be interested in how the save files work since save/load times are a bit of an issue. I haven't delved too deeply but I made a few observations.

Firstly, most of main game data is serialized into binary using the SharpSerializer library. It's a free, open-source library and seems fairly mature though after having to essentially re-implement the whole library in Java, I'm not sure it's entirely as fast as it could be when it comes to serialization. Serialization might not be the bottleneck though.

Secondly, there is a lot of stuff stored in saved games. Every single item ever sold to a vendor is stored. I think containers and items dropped on the ground are also stored but I haven't looked into the area files yet. Speaking of area files, the fog of war for each area is saved and also probably a few other state variables. This means that, as the game progresses, you will add more and more area files to your save file.

The saved games are just compressed zip files. This revelation obviously isn't anything new and I think a lot of save files these days are similar. The only thing to note about this is that they are compressed very heavily, i.e. more than the standard LZMA compression settings. This will mean saving and loading will be slower as the (de)compression step is more intensive. Eternity Keeper only uses default settings and the save files it produces are a fair bit less compressed than the files the game produces. Again, whether this is an actual bottleneck in the save/load process is completely up for debate as the (de)compression time could well just be negligible.


# Testing
You may run the TestEnvironment tool to automatically copy a small number of saves as well as any necessary game data to a temporary directory on your filesystem and then modify your settings file to point to these. This should make UI testing a bit easier and less destructive. You can run the tool after building the whole project with:

	java -cp target/eternity-0.21a.jar uk.me.mantas.eternity.TestEnvironment <game location> <save location>

Where *<game location>* is the path to your Pillars of Eternity install and *<save location>* is the path to your save file directory.

You may also provide the `--help` flag to see advanced options.

# Acknowledgements
The icon used by Eternity Keeper was created by [Alexander Loginov](http://alexanderloginov.deviantart.com/).

Pillars of Eternity uses the [SharpSerializer](http://www.sharpserializer.com/) library to serialize its saved game data. The [SharpSerializer](http://www.sharpserializer.com/) source code was therefore referenced heavily in the creation of the serialization implementation however the implementation cannot be considered a complete port as it is coupled tightly with the save game format and cannot function as a serializer/deserializer without it.
