#!/usr/bin/env python3
"""Collects official line colours for Swiss local transport.

Sources:
- OpenStreetMap route relations with a `colour` tag (ODbL, © OpenStreetMap contributors),
- Traewelling/line-colors line-colors-CH.csv (CC0).

Also prints the `operator` names the timetable API uses at a few stations, so the app can
match colours to departures. Run in CI (needs internet): python3 tools/line_colors.py
"""
import csv, io, json, sys, urllib.parse, urllib.request

UA = {"User-Agent": "departures-widget line-colour probe"}

def get(url, data=None):
    req = urllib.request.Request(url, data=data, headers=UA)
    with urllib.request.urlopen(req, timeout=240) as r:
        return r.read().decode("utf-8")

def api(path, **params):
    return json.loads(get("https://transport.opendata.ch/v1/" + path + "?" + urllib.parse.urlencode(params)))

print("== API operator names ==")
for q in ["Genève, Cornavin", "Genève, Bel-Air", "Zürich, Paradeplatz", "Bern, Bahnhof", "Basel, Barfüsserplatz", "Lausanne, Flon"]:
    st = api("locations", query=q, type="station")["stations"]
    st = [s for s in st if s.get("id")]
    if not st:
        continue
    board = api("stationboard", id=st[0]["id"], limit=25)["stationboard"]
    seen = sorted({(j.get("operator"), j.get("category"), j.get("number")) for j in board}, key=str)
    print(st[0]["name"], "->", seen)

print("\n== OpenStreetMap ==")
query = """[out:json][timeout:200];
area["ISO3166-1"="CH"][admin_level=2]->.ch;
relation(area.ch)["type"="route"]["route"~"^(tram|bus|trolleybus|light_rail|subway|funicular)$"]["colour"];
out tags;"""
osm = json.loads(get("https://overpass-api.de/api/interpreter", data=urllib.parse.urlencode({"data": query}).encode()))
rows = []
for el in osm["elements"]:
    t = el.get("tags", {})
    rows.append({
        "source": "osm",
        "operator": t.get("operator", ""),
        "network": t.get("network", ""),
        "route": t.get("route", ""),
        "line": t.get("ref", ""),
        "colour": t.get("colour", ""),
        "text_colour": t.get("text_colour", "") or t.get("colour:text", ""),
    })
print("relations with colour:", len(rows))
ops = {}
for r in rows:
    ops[r["operator"]] = ops.get(r["operator"], 0) + 1
for op, n in sorted(ops.items(), key=lambda x: -x[1])[:60]:
    print(f"{n:5d}  {op}")

print("\n== Geneva (TPG / unireso) from OSM ==")
gva = sorted({(r["line"], r["route"], r["colour"], r["text_colour"], r["operator"], r["network"]) for r in rows
              if "genev" in (r["operator"] + r["network"]).lower() or "unireso" in r["network"].lower() or r["operator"].lower().startswith("tpg")},
             key=lambda x: (len(x[0]), x[0]))
for g in gva:
    print(",".join(g))

print("\n== Traewelling CH ==")
tw = list(csv.DictReader(io.StringIO(get("https://raw.githubusercontent.com/Traewelling/line-colors/main/line-colors-CH.csv"))))
print("rows:", len(tw))

with open("line-colors-probe.csv", "w", newline="") as f:
    w = csv.DictWriter(f, fieldnames=["source", "operator", "network", "route", "line", "colour", "text_colour"])
    w.writeheader()
    w.writerows(rows)
print("wrote line-colors-probe.csv")
