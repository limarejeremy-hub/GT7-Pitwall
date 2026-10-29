# Audit des sources d'empreintes de grille (revue #62, 30/10/2026)

**Question du pilote** : « Chaque place sur la grille a des valeurs X Y Z ; la communauté les connaît ? Il faudrait les croiser. »

**Réponse courte** : je n'ai trouvé **aucun jeu public sous licence qui publie les cases de grille P1…P20 par circuit**. Les sources ouvertes publient des **tracés** (trajectoires X/Z, parfois Y) et, pour l'une d'elles, des **points de départ de tour**. Une case de grille n'est pas un point de départ de tour.

Toutes les vérifications ci-dessous sont faites le 30/10/2026 depuis les pages publiques des dépôts. Aucune donnée tierce n'a été téléchargée en masse ni intégrée.

## Inventaire

| Source | URL | Licence (constatée) | Type de données | Repère | Cap (heading) | Cases de grille prouvées | Réutilisation |
|---|---|---|---|---|---|---|---|
| gt7-datalogger-track-data | github.com/jbhoorasingh/gt7-datalogger-track-data | CC0 (relevés) + MIT (captures zetetos), mixte | tracés 1 pt / 20 m (`signatures.json`), bundles de bordures pour 27 configurations (`tracks/`), virages nommés | X/Z télémétrie GT7 | dans les bundles (`hx`/`hz` par mètre de bordure) | **aucune** : la page ne mentionne ni grille ni voie des stands | **oui**, déjà embarqué (72 signatures, V482), avec notice |
| GT7Tracks | github.com/vthinsel/GT7Tracks | MIT (licence du dépôt) | `dumps/<track_id>.csv` : position x/z/y, vitesse, régime, enregistrées en roulant ; `tracks_concatenated.csv` ; modèle TensorFlow | X/Z/Y télémétrie GT7 | orientation dans les colonnes CSV (selon GPT : `orientation,rotation_x,rotation_z,rotation_y`) | **non démontrées** : ce sont des relevés en roulant | à auditer fichier par fichier : provenance des enregistrements, version GT7, nombre de circuits. **Non intégré.** |
| TRACE (gt7telemtrace) | github.com/ransh2014/gt7telemtrace | MIT (code) | algorithme : reconnaît le circuit par **le point où le tour commence et le sens de sortie de la ligne** ; empreintes = nom de layout, position de départ, cap, longueur du tour ; liste **partagée** téléchargée au lancement, apprentissages locaux dans `track_fingerprints.json` | coordonnées du jeu | oui | **non** : points de départ de tour. La reconnaissance intervient « environ un tour » après le départ. | **code** : MIT. **Données partagées** : servies par un serveur tiers, sans licence de redistribution constatée. **Ne pas aspirer, ne pas réutiliser son point d'accès.** |
| Docs GT7 Datalogger | docs.gt7-datalogger.com | documentation | identification de circuit par trajectoire, détection de tour | — | — | non : les positions de grille varient selon la distance à la ligne | lecture seule |

## Ce que dit la télémétrie (rappel)

- Le paquet contient la position X/Y/Z (0x04, 0x08, 0x0C), le quaternion de rotation (0x1C–0x28), le code voiture, les tours et 0x84/0x86 (position, participants).
- Les repères de nombreux circuits **se chevauchent** autour de l'origine. Mesure sur nos 72 tracés : seules **4,4 %** des positions à l'arrêt désignent un seul tracé à 30 m près.
- L'**orientation** n'est **pas encore utilisée** : la conversion du quaternion en cap et son repère ne sont pas validés sur PS5.
- **Y** est absent de nos signatures X/Z, donc inutilisable sans autre référence.

## Ce qui est fait à la place (V482 + revue #62)

1. **Voie contextuelle, 0 m.** Le programme de la semaine et la voiture envoyée par GT7 désignent une épreuve **probable** (catégorie ou voiture imposée), avec circuit **attendu**, dès la qualification.
   - Semaine du 26/10 : la Subaru Gr.3 désigne la C (SUGO) ; la Fairlady Z désigne la A ; une Gr.4 désigne la B (Big Willow).
   - Ce n'est jamais une preuve géométrique, ni une course « officielle ».
2. **Position à l'arrêt** : candidat **probable** si un seul tracé passe à 30 m. Nouvel examen sur une nouvelle position à l'arrêt, au plus 4 par session.
3. **Tracé en roulant**, y compris au tour de sortie de qualification (tour 0) : reconnaissance mesurée.
4. **Relais qualification → course** : un tracé mesuré en qualification est repris pour la course (même voiture, moins de 30 min) comme « mesuré en qualification, à confirmer ». Il n'est jamais compté comme mesuré dans la course.

## Si une source de cases de grille apparaît

Avant tout `GridPoseMatcher`, il faudrait :
- une licence de **données** explicite ;
- la provenance et la version de GT7 ;
- des cases relevées à l'arrêt (pas des points de départ de tour) ;
- par layout et par sens ;
- avec une tolérance mesurée et une collision chiffrée contre **tous** les layouts.

Sans cela, ne rien intégrer et ne jamais annoncer « grille identifiable ». Il est possible de contacter les auteurs (TRACE, GT7Tracks) pour une autorisation de réutilisation.
