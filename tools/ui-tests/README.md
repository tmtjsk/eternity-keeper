# UI suites

Scripts that drive a running editor through the embedded browser's DevTools
protocol and check what it does: every view and dialog, one real edit through
every write path (saved and read back from the written file), layout rules
measured in pixels, both themes. The Java tests cover the save format; these
cover the page.

Windows only, like the editor.

## Setup

1. Build the editor: `mvn install -Pwin64` (the suites run `target/eternity-keeper.jar`).
2. Make a test environment, a folder of copied saves the suites may edit:

   ```bash
   java -Djava.io.tmpdir=../test-env -Dek.data=target/ui-tests/data -cp target/eternity-keeper.jar uk.me.mantas.eternity.TestEnvironment "<game install>" "<your saves folder>" -n 12
   ```

   This makes `..\test-env\EK_TEST_ENV\{poe,save}` beside the checkout. It
   copies saves out of your saves folder and never writes into it.
3. Read the game data once (several suites need item names and icons):

   ```bash
   python tools/gamedata/extract_gamedata.py --game "<game install>" --out "<folder>"
   ```

4. `pip install websocket-client`

`config.py` says where everything is. Each value can be set with an environment
variable instead:

| Variable | Default |
|---|---|
| `EK_REPO` | this checkout |
| `EK_JAVA` | `..\tools\jdk8u492-b09\bin\java.exe` beside the checkout, else `java` (must be Java 8) |
| `EK_TEST_SAVES` | `..\test-env\EK_TEST_ENV\save` beside the checkout |
| `EK_GAMEDATA` | `..\itemdata` beside the checkout |
| `EK_GAME` | the Steam install, for `gamedata_ui.py full` |
| `EK_UI_TEST_OUT` | `target\ui-tests`: logs, screenshots, the editor's settings |
| `EK_DEBUG_PORT` | 13002 |

**Never point `EK_TEST_SAVES` at your real saves.** The suites open, edit and
write saves there (they delete what they write).

## Running

```bash
python tools/ui-tests/run_suites.py                     # every suite below but gamedata_ui
python tools/ui-tests/run_suites.py writes.py mint.py
```

`run_suites.py` starts a fresh editor for each suite, with its own settings in
`target\ui-tests\data`, and writes each suite's output to
`target\ui-tests\suite-<name>.log`. It closes every running editor first
(`java.exe`, `javaw.exe` and the browser's `jcef_helper.exe`, which outlives
a killed java and keeps the DevTools port bound). Close your own editor before
running it.

| Suite | Checks |
|---|---|
| `smoke.py` | A cold boot: every view opens and nothing throws |
| `functional.py` | One real interaction per feature, checked against the model |
| `panels.py` | Revert and Apply on every panel |
| `audit.py`, `audit2.py` | Every view and dialog, in both themes: nothing clipped, off screen, too small to hit or faded into its background (`shaudit.js` measures) |
| `paging.py` | Paged browsers reach the end of their lists |
| `format_ui.py` | The save-format conversion dialog |
| `consistency.py` | Views agree with each other after edits |
| `merge_bugs.py` | Unsaved edits survive every Apply |
| `writes.py` | One edit through every write path, saved and read back from the file |
| `mint.py` | A minted item and ability, saved and read back |
| `layout_rules.py` | Measured layout rules (packs three to a row, controls beside what they act on…) |
| `look_variants.py` | The overflow checks at 1440px and in light mode |
| `catalog_offer.py` | The browsers offer only shipped content |
| `backups_ui.py` | A save deleted from the list is copied first; File → Backups restores it, refuses to restore over it, and a rename is copied too (works on a throwaway copy of one test save) |
| `stronghold_people.py` | Hirelings and prisoners by the game's own names; a staged dismissal projects the gauges, Keep and Revert undo it, Apply dismisses; a prisoner released and read back from the written file (borrows a save with a prisoner from the real saves folder's `2.0 Save Games Backup`, and deletes it afterwards) |
| `gamedata_ui.py` | The game-data banner and Settings row on a first launch; `full` also runs a real extraction (a few minutes) |

`gamedata_ui.py` starts its own editor with an empty data folder, so run it
directly rather than through `run_suites.py`.

`ingame_save.py` and `ingame_save_b.py` build two saves that exercise every
editor feature, for loading in the game itself; `ingame_portraits.py` builds
one whose load-list thumbnails have changed (the Watcher's face, a smaller
party) and checks them in the file; `ingame_keep.py` builds one with the
Crucible Knight dismissed and Kestorik released; `filedialog.py` answers the
native file dialogs the editor opens. They are not pass/fail suites.

A caution: many page reloads in one session can leave queries unanswered, which
shows up as a dialog stuck on its loading text. That is why each suite gets a
freshly started editor.
