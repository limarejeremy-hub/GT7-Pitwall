# Journal de course intégral

Après chaque course, on retrouve tous les échanges radio et les événements de course, puis on exporte le tout pour
une analyse du pilote et de l'ingénieur (par exemple avec ChatGPT).

## Audit des structures existantes (avant cette étape)

| Structure | Rôle constaté | Réutilisation |
|---|---|---|
| `SessionArchive.kt` (`SessionRecord`, `LapEntry`, `StopEntry`, `PlanEntry`, `DriverNote`) | archive d'une session : tours mesurés, arrêts, pneus, plans, notes du pilote ; enregistrée à chaque tour | **même identifiant** de session pour le journal ; l'archive est **incluse dans l'export** |
| `RaceStorage` (interface) et `RaceStore` (Android) | règlements, mémoire, sessions (un fichier JSON par session, écriture atomique) | **trois méthodes ajoutées** au même stockage ; le journal va dans `files/journal/<session>.jsonl` |
| `RaceController` | cerveau de course : paquets, commandes, garde des commandes, plan, archive | **crée et alimente** le journal : début et fin de session, tours, stands, positions, plans, décisions |
| `PitwallEngine` | chaîne Android : UDP, voix, point de sortie unique des messages (`deliver`) | **journalise** les phrases reconnues, les messages dits ou affichés et les appels à Gemini ; **sert** la page Historique |
| `RadioConversation` | route chaque phrase : moteur, analyseur ou Gemini ; garde `AiTrace` | **inchangée** : sa trace (question, réponse brute, verdict, délai) est versée au journal |
| `VoskVoiceInput` | reconnaissance (Vosk, Google), conversation continue | **ajout du nom de la reconnaissance** dans `VoiceInputEvent.Utterance` (`recognizer`) |
| `GapHunter`, `PositionProbe` | diagnostics de développement | inchangés (le journal n'est pas un diagnostic, il est toujours actif) |

Il n'y a **pas d'archive concurrente** : l'archive garde les mesures par tour, le journal garde la chronologie.
Les deux partagent l'identifiant de session et sont réunis à l'export.

## Contenu du journal

**Chaque événement** comporte :
- l'heure ;
- le numéro d'ordre ;
- la session ;
- le tour ;
- le type ;
- la source ;
- le texte ;
- un instantané télémétrique léger : tour, tours prévus, vitesse, carburant, position mesurée (0x84), participants,
  chrono du tour (format C), températures des pneus, rapport, passage au stand ;
- le cas échéant, une décision et des détails.

| Type | Enregistré quand | Source |
|---|---|---|
| session | début (voiture, circuit, épreuve, règlement), arrivée, fin (terminée ou interrompue) | application, mesure GT7 |
| tour | chaque tour bouclé | mesure GT7 (voir détails ci-dessous) |
| stand | passage détecté (carburant ajouté, temps immobile) | mesure GT7 |
| position | grille, changement de place, classement final, position perdue | mesure GT7 (0x84/0x86) |
| pilote | chaque phrase reconnue, chaque question tapée | transcription ou écran |
| ingénieur, alerte | chaque message dit ou affiché | calcul du moteur ou Gemini |
| décision | interprétation d'une phrase par le garde des commandes ; commande appliquée ; appel à Gemini | moteur ou Gemini |
| stratégie | chaque nouveau plan d'arrêts, avec sa raison | moteur |
| radio | conversation ouverte ou fermée, statuts | application |

**Détails d'un tour** :
- temps ;
- carburant consommé et restant, ravitaillement ;
- vitesse maximale ;
- secteurs Pitwall ;
- part de TCS, blocages, patinages (avant et arrière) ;
- températures des pneus ;
- gomme, âge et certitude.

**Les 60 paquets UDP par seconde ne sont pas conservés** : ce sont des indicateurs par tour et des instantanés par
événement.

### Ce qui est dit, ce qui est transcrit, ce qui est calculé

- **Phrase réellement prononcée** : **inconnue**, car aucun son n'est enregistré. Chaque phrase du pilote le rappelle
  (`phrase_prononcee : inconnue`).
- **Transcription** : la phrase du vocabulaire radio et la transcription libre, avec la reconnaissance utilisée
  (Vosk ou Google, et le micro). On voit aussi si la phrase était adressée (« radio » devant) ou entendue en
  conversation continue.
- **Réponse de Gemini** : source « Gemini ». La trace de l'appel donne la question, la réponse brute, le verdict du
  contrôle (dite, refusée parce qu'elle invente un chiffre…) et le délai.
- **Décision du moteur** : source « calcul du moteur ». On y trouve la commande comprise, la décision du garde
  (appliquée, confirmation demandée, question, rien modifié) et l'origine (voix, conversation, Gemini, écran).
- **Mesure GT7** : source « mesure de la télémétrie GT7 ».

## Persistance

- **Écriture immédiate** : chaque événement est ajouté aussitôt dans `files/journal/<session>.jsonl` (une ligne JSON),
  puis vidé sur le disque (`fsync`). Si Android ferme l'application, seule la ligne en cours d'écriture peut manquer ;
  une ligne abîmée est ignorée à la lecture.
- **Avant la course** : les échanges radio des menus attendent en mémoire (200 au plus) et rejoignent la session
  suivante, marqués `avant_session`.
- **Conservation** : les 200 journaux les plus récents sont gardés, et le journal reste après la fermeture de
  l'application.

## Confidentialité

- **Aucun son** n'est enregistré. Aucune option d'enregistrement audio n'a été ajoutée. Si elle l'était un jour, ce
  serait une option séparée, désactivée par défaut, avec un consentement explicite.
- **Ni le jeton Discord ni les identifiants** ne passent par le journal.
- **Filet de sécurité** : toute adresse IP et toute chaîne qui ressemble à un jeton sont masquées avant l'écriture
  (`[adresse masquée]`, `[masqué]`).
- **Données locales** : elles restent sur le téléphone, dans l'espace privé de l'application. L'export n'a lieu que
  sur demande, par le partage Android.

## Page « Historique des courses »

Bouton **Historique** en haut de l'écran principal.

- **Liste** : les sessions, de la plus récente à la plus ancienne. Pour chacune : date, voiture, circuit, épreuve,
  nombre de tours et d'événements, et état (en cours, terminée, interrompue, fermée brutalement).
- **Chronologie** :
  - filtres « Tout », « Radio », « Décisions » et « Course » ;
  - toucher un événement affiche ses détails et sa télémétrie.
- **Export texte** : le fichier commence par la légende des sources, puis le tableau des tours, puis la chronologie
  (une ligne par événement).
- **Export JSON** : légende, archive complète de la session et tous les événements.
- **Affichage** : deux colonnes sur le Fold déplié, une colonne plié.

## Tests automatisés (course simulée, `SessionJournalTest`)

- **Même identifiant** : les tours, le stand, le plan et la décision sont sous l'identifiant de la session archivée.
  La numérotation est continue, la session s'ouvre et se ferme, et chaque tour du journal correspond à un tour archivé.
- **Bon tour** : une phrase dite au tour 3 est associée au tour 3, avec son origine (voix) et sa décision.
- **Commande de l'écran** : elle est notée avec l'origine « écran ».
- **Radio avant la course** : elle rejoint la session suivante, après son ouverture.
- **Données sensibles** : l'adresse IP et le jeton sont masqués, et une ligne abîmée est ignorée.
- **Exports** : JSON complet avec la légende et l'archive ; texte avec une ligne par événement ; liste des sessions.

## Protocole de validation PS5 (environ 15 minutes)

1. **Avant la course (menus, 2 min)** :
   - lance Pitwall et la télémétrie ;
   - dis « Radio », puis « test radio » ;
   - dis « Terminé ».
2. **Course hors ligne, 4 tours, ravitaillement au tour 2 (10 min)** :
   - **tour 1** : vers la mi-tour, dis « Radio, combien d'essence ? », et note le tour affiché par le HUD ;
   - **tour 2** : rentre au stand, ravitaille, puis dis « Radio, arrêt fait » ;
   - **tour 3** : dis « Radio, comment vont mes pneus ? », puis pose une question ouverte à Gemini s'il est activé ;
   - **dépassement** : si tu doubles quelqu'un, note le tour ;
   - termine la course.
3. **Historique (3 min)** :
   - « Historique », puis la session du jour ;
   - attendu :
     - la session est en tête de liste et marquée « terminée » ;
     - « test radio » apparaît avec le détail `avant_session = oui` ;
     - « combien d'essence » est au tour 1, suivi de la réponse de l'ingénieur (source « calcul du moteur ») ;
     - un événement « stand » au tour 2, puis la décision « arrêt fait » au même tour ;
     - les tours 1 à 4, avec leurs temps égaux à ceux du HUD ;
     - les changements de position aux bons tours ;
     - la réponse de Gemini marquée « Gemini », avec son verdict ;
   - quitte l'application (glisser hors de la liste des applications), rouvre-la : la session est toujours là ;
   - « Export texte » et « Export JSON » : envoie-moi les deux fichiers.

À noter : tout événement au mauvais tour, toute phrase manquante et toute réponse attribuée à la mauvaise source.
