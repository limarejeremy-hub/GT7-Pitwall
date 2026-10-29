# Matrice d'intégration — V481 (issue #59)

**Build de référence** : branche `claude/v481-combined-validated`. L'application affiche `0.8.1 · V481 Sport d'abord + Connexion PS5 auto (build N)` dans la carte *Connexion PS5*.

**Aucune validation PS5** : le pilote n'a pas sa console. Les résultats ci-dessous viennent des tests JVM (744, 0 échec) et de la compilation Android CI. Ce ne sont pas des essais terrain.

## Ce qui change pour le pilote (court)

- **Sport d'abord** (interrupteur, désactivé par défaut). Tu choisis ta course et ta voiture dans GT7 seulement.
  - Pitwall reconnaît A, B ou C de la semaine dès la grille (tours annoncés) et prépare le règlement.
  - La course est toujours dite « probable » ou « reconnue », jamais « officielle ».
- **Connexion PS5 automatique** (interrupteur, désactivé par défaut).
  - La recherche démarre à l'ouverture de Pitwall, ou tout de suite quand tu actives l'interrupteur.
  - « Arrêter » est respecté jusqu'à « Démarrer ».
- **Pneus de départ inconnus** avec deux gommes imposées : deux plans annoncés (« si tu pars en… »). « Je pars en tendres » choisit le bon.
- **Preuves de circuit** : le nom publié par la fiche n'est plus une preuve. Seul le tracé mesuré « reconnaît » le circuit.
- **Estimation carburant d'un circuit à l'autre** : le modèle est présent et testé, mais **pas encore utilisé en course**.

## Construction de la branche

```
v476 (065c22b) socle : #37 #39 #42 #43 #44 #46 #49 #50 déjà ancêtres
 └─ 71773fd  PR #53  preuves de circuit Sport (#51)
     └─ 20f3495  PR #57  jalon A zéro touch Sport (#30)
         └─ 2f3a7bd  report de PR #56 (d5c1f0b), conflits résolus + revue #56
             └─ 8dab898  report de PR #54 (243e5e1)
                 └─ HEAD  intégration : version affichée, interrupteur auto actif tout de suite, matrice
```

- #56 et #54 sont reportés par `cherry-pick -x` : leurs SHA d'origine ne sont donc **pas** ancêtres, mais leur contenu est présent et testé.
- Les conflits portaient sur `SettingsStore` et `PitwallScreen` : les deux interrupteurs coexistent, indépendants et persistants.
- Aucune PR n'est fusionnée ni fermée. L'import communautaire #58 est **exclu**.

## Statuts

- `ACTIF` : implémenté et actif.
- `OFF PAR DÉFAUT` : implémenté, opt-in.
- `NON CÂBLÉ` : modèle expérimental, non appelé en course.
- `NON TESTÉ PS5` : fonctionne en JVM ou CI, jamais essayé sur console avec cette version.
- `EXCLU`.

Aucune ligne n'est « validée PS5 » pour V481.

## Matrice

Colonnes : déjà dans v476 ? · dans v481 ? · interface · branché au moteur · tests JVM · statut.

| PR | Sujet | Ancêtre v476 ? | v481 ? | UI | Moteur | Tests JVM | Statut |
|---|---|---|---|---|---|---|---|
| #1–#4 | socle Android, format C, journal, fiabilisation 21/10 | oui | oui | Live, Historique | oui | oui | ACTIF, NON TESTÉ PS5 (V481) |
| #12 | Tyre Intelligence V2 (`TyreChangeHunter`, `PitLedger`) | oui | oui | Briefing, Analyse | oui | oui | ACTIF ; gomme toujours *déclarée*, jamais mesurée |
| #13 | Race Engineer V3 | **SHA non ancêtre** ; contenu réintégré via #15 (tous les fichiers présents) | oui (via #15) | Analyse | oui | oui (`StrategyV3Test`, `DriverDnaTest`…) | ACTIF |
| #14 | Human Radio V4 (interruption, dialogue, mémoire) | oui | oui | Radio | oui | oui | ACTIF |
| #15–#17 | Driver DNA V3 + V4, Sport multi-source, V5 | oui | oui | Analyse, Briefing | oui | oui | ACTIF |
| #18–#21 | Zero Touch V5, briefing V6, cockpit Fold V7.1, garage V8 | oui | oui | Live, Garage | oui | oui | ACTIF |
| #22–#26 | profil GT7/PSN, carrière locale, GridStats public | oui | oui | Profil | oui | oui | GridStats OFF PAR DÉFAUT (opt-in) |
| #27 | Sport Intelligence (A/B/C à trois preuves) | oui | oui | Briefing | oui (`SessionMatcher`) | oui | ACTIF ; règle des preuves resserrée par #53 |
| #28 | géométrie de circuit + Black Box | oui | oui | Atelier | oui (`TrackGeometryRecognizer`, `BlackBoxProbe`) | oui | ACTIF ; SUGO sans signature (UNKNOWN), voir #58 |
| #31 | V421 : arrêts vocaux, photo officielle, virages numérotés | oui | oui | Live, Analyse | oui (`PitwallCarArtwork`, `CornerCoach`) | oui | ACTIF ; photo dépend du réseau |
| #34 | V426 : radio muette, photo, SUGO, P1/1 | oui | oui | Radio | oui | oui | ACTIF |
| #37 | données croisées : motricité, freinage, thermique | oui | oui | Analyse | oui (`TractionMonitor`) | oui | ACTIF |
| #39 | sonde touche radio DualSense | oui | oui | Atelier | sonde seule (`ControllerRadioProbe`) | oui | OFF PAR DÉFAUT, expérimental |
| #42 | changement de voiture (Live, Briefing, photo, historique) | oui | oui | Live | oui (`CarIdentity`) | oui | ACTIF ; jalon `CARCODE_CONFIRMED` ajouté par #57 |
| #43 | provenance règlement et gomme | oui | oui | Briefing, Historique | oui | oui | ACTIF |
| #44 | trace télémétrique rejouable | oui | oui | Atelier | oui (`TelemetryRecorder`) | oui | OFF PAR DÉFAUT (sur demande) |
| #46 | radio : écho Discord, commandes fantômes | oui | oui | Radio | oui (`EchoFilter`) | oui | ACTIF |
| #49 | plan gelé pendant le stand, règlement périmé | oui | oui | Live | oui (`replan` verrouillé) | oui | ACTIF |
| #50 | diagnostic voix Discord (Opus 20 ms, file, vidages) | oui | oui | Atelier | oui (`AudioSendMonitor`) | oui | OFF PAR DÉFAUT (`audioDiagnostics`) |
| **#53** | le circuit publié n'est plus une preuve, briefing conditionnel | non | **oui** (71773fd) | Briefing, journal | oui (`TrackEvidence`, `SessionMatcher(trackMeasured)`) | oui (`SportEvidenceLeakRegressionTest`, 7) | ACTIF, NON TESTÉ PS5 |
| **#57** | zéro touch Sport, jalon A | non | **oui** (20f3495) | interrupteur *Sport d'abord* | oui (`RaceController.sportFirst`, `RacePlan.scenarios`, `ZeroTouchLog`) | oui (`SportZeroTouchE2ETest`, 9) | OFF PAR DÉFAUT, NON TESTÉ PS5 |
| **#56** | connexion PS5 automatique | non | **oui** (2f3a7bd, report de d5c1f0b) | interrupteur *Connexion PS5 automatique* | oui (`AutoConnect`, `MainActivity.onStart` + activation immédiate, `TelemetryService` idempotent) | oui (`AutoConnectTest`, 5) | OFF PAR DÉFAUT, NON TESTÉ PS5 |
| **#54** | `CrossTrackFuelPrior` (carburant au km) | non | **oui** (8dab898, report de 243e5e1) | aucune | **non : aucun appel hors de son fichier** | oui (`CrossTrackFuelPriorTest`, 7) | **NON CÂBLÉ** |
| #58 | données communautaires (122 circuits, 80 signatures) | — | **non** | — | — | — | **EXCLU** (R&D, audit licences) |
| #5, #9 | docs collaboration, diagnostic jeton CI | non | non | — | — | — | hors application |

## Comportements à connaître

- **Arrêt volontaire** : « Arrêter » (écran ou notification) tient pendant toute la vie du processus.
  - Si Android tue l'application, la réouverture suivante relance la recherche (si l'option est active). C'est voulu et documenté ici.
- **Hors Wi-Fi ou loin de la PS5**, avec l'option active, le service de premier plan tourne en « recherche ». La notification le dit, et « Arrêter » reste disponible.
  - Les verrous Wi-Fi et CPU sont ceux du service existant. Économiser la batterie hors Wi-Fi est une évolution future.
- **Bot Discord** : il rejoint toujours automatiquement son salon (inchangé). Sur la PS5, le pilote rejoint lui-même le salon.
- **Indiscernable en UDP** :
  - course en ligne ou salon identique ;
  - gomme montée ;
  - qualification, chrono ou essais libres (GT7 n'annonce aucun nombre de tours).

## Tests (HEAD de la branche)

- `:core:test` : **744 tests, 0 échec**.
  - Socle v476 : 716.
  - #53 : +7.
  - #57 : +9.
  - #54 : +7.
  - #56 : +5, dont l'interrupteur activé dans l'application ouverte et l'arrêt suivi d'une réouverture.
- Régressions couvertes, dans le même lancement :
  - stand Fuji (#47/#49, pas de replan pendant le service) ;
  - Sport C SUGO (#51/#53) ;
  - radio écho (#45/#46) ;
  - diagnostic Opus 20 ms (#48/#50).
- **Non couvert** : interface Compose (pas de test instrumenté), essais Fold/PS5.
