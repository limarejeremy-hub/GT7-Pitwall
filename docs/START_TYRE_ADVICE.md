# Conseil de gomme de départ (issue #65, V487 après les revues #66)

## Quand
1. **Dès la qualification**, sans grille. GT7 n'annonce aucun tour ; la course probable vient du programme et de la voiture (#62). Le conseil est calculé avec le règlement de la course et l'historique de **cette** course (jalon `QUALIF_ADVICE_READY`). La place n'est pas fiable en qualification et n'est pas utilisée.
2. **Sur la prégrille** :
   - la place est attendue au plus 1 s (jalon `FIRST_VALID_GRID` quand 0x84 devient stable) ;
   - le conseil n'est jamais dit dans le même paquet que le briefing ;
   - il est réévalué à chaque nouvelle place ou nouveau règlement, mais **dit seulement si sa conclusion change**, au plus 3 fois (jalon `START_TYRE_ADVICE`) ;
   - même conclusion qu'en qualification (même voiture, moins de 30 min) → **pas de redite**, seulement l'écran.
3. **Après le départ** : rien.

GT7 **ne signale pas la fermeture du choix des pneus**. La chronologie à mesurer sur une trace PS5 réelle : `QUALIF_ADVICE_READY` → `GRID_DETECTED` → `FIRST_VALID_GRID` → `START_TYRE_ADVICE` → `RACE_STARTED`.

## Le calcul (`StartTyreAdvisor`, pur Kotlin, borné)
- **Programmation dynamique** sur les tours, pour chaque suite de gommes légale : O(gommes × tours²). Mesuré sur JVM : environ 40 à 110 ms pour 40, 80 et 150 tours, sans liste de tous les plans. Le calcul se fait une à trois fois par session, jamais à chaque paquet.
- **Temps** : rythme des pneus jeunes × tours + perte par tour d'âge × somme des âges (`CompoundBook`, relais de course comparables), plus le coût mesuré d'un arrêt avec pneus.
- **Exploration partielle, dite comme telle** :
  - de l'arrêt minimal (obligations, gommes imposées, durée de vie, carburant via les arrêts de ravitaillement du plan) à un arrêt de plus ;
  - aucun de plus au-delà de 40 tours, et au plus 3 arrêts ;
  - chaque arrêt est compté comme un changement de pneus.
- **Incertitude de la différence** entre deux plans : rythme et dégradation de chaque gomme, coût des arrêts, effet de la grille, plus **150 ms par tour roulé sur une gomme différente d'un plan à l'autre**. Le gain est dit à la seconde près (pas de faux dixièmes), marqué « estimation provisoire » si une gomme n'est que provisoire.

## Statuts : jamais de conseil sans preuve
| Statut | Quand | Radio |
|---|---|---|
| `RECOMMENDED` | écart ≥ 1 s et plus de deux fois l'incertitude, toutes les gommes de départ légales chiffrées | « je conseille… Conseil seulement, dis-moi ce que tu montes » |
| `CLOSE` | écart sous l'incertitude | « options proches » |
| `INCOMPLETE` | une gomme de départ légale sans historique | « parmi les seules gommes chiffrables… conseil incomplet » (tendance, pas de conseil) |
| `NO_HISTORY` | aucune gomme chiffrable | départs légaux seulement |
| `RULES_UNKNOWN` | gommes autorisées inconnues ; programme périmé ; fiche contredite par les tours GT7 ; course personnalisée non vérifiée ; gommes non confirmées | **écran et journal seulement** (le rappel de règlement existe déjà) |
| `IMPOSSIBLE` | gommes imposées hors des autorisées, trop d'arrêts | pas de conseil |

- Une gomme roulée dans une autre course, ou la seule liste des gommes imposées, **ne prouve pas** qu'une gomme est autorisée dans cette course.
- **Le conseil n'est jamais la gomme montée** : `RaceRules.startTyre` et le registre des arrêts restent intacts. Rien n'est dit si le pilote a déjà dit sa gomme.

## La grille
- Elle n'agit que par les pertes mesurées dans **ton** historique au début du relais de départ : tours 2 et 3, car le tour 1 est exclu des tours propres. C'est mesuré par zone (P1–P4, P5–P10, au-delà) et par gomme, sur au moins 2 courses.
- Ces pertes sont **provisoires** : le rythme de référence vient des mêmes courses, et la dispersion est comptée au moins égale à l'effet tant que l'échantillon est petit. Ce n'est jamais une preuve de cause.
- GT7 ne transmet ni écarts ni gommes adverses : rien n'est inventé.

## Chronos de qualification
**Non utilisés.** Les sessions de qualification ne sont pas comparables au rythme de course (`HistoryRelation`). Le conseil l'écrit dans ses bases : « chronos de qualification non utilisés ».

## À l'écran
Avant le départ, le panneau « Plan de course » du Live devient « Gomme de départ » : conseil ou tendance, phrase, statut, et la mention « conseil seulement, dis ta gomme à la radio ».

## Tests
- `StartTyreAdvisorTest` (12) :
  - RM + RS imposées ;
  - P2 contre P14 (la place seule ne change rien ; des pertes mesurées inversent le choix) ;
  - options proches ;
  - pas d'historique ;
  - un seul relais ;
  - RH imposée et sprint ;
  - règlement contradictoire ;
  - durée de vie ;
  - gommes autorisées inconnues ;
  - troisième gomme légale non chiffrée ;
  - gain à la seconde et chronos de qualification non utilisés ;
  - courses de 40, 80 et 150 tours (performances, exploration partielle).
- `StartTyreAdviceRaceTest` (5) :
  - qualification puis prégrille sans redite, et rien après le départ ;
  - grille sans qualification ;
  - gommes autorisées inconnues ;
  - programme périmé et course personnalisée non vérifiée ;
  - gomme déjà dite.
- **Aucun essai PS5.** Le délai réel avant la fermeture du choix des pneus reste à mesurer.

## Revue de suivi (V487)
- **Carte visible pendant les tours de qualification** : elle s'affiche tant qu'aucun tour n'est annoncé (qualification) ou à la grille (tour 0), et disparaît une fois la course lancée (`StartTyreAdvisor.showable`).
- **Anti-redite limitée au passage qualification → grille de la même épreuve** : même voiture, même série, mêmes tours, gommes et arrêts imposés, même conclusion **plan compris** (un autre tour d'arrêt est un autre conseil), moins de 30 min. Une autre course n'est jamais dédoublonnée.
- **Priorité radio haute** pour le conseil. Il passe immédiatement, devant l'annonce de l'épreuve, le briefing et toute annonce en attente. En qualification, l'annonce de l'épreuve est alors raccourcie. Les tests vérifient l'**ordre** des messages, pas seulement leur présence.
- **Qualification** :
  - la phrase dit « Si c'est bien la Course Sport C : … » (épreuve candidate, règlement publié), et elle est révisée sur la grille ;
  - le coût des arrêts vient de l'historique **de la course visée** et de la voie des stands **de son circuit**, jamais de la session en cours ;
  - l'autonomie est inconnue : « carburant non pris en compte » est écrit dans les bases.
- **Calcul borné quel que soit le nombre de gommes autorisées** :
  - les départs légaux sont déduits du règlement, sans énumérer ;
  - seules les gommes chiffrables, au plus 4, entrent dans la programmation dynamique ;
  - mesuré sur JVM : 8 gommes autorisées sur 150 tours, environ 80 ms au pire.
  - Ce calcul est **ponctuel** : il bloque un paquet, au plus 1 à 4 fois par session, jamais à chaque paquet. Il n'est pas garanti sous 16 ms.
