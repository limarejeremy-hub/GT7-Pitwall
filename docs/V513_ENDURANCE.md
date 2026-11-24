# V513 — Endurance Intelligence : télémétrie, pneus, carburant, stands

Données analysées : **4 traces brutes réelles** du pilote (lues sur place, jamais commitées). Elles couvrent :
- 6 courses, 57 tours, dont **40 propres** pour l'analyse (le tour qui précède un arrêt, qui contient l'entrée de voie,
  est exclu depuis la revue qualité) ;
- 3 voitures à moteur thermique ;
- les formats **A** (06/11) et **C** (19/11, 22/11 en Course Sport B, 28/10).

Aucune trace des deux courses du Mans (23/11) : seulement les journaux.

Natures : **MESURE** (paquet GT7), **CALCUL** (Lab), **ESTIMATION**, **HYPOTHÈSE**, **NON ÉVALUABLE**.

Commandes : `lab tyre-research <traces…>`, `lab fuel-performance <traces…>`, `lab pit-research <traces…>`.

> **Source exclue** : l'auteur de Nenkai/PDTools interdit l'usage de son dépôt par des IA (`AGENTS.md` : lecture,
> analyse, indexation et génération). Décision du pilote (24/11) : il est **exclu des prochaines recherches, analyses
> et générations assistées par IA** ; les citations historiques et leur attribution restent en place (règle dans
> `CLAUDE.md`, revue de provenance dans `docs/PROVENANCE.md`). MacManley/gt7-udp et zetetos/gt-telemetry (MIT)
> couvrent tous les champs utilisés ici.

> **Revue qualité (24/11)** : l'agent *qualité* a relu ce rapport et le code du Lab. Ses corrections justifiées sont
> appliquées (chiffres, sur-interprétations, 10 défauts du Lab, 6 tests qui reproduisent les défauts). Les chiffres
> ci-dessous sont ceux du Lab corrigé, recalculés sur les mêmes traces.

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

## 2. Usure des pneus : verdict (résultats négatifs conservés)

Ces résultats négatifs sont des résultats : ils évitent de câbler un faux indicateur d'usure.

| Piste | Test | Résultat (MESURE et CALCUL sur 4 traces, 6 courses) | Verdict |
|---|---|---|---|
| **A. Octets inconnus** | 0x93, 0xD4–0xF3, 0x120 sur tous les paquets, menus et stands compris | **une seule valeur (0) partout**, en A comme en C, en Sport comme hors ligne | **Rejetée dans ces conditions** : aucune variation observée. Les 6 courses étaient très probablement à usure ×1 : rien n'est testé à ×4 |
| **B. Rayon des pneus (0xB4)** | rayon par tour, par relais, avant et après un arrêt | **constant** (0,33 / 0,34–0,35 / 0,355 m selon la voiture et l'essieu), y compris après l'arrêt du 28/10 | **Rejetée** : rayon fixe de la voiture, pas une usure |
| B'. Rayon effectif v/ω | roue par roue, en ligne droite sans frein | roues libres = rayon exact (± 0,1 %) ; roues motrices **1,2 à 1,6 %** sous le rayon (patinage : 06/11 1,4–1,5 % ; 19/11 et 22/11 1,4–1,5 % ; 28/10 1,2 % puis 1,6 % après l'arrêt). 28/10 : 0,3508 → **0,3493** après l'arrêt | **Rejetée** comme mesure d'usure : le patinage dépend du couple, de la température et du carburant. HYPOTHÈSE non vérifiée : pneus neufs au 28/10 ; le rythme y chute de 8 à 16 s par tour et la consommation de 15,4 à 12,5 après l'arrêt, un changement de régime inexpliqué |
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
- blocages et patinages forts (comptés en **échantillons** à environ 20 Hz, pas en événements) ;
- qualité du tour (0–1 : tour propre, échantillons, mesures présentes).

Par relais :
- pente et Spearman du chrono, de la température et du patinage ;
- détecteur de dégradation : 2 tours propres **réellement consécutifs** à plus de 1 s de la médiane des 3 premiers,
  avec une température en hausse ;
- autonomie : une fourchette seulement si la tendance est nette (≥ 5 tours, Spearman ≥ 0,6, pente > 100 ms par tour),
  sinon **« inconnue »** ; une borne haute non connue reste vide (plus de « 2 000 tours »).

Un double arrêt (28/10 : deux immobilisations en une visite) ne crée plus qu'un seul nouveau relais.

**Résultats sur les relais réels (8 relais d'au moins 4 tours propres)** :
- **limite structurelle** : le détecteur exige 5 tours propres consécutifs dans un relais. **Seuls 2 relais sur 8
  sont testables** (19/11 et 22/11, 6 tours, 2 comparaisons chacun) ; les 6 relais du 06/11 n'ont que 4 tours propres.
  **« 0 alerte » ne prouve donc rien** sur ces 6 relais ;
- **0 alerte** sur les 2 relais testables ; autonomie **« inconnue » partout** ;
- pentes du chrono : **3 relais s'améliorent** (06/11 n° 1 : −786 ; 19/11 : −490 ; 22/11 : −1 647 ms par tour),
  **1 est stable** (06/11 n° 3 : −21) et **4 se dégradent** (06/11 n° 2 : +262 ; seconds relais du 06/11 : +2 143,
  +672, +318). Sur 4 à 6 tours,
  aucune conclusion : les pentes négatives viennent surtout d'un tour 2 lent (trafic du départ : 22/11 102,8 s contre
  90,6–92,4 ; 19/11 94,9 s), pas d'un « carburant qui s'allège » démontré ;
- températures à l'avant : +2,5 à +3,6 °C par tour sur les seconds relais du 06/11 (après l'arrêt), +1,05 à +2,9 sur
  les relais de départ. Aucune « stabilisation » n'est calculée.

HYPOTHÈSES à vérifier, non utilisées : au 06/11, le patinage des roues avant motrices augmente au fil de chaque
relais (Spearman de +0,4 à +1,0, au moins +0,8 dans 5 relais sur 6) et les seconds relais ont une pente positive. Ces effets sont confondus
avec la température et le carburant et ne portent que sur 4 tours.

**Modèles du chrono, évalués sans jamais calibrer sur la course testée** : calibration sur les autres courses de la
**même voiture et du même circuit** (longueur de tour à 1 % près) ; M0 limité au **même relais**.

| Course testée | Courses de calibration | M0 sans dégradation (erreur) | M1 avec pente apprise ailleurs (erreur) |
|---|---|---|---|
| 06/11 n° 1 | les 2 autres du 06/11 | 3,42 s | 3,45 s |
| 06/11 n° 2 | les 2 autres du 06/11 | 1,72 s | 1,52 s |
| 06/11 n° 3 | les 2 autres du 06/11 | 0,46 s | 0,63 s |
| 19/11 | 22/11 | 0,77 s | 3,35 s |
| 22/11 | 19/11 | 3,06 s | 1,84 s |
| 28/10 | aucune (M1 = M0) | 5,61 s | 5,61 s |

M1 ne bat M0 que sur 2 courses sur 5 comparables, avec des écarts de signes opposés et du même ordre que l'erreur
elle-même (0,5 à 5,6 s selon la course). **La dégradation n'est pas démontrée.** Une trace brute d'une course à usure
×4 (Le Mans) est indispensable.

**Ce qui n'entre pas dans le moteur** : rien de cette partie. Aucun conseil de pneus n'est donné en course à partir
de ces indices.

## 4. Fuel Intelligence 2.0 : résultats

| Mesure | Résultat (traces réelles) | Lecture |
|---|---|---|
| Consommation par tour (tours propres) | 06/11 : 6,06–6,17 (écart-type 0,08–0,12) ; 19/11 et 22/11 : 1,95–1,99 (0,02–0,03) ; 28/10 : 14,5 (1,6) | très régulière, sauf le 28/10 (changement de régime après l'arrêt) |
| Segments les plus gourmands | 06/11 : segments 4, 13 et 17 à 0,416 / 0,411 / 0,409 | **à égalité de fait** : classement non robuste, aucune décision n'en dépend |
| **Prévision du carburant à l'arrivée** (± 2σ√n, courses complètes seulement) | **29 prévisions sur 6 courses**, erreur moyenne **0,73**, couverture **79 %**. Par trace : 06/11 0,36 (biais **−0,28**, 17/21) ; 19/11 et 22/11 0,04 (6/6) ; 28/10 **6,6** (0/2) | le défaut n'est pas surtout un intervalle trop étroit, mais un **biais** : au 06/11, le tour d'entrée de voie et le tour d'arrêt consomment moins que 2 × la médiane, et le modèle ne le sait pas. Au 28/10, le changement de régime fait tout échouer. Les prévisions d'une même course sont corrélées : 79 % n'est pas un test à 95 % |
| Intervalle **calibré sur les autres courses** (quantile 90 % des erreurs normalisées des autres courses) | multiplicateur 2,0–2,3 ; couverture **83 %** (06/11 17/21, 28/10 1/2) | gain faible : un intervalle plus large ne corrige pas un biais |
| **Coût en temps d'une unité économisée** (calibré sur les **deux autres** courses du 06/11, seul circuit évaluable) | calibré : −0,9 / −14,9 / −5,7 s par unité ; course testée : −25,1 / +15,0 / −3,9 | **non calibrable** : signes incohérents, la variation naturelle du rythme ne mesure pas le lift-and-coast |
| Roue libre et consommation | Spearman de −0,7 à +0,8 selon la course (roue libre comptée sur tout le tour, pas seulement avant les freinages) | pas de lien net dans la conduite normale |

**Conséquence pour V512** :
- les 3,2 s par unité restent une **ESTIMATION expérimentale** d'un seul tour au Mans ;
- aucune de nos traces ne la confirme ni ne la remplace ;
- le moteur garde ses garde-fous : économie ≤ 10 %, coût nettement inférieur, revue à chaque tour ;
- **aucun modèle de lift-and-coast n'entre dans le moteur.**

Pour calibrer, il faut des tours de lift-and-coast **volontaires** et comparables : 2 tours normaux, 2 tours en levant
le pied avant 3 freinages donnés, en course hors ligne.

## 5. Pit Intelligence 2.0 : résultats

Méthode (Lab corrigé) :
- trajectoire de référence d'un tour propre ;
- entrée de voie : écart de plus de 8 m, ou approche continue à 105 km/h ou moins ; si les deux répondent, le plus
  proche de l'arrêt est retenu ; une entrée à vitesse médiane > 105 km/h est marquée **douteuse** ;
- une visite à plusieurs immobilisations est comptée **une seule fois** (immobilisation = somme des arrêts) ;
- retour en piste : moins de 4 m de la trajectoire, au-dessus de la vitesse de voie, pendant 2 s ;
- **temps perdu** = portion [entrée − 200 m, retour + 200 m] comparée aux mêmes fractions de tours propres
  consécutifs (la portion peut franchir la ligne, y compris quand elle commence juste avant) ; fenêtre tronquée →
  aucun chiffre ;
- **recoupement indépendant** : chronos GT7 des tours touchés moins autant de tours propres médians.

| Trace | Arrêt | Immobile (MESURE) | Plein | Entrée | Temps perdu, portion (CALCUL) | dont hors immobilisation | Temps perdu, chronos GT7 (CALCUL) |
|---|---|---|---|---|---|---|---|
| 06/11 n° 1 | T7 | 6,8 s | 0 | 25 m avant la ligne | 26,0 s | 19,2 s | 21,3 s |
| 06/11 n° 2 | T7 | 7,2 s | 0 | 25 m avant la ligne | 26,5 s | 19,3 s | 18,8 s |
| 06/11 n° 3 | T7 | 6,8 s | 0 | 26 m avant la ligne | 25,9 s | 19,1 s | 29,4 s |
| 28/10 | T6 + T7, **une visite** | 22,2 s (2 immobilisations) | 56,6 | **douteuse** (195 km/h, remonte en piste) | 26,6 s | 4,5 s (non interprétable) | 40,1 s (faussé par le changement de régime) |

**Coût de la voie au 06/11 : un ordre de grandeur, pas une valeur fiable.**
- Les deux méthodes diffèrent de 3 à 7 s par course : 26 s par la portion, de 19 à 29 s par les chronos.
- L'ancienne version du détecteur donnait 22 à 25 s.
- Seule l'immobilisation est une MESURE. Le reste est un CALCUL, sur 3 courses du même jour et du même circuit.
- Conclusion : environ **20 à 30 s** de perte totale pour un arrêt sans plein sur ce circuit. Ce n'est pas assez
  précis pour remplacer la valeur du moteur.

Il manque encore le **débit de remplissage**, à mesurer sur un arrêt avec plein seul.

Le Mans : aucune trace brute. Le journal donne une perte plus courte que l'immobilisation, une mesure incohérente
déjà écartée par V512.

**Adversaires** : aucune donnée dans les paquets. Toute stratégie adverse reste probabiliste ou inconnue.

Limites connues du Lab, non corrigées :
- **Arrêts** : un arrêt de 3 s ou plus hors pause est compté comme arrêt. Un tête-à-queue ou une pénalité donnent le
  même signal, et seul un plein prouve un passage au stand.
- **Projection** : elle se fait sur le point le plus proche, ce qui devient ambigu si la piste repasse près
  d'elle-même.
- **Découpage** : un redémarrage pendant le tour 1 fusionne deux tentatives.
- **Tours écartés** : ils sont comptés (`droppedLaps`). Une course incomplète n'entre plus dans la prévision.

## 6. Comparaison avec V512 lot 2

**V513 ne modifie ni le moteur ni l'application** : seuls le Lab, les tests et la documentation changent. Le
comportement en course est identique à V512 lot 2, et l'APK du lot 2 reste la version à utiliser. Aucune nouvelle APK
n'est utile pour ce lot.

Raison : aucune fonction de cette étude n'atteint la fiabilité demandée pour la course.
- Pneus : aucun signal démontré ; le détecteur n'a pu être testé que sur 2 relais.
- Coût du lift-and-coast : non calibrable sur nos traces.
- Prévision du carburant : un biais au tour d'arrêt, et un échec au 28/10.
- Coût de la voie : un ordre de grandeur (20 à 30 s), sur un seul circuit, avec deux méthodes qui divergent.

## 7. Prochaine étape A — tableau de bord de diagnostic des 4 pneus (spécification, non implémenté)

But : montrer **ce que GT7 mesure vraiment** sur chaque roue, comparé au début du relais, sans jamais afficher
d'usure ni de pourcentage.

```
┌──────────── PNEUS — diagnostic mesuré (pas une usure) ─────────────┐
│ AVG 78 °C  ▲ +2,4/tour │ AVD 76 °C  ▲ +2,1/tour                     │
│ patinage —   blocages 3│ patinage —   blocages 0      (échantillons)│
│ ARG 72 °C  =           │ ARD 71 °C  =                                │
│ patinage 1,4 %  ●      │ patinage 1,5 %  ●                           │
├─────────────────────────────────────────────────────────────────────┤
│ Relais : 6 tours (5 propres) · chrono −0,5 s/tour (non significatif)│
│ Usure : NON TRANSMISE PAR GT7 · Autonomie : INCONNUE                │
│ Qualité des mesures : ●●●○ · surface TTTT (format C)                │
└─────────────────────────────────────────────────────────────────────┘
```

| Élément affiché | Source | Nature | Règle |
|---|---|---|---|
| Température de surface par roue | 0x60–0x6C | MESURE | moyenne du tour et maximale |
| Tendance de température | Lab `tyre-research` | CALCUL | pente par tour du relais ; « = » si moins de 0,5 °C par tour |
| Patinage sous accélération | 0xA4–0xB0 × 0xB4–0xC0 / vitesse | CALCUL | roues motrices seulement ; « — » sinon |
| Blocages et patinages forts | patinage + frein ou accélérateur | CALCUL | nombre d'**échantillons**, signalé comme tel |
| Surface sous chaque pneu | 0x158 (format C) | MESURE | tours avec vibreur, herbe ou sable marqués moins fiables |
| Chrono du relais | 0x7C | MESURE + CALCUL | pente affichée « non significative » sous 5 tours propres |
| Usure | — | **absente** | texte fixe « non transmise par GT7 » |
| Autonomie | Lab | CALCUL | « INCONNUE » tant qu'aucun modèle n'est validé sur des courses distinctes |

Couleurs : une roue change de couleur seulement sur une **anomalie mesurée** par rapport aux 3 premiers tours du
relais (écart de température de plus de 8 °C avec la roue opposée, blocages répétés, patinage en hausse). Jamais
« usé ».

Étapes, toutes sans coût :
1. Faire produire par le Lab, sur les traces réelles, les lignes « roue × tour » de ce tableau (déjà dans
   `tyre-research`), et faire valider les seuils d'anomalie par le pilote sur 2 courses.
2. Écrire un calcul `TyreDiagnostics` en Kotlin pur dans `core/`, testé sur les traces réelles via `RealTraceReplay`,
   sans rien de prédictif.
3. Ajouter la carte Compose au tableau de bord, avec une capture Robolectric dans la CI, puis la faire valider en
   course sur PS5 et Fold par le pilote.
4. Seulement ensuite, produire une APK.

## 8. Prochaine étape B — intervalles de prévision du carburant (plan d'évaluation)

Constat (§4) : l'erreur vient surtout d'un **biais**, pas de la largeur de l'intervalle. Plan :

1. **Modèle à tours spéciaux** : retirer les tours d'entrée de voie et d'arrêt de la médiane, et prévoir leur
   consommation à part. Ce poste est appris par circuit sur les arrêts mesurés (06/11 : environ 0,6 à 0,7 unité de
   moins que 2 tours normaux).
2. **Détection de changement de régime** (cas du 28/10) : la médiane est réinitialisée si 2 tours consécutifs
   s'écartent de plus de 3 écarts-types. L'intervalle est alors élargi et un message dit « régime changé ».
3. **Intervalle calibré** (déjà dans le Lab) : le multiplicateur est appris sur les erreurs normalisées des **autres**
   courses, jamais sur celle qui est évaluée.
4. **Évaluation** :
   - laisser une course de côté, en séparant aussi les **jours** (une course du 06/11 n'est jamais calibrée sur les
     deux autres seules) ;
   - critères : biais, erreur absolue et couverture **par course**, pas seulement globale ;
   - il faut au moins 3 jours et 2 circuits avant toute conclusion.
5. **Intégration dans le moteur** : seulement si le modèle bat la version V513 sur des courses distinctes, avec une
   comparaison `lab_compare` à V512 lot 2 et sans régression.

Données qui manquent : une trace brute du Mans, et des courses avec plein sur un second circuit.

## 9. Outils et agents

- **Connecteurs** : DeepWiki (dépôts publics) et DuckDB (local) ajoutés au dépôt le 24/11. Ils seront chargés aux
  prochaines sessions. Le plugin Playwright est actif. Aucun connecteur payant, aucun doublon.
- **Agents** :
  - le *chercheur* a comparé les décodeurs open source et relevé la restriction de PDTools ;
  - l'agent *qualité* a relu ce rapport et le code du Lab. Ses corrections sont appliquées (encadré en tête du
    rapport, PR #93).

## 9 bis. Recoupements par connecteur (24/11)

| Connecteur | Action exécutée | Résultat concret |
|---|---|---|
| DeepWiki (accès HTTP public, dépôt public uniquement) | question sur MacManley/gt7-udp | 1 octet de remplissage et `UNKNOWNFLOATS[8]` ; rayon, rotation et suspension présents ; **aucun champ d'usure**. Concorde avec la piste A. zetetos/gt-telemetry n'est pas indexé par DeepWiki |
| Parallel Search | « GT7 telemetry tyre wear » ; puis licences de gt7-udp et gt-telemetry, et formulation de l'exclusion de PDTools | TRACE (ransh2014, MIT) : « GT7 sends no tyre wear, so the tyre figures are estimates ». gt7-udp et gt-telemetry sont sous licence MIT |
| Playwright | lecture du README de gt7-udp | « Packet B… (not available in Sport Mode) », « Packet ~… (not available in Replays) » : rien sur le C, cohérent avec notre C reçu en Sport B (22/11) |
| DuckDB (local, `uv run`, après correction du Lab) | SQL sur les tours et les prévisions exportés | 40 tours propres ; chronos médians de 120,1 à 122,1 s (06/11) et de 91,8 à 91,9 s (19/11 et 22/11). Biais des prévisions : −0,28 au 06/11, −6,6 au 28/10 ; couvertures 17/21, 3/3, 3/3, 0/2 (1/2 après calibration). Concorde avec `fuel-performance` |
| Google Drive (lecture des métadonnées) | recherche de fichiers `.gt7trace` et de journaux Pitwall | 10 fichiers : journaux et APK anciennes, **aucune trace brute du Mans** |

## 10. Ce qu'il faut maintenant (gratuit)

1. **Trace brute d'une course du Mans** (usure ×4, carburant ×2) : seule voie pour tester la dégradation et le coût
   réel d'un arrêt avec plein au Mans.
2. **Tours de lift-and-coast volontaires** (§4) pour calibrer le coût de l'économie.
3. **Captures de l'ATH** à chaque passage de ligne d'un relais ×4, pour le protocole C.
4. Un **arrêt avec plein seul** pour mesurer le débit de remplissage.
5. Des **relais d'au moins 5 tours propres** : en dessous, le détecteur de dégradation ne teste rien.
