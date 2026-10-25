# Race Engineer V3

Branche `claude/race-engineer-v3`, partie de `claude/tyre-intelligence-v2` (PR #12). Elle n'ajoute aucun tableau de bord,
aucune vidéo, aucune capture d'écran : les données servent au moteur local, et ce qui compte passe par la radio.
L'ingénieur fonctionne sans aucun service d'IA. Gemini peut formuler les réponses, mais les calculs, les preuves et les
décisions restent au moteur Pitwall.

## 1. Audit : les données et ce qu'elles permettent de décider

| Donnée GT7 | Utilisée pour décider | Statut |
|---|---|---|
| Positions X/Z | Carte des virages, projection des tours, trajectoire au point de vitesse minimale | Développé |
| Vitesse, décélération (paquets successifs) | Vitesses d'entrée, minimale et de sortie ; détection d'un pilote occupé | Développé |
| Frein, accélérateur | Point et montée en pression du freinage ; point de réaccélération | Développé |
| Lacet (vitesse angulaire Y) | Pilote occupé (rotation, accélération latérale) ; rotation réelle d'un virage | Développé |
| Vitesse des roues × rayon | Blocage à l'avant, patinage à l'arrière (dans chaque virage et dans le tour) | Développé |
| TCS | Part de la zone de virage avec l'antipatinage actif | Mesuré, archivé, pas de conseil |
| Températures des 4 pneus | Tyre Change Hunter (V2), diagnostic d'adhérence | Inchangé |
| Carburant | Consommation par tour, plan, erreur de prévision | Développé (garde contre les coupures) |
| Chronos, tours | Référence par virage, régularité, rythme en fin de relais, bilan | Développé |
| Place (0x84) | Grille et arrivée pour le bilan ; aucune attribution causale | Développé |
| Volant (formats B et ~) | Pilote occupé | Développé |
| Format C : surfaces | Sortie de piste dans une zone de virage (virage exclu des comparaisons) | Développé, comme V2 |
| Format C : angle des roues | Pilote occupé ; rapport rotation réelle / rotation attendue | **Expérimental** : archivé, jamais dit à la radio |
| Format C : empattement | Calcul de la rotation attendue | **Expérimental** |
| Format C : chrono du tour en cours, catégorie | Aucune | Non utilisé : signification non validée sur PS5 |
| Suspension, hauteur de caisse, rapports, régime | Aucune décision démontrée | Non utilisé |
| Adversaires (écarts, arrêts, gommes) | — | **Non transmis par GT7** : jamais annoncé ni deviné |

## 2. Phase 1 : sessions et radio

### Radio (`RadioPacer`)

- **Bug corrigé** : une annonce différée était relâchée au bout de 6 s, même si le pilote restait occupé. Désormais :
  - elle attend 1,2 s de calme continu, c'est-à-dire une vraie ligne droite, pas un relâché entre deux virages ;
  - au plus deux annonces sortent à la fois ;
  - elle est annulée si elle devient périmée : 20 s pour une information, 40 s sinon, ou tour dépassé (`validUntilLap`).
- **Pilote occupé** : gros freinage, volant ou roues braqués, lacet ou accélération latérale forte, décélération de plus
  de 7 m/s², blocage ou patinage, deux roues hors piste.
- **Contradictions** : une annonce plus récente sur le même sujet remplace celle qui attendait. Dans une même salve,
  seule la dernière d'un sujet est gardée.
- **Urgences** : alertes, appels au stand, messages HIGH ou CRITICAL, réponses et questions passent toujours tout de
  suite.
- L'horloge est celle des paquets GT7 (60 par seconde), pas celle du téléphone.
- Bips, conversation continue et mot de réveil sont inchangés.

### Sessions

- **Rediffusion** d'une course archivée : les deux premiers chronos sont identiques à la milliseconde, avec la même
  voiture. Rien n'est alors archivé, appris ni annoncé.
- **Coupure de télémétrie** au passage de la ligne, ou tours sautés (téléphone en veille) : la consommation de ces
  tours reste inconnue. Plusieurs tours ne sont jamais comptés comme un seul.
- **Nombre de tours différent** : nouvelle session.
- **Inchangé depuis V2** : arrivée au tour 11/10, télémétrie figée, menus (tour négatif), pause, chargement.
- **Clôture** : à chaque fin de session, le profil de pilotage et le bilan sont archivés.

## 3. Phase 2 : Driver DNA et coaching par virage

- **Virages** (`analysis/Corners.kt`) :
  - reconnus sur un tour propre par la courbure (rayon de moins de 220 m sur au moins 15 m) et un ralentissement d'au
    moins 8 km/h ;
  - point de corde **estimé** au point de vitesse minimale, jamais présenté comme officiel ;
  - zone d'analyse de 220 m avant à 160 m après ;
  - le dernier virage est mesuré sur le début du tour suivant (sortie au-delà de la ligne).
- **Mesures par passage** : point et intensité du freinage, montée en pression, vitesses d'entrée, minimale et de
  sortie, point de réaccélération, blocage, patinage, TCS, écart de trajectoire.
- **Zone incomplète** (trou dans les données, échantillons à plus de 25 m du tracé) : virage non mesuré.
- **Référence personnelle** : médiane de tes trois meilleurs passages dans ce virage.
- **Cause** : elle n'est retenue que si elle se retrouve dans la majorité des passages perdants. Causes possibles :
  - freinage trop précoce ;
  - entrée trop rapide ;
  - vitesse minimale trop faible ;
  - réaccélération tardive ;
  - patinage en sortie ;
  - trajectoire.

  Sans cause claire, aucun conseil.
- **Perte sur tous les virages à la fois** (pneus, carburant, conditions) : ce n'est pas un défaut de pilotage, aucun
  conseil.
- **Entraînement** (Sport en entraînement, chrono, course personnalisée) : une consigne concrète, puis une vérification
  sur les trois passages suivants (« Virage 2 : c'est mieux, tu as repris environ 2 dixièmes. »).
- **Course Sport officielle** : phrase courte (« Virage 2 : remets les gaz plus tôt. Environ 3 dixièmes par passage. »).
  Une perte d'au moins 0,25 s, trois consignes au plus. Jamais avant le tour 3, ni à la fin de course, ni dans les 30 s
  qui suivent un changement de position.
- **Profil persistant** (`driver_dna.json`) :
  - un profil par voiture × circuit × type de session ;
  - une configuration de circuit différente donne un autre profil ;
  - il contient la carte, les derniers passages et les meilleurs, le style par session (régularité, blocages et
    patinages par tour, rythme en fin de relais, consommation) et le suivi des conseils ;
  - points forts et faibles seulement à partir de 6 passages sur au moins deux sessions.

## 4. Phases 3 et 4 : stratégie personnalisée, apprentissage

- **Briefing sur la grille**, une fois, pour les épreuves Sport A, B et C :
  - règlement (confirmé, non confirmé ou incomplet) et circuit identifié ;
  - courses comparables et consommation de référence, avec sa fiabilité ;
  - pneus de départ, si l'historique le permet ;
  - gain estimé du plan recommandé, avec son incertitude, ou « plusieurs plans se valent » ;
  - points à travailler, en entraînement seulement.
- **Recalculs** : le planificateur reste celui de V2, il compare le temps total jusqu'à l'arrivée, arrêts compris. Un
  arrêt repoussé d'un tour n'est annoncé que s'il se confirme au recalcul suivant. Un arrêt avancé, ajouté ou retiré est
  annoncé tout de suite.
- **Bilan après chaque course** (journal et IA) :
  - consommation prévue et mesurée ;
  - arrêts prévus et réalisés, changements de plan ;
  - temps perdu au stand, prévu et mesuré ;
  - positions de départ et d'arrivée (« GT7 ne dit pas pourquoi, aucun lien avec la stratégie n'est établi ») ;
  - consignes et leur effet.
- **Apprentissage** : une prévision de consommation tirée de l'historique est corrigée si l'erreur passée est nette
  (au moins 2 %) et répétée (au moins 2 courses), plafonnée à 8 %. La correction est dite dans le briefing.

## 5. Tests (427 tests, 0 échec)

| Cas demandé | Test |
|---|---|
| Règlements Sport différents | `StrategyV3Test.differentSportRulesGiveDifferentBriefings` |
| Historique insuffisant ou incompatible | briefing sans historique ; profil distinct si le tracé ne se superpose pas (`DriverDnaTest`) ; `CompoundPerformanceTest` |
| Reconnexion, coupure en pleine session | `SessionAndRadioV3Test.reconnectingMidSessionStartsCleanWithoutOldData`, `aTelemetryBlackoutNeverProducesAFalseConsumption` |
| Rediffusion | `aReplayOfAnArchivedRaceIsNeitherArchivedNorAnnounced` |
| Décision d'arrêt périmée | `contradictoryAndStaleAnnouncementsAreDropped`, `aStopPushedBackByOneLapMustBeConfirmedButAnEarlierStopIsImmediate` |
| Message pendant un gros freinage | `aMessageDuringHeavyBrakingWaitsForAStraightEvenBeyondSixSeconds`, `theBusyDetectorSeesBrakingRotationAndInstability` |
| Recommandations contradictoires | `contradictoryAndStaleAnnouncementsAreDropped` |
| Dégradation sans preuve suffisante | `eachCauseIsIdentifiedOnlyWhenTheTelemetryShowsIt` (perte générale : aucun conseil) ; tests pneus V2 |
| Changement de position sans données adverses | `raceModeIsShortRareAndNeverDuringABattle` ; `withoutOpponentDataNothingIsInvented…` |
| Virage à trajectoire incomplète | `anIncompleteTrajectoryIsNeverAnalysed` |
| Gemini indisponible | `withoutOpponentDataNothingIsInventedAndTheEngineWorksWithoutAnyAi` (aucune IA) ; `AiConversationTest` (réseau coupé) |

Les virages sont testés sur un circuit simulé (`CornerSim`) : lignes droites, épingle, freinage et réaccélération
réglables à chaque tour. Les causes détectées sont celles injectées.

## 6. Ce qui reste expérimental ou limité

- **Seuils** (8 m de freinage, 4 km/h de vitesse minimale, 10 m de réaccélération, 1,5 m de trajectoire) : choisis sur le
  simulateur, à valider sur PS5.
- **Rotation réelle / attendue** (sous-virage ou survirage probable) : archivée seulement, jamais dite.
- **Virages pris à fond** (sans ralentir) : non analysés.
- **Chicanes rapides** : peuvent être vues comme un seul virage.
- **Bataille en piste** : détectée seulement par un changement de position récent. GT7 ne transmet pas les voitures
  proches.
- **Rediffusion** : reconnue par des chronos identiques à la milliseconde. Une rediffusion qui commencerait au milieu de
  la course ne serait reconnue qu'à son deuxième tour complet. Les annonces du départ peuvent donc être dites.
- **Écrans** : aucun ajout. Le profil de pilotage est dans les réponses radio, le journal et les faits envoyés à l'IA.

## 7. Protocole PS5

1. **Entraînement Sport**, 8 tours propres sur un circuit connu. Au tour 5 ou 6, prends volontairement un virage avec
   une réaccélération tardive pendant 3 tours. Attendu : une seule consigne sur ce virage, puis « c'est mieux » quand tu
   corriges. Dans le journal : `Coaching : Virage N…`.
2. **Radio et freinage** : pendant l'enchaînement de virages le plus long, aucune annonce non urgente. Elle arrive dans
   la ligne droite suivante ou n'arrive pas (périmée). Les alertes carburant passent toujours.
3. **Course Sport officielle** : briefing sur la grille, puis au plus 3 consignes courtes. Aucune dans les 30 s qui
   suivent un dépassement.
4. **Après l'arrivée** : regarde la rediffusion. Aucune nouvelle session ne doit apparaître dans l'historique (journal :
   « Rediffusion … détectée »).
5. **Veille du téléphone** pendant 2 tours en course : à la reprise, la consommation par tour ne doit pas doubler.
6. **Bilan** : exporte le journal ; les lignes `Bilan :` donnent la consommation prévue et mesurée et le temps perdu au
   stand.
7. **Non-régression** : bips, conversation continue, Discord, écran éteint, Fold, Gemini, Tyre Change Hunter.
