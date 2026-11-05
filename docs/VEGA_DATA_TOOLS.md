# Véga : outils de données en lecture seule (issue #70, étape 2)

Prototype **opt-in** (désactivé par défaut), **non validé sur le Fold**. Base : V489 (PR #69).

## Ce que dit la documentation officielle (lue le 05/11/2026)

- « Sign in with ChatGPT », *Preview limitations* : outils personnalisés acceptés **groupés en namespace** (ou via
  `additional_tools`) ; recherche web soumise aux droits du modèle et du compte ; **non pris en charge** : file search,
  Code Interpreter, MCP/connecteurs hébergés, `tool_search`, génération d'images. Pas de `previous_response_id` :
  l'historique est renvoyé dans `input`.
- *Function calling* : format `{"type":"namespace","name":…,"tools":[{"type":"function",…}]}` ; appels reçus en
  `response.output_item.done` (élément `function_call`, `call_id`), résultats renvoyés en `function_call_output`.

Rien ne donne à GPT un accès automatique au téléphone ni à Internet : c'est Pitwall qui répond aux appels.

## Ce qui est codé

- `PitwallDataTools` (core) : 6 outils, tous `get_*`, **aucun outil d'écriture** (règlement, journal, plan et réglages
  restent hors d'atteinte ; une commande passe toujours par le moteur et son garde).
  `get_live_race_state`, `get_race_rules_and_sources`, `get_fuel_strategy`, `get_tyre_strategy`, `get_recent_laps`,
  `get_session_history`.
- `ToolSnapshot` : cliché figé **sous le verrou de la course** au moment de la question, construit à partir des phrases
  de `RaceController.aiFacts()` (aucun calcul refait, aucune source dupliquée, aucun flux 60 Hz envoyé).
- Contrat de chaque réponse : `observed_at_ms`, `telemetry_live`, `lap`, `confidence`, `facts[{text, provenance}]`
  (mesuré / calculé / publié / déclaré), `missing` (dont ce que GT7 ne transmet pas), `conflicts`.
- `ToolLoop` : 3 allers-retours et 6 appels au plus ; outil inconnu → erreur, jamais une donnée ; dépassement → échec →
  **réponse locale**. Refus des outils par le serveur (400) → une reprise **sans** outils.
- Contrôle : les chiffres de la réponse doivent venir des faits de la question **ou des faits réellement rendus par les
  outils** (`AiResult.Text.evidence`) ; mêmes règles qu'avant (autres voitures, conseils non prouvés, commandes).
- App : réglage « Véga consulte les données Pitwall » (désactivé par défaut) et bouton **« Tester les outils de Véga »** :
  la question carburant SANS les faits, qui ne peuvent venir que des outils (simulation). La carte dit quels outils GPT a
  appelés et si la réponse est vérifiée.

## Pas encore fait (étapes suivantes de #70)

- Nouveaux outils au-delà des faits actuels : comparaison par virage/secteur, débrief, événements Sport A/B/C avec
  sources web (réutiliser `SportScheduleRepository`, respecter licences et conditions des sites).
- Questions plus courtes quand les outils sont actifs (aujourd'hui la question garde tous les faits : les outils
  servent surtout la provenance et l'essai).
- Mesures de latence p50/p95 et coût en requêtes.

## Vérifié / non vérifié

- Vérifié sur PC (`VegaToolsTest`, 12 tests, transport simulé) : découpage et provenance, format namespace, boucle,
  historique renvoyé sans `previous_response_id`, outil halluciné, boucle infinie coupée, refus serveur, lecture des
  appels dans le flux, outils seulement si activés, chiffre absent rejeté, repli local.
- **Non vérifié** : acceptation réelle des outils par le forfait sur le Fold, comportement de `gpt-6.1-sol`, latence.
