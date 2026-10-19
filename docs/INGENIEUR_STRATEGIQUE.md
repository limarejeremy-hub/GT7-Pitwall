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

## Jalon B : à venir

Comparaison de plans (normal, arrêt anticipé, relais prolongé) avec temps total estimé. Consommation et rythme tirés
des sessions comparables, avec niveau de confiance. Actualisation tour par tour sans changer de plan pour rien. Unité
du carburant vérifiée.

## Jalon C : à venir

Questions stratégiques à la radio, déclarations du pilote (adhérence, trafic, arrêts des autres) et explication des
changements de plan.
