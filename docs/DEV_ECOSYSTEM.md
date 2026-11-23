# Pitwall — écosystème IA de développement (état au 23/11/2026)

**Contraintes** :
- 0 € supplémentaire, aucun abonnement, aucune facturation automatique ;
- développement depuis le Galaxy Fold, sans PC ;
- aucun secret ni donnée privée transmis à un service non approuvé.

**Règle des statuts** : un outil n'est dit « opérationnel » qu'après un test réel, décrit ci-dessous.

## 1. Audit de l'existant (aucune modification applicative)

- **Branches** :
  - `main` ne contient que le commit initial et trois workflows CI (diagnostic OAuth Claude) ;
  - l'application vit sur une pile de PR brouillons ;
  - la dernière version est `claude/v511-reliability` (PR #86, CI verte #577) ; aucune version plus récente.
- **Architecture** : `core/` (Kotlin JVM pur, 946 tests JUnit 5), `app/` (Android Compose, tests Robolectric de
  captures), 14 modules fonctionnels (télémétrie, session, moteur de course, position, ingénieur, radio, IA, stratégie,
  pneus, coaching, Sport, connaissances, affichage, prototype GPT-Live simulé).
- **CI** : un seul workflow utile (`android.yml`). Il lance les tests `core`, compile DAVE, produit l'APK debug et les
  captures Robolectric. Le dépôt est public, donc les minutes GitHub Actions sont gratuites sur les runners standard.
- **IA déjà dans l'app** :
  - Gemini Nano (local) ;
  - Firebase AI (clé saisie par le pilote) ;
  - ChatGPT via le forfait du pilote (Sign in with ChatGPT, Responses API).
- **Voix déjà dans l'app** :
  - Vosk et sherpa-onnx (reconnaissance locale) ;
  - Google Speech en secours ;
  - TextToSpeech Android ;
  - banc de reconnaissance (`RecognitionBench`, taux d'erreur par mot) ;
  - mesure de latence (`LatencyTrace`).
- **Claude dans GitHub Actions (sur `main`)** : `claude-code-action` avec jeton OAuth d'abonnement.
  - Le diagnostic a échoué 10 fois le 23/10 (authentification refusée, 401 probable).
  - Le test « smoke » n'a jamais tourné (conditions non remplies) et vise une ancienne branche
    (`claude/gt7-ia-conversation`).
  - **Non opérationnel.**
- **Doublons et incompatibilités** :
  - trois IA cloud dans l'app (Firebase AI, ChatGPT, Gemini Nano) : voulu (repli), mais à surveiller ;
  - Crashlytics est incompatible avec la règle « zéro collecte ».

## 2. Connecteurs : état réel

### Déjà connectés et testés dans cette session

| Connecteur | Test effectué | Résultat | Intérêt Pitwall |
|---|---|---|---|
| GitHub (intégration Claude Code) | lecture des PR, journaux d'un job CI échoué | opérationnel | PR, CI, issues, revue |
| Gmail, Google Calendar, Google Drive, Google Sheets | listés par `ListConnectors` (connectés) | connectés, non testés ici | Drive : dépôt de traces et journaux (privé) ; Sheets : suivi de validation PS5 |
| Microsoft 365 | — | connexion inachevée | aucun |

### Recherchés (classés par intérêt, coût, facilité)

| Outil | MCP réel | Coût et quotas | Compatible Fold / Claude Code cloud | Intérêt | Risques, doublons | Verdict |
|---|---|---|---|---|---|---|
| **Pitwall Lab (maison)** | oui (stdio, ce dépôt) | 0 € | sessions Claude Code cloud | **très élevé** : rejeu réel, archives, messages | traces privées, jamais commitées | **construit et testé** |
| **Context7** | oui, distant (`mcp.context7.com/mcp`), dans l'annuaire claude.ai | gratuit (clé gratuite = quotas plus hauts ; chiffres non publiés) | oui, via l'annuaire claude.ai (mobile et Claude Code) | élevé : docs à jour Kotlin, Compose, Coroutines, MediaCodec | faible (lecture de docs publiques) | **à connecter par toi** |
| **Exa** | oui, distant (`mcp.exa.ai/mcp`), dans l'annuaire claude.ai | offre gratuite (sources contradictoires sur le volume) | oui, via l'annuaire | moyen : recherche de code et veille ; recoupe la recherche web intégrée | faible | optionnel |
| **Parallel Search** | oui, dans l'annuaire, sans compte | gratuit | oui | moyen : doublon d'Exa | faible | optionnel (un seul des deux) |
| **Figma MCP** | oui, distant, dans l'annuaire | Starter : 6 à 20 appels **par mois** (docs contradictoires) ; utile seulement en payant | oui | faible à 0 € | quota quasi nul | non retenu |
| **Google Stitch** | MCP communautaires (non officiels) ; export HTML/CSS, pas Compose | gratuit (Labs) | partiel | faible : les captures Robolectric de la CI couvrent déjà le contrôle visuel | serveurs tiers | non retenu |
| **ElevenLabs** | oui (annuaire : gestion d'agents vocaux) | 10 000 crédits/mois (≈ 10 min), usage non commercial avec attribution | oui | faible : trop peu pour la radio, envoie le texte à un tiers | doublon du TTS local | non retenu |
| **Voix locale Android (sherpa-onnx : Piper, Kokoro)** | pas de MCP (bibliothèque déjà dans l'app) | 0 €, hors ligne | oui (dans l'APK) | **élevé** pour une voix plus naturelle sans cloud | taille des modèles | piste V512+ (hors périmètre infra) |
| **Serena** | oui (stdio local, `uvx`) | gratuit, open source | possible dans le conteneur cloud (PyPI autorisé) ; serveur de langage Kotlin encore jeune | moyen : navigation sémantique sur un gros dépôt | téléchargement du serveur de langage ; doublon partiel de Grep/Read | à essayer plus tard |
| **Maestro** | oui (`maestro mcp`, dans la CLI) | gratuit en local ; Maestro Cloud ≈ 250 $/appareil/mois | pas sur le Fold sans PC ; **possible dans GitHub Actions** (émulateur, minutes gratuites en dépôt public) | élevé pour les tests d'interface | aucun si en CI | feuille de route (CI) |
| **Google ARTEMIS** | oui (MCP, agent Android en langage naturel) | open source, gratuit | exige un appareil ou un émulateur par ADB : pas depuis le Fold seul | moyen | litige d'attribution signalé (non vérifié) ; doublon de Maestro | non retenu (Maestro d'abord) |
| **Firebase Crashlytics (MCP Firebase)** | oui (MCP Firebase local, outils Crashlytics) | Crashlytics gratuit | serveur local (npx) | moyen | **envoie des rapports de plantage à Google : contraire à « zéro collecte »** ; modifie l'app | refusé sans décision explicite |

Sources :
- Context7 : https://github.com/upstash/context7, https://mcpservers.org/remote-mcp-servers/context7
- Exa : https://exa.ai/docs/reference/exa-mcp.md
- Figma : https://developers.figma.com/docs/figma-mcp-server/rate-limits-access/
- ElevenLabs : https://elevenlabs.io/docs/overview/administration/billing.mdx
- Maestro : https://maestro.dev/pricing
- ARTEMIS : https://raw.githubusercontent.com/google/artemis/main/README.md
- Firebase : https://firebase.google.com/docs/crashlytics/ai-assistance-mcp
- Serena : https://github.com/oraios/Serena
- Stitch : https://www.mindstudio.ai/blog/google-stitch-mcp-coding-agents/

**Réseau de l'environnement cloud.** Les serveurs distants Context7 et Exa sont **bloqués** en accès direct depuis le
conteneur (liste d'hôtes restreinte, test HTTP sans réponse). La voie qui fonctionne est l'annuaire de connecteurs
claude.ai : ses appels passent par le relais MCP d'Anthropic, autorisé, et le connecteur est alors disponible sur
mobile et dans Claude Code.

## 3. Ce qui a été configuré et testé (branche `claude/dev-ecosystem`)

| Élément | Statut | Test |
|---|---|---|
| `CLAUDE.md` (contexte partagé : branches, architecture, commandes, règles, économie de quota) | configuré | lu automatiquement par Claude Code à chaque session ; effet sur les quotas non mesurable directement |
| `.claude/agents/` : architecte, ingénieur GT7, stratège, ingénieur vocal, designer, qualité, chercheur | configurés, pas encore validés | fichiers au format des sous-agents Claude Code ; à utiliser dans la prochaine session |
| **Pitwall Lab v0** (`lab/`) : `inspect`, `replay`, `list`, sortie JSON étiquetée MESURE / CALCUL | **opérationnel et testé** | 2 tests JVM verts ; sur les vraies traces : 22/11 → ligne P12/16 (MESURE), archivé P13/16 consolidé (CALCUL), révision à 567 ms ; 19/11 → 74 556 échantillons C, 3 trous |
| Compilation autonome du Lab sans SDK Android (`./gradlew -p lab/standalone …`) | **opérationnel et testé** | wrapper Gradle du dépôt, conteneur cloud |
| **Serveur MCP `pitwall-lab`** (`tools/pitwall-lab-mcp/server.py`, Python standard, sans dépendance) + `.mcp.json` | **testé au niveau du protocole**, pas encore dans une session Claude Code | JSON-RPC : initialize, tools/list (3 outils), tools/call `lab_replay` sur la trace du 22/11 → P12/16 puis P13/16 ; chemin hors `.gt7trace` refusé |
| CI : étape « Tests Pitwall Lab » | configurée, validée par la CI de la PR | — |
| `.gitignore` : `*.gt7trace` | configuré | empêche de commiter une trace privée |

## 4. Pitwall Lab : proposition

**v0 (livrée)** :
- lecture des traces réelles (`.gt7trace`) ;
- mesures brutes : échantillons, formats, trous, pertes UDP, transitions 0x84/0x86, carburant ;
- rejeu dans le vrai `RaceController` : sessions archivées, classement à la ligne et consolidé, révisions, messages
  radio produits, compteurs de résumés.

Chaque valeur porte sa nature, MESURE ou CALCUL. Rien n'est simulé en v0.

**Étapes suivantes (sans données inventées)** :
1. `compare` : rejouer la même trace avec deux versions du moteur (deux branches) et différencier archives et
   messages. C'est la non-régression sur données réelles.
2. `fuel-scenarios` : stratégies carburant et arrêts **étiquetées SIMULATION**, à partir des consommations MESURÉES
   d'une trace (variation du nombre de tours, du multiplicateur, de la capacité).
3. `vega-golden` : questions radio rejouées sur l'état d'une trace (réponses locales déterministes), comparées entre
   versions.
4. `anomalies` : trous, pertes UDP, sauts de compteur, 0x84 incohérents, valeurs figées, avec horodatage et numéro de
   paquet.
5. Rapport Markdown et HTML lisible sur le Fold (artefact de session ou de CI).

**Sources d'inspiration** : projets GT7 audités le 15/11 (gt7-datalogger MIT, gt7telemtrace MIT, gt7dashboard GPL-3
à ne pas copier). Le Lab réutilise d'abord le décodeur et le moteur de Pitwall.

## 5. Agents spécialisés : organisation

- **Sous-agents Claude Code** à la demande (`.claude/agents/`), aucun actif en permanence.
- **Contexte partagé** par `CLAUDE.md` et les documents de référence du domaine. Chaque agent les lit avant d'explorer,
  ce qui évite de refaire l'audit à chaque tâche.
- **Coordination** : la session principale (l'architecte) découpe la tâche, appelle un ou deux spécialistes avec un
  objectif et des fichiers précis, puis le responsable qualité vérifie avant la PR.
- **Quota** :
  - pas d'agents en parallèle sans besoin ;
  - le chercheur tourne sur un modèle plus léger ;
  - des livrables courts (constats et fichier:ligne) plutôt que des rapports complets répétés.

## 6. Automatisation adaptée au Fold (0 €, sans PC)

**Boucle déjà possible aujourd'hui (Claude Code sur le web ou l'app, depuis le Fold)** :
1. Tu décris le problème, ou tu joins une trace et un journal.
2. Claude analyse : Lab, agents, tests.
3. Il développe sur une branche `claude/...` isolée.
4. Il lance les tests (core, Lab, rejeu réel).
5. Il pousse : la CI GitHub produit l'APK candidate.
6. Il te donne un rapport en français et le lien de l'APK.
7. Tu valides sur PS5 et Fold.
8. Fusion seulement avec ton accord.

**Protections en place** :
- PR en brouillon ;
- pas de fusion, pas de force-push ;
- branches empilées ;
- CI obligatoire avant le lien d'APK ;
- traces exclues du dépôt.

**Option : Claude dans GitHub Actions** (tâches déclenchées par issue). Le jeton OAuth actuel est refusé. Le réparer
demande de générer un nouveau jeton avec `claude setup-token`, une commande de terminal (difficile sans PC), et de
mettre à jour le workflow. **Non prioritaire** : les sessions Claude Code cloud font déjà la même chose sans secret
dans le dépôt.

**Option : tâches planifiées Claude Code** (veille hebdomadaire, vérification des CI). Gratuites en argent, mais elles
consomment ton quota Claude. Elles ne seront créées qu'avec ton accord.

## 7. Feuille de route priorisée

| Priorité | Action | Qui | Coût |
|---|---|---|---|
| 1 | Connecter **Context7** sur claude.ai (Paramètres → Connecteurs → Context7) | toi | 0 € |
| 2 | Utiliser Pitwall Lab sur chaque nouvelle trace (dépôt dans la session ou Drive) | Claude | 0 € |
| 3 | Lab `compare` et `anomalies` (non-régression sur traces réelles) | Claude | 0 € |
| 4 | Tests d'interface Maestro sur émulateur dans GitHub Actions (dépôt public) | Claude, puis ton accord | 0 € (minutes Actions publiques) |
| 5 | Lab `fuel-scenarios` (SIMULATION étiquetée) et `vega-golden` | Claude | 0 € |
| 6 | Exa **ou** Parallel Search, si la recherche web intégrée ne suffit pas | toi | 0 € |
| 7 | Essai de Serena dans une session cloud | Claude | 0 € |
| 8 | Voix locale plus naturelle (sherpa-onnx Piper ou Kokoro), si tu le demandes | V512+ | 0 € |
| — | Décisions à prendre : Crashlytics (contraire à « zéro collecte »), relais GPT-Live (payant), workflows Claude de `main` (à réparer ou supprimer) | toi | — |
