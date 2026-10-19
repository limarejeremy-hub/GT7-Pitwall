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
