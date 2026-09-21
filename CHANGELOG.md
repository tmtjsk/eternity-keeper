# Changelog

## 1.0.0-beta (unreleased)

The first release of this fork, and the first that installs by unzipping.
Compared with upstream Eternity Keeper 0.21a, from 2016:

### Getting started

- **A ready-to-run Windows download.** One zip holding `Eternity Keeper.exe`,
  the Java 8 runtime it needs and the embedded browser. Nothing to install, and
  whatever Java the machine has is never used.
- **Works from any folder.** Settings and the log live in
  `%APPDATA%\Eternity Keeper`, so the editor runs from Program Files, a
  shortcut or a USB stick. Settings from an older copy are brought across once.
- **Game data is read for you.** Item names and icons, abilities, stronghold
  upgrades and deities come from your own game install. The editor offers to
  read them on first launch (a few minutes, once), shows its progress, and
  can do it again from Settings after the game updates.
- **Finds the game wherever it is installed**: Steam libraries on any drive,
  GOG, Epic, or common folders on every drive. A Microsoft Store copy is
  detected and explained, because Windows does not let other programs read its
  files.
- Saves from the current game (tested with Steam v3.9.5) open, edit and load
  back in the game correctly.

### Editing

- **Inventory**: every party member's pack, their equipment and the shared
  stash, with real names and icons, and the equipment laid out around the
  character the way the game's own inventory screen has it. Move items between characters, equip and
  unequip them (the game's slot, class and race rules are enforced), change
  stack sizes, add any of the game's items, sell junk in bulk, tidy stacks.
- **Abilities and talents**: see what a character knows and add anything their
  class, race or companion could learn, including the abilities a talent grants.
- **Grimoires**: edit a wizard's spellbook four spells to a level, the way the
  game lays it out.
- **Stronghold**: build and demolish Caed Nua's upgrades with the game's own
  Prestige and Security arithmetic, and edit turns, debt and taxes.
- **Identity**: race, subrace, class, culture, background, deity and paladin
  order, showing what each choice does to the character sheet.
- **Skills** edited as the ranks the game shows, not raw points.
- **Portraits**: pick any portrait on your install, including ones you added.
  The game's load list shows the new faces too: Save redraws the party
  thumbnails stored in the save, which the game only updates when it saves.
- **Resurrect dead companions**, including their failed personal quest and
  Sagani's pet.
- **Party management** from anywhere: swap companions between the party and the
  stronghold roster.
- **Console tab**: re-enable achievements, and run the save-representable
  console commands (experience, attributes, skills, money, globals,
  stronghold), with a searchable reference of the in-game-only ones.
- Difficulty, Expert Mode, Trial of Iron and turn-based mode, and party money.
- Import and export characters as `.chr` files.
- Revert and Apply on every panel. Save shows the exact file name and folder it
  will write.

### Safety

- **Your original save is never written to.** Edits go to a private working
  copy, and Save writes a new file.
- **Backups.** Before the editor deletes a save, renames one or replaces a file
  of the same name when saving, it keeps a copy. File → Backups lists the ten
  most recent and puts any of them back; restoring never replaces a save that
  is there.
- Delete on the save list only ever deletes a `.savegame` file.
- Unsaved edits survive every Apply: a change made on one tab is not lost when
  another tab reloads the save.
- A save that breaks the format's own rules is flagged when it opens, before
  the game quietly drops what it cannot read.
- Polish and other non-ASCII save names display correctly.

### Also

- Light and dark themes.
- Saving is about 15 times faster (roughly 2 seconds instead of 30).
- Save format conversion (*Format* on the save list) rewrites a save for Steam
  and GOG builds older than the 2017 Unity update.
- The embedded browser's remote-debugging port is off unless asked for
  (`-Dek.debugPort`): it let any program on the machine drive the editor.
- org.json is now a public-domain release. The 2014 one was under the JSON
  License, whose "Good, not Evil" clause is at odds with the GPL.

### Removed

- The auto-updater and its bootstrapper.
- 32-bit Windows builds. Linux builds still compile but are untested and
  unsupported for this release.
