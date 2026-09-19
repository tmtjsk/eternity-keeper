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
