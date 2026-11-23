# Pitwall Lab — mode d'emploi

Le laboratoire rejoue les traces brutes GT7 (`.gt7trace`) **hors téléphone**, avec le vrai moteur de Pitwall.

**Aucune donnée n'est inventée.** Chaque valeur porte sa nature :
- `MESURE` : lue dans les paquets GT7 ;
- `CALCUL` : produite par le moteur à partir de ces mesures ;
- `SIMULATION` : scénario construit (aucun pour l'instant).

Une trace fabriquée pour les tests porte `GABARIT-NON-MESURE` dans son nom. Tout rapport la signale.

Les traces du pilote sont privées. Elles ne sont jamais dans le dépôt (`*.gt7trace` ignoré). Le Lab les lit sur place,
dans le dossier `PITWALL_TRACES`.

## Commandes

Construction, sans SDK Android : `./gradlew -p lab/standalone :lab:installDist`. Puis :

| Commande | Rôle | Nature |
|---|---|---|
| `lab/build/install/lab/bin/lab list <dossier>` | traces disponibles | — |
| `… inspect <trace>` | échantillons, formats, trous, pertes UDP, transitions 0x84/0x86, carburant | MESURE |
| `… replay <trace> [série] [type]` | sessions archivées, ligne, résultat consolidé, révisions, arrêts, messages de Véga, journal (décisions, positions), compteurs | MESURE + CALCUL |
| `… anomalies <trace>` | incohérences et événements suspects, avec gravité `info` ou `suspect` et contexte (tour, vitesse) | MESURE |
| `lab/compare.sh <versionA> <versionB> <trace> [sortie]` | même trace rejouée par deux versions du moteur ; différences de résultats, alertes, messages, décisions, positions ; rapport JSON et Markdown | CALCUL |
| `… diff <a.json> <b.json> [la] [lb] [rapport.md]` | différences entre deux rapports `replay` | CALCUL |
| `… gabarit <dossier>` | trace fabriquée pour tester le Lab sans trace privée | FABRIQUÉE |

**`compare`** extrait chaque version dans un worktree git (`lab/build/compare/worktrees`), sans toucher aux
branches. Il y copie le Lab actuel et le compile contre le moteur de cette version. Versions prises en charge : V508
et suivantes, celles dont l'archive contient le rang de ligne.

**`anomalies`** recherche :
- les trous de flux (> 500 ms) ;
- les numéros de paquet qui reculent ou sautent anormalement ;
- les tours qui reculent ou sautent, et le nombre de tours qui change (« 8/0 » après l'arrivée → `info`) ;
- 0x84/0x86 incohérents en course, et un peloton en hausse ;
- le carburant qui monte en roulant (> 90 km/h) ;
- la télémétrie figée : position X/Z immobile à vitesse non nulle. Avant le passage au menu, c'est une transition
  connue de GT7 (`TELEMETRIE_FIGEE_SORTIE`, `info`) ; sinon `suspect` ;
- un chrono absent à un passage de ligne.

Les seuils sont des règles du Lab, à ajuster avec les essais.

## Serveur MCP `pitwall-lab`

Il est déclaré dans `.mcp.json` et approuvé dans `.claude/settings.json`. Il expose 5 outils : `lab_list_traces`,
`lab_inspect`, `lab_replay`, `lab_anomalies` et `lab_compare`.

Garde-fous :
- une trace doit finir par `.gt7trace` ;
- une version doit être un nom de branche, d'étiquette ou de commit simple, jamais une option.

**Vérifié dans une nouvelle session Claude Code (23/11)** : les outils du Lab et de Serena se chargent sans invite.
Sur le gabarit, `lab_replay` → ligne P12/16 puis P13/16 ; Serena → `PositionTracker/liveRank` trouvé.

## Premiers résultats sur les traces réelles (23/11)

### `compare` V510 → V511, trace du 22/11

| Catégorie | Différences |
|---|---|
| Résultats et compteurs | 0 |
| Décisions | 0 |
| Alertes | 2 |
| Messages | 4 |
| Positions | 2 |

- **Coaching** : V510 aurait dit « Virage 5 : porte plus de vitesse au milieu. Environ 41 dixièmes par passage. ».
  C'est exactement la consigne relevée dans le journal réel de cette course. V511 dit à la place « Virage 10 : tu
  arrives trop vite, freine un peu plus tôt. Environ 4 dixièmes par passage. ». Le garde-fou de vraisemblance V511 est
  donc confirmé sur données réelles.
- **Bilan** : « … pas assez de tours propres … » devient « … Pas assez de tours propres … ».
- **Journal** : « Classement final : P12 sur 16 » devient « Ligne d'arrivée : P12 sur 16 ».

### `anomalies`

| Trace | Suspects | Constats |
|---|---|---|
| 06/11 | 0 | 5 télémétries figées en sortie de session (`info`) |
| 22/11 | 0 | 2 télémétries figées en sortie de session (`info`), « 8/0 » après l'arrivée |
| 19/11 | 4 | 3 trous de flux (162 s, 29 s, 0,6 s) et un redémarrage de la télémétrie (paquet 250 513 → 36), **tous au menu** (tour -1/-1), aucun en course |
| 28/10 | 1 | « 1/0 → 1/10 » dans les 4 premières secondes : le nombre de tours apparaît alors que le tour 1 est déjà affiché. À examiner |
| 20/11 | — | fichier vide (en-tête seul) |

## Limites

- Les traces sont souvent échantillonnées à 20 Hz : certaines analyses de pilotage diffèrent du direct à 60 Hz.
- L'historique du pilote est absent du rejeu, ce qui limite les comparaisons inter-courses et le coaching.
- La radio n'est vue qu'en texte : aucun son.
- `compare` exige une version V508 ou plus récente.

## Suite possible

- `fuel-scenarios` : stratégies carburant et arrêts étiquetées SIMULATION, à partir des consommations mesurées.
- `vega-golden` : questions radio rejouées sur l'état d'une trace, comparées entre versions.
- Rapport HTML lisible sur le Fold.
