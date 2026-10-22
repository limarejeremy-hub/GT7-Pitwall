# GT7 Pitwall — règles communes des agents

## Mission et source de vérité

GT7 Pitwall est un ingénieur de course Android pour Gran Turismo 7 sur PS5, conçu pour un Samsung Galaxy Fold. Le téléphone reçoit directement la télémétrie GT7 sur le réseau local ; aucun PC ni SimHub n'est nécessaire. **Jeremy décide des priorités, valide les essais réels et autorise les fusions.**

Ce fichier régit les contributions de Codex, Claude Code et de tout autre agent. Les **issues, pull requests, commits et résultats CI GitHub** sont le dossier partagé du projet. Une discussion privée dans un assistant n'est pas un état de projet partagé tant qu'elle n'est pas résumée sur GitHub.

## Architecture existante

- `core/` : Kotlin/JVM sans dépendance à Android ; décodage télémétrique GT7, analyse des tours, calculs de carburant, pneus, stands, règlement, journal de course, garde des commandes et logique de conversation IA.
- `app/` : application Android native (Jetpack Compose) pour Fold plié/déplié ; orchestration `PitwallEngine`, services, stockage, microphone/reconnaissance, synthèse vocale, Gemini Nano/cloud et prototype Discord.
- `.github/workflows/android.yml` : exécute `:core:test`, compile `:app:assembleDebug` et publie un APK de test.
- `docs/` : protocoles et constats expérimentaux. Lire les documents pertinents avant de modifier le comportement.

Ne pas reconstruire le projet à partir de `main` sans avoir vérifié son contenu : au 22 octobre 2026, `main` ne contient que le README, tandis que la version de travail complète est `claude/gt7-ia-conversation` (référence de départ `7e50132`). **Toujours vérifier `git branch --show-current`, `git status` et le dernier commit avant de modifier quoi que ce soit.** Mettre à jour cette référence dans les issues quand une autre branche de référence est validée.

## Invariants produit (non négociables)

1. **GT7 et le moteur `core` font autorité** sur les mesures et les calculs. L'IA peut expliquer, comparer et suggérer ; elle ne fabrique pas de chiffres, ne modifie pas silencieusement la course et ne bloque pas la réception UDP ni les alertes urgentes.
2. **Distinguer les sources** : valeur mesurée par GT7, calculée, déclarée par le pilote, hypothèse ou donnée indisponible. Une hypothèse n'est jamais présentée comme un fait.
3. **Écarts/adversaires** : aucune source d'écarts fiables n'a été validée. Ne jamais annoncer de gap, d'écart ou de position adversaire non prouvés ; documenter la fraîcheur et la fiabilité de toute nouvelle source avant de l'exploiter.
4. **Pneus/météo** : ne pas prétendre mesurer directement l'usure, le type de gomme monté ou la météo si ces informations ne sont pas fournies par un flux GT7 vérifié. Conserver les déclarations du pilote avec leur provenance et leur degré de certitude.
5. **Stratégie/stands** : un passage aux stands ne doit pas créer deux ravitaillements ; les tours de ravitaillement et de sortie des stands ne doivent pas fausser les moyennes. Préserver le classement final et ne pas rouvrir de sessions fantômes après l'arrivée.
6. **Radio** : réponses brèves, utiles et en français ; les confirmations de commandes potentiellement sensibles passent par le garde existant. Ne pas réintroduire le « Je t'écoute » répétitif. Pour les futures améliorations vocales, s'inspirer du confort de Cornelius (bips courts, enchaînement naturel, silence, interruption) **sans copier ni intégrer directement son code**.
7. **Priorités** : fiabiliser et tester réellement l'APK sur PS5/Fold avant d'ajouter l'orchestration GPT + Claude ; revoir le visuel ensuite ; enrichir la couche vocale ensuite, sauf priorité explicitement révisée par Jeremy.

## Collaboration GitHub

1. **Une issue par sujet** : contexte réel, reproduction, sources (journal expurgé ou capture), comportement attendu, critères d'acceptation, limites et niveau de preuve.
2. **Une branche par agent et tâche** (`codex/<sujet>` ou `claude/<sujet>`), créée depuis la branche de base explicitement indiquée dans l'issue. Ne pas travailler à deux sur une même branche ; ne jamais pousser directement sur `main` ni sur la branche de test actuelle de Jeremy.
3. **Une PR ciblée** vers la bonne branche de base ; indiquer l'issue liée, les fichiers modifiés, les tests réellement exécutés, les risques, et ce qui exige encore un essai sur PS5/Fold. Une compilation ne remplace pas un essai de course.
4. **Revue croisée** : l'agent auteur publie le code ; l'autre agent peut examiner la PR et commenter précisément ; l'auteur répond et corrige sur sa propre branche. Réutiliser les commentaires GitHub comme trace écrite, pas les copier-coller de conversations privées.
5. **Pas de fusion automatique** : attendre l'accord explicite de Jeremy. Les agents ne s'observent pas et ne se déclenchent pas mutuellement sans une tâche ou une automatisation GitHub configurée.
6. **Permissions** : si les API GitHub renvoient `403` mais que `git push` est permis, signaler précisément l'étape bloquée. Utiliser une création de PR manuelle via l'interface GitHub si nécessaire ; ne pas contourner les droits avec un jeton personnel ou des secrets partagés.

## Vérification avant PR

- Au minimum, exécuter `./gradlew --no-daemon :core:test` après les modifications du moteur ; noter JDK, nombre de tests et résultat (la CI utilise Java 17 ; un essai local avec Java 21 ne prouve pas la compatibilité CI).
- Pour les changements Android, exécuter si possible `./gradlew --no-daemon :app:assembleDebug` et contrôler le workflow GitHub Actions ; ne pas déclarer un APK vérifié si seule la compilation est passée.
- Ajouter des tests de non-régression portant sur les symptômes réels. Ne pas modifier des calculs pour « faire passer les tests » sans vérifier la logique métier.
- Les tests `core` et le build Android ne valident ni l'interface sur Fold, ni le micro/les bips/TTS/Discord, ni la connexion PS5 ; les documenter comme **à tester sur appareil**.
- Pour une PR uniquement documentaire : vérifier son diff, ne modifier ni Kotlin, ni Gradle, ni l'APK, et ne pas prétendre avoir réalisé un essai matériel.

## Sécurité et confidentialité

Ne jamais placer de clés API, jetons Firebase/App Check, secrets Discord, identifiants privés ou données personnelles dans le dépôt, les issues, les PR, les journaux ou les captures. Masquer les données sensibles avant d'ajouter un export de course. Ne pas créer de nouvelle dépendance réseau ni de collecte de télémétrie sans justification et validation de Jeremy.

## Compte rendu attendu d'un agent

Toujours conclure par : branche/commit, changements exacts, tests exécutés avec résultats, limites vérifiables, lien GitHub quand disponible, blocages et décision attendue de Jeremy. **Ne jamais annoncer une PR, un push ou un test comme réussi sans retour d'outil qui le confirme.**
