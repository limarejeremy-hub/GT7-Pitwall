#!/usr/bin/env python3
"""
Tracés d'AFFICHAGE (Briefing) des configurations que track_signatures.json n'a pas (signature signalée ou sans tracé
chez jbhoorasingh/gt7-datalogger-track-data : Suzuka East…). Données CC0 du même dépôt, rien n'est inventé :

1. le relevé de bords de la configuration (tracks/<slug>.json, « edges ») ;
2. le tracé d'une configuration complète du même site déjà présente dans track_signatures.json.

La portion du tracé complet couverte (à < 18 m) par le relevé est gardée telle quelle ; la liaison propre à la
configuration est faite de la moyenne, tous les 20 m, des points du relevé situés hors du tracé complet. Résultat gardé
seulement si la portion couverte est d'un seul tenant, la liaison < 400 m, et la longueur à 5 % de la longueur
officielle. Ces tracés ne servent JAMAIS à la reconnaissance du circuit (affichage seulement).

Usage : python3 -I build.py <dossier track-data> <track_signatures.json> <sortie.json> [commit amont]
"""
import json, math, sys

src, sigs_path, out = sys.argv[1], sys.argv[2], sys.argv[3]
commit = sys.argv[4] if len(sys.argv) > 4 else None
sigs = json.load(open(sigs_path, encoding="utf-8"))["signatures"]
have = {s["official_id"] for s in sigs}
upstream = json.load(open(f"{src}/signatures.json", encoding="utf-8"))["signatures"]
index = json.load(open(f"{src}/index.json", encoding="utf-8")).get("configurations", [])

def slug_of(official_id):
    for c in index:
        if c.get("official_id") == official_id:
            return c.get("slug") or c.get("file", "").replace("tracks/", "").replace(".json", "")
    return None

rows, report = [], []
for s in upstream:
    oid = s["official_id"]
    if oid in have:
        continue
    name, length = s["official_name"], s["length_m"]
    slug = slug_of(oid)
    try:
        bundle = json.load(open(f"{src}/tracks/{slug}.json", encoding="utf-8"))
    except Exception:
        report.append((name, "pas de relevé")); continue
    pts = [(e["x"], e["z"]) for e in bundle.get("edges", []) if e.get("kind") in ("edge", "straddle")]
    if len(pts) < 50:
        report.append((name, "relevé trop maigre")); continue
    best = None
    for parent in sigs:
        p = parent["path"]; path = [(p[i], p[i + 1]) for i in range(0, len(p), 2)]
        cov = [min(math.dist(q, e) for e in pts) < 18 for q in path]
        if sum(cov) < 10:
            continue
        n = len(path)
        # Portion couverte d'un seul tenant (en tenant compte du bouclage).
        starts = [i for i in range(n) if cov[i] and not cov[i - 1]]
        if len(starts) != 1:
            continue
        i0 = starts[0]; run = []
        i = i0
        while cov[i % n] and len(run) < n:
            run.append(path[i % n]); i += 1
        far = [e for e in pts if min(math.dist(e, q) for q in path) > 25]
        a, b = run[-1], run[0]
        gap = math.dist(a, b)
        if gap > 400 or gap < 1:
            continue
        ux, uz = (b[0] - a[0]) / gap, (b[1] - a[1]) / gap
        bins = {}
        for e in far:
            t = (e[0] - a[0]) * ux + (e[1] - a[1]) * uz
            if 0 < t < gap:
                bins.setdefault(int(t // 20), []).append(e)
        link = [(sum(x for x, _ in v) / len(v), sum(z for _, z in v) / len(v)) for _, v in sorted(bins.items())]
        loop = run + link
        total = sum(math.dist(loop[k], loop[(k + 1) % len(loop)]) for k in range(len(loop)))
        if abs(total - length) / length > 0.05:
            continue
        cand = (abs(total - length), parent["name"], loop, total)
        if best is None or cand[0] < best[0]:
            best = cand
    if best is None:
        report.append((name, "aucune construction sûre")); continue
    _, parent_name, loop, total = best
    rows.append({"official_id": oid, "name": name, "length_m": round(length), "from": parent_name,
                 "path": [v for q in loop for v in (round(q[0]), round(q[1]))]})
    report.append((name, f"tracé construit ({round(total)} m pour {round(length)} m, à partir de {parent_name})"))

json.dump({"format": "gt7pitwall-track-outlines", "version": 1, "display_only": True,
           "source": {"repo": "https://github.com/jbhoorasingh/gt7-datalogger-track-data", "commit": commit, "license": "CC0-1.0"},
           "outlines": rows}, open(out, "w", encoding="utf-8"), ensure_ascii=False, separators=(",", ":"))
for r in report:
    print(" -", r[0], ":", r[1])
print(len(rows), "tracés d'affichage")
