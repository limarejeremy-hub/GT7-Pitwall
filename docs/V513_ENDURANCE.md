# V513 — Endurance Intelligence : télémétrie, pneus, carburant, stands

Données analysées : **4 traces brutes réelles** du pilote (lues sur place, jamais commitées). Elles couvrent :
- 6 courses, 57 tours, dont 46 propres pour l'analyse ;
- 3 voitures à moteur thermique ;
- les formats **A** (06/11) et **C** (19/11, 22/11 en Course Sport B, 28/10).

Aucune trace des deux courses du Mans (23/11) : seulement les journaux.

Natures : **MESURE** (paquet GT7), **CALCUL** (Lab), **ESTIMATION**, **HYPOTHÈSE**, **NON ÉVALUABLE**.

Commandes : `lab tyre-research <traces…>`, `lab fuel-performance <traces…>`, `lab pit-research <traces…>`.

> **Source écartée** : Nenkai/PDTools interdit expressément son usage par des IA (`AGENTS.md`, en-têtes SPDX), malgré
> sa licence MIT. Il n'est pas utilisé ici. MacManley/gt7-udp et zetetos/gt-telemetry (MIT) couvrent tous les champs.
> Des documents plus anciens du dépôt (`docs/GAP_HUNTER_RECHERCHE.md`, `docs/UDP_FIELDS.md`) le citent encore : c'est
> au pilote de décider s'il faut retirer ces citations.

## 1. Inventaire de la télémétrie

Le décodeur (`TelemetryParser`) lit **tous** les champs connus de A, B, ~ et C. Sources : gt7-udp, zetetos, et nos
traces (MESURE).

**Format observé en Sport** : la Course Sport B du 22/11 a été reçue en **format C (368 octets)**. Le C est donc
disponible au moins dans ce mode (MESURE). La note « B non disponible en Sport » de gt7-udp ne concerne pas le C.

| Offset | Champ | Unité | Formats | Validation | Exploitation actuelle | Nouveaux calculs possibles |
|---|---|---|---|---|---|---|
| 0x04–0x18 | position X/Y/Z, vitesse X/Y/Z | m, m/s | A+ | MESURE, concordant | circuit, carte, stands, distance | **voie des stands** (écart à la trajectoire) |
| 0x1C–0x28 | rotation (quaternion ou 3 angles + cap : découpages différents selon les projets) | — | A+ | convention à vérifier | non | angle de dérive |
| 0x2C–0x34 | vitesse angulaire X/Y/Z | rad/s | A+ | MESURE | lacet (radio occupée) | roulis et tangage au freinage |
| 0x38 | hauteur de caisse | ? | A+ | MESURE | non | talonnage |
| 0x3C | régime moteur | tr/min | A+ | MESURE | rapports, patinage | — |
| 0x40 | IV de chiffrement | — | A+ | varie à chaque paquet (MESURE) | — | aucun (pas une donnée) |
| 0x44 / 0x48 | carburant / capacité | unités GT7 | A+ | MESURE | stratégie, pleins | **référence par segment** (V513) |
| 0x4C | vitesse | m/s | A+ | MESURE | partout | — |
| 0x50–0x5C | turbo, huile, eau, température d'huile | — | A+ | eau et huile constantes selon gt7-udp | non | — |
| 0x60–0x6C | température de surface des 4 pneus | °C | A+ | MESURE | chauffe, Tyre Change Hunter | **indicateurs par roue** (V513) |
| 0x70–0x8C | n° de paquet, tours, chronos, heure, position, peloton, alertes de régime, vitesse max | — | A+ | MESURE ; 0x84 en direct observé (essais du pilote) | course, position, chronos | — |
| 0x8E | drapeaux | bits | A+ | bits 12–15 inconnus | pause, chargement, en piste | — |
| 0x90–0x92 | rapports, accélérateur, frein | — | A+ | MESURE | coaching, radio | **roue libre** (V513) |
| 0x93 | octet | — | A+ | **toujours 0** dans nos 4 traces | — | aucun |
| 0x94–0xA0 | plan de la route, distance | — | A+ | expérimental | non | pente et dévers |
| 0xA4–0xB0 | rotation des 4 roues | rad/s | A+ | MESURE | patinage, blocages | **rayon effectif v/ω** (V513) |
| **0xB4–0xC0** | **rayon des pneus** | m | A+ | **constant** (V513, ci-dessous) | patinage | **aucun signal d'usure** |
| 0xC4–0xD0 | hauteur de suspension | ? | A+ | MESURE | non | vibreurs |
| **0xD4–0xF3** | 8 flottants « inconnus » | — | A+ | **toujours 0** dans nos 4 traces | — | aucun |
| 0xF4–0x11C | embrayage, rapports de boîte | — | A+ | MESURE | rapports | régime attendu |
| **0x120** | 8ᵉ rapport ou inconnu | — | A+ | **toujours 0** dans nos 4 traces | — | aucun |
| 0x124 | code voiture | — | A+ | MESURE | voiture | — |
| 0x128–0x138 | volant, vitesse du volant, sway/heave/surge | rad, rad/s, accél. | B+ | MESURE ; unités à vérifier | radio occupée | instabilité |
| 0x13C–0x154 | pédales filtrées, type, récupération, couple par roue, couple normalisé | — | ~+ | expérimental | électrique, observation | coupure électronique |
| 0x158 | surface sous chaque pneu | lettres | C | MESURE (« TTTT ») | exclusion des vibreurs | **qualité des mesures pneus** |
| 0x15C–0x16C | chrono en cours, angle des roues avant, empattement, catégorie | — | C | MESURE | chrono en direct, radio | — |

**Absents de tous les formats** (aucun projet, aucune de nos traces) :
- usure des pneus, météo et pluie, dommages ;
- positions et arrêts des adversaires, écarts.

Rien n'est déduit du HUD du jeu.

## 2. Usure des pneus : verdict

| Piste | Test | Résultat (MESURE et CALCUL sur 4 traces, 6 courses) | Verdict |
|---|---|---|---|
| **A. Octets inconnus** | 0x93, 0xD4–0xF3, 0x120 sur tous les paquets, menus et stands compris | **une seule valeur (0) partout**, en A comme en C, en Sport comme hors ligne | **Rejetée** : aucun signal caché dans ces octets |
| **B. Rayon des pneus (0xB4)** | rayon par tour, par relais, avant et après un arrêt | **constant** (0,355 / 0,33 / 0,34–0,355 m selon la voiture), y compris après l'arrêt du 28/10 avec pneus neufs suspectés | **Rejetée** : rayon fixe de la voiture, pas une usure |
| B'. Rayon effectif v/ω | roue par roue, en ligne droite sans frein | roues libres = rayon exact (± 0,1 %) ; roues motrices ≈ 1,3 % sous le rayon (patinage). 28/10 : 0,3508 → **0,3493** après l'arrêt, soit **plus bas** avec des pneus probablement neufs | **Rejetée** comme mesure d'usure : le patinage dépend du couple, de la température et du carburant |
| **C. Calibration visuelle** | captures de l'ATH (l'affichage de jeu à l'écran) associées aux traces horodatées | aucune capture de référence | **Non évaluable** : protocole ci-dessous |

**Conclusion** : l'usure n'est pas lisible dans l'UDP actuel, et rien de fiable ne permet de la reconstruire avec nos
données. Pitwall ne peut proposer qu'un **indice de performance**, jamais un pourcentage d'usure.

**Protocole C, plus tard, sans coût** :
1. Prendre une capture de l'ATH à chaque passage de ligne d'un relais long (usure ×4), avec l'heure de la trace.
2. Faire relever les niveaux par le pilote (contrôle humain).
3. Associer chaque niveau au tour de la trace.
4. N'entraîner un modèle qu'à partir de plusieurs relais complets, en évaluant sur des courses distinctes.

**Live Timing API** : seule une source publique en parle (un article de GTPlanet) : elle n'est pas faite pour les
utilisateurs. D'après une note plus ancienne du dépôt, non revérifiée, elle exigerait une liste blanche ou un jeu
modifié. **Hors de nos règles : non poursuivie.**

## 3. Tyre Intelligence (expérimental, Lab uniquement)

Par roue et par tour :
- température moyenne et maximale ;
- patinage médian sous accélération ;
- rayon effectif ;
- blocages et patinages forts ;
- qualité du tour (0–1 : tour propre, échantillons, mesures présentes).

Par relais :
- pente et Spearman du chrono, de la température et du patinage ;
- détecteur de dégradation (2 tours propres consécutifs à plus de 1 s de la médiane des 3 premiers, avec une
  température en hausse) ;
- autonomie : une fourchette seulement si la tendance est nette (≥ 5 tours, Spearman ≥ 0,6, pente > 100 ms par tour),
  sinon **« inconnue »**.

**Résultats sur les 9 relais réels** :
- **0 alerte** de dégradation ;
- autonomie **« inconnue » pour les 9 relais** ;
- les chronos **s'améliorent** dans 5 relais sur 9 (pente −490 à −1 647 ms par tour : carburant qui s'allège, rythme).

Températures : +1 à +3,6 °C par tour à l'avant après un arrêt, puis stabilisation.

**Modèles du chrono, évalués sans jamais calibrer sur la course testée** (on laisse une course de côté, 06/11) :

| Course testée | M0 sans dégradation (erreur) | M1 avec pente apprise ailleurs (erreur) |
|---|---|---|
| 06/11 n° 1 | 2,34 s | 2,59 s |
| 06/11 n° 2 | 2,36 s | 2,09 s |
| 06/11 n° 3 | 1,70 s | 1,42 s |

M1 fait mieux sur 2 courses sur 3, mais l'écart (≤ 0,3 s) est **plus petit que le bruit** (≈ 2 s : trafic, incidents).
La dégradation n'est **pas démontrée** : ces courses étaient très probablement à usure ×1. **Une trace brute d'une
course à usure ×4 (Le Mans) est indispensable.**

**Ce qui n'entre pas dans le moteur** : rien de cette partie. Aucun conseil de pneus n'est donné en course à partir
de ces indices.

## 4. Fuel Intelligence 2.0 : résultats

| Mesure | Résultat (traces réelles) | Lecture |
|---|---|---|
| Consommation par tour | 06/11 : 6,05–6,15 (écart-type 0,14–0,19) ; 19/11 et 22/11 : 1,95–1,99 (0,02–0,03) ; 28/10 : 15,4 (1,7) | très régulière, sauf le 28/10 |
| Segments les plus gourmands | 3 sur 20 par circuit, par exemple le 13, le 4 et le 17 au 06/11 | base du fuel delta par portion |
| **Prévision du carburant à l'arrivée** (après k tours, ± 2σ√n) | **29 prévisions**, erreur moyenne **0,76** unité, **79 % de couverture** | utile ; **intervalle trop étroit** (79 % au lieu de 95 % visés) : à élargir, avec réglage sur d'autres traces que celles du test |
| **Coût en temps d'une unité économisée** (chrono contre consommation, calibré sur une course, testé sur une autre) | **signes incohérents d'une course à l'autre** (+2,3, −3,8, −6,0 s par unité) | **non calibrable** sur nos traces : la variation naturelle du rythme ne mesure pas le lift-and-coast |
| Roue libre et consommation | Spearman de −0,6 à +0,5 selon la course | pas de lien net dans la conduite normale |

**Conséquence pour V512** :
- les 3,2 s par unité restent une **ESTIMATION expérimentale** d'un seul tour au Mans ;
- aucune de nos traces ne la confirme ni ne la remplace ;
- le moteur garde ses garde-fous : économie ≤ 10 %, coût nettement inférieur, revue à chaque tour.

Pour calibrer, il faut des tours de lift-and-coast **volontaires** et comparables : 2 tours normaux, 2 tours en levant
le pied avant 3 freinages donnés, en course hors ligne.

## 5. Pit Intelligence 2.0 : résultats

Méthode :
- trajectoire de référence d'un tour propre ;
- entrée de voie : écart de plus de 8 m, ou approche continue à 105 km/h ou moins ;
- immobilisation, ravitaillement, retour en piste (moins de 4 m pendant 2 s) ;
- **temps perdu** = portion [entrée − 200 m, retour + 200 m] comparée aux mêmes fractions de tours propres
  **consécutifs** (la portion peut franchir la ligne).

| Trace | Arrêt | Immobile | Plein | Entrée | Retour | **Temps perdu** | **dont voie (hors immobilisation)** |
|---|---|---|---|---|---|---|---|
| 06/11 n° 1 | T7 | 6,8 s | 0 | 1,00 | 0,11 | **25,4 s** | **18,6 s** |
| 06/11 n° 2 | T7 | 7,2 s | 0 | 0,94 | 0,11 | **22,9 s** | **15,7 s** |
| 06/11 n° 3 | T7 | 6,8 s | 0 | 0,94 | 0,12 | **22,1 s** | **15,3 s** |
| 28/10 | T6 + T7 | 10,1 s + 12,1 s | 56,7 | 0,86 | 0,15 | **27,9 s** (une seule visite, deux immobilisations : non interprétée plus avant) | — |

**Coût de la voie pour ce circuit (06/11)** : environ **16,5 ± 1,8 s** hors immobilisation, concordant sur trois
courses (MESURE et CALCUL). C'est la base d'un « coût complet d'arrêt = voie + remplissage » par circuit.

Il manque encore le **débit de remplissage**, à mesurer sur un arrêt avec plein seul.

Le Mans : aucune trace brute. Le journal donne une perte plus courte que l'immobilisation, une mesure incohérente
déjà écartée par V512.

**Adversaires** : aucune donnée dans les paquets. Toute stratégie adverse reste probabiliste ou inconnue.

## 6. Comparaison avec V512 lot 2

**V513 ne modifie ni le moteur ni l'application** : seuls le Lab, les tests et la documentation changent. Le
comportement en course est identique à V512 lot 2, et l'APK du lot 2 reste la version à utiliser.

Raison : aucune fonction de cette étude n'atteint la fiabilité demandée pour la course.
- Pneus : aucun signal démontré.
- Coût du lift-and-coast : non calibrable sur nos traces.
- Coût de la voie : solide, mais sur un seul circuit.

## 7. Proposition de tableau de bord des 4 pneus (non implémenté)

```
┌──────────── PNEUS (indices, pas une usure) ────────────┐
│  AVG 78°  ▲+2/tour     │  AVD 76°  ▲+2/tour            │
│  patinage —  blocages 1│  patinage —  blocages 0       │
│  ARG 72°  =            │  ARD 71°  =                   │
│  patinage 1,3 %  ●     │  patinage 1,2 %  ●            │
├────────────────────────────────────────────────────────┤
│ Relais : 6 tours · tendance chrono −0,5 s/tour         │
│ Autonomie : INCONNUE (tendance insuffisante)           │
│ Qualité des mesures : ●●●○  (3 tours propres sur 4)    │
└────────────────────────────────────────────────────────┘
```

Règles :
- la couleur d'une roue ne dit jamais « usée » ;
- elle signale une **anomalie mesurée** par rapport aux 3 premiers tours du relais (température, patinage, blocages) ;
- le mot **INCONNUE** reste affiché tant que le Lab ne valide pas un modèle sur des courses distinctes.

## 8. Outils et agents

- **Connecteurs** : DeepWiki (dépôts publics) et DuckDB (local) ajoutés au dépôt le 24/11. Ils seront chargés aux
  prochaines sessions. Le plugin Playwright est actif. Aucun connecteur payant, aucun doublon.
- **Agents** :
  - le *chercheur* a comparé les décodeurs open source et relevé la restriction de PDTools ;
  - l'agent *qualité* a relu ce rapport avant toute intégration (voir la PR).

## 9. Ce qu'il faut maintenant (gratuit)

1. **Trace brute d'une course du Mans** (usure ×4, carburant ×2) : seule voie pour tester la dégradation et le coût
   réel d'un arrêt avec plein au Mans.
2. **Tours de lift-and-coast volontaires** (§4) pour calibrer le coût de l'économie.
3. **Captures de l'ATH** à chaque passage de ligne d'un relais ×4, pour le protocole C.
4. Un **arrêt avec plein seul** pour mesurer le débit de remplissage.
