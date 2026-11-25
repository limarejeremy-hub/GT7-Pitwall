# GT7 Pitwall — contexte partagé pour Claude (lire avant toute analyse)

Ingénieur de course Android natif (Galaxy Fold) pour Gran Turismo 7 sur PS5 : télémétrie UDP, stratégie, radio Véga
(Discord ou haut-parleur). Le pilote développe depuis son Fold : aucun PC, 0 € de service supplémentaire.

## Branches (vérifier avant de partir d'une base)
- `main` n'est PAS l'application : commit initial + workflows CI seulement.
- L'application vit sur une pile de PR brouillons empilées (chacune a pour base la précédente). La plus récente
  connue : `claude/v514-tyre-fuel-pit` (V514, diagnostic des 4 pneus, `docs/V514.md`), base `claude/v513-endurance-intelligence`
  (V513, PR #93, Lab et docs seulement), base `claude/v512-lot2-carburant-stands`
  (V512 lot 2, PR #92, dernière version du moteur et de l'APK ; lot 1 = PR #91 ; V511 = PR #86). Vérifier `git branch -r --sort=-committerdate` : une version plus
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
  `docs/DEV_ECOSYSTEM.md` (outils et agents), `docs/LE_MANS_2026.md` (Le Mans 14 tours), `docs/V512_LE_MANS.md` (analyse de la course du 23/11, lots V512), `docs/V512_LOT2.md` (carburant, stands, radio, pneus).

## Commandes (sans SDK Android)
- Tests du cœur et du Lab : `./gradlew -p lab/standalone :core:test :lab:test`
- Lab : `./gradlew -p lab/standalone :lab:installDist` puis `lab/build/install/lab/bin/lab inspect|replay|anomalies|list|gabarit ...` ;
  comparaison de deux versions du moteur : `lab/compare.sh <versionA> <versionB> <trace>` (mode d'emploi : `docs/PITWALL_LAB.md`) ;
  banc de Véga : `lab vega <trace>` et `LAB_MODE=vega lab/compare.sh …` (`docs/VEGA_GOLDEN.md`, scénarios dans
  `lab/vega-golden/scenarios.json` : ajouter un scénario à chaque défaut de radio constaté en course) ;
  course longue : `lab endurance <trace> lab/le-mans/regles-sport-c-2026-11-23.json` et `lab vega <trace> lab/vega-golden/le-mans.json`
  (`docs/LE_MANS_2026.md`) ; décision carburant sur un journal exporté : `lab carburant <journal.json>` (`docs/V512_LOT2.md`).
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
- **Nenkai/PDTools est exclu de toute recherche, analyse ou génération assistée par IA** (demande de son auteur,
  `AGENTS.md` du dépôt : lecture, indexation, analyse et génération interdites aux IA). Ne pas le lire, l'interroger
  (DeepWiki, Parallel, Playwright, Context7, GitHub) ni s'en inspirer pour un nouveau travail. Sources de remplacement :
  MacManley/gt7-udp et zetetos/gt-telemetry (MIT). Les citations historiques et leur attribution restent en place :
  ne pas les supprimer ni modifier du code pour effacer sa provenance (revue : `docs/PROVENANCE.md`).
- Réponses au pilote en français, claires, avec résultats de tests et liens d'APK.
- Commits : trailers d'attribution de la session ; PR : brouillon, rapport en français.

## Outils connectés
- **Context7** (MCP) : documentation à jour des bibliothèques (Android, Compose, Kotlin, coroutines, MediaCodec…).
  L'utiliser avant d'écrire du code qui dépend d'une API de bibliothèque. Requêtes : questions génériques seulement,
  jamais de code privé, de trace ni de secret.
- **Parallel Search** (MCP) : recherche web et lecture de pages pour la veille et les docs non couvertes par Context7.
- **GitHub** (intégration Claude Code) : PR, CI, journaux de jobs.
- **Serena** (MCP du dépôt, `.mcp.json`, projet `.serena/project.yml`, langage Kotlin) : symboles, définitions et
  références (`serena_repl` : `s.lsp.find_symbol`, `s.lsp.find_referencing_symbols`, `s.lsp.get_symbols_overview`).
  Le premier démarrage d'une nouvelle session télécharge le serveur Kotlin (~40 s) puis indexe (~50 s). Préférer
  Serena à la lecture de fichiers entiers pour naviguer dans `RaceController.kt`.
- **DeepWiki** (MCP du dépôt, `.mcp.json`, HTTP `mcp.deepwiki.com`, gratuit sans compte) : documentation générée des
  dépôts GitHub **publics** (projets GT7 open source : télémétrie, découpage des virages, analyse de relais). Questions
  sur ces dépôts publics seulement : jamais de code privé de Pitwall, de trace, de journal ni de secret. Vérifier la
  licence avant de reprendre une idée de code.
- **DuckDB** (MCP du dépôt, `.mcp.json`, `mcp-server-motherduck==1.1.1` en local, base en mémoire : rien ne
  persiste) : SQL sur des CSV/Parquet/JSON locaux (exports du Lab, journaux). Aucun compte MotherDuck, aucun jeton, rien
  n'est envoyé hors du conteneur.
- **pitwall-lab** (MCP du dépôt, `.mcp.json`, approuvé dans `.claude/settings.json`) : `lab_list_traces`, `lab_inspect`,
  `lab_replay`, `lab_anomalies`, `lab_vega`, `lab_compare` (mode `replay` ou `vega`) sur les traces locales. Avant de proposer un changement du moteur, le
  comparer à la version précédente sur les traces réelles disponibles (`lab_compare`).

## Connecteurs et agents : règle de chaque mission (demande du pilote, 24/11)
1. Au début : inventorier les connecteurs MCP, outils et sous-agents **chargés dans la session** et vérifier lesquels
   répondent vraiment (un serveur déclaré dans `.mcp.json` n'est chargé qu'au démarrage d'une session).
2. Utiliser chaque connecteur pertinent pour une contribution concrète ; croiser les sources quand c'est possible.
3. Un connecteur non pertinent, indisponible ou limité par son quota : le dire, avec la raison. **Aucune utilisation
   fictive.** Un serveur non chargé peut être utilisé par son accès direct (HTTP public, ligne de commande locale) en le
   signalant comme tel.
4. Chercher d'autres outils spécialisés seulement si leur valeur ajoutée est démontrable ; gratuit uniquement.
5. Répartition : GitHub (code, PR, CI), Serena (symboles Kotlin), Context7 (API), Parallel Search et Playwright (web,
   sources GT7), Pitwall Lab (traces, statistiques), Hugging Face (modèles, voix), DeepWiki (projets publics), DuckDB
   (SQL local), Google Drive (traces et documents privés, seulement si nécessaire), Maestro (tests Android si un
   émulateur existe), sous-agents (`.claude/agents/`).
6. **Compte rendu de fin de mission obligatoire** : tableau par connecteur — disponible, utilisé, action exécutée,
   résultat concret, limitation.
7. Jamais de trace privée envoyée à un service tiers non autorisé ; aucune dépense ; aucune fusion automatique.

## Économie de quota
- Lire ce fichier et le doc de référence du domaine AVANT de réexplorer le dépôt.
- Utiliser les agents spécialisés (`.claude/agents/`) seulement quand la tâche le demande ; leur donner un objectif
  précis et les fichiers concernés plutôt que « analyse tout ».
- Préférer `Grep` ciblé et la lecture de sections à la lecture de fichiers entiers (RaceController ≈ 4 500 lignes).
