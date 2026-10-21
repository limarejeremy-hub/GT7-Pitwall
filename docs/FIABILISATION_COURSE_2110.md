# Fiabilisation après la première course réelle (21/10)

Session analysée : `1792612877035-3600`. Porsche 911 GT3 R '22, Gilles-Villeneuve, 12 tours, 20 voitures, départ en
course durs. Un arrêt au tour 6 (+66,7 de carburant, course tendres montés), arrivée P1/20, meilleur tour 1:35.130.
Sources utilisées : le journal exporté (JSON et texte) et l'export Gap Hunter (142 459 paquets de 368 octets).

Tous les correctifs sont couverts par `RealRace2110Test`, construit avec les valeurs de cette course. Les paquets y sont
reconstruits, pas rejoués octet par octet.

## Bugs confirmés (cause racine démontrée)

| # | Symptôme en course | Cause racine prouvée | Correctif |
|---|---|---|---|
| A | « pneus tendres » → noté **confort tendres** | `Tyres.fromSpeech` choisissait parmi les gommes du règlement (`CS, CM, CH, RH`) **non confirmé** : la seule « tendre » était CS | Sans règlement confirmé, une dureté sans famille est **demandée** : « Tendres : course, sport ou confort ? Course tendres, comme tes pneus actuels ? ». « Course », « oui » (l'hypothèse) ou « course tendres » répondent. Rien n'est noté avant la réponse |
| A' | « course tendres » compris comme une question sur la course | Le parseur n'acceptait une gomme qu'avec « pneus », un verbe ou « je suis en » ; « course » partait vers l'état de la course | Famille + dureté accolées = déclaration de gomme |
| B | « oui » à « As-tu changé les pneus ? » **efface** la gomme | Au T7, `tyresChanged` ajoutait un second `TyreChange(7, null)` ; la gomme courante devenait inconnue | Un même passage n'a qu'un changement : « oui » l'enrichit sans rien effacer. La question n'est plus posée si les pneus sont déjà déclarés (« Pneus déjà notés pour cet arrêt »). Une gomme inconnue n'est jamais « certaine » ; la certitude de l'**âge** est séparée de celle de la **gomme** |
| C | Deux ravitaillements (T6 et T7) | `FuelRise` ne remettait son cumul à zéro qu'à une **baisse** du niveau ; immobile au stand, le niveau ne baisse pas, et le tour 7 était encore « en hausse » | Seule une hausse **en cours** compte. Le tour qui commence dans la voie des stands est marqué `sortie_des_stands` et exclu des moyennes ; le niveau après chaque tour (`fuelAfter`) est archivé |
| D | Plan contradictoire au T7 (« arrêt au T9 » puis « plus d'arrêt » au T8) | Conséquence de C : tour 7 « ravitaillé » → `currentLapRemaining = 1`. Rejeu avec les valeurs du journal : 6 × 13,95 = 83,7 > 81,1 → arrêt ; avec la part réelle du tour (≈ 0,73) : 80,0 < 81,1 → aucun arrêt | Corrigé par C ; le rejeu est un test |
| E | Alerte « moins de deux tours » répétée au stand | Les alertes carburant étaient évaluées pendant le plein | Aucune alerte carburant pendant un tour ravitaillé. Les autres alertes critiques ne sont pas masquées |
| F | « Au prochain arrêt je mets quoi comme pneus ? » → réponse sur l'essence | « prochain » envoyait vers le plan d'arrêts | Nouvelle question `NEXT_TYRE` : gommes imposées manquantes, prochain arrêt, pneus actuels, gommes du règlement (en précisant s'il n'est pas confirmé), durée de vie si le pilote l'a donnée, sinon « GT7 ne transmet pas l'usure ». Rien n'est modifié |
| G | « douzième » après l'arrivée → « Tu es P12, je le note ? » | Le garde des commandes ignorait que la course était finie | Après l'arrivée : « Course terminée : tu as fini P1. Je ne change plus le classement. » |
| H | Après l'arrivée : 14 min d'instantané figé (79,6 km/h), puis tour −1 à 178 km/h | Le contrôleur traitait ces paquets comme une voiture en mouvement ; les transitions 13 → −1 → 13 pouvaient rouvrir une session | Vitesse > 1 km/h sans aucun déplacement pendant 0,5 s, ou tour < 0 : télémétrie **figée**, une seule ligne de journal, rien n'est suivi. Le journal ne s'ouvre qu'au tour 1. Aucune session fantôme |

## Améliorations (pas des bugs)

- **Surfaces (format C, 0x158)** : chaque tour enregistre la part passée sur un vibreur (`vibreurs_pourcent`) et les
  sorties de piste (`sorties_de_piste` : au moins deux roues hors asphalte et hors vibreur pendant 15 paquets). Un
  vibreur n'est **pas** une erreur. Un tour avec une sortie est exclu du rythme et du diagnostic d'adhérence. Sans
  format C : `null`, rien n'est inventé. **Aucun conseil vocal** n'est tiré de ces données (non validé sur PS5).
- **Traçabilité** : une gomme corrigée est journalisée (« Gomme corrigée : X → Y », avec avant, après et tour) ; chaque
  tour porte `gomme_certaine`, `age_pneus_certain` et `sortie_des_stands`.
- **Journal** : les événements radio en attente depuis plus de 10 minutes ne rejoignent plus la session suivante.
- **Règlement non confirmé** : « À vérifier : les X ne sont pas dans le règlement enregistré, qui n'est pas confirmé »
  au lieu d'un « Attention » catégorique.

## Hypothèses et limites (non démontrées)

- **Écarts entre voitures** : toujours **non disponibles**. L'export Gap Hunter (42 changements de 0x84, 18 changements
  de tour) ne montre **aucune corrélation**. Aucun écart n'est annoncé.
- **0x84 en course** : non utilisé comme position en direct.
- **Heure et état réels des bips et de la voix (TTS)** : **non implémenté**. C'est une instrumentation de l'application
  Android, impossible à tester ici ; le journal note le message envoyé à la voix, pas l'instant où il a été entendu.
- **Discord et veille écran éteint** : aucun changement dans cette tâche ; leur bon fonctionnement n'est vérifié que par
  la compilation de l'APK, pas sur PS5.

## Tests automatisés (`RealRace2110Test`, 13 tests ; 378 tests au total, 0 échec)

1. Tendres ambiguës → question → course tendres = RS ; « course » seul ou « oui » répond.
2. Arrêt après une gomme déclarée : pas de seconde question, « oui » ne crée rien et n'efface rien ; une seule gomme,
   un seul arrêt, tours suivants en RS certains.
3. Registre : un changement par passage, correction tracée et journalisée (avant et après).
4. Gomme inconnue : jamais certaine, mais l'âge (pneus neufs) l'est.
5. Plein à cheval sur la ligne : un seul ravitaillement, tour 7 mesuré (14,4), exclu des moyennes, `fuelAfter` partout.
6. Rejeu du plan au T7 avec les valeurs du journal : le bug est reproduit, puis disparaît avec la part réelle du tour.
7. Questions « prochaine gomme » tirées des transcriptions réelles ; la réponse parle de pneus, pas d'essence.
8. Surfaces : vibreur ≠ sortie ; échantillons isolés ignorés ; sans format C, rien.
9. « Douzième » après l'arrivée : P1 conservé.
10. Paquets figés et menus après l'arrivée : aucun message, aucune session fantôme, une ligne « Télémétrie figée ».

## Tests PS5 à refaire (une course de 4 à 6 tours, un arrêt)

1. **Départ** : « je pars en mediums » → la radio demande « course, sport ou confort ? » ; réponds « course ».
2. **Au stand** : dis « course tendres » pendant le plein. Attendu : « Pneus déjà notés pour cet arrêt » et **aucune**
   question « As-tu changé les pneus ? ». Dans l'historique : **un** seul stand, tours suivants en course tendres.
3. **Plein long, ligne franchie dans la voie des stands** : aucune alerte carburant pendant le plein, **un** seul
   ravitaillement dans l'historique, et le plan du tour suivant ne change pas d'avis d'un tour à l'autre.
4. **« Au prochain arrêt je mets quoi comme pneus ? »** : la réponse parle de gommes, pas d'essence.
5. **Vibreurs et herbe** : prends un vibreur franchement, puis mets deux roues dans l'herbe ; dans l'export du tour :
   `vibreurs_pourcent` > 0, puis `sorties_de_piste` = 1 au bon tour.
6. **Après l'arrivée** : reste 2 minutes sur l'écran de résultats, puis va au menu. Dis « douzième » : la radio garde
   ta position finale. L'historique ne contient **qu'une** session.
7. **Radio** : les bips, la conversation continue, Discord et l'écran éteint fonctionnent comme avant.
