# État de mission Pitwall (à tenir à jour à chaque jalon ; court, sans secret ni trace)

## Version et branche active
- Application : `0.14.1 · V515.1 précision stratégie (revue)`, branche `claude/v515-1-strategy-precision`.
- Pile de PR brouillons : … #94 (V514) → #95 (V515) → #96 (V515.1, la plus récente). Détails : `CLAUDE.md` § Branches.

## PR en cours
- #96 V515.1 (brouillon, non fusionnée). Rapport : `docs/V5151.md`.
- Mission outillage : branche `claude/pitwall-token-optimization-l58g31` (base #96), PR brouillon #97.

## Mission
Réduire la consommation de tokens des sessions Claude Code sans perte de qualité. Hors périmètre : moteurs carburant,
pneus, stands, Véga, UDP (aucune modification).

## Décisions validées (pilote, 26/11)
- Fenêtre de compaction 400 000 : `autoCompactWindow` dans `.claude/settings.json` (la variable
  `CLAUDE_CODE_AUTO_COMPACT_WINDOW` est refusée depuis une session ; réglable seulement par le pilote dans Settings).
  La plateforme impose `CLAUDE_AUTOCOMPACT_PCT_OVERRIDE=92`.
- Une mission = une nouvelle session ; consignes d'économie dans `CLAUDE.md` ; ce fichier rechargé après compaction
  (hook SessionStart `compact`) ; mesure par `tools/token-meter.py`, agrégats seulement dans `docs/TOKENS.md`.
- Gain de 20 à 35 % : hypothèse, à vérifier sur les deux prochaines missions.
- 400 k conservé pour les deux prochaines missions, puis ajusté selon les mesures (pilote, 26/11).
- Écart de charge fixe dû aux connecteurs (≈ 48 k) : NON conclu ; test A/B requis sur deux sessions équivalentes.

## Résultats démontrés
- V515.1 (PC, pas PS5/Fold) : core 1039 tests 0 échec, lab 27 0 échec ; rejeu des 4 traces réelles identique à V515.
- Fenêtre vérifiée par `/context` (session imbriquée dans ce dépôt) : 400 k avec le réglage (tampon 33 k, compaction
  ≈ 367 k) ; 1 M sans ; la variable d'environnement, si définie, l'emporte (200 k testé).
- Hook testé en vraie compaction (`/compact`) : `hook_success SessionStart:compact`, état et fenêtre injectés.
- Script testé sur des journaux réels et sans journal (« indisponible »).
- Mesure de référence (session d'outillage) : charge fixe ≈ 91 k tokens au premier appel (`docs/TOKENS.md`).

## Points restant ouverts
- Reprise de contexte en nouvelle session : vérifier au début de la prochaine mission (`/context` : « / 400k »).
- Pas de journaux des missions passées (conteneurs éphémères) : la base de comparaison commence ici.
- V515.1 à observer en course : débit de remplissage multi-pleins, service séquentiel, rappel « Box » au tour 2,
  gain des pneus neufs (`docs/V5151.md` § 10).

## Prochaine action
Mission outillage terminée quand la CI de #97 est verte. Reprise du développement dans une nouvelle session.
Prochaine mission dans une nouvelle session, partant de la dernière branche ; en fin de mission, lancer
`python3 tools/token-meter.py` et reporter la ligne agrégée dans `docs/TOKENS.md`.
