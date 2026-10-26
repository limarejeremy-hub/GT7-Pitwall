# GT7 Pitwall — Ingénieur de course V5 (fiabilisation, profil de pilotage, bilan)

Branche : `claude/race-engineer-v5`, partie de `claude/consolidation-reference` (f83f93a).
Sources de l'audit : journal réel du 26/10 (`gt7-pitwall-course-1793026710934-2186`, Subaru WRX Gr.3, 14 tours)
et journal du 25/10 (Porsche 963, Le Mans). **Aucun essai PS5 n'a été fait sur cette branche** : tout ce qui suit est
vérifié par des tests JVM rejouant les phrases et séries réelles, pas en course.

## 1. Audit : causes trouvées dans le journal du 26/10

| Symptôme en course | Cause | Fichier |
|---|---|---|
| « medium » répondu à la question de la gomme, jamais enregistré, question reposée | La couche conversation jetait la réponse courte comme un fragment avant qu'elle n'atteigne le contrôleur | `ai/RadioConversation.kt` |
| « mediums » sans famille (course/sport) refusé | `compoundAnswer` exigeait la famille même quand les pneus actuels la donnent | `race/RaceController.kt` |
| Changement de pneus réel non détecté par le Hunter | Garde ajoutée exigeant que la voiture reste à l'arrêt après la remise à 60 °C ; au 26/10 elle repart 1 s après | `tyres/TyreChangeHunter.kt` |
| « j'ai changé les pneus mais pas fait le plein » compris comme une négation | La négation de la 2ᵉ proposition s'appliquait à toute la phrase | `radio/CommandGate.kt` |
| « Carburant pour 30,9 tours, marge de 18,9 » répété à chaque tour | Phrase carburant dans chaque bilan de tour en mode détaillé | `engineer/RaceEngineer.kt` |
| « puisque mes tours sont bons » → « Tour 10 sur 14 » | Le mot « tours » classait la question en avancement de course | `radio/DriverCommands.kt` |
| Circuit Le Mans (13,6 km) gardé avec des tours de 1:20, 10 tours au lieu de 14 | Règles d'une course personnalisée du 25/10 restées dans le profil ; l'apprentissage du circuit était sauté dès qu'un circuit était enregistré | `race/RaceController.kt` |
| Coaching muet sans explication | Aucun compteur des tours écartés ni des raisons | `engineer/DriverCoach.kt` |

## 2. Corrections

- **Pneus** : une réponse qui répond à la question en attente est routée directement vers le contrôleur ; la famille
  est complétée depuis les pneus actuels ; corrections (« non, des tendres ») remplacent sans doubler le changement ;
  phrases naturelles (« j'ai gardé les mêmes pneus », « j'ai mis des mediums »).
- **Hunter** : après la remise à 60 °C, seule la continuité et l'uniformité des quatre roues sont exigées.
- **Carburant** (`FuelRadio`) : « Essence suffisante pour l'arrivée » une seule fois ; alerte quand la marge à
  l'arrivée passe à 2 tours ou moins (réarmée au-dessus de 2,5 tours, hystérésis) ; alerte « insuffisant » une fois
  par entrée dans ce niveau, sauf si un arrêt carburant est prévu ; remise à zéro à chaque course. Les alertes de
  niveau absolu (moins de 2 tours, moins d'un tour) restent celles de l'ingénieur.
- **Questions de pilotage** (`DrivingAnalysis`) : « mes tours sont bons ? », « où je perds du temps ? », « je freine
  trop tôt ? », « je progresse ? », « qu'est-ce que je dois changer ? », « qu'as-tu appris ? » ont leur propre
  réponse, calculée sur les tours propres (tour 1, arrêts et tours à plus de 7 % du meilleur écartés). Sans mesure
  par virage, la réponse le dit au lieu d'affirmer une cause.
- **Cohérence circuit** : distance du tour à ±15 % de la longueur du circuit et vitesse moyenne sous la vitesse de
  pointe ×1,05 ; sinon le circuit enregistré est retiré, le journal note « Circuit incohérent » et la radio le dit une
  fois. Le journal de début de session donne `tours_gt7` et `tours_reglement` séparément.
- **Règlement hebdomadaire** : une fiche corrigée par la source en cours de semaine garde les champs saisis par le
  pilote ; un profil confirmé n'est jamais réécrit dans la semaine ; les désaccords entre sources restent affichés
  (« Sources en désaccord »).

## 3. Nouvelles fonctions opérationnelles

- **Diagnostic du profil de pilotage** (écran « Pilotage ») : tours analysés, tours écartés par raison, dernier
  enregistrement, dernière erreur, constats par virage. Les erreurs internes sont tracées une fois par module.
- **Coaching sans répétition** : un conseil de virage non suivi d'amélioration n'est plus répété tel quel.
- **Bilan de fin de course** (`DrivingReport`) : places, meilleur tour, rythme et régularité, tour théorique (somme
  des meilleurs secteurs, présenté comme indicatif), secteurs, avant/après arrêt, tendance blocages/patinages,
  températures, pneus, conseils suivis, une seule priorité. Synthèse radio « Bilan : … », bilan complet dans le
  journal, l'historique (`SessionRecord.report`) et l'écran. Moins de 4 tours propres : « Pilotage non jugé ».
- **Base de calibration du Hunter** (`TyreCalibrationSetTest`, `tyre_calibration/*.series.json`).

## 4. Base de calibration des pneus

| Exemple | Nature | Étiquette | Verdict |
|---|---|---|---|
| Le Mans 963, tour 4 (25/10) | réel | changé | SUSPECTED ✔ |
| WRX Gr.3, tour 8 (26/10) | réel | changé | SUSPECTED ✔ |
| 2 × refroidissement sans remise à 60 °C | synthétique | conservé | rien ✔ |
| 2 × coupure UDP au moment du saut | synthétique | conservé | rien ✔ |
| 2 × saut voiture en mouvement | synthétique | conservé | rien ✔ |

Faux négatifs 0/2, faux positifs 0/6 — **sur des contre-exemples synthétiques**. Il manque des arrêts réels
« pneus conservés » : la détection reste une suspicion, la confirmation reste au pilote.

## 5. Comparaison open source (section 10)

Lecture des README et licences seulement ; aucun code repris.

| Projet | Licence | Ce qu'il fait de plus | Ce que Pitwall en retient |
|---|---|---|---|
| jbhoorasingh/gt7-datalogger | MIT | Analyse par virage (entrée/min/sortie), comparaison multi-tours, identification de 78 tracés | Idée : bulletin par virage dans le bilan quand le profil a les mesures |
| jbhoorasingh/gt7-datalogger-track-data | CC0 | Bords de piste relevés, secteurs et virages nommés | Données réutilisables librement ; intégration possible plus tard (non faite) |
| ransh2014/gt7telemtrace (TRACE) | MIT | Tour théorique par micro-secteurs, score de régularité, tours à +7 % écartés | Même règle des 7 % adoptée ; le tour théorique Pitwall est par secteurs (plus grossier) |
| snipem/gt7dashboard | GPL-3.0 | Tableau de bord web, comparaison au meilleur tour | Rien de copié (GPL incompatible avec une reprise de code) |
| aleon80/gran-turismo | MIT | 20 zones par tour : freinage tôt/tard, vitesse mini, sortie | Les causes ne sont dites qu'avec une mesure par virage, comme ici |

Différence de Pitwall : tout tourne sur le téléphone, à la voix, pendant la course, sans PC ; aucun de ces projets ne
gère la stratégie pneus/carburant parlée ni le règlement Sport hebdomadaire.

## 6. Tests et build

- `core` : 496 tests JVM, 0 échec (dont `RealRace2610Test`, `FuelRadioTest`, `DrivingQuestionsTest`,
  `DrivingReportTest`, `TyreCalibrationSetTest`, `EditionPreservesPilotRulesTest`).
- APK : workflow « APK Android » (CI GitHub), artefact `GT7-Pitwall-debug`.

## 7. Limites et essais PS5 à faire

1. Course Sport : répondre « medium » à la question de la gomme → enregistrée sans nouvelle question.
2. Arrêt avec changement de pneus et sans essence → suspicion Hunter puis question.
3. Carburant : une seule annonce « suffisante », puis silence ; course juste en carburant → alerte unique.
4. Demander « mes tours sont bons ? » et « où je perds du temps ? » en course.
5. Course personnalisée puis course Sport : le circuit de la course précédente doit être écarté s'il ne colle pas.
6. Fin de course : bilan radio court, écran Pilotage rempli.
7. Arrêt sans changer les pneus : il faut des exemples réels pour la base de calibration.

## 8. Compatibilité

Branche partie de `claude/consolidation-reference` ; le format JSON des sessions ajoute `report` (lu vide s'il
manque) ; aucun autre format persistant modifié. Les PR historiques ne sont pas fusionnées.
