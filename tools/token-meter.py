#!/usr/bin/env python3
"""Compteur de tokens Claude Code pour Pitwall (lecture seule, statistiques agrégées uniquement).

Lit les journaux locaux de Claude Code (~/.claude/projects/**/*.jsonl) sans jamais les modifier ni recopier
leur contenu. Sortie : chiffres agrégés, noms d'outils et noms de branches. Aucun texte de conversation, aucun
chemin, aucun résultat d'outil n'est affiché.

Usage :
  python3 tools/token-meter.py                      # tableau Markdown, toutes sessions locales
  python3 tools/token-meter.py --json               # même chose en JSON
  python3 tools/token-meter.py --pr-map prs.json    # {"<branche>": {"pr": 96, "validee": true}}
  python3 tools/token-meter.py --root <dossier>     # autre dossier de journaux

Une donnée absente est signalée « indisponible », jamais remplacée par une estimation.
"""
from __future__ import annotations

import argparse
import json
import os
import statistics
import sys
from collections import Counter, defaultdict
from pathlib import Path

NA = "indisponible"


def pct(values: list[int], q: float):
    if not values:
        return None
    s = sorted(values)
    k = (len(s) - 1) * q
    lo, hi = int(k), min(int(k) + 1, len(s) - 1)
    return round(s[lo] + (s[hi] - s[lo]) * (k - lo))


def content_size(content) -> int:
    """Taille en caractères d'un résultat d'outil (le contenu lui-même n'est jamais conservé)."""
    if isinstance(content, str):
        return len(content)
    if isinstance(content, list):
        n = 0
        for c in content:
            if isinstance(c, dict):
                n += len(c.get("text") or "") if c.get("type") == "text" else len(json.dumps(c)) // 4
        return n
    return 0


def new_session():
    return {
        "calls": {},  # requestId -> usage (dédoublonné : un appel API = plusieurs blocs dans le journal)
        "branches": Counter(),
        "tools": Counter(),
        "tool_chars": Counter(),
        "biggest": [],  # (caractères, outil)
        "compactions": 0,
        "sidechain_calls": 0,
        "missing_usage": 0,
        "first": None,
        "last": None,
    }


def scan(root: Path):
    sessions: dict[str, dict] = defaultdict(new_session)
    files = sorted(root.rglob("*.jsonl")) if root.exists() else []
    for f in files:
        tool_names: dict[str, str] = {}
        try:
            fh = f.open(encoding="utf-8", errors="replace")
        except OSError:
            continue
        with fh:
            for line in fh:
                try:
                    d = json.loads(line)
                except ValueError:
                    continue
                sid = d.get("sessionId") or f.stem
                s = sessions[sid]
                ts = d.get("timestamp")
                if ts:
                    s["first"] = min(s["first"] or ts, ts)
                    s["last"] = max(s["last"] or ts, ts)
                br = d.get("gitBranch")
                if br and br != "HEAD":
                    s["branches"][br] += 1
                t = d.get("type")
                if t == "system" and d.get("subtype") == "compact_boundary" or d.get("isCompactSummary"):
                    s["compactions"] += 1
                msg = d.get("message") if isinstance(d.get("message"), dict) else {}
                if t == "assistant":
                    for c in msg.get("content") or []:
                        if isinstance(c, dict) and c.get("type") == "tool_use":
                            name = c.get("name", "?")
                            tool_names[c.get("id", "")] = name
                            s["tools"][name] += 1
                    u = msg.get("usage")
                    key = d.get("requestId") or msg.get("id") or d.get("uuid")
                    if u is None:
                        s["missing_usage"] += 1
                    elif key:
                        s["calls"][key] = (u, bool(d.get("isSidechain")))
                elif t == "user":
                    for c in msg.get("content") or [] if isinstance(msg.get("content"), list) else []:
                        if isinstance(c, dict) and c.get("type") == "tool_result":
                            name = tool_names.get(c.get("tool_use_id", ""), "?")
                            n = content_size(c.get("content"))
                            s["tool_chars"][name] += n
                            s["biggest"].append((n, name))
    return sessions, len(files)


def summarize(sid: str, s: dict, pr_map: dict) -> dict:
    inp = out = cr = cw = 0
    has = {"input": False, "output": False, "cache_read": False, "cache_write": False}
    contexts = []
    side = 0
    for u, is_side in s["calls"].values():
        side += is_side
        for field, k in (("input_tokens", "input"), ("output_tokens", "output"),
                         ("cache_read_input_tokens", "cache_read"), ("cache_creation_input_tokens", "cache_write")):
            if field in u:
                has[k] = True
        i, o = u.get("input_tokens", 0), u.get("output_tokens", 0)
        r, w = u.get("cache_read_input_tokens", 0), u.get("cache_creation_input_tokens", 0)
        inp, out, cr, cw = inp + i, out + o, cr + r, cw + w
        contexts.append(i + r + w)
    branch = s["branches"].most_common(1)[0][0] if s["branches"] else None
    pr = pr_map.get(branch or "", {})
    biggest = sorted(s["biggest"], reverse=True)[:5]
    return {
        "session": sid[:8],
        "mission": branch or NA,
        "pr": pr.get("pr", NA),
        "pr_validee": pr.get("validee", NA),
        "debut": s["first"] or NA,
        "fin": s["last"] or NA,
        "appels_api": len(s["calls"]),
        "appels_sous_agents": side,
        "entree": inp if has["input"] else NA,
        "sortie": out if has["output"] else NA,
        "cache_lu": cr if has["cache_read"] else NA,
        "cache_ecrit": cw if has["cache_write"] else NA,
        "contexte_median": round(statistics.median(contexts)) if contexts else NA,
        "contexte_p95": pct(contexts, 0.95) if contexts else NA,
        "contexte_min": min(contexts) if contexts else NA,
        "contexte_max": max(contexts) if contexts else NA,
        "compactions": s["compactions"],
        "blocs_sans_usage": s["missing_usage"],
        "outils_appels": dict(s["tools"].most_common(8)),
        "outils_caracteres": dict(s["tool_chars"].most_common(8)),
        "plus_gros_resultats": [{"outil": n, "caracteres": c} for c, n in biggest],
    }


def by_mission(rows: list[dict]) -> list[dict]:
    agg: dict[str, dict] = {}
    for r in rows:
        m = agg.setdefault(r["mission"], {"mission": r["mission"], "pr": r["pr"], "pr_validee": r["pr_validee"],
                                          "sessions": 0, "appels_api": 0, "entree": 0, "sortie": 0,
                                          "cache_lu": 0, "cache_ecrit": 0, "incomplet": False})
        m["sessions"] += 1
        m["appels_api"] += r["appels_api"]
        for k in ("entree", "sortie", "cache_lu", "cache_ecrit"):
            if r[k] == NA:
                m["incomplet"] = True
            else:
                m[k] += r[k]
    for m in agg.values():
        m["total_traite"] = m["entree"] + m["sortie"] + m["cache_lu"] + m["cache_ecrit"]
    return sorted(agg.values(), key=lambda m: m["mission"])


def fmt(v):
    return f"{v:,}".replace(",", " ") if isinstance(v, int) and not isinstance(v, bool) else str(v)


def markdown(rows, missions, nfiles, root_ok) -> str:
    L = []
    if not root_ok or not rows:
        L.append(f"Aucun journal local trouvé : toutes les mesures sont **{NA}**.")
        return "\n".join(L)
    L.append(f"Journaux lus : {nfiles} fichier(s), {len(rows)} session(s). Lecture seule.\n")
    L.append("| Session | Mission (branche) | Appels | Entrée | Sortie | Cache lu | Cache écrit | Contexte min | Médian | P95 | Max | Compactions |")
    L.append("|---|---|---|---|---|---|---|---|---|---|---|---|")
    for r in rows:
        L.append("| " + " | ".join(fmt(r[k]) for k in ("session", "mission", "appels_api", "entree", "sortie", "cache_lu",
                                                       "cache_ecrit", "contexte_min", "contexte_median", "contexte_p95", "contexte_max",
                                                       "compactions")) + " |")
    L.append("\n**Par mission / PR**\n")
    L.append("| Mission | PR | Validée | Sessions | Appels | Total traité | dont cache lu | Données complètes |")
    L.append("|---|---|---|---|---|---|---|---|")
    for m in missions:
        L.append(f"| {m['mission']} | {m['pr']} | {m['pr_validee']} | {m['sessions']} | {fmt(m['appels_api'])} | "
                 f"{fmt(m['total_traite'])} | {fmt(m['cache_lu'])} | {'non' if m['incomplet'] else 'oui'} |")
    L.append("\n**Opérations coûteuses** (taille des résultats d'outils réinjectés dans le contexte, en caractères)\n")
    for r in rows:
        tops = ", ".join(f"{k} {fmt(v)}" for k, v in r["outils_caracteres"].items()) or NA
        big = ", ".join(f"{b['outil']} {fmt(b['caracteres'])}" for b in r["plus_gros_resultats"]) or NA
        L.append(f"- {r['session']} : par outil — {tops} ; plus gros résultats — {big}")
        if r["blocs_sans_usage"]:
            L.append(f"  - {r['blocs_sans_usage']} bloc(s) assistant sans champ usage : non comptés, non estimés.")
    return "\n".join(L)


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--root", default=os.path.expanduser("~/.claude/projects"))
    ap.add_argument("--json", action="store_true")
    ap.add_argument("--pr-map", help="JSON {branche: {pr, validee}} ; sinon PR « indisponible »")
    a = ap.parse_args()
    root = Path(a.root)
    pr_map = json.loads(Path(a.pr_map).read_text()) if a.pr_map else {}
    sessions, nfiles = scan(root)
    rows = sorted((summarize(k, v, pr_map) for k, v in sessions.items() if v["calls"] or v["missing_usage"]),
                  key=lambda r: str(r["debut"]))
    missions = by_mission(rows)
    if a.json:
        json.dump({"fichiers": nfiles, "sessions": rows, "missions": missions}, sys.stdout, ensure_ascii=False, indent=1)
        print()
    else:
        print(markdown(rows, missions, nfiles, root.exists()))
    return 0


if __name__ == "__main__":
    sys.exit(main())
