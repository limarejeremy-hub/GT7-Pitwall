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
- `ToolSnapshot` : cliché figé **sous le verrou de la course** au moment de la question, construit à partir de
  `RaceController.aiFacts()` (aucun calcul refait, aucune source dupliquée, aucun flux 60 Hz envoyé). Périmé après
  60 s : plus aucune donnée servie.
- **Provenance fixée à la source (V491, revue #71)** : `aiFacts()` étiquette chaque phrase au moment où il la produit
  (`TaggedFact` : sujet, provenance, source, fiabilité). Plus aucune déduction d'après le texte. Une phrase non
  étiquetée est « non classée, provenance inconnue » et n'est servie par aucun outil ; « mesuré » n'est jamais un
  défaut. Les contradictions viennent du statut `CONFLICT` des champs du programme Sport, pas de mots-clés.
  Confiance « élevée » seulement si tout est mesuré en direct ; « faible » dès qu'une provenance est inconnue ou qu'il
  y a contradiction. (Corrigé au passage : 6 phrases de pilotage partaient vers l'IA précédées de « null : ».)
- Contrat de chaque réponse : `observed_at_ms`, `telemetry_live`, `lap`, `confidence`, `facts[{text, provenance}]`
  (mesuré / calculé / publié / déclaré), `missing` (dont ce que GT7 ne transmet pas), `conflicts`.
- `ToolLoop` : 3 allers-retours et 6 appels au plus ; outil inconnu → erreur, jamais une donnée ; dépassement → échec →
  **réponse locale**. Refus des outils par le serveur (400) → une reprise **sans** outils.
- Contrôle : les chiffres de la réponse doivent venir des faits de la question **ou des faits réellement rendus par les
  outils** (`AiResult.Text.evidence`) ; mêmes règles qu'avant (autres voitures, conseils non prouvés, commandes).
- App : réglage « Véga consulte les données Pitwall » (désactivé par défaut) et bouton **« Tester les outils de Véga »** :
  la question carburant SANS les faits, qui ne peuvent venir que des outils (simulation). La carte dit quels outils GPT a
  appelés et si la réponse est vérifiée. Si le serveur refuse les outils : « MODE OUTILS NON PRIS EN CHARGE (pas une
  validation) », sans réponse de repli présentée comme un succès.

## Pas encore fait (étapes suivantes de #70)

- Nouveaux outils au-delà des faits actuels : comparaison par virage/secteur, débrief, événements Sport A/B/C avec
  sources web (réutiliser `SportScheduleRepository`, respecter licences et conditions des sites).
- Questions plus courtes quand les outils sont actifs (aujourd'hui la question garde tous les faits : les outils
  servent surtout la provenance et l'essai).
- Mesures de latence p50/p95 et coût en requêtes.

## Vérifié / non vérifié

- Vérifié sur PC (`VegaToolsTest`, 17 tests, transport simulé) : découpage et provenance, format namespace, boucle,
  historique renvoyé sans `previous_response_id`, outil halluciné, boucle infinie coupée, refus serveur, lecture des
  appels dans le flux, outils seulement si activés, chiffre absent rejeté, repli local.
- **Non vérifié** : acceptation réelle des outils par le forfait sur le Fold, comportement de `gpt-6.1-sol`, latence.

## V492 : analyses locales, fiche Sport, latence (#70 / #68, après la validation sur le Fold du 06/11)

Validé par le pilote sur le Fold (V491) : connexion Plus, `gpt-6.1-sol`, **un vrai appel `get_fuel_strategy`**, réponse
vérifiée sur la simulation, 8 230 ms ; « Écouter Véga » audible avec la voix Android habituelle (inchangée).

### Nouveaux outils (lecture seule, 10 au total)

| Outil | Moteur local lu (rien n'est recalculé) | Ce qui est dit quand ça manque |
|---|---|---|
| `get_corner_comparison` | `CornerReport` (passages du même virage comparés au meilleur), `TyreOrDriving`, motricité | virages à moins de 2 passages : non chiffrés ; secteurs officiels et adversaires : non transmis |
| `get_driver_debrief` | `RaceReview.lines()` (dernier bilan), constats de pilotage | aucun bilan enregistré ; adversaires non transmis |
| `get_start_tyre_advice` | `StartTyreAdvisor.Advice` déjà calculé (jamais relancé par l'outil) | aucun conseil calculé ; « options proches » ≠ recommandation |
| `get_current_sport_events` | programme Sport **en cache** (`ScheduleState` : GTPlanet, Victory, DG EDGE, GridStats) — aucune requête web | champs inconnus, édition hors semaine (heure de Paris), **deux gommes imposées sans arrêt explicite**, contradictions (statut `CONFLICT` → aucune valeur) |

Chaque fait porte provenance, source et fiabilité (« confiance élevée, 6 passages », « édition en cours », statut du champ).

### Latence

- **Question allégée** quand les outils sont actifs (`EngineerPrompt.buildForTools`) : les faits ne sont plus recopiés
  dans la question ET dans les outils ; restent le contexte, le calcul du moteur (qui fait foi), la mémoire et 3 échanges.
  Le contrôle des chiffres garde tous les faits et ceux rendus par les outils.
- Mesures par aller-retour (`ToolLoop.Round` : durée, 1er octet, taille de la requête, appels) et p50/p95 sur le
  téléphone (`LatencyStats`, en mémoire seulement), affichées après chaque essai.
- Les alertes critiques (« box ») restent locales et immédiates : aucun outil ni appel réseau sur ce chemin.

### Essai sans PS5

« Véga : quelle course Sport cette semaine ? » interroge GPT sur le **programme réellement en cache** dans Pitwall.

### Exemples reproductibles (`VegaAnalysesTest`, fixtures SIMULÉES)

1. Virage : « pourquoi je perds du temps au virage 5 » → T5 0,60 s, freinage trop tardif ; V9 (1 passage) jamais chiffré.
2. Gomme de départ : conseil du moteur repris, « options proches » dit comme tel, absence de conseil dite.
3. Fiche Sport : C en cours 11 tours ×3/×5 ; B « tours » contradictoire sans valeur ; A hors semaine ; gommes imposées
   sans arrêt explicite signalées.
4. Débrief : bilan de course repris, aucun adversaire inventé.

**Non vérifié** : ces outils sur le Fold, la latence après allègement, la radio en course.

## V493 : sens des règles Sport (revue #72, essai Fold du 06/11)

Essai réel V492 : `get_current_sport_events` appelé une fois, semaine 02/11–09/11 citée, 7 997 ms (2 allers-retours).
**Faute relevée** : « C … RH et RM autorisés » alors que GTPlanet et DG EDGE donnent RH **et** RM **obligatoires**.

- La fiche Sport dit « pneus OBLIGATOIRES » (plus « imposés ») et, si au moins deux gommes sont obligatoires, une
  phrase explicite : « il faut utiliser chacune d'elles : au moins un passage aux stands pour changer de gomme, même si
  aucun arrêt obligatoire n'est écrit ».
- Obligation inconnue ou contradictoire → « obligation de gommes NON VÉRIFIÉE : ne rien conclure ».
- Chaque champ est toujours affiché : valeur [statut], « inconnu », ou « contradictoire entre sources (non appliqué) ».
  Un circuit absent du cache est dit « inconnu », jamais deviné (le circuit C manquait dans la réponse).
- **Contrôle du sens** (`SportSemantics`), en plus du contrôle des chiffres, à la radio comme dans l'essai : une
  réponse qui présente des gommes obligatoires comme seulement « autorisées », ou qui affirme une obligation non
  vérifiée, est écartée → réponse locale (qui dit l'obligation).
- Semaine ancienne ou programme périmé : « SEMAINE ANCIENNE, ne vaut pas pour aujourd'hui » ; jamais repris comme la
  course du jour (test au 09/11 : l'édition du 02/11 est ancienne).
- La fiche dit « actualisé par le téléphone le … à …h… (cache local, pas une recherche web) ».
- p50/p95 : « trop peu de mesures pour un p95 fiable » sous 10 mesures.
