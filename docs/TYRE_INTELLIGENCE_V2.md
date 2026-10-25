# Tyre Intelligence V2

Branche `claude/tyre-intelligence-v2`, partie de `claude/gt7-ia-conversation`. Priorité : détecter de façon fiable un
changement de pneus et connaître l'âge du relais. Ensuite seulement : la performance par gomme et la stratégie.

Principe : **aucune donnée inventée**. GT7 ne transmet ni la gomme montée, ni l'usure, ni le changement de pneus. Les
températures ne font que **suspecter** un changement. L'âge des pneus ne repart de zéro qu'après la confirmation du
pilote. Aucun pourcentage d'usure n'est jamais annoncé.

## 1. Audit de l'existant

| Composant | Ce qu'il faisait | Ce qui manquait ou posait problème |
|---|---|---|
| `PitDetector` / `SessionTracker` | Passage aux stands classé `REFUEL`, `PIT_LIKE_STOP` (immobile dans la voie, sans essence) ou `TRACK_STOP`. Session réinitialisée si le tour recule ou si la voiture change | Rien sur les pneus : un arrêt pneus seuls sans obligation au règlement passait inaperçu |
| `PitLedger` | Arrêts avec preuve ; changements de pneus **déclarés seulement** ; `TyreService` (changés, conservés, inconnu) ; `tyreStint` avec certitude | Rien à corriger : réutilisé tel quel, avec un champ `thermal` en plus |
| `TelemetryPacket` | Températures des 4 pneus (0x60, 0x64, 0x68, 0x6C) lues mais seulement affichées | Aucune exploitation au stand |
| `TyreModel` | Pente par tour d'âge, durée performante, gain des pneus neufs, confiance | Pente non corrigée du carburant (l'allègement masque la dégradation) ; tours de sortie de piste et tours commencés dans la voie gardés ; gain des pneus neufs mélangé entre gommes |
| `GripDiagnosis`, `TyreCoach` | Signes d'usure mesurés (glisse, chronos) | Réutilisés, inchangés |
| `StrategyOptions` | Moment du changement de pneus (maintenant, au prochain tour, au ravitaillement, rester en piste) | Aucune comparaison **entre gommes** |
| `RaceController` | Question « As-tu changé les pneus ? » après chaque ravitaillement | Gomme demandée seulement si le règlement en impose ; la question sur la gomme **bloquait le recalcul du plan** |
| `SessionJournal` | Filtre anti-secrets sur les détails exportés | Une longue suite de lettres (série de phases « AAAA…SSSS… ») était **masquée comme un jeton** : corrigé côté export (format par plages), le filtre n'est pas affaibli |

Aucun système concurrent n'a été créé : le Hunter alimente le `PitLedger` existant, et le modèle par gomme réutilise
`TyreModel` et les coûts d'arrêt de `HistoryModel`.

## 2. Tyre Change Hunter (`tyres/TyreChangeHunter.kt`)

- **Fenêtre** : 20 s avant le passage (anneau), tout le passage, 15 s de sortie. Une mesure par seconde : 4 températures,
  vitesse, carburant.
- **Recherche** : à chaque instant de l'arrêt, moyenne des 3 mesures d'avant contre les 3 d'après, pour chaque pneu.
- **Trois états** :
  - **suspecté** : saut d'au moins 10 °C sur **les quatre** pneus, de même sens, et **durable** (chaque mesure suivante
    garde au moins 70 % du saut) ;
  - **non déterminé** : saut trop faible, pneus qui ne varient pas ensemble, pic isolé, valeurs aberrantes (hors de
    −20 à 250 °C, NaN), mesures insuffisantes, ou arrêt en piste ;
  - **confirmé** : seulement par le pilote.
- Le seuil de **10 °C est expérimental** : il est écrit dans chaque export (`hunter_seuil_c`).
- **Jamais analysé** : arrêt en piste, variation en roulant, passage après l'arrivée (tour 11/10), pause, chargement,
  transition de session.
- **Calibration** (`TyreHunterCalibration`) : chaque réponse du pilote étiquette le diagnostic. La fiabilité ne serait
  « démontrée » qu'avec 3 changements **et** 3 non-changements étiquetés, sans aucune erreur. Même alors, la
  confirmation automatique **reste désactivée** dans cette version.

### Communication

| Situation | Radio |
|---|---|
| Plein + saut thermique | « Arrêt N enregistré : ravitaillement de … Les quatre pneus ont changé de température d'un coup : pneus neufs ? » |
| Plein, températures stables | « … As-tu changé les pneus ? » |
| Arrêt sans essence + saut thermique | « Arrêt de N secondes sans ravitaillement, et les quatre pneus ont changé de température d'un coup. Tu as changé les pneus ? » |
| « oui » | « Pneus neufs confirmés : je repars sur un nouveau relais. Tu as monté quelle gomme ? » |
| « non » après un plein | « Plein terminé, on conserve le même train de pneus. » |
| « non » à la gomme | « D'accord, gomme non précisée : je ne la devine pas. » |

Chaque question n'est posée qu'une fois et expire au bout d'une minute. Une réponse répétée ne crée rien de plus. Gemini
peut reformuler, mais les faits viennent du moteur (`aiFacts`).

### Âge du train de pneus

- 0 au départ (pneus neufs supposés), +1 par tour bouclé.
- Remis à zéro **uniquement** sur un changement confirmé. Conservé lors d'un simple ravitaillement.
- Arrêt sans réponse sur les pneus : âge **incertain**, affiché (« âge incertain » sur l'écran, « Âge incertain : un
  arrêt est resté sans réponse sur les pneus » à la radio) et archivé (`tyreAgeCertain` sur chaque tour).
- Nouvelle session : registre vidé, rien n'est importé d'une autre session.

## 3. Export (journal de course, JSON et texte)

Par passage, trois événements :

1. `DECISION` « Tyre Change Hunter, tour N : verdict » : `hunter_verdict`, `hunter_saut_c`, et le statut « indice
   expérimental » ;
2. `PIT` « Tyre Change Hunter, séries du tour N » : `hunter_verdict`, `hunter_raison`, `hunter_seuil_c`,
   `hunter_type_passage`, `hunter_saut_c` (4 écarts en °C), `hunter_saut_seconde`, `hunter_saut_durable`, `phases` (par
   plages, par exemple `A20 S41 E15`), puis `avg`, `avd`, `arg`, `ard`, `kmh` et `carburant` (une valeur par seconde,
   une décimale) ;
3. `PIT` « étiquette du pilote » : `hunter_etiquette` (`pneus_changes` ou `pneus_conserves`), `origine_etiquette`, le
   verdict et le saut.

Le verdict est aussi archivé sur l'arrêt (`thermal`). Le champ est facultatif : les historiques existants se relisent
sans changement.

## 4. Performance par gomme et scénarios (`tyres/CompoundStrategy.kt`)

- **Données** : courses comparables seulement (même épreuve, voiture, circuit, multiplicateur d'usure). Seuls les tours
  propres comptent : pas le premier tour, ni un ravitaillement, un tour d'entrée ou de sortie des stands, une sortie de
  piste, un incident déclaré, ou un chrono à plus de 7 % de la médiane (trafic, erreur). Les tours dont l'âge des pneus
  est incertain sont exclus.
- **Régression** : chrono = niveau du relais + pente de la gomme × âge + k × carburant, avec effets fixes par relais.
  - k n'est retenu que s'il est **séparable** de l'âge, c'est-à-dire si l'historique contient des pleins sans changement
    de pneus. Sinon, la pente brute est gardée et signalée « effet du carburant non séparé ».
  - Sur un historique synthétique (vraies valeurs : 30 ms par unité, pentes de +0,50, +0,25 et +0,10 s), le modèle
    retrouve ces valeurs. Sans plein sans changement, il ne corrige rien et le dit.
- **Par gomme** : rythme des pneus jeunes (1 à 3 tours), perte par tour d'âge, durée compétitive et gain des pneus neufs
  (`TyreModel`), avec un niveau de confiance.
- **Scénarios** : continuer, tendres, mediums, durs (dans la famille de la gomme actuelle).
  - Calcul sur le temps total jusqu'à l'arrivée, arrêt compris. Changer pendant un arrêt déjà prévu ne coûte que le
    changement de pneus ; sinon, c'est un arrêt pneus seuls.
  - Le meilleur tour d'arrêt est cherché pour chaque gomme. Les gommes autorisées et imposées sont respectées, ainsi que
    la durée de vie donnée par le pilote.
  - Incertitude = mesures + erreur de modèle (0,15 s par tour sur la nouvelle gomme : trafic, dégradation non linéaire).
  - Recommandation seulement si l'écart dépasse deux fois l'incertitude et au moins 1 s. « Changer » peut être conseillé
    sans désigner une gomme, quand les gommes neuves restent indiscernables.
  - Sinon : « Pas assez d'historique comparable pour chiffrer les gommes » ou « … inférieur à l'incertitude : aucun
    scénario n'est démontré meilleur ».
  - Aucune comparaison si la gomme actuelle est inconnue ou si l'âge est incertain.

## 5. Tests (405 tests, 0 échec)

`TyreIntelligenceTest` couvre les 10 cas obligatoires, et un onzième sur l'âge incertain :

1. Ravitaillement sans changement : âge conservé, « Plein terminé, on conserve le même train de pneus. »
2. Ravitaillement avec changement : suspecté, âge inchangé avant le « oui », puis remis à zéro.
3. Pneus sans ravitaillement : proposé grâce à la signature thermique, rien de compté sans « oui ». Témoin : sans
   signature ni obligation, rien n'est proposé.
4. Signature hors stands (en roulant, et arrêté en piste) : aucun changement, aucun arrêt.
5. Températures aberrantes : pic d'une seconde, valeurs absurdes, chute de 7 °C, un seul pneu → non déterminé. Une vraie
   chute durable sert de témoin. Pic réel pendant un plein simulé : aucun faux positif.
6. Gomme inconnue : demandée une fois, jamais devinée, puis la question expire.
7. Historique insuffisant : aucune dégradation apprise, aucun pourcentage, « Pas assez d'historique comparable ».
8. Course terminée puis nouvelle session : passage au tour 7/6 ignoré, relais non mélangés, deux sessions archivées.
9. Deux arrêts réels (plein + pneus, puis plein seul) : registre `CHANGED` puis `NOT_CHANGED`, un seul changement, âge
   cohérent.
10. Aucun doublon : une annonce, une question et une étiquette par passage ; séries exportées complètes et non masquées.
11. Arrêt sans réponse : âge incertain à la radio et dans l'historique archivé.

`CompoundPerformanceTest` (5 tests) : séparation carburant/âge, refus de corriger sans données, historique incompatible,
temps total exact du scénario « continuer », règles respectées, gommes sans historique jamais chiffrées.

Non-régression : toute la suite existante passe. Un test a été adapté à la nouvelle phrase demandée (« Pneus neufs
confirmés »).

## 6. Limites

- Le seuil de 10 °C et la règle des 70 % ne sont **validés sur aucun arrêt réel** : les températures de surface
  transmises par GT7 au stand restent à observer. Si GT7 ne fait pas varier ces températures au changement, le Hunter
  répondra « non déterminé » et le pilote confirmera, comme avant.
- Des pneus neufs chauffés au même niveau que les anciens ne produiraient aucun saut : faux négatif possible, d'où la
  question systématique.
- Le trafic n'est pas mesuré : il n'est écarté que par le filtre des chronos aberrants et compté dans l'incertitude.
- L'effet du carburant n'est séparable qu'avec des pleins sans changement de pneus dans l'historique.
- Les scénarios supposent une dégradation linéaire par tour d'âge.
- L'application Android n'est compilée qu'en CI (carte Pneus : verdict du dernier arrêt et conclusion des scénarios).

## 7. Protocole de validation PS5

Base : la course de référence (Corvette C7 Gr.4, Dragon Trail Gardens, 10 tours, usure ×8, carburant ×10). Exporter le
journal après chaque course.

1. **Arrêt 1, plein et pneus neufs** : attendu « … pneus neufs ? ». Réponds « oui ». Dans l'export : `hunter_verdict`
   `SUSPECTED` (ou `UNDETERMINED`, à noter, ce n'est pas un bug), étiquette `pneus_changes`, âge à 0 au tour suivant.
2. **Arrêt 2, plein seul** : attendu « As-tu changé les pneus ? ». Réponds « non » → « Plein terminé, on conserve le
   même train de pneus. ». Dans l'export : `UNDETERMINED`, étiquette `pneus_conserves`, âge qui continue.
3. **Pneus seuls sans essence** (course d'essai) : attendu « … sans ravitaillement, et les quatre pneus ont changé de
   température d'un coup. Tu as changé les pneus ? ». Si rien n'est dit, envoie l'export : les séries montreront si
   GT7 fait varier les températures.
4. **Arrivée puis menus** : reste sur l'écran des résultats, puis repasse par le stand ou relance une course. Aucun arrêt
   ni changement de pneus ne doit apparaître après l'arrivée. L'historique contient une session par course.
5. **Calibration** : répète au moins 3 arrêts avec changement et 3 sans. Envoie les exports : la fiabilité sera évaluée
   sur ces étiquettes avant toute idée de confirmation automatique.
6. **Gomme** : à « Tu as monté quelle gomme ? », réponds « course mediums ». Ensuite, la question « au prochain arrêt je
   mets quoi comme pneus ? » doit donner les chiffres de l'historique, ou dire qu'il est insuffisant.
7. **Non-régression** : bips, conversation continue, Discord, écran éteint, Fold, Gemini, positions et carburant
   fonctionnent comme avant.
