# Consommation de tokens Claude Code (statistiques agrégées seulement)

Mesure : `python3 tools/token-meter.py` (lecture seule de `~/.claude/projects/**/*.jsonl`). Ne jamais commiter de
journal brut, de texte de conversation, de chemin local ni de résultat d'outil : seulement les lignes ci-dessous.

**Limite** : un conteneur cloud ne garde que les journaux de sa propre session. Lancer le script **avant la fin de
chaque mission**, sinon la mesure est perdue (notée « indisponible », jamais estimée).

## Définitions
- Entrée / sortie : tokens facturés hors cache. Cache lu / écrit : `cache_read` / `cache_creation`.
- Contexte d'un appel = entrée + cache lu + cache écrit. Min ≈ charge fixe (prompt système, outils, CLAUDE.md).
- Opérations coûteuses : outils dont les résultats réinjectent le plus de caractères dans le contexte.

## Fenêtre de compaction (vérifiée le 26/11 par `/context`)
| Configuration | Fenêtre | Tampon | Compaction vers |
|---|---|---|---|
| Sans réglage (modèle 1 M) | 1 000 k | 33 k | ≈ 967 k |
| `autoCompactWindow: 400000` (`.claude/settings.json`) | 400 k | 33 k | ≈ 367 k |
| Variable `CLAUDE_CODE_AUTO_COMPACT_WINDOW` définie | prioritaire sur le réglage | | |

## Relevés

| Date | Mission (branche) | PR | Validée | Appels | Entrée | Sortie | Cache lu | Cache écrit | Ctx min | Médian | P95 | Compactions |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| 26/11 | pitwall-token-optimization (outillage, session complète hors tests imbriqués) | #97 | CI en cours | 54 | 112 | 35 527 | 7 624 155 | 119 170 | 91 529 | 146 500 | 181 030 | 0 |

Constats de la session d'outillage : ≈ 91 k tokens de charge fixe par appel, avant tout travail ; les plus gros
résultats d'outils sont des sorties Bash et des listes de PR complètes (à filtrer : `fields`, `head`, `grep`).

Comparaison de charge fixe (même dépôt, même `CLAUDE.md`, 26/11) : session cloud avec tous les connecteurs claude.ai
chargés ≈ **91 k** ; session Claude Code lancée sans ces connecteurs (tests du hook) ≈ **43–45 k**. Les connecteurs
sans rapport avec Pitwall (courses, messagerie, agenda…) pèsent donc environ la moitié de chaque appel : les
désactiver pour les sessions Pitwall est une piste, **non conclue** : l'écart vient de sessions différentes
(session cloud contre session imbriquée sans connecteurs).

## Test A/B des connecteurs (requis avant toute conclusion)
Deux sessions cloud neuves, même dépôt, même branche, même premier message (par ex. « Lis `CLAUDE.md` et
`docs/SESSION_STATE.md`, puis résume l'état en 5 lignes ») : **A** avec tous les connecteurs claude.ai, **B** sans les
connecteurs hors Pitwall (Gmail, Agenda, Carrefour, Drive, Sheets). Dans chacune, lancer `tools/token-meter.py` et
comparer le contexte min (charge fixe) et le médian. Conclure seulement si l'écart se reproduit.

| Session | Connecteurs | Ctx min | Médian | Écart A − B |
|---|---|---|---|---|
| A | tous | | | |
| B | Pitwall seulement | | | |

## Validation sur deux missions (hypothèse −20 à −35 %, non acquise)
Critères, à qualité égale : CI verte, zéro régression, revue d'agents maintenue, aucune décision oubliée, aucune perte
de contexte bloquante, total traité par PR validée en baisse. Missions passées sans journal : comparer d'abord la
charge fixe, le contexte médian et le P95 entre les deux prochaines missions et cette référence. Si la fenêtre de
400 k dégrade le travail (décision oubliée après compaction, reprise coûteuse), la changer dans `.claude/settings.json`
et noter ici la raison et les chiffres.

| Mission | PR | CI | Régressions | Revue agents | Contexte perdu | Total / PR | Verdict |
|---|---|---|---|---|---|---|---|
| 1 | | | | | | | |
| 2 | | | | | | | |
