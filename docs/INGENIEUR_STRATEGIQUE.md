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
