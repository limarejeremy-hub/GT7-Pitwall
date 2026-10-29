# Circuits zéro touch : validation hors ligne (issue #58, V482)

**Aucune validation PS5.** Le pilote n'a pas sa console. Les trajectoires de ce document sont **synthétiques** : tirées des tracés de référence, avec décalage latéral et bruit. Le dépôt ne contient aucune trace réelle de positions sur la grille. La seule validation réelle antérieure reste Suzuka : reconnu en course le 27/10 à 706 m, écart moyen de 1,4 m.

## Données

| Fichier | Source | Licence | Usage |
|---|---|---|---|
| `signatures.json` | jbhoorasingh/gt7-datalogger-track-data, commit `80d0a11`, blob `a1f99b5` | **mixte** : 26 relevés CC0 + 54 captures MIT (zetetos/gt-telemetry) | tracés de référence (1 point / 20 m) |
| `index.json` | même commit, blob `e6713d2` | CC0 | 122 configurations, sert à repérer les sites incomplets |

- Les fichiers sont lus depuis la branche `research/open-community-data-licensing` et transformés par `tools/track_signatures/build.py` en `app/src/main/assets/gt7db/track_signatures.json`.
- La notice (`assets/licenses/NOTICE-track-signatures.txt`) est mise à jour : commit, caractère mixte CC0 + MIT, notice MIT.
- Aucune image, aucune donnée de joueur, aucun fichier `tracks/` détaillé n'est importé.
- **La redistribution commerciale reste soumise à une revue juridique** (Sony/Polyphony, noms de circuits).

## Couverture réelle (aucun faux « 122 circuits complets »)

| Mesure | Nombre |
|---|---|
| Configurations dans l'index | 122 |
| Configurations couvertes par une signature amont (sens direct ou inverse) | 116 |
| Configurations couvertes par une signature **gardée** par Pitwall | 105 |
| Signatures amont | 80 |
| Signatures signalées par leurs auteurs (`flags`), écartées | 8 |
| **Signatures gardées par Pitwall** | **72** (avant : 67) |
| … rattachées à un identifiant gt7info | 67 (identiques octet pour octet à l'ancien fichier) |
| … rattachées au démarrage à une fiche des notes officielles | 1 : **SUGO** (3 560 m contre 3 586 m, écart 0,7 %) |
| … sans circuit (longueur à plus de 3 % de la fiche) | 4 : Barcelona GP, Barcelona GP No Chicane, Eiger Nordwand, Yas Marina. Elles servent seulement à écarter les autres circuits. |
| Sites marqués « variante sans signature » (anticipation interdite) | 17 signatures, dont Fuji (Short manque) et Suzuka (East manque) |
| Virages, bordures, position de grille | **aucun** dans ce jeu. SUGO est une ligne de capture, pas un relevé de bordures. |

## Ce que fait le code

1. **`Gt7Database.geometrySignatures`**
   - Une signature sans identifiant communautaire est rattachée à la fiche des notes officielles **de même nom et de longueur à 3 % près**, sinon elle reste sans circuit.
   - Aucun identifiant GT7 n'est inventé : SUGO garde l'identifiant interne des notes officielles.
2. **Reconnaissance anticipée en roulant** (`TrackGeometryRecognizer.early`). Elle conclut dès 250 m et 20 échantillons si toutes ces conditions sont réunies :
   - **une seule** signature reste compatible sur les 72, y compris celles sans circuit ;
   - l'écart moyen est d'au plus 10 m ;
   - le sens de roulage est net ;
   - le site n'a pas de variante sans signature.

   Sinon, la règle d'avant s'applique : 700 m et 40 échantillons.
3. **Position à l'arrêt** (grille, stands) : `positionCandidates` après 0,5 s d'immobilité, une fois par session.
   - Un seul tracé à moins de 30 m et un seul sens possible : circuit **probable** (`TrackEvidence.POSITION_COMPATIBLE`), circuit de travail, jalon `TRACK_CANDIDATE`, annonce « Circuit probable d'après la position sur la grille ».
   - **Jamais `GEOMETRY_MEASURED`** : un point seul ne prouve pas le circuit.
   - Le circuit de la fiche Sport n'est jamais utilisé comme preuve (#51/#53).
4. **Tracé confirmé en roulant** : `GEOMETRY_MEASURED` et jalon `TRACK_MEASURED`, qui alimentent la reconnaissance de l'épreuve Sport (#57) sans `selectProfile`.

## Résultats JVM (`TrackZeroTouchTest`, `TrackGeometryTest`)

| Mesure (synthétique) | Résultat |
|---|---|
| Erreurs de circuit (anticipé, 3 départs × 2 sens × toutes signatures, décalage 8 m, bruit ±4 m) | **0** |
| Reconnus sur ce lot | 294 / 300 |
| Distance de reconnaissance : médiane / 90 % / max | 631 m / 2 217 m / 17 123 m (Nordschleife) |
| SUGO, 6 départs différents | reconnu à 251–424 m (avant : inconnu à 702 m le 28/10, faute de signature) |
| Laguna Seca (3 602 m, voisin de SUGO en longueur) | reconnu Laguna Seca, jamais SUGO |
| Fuji, Suzuka (variante sans signature) | jamais avant 700 m |
| **Position à l'arrêt désignant un seul tracé** | **68 / 1 549 positions (4,4 %)**. Médiane : plusieurs tracés à moins de 30 m. |
| Position à l'arrêt : bon tracé parmi les candidats | 1 549 / 1 549 |
| Coût JVM | ~1,1 µs par position en roulant ; ~0,25 ms par examen à l'arrêt (une fois par session) |

**Conclusion honnête** : la position sur la grille ne suffit presque jamais à nommer le circuit. Les repères X/Z des circuits GT7 se superposent autour de l'origine. Le gain réel vient de la **reconnaissance en roulant** : dès 250 m pour SUGO et les sites sans variante inconnue, sinon 700 m comme avant.

## À valider sur PS5 (campagne à venir)

1. SUGO réel : la capture MIT est-elle dans le même repère que la PS5 ? Le diagnostic « repère miroir » du journal le dira.
2. Positions réelles sur la grille, sur la prégrille et en sortie des stands en qualification : proportion de candidats uniques.
3. Délai entre le premier paquet et `TRACK_MEASURED`, et entre le premier paquet et le briefing (jalons #57, exportés au journal).
4. Faux positifs sur des circuits absents des signatures : 17 configurations sur 122 n'ont pas de signature gardée, ni en sens direct ni en inverse (105 sont couvertes).
5. L'orientation de la voiture (quaternion `rotation*`) n'est **pas** utilisée : son repère n'est pas validé.
