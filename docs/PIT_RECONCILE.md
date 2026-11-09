# Stands : indices contradictoires réconciliés (V503, #73 P0-B)

## Le problème réel (Watkins Glen, 06/11)

Arrêt prévu fin du tour 6 ; passage au tour 7 (le compteur passe dans la voie des stands), immobile 4,8 s, sans
ravitaillement. Le détecteur de passages conclut « arrêt en piste » ; Pitwall l'écarte (« je ne le compte pas »), puis
recalcule un arrêt au tour 10 (« gain 6,7 s »). Au tour 9 le pilote déclare « j'ai déjà mis les durs… arrêté au stand » :
l'arrêt est enregistré **au tour 9** et le train de durs date du tour 9. Le résumé final dit « arrêts aux tours 9 ».

Ce n'était pas qu'une formulation « début / fin du tour 7 » : un indice expérimental l'emportait sur le plan, la
déclaration déplaçait l'événement, et un nouvel arrêt était proposé sur un historique contradictoire.

## Ce qui change

1. **Arrêt contesté.** Un arrêt classé « en piste » qui tombe sur l'arrêt du plan (fin du tour précédent ou ce tour)
   n'est plus écarté : passage au stand **PROBABLE**, gomme **NON confirmée**, rien de compté. Une question courte :
   « Tour 7 : arrêt de 5 secondes, mes indices se contredisent. C'était ton arrêt au stand ? ». Journal : « Indices
   contradictoires au tour 7 : passage au stand probable, gomme non confirmée », avec l'indice du détecteur, l'arrêt du
   plan, la durée, le verdict Tyre Change Hunter et `STAND_PROBABLE / TYRES_UNCONFIRMED`.
   Exceptions : un **choc** reste un arrêt en piste ; ailleurs dans la course, un arrêt en piste n'est pas mis en doute
   (pas de question à chaque tête-à-queue).
2. **Aucun nouvel arrêt annoncé** tant que le pilote n'a pas tranché : plan suspendu (journalisé une fois), un seul
   rappel au tour suivant (« Sans réponse : tour 7, c'était ton arrêt au stand ? Je n'annonce pas d'autre arrêt d'ici
   là. »). Les alertes carburant restent locales et immédiates. « Non » : l'arrêt n'est pas compté, le plan reprend.
3. **Tour du passage ≠ tour de la déclaration.** « Arrêt fait » ou « oui », même deux tours plus tard, enregistre
   l'arrêt **au tour du passage** ; le tour de la déclaration est noté à part (`PitStopRecord.declaredLap`) : « Arrêt 1
   enregistré : confirmé par toi. C'est le passage du tour 7, prévu fin du tour 6. »
4. **Pneus déclarés plus tard** : datés du passage (premier tour après), jamais du tour de la phrase — on ne change de
   pneus qu'à un arrêt (`PitLedger.passageLapForLateTyres`). La gomme vient toujours du pilote, jamais de la trajectoire.
   Une déclaration de pneus pendant qu'une question attend sa réponse est d'abord confirmée (protection V494), puis
   confirme l'arrêt contesté.
5. **Deux gommes imposées ≠ « arrêts obligatoires : 0 ».** Le plan garde l'arrêt nécessaire pour monter la seconde gomme ;
   c'est cet arrêt du plan qui rend un arrêt « en piste » contestable.

## Corrections du détecteur au passage (`PitDetector`)

- **Choc jamais vu** : la fenêtre de décélération ne contenait que les 2 s d'immobilité (ouverture du passage) ; elle
  remonte désormais avant. Seuil relevé à **8 g** (au-delà de tout freinage), pour ne pas prendre un freinage appuyé
  dans la voie des stands pour un accident.
- **Durée sous-estimée de 2 s** : les 2 s d'immobilité avant l'ouverture sont comptées.
- Chaque « arrêt en piste » dit pourquoi (`TrackReason` : choc, pas d'approche au limiteur, loin de la ligne, sur la
  trajectoire).

## Validation

- `PitReconcileTest` (8) — **fixtures synthétiques** reprenant les événements de #73 (aucune trace réelle de cette
  course dans le dépôt ; aucune n'est prétendue) : arrêt du plan lu « en piste », question unique, plan suspendu, un seul
  rappel, déclaration tardive au tour du passage, pneus seuls qui confirment, « non », choc, raison du détecteur, registre.
- Suite JVM complète : 885 tests, 0 échec.
- **Non vérifié** : course réelle sur PS5 (pourquoi le détecteur a lu « en piste » à Watkins Glen n'est pas établi sans
  la trace ; à rejouer avec le pilote).
