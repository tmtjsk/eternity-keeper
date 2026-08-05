# Item and ability catalog extraction

Regenerates `D:\PillarsEditor\itemdata` — the real names, icons and rules the
editor's Inventory and Abilities tabs show:

| File | What it holds |
|---|---|
| `catalog.json` | ~2,150 items: display name, icon, prefab path, max stack size, the `Equippable` per-slot flags, class restrictions, the game's own item filter category, quality tier (soulbound / unique / fine) used for tinting |
| `abilities.json` | ~1,440 abilities, spells and talents: display name, description, icon, the exact component class the save must record, `EffectType`, acquisition/spell level, and for talents what they grant or modify |
| `progression.json` | All 26 `AbilityProgressionTable`s — which class, subrace or companion may take what, and from which level |
| `icons/` | The PNGs both catalogs reference |

The data is read out of the user's own Pillars of Eternity install, the same policy the
editor already follows for portraits, and lands OUTSIDE the git repo. The editor works
without it and simply falls back to prettified prefab file names.

Why offline: all of this lives inside Unity asset bundles, which Java 8 can't
realistically parse. This script does it once with UnityPy.

    pip install UnityPy
    python extract_catalog.py

Takes a few minutes (4604 bundles). Expected output:

    DONE: 2156 items, 976 abilitys, 273 spells, 191 talents, 1387 icons, 0 bundles unreadable

`ItemCatalog` and `AbilityCatalog` locate the result via the `itemDataLocation`
setting, else `<cwd>/itemdata`, else `<cwd>/../itemdata`.

## Two things worth knowing before editing this

**A bundle ships its subject's dependencies too**, including other real items —
a belt that summons a weapon ships the weapon's prefab. The component that
describes the thing the bundle is *named* after is the one to read; picking the
first match once labelled a belt "Firebrand".

**Items and abilities look alike.** A potion carries a `GenericAbility`
component describing its effect, so the ability scan would happily claim it;
anything with an inventory icon (`IconTexture`) is an item and is skipped.
