# One-time stronghold catalog extraction from the user's own Pillars of Eternity
# install, in the same spirit as tools/itemdata-extract: the editor needs the
# game's own numbers for every upgrade, and they live in a Unity asset rather
# than in the save or the assembly.
#
# The Stronghold MonoBehaviour hangs off the InGameGlobal prefab -- the same
# object a save carries the Stronghold component on -- and its Upgrades array
# holds cost, build time, prestige and security adjustments, the prerequisite,
# the display strings and the icon for each StrongholdUpgrade.Type. That matters
# because Stronghold.CompleteBuildingUpgrade() applies those adjustments once,
# at the moment of building, and nothing recomputes them on load: an editor that
# adds an upgrade without them leaves a save the game could never have produced.
#
# InGameGlobal lives in its own object bundle and its MonoBehaviours are stored
# by script reference alone, so shaping them needs a typetree generated from
# Assembly-CSharp.dll (pip install TypeTreeGeneratorAPI).
#
# Output lands OUTSIDE the git repo, next to the item catalog, because it is
# derived from the user's own game install.
import io
import json
import os
import re
import sys

import UnityPy
from UnityPy.helpers.TypeTreeGenerator import TypeTreeGenerator

ROOT = r"D:\Steam\steamapps\common\Pillars of Eternity"
GAME = os.path.join(ROOT, "PillarsOfEternity_Data")
BUNDLE = os.path.join(GAME, "assetbundles", "prefabs", "objectbundle",
                      "ingameglobal.unity3d")
TEXT = os.path.join(GAME, "data", "localized", "en", "text", "game")
OUT = r"D:\PillarsEditor\itemdata"
ICONS = os.path.join(OUT, "stronghold-icons")

# StrongholdUpgrade.Type, in enum order. The save stores these by ordinal, so
# the ordinal is the identity and the name is only a label -- note the game's
# own misspelling of AritficersHall, kept here so the two agree.
UPGRADE_TYPES = [
    "Barbican", "WestCurtainWall", "SouthCurtainWall", "Bailey", "MainKeep",
    "Barracks", "Dungeons", "BeastVault", "Towers", "Library", "CraftHall",
    "AritficersHall", "Forum", "MerchantStalls", "CurioShop", "TrainingGrounds",
    "Chapel", "HedgeMaze", "BotanicalGarden", "RoadRepairs", "WardensLodge",
    "WoodlandTrails", "Bedding", "AdditionalStorage", "Hearth", "Lab",
    "CourtyardPool", "EasternBarbican", "Count", "None",
]

# StrongholdUpgrade.InteriorLocation, in enum order.
INTERIORS = ["None", "House_Lower", "House_Upper", "Hall", "Dungeon", "Library",
             "Barracks"]

# DatabaseString.StringTableType -> stringtable file name (decompiled enum).
TABLES = {1: "gui", 4: "characters", 5: "items", 6: "abilities", 938: "stronghold"}

ENTRY = re.compile(
    r"<ID>(\d+)</ID>\s*<DefaultText>(.*?)</DefaultText>", re.S)
UNESCAPE = [("&amp;", "&"), ("&lt;", "<"), ("&gt;", ">"),
            ("&quot;", '"'), ("&apos;", "'")]

_tables = {}


def table(idx):
    """Lazily parse a stringtable into {id: text}."""
    if idx in _tables:
        return _tables[idx]

    name = TABLES.get(idx)
    result = {}
    if name:
        path = os.path.join(TEXT, name + ".stringtable")
        if os.path.exists(path):
            with open(path, encoding="utf-8-sig") as handle:
                for sid, text in ENTRY.findall(handle.read()):
                    for a, b in UNESCAPE:
                        text = text.replace(a, b)
                    result[int(sid)] = text.strip()

    _tables[idx] = result
    return result


def resolve(dbstring):
    if not isinstance(dbstring, dict):
        return ""

    sid = dbstring.get("StringID", -1)
    if sid is None or sid < 0:
        return ""

    return table(dbstring.get("StringTable", 0)).get(sid, "")


def name_of(types, index):
    return types[index] if isinstance(index, int) and 0 <= index < len(types) else ""


def save_icon(by_id, pointer, seen):
    """Export a PPtr<Texture2D> as a PNG and return its file name."""
    path_id = (pointer or {}).get("m_PathID", 0)
    if not path_id or path_id not in by_id:
        return ""

    try:
        texture = by_id[path_id].read()
        icon_file = re.sub(r"[^A-Za-z0-9_.-]", "_", texture.m_Name) + ".png"
        if icon_file not in seen:
            texture.image.save(os.path.join(ICONS, icon_file))
            seen.add(icon_file)
        return icon_file
    except Exception:
        return ""


def find_stronghold(env, generator):
    """The Stronghold behaviour, matched on the class its m_Script points at.

    An unrelated Faction asset is also named "Stronghold", so matching on the
    object's own name finds the wrong thing.
    """
    scripts = set()
    for obj in env.objects:
        if obj.type.name != "MonoScript":
            continue
        try:
            if obj.read().m_ClassName == "Stronghold":
                scripts.add(obj.path_id)
        except Exception:
            continue

    env.typetree_generator = generator

    for obj in env.objects:
        if obj.type.name != "MonoBehaviour":
            continue
        try:
            tree = obj.read_typetree()
        except Exception:
            continue

        if (tree.get("m_Script") or {}).get("m_PathID") in scripts \
                and isinstance(tree.get("Upgrades"), list):
            return tree

    return None


def hireling_entry(raw, by_id, guest):
    entry = {
        "costPerDay": raw.get("CostPerDay", 0),
        "prestige": raw.get("PrestigeAdjustment", 0),
        "security": raw.get("SecurityAdjustment", 0),
        "leavesAfterFullPayCycle": 1 if raw.get("LeavesAfterFullPayCycle") else 0,
        "hiredGlobal": raw.get("HiredGlobalVariableName", "") or "",
        "canHireGlobal": raw.get("CanHireGlobalVariableName", "") or "",
        "guest": 1 if guest else 0,
    }

    if guest:
        entry["minimumPrestige"] = raw.get("MinimumPrestige", 0)

    # The hireling's display name lives on the CharacterStats prefab it points
    # at, which the save also records by name.
    prefab = (raw.get("HirelingPrefab") or {}).get("m_PathID", 0)
    stats = by_id.get(prefab)
    if stats is not None:
        try:
            entry["prefab"] = stats.read().m_Name or ""
        except Exception:
            pass

    return entry


def main():
    if not os.path.isfile(BUNDLE):
        sys.exit("no ingameglobal bundle at %s" % BUNDLE)

    os.makedirs(ICONS, exist_ok=True)
    env = UnityPy.load(BUNDLE)

    version = next((a.unity_version for a in env.assets
                    if getattr(a, "unity_version", None)), "5.4.0f3")

    generator = TypeTreeGenerator(version)
    generator.load_local_game(ROOT)

    tree = find_stronghold(env, generator)
    if tree is None:
        sys.exit("no Stronghold behaviour with an Upgrades array in the bundle")

    by_id = {obj.path_id: obj for obj in env.objects}
    seen = set()
    upgrades = {}

    for raw in tree["Upgrades"]:
        key = name_of(UPGRADE_TYPES, raw.get("UpgradeType", -1))
        if not key:
            continue

        entry = {
            "ordinal": raw["UpgradeType"],
            "name": resolve(raw.get("Name")) or key,
            "description": resolve(raw.get("Description")),
            "cost": raw.get("Cost", 0),
            "days": raw.get("TimeToBuild", 0),
            "prestige": raw.get("PrestigeAdjustment", 0),
            "security": raw.get("SecurityAdjustment", 0),
            "order": raw.get("UiOrderNumber", 0),
            "destructible": 1 if raw.get("Destructible") else 0,
        }

        prerequisite = name_of(UPGRADE_TYPES, raw.get("Prerequisite", -1))
        if prerequisite and prerequisite != "None":
            entry["prerequisite"] = prerequisite

        # Set to 1 when the upgrade is built and back to 0 when destroyed, so
        # the editor has to keep it in step.
        global_name = raw.get("UpgradeCompletedGlobalVariableName") or ""
        if global_name:
            entry["global"] = global_name

        interior = name_of(INTERIORS, raw.get("InteriorUpgradeLocation", 0))
        if interior and interior != "None":
            entry["interior"] = interior

        # StrongholdUpgrade also carries Attribute/AttributeAdjustment and
        # Skill/SkillAdjustment, and they are DEAD -- nothing in the game reads
        # them, and their values disagree with what the upgrade actually does
        # (Towers is set to "+1 Might" there while granting a perception boon).
        # The resting bonus really comes from the Boon affliction, and the
        # upgrade's own Description states it in the game's own words, so that
        # is what gets carried and those four fields are deliberately dropped.
        if raw.get("Boon", {}).get("m_PathID"):
            entry["hasBoon"] = 1

        icon = save_icon(by_id, raw.get("Icon"), seen)
        if icon:
            entry["icon"] = icon

        upgrades[key] = entry

    hirelings = {}
    for raw in tree.get("StandardHirelings") or []:
        entry = hireling_entry(raw, by_id, guest=False)
        hirelings[entry.get("prefab") or entry["hiredGlobal"]] = entry

    for raw in tree.get("GuestHirelings") or []:
        entry = hireling_entry(raw, by_id, guest=True)
        hirelings[entry.get("prefab") or entry["hiredGlobal"]] = entry

    catalog = {
        "upgrades": upgrades,
        "hirelings": hirelings,
        "maxHirelings": tree.get("MaxHirelings", 8),
        "collectTaxesTurnCount": tree.get("CollectTaxesTurnCount", 5),
        "payHirelingsDayCount": tree.get("PayHirelingsDayCount", 5),
    }

    out = os.path.join(OUT, "stronghold.json")
    io.open(out, "w", encoding="utf-8").write(
        json.dumps(catalog, indent=1, sort_keys=True, ensure_ascii=False))

    print("wrote %s" % out)
    print("  %d upgrades, %d hirelings, %d icons"
          % (len(upgrades), len(hirelings), len(seen)))

    # Only a genuinely empty string is a problem: plenty of upgrades are simply
    # called the same thing as their enum value ("Bailey", "Towers").
    missing = [k for k, v in upgrades.items() if not v["name"]]
    if missing:
        print("  upgrades with no display name: %s" % ", ".join(sorted(missing)))


if __name__ == "__main__":
    main()
