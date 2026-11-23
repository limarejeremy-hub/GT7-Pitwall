# GT7 Pitwall — contexte partagé pour Claude (lire avant toute analyse)

Ingénieur de course Android natif (Galaxy Fold) pour Gran Turismo 7 sur PS5 : télémétrie UDP, stratégie, radio Véga
(Discord ou haut-parleur). Le pilote développe depuis son Fold : aucun PC, 0 € de service supplémentaire.

## Branches (vérifier avant de partir d'une base)
- `main` n'est PAS l'application : commit initial + workflows CI seulement.
- L'application vit sur une pile de PR brouillons empilées (chacune a pour base la précédente). La plus récente
  connue : `claude/v511-reliability` (PR #86). Vérifier `git branch -r --sort=-committerdate` : une version plus
  récente peut exister.
- PR #64 (veille automatique #63) est hors pile.
- Toujours travailler sur une nouvelle branche `claude/<sujet>` partant de la dernière version, PR en brouillon,
  **jamais de fusion**, jamais de force-push.

## Architecture
- `core/` : Kotlin/JVM pur, testé localement. Décodage GT7 (`telemetry/`), session et tours (`session/`), moteur de
  course `race/RaceController.kt` (point d'entrée de tous les paquets), position (`race/PositionTracker.kt`),
  ingénieur et radio (`engineer/`, `radio/`, `ai/`), stratégie (`strategy/`, `tyres/`), coaching (`driver/`,
  `analysis/`), prototype GPT-Live simulé (`live/`).
- `app/` : Android Compose (UI `ui/`, moteur `engine/PitwallEngine.kt`, Discord `discord/`, voix `voice/`).
  Compilé seulement par la CI (pas de SDK Android dans les sessions cloud).
- `lab/` : Pitwall Lab (rejeu de traces, hors APK). `tools/pitwall-lab-mcp/` : serveur MCP du Lab.
- Docs de référence : `docs/V511_AUDIT.md` (état, matrice, P0-P2), `docs/UDP_FIELDS.md`, `docs/POSITION_RADIO.md`,
  `docs/DEV_ECOSYSTEM.md` (outils et agents).

## Commandes (sans SDK Android)
- Tests du cœur et du Lab : `./gradlew -p lab/standalone :core:test :lab:test`
- Lab : `./gradlew -p lab/standalone :lab:installDist` puis `lab/build/install/lab/bin/lab inspect|replay|list ...`
- Traces réelles : jamais dans le dépôt (`*.gt7trace` ignoré). Dossier local via `PITWALL_TRACES`, puis
  `PITWALL_TRACES=<dossier> ./gradlew -p lab/standalone :core:test --tests '*RealTraceReplay*'`.
- CI GitHub Actions (`.github/workflows/android.yml`) : tests, APK debug (artefact), captures d'écran Robolectric.
  Lien APK : `https://nightly.link/limarejeremy-hub/GT7-Pitwall/actions/artifacts/<id>.zip`.

## Règles non négociables
- **Aucune donnée inventée.** Toujours distinguer MESURE (paquet GT7), CALCUL (moteur), ESTIMATION et SIMULATION.
  Une estimation n'est jamais présentée comme une mesure. Rien n'est validé PS5/Fold depuis un test PC ou CI.
- Ne pas modifier la négociation UDP ni la voix par défaut sans demande explicite.
- Aucun secret (jeton Discord, clé API, OAuth, PSN) dans le code, les journaux, GitHub ou l'APK. Pas de collecte de
  données, pas de backend imposé, pas de service payant ni de facturation automatique.
- Traces et journaux du pilote : privés, jamais publiés ni commités.
- Ne pas copier de code sous licence incompatible (vérifier la licence des projets GT7 open source).
- Réponses au pilote en français, claires, avec résultats de tests et liens d'APK.
- Commits : trailers d'attribution de la session ; PR : brouillon, rapport en français.

## Outils connectés
- **Context7** (MCP) : documentation à jour des bibliothèques (Android, Compose, Kotlin, coroutines, MediaCodec…).
  L'utiliser avant d'écrire du code qui dépend d'une API de bibliothèque. Requêtes : questions génériques seulement,
  jamais de code privé, de trace ni de secret.
- **Parallel Search** (MCP) : recherche web et lecture de pages pour la veille et les docs non couvertes par Context7.
- **GitHub** (intégration Claude Code) : PR, CI, journaux de jobs.
- Serena : non installé (serveur de langage Kotlin non téléchargeable dans l'environnement actuel).
- **pitwall-lab** (MCP du dépôt, `.mcp.json`) : `lab_list_traces`, `lab_inspect`, `lab_replay` sur les traces locales.

## Économie de quota
- Lire ce fichier et le doc de référence du domaine AVANT de réexplorer le dépôt.
- Utiliser les agents spécialisés (`.claude/agents/`) seulement quand la tâche le demande ; leur donner un objectif
  précis et les fichiers concernés plutôt que « analyse tout ».
- Préférer `Grep` ciblé et la lecture de sections à la lecture de fichiers entiers (RaceController ≈ 4 500 lignes).
