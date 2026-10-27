#!/usr/bin/env python3
"""
Construit app/src/main/assets/gt7db/track_corners.json : virages relevés (numéro, nom éventuel, apex X/Z) des circuits
de jbhoorasingh/gt7-datalogger-track-data (bundles `tracks/*.json`, CC0), rapprochés des identifiants gt7info.

Usage : python3 -I build_corners.py <dossier du dépôt track-data> <course.csv de gt7info> <sortie.json>

Couverture réelle vérifiée : seuls les bundles qui contiennent des virages sont repris. La numérotation est celle de la
source communautaire ; quand son nombre de virages diffère du catalogue officiel, c'est noté (`count_matches_official`
faux) et l'application la présente comme « numérotation communautaire, à vérifier ».
"""
import csv, glob, json, os, re, sys, unicodedata

src, course_csv, out = sys.argv[1], sys.argv[2], sys.argv[3]
index = {c["slug"]: c for c in json.load(open(f"{src}/index.json", encoding="utf-8"))["configurations"]}
courses = list(csv.DictReader(open(course_csv, encoding="utf-8")))

def norm(s):
    s = unicodedata.normalize("NFD", s.lower()).encode("ascii", "ignore").decode()
    return re.sub(r"[^a-z0-9]+", " ", s).strip()

def match(name, length):
    cands = [c for c in courses if norm(c["Name"]).replace(" reverse", "") == norm(name) and c["IsReverse"] != "1"
             and abs(float(c["Length"]) - length) / length < 0.03]
    return int(cands[0]["ID"]) if len(cands) == 1 else None

rows, skipped = [], []
for f in sorted(glob.glob(f"{src}/tracks/*.json")):
    slug = os.path.basename(f)[:-5]
    bundle = json.load(open(f, encoding="utf-8"))
    corners = [c for c in bundle.get("corners") or [] if c.get("apex") and c.get("n") is not None]
    cfg = index.get(slug)
    if not corners or cfg is None:
        skipped.append({"slug": slug, "reason": "aucun virage relevé" if not corners else "absent de l'index"})
        continue
    course = match(cfg["official_name"], float(cfg["length_m"]))
    if course is None:
        skipped.append({"slug": slug, "reason": "non rapproché de gt7info"})
        continue
    rows.append({
        "course": course, "name": cfg["official_name"], "official_turns": cfg.get("turns"),
        "count_matches_official": cfg.get("turns") == len(corners),
        "corners": [{"n": c["n"], "name": c.get("name") or "", "x": round(c["apex"]["x"], 1), "z": round(c["apex"]["z"], 1)} for c in corners],
    })
json.dump({"format": "gt7pitwall-track-corners", "version": 1,
           "source": {"repo": "https://github.com/jbhoorasingh/gt7-datalogger-track-data", "license": "CC0-1.0"},
           "tracks": rows, "skipped": skipped},
          open(out, "w", encoding="utf-8"), ensure_ascii=False, separators=(",", ":"))
print(len(rows), "circuits avec virages ;", len(skipped), "écartés")
for r in rows: print(f"  {r['course']:5d} {r['name']:45s} {len(r['corners'])} virages / {r['official_turns']} officiels")
