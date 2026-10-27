#!/usr/bin/env python3
"""
Construit app/src/main/assets/gt7db/track_signatures.json à partir des signatures de circuits de
jbhoorasingh/gt7-datalogger-track-data (données CC0 ; une partie dérivée de zetetos/gt-telemetry, MIT).

Usage : python3 -I build.py <dossier du dépôt track-data> <course.csv de gt7info> <sortie.json>

Rapprochement prudent avec les identifiants gt7info (ceux de Pitwall) : même nom (sans « Reverse »), même sens,
longueur à 3 % près. Une signature signalée par ses auteurs (« flags ») ou sans tracé est écartée. Rien n'est deviné :
une configuration non rapprochée n'est pas utilisée et Pitwall garde sa reconnaissance par la longueur.
"""
import csv, json, re, sys, unicodedata

src, course_csv, out = sys.argv[1], sys.argv[2], sys.argv[3]
doc = json.load(open(f"{src}/signatures.json", encoding="utf-8"))
courses = list(csv.DictReader(open(course_csv, encoding="utf-8")))

def norm(s):
    s = unicodedata.normalize("NFD", s.lower()).encode("ascii", "ignore").decode()
    return re.sub(r"[^a-z0-9]+", " ", s).strip()

def key(name):
    return norm(name).replace(" reverse", "").strip()

by_name = {}
for c in courses:
    by_name.setdefault((key(c["Name"]), c["IsReverse"] == "1"), []).append(c)

def match(name, reverse, length):
    cands = [c for c in by_name.get((key(name), reverse), []) if abs(float(c["Length"]) - length) / length < 0.03]
    return int(cands[0]["ID"]) if len(cands) == 1 else None

rows, skipped, ids = [], [], {}
for s in doc["signatures"]:
    fwd = match(s["official_name"], False, s["length_m"])
    rev = match(s["reverse"]["official_name"], True, s["length_m"]) if s.get("reverse") else None
    ids[s["official_id"]] = fwd
    if s.get("flags") or not s.get("path") or (fwd is None and rev is None):
        skipped.append({"name": s["official_name"], "flags": s.get("flags"), "path": bool(s.get("path")), "id": fwd})
        continue
    rows.append({
        "official_id": s["official_id"], "name": s["official_name"], "course": fwd, "reverse_course": rev,
        "length_m": round(s["length_m"]), "box": [s["min_x"], s["max_x"], s["min_z"], s["max_z"]],
        "path": [v for p in s["path"] for v in (round(p[0]), round(p[1]))],
        "provenance": s.get("provenance"), "ambiguous_with": s.get("ambiguous_with") or [],
    })
for r in rows:
    r["ambiguous_with"] = sorted({ids[o] for o in r["ambiguous_with"] if ids.get(o) is not None})
json.dump({
    "format": "gt7pitwall-track-signatures", "version": 1,
    "source": {"repo": "https://github.com/jbhoorasingh/gt7-datalogger-track-data", "format": doc.get("format"),
               "version": doc.get("version"), "license": "CC0-1.0 (captures dérivées de zetetos/gt-telemetry : MIT)"},
    "path_step_m": doc["counts"].get("path_step_m", 20), "signatures": rows, "skipped": skipped,
}, open(out, "w", encoding="utf-8"), ensure_ascii=False, separators=(",", ":"))
print(len(rows), "signatures utilisables ;", len(skipped), "écartées")
