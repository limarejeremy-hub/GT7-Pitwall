# Diagnostic de la position UDP (0x84 / 0x86)

## Pourquoi

- Ce que dit la structure des paquets reconstituée par la communauté :
  - 0x84 est la position sur la grille, 0x86 le nombre de voitures au départ ;
  - les deux valent -1 une fois la course lancée.
- D'autres sources (GT7 Datalogger, SimHub) signalent une position qui évolue dans certaines épreuves.
- Rien n'est supposé. Le diagnostic mesure ce que GT7 envoie réellement en Course Sport A, B et C.
- Aucune source UDP vérifiée ne donne les écarts avec les adversaires.

## Ce que fait le diagnostic

- **Emplacement** : carte « Diagnostic radio », interrupteur « Outils de développement », carte « Diagnostic position UDP ».
- **Valeurs** : 0x84 et 0x86 bruts et signés, tour, état de session (avant départ, en course, pause, chargement,
  hors piste, arrivée).
- **Proportions** : part des paquets à une valeur positive, à -1, ou à une autre valeur.
- **Journal** :
  - une ligne à chaque changement de 0x84 ou 0x86, d'état ou de tour ;
  - une ligne à chaque repère que tu poses : « J'ai doublé », « Doublé », « Arrivée », « Position HUD » ;
  - le journal garde les 600 dernières lignes.
- **Export** : « Exporter » ouvre le partage Android. C'est du texte, sans audio ni donnée personnelle.
- **Aucun effet sur la course** :
  - le diagnostic n'est utilisé ni par la stratégie ni par la radio ;
  - il tourne même quand la carte est masquée, avec quelques comparaisons par paquet ;
  - toute erreur est ignorée, sans toucher à la télémétrie, à Discord ou à Vosk.
- **Sécurité** : l'annonce « Tu pars en position X » n'est faite qu'avant le départ (tour 0). Une valeur qui
  apparaîtrait en course n'est jamais annoncée.

## Protocole PS5 (moins de 10 minutes)

1. **Préparation** : activer les outils de développement, appuyer sur « Réinitialiser », lancer la télémétrie.
2. **Sur la grille** d'une Course Sport (A, B ou C) :
   - entrer la position affichée sur l'écran de départ dans « Position HUD », puis « Noter » ;
   - noter la valeur de 0x84 affichée.
3. **Tours 1 et 2** :
   - à chaque dépassement, que tu doubles ou que tu sois doublé, appuyer sur le bouton correspondant dès que possible ;
   - une fois en ligne droite sûre ou à l'arrivée, noter la position du HUD au moins une fois.
4. **À l'arrivée** : appuyer sur « Arrivée », puis noter la position finale du HUD.
5. **Export** : « Exporter », puis m'envoyer le texte.

## Lecture du résultat

- **0x84 reste à -1 après le départ (100 % à -1 en course)** : pas de position en direct dans cette épreuve.
  Pitwall garde la position que tu déclares toi-même.
- **0x84 change en course** : comparer chaque changement avec les repères posés.
  - Il faut au moins trois courses Sport, dont une de chaque série si possible.
  - Le décalage entre un dépassement et le changement de valeur doit être faible et toujours dans le bon sens.
  - L'annonce automatique à la radio ne sera envisagée qu'après ces résultats.

## Résultats des essais PS5 (20 octobre 2026)

| Essai | Constat |
| --- | --- |
| Course hors ligne, 20 voitures | 0x84 suit le HUD : P20 au départ, P16 au tour 4 |
| Course Sport A en ligne, 12 voitures | grille P7 = HUD, tour 3 P10 = HUD, arrivée P8 sur 11 |
| Matchmaking Sport A (tours à 0) | 0x84 = 0x86 = voitures chargées (12/12 alors que le HUD indique P7) : **pas une position** |
| Chargements | 0x84 vaut 1 pendant quelques millisecondes |
| Déconnexion au tour 5 | 0x86 passe de 12 à 11, 0x84 baisse d'une place au même instant |

## Position automatique (livrée après ces essais)

`PositionTracker` lit 0x84 et 0x86 uniquement :

- **Quand** :
  - dans une course lancée : nombre de tours connu, sur la grille (tour 0) ou en piste ;
  - hors chargement et hors pause ;
  - jamais en qualification : 0x84 n'y a pas été validé, la réponse le dit.
- **Valeurs acceptées** : cohérentes, 1 ≤ position ≤ participants ≤ 40.
- **Stabilité** :
  - une valeur doit rester stable 3 s pour être retenue ;
  - un roue dans roue de 0,3 s est ignoré ;
  - à l'arrivée, le classement de la ligne est figé dès qu'il est stable 1 s, et les valeurs d'après-course sont
    ignorées.
- **Annonces radio** :
  - « Tu pars en position 7 sur 12 », une fois, sur la grille ;
  - un changement est annoncé par la seule nouvelle place (« P6. »), quand la position ne bouge plus depuis 3 s
    (dépassements multiples et accidents regroupés), au plus toutes les 8 s ;
  - à l'arrivée : « Tu termines P8 sur 11 ».
- **Questions** :
  - « Quelle est ma position ? » ;
  - « Combien de places gagnées ? », « J'ai perdu combien de places ? » : compte depuis la grille, et signale les
    voitures parties.
- **Priorité à GT7** : la mesure passe avant les déclarations (« je suis cinquième », « j'ai doublé »).
- **« Ta position ? » à chaque tour** : la question est supprimée tant que GT7 transmet la position.
- **Repli** : si GT7 n'envoie plus de valeur valide pendant 5 s, la radio le signale une fois. La position déclarée
  reprend, à partir de la dernière position mesurée.
- **Participants** : ceux du départ et ceux d'aujourd'hui sont gardés séparément.
- **Jamais** de position adverse ni d'écart : GT7 ne donne que notre rang.

## Validation rapide Sport B et C (moins de 10 minutes chacune)

1. **Avant la course** : la série choisie dans Pitwall doit correspondre (B ou C, « Course »). Dans le diagnostic,
   appuyer sur « Réinitialiser ».
2. **Grille** : la radio doit annoncer « Tu pars en position X sur Y », avec X égal au HUD. Noter le HUD dans
   « Position HUD ».
3. **Pendant la course**, en ligne droite et sans prendre de risque :
   - demander « Quelle est ma position ? » une ou deux fois, et comparer avec le HUD ;
   - noter le HUD dans le diagnostic.
4. **Après un dépassement** :
   - l'annonce doit arriver dans les 3 à 20 s ;
   - « Combien de places gagnées ? » doit donner le bon compte depuis la grille.
5. **Arrivée** :
   - la radio doit dire « Tu termines PX sur Y », avec X égal au classement final ;
   - noter le classement dans « Position HUD », puis exporter le journal.
6. **Qualification (facultatif)** : choisir « Qualification » dans Pitwall. « Quelle est ma position ? » doit
   répondre « En qualification, je n'utilise pas la position… », et aucune position n'est annoncée.

À noter à chaque étape : toute différence entre la radio et le HUD, et les annonces jugées trop fréquentes.
