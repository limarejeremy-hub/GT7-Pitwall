# Ingénieur stratégique : jalons

## Audit (avant le jalon A)

- **`RaceController`** : point d'entrée unique (paquets, boutons, voix, écran). Il est réutilisé, sans moteur
  concurrent.
- **`RaceRules`** : le règlement est saisi par le pilote. Jusqu'ici, **un seul** règlement servait pour toutes les
  épreuves.
- **`CarTrackMemory`** : consommation (ramenée à ×1) et rythme par voiture × circuit, mis à jour à chaque tour, **sans
  distinguer** Sport A, B, C, qualification et course. Il y avait donc un risque de contamination.
- **`RacePlanner`** : nombre d'arrêts minimal, relais équilibrés et plan stable. Il propose **un seul** plan, sans
  comparer d'alternatives.
- **`PitLedger`** : arrêts prouvés (ravitaillement) ou déclarés par le pilote, gommes déclarées.
- **`CommandGate`** : rien ne modifie l'état de course sans validation.
- **Archive** : **aucune** session n'était conservée. Les tours disparaissaient à la fermeture de l'application.

## Jalon A : livré

- **Profils d'épreuve** (`ProfileBook`) : Sport A, B, C ou autre, en qualification ou en course.
  - Chaque profil garde **son** règlement. La qualification et la course d'une même série partagent seulement le
    circuit.
  - Version de GT7 et contexte réglages / BoP déclarés.
  - Règlement **à confirmer**. Toute modification retire la confirmation, et un rappel radio est fait une fois par
    session.
- **Migration sans perte** :
  - l'ancien règlement devient le profil « Autre, course » ;
  - l'ancienne mémoire voiture × circuit y reste rattachée ;
  - les nouvelles mesures sont rangées par épreuve, et écartées si la version de GT7 déclarée diffère.
- **Archive des sessions** (`SessionRecord`), enregistrée à chaque tour, un fichier JSON par session :
  - **mesures** : tours, chronos, consommation, ravitaillements ;
  - **déclarations** : pneus, notes du pilote, arrêts déclarés ou confirmés ;
  - **plan prévu et ses changements** ;
  - **issue** : terminée ou interrompue. Une session laissée « en cours » par une fermeture brutale est close au
    lancement suivant ; les tours après l'arrivée sont exclus.
- **Comparabilité** (`Compatibility`) :
  - comparables : même épreuve, même type de session, même circuit, même voiture, mêmes multiplicateurs, même version
    si elle est déclarée des deux côtés ;
  - « même règlement » en plus : mêmes tours, arrêts et gommes imposées.
- **Radio figée** :
  - Vosk sur le casque PS5 ;
  - Google n'est plus lancé sur le flux du casque (réglage expérimental, désactivé par défaut) ; il reste utilisé sur
    le micro du Fold ;
  - transcription libre de Vosk toujours active ;
  - banc d'essai rangé dans les « Outils de développement », masqués et arrêtés par défaut.

## Jalon B1 : programme hebdomadaire automatique (livré)

### Sources vérifiées (octobre 2026)

| Source | Accès | Contenu | Utilisée |
|---|---|---|---|
| Polyphony Digital / gran-turismo.com | aucune API publique des courses quotidiennes ; le manuel décrit seulement le format | — | non |
| **GTPlanet**, flux RSS des courses quotidiennes | public, autorisé par robots.txt, conditions sans interdiction | fiche structurée par course : circuit et tours, voitures, BoP, pneus, réglages, départ, arrêt obligatoire, multiplicateurs carburant et pneus | **oui** |
| DG EDGE | protégé par Cloudflare (contrôle anti-robots) | — | non (pas de contournement) |
| gtsh-rank.com | robots.txt autorise `/daily/`, page avec script de « protection du contenu » | pneus en codes, multiplicateurs, arrêts, pas de nombre de tours | non par défaut |
| gt7info (ddm999) | données des courses quotidiennes abandonnées (« placeholder ») | — | non |

Sans source officielle, tout ce qui vient d'une seule source est **probable**, à confirmer d'une touche. Deux sources
concordantes donneraient « confirmé », discordantes « contradictoire » (non appliqué) : l'architecture le gère déjà.

### Fonctionnement

- **Lecture** : le flux est lu au lancement, au changement de semaine (le lundi et le mardi, toutes les 30 min tant
  que la nouvelle semaine manque) et à la demande, avec un cache local. Une erreur réseau ou un changement de format
  garde le dernier programme valide.
- **Extraction** : seules les lignes « Libellé : valeur » de la fiche sont lues, jamais la prose. Une valeur
  illisible reste inconnue.
- **Édition** : une par course et par semaine (identifiant = série + lundi de validité), avec une empreinte pour
  détecter une correction de la source.
- **Nouvelle semaine** : rien de la précédente n'est recopié. Elle n'est jamais appliquée en pleine course, seulement
  à la fin de la session.
- **Ce qui est rempli** :
  - en course : circuit (s'il désigne sans ambiguïté un tracé de la base GT7), tours, multiplicateurs, arrêts
    obligatoires, pneus autorisés et imposés ;
  - en qualification : circuit et pneus seulement.
- **Statut de chaque champ** : confirmé, probable, saisi par toi, inconnu ou contradictoire. Les obligations inconnues
  sont signalées, avec une saisie rapide.

### Limites

- Une seule source exploitable : les informations restent « probables » tant que le pilote ne les confirme pas.
- Si GTPlanet ne publie pas la fiche, ou change son format, l'épreuve passe en saisie manuelle et l'application le
  dit.
- Les voitures autorisées sont reprises en texte : elles ne sont pas vérifiées par rapport à la voiture détectée.

## Jalon B2 : moteur stratégique (livré)

- **Carburant** : GT7 ne transmet qu'une capacité normalisée (100, 5 en kart, 0 en électrique). Aucune documentation
  ne prouve des litres : tout est exprimé en **pourcentage du réservoir** et en tours d'autonomie (« unités GT7 » sans
  capacité). Un test vérifie qu'une course entière ne parle jamais de litres.
- **Apprentissage** (`HistoryModel`) : à partir des seules courses comparables, sur les tours propres (sans
  ravitaillement, tour d'arrêt ou de sortie, premier tour, incident déclaré ni valeur aberrante), Pitwall apprend :
  - consommation et dispersion ;
  - rythme ;
  - dérive des chronos au fil d'un relais, seulement si elle est mesurée et significative ;
  - perte par arrêt ;
  - erreur des prévisions passées.
- **Confiance** :
  - historique insuffisant : moins de 3 mesures ;
  - estimation relativement fiable : au moins 3 courses, 15 mesures et 5 % de dispersion au plus ;
  - estimation provisoire : entre les deux.
- **Trois stratégies** (`StrategyOptions`) : normale, arrêt anticipé, relais prolongé.
  - Elles sont calculées par `RacePlanner` ; une variante n'est valable que si `RacePlanner` la juge tenable
    (carburant, pneus, obligations).
  - Temps estimé, écart et incertitude ; aucun gain d'undercut ou d'overcut.
  - Si les écarts sont inférieurs à l'incertitude, les stratégies sont annoncées comme indiscernables.

## Jalon B3 : intégration dynamique (livré)

- **À chaque tour**, le plan et la comparaison sont recalculés. Une alternative ne remplace le plan que si elle reste
  recommandée d'un tour sur l'autre, ou si son gain dépasse deux fois l'incertitude.
- **Chaque changement de plan est expliqué** par ce qui a été mesuré, par exemple : « La consommation mesurée
  remplace l'estimation… Un arrêt devient nécessaire… ». Le changement est gardé et archivé avec le plan.
- **Écran** : section « Plan recommandé » avec le prochain arrêt, le carburant en pourcentage, les pneus, les trois
  stratégies, l'incertitude, la raison et les données utilisées.
- **Radio (préparation du jalon C)** : `StrategyBrief` donne des réponses déjà calculées, transmises à l'IA comme des
  faits :
  - stratégie recommandée et pourquoi ;
  - prolonger d'un tour ;
  - si d'autres voitures rentrent, sans rien inventer sur elles ;
  - carburant pour finir ;
  - dernier changement de plan.

## Jalon C : ingénieur de course conversationnel (livré)

### Fonctionnement

- **Aiguillage** : `RadioConversation.onUtterance` passe d'abord la phrase à `StrategyDialogue.classify`. Une question
  stratégique ou une observation est répondue par le moteur (`RaceController.strategyAnswer`), jamais par Gemini.
- **Commandes inchangées** : les commandes qui changent l'état (« j'ai fait mon arrêt », règlement, pneus) gardent
  leur chemin, avec CommandGate et StatementGuard.
- **Aucun enregistrement sur une question** : ni arrêt, ni pneus, ni règlement. Une hypothèse ou une intention
  (« je vais prolonger d'un tour ») est notée comme intention et annoncée comme telle.
- **Questions reconnues** (Vosk ne transcrit pas de « ? », la tournure suffit) :

| Exemple | Réponse calculée à partir de |
| --- | --- |
| « Quelle est notre meilleure stratégie ? » | recommandation et raison de `StrategyReport` |
| « Pourquoi on rentre au tour 5 ? » | prochain arrêt du plan : carburant, obligation, gomme. Si le tour cité est faux, il est corrigé |
| « On peut prolonger d'un tour ? » | option « relais prolongé » : faisable ou non, gain ou non |
| « On peut avancer l'arrêt ? » | option « arrêt anticipé » |
| « Devant moi ils rentrent, on fait quoi ? » | notre plan seulement : GT7 ne transmet rien sur les adversaires |
| « Combien de carburant pour finir ? » | carburant pour finir, en % du réservoir ou en unités GT7 |
| « Qu'est-ce qui a changé depuis le plan initial ? » | premier plan archivé, plan actuel, raison du changement, observations |
| « Pourquoi ? », « tu es sûr ? » (dans les 45 s) | données utilisées par la comparaison |

- **Observations du pilote** (adhérence avant ou arrière, pneus qui fatiguent, trafic, dégâts) :
  - gardées 5 tours, transmises à l'IA comme « déclaré par le pilote (non mesuré) » ;
  - confrontées aux chronos mesurés du relais ;
  - la stratégie n'est recalculée que sur les mesures, le trafic ne change jamais le plan ;
  - une négation (« je ne perds pas l'avant ») ne mémorise rien.
- **Pente des chronos du relais en cours** : si au moins 4 tours propres montrent une dégradation significative, elle
  entre dans la comparaison des stratégies.
- **Sans télémétrie** : « Conseil général, sans télémétrie : … », sans aucun chiffre de course.
- **Phrase mal reconnue** sur un sujet stratégique : l'ingénieur demande de préciser.

### Protocole de test PS5 (environ 10 minutes)

Préparation : épreuve Sport choisie (A, B ou C), Discord connecté, casque PS5, Vosk. Course d'au moins 8 tours
avec un arrêt carburant nécessaire, ou une course personnalisée en consommation ×2 ou plus.

1. **Avant le départ** (au stand, sans télémétrie) : « Radio, quelle est notre meilleure stratégie ? »
   Attendu : « Conseil général, sans télémétrie… », aucun chiffre.
2. **Tour 3 ou 4** :
   - « Quelle est notre meilleure stratégie ? » : stratégie, tour d'arrêt, % du réservoir ;
   - « Pourquoi ? » : les données utilisées ;
   - « Pourquoi on rentre au tour 2 ? » : le vrai tour d'arrêt, et la correction.
3. **Même tour** :
   - « On peut prolonger d'un tour ? » : oui ou non, avec la raison ;
   - « On peut avancer l'arrêt ? ».
4. « Devant moi ils commencent à rentrer, on fait quoi ? »
   Attendu : aucune position ni écart inventé, notre plan.
5. « Je perds de l'adhérence à l'avant. »
   Attendu : « Noté… d'après toi », confrontation aux chronos. **Vérifier à l'écran qu'aucun changement de pneus
   n'est enregistré.**
6. « Combien de carburant pour finir ? »
   Attendu : des %, jamais de litres.
7. « Je vais prolonger d'un tour. »
   Attendu : « Noté comme intention… ». **Vérifier qu'aucun arrêt n'est compté.**
8. Faire l'arrêt réel, puis :
   - « Qu'est-ce qui a changé depuis notre plan initial ? » ;
   - « Combien de carburant pour finir ? ».
   Attendu : un seul arrêt compté, celui détecté.

À noter pour chaque étape : la phrase reconnue (« Toi : … » à l'écran), la réponse, le délai ressenti, et toute
réponse fausse ou inattendue. Les tests GitHub vérifient la logique sur des courses simulées ; seule cette séance dit
si la reconnaissance du casque et le délai conviennent en course.

## Jalon D : pneus, endurance et entraînement (livré)

### D0 — Types de session

- **Quatre types**, choisis dans la carte Course et rappelés en haut de la carte des tours :
  - Sport officiel ;
  - entraînement Sport (hors ligne) ;
  - course personnalisée ;
  - qualification / chrono.
- **Ce qui est séparé** : le type fait partie de l'identifiant d'épreuve (`ProfileId.mode`). Règlement, historique,
  mémoire et archives sont donc séparés : un entraînement n'est jamais archivé comme Course Sport officielle.
- **Entraînement** : il reprend le règlement de la semaine comme modèle, jamais confirmé d'office. Le rappel radio
  demande de vérifier les réglages de la course hors ligne.
- **Réutilisation de l'historique** (`HistoryRelation`) :
  - un entraînement ne sert à une Course Sport que s'il la reproduit exactement (même règlement, voiture, circuit,
    multiplicateurs), et la confiance ne dépasse jamais « provisoire » ;
  - les sessions archivées avant ce jalon sont « type non précisé » et traitées de la même façon ;
  - course personnalisée et chrono ne se mélangent avec rien.

### D1 — Pneus et carburant indépendants

- **Défaut corrigé** :
  - un ravitaillement remettait à zéro le suivi d'usure (`TyreCoach.pitStop` sur `RefuelDetected`) ;
  - la dégradation était mesurée depuis le dernier ravitaillement ;
  - la comparaison des stratégies supposait des pneus neufs à chaque arrêt.
- **Relais pneus** (`PitLedger.tyreStint`) : il ne suit que les changements déclarés.
- **Chaque arrêt note ce qui a été fait aux pneus** : changés, mêmes pneus, ou non précisé.
  - « As-tu changé les pneus ? » est demandé après chaque arrêt.
  - Sans réponse, la question expire au bout d'une minute et l'âge des pneus devient incertain : jamais « neufs » par
    défaut.
- **Archives** : chaque tour garde l'âge, la gomme et la certitude du relais pneus, ainsi que les mesures d'adhérence
  (TCS, blocages et patinages par essieu, températures des quatre pneus).

### D2 — Diagnostic d'adhérence (`GripDiagnosis`)

- **Cinq niveaux** :
  - données insuffisantes ;
  - comportement stable ;
  - dégradation possible ;
  - dégradation persistante ;
  - perte d'adhérence importante.
- **Zones** : avant, arrière, gauche / droite, traction, freinage.
- **Tour lent isolé** : reconnu comme erreur ou incident, pas comme usure.
- **Toujours cité** : les autres causes possibles (trafic, erreurs, pneus froids, dégâts déclarés, âge incertain).
- **Températures** : jamais une preuve. « Tu peux attaquer » est remplacé par une simple information
  (« températures stabilisées »).

### D3 — Apprentissage des relais (`TyreModel`)

- **Relais retenus** : seulement ceux qui sont comparables (même type de session, voiture, circuit, usure, gomme
  déclarée).
- **Ce qui est appris** :
  - la dégradation par tour d'âge ;
  - les tours avant une perte nette (+0,7 s) ;
  - le gain des pneus neufs ;
  - l'essieu qui souffre.
- **Confiance** : insuffisant sous 2 relais ; relativement fiable à partir de 4 relais sur 3 courses peu dispersés.
  Gomme non déclarée ou entraînement : provisoire au mieux.

### D4 — Stratégie

- **Même moteur** (`StrategyOptions`, `RacePlanner`), pas de second moteur.
- **Options comparées** :
  - pneus à la fin de ce tour ;
  - pneus au prochain tour ;
  - pneus au meilleur tour ;
  - pneus au ravitaillement ;
  - garder les pneus.
- **Calcul du temps** : l'âge des pneus ne repart de zéro qu'aux arrêts où ils sont changés.
- **Seuil pour un arrêt de plus** : il n'est retenu que si son gain dépasse deux fois l'incertitude et au moins 2 s.
- **Changement de pneus au ravitaillement** : son temps est inconnu, une marge prudente de 10 s est comptée et
  annoncée dans les données.
- **Arrêt pour les pneus seuls** : ajouté sans toucher au carburant. L'appel dit « pour les pneus uniquement, pas
  besoin d'essence ».
- **Le plan attend** la réponse sur les pneus après l'arrêt avant de recalculer.

### D5 — Radio

- **Nouvelles questions** :
  - comment vont mes pneus, ils sont encore bons ;
  - combien de tours avec ces gommes ;
  - je perds l'avant, tu vois quelque chose ;
  - je patine en sortie, pourquoi ;
  - doit-on changer les pneus, gagnerait-on du temps en rentrant maintenant ;
  - prolonger de deux tours ;
  - pourquoi rentrer avec assez d'essence.
- **Déclarations** : « je change seulement les pneus » est noté comme intention. « Je viens de monter des mediums »
  passe par la commande pneus habituelle.
- **Réponses** : réponses courtes, détails sur « pourquoi ? ». Elles séparent ce qui est mesuré, déclaré et estimé.
- **Messages non urgents différés** pendant un gros freinage ou un volant très braqué, 6 s au plus. Alertes, appels
  au stand et réponses passent toujours tout de suite.

### D6 — Écran

- **Carte « Pneus & endurance »** :
  - gomme, âge et certitude du relais ;
  - quatre températures mesurées ;
  - état de l'adhérence, mesuré, déclaré et autres causes ;
  - rythme ;
  - durée seulement si elle est déclarée ou justifiée par l'historique ;
  - prochain changement et sa raison.
- **Section stratégie, « PROCHAIN ARRÊT »** :
  - motif (règlement, carburant, pneus) ;
  - intervention prévue ;
  - alternatives avec écarts, incertitude et données.

### Limites

- GT7 ne transmet ni l'usure, ni la gomme montée, ni les changements de pneus : tout cela est déclaré par le pilote,
  ou déduit des chronos et de la glisse.
- Le temps d'un changement de pneus n'est pas mesuré : une marge prudente de 10 s le remplace.
- Le format de paquet A ne transmet pas l'angle du volant : le report des messages pendant les virages ne fonctionne
  qu'avec les formats B et ~ ; le freinage marche avec tous.
- La glisse par essieu repose sur les vitesses de roue (seuils prudents). Elle reste à valider sur PS5.
- Sans historique comparable, la durée de vie des pneus n'est jamais annoncée. La dégradation est mesurée sur le
  relais en cours, à partir de 4 tours propres.
- Les tests automatiques simulent des courses. Seuls des essais sur PS5 valideront les seuils.

### Protocole PS5 hors ligne (environ 15 minutes)

1. **Préparation** : course personnalisée hors ligne, 12 tours ou plus.
   - Usure des pneus ×3 à ×5 ou plus, carburant ×1 (le carburant doit suffire).
   - Dans Pitwall, type « Entraînement Sport » ou « Course personnalisée », règlement saisi (tours, usure).
   - Gomme de départ déclarée : « je pars en mediums course ».
2. **Tours 1 à 4** : rouler régulièrement.
   - Une seule annonce « températures stabilisées » est attendue, jamais « tu peux attaquer ».
   - « Comment vont mes pneus ? » : âge, gomme, « pas encore assez de tours » puis « comportement stable ».
3. **Une erreur volontaire** sur un tour (large, sans sortie) : le tour suivant ne doit pas annoncer de dégradation.
4. **Tours 5 et plus** : à mesure que l'usure baisse le rythme, on doit entendre « possible dégradation » puis
   éventuellement « persistante ». Les autres causes doivent être citées, sans aucun pourcentage d'usure.
   - « Je perds l'avant, tu vois quelque chose ? » : « Noté, d'après toi », puis ce que mesurent les données.
   - « Est-ce qu'on doit changer les pneus ? » et « On gagnerait du temps en rentrant maintenant ? » : gain chiffré
     seulement si démontré, sinon le dire.
5. **Arrêt pour les pneus seulement** (sans essence), à l'appel « pour les pneus uniquement » ou de toi-même :
   - « Tu as bien effectué ton arrêt ? » → « oui » ; « As-tu changé les pneus ? » → « oui ». La carte Pneus repart à
     0 tour.
6. **Ravitaillement sans pneus** (si la course le permet) : « non » à la question. Le relais pneus doit continuer, sans
   remise à zéro.
7. **À la fin** : la carte Pneus et la section « PROCHAIN ARRÊT » doivent rester cohérentes avec ce qui a été fait.
   Envoie les captures si une réponse est fausse.

### Démonstration simulée : le carburant suffit, les pneus imposent l'arrêt

Course de 20 tours, consommation 2 % par tour, chronos qui se dégradent d'environ 0,4 s par tour d'âge des pneus,
quatre courses comparables archivées (test `TyreStrategyTest.fullRaceTyresForceAStopWhileFuelIsEnough`, extrait de la
radio) :

```
RADIO   Course de 20 tours. Stratégie : 1 arrêt (consommation tirée de l'historique, à confirmer). fin du tour 10, sans
        essence, pour les pneus uniquement. Le carburant suffit, mais tes pneus nous coûtent du temps. Je recommande un
        arrêt pour les pneus uniquement, fin du tour 10. Aucun ravitaillement nécessaire. Gain estimé 15 s, au-delà de
        notre incertitude (2,0 s).
RADIO   Tour 5 : 39 secondes 5. Plus 1,8 seconde sur ton meilleur. […] Possible dégradation : plus 90 centièmes sur tes
        meilleurs tours avec ces pneus. Pas encore confirmée, je surveille. […] Carburant pour 45,1 tours.
RADIO   Stand au prochain tour, prépare-toi. On changera les pneus.
RADIO   Box, box ! Rentre au stand à la fin de ce tour pour les pneus uniquement, pas besoin d'essence.
RADIO   Je t'ai vu immobilisé 4 secondes sans ravitaillement. Tu as bien effectué ton arrêt ?
PILOTE  oui
RADIO   Arrêt 1 enregistré : confirmé par toi. As-tu changé les pneus ?
PILOTE  oui
RADIO   Noté : pneus neufs au tour 11.
RADIO   Tour 12 : 37 secondes 9. Plus 16 centièmes sur ton meilleur. […]
RADIO   Tour 14 : 37 secondes 7. Meilleur tour, 2 centièmes de mieux ! […]
RADIO   Course terminée ! Meilleur tour : 37 secondes 7.
```
