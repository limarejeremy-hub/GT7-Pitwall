# Champs UDP de GT7 : ce que Pitwall exploite, ce qu'il n'exploite pas encore

Revue de la PR #37 (P1-E) et consigne du pilote du 27/10 : examiner `0x154`, puis tous les champs encore inexploités.
Sont comparés :
- la carte de Gap Hunter (`GapHunter.FIELDS`) ;
- le décodeur Pitwall (`TelemetryParser`) ;
- les travaux publics : gt7-udp (MacManley, `README.md` et `GT7UDPParser.h`), PDTools (Nenkai), et l'issue 150 et la
  PR 158 de gt7-datalogger (jbhoorasingh).

Le forum GTPlanet, source primaire de l'issue 150 (messages 372 à 386), n'est pas joignable depuis l'environnement de
développement. Ses chiffres sont repris **tels que résumés par l'issue 150**, sans relecture directe.

**Rien de ce qui est marqué « expérimental » ne décide quoi que ce soit dans Pitwall.**

## 0x154 — couple ou charge moteur délivré, normalisé (expérimental)

| Question | Ce qu'on sait | Source | Niveau de preuve |
|---|---|---|---|
| Présence | Dernier flottant du format ~ (344 octets), donc aussi du format C | gt7-udp `.h` ; notre carte | Certain |
| Nom | `UNKNOWNFLOAT11` (gt7-udp), `Unk7` (PDTools), `engine_torque_norm` (gt7-datalogger, PR 158) | sources citées | Pas de nom officiel |
| Signification | « Normalized delivered engine torque/load » | gt7-datalogger issue 150 | Rétro-ingénierie communautaire |
| Corrélations | +0,87 à +0,90 avec l'accélérateur ; +0,92 avec le turbo | issue 150 | Rapporté, non revérifié |
| Frein moteur | Négatif dans 99,4 % des paquets en décélération moteur | issue 150 | Rapporté |
| Point mort, débrayé | Exactement 0 | issue 150 | Rapporté |
| Plage | Non bornée à ±1 : Porsche Taycan −2,7 à +9,9 | issue 150 | Rapporté |
| Notre relevé | **−0,71 à 1,66** (21/10, course hors ligne, Gr.3, format C) | `docs/GAP_HUNTER.md` | **Mesuré**, plage seulement |
| Bruit | Moins de 1e-5 dans les replays : ramené à 0 | issue 150 | Rapporté |
| Unité | **Inconnue.** Normalisé par quoi (couple maximal ? courbe à ce régime ?) : inconnu | — | — |
| Selon les voitures | Thermique, électrique (Taycan > 1), hybride : la normalisation diffère probablement | issue 150 | Hypothèse |

**Décodage** : `TelemetryPacket.engineTorqueNorm`, formats ~ et C seulement.
- Formats A et B : `null` (l'offset n'existe pas).
- Valeur non finie : `null` (des paquets GT7 contiennent des flottants non finis, jegr78/gt-racing-broadcast #717).
- Tests : `ExperimentalFieldsTest`.

**Ce qu'on n'en tire jamais** : un couple en Nm, une puissance à la roue, une usure.

**Validation sur PS5** : sonde `TorqueProbe`, carte Atelier « Champs GT7 expérimentaux ». Elle mesure sur ta voiture la
corrélation avec l'accélérateur en prise, la part de zéros au point mort, la part de négatifs en frein moteur et la plage.
Tests : `TorqueProbeTest`.

## Nouveau croisement n°14 : accélérateur × couple délivré × régime × rapport × glissement des 4 roues × TCS × accélération × surface

Ce que la combinaison peut révéler :

| Phénomène | Signature attendue | Pitwall aujourd'hui |
|---|---|---|
| Perte de motricité des pneus | Même pédale, **même couple** délivré, glissement en hausse, accélération en baisse | Détecté par `TractionMonitor` (sans le couple) |
| Coupure électronique (TCS) | Même pédale, **couple en baisse**, TCS actif, glissement contenu | Comptée comme perte de motricité : le TCS coupe parce que ça patine |
| Perte côté moteur ou commande | Même pédale, **couple en baisse sans TCS**, glissement stable | **Écartée des pneus** quand 0x154 est présent (seul usage décisionnel, et seulement pour écarter) |
| Moins de puissance sans changer de couple | Couple égal, glissement stable, accélération en baisse | Dit : « pas une perte de motricité » (aspiration, trajectoire, pente) |
| Turbo pas encore en charge | Couple qui monte avec retard à pédale égale | À étudier : corrélation turbo +0,92 |
| Différence de performance entre deux trains de pneus | Glissement et accélération à couple égal, avant et après un arrêt | À faire (croisement n°6) |

Le régime n'est **pas** un critère de comparaison : à vitesse égale, un patinage le fait monter (test dédié). Le rapport
et la vitesse le sont.

Tests : `TractionReviewTest.lessDeliveredTorqueWithoutTcsIsNotBlamedOnTheTyres` (couple en baisse sans TCS → stable ;
couple en baisse avec TCS → perte de motricité).

## Découverte à vérifier en priorité : disponibilité des formats selon le mode

Le README de gt7-udp indique :
- format B : **« not available in Sport Mode »** ;
- format ~ : **« not available in Replays »**.

Si c'est vrai :
- **en course Sport, seul le format A (296 octets) arriverait**, donc ni surfaces, ni 0x154, ni angle des roues ;
- cela expliquerait nos 152 969 paquets de 296 octets ;
- le croisement 1 reste utilisable, mais jamais « confirmé » (voir P0-C), et les croisements 3 et 14 seraient
  indisponibles en Sport.

**À vérifier sur PS5** : noter la taille des paquets (Atelier) en course Sport et en course hors ligne.

## Inventaire des autres champs inexploités

| Offset | Format | Champ | Décodé par Pitwall | Utilisé | Piste |
|---|---|---|---|---|---|
| 0x1C–0x28 | A | Rotation (quaternion ; gt7-udp : tangage, lacet, roulis, cap) | Oui | Non | Angle de dérive : convention des axes à vérifier en jeu |
| 0x2C, 0x34 | A | Vitesse angulaire X, Z | Oui | Non | Roulis et tangage au freinage (transfert de charge) |
| 0x38 | A | Hauteur de caisse | Oui | Non | Talonnage (croisement n°11) |
| 0x54 | A | Pression d'huile | Oui | Non | Rien à en tirer en course connu |
| 0x93 | A | Octet inconnu (rembourrage selon gt7-udp) | Non | Non | — |
| **0x94–0xA0** | A | **Plan de la route (normale) et distance au plan** | **Oui, expérimental** | Non | **Pente et dévers sous la voiture** : contrôler qu'une sortie en montée n'est pas prise pour une perte de motricité |
| 0xC4–0xD0 | A | Hauteur de suspension par roue | Oui | Non | Vibreurs, talonnage (n°11) |
| 0xD4–0xF3 | A | 8 flottants « réservés » | Non | Non | Toujours 0 le 21/10 |
| 0xF4 / 0xF8 / 0xFC | A | Embrayage, engagement, régime embrayage → boîte | Oui | Non | Patinage d'embrayage au départ, durée des changements de rapport |
| 0x100, 0x104–0x11C | A | Vitesse max de boîte, rapports | Oui | Non | **Régime attendu** d'après la rotation des roues motrices et le rapport : test indépendant du patinage (couple final inconnu, à étalonner) |
| 0x120 | A | 8e rapport ou inconnu | Non | Non | — |
| 0x128 | B | Angle du volant | Oui | Non | Croisement n°3 pour les formats B et ~ (aujourd'hui format C seulement) |
| **0x12C** | B | **Vitesse du volant** (gt7-udp) ; −31 à 101 le 21/10 | **Oui, expérimental** | Non | Corrections au volant = instabilité ou survirage |
| 0x130–0x138 | B | Sway, heave, surge : **accélérations X, Y, Z** selon gt7-udp | Oui | Non | **Accélération longitudinale mesurée directement**, à comparer à Δv/Δt (unité et signe à vérifier) |
| **0x13C / 0x13D** | ~ | **Pédales filtrées** | Oui | Non | **Hypothèse** : pédale après les aides. Un écart avec la pédale du pilote mesurerait une coupure électronique. À vérifier |
| 0x13E | ~ | Type de motorisation (0 thermique, 4 électrique, 9 quatre moteurs) | Oui | Détection électrique | — |
| **0x13F** | ~ | Récupération d'énergie transmise | **Oui, expérimental** | Non | — |
| **0x140–0x14C** | ~ | **Couple par roue** (vectorisation ; + motricité, − freinage ou récupération) | **Oui, expérimental** | Non | Toujours 0 sur nos voitures thermiques ; utile seulement pour les voitures à vectorisation |
| 0x150 | ~ | Récupération d'énergie | Oui | Affichée (électrique) | — |
| **0x154** | ~ | **Couple moteur normalisé ?** | **Oui, expérimental** | **Seulement pour écarter les pneus** | Voir plus haut |

## Expériences PS5 à faire (sans HUD, comme d'habitude)

1. **Formats.** Lire la taille des paquets (Atelier) en course Sport et en course hors ligne.
2. **0x154.** Après une course, lire la carte « Champs GT7 expérimentaux ».
   - Attendu : corrélation positive forte, 100 % de zéros au point mort, une large majorité de négatifs en frein moteur.
   - Refaire avec une voiture turbo et une voiture électrique.
3. **Pédales filtrées.** Accélérateur à fond en sortie lente, TCS au maximum, puis TCS coupé : la pédale filtrée
   diffère-t-elle de la pédale ? (à exporter via Gap Hunter, outils de développement)
4. **Plan de la route.** Une montée et une descente connues : le signe de la composante « avant » doit changer.
