#!/usr/bin/env bash
# Pitwall Lab — compare : rejoue la MÊME trace avec deux versions du moteur et liste les différences.
#   lab/compare.sh <versionA> <versionB> <trace.gt7trace> [dossier_sortie]
# <version> : branche, étiquette ou commit (ex. claude/v510-live-b-sim, claude/v511-reliability, HEAD).
# Chaque version est extraite dans un worktree git séparé ; le Lab ACTUEL y est copié puis compilé contre le moteur
# (core) de cette version. Rien n'est modifié dans les branches ; les worktrees restent dans lab/build/compare.
# Versions prises en charge : celles dont l'archive contient le rang de ligne (V508 et suivantes).
# LAB_MODE=vega : compare les réponses de Véga (banc vega-golden, mêmes scénarios pour les deux versions : ceux de la
# version courante du Lab) au lieu des rejeux.
set -euo pipefail
[ $# -ge 3 ] || { echo "usage : lab/compare.sh <versionA> <versionB> <trace.gt7trace> [sortie]" >&2; exit 2; }
ROOT="$(git rev-parse --show-toplevel)"
A="$1"; B="$2"; TRACE="$(cd "$(dirname "$3")" && pwd)/$(basename "$3")"
OUT="${4:-$ROOT/lab/build/compare/rapports}"
WORK="$ROOT/lab/build/compare/worktrees"
mkdir -p "$OUT" "$WORK"
[ -f "$TRACE" ] || { echo "trace introuvable : $TRACE" >&2; exit 2; }
export JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:-}"

resolve() {
  local ref="$1"
  if git -C "$ROOT" rev-parse -q --verify "$ref^{commit}" >/dev/null; then echo "$ref"; return; fi
  git -C "$ROOT" fetch -q origin "$ref" 2>/dev/null || true
  if git -C "$ROOT" rev-parse -q --verify "origin/$ref^{commit}" >/dev/null; then echo "origin/$ref"; return; fi
  if git -C "$ROOT" rev-parse -q --verify "FETCH_HEAD^{commit}" >/dev/null; then git -C "$ROOT" rev-parse FETCH_HEAD; return; fi
  echo "version introuvable : $ref" >&2; exit 2
}

run_version() {
  local ref="$1" safe sha dir
  safe="$(echo "$ref" | tr '/:' '__')"
  sha="$(git -C "$ROOT" rev-parse "$(resolve "$ref")")"
  dir="$WORK/$safe"
  if [ -d "$dir" ]; then git -C "$dir" checkout -q --detach "$sha"; else git -C "$ROOT" worktree add -q --detach "$dir" "$sha"; fi
  rm -rf "$dir/lab"
  mkdir -p "$dir/lab"
  (cd "$ROOT/lab" && tar --exclude=./build -cf - .) | (cd "$dir/lab" && tar -xf -)
  # Commandes du Lab qui dépendent d'un module du moteur absent de cette version : retirées pour la compilation
  # (ex. `carburant` a besoin de FuelIntelligence, V512 lot 2). Les rejeux comparés n'en dépendent pas.
  if [ ! -f "$dir/core/src/main/kotlin/com/gt7pitwall/core/strategy/FuelIntelligence.kt" ]; then
    rm -f "$dir/lab/src/main/kotlin/com/gt7pitwall/lab/FuelReplay.kt" "$dir/lab/src/test/kotlin/com/gt7pitwall/lab/FuelReplayTest.kt"
    sed -i '/"carburant" -> FuelReplay.run/d' "$dir/lab/src/main/kotlin/com/gt7pitwall/lab/PitwallLab.kt"
  fi
  # `tyre-diag` a besoin de TyreDiagnostics (V514-A).
  if [ ! -f "$dir/core/src/main/kotlin/com/gt7pitwall/core/tyres/TyreDiagnostics.kt" ]; then
    rm -f "$dir/lab/src/main/kotlin/com/gt7pitwall/lab/TyreDiag.kt"
    sed -i '/"tyre-diag" -> TyreDiag.run/d' "$dir/lab/src/main/kotlin/com/gt7pitwall/lab/PitwallLab.kt"
  fi
  (cd "$dir" && ./gradlew -q --no-daemon -p lab/standalone :lab:installDist) >&2 \
    || { echo "compilation du Lab impossible contre le moteur de $ref (version antérieure à V508 ?)" >&2; exit 3; }
  if [ "${LAB_MODE:-replay}" = vega ]; then
    "$dir/lab/build/install/lab/bin/lab" vega "$TRACE" "$ROOT/lab/vega-golden/scenarios.json" > "$OUT/vega-$safe.json"
    echo "$OUT/vega-$safe.json"
    return
  fi
  "$dir/lab/build/install/lab/bin/lab" replay "$TRACE" > "$OUT/$safe.json"
  echo "$OUT/$safe.json"
}

echo "Rejeu avec $A…" >&2
JA="$(run_version "$A")"
echo "Rejeu avec $B…" >&2
JB="$(run_version "$B")"
(cd "$ROOT" && ./gradlew -q --no-daemon -p lab/standalone :lab:installDist) >&2
if [ "${LAB_MODE:-replay}" = vega ]; then
  MD="$OUT/vega-$(echo "$A" | tr '/:' '__')-vs-$(echo "$B" | tr '/:' '__').md"
  "$ROOT/lab/build/install/lab/bin/lab" vega-diff "$JA" "$JB" "$A" "$B" "$MD"
else
  MD="$OUT/compare-$(echo "$A" | tr '/:' '__')-vs-$(echo "$B" | tr '/:' '__').md"
  "$ROOT/lab/build/install/lab/bin/lab" diff "$JA" "$JB" "$A" "$B" "$MD"
fi
echo "Rapport Markdown : $MD" >&2
