# Radio : écho de l'ingénieur et commandes fantômes (issue #45)

## Constat (course PS5 du 28/10, APK #457)
- 26 « Interruption du pilote », toutes moins de 2,6 s après une annonce de l'ingénieur (médiane 1,65 s). Les mots reconnus ressemblaient à l'annonce elle-même :
  - « Tu es P10 sur 14 » → « dix second » ;
  - « Tu es P8 sur 14 » → « peux huitième » ;
  - « Tour 3 : 2 minutes 9 secondes » → « tour trois », « deux minutes neuf » ;
  - « Meilleur tour » → « meilleur tôt ».
- Deux « course de 2 minutes » ont été **appliquées** alors que GT7 annonçait 5 tours. Des « cinq » et « quatorze » ont été pris pour des positions.
- Hypothèse forte, sans preuve acoustique : la voix du bot Discord revient dans le flux du micro du casque PS5.

## Causes dans le code
1. `DuplexPolicy` activait d'office l'interruption pendant la parole sur Discord, en supposant que le pilote ne s'entend pas.
2. Le détecteur d'interruption ne reconnaissait l'écho que mot pour mot. Vosk transcrit « P10 » en « dix », et « 14 » en mots qui ne figurent pas dans le texte de l'ingénieur.
3. `CommandGate` appliquait sans confirmation un règlement dont la phrase commençait par un nombre. « deux minutes… » devenait « course de 2 minutes ».

## Correctifs
- **Discord** : plus d'interruption pendant la parole par défaut (`DuplexMode.OFF`). Option « Interruption aussi par Discord (expérimental) » dans l'Atelier, à n'activer qu'après un essai à 0 fausse interruption. Le mot « Radio » et la conversation après la réponse restent inchangés.
- **`EchoFilter`** : compare ce qui est entendu à ce que l'ingénieur vient de dire, sur une forme commune (sans accents, nombres et rangs en chiffres, « P10 » → 10, mots proches acceptés).
  - Interruption refusée si au moins la moitié des mots viennent de l'ingénieur.
  - Un « attends » ou un « stop » ne compte que s'il n'est pas dans sa phrase.
  - Les refus sont notés (`FAUSSE_INTERRUPTION`), sans aucun son.
- **Phrases du pilote** : seules celles commencées pendant la voix de l'ingénieur, ou dans les 2 s qui suivent, sont comparées à ce qu'il vient de dire. La fenêtre d'écho part du premier son réellement émis, et dure 10 s plus environ 350 ms par mot.
  - Un écho complet est écarté sans réponse parlée.
  - Un écho partiel portant une modification de course demande un « oui ».
  - Une réponse attendue (« oui », « medium ») n'est jamais écartée.
- **Règlement à la voix** (durée, tours, multiplicateurs, arrêts obligatoires, relais pneus) : toujours « Tu confirmes… ? » avant d'être appliqué.
- **Durée** :
  - un chrono (« 2 minutes 9 », « … secondes ») n'est jamais une durée de course ;
  - si GT7 annonce un nombre de tours, une durée est refusée : « GT7 annonce une course de 5 tours ».
- **Journal** : fermeture de conversation avec un code (`FERMEE_PILOTE`, `FERMEE_SILENCE`), la voie (Discord ou téléphone) et le nombre d'échos écartés.

## Tests
- `RadioEchoTest` (9) : échos réels du 28/10, Discord qui entend sa propre voix, mode par défaut, chrono « deux minutes », course de 5 tours, règlement avec « oui », position par écho, réponse après la fin de la phrase, arrêt pendant la voix, longue réponse.
- Tests existants adaptés : un règlement dicté demande maintenant « oui ».

## Protocole PS5 (statut : non testé)
Interruption Discord **désactivée** : 10 questions, 10 réponses. Attendu : 0 fausse interruption et 0 commande involontaire, avec le journal exporté. Seulement ensuite, essayer l'option d'interruption par Discord.

## Révision après la revue GPT de la PR #46
- **Bloquant A (compilation Android)** : corrigé dans 8a70b2e. Exécution 468 réussie.
- **Bloquant B (fenêtre d'écho)** : `EchoTiming.near` ne regarde que le début de la phrase **en cours**.
  - Un début déjà utilisé pour une phrase précédente, ou vieux de plus de 12 s, est ignoré.
  - Une phrase est comparée à l'ingénieur si elle a commencé pendant sa voix, ou au plus 2 s après sa fin.
  - Sans début connu (Google, secours), la remise doit suivre la fin de sa voix de 5 s au plus.
- **Questions courtes** : une question du pilote (« combien de tours ? », « position ? ») n'est jamais prise pour un écho. Les petits mots (« de », « la », « tu »…) ne comptent plus dans la comparaison.
- **« oui » par écho** : un « oui » entendu alors que l'ingénieur vient de dire « oui » ne confirme rien.
- **Journal** : chaque état de l'écoute a un code :
  - `MICRO_MUET`, `ECOUTE_INDISPONIBLE`, `ECOUTE_RELANCEE`, `ECOUTE_REPRISE`, `MICRO_REPRIS` ;
  - `FAUSSE_INTERRUPTION` ;
  - `DISCORD_CONNECTE` et `DISCORD_DECONNECTE` ;
  - `FERMEE_PILOTE` et `FERMEE_SILENCE`.
- Tests ajoutés : `onlyTheCurrentPhraseStartCountsForTheEchoWindow`, `shortRealQuestionsAfterTheEngineerAreNeverSuppressed`, `aYesHeardInTheEngineersOwnSentenceConfirmsNothing`, `radioStatesHaveDistinctJournalCodes`.
