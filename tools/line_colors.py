#!/usr/bin/env python3
"""Builds core/src/main/resources/line_colors.csv: official line colours for local transport.

Sources:
- OpenStreetMap route relations with a `colour` tag, Geneva area (TPG).
  Data (c) OpenStreetMap contributors, ODbL 1.0, https://www.openstreetmap.org/copyright
- Traewelling/line-colors, line-colors-CH.csv (CC0): Zurich (VBZ) and Basel (BVB, BLT).

Output columns: operator,line,background,text. `operator` is the operator code the timetable
API (transport.opendata.ch) reports for a departure, e.g. "TPG" or "VBZ".

Usage (needs internet, so it normally runs in the "Line colours" workflow):
    python3 tools/line_colors.py [--osm-cache osm.json] [--tw-cache line-colors-CH.csv]
Also prints the operator codes the API uses at a few stations, to check the mapping.
"""
import argparse, csv, io, json, sys, time, urllib.error, urllib.parse, urllib.request

UA = {"User-Agent": "departures-widget line-colour collector"}
OUT = "core/src/main/resources/line_colors.csv"

# Traewelling operator -> (API operator code, GTFS agency names to keep)
TRAEWELLING = {
    "zvv-vbz": ("VBZ", {"Verkehrsbetriebe Zürich", "Verkehrsbetriebe Zürich INFO+"}),
    "tnw-bvb": ("BVB", {"Basler Verkehrsbetriebe"}),
    "tnw-blt": ("BLT", {"Baselland Transport"}),
}

OVERPASS_QUERY = """[out:json][timeout:120];
relation(46.10,5.90,46.40,6.35)["type"="route"]["route"~"^(tram|bus|trolleybus|light_rail)$"]["colour"];
out tags;"""
OVERPASS_MIRRORS = [
    "https://overpass-api.de/api/interpreter",
    "https://overpass.kumi.systems/api/interpreter",
    "https://overpass.private.coffee/api/interpreter",
]


def get(url, data=None):
    req = urllib.request.Request(url, data=data, headers=UA)
    with urllib.request.urlopen(req, timeout=240) as r:
        return r.read().decode("utf-8")


def overpass():
    for _ in range(3):
        for url in OVERPASS_MIRRORS:
            try:
                return json.loads(get(url, data=urllib.parse.urlencode({"data": OVERPASS_QUERY}).encode()))
            except (urllib.error.URLError, TimeoutError, json.JSONDecodeError) as e:
                print("failed", url, e, file=sys.stderr)
        time.sleep(20)
    sys.exit("all Overpass servers failed")


def norm(hex_colour):
    h = hex_colour.strip().lower()
    if not h.startswith("#"):
        h = "#" + h
    if len(h) != 7 or any(c not in "0123456789abcdef" for c in h[1:]):
        raise ValueError(hex_colour)
    return h


def text_for(background):
    """Black or white, whichever reads better on the background (WCAG relative luminance)."""
    def channel(v):
        v /= 255
        return v / 12.92 if v <= 0.03928 else ((v + 0.055) / 1.055) ** 2.4
    r, g, b = (int(background[i:i + 2], 16) for i in (1, 3, 5))
    lum = 0.2126 * channel(r) + 0.7152 * channel(g) + 0.0722 * channel(b)
    return "#000000" if lum > 0.179 else "#ffffff"


def tpg_rows(osm):
    colours = {}
    for el in osm["elements"]:
        t = el.get("tags", {})
        if not t.get("operator", "").startswith("TPG") or not t.get("ref"):
            continue
        try:
            colours.setdefault(t["ref"].strip(), set()).add(norm(t["colour"]))
        except ValueError:
            pass
    rows = []
    for line, cs in colours.items():
        if len(cs) != 1:
            print(f"skipping TPG {line}: conflicting colours {sorted(cs)}", file=sys.stderr)
            continue
        bg = cs.pop()
        rows.append(("TPG", line, bg, text_for(bg)))
    return rows


def traewelling_rows(text):
    rows = []
    for r in csv.DictReader(io.StringIO(text)):
        target = TRAEWELLING.get(r["shortOperatorName"])
        if not target or r["GTFSAgencyName"] not in target[1]:
            continue
        bg = norm(r["backgroundColor"])
        fg = norm(r["textColor"]) if r["textColor"].strip() else text_for(bg)
        rows.append((target[0], r["lineName"].strip(), bg, fg))
    return rows


def print_api_operators():
    def api(path, **params):
        return json.loads(get("https://transport.opendata.ch/v1/" + path + "?" + urllib.parse.urlencode(params)))
    for q in ["Genève, Cornavin", "Zürich, Paradeplatz", "Basel, Barfüsserplatz"]:
        st = [s for s in api("locations", query=q, type="station")["stations"] if s.get("id")]
        if st:
            board = api("stationboard", id=st[0]["id"], limit=20)["stationboard"]
            print(st[0]["name"], "->", sorted({j.get("operator") for j in board}, key=str))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--osm-cache")
    ap.add_argument("--tw-cache")
    args = ap.parse_args()

    osm = json.load(open(args.osm_cache)) if args.osm_cache else overpass()
    tw = open(args.tw_cache).read() if args.tw_cache else get(
        "https://raw.githubusercontent.com/Traewelling/line-colors/main/line-colors-CH.csv")
    if not args.osm_cache:
        print_api_operators()

    rows = {}
    for op, line, bg, fg in tpg_rows(osm) + traewelling_rows(tw):
        rows[(op, line)] = (bg, fg)
    ordered = sorted(rows.items(), key=lambda kv: (kv[0][0], len(kv[0][1]), kv[0][1]))
    with open(OUT, "w", newline="") as f:
        f.write("# Official line colours. Generated by tools/line_colors.py; do not edit by hand.\n")
        f.write("# TPG: (c) OpenStreetMap contributors, ODbL. VBZ, BVB, BLT: Traewelling/line-colors, CC0.\n")
        w = csv.writer(f, lineterminator="\n")
        w.writerow(["operator", "line", "background", "text"])
        for (op, line), (bg, fg) in ordered:
            w.writerow([op, line, bg, fg])
    print(f"wrote {OUT}: {len(ordered)} lines")
    with open(OUT) as f:
        sys.stdout.write(f.read())


if __name__ == "__main__":
    main()
