# V511 — Audit de fiabilisation avant évolution majeure

**Branche candidate** : `claude/v511-reliability`, empilée sur V510 (`claude/v510-live-b-sim`, b03e439).

**Rien n'est fusionné dans `main`.** Les APK précédentes restent des artefacts CI : V510 #571 = 14758247591 (dernière
APK construite) et V507 #558 = 14274682483 (référence V507).

**Aucune validation PS5 ni Fold n'est revendiquée dans ce document.** Tout ce qui suit vient du code, des tests JVM et
de la compilation CI. Les essais réels cités sont ceux des journaux déjà fournis.

## 1. Audit d'intégration

### Ancêtre commun et pile

- `main` (1a05886) ne contient que le commit initial (858621b) et trois workflows CI (diagnostic OAuth Claude, #7 #10
  #11). **Le seul ancêtre commun entre `main` et la pile est le commit initial.** Toute l'application vit sur la pile de
  PR brouillons : 354 commits, dont aucun dans `main`.
- Ordre réel jusqu'à V510 (chaque PR a pour base la précédente) :
  1. socle et premières PR :
     - #57 v480 ← v477 ← v476 ;
     - #60 v481 ;
     - #62 v482 ;
     - #66 v485 (gomme de départ) ;
  2. Véga et IA :
     - #69 v488 (ChatGPT) ;
     - #71 v490 ;
     - #72 v492 ;
     - #74 v494 ;
  3. écrans, briefing, stands, radio, diction :
     - #75 v496 ;
     - #76 v497/V502 ;
     - #77 V503 ;
     - #78 V504 ;
     - #79 V505 ;
  4. refonte et tableau de bord :
     - #80 V506 ;
     - #81 correctifs V506 (GPT) ;
     - #82 V507 ;
  5. position, voix, prototype B :
     - #83 V508 ;
     - #84 V509 ;
     - #85 V510.
- **Hors pile** : #64 (`claude/v484-idle-suspend`, veille automatique #63, commit bdebf62) a la même base que #66.
  **Il n'est pas inclus** (1 commit manquant) : `TelemetryIdlePolicy` est absent de la candidate.

### Correctifs réellement présents (vérifiés dans le code, pas dans les descriptions)

| Correctif | Preuve dans le code | Présent |
|---|---|---|
| #74 intention sûre | `IntentArbiter` (4 fichiers) | oui |
| #72 outils Véga | `get_corner_comparison` | oui |
| #77 réconciliation stands | `contestedTrackStop` | oui |
| #78 preuve chaîne radio | `RouteTrace` | oui |
| #79 diction | `SpokenFrench.forAudio` (Fold et Discord) | oui |
| #81 données périmées et deux alertes critiques | `AlertBanner` a disparu avec l'écran V506 (`RaceCommandCenter.kt` supprimé par V507). Le comportement est **repris** dans `GtDashboard` : atténuation si `!live` (l. 85-93), deuxième alerte critique visible (l. 603-619) | oui, sous une autre forme |
| #82 tableau GT3 | `GtDash.of` | oui |
| #83 position radio, révision après la ligne, journal des formats | `liveRank`, `PostFinishChange`, `EARLIER_HEARTBEAT` | oui |
| #84 moteur de voix | `VoiceEngineReport` | oui |
| #85 B simulé | `SimulatedLiveServer` | oui |
| #63 veille automatique | `TelemetryIdlePolicy` | **non** (PR #64 hors pile) |

### Fonctions développées mais non reliées au moteur de course

- `CrossTrackFuelPrior` (#52, carburant d'un circuit à l'autre) : testé, **appelé nulle part en production** (déjà
  signalé en V481).
- `Opponents` (`OpponentsSource`, `NoOpponentsSource`) : emplacement réservé, voulu (GT7 ne transmet pas les
  adversaires).
- `DriverQueryParser` (Radio.kt) : résidu non utilisé.
- `RecognitionBench.WordErrorRate` / `FrenchNumbers` : banc de reconnaissance, outils de test seulement.
- Prototype B : relié au moteur seulement pendant une session manuelle (le moteur reste celui de A).

## 2. Replays GT7

**Les cinq traces brutes du pilote ne sont pas accessibles dans cette session.** Elles ne sont ni jointes, ni dans le
dépôt, ni dans les issues ou les PR, ni sur un ordinateur relié. Elles ne sont donc **pas** rejouées, et aucun résultat
n'est inventé à leur place.

Ce qui est rejoué à la place, à chaque exécution de la suite (938 tests, 0 échec, 1 ignoré) :

| Source | Contrôle | Test |
|---|---|---|
| Course Sport A du 20/10 (journal réel, transitions 0x84/0x86) | positions suivant le HUD, matchmaking ignoré, déconnexion 12→11, arrivée | `LivePositionTest` |
| Course du 21/10 (Gilles-Villeneuve, 12 tours, arrêt T6 +66,7 L) | carburant, arrêt, gomme, P1/20 | `RealRace2110Test` (13) |
| Course du 25/10 (Le Mans, ×4/×6) | arrêt T4, plein, pneus | `RealRace2510Test`, `October26PitRadioRegressionTest` |
| Course du 26/10 (WRX, 14 tours) | arrêt sans essence T8, « oui », gomme non certaine | `RealRace2610Test` |
| Course du 06/11 (#73) | stand T7 contesté, pas de nouvel arrêt avant réponse | `PitReconcileTest` |
| Capture texte du 19/11 | P10/15 → P9/15 → P8/14 (aucun changement de rang à 15→14) → ligne P5/14, « 8/0 » | `PositionRadioV508Test` |
| Fait du 22/11 | ligne P12/16, P13/16 à +567 ms, archive P13 et ligne P12 | `PostFinishRevisionTest` |
| Traces brutes du 19 et du 22/11 | archivé P7/13 et P13/16 | `RealTraceReplayTest` : **ignoré**, s'active avec `PITWALL_TRACES=<dossier>` |

**Reste à faire dès réception des traces** :
- lancer `RealTraceReplayTest` (19/11 → P7/13 sous réserve du classement officiel, 22/11 → P13/16) ;
- ajouter les contrôles demandés sur les vraies traces : paquets invalides, coupure UDP, reconnexion, carburant,
  tours, faux passages aux stands, messages réellement produits.

**Point ouvert sur le 19/11** : l'export texte montrait P5/14 à la ligne. Un résultat archivé à P7/13 impliquerait une
révision après la ligne, absente de l'export. Seules la trace brute et le classement officiel trancheront.

## 3. Audit radio

Chaîne : micro PS5 → Discord (JDA + DAVE) → décodage Opus → Vosk/Google → `CommandGate` / `IntentArbiter` →
`RadioConversation` (local ou IA) → moteur → `RadioPacer` → sortie (Discord : `SpeechPcm` → Opus → salon ; Fold :
TextToSpeech).

| Point | Constat | Gravité | V511 |
|---|---|---|---|
| Alertes critiques concurrentes | Une 2e alerte critique **effaçait la 1re encore en attente** (`SpeechQueue.offer` vidait toute la file) et coupait une alerte critique en cours (Discord et Fold) | **P0** | corrigé : seules les annonces non critiques sont écartées ; une alerte critique n'en coupe jamais une autre |
| Données périmées avant la synthèse | Le TTL n'était appliqué qu'en amont (`RadioPacer`). Dans la file Discord, un message attendant derrière une longue phrase était dit en retard (« carburant pour 3 tours » faux) | P1 | corrigé : refus au moment de la synthèse si âge > `ttlMs` ou 10 s, noté `VOIX_PERIMEE` (Discord). Fold : non traité (P2) |
| Interruptions sur Discord | Désactivées par défaut (#45 : écho du casque, 26 coupures). **Le test « coupe-la » du banc A/B ne pouvait pas réussir sans l'option** | P1 (protocole) | le banc l'indique ; le réglage par défaut n'est **pas** changé |
| Échos | `EchoFilter` et `nearEngineer` en place, testés (`RadioEchoTest`) | — | inchangé |
| Réponses silencieuses | Volontaires : fragments (`Route.Ignore`) et réponses périmées par une question plus récente (`Converse.Stale`). Toute réponse locale ou IA a une issue parlée | P2 (à observer) | inchangé |
| Local ou IA | Route, fournisseur, outils et délai journalisés (`RouteTrace`, `AiTrace`, #78). Les questions d'état restent locales | — | inchangé |
| B : appel bloquant | La synthèse simulée (`runBlocking`) bloquait le fil de la session jusqu'à ~20 s : ni limite de 5 min, ni coupure pendant ce temps | P1 (prototype) | corrigé : synthèse asynchrone ; une réponse coupée avant la fin de sa synthèse n'est jamais jouée |
| B : file audio non bornée | Le serveur simulé poussait l'audio deux fois plus vite que la lecture, dans des files illimitées | P2 | corrigé : contre-pression (≤ 0,6 s d'avance), file Discord bornée à 3 s |
| B : voix A pendant B | Les annonces non critiques de A sont abandonnées (pas différées) pendant l'essai de 5 min | P2 | documenté |

**La simulation B ne valide pas GPT-Live.** Elle ne montre ni sa voix, ni sa compréhension, ni son délai.

## 4. Audit fonctionnel

| Modèle | Source des données | Confiance | Câblage | Tests | Limites | Améliorations prioritaires |
|---|---|---|---|---|---|---|
| Carburant | Niveau et capacité GT7 (mesurés), consommation des tours propres (fenêtre glissante) | élevée dès 2 tours propres | moteur, radio, plan, écran | `RealRace2110/2510/2610`, `FuelRadioTest`, `StrategyTest` | multiplicateur et capacité dépendent des règles déclarées ; estimation inter-circuits non câblée | câbler `CrossTrackFuelPrior` (provisoire, effacé par la mesure) |
| Pneus | Températures GT7 (mesurées). Gomme et changement **déclarés** par le pilote. Usure **non transmise** (`WEAR_AVAILABLE=false`). `TyreChangeHunter` : indice thermique expérimental | gomme : déclarée ; changement : indice seulement | moteur, stands, plan | `TyreSignatureTest`, `PitReconcileTest` | aucune usure mesurée ; changement jamais prouvé par la télémétrie | garder la question « as-tu changé ? » ; plus de cas étiquetés |
| Circuit | Géométrie X/Z mesurée contre des signatures (source communautaire CC0) | élevée si signature, sinon « non identifié » | briefing, coaching, historique | `TrackGeometry*Test` | circuits sans signature (ex. SUGO) ; numérotation des virages communautaire ou estimée | compléter les signatures (#58) |
| Stratégie | Règlement (fiches Sport multi-sources ou saisi), carburant mesuré, perte au stand (base GT7) | moyenne : règlement souvent « non confirmé » | plan, radio, Véga | `Strategy*Test`, `DynamicStrategyTest` | règles Sport non officielles ; aucune donnée adverse | confirmation du règlement par le pilote |
| Position | 0x84/0x86, course lancée seulement ; rang de la ligne, révisions après l'arrivée, consolidation 30 s | élevée en course (essais 20/10, 19/11) ; consolidation non validée | radio, archive, écran | `LivePosition`, `PositionRadioV508`, `PostFinishRevision` | délai de 30 s fondé sur une seule mesure (567 ms) | recalibrer avec des traces |
| Coaching virage | Passages mesurés par virage (zone de la carte) contre la médiane des 3 meilleurs passages | faible en format A (surface inconnue) | radio (≤ 3 conseils en course), Véga, débrief | `CornerCoach`, `DriverDnaTest` | voir ci-dessous | garde-fou V511 ; libellé T/V dans les conseils |

### « Virage 5 : 41 dixièmes gagnables par passage »

- **Calcul** (`CornerCoach.losses`) : moyenne des 3 derniers passages propres moins la médiane des 3 meilleurs passages
  (historique et session, même voiture et même circuit).
- **Ce n'est pas un temps « gagnable ».** C'est un écart à une référence personnelle optimiste, et le mot « gagnable »
  n'existe pas dans le code. La formulation du moteur était « Environ 41 dixièmes par passage » ; la reprise par l'IA
  ou un résumé l'a transformée.
- **Valeur non défendable.** 4,1 s dans un seul virage correspond à un incident répété (sortie de piste invisible en
  format A, trafic) ou à une zone mal découpée, pas à un défaut de technique. Le seul filtre était au niveau du tour
  (+7 % du meilleur, environ 8 s sur 2 min).
- **« Virage 5 »** est le numéro détecté par Pitwall, pas forcément le T5 officiel.
- **V511** :
  - aucune perte au-delà de 1,5 s ou de 30 % du temps de référence du virage (aucun conseil, aucun chiffre) ;
  - une seconde ou plus se dit en secondes (« 4,1 secondes », jamais « 41 dixièmes ») ;
  - l'outil GPT et la persona interdisent de présenter l'écart comme « gagnable ».

## 5. Matrice

| Statut | Fonctions |
|---|---|
| **Validé (journaux PS5 réels)** | réception UDP formats A et C ; position 0x84/0x86 en course (20/10, 19/11) ; carburant mesuré et arrêt avec ravitaillement (21/10, 25/10) ; arrêt sans essence avec réponse vocale (26/10) ; radio Discord dans le casque (essais 28/10, 06/11, avec défauts corrigés depuis) |
| **Fonctionnel mais non validé** (JVM + CI) | révision du classement après la ligne et consolidation (V508) ; « Position ? » courte ; journal des formats V508 ; tableau GT3 (V507) ; réconciliation des stands (V503) ; diction (V505 : écoute à faire) ; diagnostic du moteur de voix (V509) ; correctifs radio V511 |
| **Défaillant** (avant V511) | alerte critique effacée par une autre ; message périmé dit en retard sur Discord ; conseil de virage non plausible (« 41 dixièmes ») ; test de coupure du banc impossible sans l'option Discord |
| **Expérimental** | prototype B simulé (V510) ; `TyreChangeHunter` ; interruption par Discord (opt-in) ; Gap Hunter ; sonde 0x154 |
| **Non intégré** | veille automatique #63 (PR #64 hors pile) ; `CrossTrackFuelPrior` ; GPT-Live réel (désactivé, clé côté serveur à décider) |

## 6. Problèmes classés

| Id | Gravité | Problème | Conséquence pour le pilote | V511 |
|---|---|---|---|---|
| R1 | **P0** | 2e alerte critique efface la 1re en attente, ou coupe la 1re en cours | un « Box, box » ou une alerte carburant n'est jamais entendu | corrigé |
| R2 | P1 | message périmé dit en retard (Discord) | chiffres faux (carburant, place) | corrigé (Discord) |
| C1 | P1 | perte de virage non plausible annoncée (4,1 s), présentée comme gain | conseil trompeur, perte de confiance | corrigé |
| R3 | P1 | coupure impossible par Discord sans option | test A/B faussé, interruption ressentie comme cassée | banc explicite |
| B1 | P1 | B : synthèse bloquante | ni coupure ni limite de 5 min pendant ~20 s | corrigé |
| P1-pos | P1 | consolidation après la ligne : délai fondé sur une mesure | résultat archivé à confirmer | à valider sur traces |
| U1 | P1 | format UDP figé après le premier paquet, B jamais demandé, aucune renégociation (audit V507) | silence possible en entrant en Sport | **non modifié** (négociation hors périmètre) |
| T1 | P1 | traces brutes non disponibles | 19/11 et 22/11 non vérifiés en brut | harnais prêt |
| B2 | P2 | B : files non bornées | latence de coupure | corrigé |
| R4 | P2 | Fold : pas de refus des messages périmés | retard possible sur haut-parleur | à faire |
| I1 | P2 | #63 hors pile | pas de veille automatique | décision du pilote |
| I2 | P2 | `CrossTrackFuelPrior` non câblé | pas d'estimation sur nouveau circuit | V512+ |
| C2 | P2 | « Virage N » = numéro Pitwall | confusion avec T officiel | V512+ |

## 7. Correctifs V511 (minimaux)

- `SpeechQueue.offer` : une alerte critique n'écarte que les annonces non critiques.
- `DiscordRadio` / `TtsVoiceOutput` : une alerte critique en cours n'est jamais coupée par une autre.
- `RoutedVoiceOutput.stale` et `DiscordRadio.speakLoop` : refus des messages périmés avant et après la synthèse
  (`VOIX_PERIMEE`). Les phrases d'écoute (diction, prononciation) ont une validité longue.
- `SimulatedLiveServer` / `LiveSession` : synthèse asynchrone, réponse coupée jamais jouée, contre-pression ≤ 0,6 s.
  La file Discord de B est bornée à 3 s.
- `CornerCoach` : garde-fou de vraisemblance (1,5 s ou 30 %), durées ≥ 1 s en secondes. L'outil GPT et la persona
  interdisent « gagnable ».
- Banc A/B : avertissement quand la coupure par Discord est désactivée.
- **Non modifiés** : la négociation UDP, les réglages par défaut (interruption Discord), la voix.

Tests de non-régression : `V511ReliabilityTest` (6), en plus de `PostFinishRevisionTest` (5) et `RealTraceReplayTest`
(traces réelles).

## 8. Retour arrière

- **APK** :
  - réinstaller l'APK V510 (artefact 14758247591, CI #571) ;
  - ou V507 (14274682483), si les nouveautés V508 à V510 posent problème.
- **Code** : `claude/v511-reliability` est une branche séparée. Il suffit d'abandonner la PR V511 ; V510 et les
  branches précédentes ne sont pas modifiées.
- **Données** : les champs d'archive ajoutés en V508 (`lineFinish`, `postFinish`…) sont facultatifs. Une version
  précédente relit les sessions en les ignorant (JSON tolérant).

## 9. Protocole de validation PS5 + Fold

À faire dans cet ordre, avec la trace télémétrique activée (GARAGE) et le journal de course exporté après chaque essai.

1. **Alertes critiques** (course personnalisée, carburant ×6, réservoir partiel) : provoquer l'alerte carburant
   critique pendant une autre annonce. Attendu : l'annonce est coupée, l'alerte est dite en entier. Si une 2e alerte
   critique arrive, elle suit la première sans la couper.
2. **Messages périmés** : dire trois questions de suite (« carburant ? », « tours ? », « position ? »). Attendu :
   aucune réponse dite plus de 10 s après son calcul (ligne `VOIX_PERIMEE` au journal si une réponse est écartée).
3. **Position et arrivée** (Course Sport) :
   - « Position ? » à chaque tour, comparée au HUD ;
   - à l'arrivée, attendu une seule fois « Tu passes la ligne P… sur … » ;
   - si le classement change après la ligne : « Classement GT7 révisé après l'arrivée : P… sur … », une seule fois,
     puis le bilan ;
   - Historique : ligne et résultat archivé distincts.
4. **Coaching** : en entraînement, ne jamais entendre une perte de virage de plus de 1,5 s. Une perte d'au moins une
   seconde se dit en secondes.
5. **Banc A/B** (VEGA) :
   - activer « Interruption aussi par Discord » pour le test de coupure, et noter toute fausse interruption ;
   - A puis B simulé (5 min) ;
   - le juger sur le déroulé seulement, pas sur la voix de GPT-Live.
6. **Traces** : envoyer les fichiers `.gt7trace` du 19 et du 22/11 (et les trois autres) pour `RealTraceReplayTest`.

Rien n'est validé tant que le journal exporté ne montre pas le comportement attendu.
