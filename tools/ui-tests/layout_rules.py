# Layout promises, measured on the running editor at the user's window size.
#
# Reported: the party packs stacked one to a row, and the grimoire's sort
# buttons stranded at the far edge from the spell search. The sweep that
# followed found the same two faults elsewhere -- things that belong together
# drawn far apart, and panel furniture in a different place on every tab.
import json, os, sys, time
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from harness import Page, check, summary, open_save, to_list, boot

SAVE = "0945952c89c640e4a18cdb293e3946b4 32111932 CaedNua.savegame"
page = Page()
boot(page)
to_list(page)
open_save(page, SAVE)
time.sleep(1.5)
E = page.eval

RECT = r"""(function(sel){ var el = $(sel).filter(':visible')[0]; if (!el) return null;
  var b = el.getBoundingClientRect();
  return {l: Math.round(b.left), t: Math.round(b.top), r: Math.round(b.right), b: Math.round(b.bottom),
          w: Math.round(b.width), h: Math.round(b.height)}; })(%s)"""


def rect(sel):
    return E(RECT % json.dumps(sel))


def view(name, wait=3.0):
    E("Eternity.SavedGame.switchView(Eternity.SavedGame.views.%s)" % name)
    time.sleep(wait)
    E("window.scrollTo(0, 0)")


def beside(control, anchor, most=24):
    """control starts within `most` px to the right of anchor, centred on it."""
    c, a = rect(control), rect(anchor)
    if not c or not a:
        return False, "missing %s / %s" % (control, anchor)
    dx = c["l"] - a["r"]
    dy = (c["t"] + c["b"]) / 2.0 - (a["t"] + a["b"]) / 2.0
    return 0 <= dx <= most and abs(dy) <= 8, "dx %s, dy %.0f" % (dx, dy)


# ---- inventory: party packs three to a row ----------------------------------
view("INVENTORY", 3.5)
rows = E("""$('#invPacks .inv-pack-row:visible').toArray().map(function(e){
  var b = e.getBoundingClientRect(); return [Math.round(b.left), Math.round(b.top), Math.round(b.right)]; })""")
panel = rect("#invPacks")
first = [r for r in rows if r[1] == rows[0][1]]
check("party packs: three to a row", len(first) == 3, "%d of %d on the first row" % (len(first), len(rows)))
check("party packs: every card inside the panel", all(r[2] <= panel["r"] + 1 for r in rows),
      "panel right %s, card right %s" % (panel["r"], max(r[2] for r in rows)))
check("party packs: no pack grid spills out of its card", E("""$('#invPacks .inv-grid').filter(function(){
  var g = this.getBoundingClientRect(), c = $(this).closest('.inv-pack-row')[0].getBoundingClientRect();
  return g.right > c.right + 1 || g.left < c.left - 1; }).length""") == 0)
widths = sorted(set(r[2] - r[0] for r in rows))
check("party packs: the cards line up as columns", len(widths) == 1, widths)

# ---- inventory: the paper doll as the game draws it -------------------------
# Read off the game's own inventory screen (v3.9.5, 2026-09-21): Head, Chest,
# the right-hand ring, Feet and Pet down the left of the doll; Neck, Hands, the
# left-hand ring, Waist and Grimoire down the right. The doll faces you, so the
# character's right hand is on your left. Quick items sit three to a row and
# weapon sets two by two, I and II over III and IV.
slots = E("""$('#inventoryView .inv-equip-slot:visible').toArray().map(function(e){
  var b = e.getBoundingClientRect();
  return {slot: e.getAttribute('data-slot'), l: Math.round(b.left), r: Math.round(b.right),
          t: Math.round(b.top), b: Math.round(b.bottom)}; })""")
portrait = rect("#invPortrait")
left = sorted([s for s in slots if portrait and s["r"] <= portrait["l"]], key=lambda s: s["t"])
right = sorted([s for s in slots if portrait and s["l"] >= portrait["r"]], key=lambda s: s["t"])
check("doll: left of the portrait, top to bottom, as in the game",
      [s["slot"] for s in left] == ["Head", "Chest", "RightRing", "Feet", "Pet"], [s["slot"] for s in left])
check("doll: right of the portrait, top to bottom, as in the game",
      [s["slot"] for s in right] == ["Neck", "Hands", "LeftRing", "Waist", "Grimoire"], [s["slot"] for s in right])
check("doll: no slot anywhere else", len(slots) == 10 and len(left) + len(right) == 10, len(slots))
check("doll: each side is one straight column",
      len({s["l"] for s in left}) == 1 and len({s["l"] for s in right}) == 1,
      [sorted({s["l"] for s in left}), sorted({s["l"] for s in right})])
if left and right and portrait:
    check("doll: the columns sit against the portrait",
          portrait["l"] - max(s["r"] for s in left) <= 16 and min(s["l"] for s in right) - portrait["r"] <= 16,
          "gaps %s / %s" % (portrait["l"] - max(s["r"] for s in left), min(s["l"] for s in right) - portrait["r"]))
    check("doll: the columns run the portrait's height",
          left[0]["t"] - portrait["t"] <= 12 and portrait["b"] - left[-1]["b"] <= 24,
          "top %s, bottom %s" % (left[0]["t"] - portrait["t"], portrait["b"] - left[-1]["b"]))

quick = E("""$('#invQuickSlots .inv-tile:visible').toArray().map(function(e){
  return Math.round(e.getBoundingClientRect().top); })""")
check("quick items: three to a row, as in the game", quick and quick.count(quick[0]) == 3, quick)
sets = E("""$('#invWeaponSets .inv-weapon-set:visible').toArray().map(function(e){
  var b = e.getBoundingClientRect(); return [Math.round(b.left), Math.round(b.top)]; })""")
check("weapon sets: I and II side by side, III and IV under them",
      len(sets) == 4 and sets[0][1] == sets[1][1] and sets[2][1] == sets[3][1]
      and sets[2][1] > sets[0][1] and sets[0][0] == sets[2][0] and sets[1][0] > sets[0][0], sets)
doll_panel = rect(".inv-doll-panel")
check("doll: nothing spills out of its panel", E("""$('.inv-doll-panel .inv-tile:visible').filter(function(){
  var t = this.getBoundingClientRect(), p = $('.inv-doll-panel')[0].getBoundingClientRect();
  return t.right > p.right + 1 || t.left < p.left - 1; }).length""") == 0, doll_panel)

# ---- grimoire: sort beside the spell search ---------------------------------
view("GRIMOIRE", 3.5)
ok, detail = beside("#grmSort", "#grmSearch")
check("grimoire: sort buttons beside the spell search", ok, detail)

# ---- abilities: sort beside the rest of the search toolbar -----------------
view("ABILITIES", 3.5)
ok, detail = beside("#ablBrowseSort", ".abl-search .abl-any-class", 32)
check("abilities: sort buttons in the search toolbar", ok, detail)

E("$('#ablBrowseSearch').val('').trigger('keyup')")
page.wait_for("$('#ablBrowseGrid .abl-row-add').length > 0", 60, "ability rows")
reach = E("""(function(){ var row = $('#ablBrowseGrid .abl-row-add:visible').first();
  return Math.round(row.find('.abl-add')[0].getBoundingClientRect().left
    - row.find('.abl-text')[0].getBoundingClientRect().left); })()""")
check("abilities: a row's + button within 520px of its name", reach <= 520, "%spx" % reach)
wraps = E("""(function(){ var s = $('.abl-search')[0].getBoundingClientRect(), i = $('#ablBrowseSearch')[0].getBoundingClientRect();
  return Math.round(s.height - i.height); })()""")
check("abilities: the search toolbar is still one row", wraps <= 12, "%spx taller than the search box" % wraps)

# ---- raw and globals: a value sits near its name ----------------------------
for name, table in (("RAW", "#rawTable"), ("GLOBALS", "#globalsTable")):
    view(name, 2.5)
    key = rect("%s tbody tr:visible:not(.raw-section-header) td:first-child" % table)
    check("%s: a value is within 480px of its name" % name.lower(), key and key["w"] <= 480,
          key and "name column %spx" % key["w"])

# ---- attributes: an input sits near its label -------------------------------
view("ATTR", 2.0)
gap = E("""(function(){ var row = $('.attributes .form-group:visible').first();
  var l = row.find('label')[0].getBoundingClientRect(), i = row.find('input')[0].getBoundingClientRect();
  return Math.round(i.left - l.left); })()""")
check("attributes: an input within 200px of where its label starts", gap <= 200, "%spx" % gap)

# ---- stronghold: an upgrade's cost and button stay near its words ----------
view("STRONGHOLD", 2.5)
row = rect("#shUpgrades .sh-upgrade")
check("stronghold: an upgrade row is no wider than 1200px", row and row["w"] <= 1200,
      row and "%spx" % row["w"])

# A hireling's Dismiss button sits on the same line as their name, inside the
# rail -- a float placed after the name would drop to a line of its own.
people = E("""$('#shHirelings .sh-person').toArray().map(function(e){
  var n = $(e).find('.sh-person-name')[0].getBoundingClientRect(),
      b = $(e).find('.sh-person-btn')[0].getBoundingClientRect(),
      p = $(e).closest('.sh-people-panel')[0].getBoundingClientRect();
  return {dy: Math.round(Math.abs((n.top + n.bottom) - (b.top + b.bottom)) / 2),
          inside: b.right <= p.right + 1 && n.left >= p.left - 1, gap: Math.round(b.left - n.right)}; })""")
check("stronghold: each hireling's button on their name's line",
      people and all(p["dy"] <= 3 for p in people), people)
check("stronghold: the hireling rows stay inside the rail",
      people and all(p["inside"] and p["gap"] >= 0 for p in people), people)

# ---- every tab: the panel bar in the same place ----------------------------
bars = {}
for name in ("ATTR", "RAW", "GLOBALS", "INVENTORY", "ABILITIES", "STRONGHOLD", "GRIMOIRE", "CONSOLE"):
    view(name, 2.2)
    bar, actions = rect(".panel-bar"), rect(".panel-bar .panel-bar-actions")
    bars[name] = (bar["l"], bar["t"], actions["r"])
print("   panel bars (left, top, actions right): %s" % bars)
for index, label in ((0, "left edge"), (1, "top"), (2, "Revert/Apply right edge")):
    values = sorted(set(v[index] for v in bars.values()))
    check("every tab: panel bar %s in one place" % label, values[-1] - values[0] <= 2, values)

sys.exit(summary(page))
