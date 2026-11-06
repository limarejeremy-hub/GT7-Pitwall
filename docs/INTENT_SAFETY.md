# Radio : le sens de la phrase libre prime (issue #73, P0-A, V494)

Course Sport C du 06/11 (Watkins Glen, 11 tours) : le journal montre **aucun appel GPT** et des modifications appliquées
à partir d'une transcription « vocabulaire radio » déformée.

| Phrase libre (ce que le pilote voulait dire) | Vocabulaire radio | Avant | Maintenant |
|---|---|---|---|
| il était préférable de partir en dur pour medium | dépasse faire départ tiennent durent bons mediums | `Tyres` **appliqué** | analyse, **rien modifié** |
| je perds du temps donc un virage | je perdues tendres aucun virages | `Overtaken` **appliqué** | question de **pilotage**, rien modifié |
| par rapport à ma position garde la même stratégie | pas pourquoi moi position bons garde mêmes stratégie | « le plan a changé depuis le tour 0 » | analyse (GPT si choisi) |
| changer mes pneus au début du tour sept | changes mes pneu aux départ dit tours sept | réponse « température des pneus » | discussion du plan, rien modifié |
| je pars en medium (Gemini Nano, « oui ») | — | confirmation demandée | **inchangé** (la bonne protection) |

## `IntentArbiter` (core, pur, testé)

1. Phrase libre sur le **pilotage** (temps perdu, virage, freinage…) mais position/dépassement côté vocabulaire →
   question de pilotage, jamais `Overtaken`.
2. **Comparaison, hypothèse, regret, avis** (« préférable », « aurait », « fallait », « mieux », « par rapport »,
   « tu penses », « si je »…) → **analyse**, aucune modification. Les questions simples (« pourquoi je m'arrête au
   prochain tour », « combien d'essence », « on garde la même stratégie ? ») restent locales et instantanées.
3. **Intention datée** (« … au début du tour sept », sans passé composé) → discussion du plan.
4. **Modification** (pneus, arrêt, règlement, position, météo) que la phrase libre **ne confirme pas** →
   `CommandSource.AMBIGUOUS` : une seule question courte, appliquée seulement après « oui ».
5. Les deux transcriptions disent la même déclaration, ou pas de phrase libre → chemin habituel (`CommandGate`).

## Route visible et journalisée

Chaque phrase note : **mode configuré · intention · route · qui a répondu · raison du contournement de GPT**
(`RadioConversation.RouteTrace`), dans le journal (« Route radio : … ») et sur la carte de l'IA.

- Mode « ChatGPT via ton forfait » : les questions d'**analyse** (causes, comparaisons, avis, pilotage) vont à GPT avec
  ses outils ; les questions d'**état** (carburant, position, plan) et les alertes (« box ») restent locales et
  immédiates — jamais 8 s d'attente.
- Le mode se choisit dans « IA de l'ingénieur » : être connecté à ChatGPT ne le sélectionne pas.

## Vérifié / non vérifié

- `IntentSafetyTest` (9) sur les paires du journal (anonymisées), négations, hypothèses, déclaration explicite,
  confirmation, mode GPT (GPT réellement appelé, état local), raison affichée en mode local. 846 tests, 0 échec.
- **Non vérifié** : en course sur la PS5 ; l'audio d'origine de chaque phrase est inconnu.
- Pas dans cette PR (issue #73) : réconciliation des stands T7 (P0-B), diction « onze » (P1-C), débrief de l'archive.
