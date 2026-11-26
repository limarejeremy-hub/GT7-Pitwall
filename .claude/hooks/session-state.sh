#!/usr/bin/env bash
# SessionStart (matcher "compact") : réinjecte l'état de mission après une compaction.
# Sortie standard = contexte ajouté. Plafonnée pour ne pas devenir elle-même coûteuse.
f="${CLAUDE_PROJECT_DIR:-$(pwd)}/docs/SESSION_STATE.md"
if [ -f "$f" ]; then
  echo "État de mission Pitwall rechargé après compaction (docs/SESSION_STATE.md) :"
  head -c 6000 "$f"
else
  echo "docs/SESSION_STATE.md introuvable : relire CLAUDE.md avant de continuer."
fi
echo
echo "Fenêtre de compaction : CLAUDE_CODE_AUTO_COMPACT_WINDOW=${CLAUDE_CODE_AUTO_COMPACT_WINDOW:-non défini} ; CLAUDE_AUTOCOMPACT_PCT_OVERRIDE=${CLAUDE_AUTOCOMPACT_PCT_OVERRIDE:-non défini} ; réglage du dépôt : autoCompactWindow dans .claude/settings.json."
