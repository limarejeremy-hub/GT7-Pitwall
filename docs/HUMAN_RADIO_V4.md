# Human Radio V4

Branche `claude/human-radio-v4`, créée depuis `claude/tyre-intelligence-v2` (`7b27a5d`, PR #12), sans fusion.

**Principe** : le moteur Pitwall reste la source de vérité. Gemini est l'interface conversationnelle, et l'ingénieur
fonctionne entièrement sans lui.

**Réutilisé de Race Engineer V3** (PR #13, branche `claude/race-engineer-v3`) : le commit de sa phase 1, repris tel quel
(`e64cba3`). Il contient :

- la correction du `RadioPacer`, qui ne relâche plus une annonce au bout de 6 s si le pilote reste occupé ;
- les gardes de session (rediffusion, coupure de télémétrie, format de course).

Le Driver DNA et le coaching par virage de V3 ne sont pas repris ici. Ils pourront alimenter la mémoire durable
(constats mesurés) quand les deux branches seront réunies.

## 1. Audit de l'existant

| Fichier | Rôle | Constat pour V4 |
|---|---|---|
| `EngineerPrompt.kt` | Consigne et contexte de Gemini (faits, calcul du moteur, mémoire, échanges) | Bonne base. Il manquait la personnalité et le contexte sportif |
| `RadioConversation.kt` | Aiguillage : accord, IA cloud, moteur, Gemini Nano | Une réponse lente de Gemini était dite même après une autre question, une interruption ou une nouvelle session |
| `ConversationRouter.kt` | Ce qui part au modèle, avec quel sujet | Conservé |
| `RaceMemory.kt` | Intentions, plans, décisions, faits, échanges | Il manquait les préférences de course, les hypothèses, les désaccords et la rétractation |
| `RadioAi.kt` | Vérification des réponses (chiffres absents des données, autres voitures, conseils non prouvés) | Conservé tel quel : la personnalité ne relâche aucun contrôle |
| `ConversationWindow.kt` | Conversation continue après « Radio » | Délai de 5 s trop court. La fenêtre pouvait se fermer pendant la réflexion de Gemini. Pas de garde contre le bruit |
| `VoskVoiceInput.kt` | Mot de réveil, Vosk et Google, micro du Fold ou de Discord | Micro **coupé** pendant que l'ingénieur parle : aucune interruption possible |
| `GoogleSpeech.kt` | Reconnaissance Android de la phrase | Conservé. Elle prend le micro du Fold pour une phrase, d'où l'interruption confiée à Vosk |
| `SpeechEndpointer.kt` | Fin de phrase dans le flux Discord | Conservé |
| `TtsVoiceOutput.kt` | Synthèse Android | Débit unique, pas de hauteur, message dit d'un trait, aucun repère du premier son |
| `DiscordRadio.kt` | Bot : voix vers le salon, micro du pilote reçu **par utilisateur** | Le flux du pilote est séparé de la voix du bot : une écoute simultanée est réellement possible |
| `VoiceRouting.kt` | Discord ou Fold, rejeu sur le Fold si Discord tombe | Conservé (sert au choix du mode d'interruption) |
| `RadioPacer.kt` | Différer pendant un gros freinage | Bug des 6 s (corrigé par V3, repris) |
| `RadioFilter.kt` | Niveau radio, anti-répétition | Conservé |
| `RaceController.kt` | Cerveau de course | Il manquait les actes de dialogue, le désaccord argumenté et le contexte sportif |
| `PitwallEngine.kt` | Chaîne Android | Phrases traitées l'une après l'autre : un appel Gemini lent bloquait la suivante |

## 2. Cornelius : audit et correspondance

Dépôt étudié : `lfpoulain/Cornelius`, commit `ecaecd8`.

**Ce qu'est Cornelius** :

- un miroir connecté, avec un serveur Node.js (Fastify, WebSocket) et une interface React ;
- il repose sur la session **OpenAI Live** (`gpt-live-1`, voix « cedar »), service payant à clé côté serveur ;
- il délègue à un modèle « responses » avec des outils.

| Mécanisme Cornelius (fichier) | Fonctionnement réel | Intérêt pour Pitwall | Android / Kotlin | Casque PS5 via Discord | Coût, dépendances | Repris ? |
|---|---|---|---|---|---|---|
| Personnalité réglée par préférences JSON, distinctes des faits et de la mémoire (`live/configuration.ts`) | Préférences de style ajoutées aux instructions ; elles « ne modifient jamais les règles de silence, de confidentialité, de vérification » | Personnalité stable sans toucher aux contrôles | Texte de consigne : direct | Indépendant | Aucun | **Principe réimplémenté** (`Persona`) : personnalité dans la consigne, contrôles `RadioAi` intacts |
| Interjections « mmh, je vois » et phrases d'attente à rallonge (`configuration.ts`) | Le modèle comble l'attente en parlant | Contraire à ta demande (pas d'hésitations simulées, pas de phrase répétitive) | — | — | — | **Écarté** : retirées des réponses (`Persona.polish`), un petit signal sonore à la place |
| Politique d'interruption (`configuration.ts`) et détection de voix serveur d'OpenAI Live | Le modèle s'arrête dès que la personne parle (VAD du service) | Indispensable | Nécessite un flux audio vers un service temps réel | Possible (flux du pilote séparé) | Service payant à la minute audio | **Principe réimplémenté localement** : `InterruptDetector` et `VoiceActivity`, sans service |
| Semi-duplex avec garde anti-écho de 600 ms (`live/transport.ts`, satellites) | Sans annulation d'écho, le micro est muet pendant que Cornelius parle, et 600 ms après | Haut-parleur du Fold | Direct | Inutile : flux séparé | Aucun | **Principe réimplémenté** : mode `GAPS`, garde de 150 ms entre les phrases |
| Minuterie de silence (`controller.ts`) | Le silence ne compte que si la personne ne parle pas, que l'assistant ne réfléchit pas et ne parle pas (avec une marge) | Ne plus fermer la conversation pendant que Gemini réfléchit | Direct | Indépendant | Aucun | **Principe réimplémenté** : `ConversationWindow.thinking`, délai porté à 8 s |
| Ajout d'instructions à la session en cours, une seule fois par clé (`transport.ts`, `session.instructions.append`) | Le contexte actualisé est poussé au modèle vocal sans tout renvoyer | Mise à jour dynamique du contexte de course | Utile seulement en architecture B | — | — | Noté pour l'architecture B. En A, le contexte est reconstruit à chaque question |
| Clé API seulement côté serveur (`live/gateway.ts`) | Le navigateur ne voit jamais la clé | Aucune clé dans l'APK | Pitwall utilise déjà Firebase AI Logic et App Check | — | — | Déjà en place |
| Extraction de mémoire après la conversation (`memory-extractor.ts`, `memory.ts`) | Seuls les faits **explicitement déclarés** par la personne, avec la preuve (tour cité), sans inférence, sans secret ; corriger ou oublier sur demande ; aucun texte journalisé ; capture abandonnée si le profil ou l'interaction change | Mémoire durable sûre | Le modèle d'extraction est un service payant | — | Appel à un modèle | **Principe réimplémenté sans IA** (`PilotMemory`) : « retiens que… » puis « oui » ; consultable, supprimable, désactivable |
| Mot d'appel local openWakeWord (`web/audio/wake.ts`, `models/cornelius.onnx`) | Le même flux micro sert au réveil puis à la session | Pitwall a déjà Vosk | ONNX sur Android possible | — | Modèle sous CC BY-NC-SA 4.0 | **Non repris** (licence non commerciale, et Vosk suffit) |

### Licence

- **Cornelius** : PolyForm Noncommercial 1.0.0 (« Required Notice: Copyright (c) 2026 Entremakers SAS, Les Frères
  Poulain »). Le modèle de mot d'appel est sous CC BY-NC-SA 4.0. Les composants tiers (polices OFL, shadcn MIT,
  bibliothèques MIT ou Apache) gardent leur propre licence.
- **Aucun fichier, extrait de code ni modèle de Cornelius n'est copié dans Pitwall.** Seuls des principes techniques
  sont réimplémentés, en Kotlin original : silence compté quand tout le monde se tait, garde anti-écho, mémoire limitée
  aux déclarations explicites, personnalité séparée des faits.
- Pitwall reste donc libre de toute contrainte non commerciale. Cornelius est cité ici comme source d'inspiration.

## 3. Ce qui est développé (phases A et B complètes, C de base, D partielle)

### Phase A : personnalité et dialogue

- **Personnalité compétiteur** (`Persona`) :
  - consigne ajoutée à Gemini : avis propre, franchise, reconnaît ses erreurs et ce qu'il ignore, honnête sur sa nature,
    joie brève sur un événement réel ;
  - formules interdites et hésitations retirées des réponses générées, ouverture répétée supprimée ;
  - aucun mot porteur de fait n'est ajouté.
- **Contexte sportif** (`MoodTracker`) : CALME, ATTAQUE, PRESSION, STRATÉGIE, CÉLÉBRATION.
  - Il est déduit des seuls événements mesurés : place gagnée ou perdue, meilleur tour, sortie de piste, tour
    nettement plus lent, fenêtre d'arrêt, question stratégique, rythme en progression.
  - Il est transmis à Gemini comme ton, jamais comme humeur du pilote.
- **Actes de dialogue traités localement** (`DialogueActs`) :
  - « oublie ce que je viens de dire » ; « non attends, finalement on reste en piste » (rétractation, puis la suite
    est traitée) ;
  - « tu es humain ? » ;
  - « tu le sens comment ? » : avis tiré des faits, ou formulé par Gemini s'il est disponible ;
  - préférence de course (« je veux préserver les pneus ») ;
  - préférence durable, confirmée avant d'être retenue ;
  - « je veux rentrer maintenant » ;
  - décision explicite ;
  - hypothèse.
- **Désaccord** :
  1. Le moteur chiffre l'arrêt immédiat. L'ingénieur répond « Je ne suis pas convaincu. … Qu'est-ce qui te pousse à
     rentrer ? », ou « Ça se discute. », ou « D'accord avec toi. ».
  2. Ta raison (« je perds l'avant ») est prise en compte : « OK, information importante. Je prends ton ressenti en
     compte. », puis le plan est réévalué par le moteur.
  3. « Je rentre quand même » est confirmé (« Dis oui pour confirmer ») avant d'être noté comme décision. Rien n'est
     modifié automatiquement.
- **Réaction brève** « Bien joué ! P5. » sur une place gagnée mesurée, au plus toutes les 2 minutes, sans en
  attribuer la cause.

### Phase B : interruption et rythme

- **Mode selon le matériel** (`DuplexPolicy`) : l'interruption n'est annoncée que là où elle peut fonctionner.
  - Voix dans le casque PS5 via Discord : interruption **pendant** la parole. Le flux du pilote est reçu séparément de
    la voix du bot, donc l'ingénieur ne s'entend pas.
  - Fold avec casque ou écouteurs : pendant la parole.
  - Haut-parleur du Fold : **entre les phrases**. La synthèse insère une pause de 450 ms après chaque phrase, et le
    micro n'écoute que là, avec une garde anti-écho de 150 ms.
  - Une alerte critique n'est jamais coupée.
  - L'interruption se désactive dans la carte « Radio humaine ».
- **Détecteur** (`InterruptDetector` et `VoiceActivity`) : il faut une voix établie au-dessus d'un plancher de bruit
  adaptatif, **et** des mots qui ne sont pas ceux que l'ingénieur prononce : « attends », « stop », « non »… ou une
  vraie phrase. Les bruits du jeu, un mot isolé et l'écho ne coupent rien.
- **Après une interruption** :
  - la voix s'arrête (file Discord vidée, synthèse arrêtée) ;
  - la réponse en préparation devient périmée (numéro de tour de parole) ;
  - l'écoute reprend sans délai ;
  - l'ancienne réponse n'est jamais reprise.
- **Tours de parole** : chaque phrase est traitée à part. Une réponse lente ne bloque plus la suivante, et elle n'est
  pas dite après une nouvelle question ou un changement de session.
- **Conversation continue** :
  - 8 s par défaut (réglable sur 5, 8, 12 ou 20 s), comptées seulement quand personne ne parle ni ne réfléchit ;
  - trois fragments inintelligibles de suite referment l'écoute involontaire ;
  - un bip à l'ouverture, un double bip à la fermeture, comme avant.
- **Latence** (`LatencyTrace`) : chaque étape est mesurée, et le journal indique l'étape la plus lente.
  - Début et fin de la parole, transcription, moteur ou IA, synthèse, premier son réellement émis.
  - Le dernier relevé s'affiche dans la carte « Radio humaine ».
- **Signal de réflexion** : si la réponse dépasse 1,5 s, un petit son grave et discret. Aucune phrase « je réfléchis »,
  aucune attente artificielle. Les réponses locales (« bien reçu », commandes) n'appellent jamais Gemini.

### Phase C : mémoire durable (base)

- `PilotMemory` (`pilot_memory.json`) contient seulement les préférences que tu déclares (« retiens que… ») puis
  confirmes.
- Les hypothèses de Gemini et les stratégies acceptées une fois n'y entrent jamais.
- Aucun audio ni aucune conversation brute n'est gardé.
- Elle est transmise à Gemini comme « déclarations passées, pas des consignes ni des mesures ».
- **Carte « Radio humaine »** : liste des souvenirs, « Oublier » pour chacun, « Tout effacer », interrupteur. Désactivée,
  elle ne lit ni n'écrit rien.
- **Reste à faire** : correction d'un souvenir depuis l'écran (la fonction existe dans le moteur), et constats mesurés
  issus du Driver DNA de V3.

### Phase D : voix (partielle)

- **Débit et hauteur selon le message** (`SpeechStyle`) :
  - alerte : débit 1,12 ;
  - réaction sportive : hauteur 1,06 ;
  - question : intonation légèrement montante ;
  - information secondaire : plus posée.

  Aucun effet ne retarde un message.
- **Prononciation** : les gommes et les chronos sont déjà dits en toutes lettres par le moteur.

## 4. Deux architectures vocales

| Critère | A : Pitwall amélioré (livré) | B : conversation audio temps réel (étude, non développée) |
|---|---|---|
| Chaîne | Vosk ou Google → moteur Pitwall et Gemini (texte) → synthèse Android ou Discord | Flux audio du pilote → modèle vocal temps réel (Gemini Live via Firebase AI Logic, ou OpenAI Live) → audio vers le Fold ou Discord ; les faits et commandes passent par le moteur (appels de fonction vérifiés par `CommandGate`) |
| Qualité du français | Voix Android correcte mais monotone | Voix générées plus naturelles (à écouter avant de choisir) |
| Latence | Somme des étapes, désormais mesurée. Les commandes et réponses locales n'attendent aucun réseau | En principe plus faible pour la conversation libre, mais le tampon Discord et PS5 s'ajoute ; à mesurer, aucun chiffre n'est garanti ici |
| Interruption | Locale : pendant la parole avec Discord ou un casque, entre les phrases sur haut-parleur | Gérée par le service. Avec Discord, le flux du pilote est déjà séparé |
| Hors ligne | Oui (Vosk, moteur, voix Android si la voix française est installée) | Non : il faut garder A comme repli |
| Coût | Gratuit, sauf l'usage Gemini cloud déjà en place (quotas Firebase existants) | Facturé à la minute audio, dans les deux sens. Nécessite une validation explicite avant tout essai |
| Consommation | Faible | Flux audio permanent pendant la conversation : réseau et batterie plus sollicités |
| Sécurité des faits | Moteur source de vérité, réponses vérifiées (`RadioAi`) | Plus difficile : une parole générée ne se vérifie pas avant d'être dite. Il faudrait limiter le modèle à reformuler des faits fournis, et garder les chiffres dans des réponses du moteur |
| Clé API | Firebase AI Logic et App Check, aucune clé dans l'APK | Même mécanisme possible avec Firebase (Live API) ; jamais de clé dans l'APK |

**Recommandation** : garder A comme base. Pour B, il faudrait :

1. un prototype isolé, derrière un interrupteur, réservé à la conversation libre ;
2. un essai mesuré avec le diagnostic de latence ;
3. ton accord sur le coût avant tout appel payant.

**Voix alternatives facultatives** :

- `sherpa-onnx` est déjà présent dans l'appli (banc de reconnaissance). Il sait aussi faire de la synthèse hors ligne
  avec des voix Piper françaises. Chaque voix a sa licence : Siwis CC BY 4.0, UPMC CC BY-SA 4.0, Tom AGPL v3, à
  vérifier avant de la distribuer.
- Une synthèse cloud (Google Cloud TTS ou voix Gemini) serait payante au-delà de son quota gratuit.

Aucune n'est activée.

## 5. Tests

**427 tests, 0 échec.** `HumanRadioV4Test` en compte 15, qui couvrent les 20 cas demandés.

| Cas | Test |
|---|---|
| 1-3 Conversation continue, bip unique, double bip | `aConversationStaysOpenAfterRadioWithOneReadyBeepAndADoubleBeepAtTheEnd` |
| 4 Pas de fermeture pendant une phrase ou une réflexion | `silenceNeverClosesTheConversationWhileTheEngineerSpeaksOrThinks` |
| 5 Interruption | `theDriverCanInterruptTheEngineer` |
| 6 Pas d'auto-interruption, alerte jamais coupée, modes | `theEngineerNeverInterruptsHimself` |
| 7 Bruits du jeu, fausses reconnaissances | `gameNoiseAndFalseRecognitionsDoNotInterruptOrKeepListening` |
| 8 et 15 Réponse périmée (nouvelle question, nouvelle session) | `aStaleAnswerIsNeverSpoken` |
| 9 et 10 Décision à confirmer, désaccord sans modification | `aDisagreementIsDebatedAndADecisionIsConfirmedBeforeAnything` |
| 11 et 12 Préférence retenue, hypothèse non factuelle, oubli, mémoire durable | `preferencesAreRememberedAndHypothesesAreNotFacts` |
| 13 Reconnexion Discord | `aDiscordDropFallsBackToThePhoneAndChangesTheInterruptionMode` |
| 14 Gemini indisponible | `withoutGeminiTheEngineerStillTalksNaturally` |
| 16 et 17 Alerte critique, message différé puis périmé | `criticalAlertsAlwaysPassAndDeferredMessagesExpire` (et `SessionAndRadioV3Test`) |
| 18 Aucun chiffre inventé, aucune formule creuse | `noInventedNumbersAndNoHollowPhrases` |
| 19 Écoute et synthèse après interruption, latence | `afterAnInterruptionTheNextQuestionIsAnsweredAndLatencyIsMeasured` |
| 20 Non-régression pneus, carburant, plan | `conversationNeverChangesTyresFuelOrPlanByItself` et toute la suite existante |

Les interactions audio sont simulées : événements, blocs PCM synthétiques, réponses d'IA scriptées. **Elles ne
remplacent pas un essai réel** avec le casque PS5 et le Fold.

## 6. Limites honnêtes

- **Haut-parleur du Fold** : pas d'interruption au milieu d'une phrase, seulement dans les pauses. L'annulation d'écho
  d'Android n'est pas fiable sur un haut-parleur proche du micro, et je ne l'annonce pas.
- **Début de la phrase après une interruption** : la reconnaissance Google reprend le micro tout de suite, mais les
  premiers mots dits pendant la coupure peuvent manquer. Le mot d'interruption lui-même (« attends ») n'est pas traité
  comme une commande.
- **Seuils** (voix, plancher de bruit, délai de 8 s, garde de 150 ms) : choisis sur simulation, à valider en course.
- **Écho par Discord** : supposé faible (casque fermé), à vérifier avec des enceintes ou un casque ouvert.

## 7. Protocole d'essai sur PS5

À faire avec le casque PS5 (Discord), puis avec le PS VR2. Exporte le journal après chaque série.

1. **Conversation** : « Radio » → bip court. « Bon, tu le sens comment ? » → avis bref. Puis « et si je mets les
   tendres ? » sans redire « Radio » → réponse. Silence de 8 s → double bip.
2. **Pas de fermeture prématurée** : pose une question complexe (Gemini). Aucun double bip pendant qu'il réfléchit ;
   un petit son grave s'il dépasse 1,5 s.
3. **Interruption au casque** : pendant une longue réponse, dis « Non, attends ! ». Attendu : la voix s'arrête, ta
   phrase suivante est entendue, l'ancienne réponse n'est jamais reprise. Le journal note « Interruption du pilote ».
4. **Pas d'auto-interruption** : laisse l'ingénieur parler sans rien dire, jeu à fond. Aucune interruption dans le
   journal.
5. **Haut-parleur du Fold** : même essai, en coupant entre deux phrases.
6. **Désaccord** : « Je veux rentrer maintenant » → avis chiffré et question. « Je perds l'avant » → prise en compte.
   « Je rentre quand même » → confirmation, puis « oui ».
7. **Mémoire** : « Retiens que je préfère des réponses courtes » → « oui ». Vérifie la carte « Radio humaine », puis
   « Oublier ».
8. **Alertes** : en pleine conversation, l'alerte carburant critique passe toujours, sans être coupée.
9. **Latence** : lis les lignes `Latence :` du journal (étape la plus lente).
10. **Non-régression** : bips, Discord, Vosk et Google, écran éteint, Fold, pneus, carburant, positions, plans d'arrêt.

## 8. Retour d'essai PS5 : corrections

Aucun journal de l'essai n'a été transmis. Les défauts ci-dessous viennent du compte rendu du pilote ; chacun est
reproduit par un test (`Ps5RadioRegressionTest`, `TyresKeptTest`), sans données de course inventées.

### Bip de fermeture absent (corrigé)

Cause trouvée dans le code : l'application interroge la fenêtre de conversation toutes les 250 ms et lui signale à
chaque fois « pas de réflexion en cours ». Chaque signal repoussait l'échéance de 8 s : la conversation ne se
refermait donc jamais d'elle-même, et le double bip n'arrivait pas. Deux autres causes possibles ont aussi été fermées :

- le bruit du jeu reconnu comme une phrase en cours prolongeait l'écoute sans limite. Désormais, un fragment `[unk]`
  ne compte pas, et la prolongation est limitée à 6 s après l'échéance ;
- un état « l'ingénieur parle » ou « réflexion » resté coincé suspendait tout. La suspension est maintenant bornée à
  45 s.

Une vraie réflexion (Gemini) ou une phrase de l'ingénieur suspend toujours le délai, qui repart ensuite pour la durée
réglée (8 s par défaut).

### Ingénieur trop bavard (corrigé)

- **Course : silence par défaut** (`quietRace`, activé dans l'application). Un tour ordinaire n'est plus annoncé : le
  chrono reste à l'écran et dans « détails ». Restent annoncés :
  - un meilleur tour ;
  - le dernier tour et l'arrivée ;
  - un carburant juste ;
  - un avis sur les pneus ;
  - l'appel au stand et toutes les alertes critiques, toujours prioritaires.

  En qualification et avec « rapports de tour détaillés », rien ne change.
- **Positions périmées** : une place annoncée est remplacée par la plus récente si elle attend encore. Elle expire après
  4 s, et n'est jamais mise en file derrière une phrase déjà en cours. La place n'est plus répétée dans le message du
  tour.
- **Questions simples : une ou deux phrases** (carburant, pneus, stratégie, rythme…). Le rappel du règlement, le détail
  du calcul et les « pas encore assez de tours » sont retirés. L'IA est limitée à 2 phrases, et à 4 si le pilote
  demande le détail (« pourquoi », « explique », « analyse complète », « bilan complet », « détails »). « Analyse
  complète » donne alors tour, carburant, pneus et plan.

### Pourquoi la voix reste robotique : limites identifiées

| Maillon | Limite précise | Ce qui a été fait |
|---|---|---|
| Texte (moteur) | Phrases télégraphiques générées par gabarit (« Tour 4 : 37 secondes 8. Plus 11 centièmes… ») : c'est le contenu qui sonne machine, quelle que soit la voix | Moins de messages, plus courts. La reformulation naturelle (`Persona`) reste limitée aux réponses |
| Gemini | Ne produit que du texte. Il n'a aucune prise sur l'intonation ; sa réponse est attendue en entier, vérifiée puis tronquée (`RadioAi`) avant d'être dite | Réponses limitées à 2 phrases sauf demande explicite |
| Synthèse Android | Voix par défaut de la langue, pas forcément la meilleure installée. La prosodie n'est réglable que par débit et hauteur pour tout le message ; pas d'emphase ni de respiration | Choix automatique de la meilleure voix française **locale** installée (`VoiceChoice`), sur le Fold et pour Discord |
| Architecture | Chaîne en série : reconnaissance → texte → synthèse. Rien n'est diffusé en flux. Pour Discord, la phrase entière est synthétisée dans un fichier avant d'être envoyée | Inchangé (voir le chemin ci-dessous) |

### Comparaison avec Cornelius

Cornelius utilise un modèle **parole vers parole** en temps réel (OpenAI Live, payant, clé côté serveur). La voix est
générée directement par le modèle, en flux, avec l'intonation du sens de la phrase. Ce qui le rend naturel tient
surtout à ce modèle, pas à son code. Ses mécanismes de conversation, eux, ont été repris dans Pitwall par des principes
réimplémentés : minuterie de silence qui ne compte que si personne ne parle ni ne réfléchit, garde anti-écho,
consignes dédupliquées, mémoire des seuls faits explicites. Sa voix, elle, ne peut pas être reprise sans un service
vocal payant.

### Chemin réaliste, sans service payant

1. **Fait dans cette version** : moins de paroles, réponses courtes, et la meilleure voix française installée.
2. **À faire par toi, gratuit** : Paramètres Android → Synthèse vocale → Services vocaux Google → Installer les données
   vocales → Français (France). Choisis la voix la plus naturelle et vérifie qu'elle est téléchargée (hors ligne).
   Pitwall la prendra automatiquement.
3. **Prochaine étape proposée, à valider** : voix neuronale locale avec `sherpa-onnx` (déjà dans l'APK) et une voix
   Piper française. Elle est gratuite et hors ligne, mais ajoute environ 60 Mo et demande un essai d'écoute ainsi que
   la vérification de sa licence (voir §4).
4. **Diffusion en flux** : dire la première phrase de Gemini dès qu'elle arrive. Cela réduirait l'attente, sans coût
   supplémentaire, mais n'est pas fait.
5. **Parole vers parole** (Gemini Live ou OpenAI Live) : ce serait le plus naturel, mais c'est payant ou limité par
   quota. Rien n'est activé : ton accord est nécessaire avant tout essai.

### À valider sur PS5

- Le double bip arrive environ 8 s après la dernière réponse, y compris avec le son du jeu fort.
- Plus aucune place annoncée en retard. En course, l'ingénieur se tait sur les tours ordinaires.
- « Combien d'essence ? » reçoit une réponse en deux phrases au plus ; « analyse complète » donne le détail.
- La voix retenue s'affiche-t-elle mieux à l'écoute ? Comparer avant et après l'installation de la voix Google
  haute qualité.
