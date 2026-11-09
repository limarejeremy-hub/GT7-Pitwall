# Radio : chaîne analyse → GPT → outils → réponse (V504, consigne du 09/11, point 3)

## Ce qui est prouvé, et comment

`RadioChainProofTest` rejoue une course simulée (Sport, 11 tours, mediums, 4 tours bouclés). Il remplace GPT par un
**fournisseur simulé** dont le transport Responses est scripté. La **vraie boucle d'outils** (`ToolLoop`) et les **vrais
outils en lecture seule** (`PitwallDataTools`) tournent sur le cliché du moteur. Pour chaque question :

| Phrase | Route | Outils demandés |
|---|---|---|
| « pourquoi je perds du temps dans ce virage ? » | GPT | `get_corner_comparison`, `get_recent_laps` |
| « était-il préférable de partir en durs ? » | GPT | `get_tyre_strategy`, `get_start_tyre_advice` |
| « vaut-il mieux faire un arrêt de plus ou garder la stratégie ? » | GPT | `get_fuel_strategy`, `get_live_race_state` |
| « carburant » | locale instantanée (pas d'attente GPT) | aucun |

Vérifié à chaque fois :
- chaque outil rend des faits **avec provenance** et ses **données manquantes** explicites (`missing`) ;
- **aucune mutation** : règles, gomme de départ, changements de pneus, arrêts, arrêt en attente et plan sont identiques
  avant et après, et aucune commande n'est appliquée ;
- **journal** (« Route radio : … ») : phrase, mode configuré, intention, route, fournisseur effectif, **outils appelés**
  et **délai** (`outils`, `delai_ms`). Aucun secret : ni jeton, ni en-tête d'autorisation, ni adresse réseau.

## Corrections trouvées par ce test

- **Comparaisons qui n'atteignaient pas GPT.** Avec deux transcriptions identiques, « était-il préférable de partir en
  durs ? » et « vaut-il mieux faire un arrêt de plus… » partaient vers les chemins locaux : l'arbitre ne basculait en
  analyse que si une commande sensible était lue. En mode « ChatGPT via ton forfait », une comparaison ou une demande
  d'avis (au moins 4 mots) va désormais à GPT avec ses outils, sauf si une modification y est lue (elle garde le garde
  et le « oui »). Le mode local est inchangé.
- **Commande proposée par GPT sur une question.** Elle n'est ni appliquée ni proposée au pilote (pas de « Tu
  confirmes… ? »). Le moteur local répond, et la trace dit « commande de l'IA écartée (question d'analyse) ».
- **Outils et délai visibles.** `AiResult.Text.tools` → `AiRouter.Outcome.tools` → `AiTrace.tools` → `RouteTrace`
  (affiché sur la carte de l'IA et journalisé).

Les alertes urgentes (box, carburant critique) viennent du moteur, sans passer par la radio conversationnelle : elles
restent locales.

## Ce qui N'est PAS prouvé

Ni réseau, ni GPT réel, ni PS5, ni Discord. GPT n'est pas « validé en course » tant qu'un journal de course réel ne
montre pas un appel avec outils sur une question d'analyse (ligne « Route radio : … · réponse gpt-… · outils … · … ms »).
À faire avec le pilote : mode « ChatGPT via ton forfait » choisi (la connexion seule ne le choisit pas), poser ces trois
questions en course, puis exporter le journal.
