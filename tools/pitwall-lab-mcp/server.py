#!/usr/bin/env python3
"""Serveur MCP « pitwall-lab » (stdio, sans dépendance) : expose Pitwall Lab à Claude Code.

Outils :
  lab_list_traces  -> traces .gt7trace disponibles (dossier PITWALL_TRACES ou dossier donné)
  lab_inspect      -> mesures brutes d'une trace (MESURE uniquement)
  lab_replay       -> rejeu dans le moteur Pitwall réel (archives et messages : CALCUL)

Règles : aucune télémétrie inventée ; les traces sont privées (lues sur place, jamais copiées ni publiées) ;
aucun réseau, aucun secret. Le Lab est compilé à la demande : ./gradlew -p lab/standalone :lab:installDist
"""
import json
import os
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
