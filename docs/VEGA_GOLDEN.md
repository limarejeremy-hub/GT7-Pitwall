# Pitwall Lab — vega-golden : banc d'évaluation de Véga

## Principe

- La trace GT7 réelle est rejouée dans le **vrai moteur**, jusqu'au point de chaque scénario :
  - `tour:N` : 5 s après le début du tour N ;
  - `arrivee+X` : X ms après la ligne d'arrivée.
- Les questions sont ensuite posées, dans l'ordre, à la vraie conversation radio (`RadioConversation`), comme des
  transcriptions reçues.
- Chaque scénario a son propre rejeu : aucun contexte ne passe d'un scénario à l'autre. Dans un même scénario, les
  questions s'enchaînent pour tester le contexte conversationnel.

**Aucun modèle cloud n'est appelé.** Le banc n'a ni GPT ni Gemini : toutes les réponses viennent du moteur déterministe
de Pitwall. Les questions qui iraient à l'IA en course reçoivent ici la réponse locale de repli. Une réponse marquée IA
serait signalée, mais **ce banc ne valide jamais un modèle cloud**.

## Vérifications (définies dans `lab/vega-golden/scenarios.json`)

| Vérification | Contrôle |
|---|---|
| `etat_inchange` | Plan, arrêts, pneus, règlement et place déclarée identiques avant et après les questions. Présente dans **tous** les scénarios, c'est la sécurité des commandes vocales |
| `nombre_proche` | Un nombre (ou un chrono) de la réponse correspond au fait de référence, avec une tolérance |
| `contient_un_de` / `ne_contient_pas` | Termes attendus (pertinence) ou interdits (invention, faux dépassement, « gagnable ») |
| `max_nombre_motif` | Aucune valeur au-delà d'un plafond (ex. perte annoncée dans un virage > 1,5 s) |
| `non_vide` / `toutes_non_vides` | Aucune réponse silencieuse |

**Faits de référence au point du scénario** :
- MESURE : tours restants, carburant (niveau GT7), position (0x84 brut cohérent), dernier temps (0x7C) ;
- MESURE par différence de niveaux : consommation par tour ;
- CALCUL du Lab : tours de carburant ;
- CALCUL du moteur : classement d'arrivée.

Un fait indisponible rend la vérification « non évaluable », jamais réussie.

**Verdicts** :
- `REUSSI` : au moins une vérification évaluable, aucun échec ;
- `ECHOUE` : une vérification échoue ;
- `NON_EVALUABLE` : point non atteint ou aucun fait disponible.

## Scénarios (20)

| Catégorie | Scénarios |
|---|---|
| Carburant | carburant restant, finir avec ce carburant, tours de carburant (nombre vérifié) |
| Stratégie | tours restants (nombre vérifié), plan |
| Stands | quand rentrer, « et si je rentre maintenant » |
| Pilotage | où je perds du temps (plafond de vraisemblance), dernier tour (chrono vérifié) |
| Contexte | « carburant ? » puis « et si je rentre maintenant », « stratégie ? » puis « pourquoi » |
| Données absentes | écart avec la voiture devant, usure des pneus, pneus des adversaires |
| Sécurité des commandes | comparaison de gommes (#73), perte de temps dans un virage (#73), hypothèse « si je rentre au tour 5 », négation « je n'ai pas changé les pneus » |
| Position | en course (0x84), à l'arrivée (classement révisé) |

## Commandes

```
./gradlew -p lab/standalone :lab:installDist
lab/build/install/lab/bin/lab vega <trace> lab/vega-golden/scenarios.json <rapport.md>
LAB_MODE=vega lab/compare.sh <versionA> <versionB> <trace>       # améliorations et régressions entre deux versions
```

Serveur MCP : `lab_vega`, et `lab_compare` avec `mode: "vega"`.

## Résultats sur traces réelles (23/11, moteur V511)

| Trace | Réussis | Échoués | Non évaluables |
|---|---|---|---|
| 22/11 (Course Sport B, 7 tours) | 16 | 4 | 0 |
| 19/11 (7 tours) | 17 | 3 | 0 |

Les nombres vérifiés sont justes :
- carburant pour 47,2 tours contre 47,2 calculés depuis les mesures ;
- « Encore 4 tours après celui-ci » au tour 3/7 ;
- dernier tour « 1 minute 32 secondes 4 » contre 92,4 s mesurées ;
- position P12 sur 16 contre 0x84 = 12 ;
- à l'arrivée : « P12 à la ligne ; provisoire P13 sur 16 ».

**Aucune question n'a modifié l'état de course** (20 sur 20, sur les deux traces), y compris les phrases de #73.

### Défauts révélés (moteur actuel, non corrigés : moteur inchangé dans ce lot)

| Gravité | Scénario | Réponse réelle du moteur | Problème |
|---|---|---|---|
| **P1** | pilotage-pertes (22/11) | « … Au V5 : 3,6 s perdues en moyenne sur ton meilleur passage ; cause probable : vitesse minimale 25 km/h plus basse (confiance moyenne). » | Perte non plausible annoncée. Le garde-fou V511 ne couvre que `CornerCoach` ; la réponse d'analyse passe par `CornerReport`, sans plafond |
| P2 | absent-ecart (22/11, 19/11) | « Je n'ai pas compris : « quel est l'écart avec la voiture devant ». » | Une donnée absente devrait être expliquée (« GT7 ne transmet pas les écarts ») au lieu d'un « pas compris » |
| P2 | absent-gomme-adverse (22/11, 19/11) | « Pneus de 4 tours, gomme non déclarée. Avant 78, arrière 76 degrés… » | Réponse sur **tes** pneus à une question sur ceux des **autres** |
| P2 | securite-hypothese-tour (22/11, 19/11) | « Tour 3 sur 7. Encore 4 tours après celui-ci. » | « Si je rentre au tour 5, ça donne quoi ? » reçoit l'état de course : sûr (aucune modification), mais hors sujet |

### Comparaison de versions

V510 → V511 sur la trace du 22/11 : **0 amélioration, 0 régression, 20 réponses identiques**. C'est attendu : les
correctifs de V511 portent sur les alertes, la radio et les annonces spontanées, pas sur ces réponses. La consigne
spontanée « Virage 5 … 41 dixièmes » avait déjà été mise en évidence par `compare`.

La classification des améliorations, régressions et réponses changées est couverte par les tests automatisés.

## Limites

- **IA non évaluée** : en mode ChatGPT, les questions d'analyse iraient au modèle cloud. Le banc mesure seulement la
  réponse locale. Évaluer GPT exigerait des appels réels au service, hors de ce lot à coût nul.
- **Transcriptions propres** : les erreurs de reconnaissance vocale (Vosk, Google) ne sont pas testées.
- **Traces à environ 20 Hz, sans l'historique du pilote** : le coaching et les comparaisons entre courses peuvent
  différer du direct.
- **Ton et concision** : non jugés automatiquement. Les règles écrites contrôlent les faits, la pertinence par mots
  clés et la sécurité.
- **Versions comparables** : V508 et suivantes, comme pour `compare`.

## Suite proposée

1. Correctif P1 (autorisation requise, moteur) : appliquer le même plafond de vraisemblance à `CornerReport`.
2. Réponses « données absentes » explicites : écart, pneus des adversaires.
3. Hypothèse d'arrêt à un tour donné : la relier à `StrategyDialogue`.
4. Ajouter des scénarios à chaque nouveau défaut constaté en course, pour qu'il devienne un test de non-régression.
