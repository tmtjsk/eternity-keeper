# One-time extraction of the deity and paladin-order data from the user's own
# Pillars of Eternity install, in the same spirit as tools/stronghold-extract.
#
# Almost everything the identity panel needs is a static table compiled into
# Assembly-CSharp -- RaceAbilityAdjustment, CultureAbilityAdjustment,
# ClassSkillAdjustment and BackgroundSkillAdjustment are all fields on
# CharacterStats, so IdentityCatalog carries them directly. The one exception
# is what a priest's deity or a paladin's order actually does: Religion holds
# DeityInfo and PaladinOrderInfo as inspector-assigned arrays on the
# InGameGlobal prefab, the same object the stronghold hangs off, so the
# dispositions each one favours exist only in a Unity asset.
#
# Religion.GetCurrentBonusMultiplier() reads them for the player character
# alone, adding PositiveTraitBonus[rank-1] for each favoured disposition and
# NegativeTraitBonus[rank-1] for each disfavoured one, ranks capped at 3. That
# ladder is extracted too rather than assumed.
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

# Disposition.Axis, in enum order.
AXES = ["Benevolent", "Cruel", "Clever", "Stoic", "Aggressive", "Diplomatic",
        "Passionate", "Rational", "Honest", "Deceptive"]

# Religion.Deity and Religion.PaladinOrder, in enum order. The save stores
# these by ordinal like every other enum, so the position is the identity.
DEITIES = ["None", "Berath", "Eothas", "Magran", "Skaen", "Wael"]
ORDERS = ["None", "BleakWalkers", "DarcozziPaladini", "GoldpactKnights",
          "KindWayfarers", "ShieldbearersOfStElcga", "FrermasMesCancSuolias"]

# DatabaseString.StringTableType -> stringtable file name. A deity's name is a
# CharacterDatabaseString left on Unassigned, which resolves through the
# character database; an order's is a FactionDatabaseString on Factions.
TABLES = {0: "characters", 1: "gui", 4: "characters", 14: "factions"}

ENTRY = re.compile(r"<ID>(\d+)</ID>\s*<DefaultText>(.*?)</DefaultText>", re.S)
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
            with io.open(path, encoding="utf-8-sig") as handle:
                for sid, text in ENTRY.findall(handle.read()):
                    for a, b in UNESCAPE:
                        text = text.replace(a, b)
                    result[int(sid)] = text.strip()

    _tables[idx] = result
    return result


def resolve(dbstring, fallback):
    if not isinstance(dbstring, dict):
        return fallback

    sid = dbstring.get("StringID", -1)
    if sid is None or sid < 0:
        return fallback

    return table(dbstring.get("StringTable", 0)).get(sid, fallback)


def find_religion(env, generator):
    """The Religion behaviour, matched on the class its m_Script points at."""
    scripts = set()
    for obj in env.objects:
        if obj.type.name != "MonoScript":
            continue
        try:
            if obj.read().m_ClassName == "Religion":
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

        if (tree.get("m_Script") or {}).get("m_PathID") in scripts:
            return tree

    return None


def axes(indices):
    return [AXES[i] for i in (indices or []) if 0 <= i < len(AXES)]


def entries(rows, names, key, spaced):
    """One block of {constant: {name, positive, negative}}."""
    result = {}
    for row in rows or []:
        index = row.get(key, 0)
        if not isinstance(index, int) or not 0 < index < len(names):
            continue

        constant = names[index]
        result[constant] = {
            "name": resolve(row.get("DisplayName"), spaced(constant)),
            "positive": axes(row.get("PositiveTrait")),
            "negative": axes(row.get("NegativeTrait")),
        }

    return result


def spaced(constant):
    return re.sub(r"(?<=[a-z])(?=[A-Z])", " ", constant)


def main():
    if not os.path.isfile(BUNDLE):
        sys.exit("no ingameglobal bundle at %s" % BUNDLE)

    env = UnityPy.load(BUNDLE)
    version = next((a.unity_version for a in env.assets
                    if getattr(a, "unity_version", None)), "5.4.0f3")

    generator = TypeTreeGenerator(version)
    generator.load_local_game(ROOT)

    tree = find_religion(env, generator)
    if tree is None:
        sys.exit("no Religion behaviour in the bundle")

    catalog = {
        "deities": entries(tree.get("DeityInfo"), DEITIES, "DeityName", spaced),
        "orders": entries(tree.get("PaladinOrderInfo"), ORDERS, "OrderName",
                          spaced),
        # Religion.GetBonus() indexes these by disposition rank - 1, and
        # MAX_BONUS_LEVEL clamps the rank at 3.
        "dispositionBonus": {
            "positive": [round(v, 4) for v in tree.get("PositiveTraitBonus") or []],
            "negative": [round(v, 4) for v in tree.get("NegativeTraitBonus") or []],
        },
    }

    os.makedirs(OUT, exist_ok=True)
    path = os.path.join(OUT, "identity.json")
    with io.open(path, "w", encoding="utf-8") as handle:
        handle.write(json.dumps(catalog, indent=1, sort_keys=True,
                                ensure_ascii=False))

    print("%d deities, %d orders -> %s"
          % (len(catalog["deities"]), len(catalog["orders"]), path))


if __name__ == "__main__":
    main()
