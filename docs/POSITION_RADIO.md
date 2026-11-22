# V508 — « Position ? » et classement à l'arrivée

Base : V507 (`claude/v507-gt3-dashboard`). Rien n'est changé dans la négociation UDP ni dans la voix par défaut.

## Chemin actuel de 0x84 / 0x86

1. `TelemetryParser` lit `preRacePosition` (0x84) et `preRaceCarCount` (0x86) dans chaque format (A, B, ~, C).
2. `PitwallEngine.onPacket` donne le paquet à `PositionProbe` (diagnostic) et `FormatLog` (formats), puis à
   `RaceController.onPacket`.
3. `RaceController` : `PositionTracker.onPacket` (course seulement), remis à zéro sur `SessionReset`, puis
   `engineer.measured = snapshot` et `engineer.onPosition(changes)`, et le journal (`JournalType.POSITION`).
4. Radio : `DriverCommands` (« position », « quelle place ») → `DriverQuery.POSITION` → `RaceEngineer.positionAnswer()`.
   `RadioConversation` le traite en local (aucune IA).
5. Replay : `TelemetryTrace.replay` relit une trace brute (GT7PWTR1) avec son horloge virtuelle. Un contrôleur construit
   avec `clock = clock::now` voit le même rythme qu'en course.

## Ce qui change

- **Réponse courte (option de test).** Dans GARAGE → diagnostic position UDP, l'option est désactivée à chaque lancement.
  - Réponse : « Neuvième sur quinze. ».
  - Le rang n'est donné que si toutes ces conditions sont réunies :
    - un paquet a été reçu il y a 1,5 s au plus ;
    - la course est lancée (tours connus, ni chargement ni menu) ;
    - la course n'est pas encore finie ;
    - le rang est établi et 1 ≤ rang ≤ peloton, avec au moins 2 voitures.
  - Sinon : « Position indisponible. ».
  - Le rang est lu au moment de la question, pas au dernier paquet : un gel UDP ne laisse pas une place périmée.
- **Rang à chaque tour.** `PositionTracker.lapPositions` garde le rang lu sur le paquet de la ligne, et le journal le
  note (« Tour 1 bouclé : P9 sur 15 (ligne) »). Rien n'est annoncé.
- **Arrivée.** Voir la section « Révision du classement après la ligne » : le rang de la ligne n'est plus définitif.
  - Le rang de la ligne est relevé dès le paquet de la ligne (« 8/7 ») s'il est cohérent.
  - Si le paquet de la ligne est incohérent, Pitwall prend la valeur stable qui suit.
  - Si la course se ferme avant cela (« 8/0 », chargement), Pitwall prend la dernière place établie.
  - Le « 8/0 » ne rouvre rien. Une nouvelle grille repart d'un état vide.
- **Diagnostic.**
  - Le « 8/0 » qui suit une arrivée s'affiche « après arrivée » (plus « en course »).
  - Chaque ligne exportée garde le numéro de paquet GT7 (0x70) et le format reçu.
- **Journal des formats.**
  - Il distingue trois choses : le dernier heartbeat envoyé, le format reçu et le statut de la réponse.
  - Un format reçu n'est jamais affiché « sans réponse ».
  - Chaque période garde ses premier et dernier numéros de paquet. `receivedAt(packetId)` rattache ainsi un événement
    de position au format reçu.

## Révision du classement après la ligne (22/11)

Trace du 22/11 : P12/16 sur le paquet de la ligne, puis P13/16 567 ms plus tard. Le pilote a reçu une pénalité de 2 s,
appliquée après la ligne. Ce n'est donc pas une anomalie de transmission : GT7 met 0x84 à jour après l'arrivée quand le
classement change. Le classement n'est plus figé définitivement au premier paquet « 8/7 ».

Pitwall distingue désormais trois valeurs :
- **position au franchissement de la ligne** (`linePosition`) : le rang du paquet de la ligne, jamais modifié ;
- **position après l'arrivée** (`finalPosition`) : révisée tant que la session d'arrivée dure. Le compteur de tours
  reste celui de l'arrivée, en « 8/7 » ou en « 8/0 ». Une nouvelle valeur doit tenir 0,5 s. Chaque révision est
  gardée dans `postFinishChanges`, avec son délai depuis la ligne ;
- **résultat archivé** (`finishPosition`, `finishConsolidated`) : la valeur consolidée.

**Consolidation.** Le classement est consolidé après 30 s sans changement depuis la ligne ou la dernière révision ;
chaque révision relance ce délai. Il l'est aussi plus tôt quand la session d'arrivée se termine :
- chargement, ou autre tour ;
- nouvelle session (le résultat est archivé avant la remise à zéro) ;
- peloton en hausse. Après l'arrivée, 0x86 ne peut pas augmenter : une hausse correspond aux valeurs du salon ou du
  matchmaking (0x84 = 0x86 = voitures chargées, essais du 20/10).

**Pourquoi 30 s.** Une seule mesure réelle existe (567 ms). Le délai n'est pas calé sur elle : une révision liée au
temps peut dépendre de voitures encore en piste, et 30 s couvrent des écarts et des pénalités de plusieurs secondes.
Après l'arrivée, attendre n'a pas de coût en course. **À recalibrer avec d'autres traces.**

**Radio.**
- Une seule annonce à la ligne : « Tu passes la ligne P12 sur 16. ». Ce n'est plus « Tu termines… », qui pourrait
  être contredit ensuite.
- À la consolidation, une seule fois et seulement si le rang a changé : « Classement GT7 révisé après l'arrivée :
  P13 sur 16. ».
- Le bilan de fin de course est donné une seule fois, après la consolidation.
- « Position ? » : « Tu as passé la ligne P12 sur 16 ; classement GT7 provisoire : P13 sur 16. » pendant la
  consolidation, puis « Tu termines P13 sur 16, d'après GT7. P12 à la ligne, classement révisé ensuite. ».

**Aucune cause attribuée.** GT7 ne transmet aucun champ de pénalité identifié. La pénalité du 22/11 est connue par le
témoignage du pilote ; Pitwall ne dit jamais « pénalité ».

L'ancien gabarit de test « 9/10 cinq secondes après l'arrivée, à ignorer » n'était pas une mesure. Il est remplacé par
le cas réel du 22/11 et par le cas des valeurs du salon.

## Preuves et limites

- `PositionRadioV508Test` rejoue les transitions de l'export texte dans une trace brute synthétique, avec le moteur de
  replay et l'horloge virtuelle.
- **La trace brute réelle de cette course n'est pas dans le dépôt.** Ces éléments du test sont des gabarits, pas des
  mesures :
  - les heures des autres passages de ligne ;
  - l'instant du passage de P9 à P8 ;
  - le délai avant « 8/0 ».
- La relecture de la trace réelle devra confirmer plusieurs points :
  - le paquet de la ligne porte bien P5/14 ;
  - le rang du passage de chaque tour est le bon ;
  - le délai réel de « 8/0 » ;
  - l'absence de faux changement de rang au passage de 15 à 14 voitures.
- Aucune validation PS5 ni Fold n'est revendiquée.
- « Course Sport B » est un libellé Pitwall : le mode réel reste à confirmer sur le HUD.

## Scénario HUD (prochain essai Sport)

1. Avant la course, dans GARAGE → diagnostic position UDP : activer « Réponse courte Position ? ».
   - Le journal des formats tourne seul ; activer la trace télémétrique si possible.
2. Sur la grille, dire « Position ? ». Attendu : la place du HUD sur le peloton (par exemple « Dixième sur quinze. »).
3. À chaque passage de ligne, comparer le HUD et la réponse à « Position ? ». Noter « HUD P… » dans la sonde en cas d'écart.
4. Si une voiture quitte la course, la réponse suit le nouveau peloton sans changer de place.
5. À l'arrivée, attendu une seule fois : « Course terminée ! … Tu termines P… sur … ». Puis « Position ? » répond
   « Position indisponible. ».
6. Exporter :
   - le diagnostic position (colonnes paquet et format) ;
   - le journal des formats ;
   - la trace brute si elle a été enregistrée.

Annonces automatiques de places et coaching 0x154 : étape suivante.
