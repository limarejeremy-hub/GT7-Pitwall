# Consolidation du 26/10/2026 — branche de référence `claude/consolidation-reference`

**Base** : PR #16 (`integration/sport-weekly-multisource`, f301bd0), plus fusion de la dernière mise à jour
documentaire de la PR #15 (4d886c7). Merge commit 02d6f4c.
Aucune PR fusionnée, aucun force-push, aucune branche existante réécrite.

## Ce que contient la branche

| Source | État dans la référence |
|---|---|
| PR #12 Tyre Intelligence V2 | intégrée (tous les commits sont ancêtres) |
| PR #14 Human Radio V4 | intégrée (tous les commits sont ancêtres) |
| PR #13 Driver DNA V3 | intégrée **par recopie** (PR #15) : moteurs `DriverDna`, `DriverCoach`, `CornerCoach`, `Corners`, `RaceReview`, `SessionTracker` et leurs tests **identiques octet pour octet** à la branche V3 ; `RaceController`, `RaceEngineer`, `RaceJson` adaptés à la V4 (mêmes fonctions : rediffusion, bilan, correction de consommation, briefing). Les 4 commits V3 ne sont pas ancêtres de la branche : la PR #13 restera « non fusionnée » dans GitHub. |
| PR #15 DNA + V4 + pneus | intégrée, y compris 4d886c7 (document Interlagos RH→RM) qui manquait à la #16 |
| PR #16 Sport GTPlanet + GridStats | base de la branche |

Doublons : aucun code en double. Les PR #12, #14 et #15 sont des étapes successives de la même lignée ; #16 les
contient. Conflits : aucun à la fusion.

## Tableau de consolidation

| Domaine | Fonctionnalités intégrées | Tests JVM réussis | Limitations | À valider sur PS5 |
|---|---|---|---|---|
| Pneus | âge du relais, pneus conservés (« je n'ai pas changé les pneus »), gomme dite seule, Tyre Change Hunter : seuil thermique + remise uniforme à 60 °C (arrêt réel du 25/10), protections phases, coupures UDP, voiture en mouvement ; candidat d'arrêt sans ravitaillement (26/10) ; jamais de confirmation automatique | TyreIntelligence 11, TyresKept 5, RealRace2510 3, TyreUniformResetSafety 4, TyreSignature 2, TyreStrategy 12, TyreCoach 6, TyreRadio 6, TyreChangeAdvice 4, TyreFuelIndependence 9, CompoundPerformance 5, PitDetection 13 | un seul arrêt réel « pneus changés » et un échantillon Interlagos ; aucun arrêt réel « pneus conservés » analysé | arrêt plein seul avec pneus conservés ; arrêt pneus sans essence ; export JSON |
| Driver DNA / coaching | profil par voiture × circuit × type, coaching par virage, vérification de la consigne, règles de course aussi en course personnalisée, bilan, briefing Sport, correction de consommation | DriverDna 10, StrategyV3 6, SessionAndRadioV3 7, AiCoaching 11 | seuils de virage calibrés en simulation seulement | 8 tours d'entraînement ; course : 3 consignes au plus, jamais en freinage |
| Radio V4 | bip d'ouverture et double bip garanti, « radio » redit = bip, course silencieuse (tours ordinaires muets, places espacées de 30 s et fraîches), réponses courtes, questions précises calculées localement, avis et aide vers la conversation, voix des réglages Android | HumanRadioV4 16, Ps5RadioRegression 6, October26PitRadioRegression 6, VoiceSafety 12, VoiceConversation 14, VoiceRouting 7, ConversationIntent 20, AiConversation 12, RadioAi 12, RealRadioPhrases 4, StreamRecognition 27, SpeechEndpointer 4, SpeechFallback 7, LivePosition 12, SectorsAndRadio 8 | voix Android (pas de voix neuronale, volontairement) | double bip avec le son du jeu ; silence en course ; appel au stand avec numéro de tour |
| Archives JSON | sessions (positions grille/arrivée, perte aux stands prévue), journal, Driver DNA (`driver_dna.json`), mémoire pilote, circuits appris (`learned_tracks.json`) ; champs absents = valeurs par défaut | ProfilesAndSessions 18, SessionJournal 5, SessionAndFuel 14, SessionMode 6 | pas de migration explicite : relecture tolérante | relire une ancienne course dans l'Historique |
| Voitures et circuits | base gt7info relue chaque jour et dès qu'une voiture est inconnue, version incomplète refusée, base livrée à jour (591 voitures) ; circuit Sport absent appris sous son nom publié (SUGO), remplacé par la fiche officielle | AutoDatabase 3 | SUGO absent de gt7info ; en course personnalisée sur un circuit inconnu, choix manuel (aucun nom inventé) | course Sport à SUGO ; voiture récente |
| Courses Sport | GTPlanet (RSS) par défaut ; GT GridStats en option avec clé personnelle ; fusion champ par champ (probable / confirmé / conflit non appliqué) ; avertissement si les tours GT7 contredisent l'édition | SportSchedule 17, GridStatsDailyFeed 4, RefreshPolicy 4 | GTPlanet publie parfois tard ; clé GridStats non disponible en libre-service (demande à l'équipe GridStats) ; DG EDGE non utilisé (protection anti-robots) | semaine sans article GTPlanet ; avec une clé GridStats |
| Sécurité des clés | clé GridStats chiffrée (Android Keystore, AES-GCM), masquée à la saisie, jamais dans l'APK, les journaux ni les exports ; envoyée uniquement en HTTPS à `gt-gridstats.com` (domaine corrigé le 26/10 : le code visait `gtgridstats.com`) ; `allowBackup=false` ; aucun secret détecté dans le dépôt | GridStatsDailyFeed (domaine) | `app/debug.keystore` versionné : clé de signature de débogage, sans valeur de production | — |

**Totaux** : 479 tests JVM, 0 échec (53 classes). Compilation Android et APK debug : CI #253 verte
(`GT7-Pitwall-debug-apk-253`).

## Suite proposée

1. Valider cette branche sur PS5 (colonne de droite).
2. Si ChatGPT la valide comme référence : une PR unique vers `main`, en remplacement des PR #12 à #16 (à fermer
   ensuite, sans les fusionner une à une).
3. Ensuite seulement : Tyre Intelligence V3 et Race Intelligence. Ni nouvelle voix ni nouveau moteur prédictif d'ici là.
