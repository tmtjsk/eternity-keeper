# Contributing

Bug reports with a log and, ideally, the save are the most useful thing you can
send (see the issue template). For code, read on.

## Setup

- A **Java 8 JDK** and **Maven 3**. Newer JDKs compile the code but cannot run
  the editor: the embedded browser's natives are Java 8 era.
- `mvn install -Pwin64`, then `run.bat`. `-Pwin64` is required.
- Python 3 with `tools/gamedata/requirements.txt`, to read game data from a
  checkout.
- Windows. Linux still compiles (`-Plinux64`) but nobody has run it recently.

If Maven fails with `PKIX path building failed`, your network inspects TLS and
Java does not trust its certificate. Set
`MAVEN_OPTS=-Djavax.net.ssl.trustStoreType=WINDOWS-ROOT` to use the Windows
certificate store.

## How the code is laid out

| Where | What |
|---|---|
| `src/main/java/.../serializer` | A Java reimplementation of SharpSerializer, the .NET library the game saves with. `TypeMap.java` is the schema: a C# type missing from it fails to read. |
| `src/main/java/.../game` | Java mirrors of the game's C# classes |
| `src/main/java/.../save` | Opening, editing and writing saves; one manager per feature (`InventoryManager`, `AbilityManager`, `StrongholdManager`…) |
| `src/main/java/.../handlers` | The bridge the page calls, one class per call, registered in `JSHandlers` |
| `src/ui` | The page: jQuery and Bootstrap in a Chrome 45 browser (no CSS grid, no flexbox `gap`, no custom properties) |
| `tools/gamedata` | The game-data reader (Python, UnityPy) |
| `tools/ui-tests` | Scripted UI suites |
| `tools/release` | The release build |

## Working test-first

Write a failing JUnit test, make it pass, then tidy. Tests extend `TestHarness`;
`src/test/resources` holds real save fixtures, and the serializer runs without
the browser (`Environment.initialise()` then
`new PacketDeserializer(file).deserialize()`). UI changes get a check in
`tools/ui-tests` as well. Then run the editor and make the change for real.

## Rules the save format enforces

Breaking one of these produces a save the game silently drops things from, or
cannot load at all. Most were learned the hard way.

1. **Never write to the user's save.** Edits go to a private working copy
   (`environment/WorkingSave`); Save writes a new file.
2. **Write the exact C# type back.** A `String` where the save had a `UUID`, or
   the wrong numeric type, corrupts the file.
3. **Follow references before mutating.** The format de-duplicates object
   graphs, so a property may be a reference to one elsewhere.
4. **Counts match contents**: the leading object count and every list length.
5. **Parallel structures stay in step**: `ItemList`, `SerializedItemList`
   (UUIDs) and the equipment slots.
6. **A new object is complete**: exact type strings, every component, a
   `Parent` link, and an `InstanceID.Guid` equal to its own `ObjectID`. Copy
   components from a template with `save/PacketMint`; never reuse the
   template's own `Property` objects.
7. **Mutations run on `Environment.mutationWorker`.** A new edit handler
   extends `handlers/SaveMutationHandler`, which does that.
8. **The page never replaces its save data wholesale** after an edit; replies
   go through `SavedGame.adopt`, which keeps the user's unsaved changes.
9. **Anything that changes or removes a file already in the saves folder
   takes a copy first** with `save/SaveBackups`, and stops if the copy fails.
   Delete, Rename and a Save that replaces a same-named file all do.
10. **Check what the game does on load before editing a field.** Several stored
   values are recomputed from something else every time a save loads (a
   companion's base attributes, every `<Skill>Bonus`), so editing them does
   nothing.

## Pull requests

Keep one change per pull request, with its tests. Say what you verified in the
running editor, and in the game if the change affects what the game loads.
