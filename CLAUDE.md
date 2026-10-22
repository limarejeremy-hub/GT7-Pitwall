# GT7 Pitwall — instructions pour Claude Code

**Lire et appliquer d'abord [`AGENTS.md`](AGENTS.md)** : c'est le protocole commun qui fait foi pour Claude Code, Codex et les autres agents. Ce document ajoute seulement des consignes propres au travail de Claude ; en cas de contradiction, `AGENTS.md` prévaut.

## Au démarrage de chaque tâche

1. Confirmer le dépôt, la branche active et l'état du répertoire : `git remote -v`, `git branch --show-current`, `git status --short`, `git log -1 --oneline`.
2. Lire l'issue ou la PR GitHub concernée, y compris les commentaires et critères d'acceptation. Ne pas supposer qu'une conversation Claude/ChatGPT a été vue par l'autre agent.
3. Lire les fichiers et documents pertinents dans `docs/` avant toute modification. Vérifier la branche de base approuvée par Jeremy ; ne pas présumer que `main` contient l'application.
4. Créer une branche de tâche `claude/<sujet>` distincte des branches Codex et de la branche utilisée pour les essais PS5.

## Rôle de Claude Code

- Implémenter les tâches explicitement validées ; préférer des modifications limitées et testables.
- Préserver la séparation `core` (calculs fiables de course) / `app` (Android, voix, interface et fournisseurs IA).
- Pour les données de télémétrie, ne tirer de conclusion que de mesures démontrées ; signaler les champs expérimentaux comme tels.
- Ne jamais demander à l'IA de décider seule d'un arrêt, d'une gomme ou d'une position. Les changements d'état passent par la logique et les confirmations existantes.
- Garder les réponses vocales courtes et la télémétrie indépendante des appels cloud.

## Collaboration avec Codex et ChatGPT

- Publier le travail dans une PR GitHub, avec un résumé, des risques et des tests **réellement** passés.
- Inviter Codex à examiner la PR dans une tâche dédiée. Lire ses commentaires sur GitHub, répondre point par point et apporter les correctifs sur **ta branche** ; ne pas modifier celle de Codex.
- Si Codex soumet une PR, effectuer une revue technique concrète : exactitude des calculs, cas limites, régressions, preuves, absence de valeurs inventées, robustesse Android et respect des critères d'acceptation.
- ChatGPT peut cadrer les tâches et consulter les éléments GitHub ; **ne pas présumer qu'il peut écrire sur GitHub** si son intégration renvoie `403`.
- Aucun agent ne se déclenche automatiquement par le seul fait d'être mentionné : prévoir explicitement le lancement d'une revue ou une automatisation autorisée.

## Qualité et publication

- Exécuter `./gradlew --no-daemon :core:test` lorsque pertinent ; vérifier `:app:assembleDebug` et la CI Android pour les changements d'application.
- Ne pas assimiler 390 tests JVM verts à une validation Android/PS5 : les essais réels restent nécessaires.
- Ne fusionner aucune PR ni remplacer l'APK en cours de test sans l'accord explicite de Jeremy.
- Si `git push` fonctionne mais qu'une API GitHub retourne `403`, conserver les commits poussés, signaler le blocage précis et fournir la branche pour création de PR dans l'interface GitHub. Ne pas contourner l'authentification.
- À chaque livraison, donner branche, SHA, fichier(s) touché(s), tests/CI, ce qui reste à vérifier sur Fold/PS5, lien PR si confirmé, et décision attendue.

## Feuille de route actuelle (sous réserve des essais réels)

1. Tester et fiabiliser la dernière APK en course GT7 sur PS5 avec le Fold.
2. Étudier puis intégrer conjointement GPT et Claude via une architecture à rôles contrôlés ; **la logique du moteur Pitwall reste l'autorité**.
3. Revoir l'interface visuelle adaptée au Fold.
4. Améliorer la couche vocale en s'inspirant des usages de Cornelius, sans importer directement son code.

**Ne pas entreprendre les étapes suivantes avant la validation de Jeremy.**
