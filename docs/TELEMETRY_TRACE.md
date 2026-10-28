# Trace télémétrique réelle (issue #41, P1 ; issue #36, Sprint D)

La sonde « Mesure Black Box » ne faisait que **mesurer** le coût d'une trace. Essai du 28/10 : 50 384 paquets en
14 min 50, calcul moyen 0,19 ms par paquet, p95 0,40 ms, trace simulée d'environ 1,1 Mo/h. **Aucune trace réelle
n'existait.** Celle-ci l'enregistre, seulement sur demande.

## Ce qui est enregistré
- Le paquet GT7 **déchiffré tel quel** (296 à 368 octets) et son heure de réception.
  - Pédales, frein appliqué (0x13D), couple (0x154), roues, surfaces, position et tour restent alignés.
  - Un décodeur futur pourra relire les mêmes octets.
- Aucun son, aucune adresse réseau, aucun identifiant de compte, rien de Discord.
- Fréquence : 20, 30 ou 60 Hz, au choix avant de démarrer.

## Garde-fous
- **Le flux 60 Hz n'attend jamais le disque.** Le fil de réception pose l'échantillon dans une file bornée (1 024) ; un fil à part compresse (gzip) et écrit. Si la file est pleine, l'échantillon est perdu et compté (« perdus par Pitwall »).
- **Arrêt automatique** à 80 Mo compressés ou 4 h, avec la raison affichée.
- **Au plus 5 traces** sur le téléphone : les plus anciennes sont supprimées.
- **Désactivé à chaque lancement de Pitwall.** L'enregistrement s'arrête aussi quand la télémétrie est arrêtée.
- **Relecture hors course seulement** : refusée si GT7 a envoyé des données dans les 10 dernières secondes. Elle passe par un contrôleur neuf, sans archive : rien n'est appris, archivé ni annoncé.

## Mesures affichées
- Durée, échantillons gardés sur paquets reçus, taille compressée et Mo/h.
- Pertes réseau GT7 (trous dans les numéros de paquet), échantillons perdus par Pitwall, silences de plus de 2 s.
- Délai réception → écriture sur disque (p95, p99). Ce n'est **pas** la latence de la radio : le log du 28/10 donnait environ 149 ms pour une réponse locale et environ 3,8 s pour Gemini Nano, à ne pas mélanger.

## Relecture (révisée après la revue de la PR #44)
- **En flux** : un seul échantillon en mémoire à la fois. Une trace de 4 h à 20 Hz (288 000 échantillons) dépasse 100 Mo une fois décompressée ; elle n'est plus chargée d'un bloc.
- **Horloge virtuelle** : avant chaque paquet, l'horloge du contrôleur de relecture prend l'heure enregistrée de ce paquet, en partant de l'heure inscrite dans le nom du fichier. Les calculs qui dépendent du temps (reconnaissance de la voiture, journal, identifiant de session) voient le rythme réel de la course, même si la relecture va plus vite.
- Test de cohérence : une course simulée de 6 tours, calculée en direct puis rejouée, donne les mêmes tours (numéro, chrono, carburant), la même consommation, le même identifiant de session et le même journal, heures comprises.
- Limite restante : l'heure des messages radio (`EngineerMessage.timestampMs`) reste celle du téléphone. Elle ne sert qu'à la diffusion vocale, jamais à un calcul, et la relecture n'annonce rien.

## Format du fichier `.gt7trace`
gzip de : `GT7PWTR1`, puis pour chaque échantillon les millisecondes depuis le début (Int), la longueur (Short) et les
octets du paquet. Une fin tronquée (téléphone coupé) garde les échantillons complets.

## Statut
- Tests JVM : `TelemetryTraceTest` (9), dont 3 ajoutés après la revue :
  - trace de 4 h à 20 Hz relue en flux, avec moins de 32 Mo retenus ;
  - 1 h à 60 Hz (216 000 paquets) rejouée dans un contrôleur, à l'heure enregistrée ;
  - cohérence entre le direct et la relecture.
  - Décimation 60 → 20 Hz et relecture exacte.
  - Format C conservé entier.
  - Pertes, silences et trames illisibles.
  - Trace tronquée.
  - Limite de taille.
  - Relecture dans un contrôleur neuf.
- **PS5 : non testé.**
  - Mesurer sur une vraie course de 15 min à 20 Hz : Mo/h, perdus par Pitwall (doit rester à 0), p95/p99, batterie.
  - Puis exporter la trace et la rejouer hors course.
