# Reconnaissance du circuit par la géométrie, et mesures Black Box

Branche `claude/track-geometry`, empilée sur `claude/sport-intelligence` (PR #27).
**Aucun essai PS5** : tout ce qui suit est vérifié sur des trajectoires simulées à partir des tracés de référence.

## Pourquoi

Avant, Pitwall reconnaissait le circuit à la fin du premier tour complet, par sa longueur. Cela posait deux
problèmes :
- plusieurs circuits ont presque la même longueur (Laguna Seca / SUGO), et un circuit et son inverse ont exactement
  la même ;
- la stratégie, la mémoire voiture × circuit et la reconnaissance de la course Sport attendaient la fin du tour.

Maintenant, la position de la voiture (X, Z de la télémétrie) est comparée aux tracés de référence. Le circuit est
reconnu au bout d'environ **700 m**.

## Données

- `jbhoorasingh/gt7-datalogger-track-data`, `signatures.json` : **CC0**. Une partie des tracés est dérivée de
  `zetetos/gt-telemetry`, sous **MIT** ; l'avis est conservé dans `assets/licenses/`.
- `tools/track_signatures/build.py` rapproche chaque configuration des identifiants gt7info (ceux de Pitwall) : même
  nom, même sens, longueur à 3 % près. Sont écartées :
  - les configurations que leurs auteurs signalent eux-mêmes (`catalog_length_disagrees`, `length_from_catalog`) ;
  - celles sans tracé ;
  - celles qui ne peuvent pas être rapprochées sans ambiguïté (Barcelone « GP Layout » / « Grand Prix Layout »,
    Yas Marina, Eiger).
- Résultat : **67 signatures, 99 configurations** avec les sens inverses ; 12 signatures écartées. L'asset pèse
  198 Ko.

## Algorithme (`TrackGeometryRecognizer`)

1. Un échantillon tous les 10 m parcourus, seulement quand la voiture roule en piste. Les stands, la pause, le
   chargement et les arrêts sont exclus.
2. Pour chaque configuration :
   - écart au tracé : suivi local d'abord, pour qu'un tracé en 8 comme Suzuka ne saute pas de branche ;
   - éliminée si la voiture sort du cadre ou s'écarte de plus de 80 m ;
   - éliminée aussi après 4 échantillons de suite à plus de 25 m ; une sortie de piste brève est tolérée.
3. **Sens** : la progression le long du tracé (80 % au moins dans un sens) sépare un circuit de son inverse. Un sens
   inverse sans configuration inverse connue est éliminé.
4. Verdict après au moins 700 m et 40 échantillons :
   - **reconnu** : une seule configuration reste ;
   - **ambigu** : plusieurs configurations voisines restent, par exemple Le Mans avec ou sans chicanes avant la
     partie qui les distingue. Jamais tranché au hasard ;
   - **inconnu** : aucune ne correspond (circuit absent des signatures).

## Ce que l'ingénieur en fait

- **Reconnu** : le circuit est appliqué tout de suite (« Circuit reconnu : … »), la stratégie est recalculée avec la
  mémoire de ce circuit, et la course Sport peut passer « reconnue » **avant la fin du premier tour**. Un circuit
  mémorisé contradictoire est remplacé, et c'est tracé au journal (course du 26/10 : Le Mans resté d'une course
  précédente).
- **Ambigu ou inconnu** : noté au journal seulement. La reconnaissance par la longueur et la vérification de
  cohérence gardent leur rôle. Ce choix protège la course si le repère X/Z de la PS5 différait de celui des
  signatures : la géométrie ne casserait alors rien de juste.

## Tests (trajectoires simulées)

- **Tous les tracés embarqués**, dans les deux sens, sur un tour : 97 configurations reconnues ; **aucune
  confusion** avec un autre circuit. Reconnaissance à 710 m en médiane. Deux cas restent ambigus (Kyoto Yamagiwa et
  sa voisine, au tracé commun).
- **Conduite difficile** : départ n'importe où, 12 m du tracé, bruit de ±5 m, une sortie de piste de 40 m.
  Résultat : 94 configurations sur 99 reconnues, aucune confusion.
- **Autres cas** :
  - sens inverse distingué ;
  - Le Mans avec ou sans chicanes ambigu au départ ;
  - circuit inconnu jamais nommé ;
  - rien n'est décidé à l'arrêt ou avant 700 m.
- **Course complète** (`TrackGeometryRaceTest`) :
  - circuit reconnu à 40 % du premier tour ;
  - circuit mémorisé périmé remplacé ;
  - sens inverse reconnu ;
  - circuit inconnu sans effet ;
  - course Sport « reconnue » avant la fin du premier tour, et toujours pas « officielle ».

## Ce qui reste à valider sur PS5

1. Le **repère X/Z** : les signatures viennent des mêmes octets du paquet GT7 (0x04 et 0x0C). Aucune trace de
   Pitwall ne contenait encore de positions pour le vérifier. La première course le dira au journal : « Circuit
   reconnu par la géométrie » ou « aucune signature ne correspond ».
2. La reconnaissance sur une grille de départ et pendant un départ lancé réel.
3. Les circuits absents des signatures (12 écartés, plus ceux que la source n'a pas encore relevés) : seule la
   longueur s'applique.

## Mesures Black Box à faire sur la Fold (`BlackBoxProbe`, Atelier)

L'interrupteur « Mesurer pendant la prochaine course » mesure **sans rien enregistrer** :
- le débit réel des paquets et les pertes ;
- le temps de calcul de Pitwall par paquet (moyenne, 95e centile, maximum) ;
- la taille d'une trace 20 Hz : chaque échantillon de 36 octets est réellement compressé en mémoire, puis jeté ;
- la batterie et la température du téléphone. C'est la consommation du téléphone entier pendant la course, pas
  celle de la Black Box seule.

Sur PC, avec des paquets simulés : 2,6 Mo brut par heure, et 0,33 ms de calcul par paquet. **Ces chiffres ne valent
pas pour la Fold.** Le taux de compression des données simulées n'est pas représentatif.

Protocole proposé :
1. Une course de 15 minutes ou plus avec la mesure activée.
2. Une course comparable sans la mesure, pour la batterie.
3. Relever les lignes de l'Atelier.

Ces chiffres décideront du format et de la fréquence de la Black Box.
