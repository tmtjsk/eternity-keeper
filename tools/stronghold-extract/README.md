# Stronghold catalog extraction

Adds `stronghold.json` and `stronghold-icons/` to `D:\PillarsEditor\itemdata` —
the numbers and artwork the editor's Stronghold tab shows for Caed Nua.

| File | What it holds |
|---|---|
| `stronghold.json` | All 25 buildable upgrades: display name and description, cost and build time, the Prestige and Security they are worth, which upgrade has to come first, the global variable they drive, whether they unlock a resting boon — plus the 28 hirelings and `MaxHirelings` |
| `stronghold-icons/` | The 25 upgrade icons the game's own list uses |

Same policy as `itemdata-extract`: read out of the user's own game install, and
written OUTSIDE the git repo. Without it the Stronghold tab still reports what a
save holds, but has no upgrade list to offer.

    pip install UnityPy TypeTreeGeneratorAPI
    python extract_stronghold.py

Expected output:

    wrote D:\PillarsEditor\itemdata\stronghold.json
      25 upgrades, 28 hirelings, 25 icons

## Why this is more awkward than the item catalog

**The data is not in the save, and it is not optional.**
`Stronghold.CompleteBuildingUpgrade()` adds an upgrade's `PrestigeAdjustment`
and `SecurityAdjustment` exactly once — at the moment of building — and
`DestroyUpgrade()` takes them back off. Nothing ever recomputes either number
from `m_upgradesBuilt` on load. A save records only which enum values are in
that list, so an editor that does not know what each one was worth cannot add
or remove one without leaving the save in a state the game could never have
reached.

**It needs a typetree generated from the assembly.** The `Stronghold`
MonoBehaviour hangs off the `InGameGlobal` prefab, in its own object bundle, and
its MonoBehaviours are stored by script reference alone — UnityPy cannot shape
them without reading `Assembly-CSharp.dll`, which is what `TypeTreeGeneratorAPI`
is for. (`load_local_game` wants the game's *root* directory, not `Managed`.)

## Three things worth knowing before editing this

**An unrelated Faction asset is also called "Stronghold".** Matching on an
object's own `m_Name` finds that instead. The behaviour has to be matched on the
class its `m_Script` points at.

**`StrongholdUpgrade.Type` has 28 values but only 25 are configured.**
`BeastVault`, `RoadRepairs` and `AdditionalStorage` are cut content with no data
behind them, and the game could never have built one.

**`Attribute`/`AttributeAdjustment` and `Skill`/`SkillAdjustment` on
`StrongholdUpgrade` are dead**, and they are wrong as well as unused — Towers is
set to "+1 Might" there while actually granting a Perception boon. Nothing in
the game reads them; the resting bonus really comes from the `Boon` affliction.
The upgrade's own `Description` states it in the game's own words, so that is
what gets carried and those four fields are deliberately dropped.
