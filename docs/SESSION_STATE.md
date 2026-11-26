# État de mission Pitwall (à tenir à jour à chaque jalon ; court, sans secret ni trace)

## Version et branche active
- Application : `0.14.1 · V515.1 précision stratégie (revue)`, branche `claude/v515-1-strategy-precision`.
- Pile de PR brouillons : … #94 (V514) → #95 (V515) → #96 (V515.1, la plus récente). Détails : `CLAUDE.md` § Branches.

## PR en cours
- #96 V515.1 (brouillon, non fusionnée). Rapport : `docs/V5151.md`.
- Mission outillage : branche `claude/pitwall-token-optimization-l58g31` (base #96), PR brouillon #97 (CI verte).
- **Mission Black Box (en cours)** : branche `claude/black-box` (base #97). Rapport : `docs/BLACK_BOX.md`.

## Mission Black Box — jalons
1. [fait] Inventaire de l'existant (`docs/BLACK_BOX.md` § 1). Décision : réutiliser journal + archive + recorder, pas de
   second moteur de journal ; format `.gt7trace` inchangé.
2. [fait] Recorder : tous les paquets, vidage gzip 2 s, segments concaténables, pré-enregistrement 10 s.
3. [fait] `core/blackbox/` : BlackBox (cycle de vie, meta.json, reprise, rétention, budget non silencieux) et
   BlackBoxPack (ZIP en flux, SHA-256, vérification par réouverture).
4. [fait, CI à confirmer] App : réglage persistant, « Partager ma course », séance, FileProvider `blackbox/exports/`.
5. [fait] `lab import-course` + outil MCP `lab_import_course`.
6. [en cours] Tests PC : core 1054 / 0 échec, lab 30 / 0 échec. CI PR #98, compte rendu, token-meter.
Session : dépôt Pitwall attaché en cours de session (le dépôt principal du conteneur est un autre projet) : les
serveurs MCP du dépôt (`pitwall-lab`, Serena, DeepWiki, DuckDB) et les agents `.claude/agents/` ne sont **pas
chargés** ; le Lab est utilisé en ligne de commande.

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
Black Box : CI verte de #98, puis validation PS5/Fold (`docs/BLACK_BOX.md` § 9). Mission outillage close (CI de #97 verte). Prochaine mission : **Black Box** (une course = un ZIP
partageable ; brief complet du pilote du 26/11 fourni en début de session). Nouvelle session, base
`claude/pitwall-token-optimization-l58g31` (#97) ; c'est la mission 1 de la comparaison des 400 k. En fin de mission :
`python3 tools/token-meter.py`, ligne agrégée dans `docs/TOKENS.md`.
