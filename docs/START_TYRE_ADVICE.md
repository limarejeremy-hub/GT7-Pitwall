# Conseil de gomme de départ (issue #65, V485)

## Ce que fait Pitwall
Sur la grille, avant les feux, Pitwall dit **une phrase** de conseil. `StartTyreAdvisor` est un calcul pur Kotlin, sans modèle de langage.

1. **Les plans légaux comparés** :
   - toutes les suites de gommes autorisées qui contiennent les gommes imposées ;
   - de l'arrêt minimal (obligations, gommes imposées, durée de vie déclarée) à un arrêt de plus ;
   - toutes les longueurs de relais possibles.
2. **Le temps de chaque relais**, d'après **ton historique** (`CompoundBook`, courses comparables) : rythme des pneus jeunes × tours + perte par tour d'âge × somme des âges. On y ajoute le coût mesuré d'un arrêt avec changement de pneus.
3. **La place sur la grille**, si GT7 la donne, stable 2 s en course :
   - elle agit **seulement** par ce que ton historique a mesuré : la perte des 3 premiers tours du relais de départ selon la zone (devant P1–P4, milieu P5–P10, derrière), par gomme, sur au moins deux courses ;
   - sans cette mesure, la place est dite mais **ne change rien**. GT7 ne donne ni les écarts ni les gommes des autres.
4. **La recommandation** n'est donnée que si l'écart dépasse 1 s **et** le double de l'incertitude. Sinon Pitwall dit « options proches », et sans historique il dit seulement les départs légaux.

## Ce que Pitwall ne fait jamais
- Il n'enregistre pas le conseil comme gomme montée : `RaceRules.startTyre` et le registre des arrêts restent vides tant que tu ne dis pas ta gomme.
- Il ne recommande rien d'illégal ni de contradictoire avec le règlement.
- Il ne parle pas après le départ. Il donne au plus deux conseils : un sans place, puis un seul autre si la place connue change la gomme conseillée.

## Limite P0 : la fenêtre de temps n'est pas prouvée
- GT7 ne signale pas quand le choix des pneus se ferme, et la place (0x84) n'est fiable qu'en course, à l'arrêt sur la grille.
- **Il est possible que la place arrive après le dernier moment pour changer de pneus.** C'est à mesurer sur une trace PS5 réelle grâce aux jalons du journal : `GRID_DETECTED`, `START_TYRE_ADVICE`, première position.
- En attendant, si la place n'arrive pas en 3 s sur la grille, le conseil part **sans place**.
- Un conseil dès la qualification (avant la grille) n'est pas encore branché : le profil de la course n'y est pas encore actif.

## Tests
- `StartTyreAdvisorTest` (8) :
  - RM + RS imposées, 14 tours ;
  - P2 contre P14 : la place seule ne change rien, des pertes mesurées inversent le choix ;
  - options proches ;
  - pas d'historique ;
  - un seul relais ;
  - RH imposée ;
  - course sans arrêt ;
  - règlement contradictoire ;
  - durée de vie déclarée.
- `StartTyreAdviceRaceTest` (2) : un seul conseil sur la grille, aucune gomme enregistrée, rien si le pilote a déjà dit sa gomme.
- Aucun essai PS5.
