#!/usr/bin/env python3
"""Serveur MCP « pitwall-lab » (stdio, sans dépendance) : expose Pitwall Lab à Claude Code.

Outils :
  lab_list_traces  -> traces .gt7trace disponibles (dossier PITWALL_TRACES ou dossier donné)
  lab_inspect      -> mesures brutes d'une trace (MESURE uniquement)
  lab_replay       -> rejeu dans le moteur Pitwall réel (archives et messages : CALCUL)
  lab_anomalies    -> incohérences de télémétrie et événements suspects (MESURE)
  lab_endurance    -> bilan d'une course longue (tours, carburant, arrêts, flux) et contrôle du règlement (Le Mans)
  lab_vega         -> banc vega-golden : questions à Véga sur des états reconstitués (moteur déterministe)
  lab_import_course -> pack de course Black Box (.zip) : vérification, rejeu, EN_COURSE contre SIMULATION, manquants
  lab_compare      -> même trace rejouée par deux versions du moteur : différences de résultats, alertes, messages,
                      décisions et positions (lab/compare.sh, worktrees git, aucune branche modifiée)

Règles : aucune télémétrie inventée ; les traces sont privées (lues sur place, jamais copiées ni publiées) ;
aucun réseau, aucun secret. Le Lab est compilé à la demande : ./gradlew -p lab/standalone :lab:installDist
"""
import json
import os
import re
import subprocess
import sys

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
LAB_BIN = os.path.join(ROOT, "lab", "build", "install", "lab", "bin", "lab")
PROTOCOL = "2025-06-18"

TOOLS = [
    {
        "name": "lab_list_traces",
        "description": "Liste les traces brutes GT7 (.gt7trace) d'un dossier (défaut : variable PITWALL_TRACES).",
        "inputSchema": {"type": "object", "properties": {"dossier": {"type": "string"}}},
    },
    {
        "name": "lab_inspect",
        "description": "Mesures brutes d'une trace GT7 (échantillons, formats, trous, pertes UDP, transitions 0x84/0x86). "
                       "Chaque valeur est étiquetée MESURE.",
        "inputSchema": {"type": "object", "properties": {"trace": {"type": "string"}}, "required": ["trace"]},
    },
    {
        "name": "lab_replay",
        "description": "Rejoue une trace GT7 dans le moteur Pitwall réel : sessions archivées, classement à la ligne et "
                       "consolidé, révisions après l'arrivée, messages radio produits. Valeurs étiquetées MESURE ou CALCUL.",
        "inputSchema": {
            "type": "object",
            "properties": {
                "trace": {"type": "string"},
                "serie": {"type": "string", "description": "Série Pitwall (défaut DAILY_B)"},
                "type": {"type": "string", "description": "RACE ou QUALIFYING (défaut RACE)"},
            },
            "required": ["trace"],
        },
    },
    {
        "name": "lab_anomalies",
        "description": "Anomalies d'une trace GT7 lues dans les paquets (MESURE) : trous de flux, numéros de paquet qui "
                       "reculent ou sautent, tours qui reculent ou sautent, nombre de tours qui change, 0x84/0x86 "
                       "incohérents en course, peloton en hausse, carburant en hausse en roulant, télémétrie figée, "
                       "chrono absent. Gravité info (transition normale de GT7) ou suspect (à examiner).",
        "inputSchema": {"type": "object", "properties": {"trace": {"type": "string"}}, "required": ["trace"]},
    },
    {
        "name": "lab_import_course",
        "description": "Importe un pack de course Black Box (.zip, « Partager ma course ») : vérifie manifeste et SHA-256, "
                       "rejoue la télémétrie dans le moteur actuel, compare ce qui a été émis en course (EN_COURSE) à ce "
                       "que le moteur recalcule (SIMULATION) et liste les données manquantes.",
        "inputSchema": {"type": "object", "properties": {"pack": {"type": "string"}}, "required": ["pack"]},
    },
    {
        "name": "lab_endurance",
        "description": "Bilan d'endurance d'une trace GT7 sans moteur : par tour chrono, carburant et consommation, position "
                       "0x84, trous de flux ; arrêts détectés (durée, plein, indice de pneus neufs) ; tours par plein et arrêts "
                       "carburant nécessaires (CALCUL). Avec « regles » (ex. lab/le-mans/regles-sport-c-2026-11-23.json), "
                       "contrôle tours, arrivée et arrêt obligatoire. Météo, usure et gomme : NON_MESURABLE.",
        "inputSchema": {"type": "object", "properties": {"trace": {"type": "string"}, "regles": {"type": "string", "description": "fichier JSON de règlement dans lab/ (facultatif)"}}, "required": ["trace"]},
    },
    {
        "name": "lab_vega",
        "description": "Banc vega-golden : rejoue la trace jusqu'à chaque point de scénario (tour N, après l'arrivée) et pose "
                       "les questions de lab/vega-golden/scenarios.json à la vraie conversation radio. Vérifie les nombres "
                       "contre les faits mesurés, l'absence de modification de la course, les termes attendus ou interdits. "
                       "Réponses du moteur DÉTERMINISTE : aucun modèle cloud n'est appelé ni validé.",
        "inputSchema": {"type": "object", "properties": {"trace": {"type": "string"}, "scenarios": {"type": "string", "description": "fichier de scénarios dans lab/vega-golden (défaut scenarios.json ; le-mans.json pour Le Mans)"}}, "required": ["trace"]},
    },
    {
        "name": "lab_compare",
        "description": "Rejoue la même trace avec deux versions du moteur (branches, étiquettes ou commits, V508 et "
                       "suivantes) et renvoie les différences : résultats archivés, compteurs, alertes, messages de "
                       "Véga, décisions et positions. Plusieurs minutes (deux compilations).",
        "inputSchema": {
            "type": "object",
            "properties": {
                "trace": {"type": "string"},
                "version_a": {"type": "string", "description": "ex. claude/v510-live-b-sim"},
                "version_b": {"type": "string", "description": "ex. claude/v511-reliability ou HEAD"},
                "mode": {"type": "string", "enum": ["replay", "vega"], "description": "replay (défaut) : résultats et messages ; vega : réponses de Véga au banc vega-golden"},
            },
            "required": ["trace", "version_a", "version_b"],
        },
    },
]


def ensure_lab():
    if os.path.exists(LAB_BIN):
        return
    subprocess.run(
        [os.path.join(ROOT, "gradlew"), "-p", "lab/standalone", ":lab:installDist", "--no-daemon", "-q"],
        cwd=ROOT, check=True, stdout=sys.stderr, stderr=sys.stderr, timeout=1200,
    )


def resolve_trace(path):
    candidates = [path]
    base = os.environ.get("PITWALL_TRACES")
    if base and not os.path.isabs(path):
        candidates.insert(0, os.path.join(base, path))
    for c in candidates:
        if c.endswith(".gt7trace") and os.path.isfile(c):
            return os.path.abspath(c)
    raise ValueError("trace introuvable ou extension différente de .gt7trace : %s" % os.path.basename(path))


def resolve_pack(path):
    candidates = [path]
    base = os.environ.get("PITWALL_TRACES")
    if base and not os.path.isabs(path):
        candidates.insert(0, os.path.join(base, path))
    for c in candidates:
        if c.endswith(".zip") and os.path.isfile(c):
            return os.path.abspath(c)
    raise ValueError("pack introuvable ou extension différente de .zip : %s" % os.path.basename(path))


def resolve_lab_json(name, sub):
    """Fichier JSON du dépôt sous lab/ uniquement (règlement, scénarios) : jamais un chemin arbitraire."""
    lab = os.path.realpath(os.path.join(ROOT, "lab"))
    for c in (os.path.join(lab, sub, name), os.path.join(ROOT, name)):
        r = os.path.realpath(c)
        if r.startswith(lab + os.sep) and r.endswith(".json") and os.path.isfile(r):
            return r
    raise ValueError("fichier JSON introuvable sous lab/ : %s" % os.path.basename(name))


def run_lab(args):
    ensure_lab()
    env = dict(os.environ, JAVA_TOOL_OPTIONS=os.environ.get("JAVA_TOOL_OPTIONS", ""))
    p = subprocess.run([LAB_BIN] + args, cwd=ROOT, capture_output=True, text=True, encoding="utf-8", timeout=900, env=env)
    out = p.stdout.strip()
    if p.returncode != 0 and not out:
        raise RuntimeError(p.stderr.strip()[-500:] or "échec du Lab")
    return out


def call(name, a):
    if name == "lab_list_traces":
        d = a.get("dossier") or os.environ.get("PITWALL_TRACES")
        if not d:
            raise ValueError("aucun dossier : définir PITWALL_TRACES ou passer « dossier »")
        return run_lab(["list", d])
    if name == "lab_inspect":
        return run_lab(["inspect", resolve_trace(a["trace"])])
    if name == "lab_replay":
        args = ["replay", resolve_trace(a["trace"])]
        if a.get("serie") or a.get("type"):
            args += [a.get("serie") or "DAILY_B", a.get("type") or "RACE"]
        return run_lab(args)
    if name == "lab_anomalies":
        return run_lab(["anomalies", resolve_trace(a["trace"])])
    if name == "lab_import_course":
        return run_lab(["import-course", resolve_pack(a["pack"])])
    if name == "lab_endurance":
        args = ["endurance", resolve_trace(a["trace"])]
        if a.get("regles"):
            args.append(resolve_lab_json(a["regles"], "le-mans"))
        return run_lab(args)
    if name == "lab_vega":
        return run_lab(["vega", resolve_trace(a["trace"]), resolve_lab_json(a.get("scenarios") or "scenarios.json", "vega-golden")])
    if name == "lab_compare":
        for k in ("version_a", "version_b"):
            if not re.fullmatch(r"[A-Za-z0-9._/\-]{1,120}", a.get(k, "")) or a[k].startswith("-"):
                raise ValueError("version invalide : %s" % a.get(k))
        p = subprocess.run(
            [os.path.join(ROOT, "lab", "compare.sh"), a["version_a"], a["version_b"], resolve_trace(a["trace"])],
            cwd=ROOT, capture_output=True, text=True, encoding="utf-8", timeout=2400,
            env=dict(os.environ, JAVA_TOOL_OPTIONS=os.environ.get("JAVA_TOOL_OPTIONS", ""),
                     LAB_MODE="vega" if a.get("mode") == "vega" else "replay"),
        )
        if p.returncode != 0:
            raise RuntimeError(p.stderr.strip()[-800:] or "échec de la comparaison")
        return p.stdout.strip()
    raise ValueError("outil inconnu : %s" % name)


def handle(msg):
    method = msg.get("method")
    mid = msg.get("id")
    if method == "initialize":
        return {"jsonrpc": "2.0", "id": mid, "result": {
            "protocolVersion": msg.get("params", {}).get("protocolVersion", PROTOCOL),
            "capabilities": {"tools": {}},
            "serverInfo": {"name": "pitwall-lab", "version": "0.1.0"},
        }}
    if method == "ping":
        return {"jsonrpc": "2.0", "id": mid, "result": {}}
    if method == "tools/list":
        return {"jsonrpc": "2.0", "id": mid, "result": {"tools": TOOLS}}
    if method == "tools/call":
        p = msg.get("params", {})
        try:
            text = call(p.get("name"), p.get("arguments") or {})
            return {"jsonrpc": "2.0", "id": mid, "result": {"content": [{"type": "text", "text": text}], "isError": False}}
        except Exception as e:  # erreur d'outil : rendue au modèle, jamais de trace de pile avec des chemins privés
            return {"jsonrpc": "2.0", "id": mid, "result": {"content": [{"type": "text", "text": "Erreur : %s" % e}], "isError": True}}
    if mid is None:
        return None  # notification
    return {"jsonrpc": "2.0", "id": mid, "error": {"code": -32601, "message": "méthode inconnue : %s" % method}}


def main():
    for line in sys.stdin:
        line = line.strip()
        if not line:
            continue
        try:
            reply = handle(json.loads(line))
        except Exception as e:
            reply = {"jsonrpc": "2.0", "id": None, "error": {"code": -32700, "message": str(e)}}
        if reply is not None:
            sys.stdout.write(json.dumps(reply, ensure_ascii=False) + "\n")
            sys.stdout.flush()


if __name__ == "__main__":
    main()
