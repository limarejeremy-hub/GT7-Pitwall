# Format C et Gap Hunter

Rapport de recherche complet : [GAP_HUNTER_RECHERCHE.md](GAP_HUNTER_RECHERCHE.md).

## État des lieux technique (avant cette étape, `05e8e0c`)

- **Télémétrie** :
  - `GT7Crypto` gère les formats A, B et ~ (Salsa20, nonce dérivé de 0x40) ;
  - `TelemetryParser` lit tous les champs documentés ;
  - `TelemetryClient` demande ~ puis se replie sur A.
- **Position** : `PositionTracker` lit 0x84 et 0x86, validés sur PS5 en course hors ligne et en Sport A. `PositionProbe`
  garde le journal de diagnostic.
- **Adversaires** : point d'extension `OpponentsSource`, branché sur `NoOpponentsSource` (liste vide).
- **Tests** : 339 tests verts dans `:core`. L'application Android ne se compile qu'en CI GitHub.

## Ce qui change

### Format C (368 octets)

Ces informations ont été vérifiées dans les sources de MacManley/gt7-udp :
- `GT7UDPParser.cpp`, lignes 13 à 16 et 117 à 133 ;
- `GT7UDPParser.h`, lignes 108 à 114 ;
- `README.md`, lignes 46 et 216 à 227.

| Offset | Type | Champ | Dans Pitwall |
|---|---|---|---|
| 0x158 | 4 caractères | surface sous chaque pneu (T asphalte, C vibreur, D terre, G herbe, S sable, s neige) | `surfaces` |
| 0x15C | int32 | chrono du tour en cours (ms) | `currentLapTimeMs` |
| 0x160 | 2 flottants | angle des roues avant gauche et droite (rad) | `frontWheelAngleLeftRad/RightRad` |
| 0x168 | flottant | empattement (m) | `wheelBaseM` |
| 0x16C | 4 caractères | catégorie (« GR3 », terminée par un octet nul) | `carCategory` |

- **Demande** : heartbeat « C ». Constante XOR 0xDEADBEEF, la même que B : seule la taille les distingue.
- **Repli automatique** : C, puis ~, puis A, si un format reste sans paquet lisible pendant 4 s. La carte de
  connexion indique les formats sans réponse (« Sans réponse lisible : format C ; format ~ utilisé »). Elle signale
  aussi quand la PS5 envoie un autre format que celui demandé, si un flux était déjà ouvert par une autre app.
- **Les formats A, B et ~** sont inchangés.
- **Codes inconnus** : un code de surface non documenté reste « inconnue » et n'est jamais compté comme de
  l'asphalte. Une catégorie illisible reste vide.
- **Affichage seulement** : le chrono du tour en cours, les surfaces, la catégorie et l'empattement sont affichés dans
  la carte Voiture. **Aucune annonce radio** n'est faite avant validation sur PS5.
- **Paquets plus longs** : un paquet plus long qu'un format connu est déchiffré en entier. Ses octets en plus restent
  visibles dans Gap Hunter.

### Gap Hunter (Diagnostic radio → Outils de développement → carte « Gap Hunter »)

- **Exploration active** : l'interrupteur est désactivé par défaut. Désactivé, le client UDP ne lui passe aucun
  paquet, donc il ne coûte rien.
- **Pour chaque mot de 4 octets** :
  - statut connu, incertain ou inconnu, d'après PDTools, gt7-udp et gt7-datalogger ;
  - forme : constant, compteur, flottant plausible, entier ;
  - changements, valeurs extrêmes, nombre de valeurs distinctes.
- **Pistes** :
  - c'est un mot inconnu ou incertain qui change autour d'au moins 80 % des changements de 0x84 ou des changements
    de tour, alors qu'il change rarement le reste du temps (hasard ≤ 30 %) ;
  - il faut au moins 3 événements ;
  - **une piste n'est pas une preuve.**
- **Journal** :
  - une ligne par seconde, plus une ligne à chaque événement (changement de 0x84, de tour, d'état) ;
  - chaque ligne : position X/Z, vitesse, carburant, chrono du tour (C), puis les mots inconnus et incertains en
    hexadécimal ;
  - l'état « en course » ne dépend pas du drapeau « en piste », absent des captures réelles en roulant ;
  - 1 500 lignes au maximum, soit environ 25 minutes.
- **Repères** : « J'ai doublé », « Doublé », et l'écart lu sur le HUD (texte libre), pour comparer avec le journal.
- **Export** : un fichier texte partagé via Android, sans adresse IP, sans identifiant et sans son.
- **Silencieux** : rien n'est transmis à la stratégie ni à la radio.

### Sonde des formats de heartbeat

- **Principe** : chaque lettre de A à Z, puis « ~ », est essayée tour à tour.
- **Pour chaque lettre** :
  - un silence, jusqu'à ce que le flux précédent s'arrête : 2 s sans paquet, 15 s au plus ;
  - puis 6 s de heartbeats avec cette lettre.
- **Résultat** : la taille des paquets reçus, et la clé connue qui les déchiffre, s'il y en a une.
- **Intérêt** : un format inconnu (nouvelle taille, ou clé inconnue) apparaîtrait en vert.
- **Interface utilisée** : seulement l'interface publique de GT7, un octet UDP vers le port 33739 de notre PS5.
- **Pendant la sonde**, la télémétrie est coupée : il faut la lancer **dans les menus**. Elle dure 4 à 9 minutes.
  Ensuite, le format C est redemandé.

### Adversaires

`OpponentInfo` porte maintenant les champs suivants :
- la provenance ;
- l'horodatage (`measuredAtMs`) ;
- la fiabilité (0 à 1) ;
- le type : mesure, estimation ou indisponible ;
- la relation : devant, derrière ou leader.

`announceable()` refuse tout ce qui n'est pas une mesure directe, fiable (≥ 0,9) et récente (≤ 5 s).
`NoOpponentsSource` reste la seule source branchée.

## Tests automatisés (données synthétiques)

- **`PacketCTest`, 8 tests** :
  - format C : taille, heartbeat, constante ;
  - chiffrement comparé à la règle de nonce publiée ;
  - décodage des nouveaux champs ;
  - pas de champ C inventé pour A et ~ ;
  - catégorie illisible ;
  - paquet plus long ;
  - identification des clés ;
  - **paquet A réel capturé par un autre projet** (vwhitteron/gt-telemetry, MIT), décodé par notre code : vitesse,
    régime, tours, carCode 3311, et les clés B, C et ~ qui échouent.
- **`TelemetryClientTest`, 4 tests** avec une fausse PS5 locale :
  - C demandé d'abord ;
  - repli de C vers ~ ;
  - repli de C vers ~ puis vers A ;
  - sonde des formats, avec un format « D » hypothétique.
- **`GapHunterTest`, 6 tests** :
  - silence quand l'exploration est désactivée ;
  - corrélation plantée trouvée, bruit et champs connus ignorés ;
  - comptage des tailles ;
  - octets au-delà de C ;
  - journal borné et export sans adresse réseau ;
  - garde d'annonce des écarts adverses.

**Ces tests prouvent le fonctionnement du code, pas le comportement de GT7.** Le seul paquet réel est un paquet A ;
aucun paquet B, ~ ou C réel n'a encore été publié ni capturé pour servir de référence.

## Résultats PS5 (21 octobre 2026, course hors ligne, 20 voitures, Gr.3)

Premier export réel : 49 851 paquets en 14 minutes, 7 tours d'une course de 12 tours, départ P20, un arrêt au stand.
Aucun repère du pilote n'a été posé.

| Point | Constat | Statut |
|---|---|---|
| Format C | 49 851 paquets de 368 octets, aucun illisible, aucun repli | **confirmé sur PS5** |
| Chrono du tour (0x15C) | au passage de la ligne, il vaut le temps du tour à 2–10 ms près (119 566 contre 119 564 ; 122 257 contre 122 247), puis repart de 0 ; il avance de 1 000 ms par seconde | **confirmé** |
| Chrono en pause | figé (12 093 ms avant et après la pause) | **confirmé** |
| Chrono au stand | continue pendant l'arrêt | **confirmé** |
| Surfaces (0x158) | 447 changements ; dernier « TTTT » (asphalte) | à confirmer avec un passage volontaire sur un vibreur |
| Catégorie (0x16C) | « GR3 » | **confirmé** |
| Mots réservés 0xD4–0xF3 | toujours 0 | confirmé : rien de caché |
| Couple par roue, récupération d'énergie | toujours 0 (voiture thermique) | attendu |
| 0x12C (B) | flottant variable, −31,4 à 101,3 | inconnu (vitesse du volant ?) |
| 0x154 (~) | flottant de −0,71 à 1,66 | compatible avec un couple moteur normalisé |
| Drapeau « en piste » | levé en course (`0x0009`) | confirmé sur notre PS5 |
| Drapeau « chargement » | levé quelques instants **pendant l'arrêt au stand** | constat nouveau |
| 0x84 | −1, puis 1, puis 20 (grille P20/20) ; 20 → 19 → 18 en dépassant ; 18 → 19 → 20 pendant l'arrêt au stand | cohérent avec les essais précédents |
| Après le dernier événement | pendant environ 100 s, les valeurs restent figées, alors que les numéros de paquet avancent et qu'aucun drapeau de pause n'est levé | constat nouveau : sortie de course probable |
| Écarts adverses | **aucun mot inconnu ou incertain ne change autour des 4 changements de 0x84** | **aucune source trouvée** |

**Conclusion de l'essai** :
- le format C et son chrono sont fiables ;
- les paquets ne contiennent aucune trace des autres voitures.

**Pour le prochain essai** :
- poser des repères (« J'ai doublé », « Doublé ») ;
- passer exprès sur un vibreur et dans l'herbe : les surfaces sont maintenant dans le journal ;
- lancer la sonde des formats dans les menus.

## Protocole PS5 (environ 15 minutes)

1. **Format C** (menus) : lancer la télémétrie.
   - Attendu : « format C » dans la carte de connexion. Sinon, noter le message de repli.
2. **Chrono et surfaces** (hors ligne, 2 tours) :
   - le « Chrono du tour (format C) » doit repartir de 0 au passage de la ligne, et rester cohérent avec le chrono du
     HUD ;
   - roule volontairement sur un vibreur, puis dans l'herbe : les surfaces doivent passer à « vibreur » puis
     « herbe » ;
   - noter le comportement du chrono au stand, en pause et avant le départ.
3. **Sonde des formats** (menus) : « Lancer la sonde », attendre la fin, puis faire une capture de la carte.
4. **Gap Hunter en course** (hors ligne avec 20 voitures, puis Course Sport) :
   - activer l'exploration et faire la course normalement ;
   - à chaque dépassement en ligne droite sûre : « J'ai doublé » ou « Doublé » ;
   - quand l'indicateur de position du HUD montre un écart, le noter : « devant 1,2 », par exemple ;
   - à l'arrivée : « Exporter », puis m'envoyer le fichier.
5. **PS VR2** (facultatif) : refaire l'étape 4 en VR. Les repères peuvent être posés après coup, en notant l'heure.

À noter : tout paquet de taille inattendue, toute piste affichée, et tout écart lu sur le HUD.
