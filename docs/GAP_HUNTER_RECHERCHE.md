# Gap Hunter : rapport de recherche

Date : 20 octobre 2026.
Question : existe-t-il un moyen légitime et non intrusif d'obtenir en direct l'écart (en secondes) avec la voiture
devant et la voiture derrière dans GT7 ? On vise surtout les courses Sport publiques, sur une PS5 non modifiée, avec le
téléphone sur le Wi-Fi local, sans PC, sans caméra et sans matériel supplémentaire.

Règles de rédaction :
- un **fait** est sourcé (URL, ou fichier et ligne) ;
- une **hypothèse** est marquée comme telle ;
- aucune valeur n'est inventée ;
- « vérifié » veut dire lu dans le code source, ou recalculé par nos soins quand c'est précisé.

---

## 1. Résumé

- **Aucune source légitime d'écarts avec les adversaires n'est démontrée pour GT7.** Le flux UDP « Simulator
  Interface » ne décrit que la voiture du joueur, quel que soit le format (A, B, ~ ou C). Aucun des 10 projets
  examinés n'en tire d'écart avec d'autres voitures. Trois d'entre eux écrivent explicitement qu'ils n'en calculent
  pas.
- **Le paquet C est confirmé** : heartbeat `'C'`, 368 octets (0x170), constante XOR `0xDEADBEEF` (la même que B).
  Il ajoute 24 octets à partir de 0x158 : surfaces, chrono du tour en cours, angles des roues, empattement,
  catégorie. **Aucun champ ne concerne les adversaires.**
- **Le LiveTiming API de PDTools existe** : un WebSocket JSON servi par la console elle-même, qui contient bien
  `Standings[]` et `Diff[]` (écarts entre toutes les voitures). Mais il n'est actif que sur une build debug, avec un
  argument de lancement interne, une option LAN cachée ou des permissions de compte « live events ». D'après
  l'auteur, on ne peut y accéder qu'avec cette liste blanche (« whitelist ») ou un jeu moddé. **Il est inaccessible
  dans nos contraintes.**
- **Position 0x84/0x86** : les sources anciennes (PDTools, gt7-udp) disent « position avant le départ, −1 une fois la
  course lancée ». Deux projets récents ont observé sur de vraies sessions une mise à jour **en direct** de la position
  (P15 → P1). Nos propres essais PS5 du 20/10/2026 l'ont validée en course hors ligne et en Course Sport A
  ([DIAGNOSTIC_POSITION_UDP.md](DIAGNOSTIC_POSITION_UDP.md)). Le comportement dans les autres modes n'est pas établi.
- **Vecteur de test trouvé** : un vrai paquet A chiffré de 296 octets, tiré des tests de `vwhitteron/gt-telemetry`.
  Nous l'avons déchiffré avec notre propre implémentation Salsa20 (valeurs en §3.4). Nous n'avons trouvé aucun
  vecteur réel pour B, ~ ou C.
- **Ce qui reste possible** :
  1. des écarts entre **volontaires coopérants** seulement, en partageant la distance parcourue et le temps de course
     (même algorithme que CrewChief) ;
  2. des **événements** (dépassement : écart ≈ 0 à l'instant où la position change) ;
  3. un **temps de référence adverse a posteriori**, à partir des replays (la télémétrie d'un replay suit la voiture
     regardée).

---

## 2. Sources examinées

### Dépôts clonés et lus

Les clones sont dans `scratchpad/dig/`. Aucun code téléchargé n'a été exécuté.

| Dépôt | Commit lu | Ce qu'on y a cherché |
|---|---|---|
| [Nenkai/PDTools](https://github.com/Nenkai/PDTools) | `2a9637d` (19/10/2026) | LiveTimingApi, SimulatorInterface, Crypto |
| [MacManley/gt7-udp](https://github.com/MacManley/gt7-udp) | `a768574` (19/09/2026), historique complet | structures A/B/~/C, constantes XOR |
| [vwhitteron/gt-telemetry](https://github.com/vwhitteron/gt-telemetry) | `02c2339` (10/02/2026, module déprécié) | vecteur de test chiffré, Kaitai |
| [jegr78/gt-racing-broadcast](https://github.com/jegr78/gt-racing-broadcast) | `5463c31` (19/10/2026) | position, écarts, « race engineer » |
| [jbhoorasingh/gt7-datalogger](https://github.com/jbhoorasingh/gt7-datalogger) | HEAD au 20/10/2026 | parseur C, XOR, replays |
| [snipem/gt7dashboard](https://github.com/snipem/gt7dashboard) | HEAD | delta par distance, 0x84 |
| [RaceCrewAI/gt-telem](https://github.com/RaceCrewAI/gt-telem) | HEAD | tests (uniquement des valeurs synthétiques) |
| [ThrottleGeist/gt7-telemetry-relay](https://github.com/ThrottleGeist/gt7-telemetry-relay) | HEAD | rien sur les adversaires |
| [denwilliams/gt7-race-engineer](https://github.com/denwilliams/gt7-race-engineer) | HEAD | limites déclarées |
| CrewChiefV4 : [miroir GitHub](https://github.com/mrbelowski/CrewChiefV4) (figé en 2019) et [GitLab officiel](https://gitlab.com/mr_belowski/CrewChiefV4) (release 4.19.5.8 du 19/10/2026) | | algorithme `DeltaTime`, jeux supportés |
| [kaiusl/KLPlugins.DynLeaderboards](https://github.com/kaiusl/KLPlugins.DynLeaderboards) | HEAD | calcul d'écart par spline (ACC et autres) |
| [MacManley/f1-25-udp](https://github.com/MacManley/f1-25-udp) | HEAD | champs LapData de F1 25 |

### Pages web consultées

- [PDTools issue #18 : « 'C' telemetry format discovered »](https://github.com/Nenkai/PDTools/issues/18), ouverte
  le 17/03/2026.
- [gt-racing-broadcast issue #854](https://github.com/jegr78/gt-racing-broadcast/issues/854) et
  [PR #862](https://github.com/jegr78/gt-racing-broadcast/pull/862) : position en course.
- [SimHub issue #2254](https://github.com/SHWotever/SimHub/issues/2254) : demande B/~/C, fermée le 12/05/2026.
- [GTPlanet, fil « GT7 & PSVR2 »](https://www.gtplanet.net/forum/threads/gt7-psvr2.409371/) (extraits de recherche
  seulement : le domaine ne se résout pas depuis notre outil) : un utilisateur de « GT Race Engineer » regrette
  l'absence d'écarts, « more of a GT7 limitation ».
- [GTPlanet, « Overview of GT7 Telemetry Software »](https://www.gtplanet.net/forum/threads/overview-of-gt7-telemetry-software.418011/)
  (extraits) : la télémétrie ne contient pas les autres voitures.
- [pitcrewhub.com](https://pitcrewhub.com/) et hector-ai.app : DNS en échec pour WebFetch et curl, donc seuls les
  extraits de recherche ont été lus (voir §6).

---

## 3. Paquet C vérifié

### 3.1 Requête et chiffrement

Les références de lignes portent sur `MacManley/gt7-udp/src/GT7UDPParser.cpp`, sauf mention contraire.

| Élément | Valeur | Source |
|---|---|---|
| Ports | envoi du heartbeat vers 33739, réception sur 33740 | lignes 11–12 ; PDTools `SimulatorInterfaceClient.cs` 36–37 |
| Heartbeat | un octet ASCII : `'A'`, `'B'`, `'~'` ou `'C'` | lignes 36–37 et 44–47 |
| Tailles | A = 296 (0x128), B = 316 (0x13C), ~ = 344 (0x158), C = **368 (0x170)** | lignes 13–16 |
| Clé Salsa20 | les 32 premiers octets de `"Simulator Interface Packet GT7 ver 0.0"` | ligne 17 ; PDTools `SimulatorInterfaceCryptorGT7.cs` 22 |
| Nonce | `iv1` = int32 LE lu à 0x40 ; `iv2 = iv1 ^ XOR` ; nonce = `iv2` (LE) puis `iv1` (LE) | lignes 123 et 138–145 |
| XOR de A | `0xDEADBEAF` | ligne 126 |
| XOR de B | `0xDEADBEEF` | ligne 128 |
| XOR de ~ | `0x55FABB4F` | ligne 130 |
| XOR de C | **`0xDEADBEEF`**, la même que B | ligne 132 ; confirmé dans l'issue PDTools #18 (« same as Packet B ») et dans gt7-datalogger `backend/app/telemetry/crypto.py` 29–37 |
| Magie déchiffrée | `0x47375330` (octets « 0S7G ») | gt-telemetry `internal/utils/salsa20.go` 29 |

- **Historique** : C a été ajouté dans le commit `7c0455e` du 17/03/2026 (« Packet C Update »). Les 12 octets à 0x160
  y étaient encore `UNKNOWNFLOATS[3]`. Ils ont été nommés dans le commit `74535ad` du 30/03/2026, avec ce crédit :
  « credit to vwhitteron, Chi-Ho, and Beregoz ».
- **Disponibilité** : le README (ligne 46) date l'apparition de C à « around Update ~1.68 ». Dans l'issue #18, Nenkai
  écrit le 31/03/2026 : « no other packet "modes" as of 1.68 ».
- **PDTools ne gère pas C.** `SimInterfacePacketType` (`SimulatorInterfaceClient.cs` 195–214) ne connaît que trois
  types. Un paquet d'une autre taille lève `InvalidDataException` (ligne 121).

### 3.2 Extension ~ (0x13C à 0x157)

Sources : gt7-udp `GT7UDPParser.h` 98–106 ; PDTools `SimulatorPacket.cs` 333–339 et 434–443.

| Offset | Type | gt7-udp | PDTools | Statut |
|---|---|---|---|---|
| 0x13C | uint8 | throttleFiltered | Unk1 (affiché « ThottleFiltered ») | probable |
| 0x13D | uint8 | brakeFiltered | Unk2 (« BrakeFiltered ») | probable |
| 0x13E | uint8 | UNKNOWNUINT81 | Unk3 (« 4 = electric », « CarType (?) ») | incertain |
| 0x13F | uint8 | UNKNOWNUINT82 | NoGasConsumption (affiché « Unk4 ») | incertain |
| 0x140 | float[4] | torqueVectors[4] | Unk5 (Vector4) | incertain |
| 0x150 | float | energyRecovery | EnergyRecovery | probable |
| 0x154 | float | UNKNOWNFLOAT11 | Unk7 | inconnu |

### 3.3 Extension C (0x158 à 0x16F)

Sources : gt7-udp `GT7UDPParser.h` 108–114 ; gt7-datalogger `packet.py` 80–86, avec la structure `"<4si2ff4s"` et les
assertions de taille aux lignes 84–86.

| Offset | Type | Champ | Notes |
|---|---|---|---|
| 0x158 | char[4] | surfaceType | Une lettre par roue (ordre AVG, AVD, ARG, ARD supposé, non documenté). T = asphalte, C = vibreur, D = terre, G = herbe, S = sable, s = neige (README 216–227). Nenkai : « 23 surface types in total » qui retombent sur ces lettres. |
| 0x15C | int32 | currentLap | Chrono du tour en cours, en ms. L'issue #18 parle de uint32. |
| 0x160 | float[2] | wheelSteeringAngle | Angle des roues avant gauche et droite, en rad. Chi-Ho : gauche positif. |
| 0x168 | float | wheelBase | Empattement en m. Selon vwhitteron et Chi-Ho, plutôt l'empattement « côté gauche », dynamique (il varie avec le pincement et le braquage). |
| 0x16C | char[4] | carCategory | Chaîne terminée par un octet nul : GR1, GR2, GR3, GR4, GRB, GRN, GRX. |

- **Incohérence mineure** : le README de gt7-udp (ligne 156) écrit `struct PacketC : public PacketB`, alors que le
  `.h` (ligne 108) dérive de `PacketTilda`. Seul le `.h` donne 368 octets (344 + 24), c'est donc lui qui fait foi.
- **Coquille dans le code** : `GT7UDPParser.cpp` ligne 107 contient `if packetSize <= 0 ...` sans parenthèse
  ouvrante. Cela n'a aucun effet sur la mise en page des champs.

### 3.4 Vecteur de test réel (paquet A)

- **Où le trouver** : [gt-telemetry, `internal/utils/salsa20_test.go`](https://github.com/vwhitteron/gt-telemetry/blob/main/internal/utils/salsa20_test.go),
  lignes 13–51, variable `validSalsa20Content`. Ce sont 296 octets chiffrés. Ils commencent par `f6374a12433223cf`, et
  l'IV à 0x40 vaut `fb599f0c`.
- **Ce que le dépôt vérifie** : seulement que la magie vaut `0x47375330` (lignes 102–113).
- **Ce que nous avons recalculé** avec un Salsa20 écrit par nous et contrôlé sur le vecteur ECRYPT (set 1, vecteur 0,
  `E3BE8FDD8BECA2E3`) :

| Champ | Valeur décodée |
|---|---|
| iv1 / nonce | `0x0C9F59FB` / `54e732d2fb599f0c` |
| Magie | `0x47375330` avec XOR A. Les XOR de B et de ~ donnent une magie fausse : bon test négatif. |
| Position (0x04) | (−58,8746 ; 83,4699 ; 426,8152) |
| Régime (0x3C) | 7973,833 tr/min |
| Carburant (0x44 / 0x48) | 100 / 100 |
| Vitesse (0x4C) | 55,62648 m/s (200,26 km/h) |
| Eau / huile (0x58 / 0x5C) | 85 / 110 |
| Pneus (0x60–0x6C) | 78,65 / 80,01 / 85,24 / 84,05 °C |
| packetId (0x70) | 175771 |
| Tour (0x74 / 0x76) | 4 / 0 |
| Meilleur et dernier tour (0x78 / 0x7C) | 139965 ms tous les deux |
| Heure du jour (0x80) | 50015420 ms |
| **0x84 / 0x86** | **−1 / −1**, pendant que la voiture roule au tour 4 (mode inconnu) |
| Alerte régime (0x88 / 0x8A) | 8500 / 9000 |
| Vitesse max calculée (0x8C) | 287 |
| Drapeaux (0x8E) | `0x0008` : seul InGear est levé, **pas** CarOnTrack |
| Octet des rapports (0x90) | `0x24` : rapport 4, rapport suggéré 2 |
| Accélérateur / frein (0x91 / 0x92) | 255 / 0 |
| 0xD4–0xF3 | huit flottants à 0 |
| Rapports de boîte (0x104) | 2,556 ; 1,983 ; 1,602 ; 1,359 ; 1,172 ; 1,005 ; 0 ; 0 |
| carCode (0x124) | 3311, soit « 911 RSR (991) '17 » d'après la table du README de gt7-udp (ligne 592) |

- **Usage proposé** : un test unitaire de non-régression pour `GT7Crypto` en format A.
- **Ce qui n'existe pas** : aucun dépôt ne publie de vrai paquet chiffré B, ~ ou C.
  - Les tests de gt-racing-broadcast (`tests/test_gt7_crypto.py` 34–70) et du datalogger **fabriquent** leurs paquets.
    Ils ne valident donc que la symétrie, pas la conformité au jeu.
  - Le JSON de gt7dashboard (`test_data/broad_bean_raceway_time_trial_4laps.json`) contient des tours déjà décodés,
    pas des paquets bruts.

---

## 4. LiveTiming API de PDTools

### Ce que c'est (lu dans le code)

- **Transport** : un client WebSocket texte vers `ws://{host}/livetimingapi/` (`PDTools.LiveTimingApi/LiveTimingClient.cs`
  84). Le client envoie un message vide, puis reçoit en boucle des JSON d'au plus 0x2000 octets (lignes 27 et 86–98).
- **Hôte** : la console elle-même. L'outil de test s'utilise ainsi : `<host of PS4/PS5 i.e "192.168.0.23:8888">`
  (`PDTools.LiveTimingApiTestTool/Program.cs` 36). **Le port 8888 n'y est qu'un exemple**, aucun port n'est fixé dans
  le code. Le README principal (ligne 17) parle d'un « TinyWeb server enabled through mods ».
- **Messages** (`LiveTimingClient.cs` 102–135) : `RaceInfo`, `RaceState`, `Entry`, `Condition`, `ConsumeState`,
  `BestlapEntry`.
- **`LiveTimingRaceState`** (`Entities/LiveTimingRaceState.cs` 15–47) :
  - `Diff[]` : « Time differences between each entry, ordered by standing » ;
  - `Standings[]` : « Ordered entry indices for the current standings » ;
  - et aussi `Time`, `TopLaps`, `NbEnabledRanking`, plus l'heure, la minute et la seconde.
  - L'unité de `Diff` n'est pas documentée (int, probablement des ms : **hypothèse**).
- **`LiveTimingEntry`** (`Entities/LiveTimingEntry.cs`) : pour chaque voiture, `DriverName`, `CarName`, `Country`,
  `BestLapTime`, `LastLapTime`, `SectorTime[]`, `Sector`, `TotalLaps`, `TotalTime`, `InPit`, `NbPitIn`, `NbPenalty`,
  `FrontTire`, `RearTire`, `DNF`, etc.
- **Ajouté le 22/09/2022** (commit `2e7ab94`, « Add PDTools.LiveTimingApi »).

### Conditions d'activation

Commentaire de `Program.cs`, lignes 22–29 :
- « The version build is "debug" » ;
- « "stagelink" command line argument is set » ;
- « GTGame.UserData.OptionData.Get().IsLANMode is set (from Option settings, not available to plebs) » ;
- « HasPermissionToHostLiveEvents is set on the online account » ;
- « HasPermissionToSpectateLiveEvents is set on the online account ».

Ce qu'en disent les README :
- `PDTools.LiveTimingApi/README.md` 3 : « Requires user whitelist flag or game modded to enable features » ;
- `PDTools.SimulatorInterface/README.md` 7 : « Normally you would want to use the LiveTiming API instead, but PDI
  closes that down ».

Le commentaire ne précise pas comment ces conditions se combinent (« et » ou « ou »). Le README résume par « liste
blanche **ou** jeu moddé ».

### Conclusions

| Question | Réponse |
|---|---|
| Protocole | WebSocket (HTTP Upgrade), messages JSON. Confirmé par le code. |
| Hôte et port | La console, sur un port non spécifié. Confirmé : aucun port n'est codé en dur. |
| Authentification | Pas de jeton dans le client. L'autorisation est côté console : build debug, argument `stagelink`, mode LAN caché, ou permissions de compte PSN/PD. Confirmé par les commentaires. |
| Joignable sur une PS5 retail, compte normal | **Non** d'après l'auteur (« PDI closes that down », « whitelist or modded »). Nous ne l'avons pas testé. |
| Nature | Service local de la console, destiné aux événements en direct de PD (diffusion, régie). Ce n'est pas un serveur PD sur Internet. **Probable** au vu des noms de permissions. |
| Dans nos contraintes | **Exclu.** L'activer demande un mod du jeu, ou une permission de compte réservée que nous ne pouvons pas obtenir. Utiliser un service réservé à des comptes autorisés reviendrait à contourner une restriction. |

- **Issue PDTools #18** : elle ne parle que du format C. On n'y trouve aucune mention du LiveTiming, des
  adversaires ou des écarts, et aucun hexdump.

---

## 5. Champs UDP incertains ou contestés

| Offset | Format | Point incertain | Sources |
|---|---|---|---|
| **0x84 / 0x86** | A+ | **Sens pendant la course.** PDTools : « Position of the car before the race has started. Will be −1 once the race is started » (`SimulatorPacket.cs` 155–165). gt7-udp dit la même chose (`.h` 66–67). Mais : | |
| | | — jegr78 #854 : enregistrements du 10/10/2026 à Grand Valley, « P15/15 at the start through P7 (lap 1) to P1/15 in lap 5 », 31 changements en 8 min, −1/−1 hors session ; | issue #854 |
| | | — denwilliams README 123 : « documented as pre-race values, but real GT7 telemetry shows them updating live » et « modes that don't send it » ; | README |
| | | — notre vecteur de test : −1/−1 pendant le tour 4 (mode inconnu) ; | §3.4 |
| | | — nos essais PS5 du 20/10/2026 : 0x84 suit le HUD en course hors ligne et en Sport A ; il compte les voitures pendant le matchmaking. | [DIAGNOSTIC_POSITION_UDP.md](DIAGNOSTIC_POSITION_UDP.md) |
| | | **Conclusion** : la position est mise à jour en direct au moins en course. La valeur vaut −1 dans certains modes (contre-la-montre ou essais : **hypothèse**). On ne sait pas si elle est recalculée en continu ou à des lignes de chronométrage. | |
| 0x8E bit 0 | A+ | CarOnTrack n'est pas levé dans deux captures d'autres projets (denwilliams README 121 : « 0x0418 while driving » ; notre vecteur : `0x0008`). **Mais sur notre PS5 (21/10/2026, course hors ligne), il est levé en course : drapeaux `0x0009`.** Le bit dépend donc peut-être du mode ou de la version. Les bits 12–15 sont inconnus (gt-telemetry `.ksy` flag13–16). | |
| 0xD4–0xF3 | A+ | 8 flottants inconnus. PDTools : « Seems to be reserved - server does not set that » (ligne 407). Ils valent 0 dans le vecteur. | |
| 0x120 | A+ | Huitième rapport de boîte, ou vide. Peut écraser carCode sur les boîtes de plus de 8 rapports (PDTools 419–421). | |
| 0x12C | B+ | PDTools : FillerFloatFB. gt7-udp : steeringAngularVelocity. Sur notre PS5 : flottant de −31,4 à 101,3, souvent 0, compatible avec une vitesse de rotation du volant (non prouvé). | |
| 0x13E–0x13F, 0x140–0x14F, 0x154 | ~ | Voir §3.2. | |
| 0x15C | C | currentLap signé ou non. Comportement en pause, au départ et au passage de la ligne non documenté. | issue #18 |
| 0x168 | C | Empattement « gauche » dynamique, ou valeur liée à l'Ackermann. | issue #18 |

**Aucun champ, dans aucun format, ne contient de données sur une autre voiture.** Seule exception de fait : en replay,
le flux décrit la voiture regardée (voir §7).

---

## 6. Ce qu'annoncent les autres outils GT7

| Outil | Ce qu'il annonce | Comment | Statut |
|---|---|---|---|
| jegr78/gt-racing-broadcast | Position Pn/N | Lit 0x84 et 0x86. « No gaps to other cars are inferred » (`src/docs/wiki/Race-Engineer.md` 65–68 ; `engineer-race-position.md` 10). | prouvé dans le code |
| denwilliams/gt7-race-engineer | Pas d'écarts | « It does not provide gaps to other cars, rivals or flags » ; « Non-goals: Gaps to other cars » (README 123 et 127). | prouvé dans le README |
| gt7-datalogger | Écart au meilleur tour du joueur seulement | `race_position` par tour ; replays (README 130 et 179). | prouvé dans le code |
| gt7dashboard | Delta entre deux tours **du joueur**, par distance | `calculate_time_diff_by_distance` (`gt7helper.py` 89–109) ; position en 0x84 (`gt7communication.py` 58–59). | prouvé dans le code |
| Pit Crew Hub (pitcrewhub.com) | Rapport vocal : tours restants, carburant, fenêtre de stand, « live pace delta » | Selon un extrait de recherche. Aucune mention d'écart avec les rivaux. Site illisible pour nous. | annonce, non vérifiée |
| hector-ai.app | Rien de trouvé. « SVR Hector » n'est cité que dans un fil GTPlanet, sans détail. | Site illisible pour nous. | inconnu |
| AI Race Engineer (airaceengineer.com) | « live voice coaching on gaps, tyres, fuel and strategy » pour plusieurs jeux, dont GT7 | Extrait seulement. On ne sait pas si les écarts concernent GT7. Site illisible. | annonce ambiguë |
| CrewChiefV4 | **Ne supporte pas GT7** | `GameDefinition.cs` sur GitLab (lignes 9–15) : l'énumération `GameEnum` n'a aucune entrée GT. L'arborescence `CrewChiefV4/` (API GitLab, 20/10/2026) n'a pas de dossier GT. | prouvé |

Aucun outil ne montre de méthode, ni de résultat, d'écart réel avec un adversaire dans GT7.

---

## 7. Autres simulateurs (architectures de référence)

| Jeu | Canal | Données par voiture | Écart fourni par le jeu ? | Comment on calcule l'écart sinon |
|---|---|---|---|---|
| F1 25 | UDP (diffusion, jusqu'à 22 voitures) | `LapData[22]` : `m_lapDistance`, `m_totalDistance`, position. `CarMotionData[22]`. (`MacManley/f1-25-udp/src/PacketLapData.h` 9–23 ; `PacketMotionData.h` 40) | **Oui** : `m_deltaToCarInFrontInMSPart` (uint16) + `MinutesPart` (uint8), même chose vers le leader | Inutile. Avant F1 23, on calculait à partir de `m_totalDistance` (fil des forums EA, extrait). |
| Project CARS 2 / AMS2 | Mémoire partagée (64 `ParticipantInfo`) et UDP | Distance dans le tour, tours, position, secteurs | **Partiellement** : écart avec la voiture devant (`mSplitTimeAhead`, lu par CrewChief `PCars2/PCars2GameStateMapper.cs` 681, miroir 2019) | CrewChief recalcule si la voiture n'est pas dans le même tour (`GameState/GameStateMapper.cs` 113–131). |
| ACC | Broadcasting API (UDP, port 9000 par défaut, mot de passe) | `RealtimeCarUpdate` : `SplinePosition` (0–1), `Position`, `TrackPosition`, `Laps`, `Delta`, `LastLap`, `CurrentLap`, `BestSessionLap` (CrewChief GitLab `ACC/ksBroadcastingNetwork/Structs/RealtimeCarUpdate.cs` 5–22) | **Non**, pas d'écart direct | Spline × temps de tour de référence. DynLeaderboards `CalculateOnTrackGap` (`src/components/CarData.cs` 1433–1470) interpole un tour de référence par classe. À défaut, il prend la distance ÷ 50 m/s (`CalculateNaiveGap`). |
| iRacing | SDK en mémoire partagée | `CarIdxLapDistPct` (fraction du tour), `CarIdxEstTime` (« Estimated time to reach current location on track »), `CarIdxF2Time` (« Race time behind leader or fastest lap time otherwise ») ([liste goirsdk](https://github.com/mpapenbr/goirsdk/blob/v0.6.0/variables.md)) | **Oui** vers le leader (`CarIdxF2Time`) | Écart entre deux voitures ≈ différence de `CarIdxEstTime`, corrigée du nombre de tours. CrewChief utilise son propre `DeltaTime` (`iRacing/iRacingGameStateMapper.cs` 645 et 805). |
| rFactor 2 / LMU | Mémoire partagée | Scoring par voiture | **Oui** : `mTimeBehindNext` (CrewChief `RF2/RF2GameStateMapper.cs` 903) | — |
| **GT7** | UDP Simulator Interface | **Le joueur seulement.** Position XYZ, mais pas de distance dans le tour. | **Non** | Impossible sans les données des autres voitures. |

### L'algorithme réutilisable : `DeltaTime` de CrewChief

Source : `GameState/GameStateData.cs`, lignes 2888–3070 (miroir de 2019). Le même mécanisme est toujours appelé dans
l'ACC mapper GitLab, lignes 825 et 905.

1. On découpe le circuit en points tous les 20 m (`spacing = 20f`).
2. Pour chaque voiture, on enregistre l'horodatage du passage à chaque point (`SetNextDeltaPoint`).
3. L'écart est la différence entre les heures de passage des deux voitures au **dernier point franchi par la voiture
   de derrière** (`GetSignedDeltaTimeWithLapDifference`).
4. On corrige du nombre de tours d'écart (`GetSignedLapDifference`).

**Données nécessaires pour chaque voiture** : la distance dans le tour, le nombre de tours bouclés et une horloge
commune. Pour GT7, une seule voiture est observable : **cet algorithme n'a rien à comparer.** Il ne sert que si
d'autres voitures fournissent leurs propres données (volontaires, §8).

---

## 8. Méthodes par contexte

### 8.1 Pilote seul (aucune coopération)

- **Possible** :
  - la position en direct (0x84/0x86), si le mode l'envoie ;
  - le delta avec son propre meilleur tour, par distance (calcul de gt7dashboard) ;
  - les événements de position :
    - quand la position passe de P à P−1, l'écart avec la voiture **désormais derrière** est d'environ 0 s à cet
      instant (à la latence de mise à jour près, inconnue) ;
    - même chose dans l'autre sens.
- **Impossible** :
  - **Suivre l'écart après un dépassement.** On ne connaît ni la vitesse ni la distance de l'adversaire. Supposer
    que l'adversaire roule à « notre rythme » serait inventer une donnée.
  - **Utiliser la grille de départ.** La valeur 0x84 d'avant course donne la place sur la grille, mais pas d'écart en
    temps.
- **Piège** : une position peut aussi changer à cause d'un arrêt au stand ou d'un abandon adverse. Un changement de
  position n'est donc pas toujours un dépassement en piste. On ne sait pas non plus si GT7 recalcule la position en
  continu ou aux lignes de chronométrage.

### 8.2 Plusieurs volontaires coopérants

**Principe** : chaque application de volontaire publie à 2–10 Hz :
- un identifiant ;
- le tour (0x74) et la position (0x84) ;
- `currentLap` (0x15C, format C) et la somme de ses `lastLaptime` ;
- sa distance le long d'une ligne de référence commune.

**Calcul de la distance** : GT7 ne donne pas de distance dans le tour. Il faut projeter la position XYZ (0x04) sur
une polyligne enregistrée pendant un tour de référence, partagée par tous les volontaires pour ce circuit.

**Calcul de l'écart, sans synchroniser les horloges** :
- on compare le **temps de course** `T(d)` = somme des tours bouclés + `currentLap`, au même point `d` ;
- l'écart vaut `T_moi(d) − T_lui(d)`, selon le schéma de CrewChief.
- Cette méthode reste **expérimentale** : elle suppose que le chronométrage du premier tour démarre au même instant
  pour tous, et que `currentLap` se comporte bien en pause et au passage de la ligne. Rien de cela n'est vérifié.

**Savoir si le volontaire est juste devant** : si sa position vaut la nôtre moins 1, c'est **la** voiture devant, et
l'écart est l'écart réel avec la voiture devant.

**Ce que la méthode ne peut pas faire** : les voitures non volontaires restent invisibles. Si la voiture juste devant
n'est pas un volontaire, l'écart avec le volontaire n'est **pas** l'écart avec la voiture devant.

**Canal** :
- Discord n'est pas conçu pour des données à plusieurs Hz : les limites de débit d'un bot (détails non vérifiés ici)
  le réservent à des messages espacés (par exemple un récapitulatif par tour).
- Un relais WebSocket ou MQTT gratuit conviendrait mieux. C'est une **hypothèse d'architecture**, non testée.

**Respect des contraintes** :
- seules les données des volontaires consentants circulent ;
- rien n'est modifié ni intercepté dans le jeu ;
- il faut vérifier le règlement des compétitions officielles (aides externes).

### 8.3 Course Sport publique

- Les adversaires sont des inconnus. **Aucune source d'écart** n'est disponible.
- Il reste la position (validée en Sport A par nos essais PS5) et les événements de dépassement.
- Avec des volontaires dans la même course, on retombe sur le cas 8.2 : rare, et limité aux voitures adjacentes.

### 8.4 Hors ligne (contre l'IA)

- Mêmes limites que pour le pilote seul.
- **Après la course** : la télémétrie d'un replay décrit la voiture regardée. Le datalogger écrit : « replays record
  other drivers' laps » et « GT7 streams replays exactly like driving » (README 130 et 179). On pourrait donc
  enregistrer le tour de référence d'un adversaire **après** la course. Cela ne donne aucun écart en direct.
- **Non établi** : nous n'avons trouvé aucune preuve que le flux suive une **autre** voiture quand on regarde en
  spectateur un salon en direct.

### 8.5 PS VR2

- Aucune source trouvée sur un comportement différent de la télémétrie en VR. On ne peut rien conclure : rien ne dit
  que le flux soit différent, ni qu'il soit identique.

---

## 9. Classement des sources d'écart candidates

| Source candidate | Classement | Justification |
|---|---|---|
| Écart avec l'adversaire dans le flux UDP (A, B, ~, C) | **IMPOSSIBLE À ÉTABLIR ACTUELLEMENT** | Aucun champ ne décrit un autre véhicule (structures complètes, issue #18, 10 projets). Un champ caché reste théoriquement possible, mais rien ne le suggère. |
| LiveTiming API (`Diff[]`, `Standings[]`) | **IMPOSSIBLE** dans nos contraintes | Le service existe (CONFIRMÉ dans le code), mais il exige une build debug, un argument interne, un mode LAN caché ou des permissions de compte, ou bien un mod. |
| Position en direct 0x84/0x86 | **CONFIRMÉ** en course hors ligne et en Sport A (nos essais PS5) ; **PROBABLE** ailleurs | Deux projets indépendants l'observent aussi en course. Elle vaut −1 dans certains modes. La cadence de mise à jour est inconnue. |
| Écart ≈ 0 au moment d'un changement de position | **EXPÉRIMENTAL** | Logiquement valable si le changement correspond à un dépassement en piste. Latence et cas d'arrêt au stand non mesurés. |
| Delta avec son propre tour de référence (par distance) | **CONFIRMÉ** | Ce n'est pas un écart avec un adversaire. gt7dashboard, datalogger et jegr78 le font. |
| Paquet C : `currentLap` à 0x15C | **CONFIRMÉ** (le champ existe) | Utile au partage entre volontaires. Sa sémantique fine (pause, ligne) reste à mesurer. |
| Écarts entre volontaires (distance + temps partagés) | **EXPÉRIMENTAL** | Algorithme éprouvé ailleurs (CrewChief, DynLeaderboards). Limité aux volontaires. Jamais démontré dans GT7. |
| Volontaire adjacent (position ± 1) = vrai écart avec la voiture devant ou derrière | **EXPÉRIMENTAL** | Ne vaut que si les deux positions sont fiables et de même fraîcheur. |
| Tour de référence adverse tiré d'un replay (après la course) | **PROBABLE** | Le README du datalogger l'affirme. Ce n'est pas en direct. |
| Flux UDP qui suit une autre voiture en spectateur d'un salon en direct | **IMPOSSIBLE À ÉTABLIR ACTUELLEMENT** | Aucune source. Il faudrait une deuxième PS5 de volontaire. |
| Annonces commerciales (Pit Crew Hub, AI Race Engineer, Hector) | **IMPOSSIBLE À ÉTABLIR ACTUELLEMENT** | Aucune méthode publiée, sites illisibles pour nous. Pit Crew Hub ne parle que de « pace delta ». |
| Estimation par le rythme supposé de l'adversaire | **Écarté** | Ce serait inventer une donnée. |

---

## 10. Expériences utiles proposées

Toutes ces expériences se font sur la PS5 du joueur, en lecture seule, sans rien modifier dans le jeu.

1. **Carte de 0x84/0x86 selon le mode** : contre-la-montre, course arcade, circuit d'expérience, salon, Sport A/B,
   chacun en VR et hors VR. On note les valeurs −1, le moment du passage −1 → valeur, et le comportement après
   l'arrivée.
2. **Cadence de mise à jour de la position** : horodater chaque changement de 0x84 avec la position XYZ. Puis
   comparer les endroits où ces changements arrivent : un peu partout sur la piste (calcul continu), ou toujours aux
   mêmes endroits (lignes de chronométrage) ?
3. **Latence d'un dépassement** : dépasser l'IA en ligne droite. Mesurer combien de paquets séparent le moment où les
   voitures sont côte à côte, vu à l'écran, du changement de 0x84.
4. **`currentLap` (C)** : son comportement en pause, au départ arrêté ou lancé, au passage de la ligne et au stand.
   Vérifier aussi que `T(d)` = somme des `lastLaptime` + `currentLap` reste cohérent tour après tour.
5. **Captures de référence** : enregistrer un paquet chiffré réel de chaque format (A, B, ~, C) et ses valeurs
   déchiffrées. On obtient ainsi les vecteurs de test qui manquent pour B, ~ et C. Les publier dans `:core`.
6. **Replays** : vérifier si le flux suit la voiture choisie quand on change de voiture dans un replay. Si oui,
   construire des tours de référence d'adversaires après la course.
7. **Essai à deux volontaires** dans un salon privé : comparer l'écart calculé (§8.2) avec l'écart que donne le
   replay ou la vidéo de fin de course.

---

## 11. Suite donnée dans Pitwall

- **Paquet C** intégré, avec repli C → ~ → A ([GAP_HUNTER.md](GAP_HUNTER.md)).
- **Le vecteur réel de vwhitteron** est devenu un test (`PacketCTest.decodesAnIndependentRealCapture`).
- **Gap Hunter** couvre les expériences 1 à 4. Son journal note chaque changement de 0x84 avec la position XYZ, la
  vitesse et le chrono du tour (format C).
- **La sonde des heartbeats** (A à Z, ~) vérifie l'affirmation « pas d'autre mode » sur notre propre console.
- **Drapeau CarOnTrack** : Gap Hunter ne s'en sert pas. Sur notre PS5, il est bien levé en course hors ligne
  (`0x0009`), donc `TyreCoach` et `RadioPacer` restent tels quels.
- **Premier essai réel (21/10/2026)** : voir [GAP_HUNTER.md](GAP_HUNTER.md), « Résultats PS5 ».

## 12. Conclusion

- **Aucune source légitime d'écarts avec les adversaires n'est démontrée pour GT7** sur une PS5 non modifiée. Ni le
  flux UDP (A, B, ~, C) ni les projets publics n'en fournissent.
- **La seule source connue qui en contienne (le LiveTiming API) est fermée aux comptes normaux** : elle demande une
  liste blanche PD ou un jeu moddé, ce qui la met hors de nos contraintes.
- **Ce qui est atteignable honnêtement** :
  1. la position en direct, avec des événements de dépassement (écart ≈ 0 à l'instant du changement) ;
  2. le delta avec son propre tour de référence ;
  3. des écarts réels **uniquement entre volontaires coopérants**, avec l'algorithme de CrewChief et le `currentLap`
     du paquet C. Ce point reste expérimental et n'est jamais démontré dans GT7.
- **En course Sport publique, Pitwall doit dire clairement qu'il ne connaît pas l'écart**, plutôt que l'estimer.
