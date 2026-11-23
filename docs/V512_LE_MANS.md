# V512 — Analyse de la Course Sport C du Mans (23/11/2026) et lot 1

Données reçues : le journal JSON et le journal texte de la session `1795428018225-3607` (Porsche 963 '24, code 3607).
**Aucune trace brute `.gt7trace` n'a été reçue.** Rien de ce qui suit ne vient d'un rejeu de paquets. Les conclusions
s'appuient sur les événements exportés par l'application.

Natures : **MESURE** (paquet GT7 journalisé), **CALCUL** (moteur ou analyse), **ESTIMATION**, **HYPOTHÈSE**,
**DÉCLARATION PILOTE**.

## 1. Défauts démontrés par la course

| # | Défaut | Preuve (journal) | Nature | Lot |
|---|---|---|---|---|
| D1 | **Fiche Sport périmée appliquée** : Suzuka du 16/11 (13 tours, usure ×3, RS) | contexte de session « tours_reglement 13 », GT7 « 14 » ; « Course de 13 tours » au tour 0 ; « Usure des pneus fois 3 » au tour 2 | MESURE | **1 (corrigé)** |
| D2 | **Raison fausse du changement T7 → T9** : « Un arrêt devient nécessaire » | T8 10:29:49, plan précédent fin T7, aucun passage au stand en T7 | MESURE + CALCUL | **1 (corrigé)** |
| D3 | **Fin de course mal classée** : « qualification supposée » au tour 14/14, puis « interrompue » avec 13 tours | 10:55:17 UTC : tour 14, 0 tour annoncé, 187,6 km/h, P5/9, puis télémétrie figée | MESURE | **1 (corrigé)** |
| D4 | **Observation du pilote prise pour une question** : « deux concurrents… rentrer aux stands » → le plan est répété | T7 10:25:44, intention « question d'état plan » | MESURE | 2 |
| D5 | **Attente IA de 12 s** : « lift and coast » mal transcrit, envoyé à l'IA cloud, délai dépassé puis repli local hors sujet | T11 10:43:21, 12 004 ms, première réponse (1 355 ms) jetée comme périmée | MESURE | 5 |
| D6 | « comment je fais pour attraper mon temps » compris comme une commande « silence » | T4 10:15:39 | MESURE | 5 |
| D7 | **Fausse alerte de chauffe après l'arrêt** répétée 4 fois : « 78 degrés, contre 63 » | T12–T13. Référence 63 °C prise sur les pneus froids juste après l'arrêt ; 78 °C est la température habituelle avant l'arrêt (75–79) | MESURE + CALCUL | 3 |
| D8 | Messages importants perdus car périmés : chauffe à 104 °C (T3), bilan du tour 8 | « Message périmé non dit » | MESURE | 5 |
| D9 | Réponses Gemini non prouvées : « tes treize blocages détruisent tes pneus » (un blocage n'est pas une mesure d'usure) | T4 | MESURE (texte) | 5 |

## 2. Cause technique du passage T7 → T9

1. **Tour 0, avant la grille** : le plan est fait sur 13 tours (fiche Suzuka), car GT7 n'annonce pas encore ses tours.
   Il prévoit 1 arrêt fin T7. **CALCUL**
2. **Dès la grille**, GT7 annonce 14 tours (**MESURE**). Le planificateur les utilise (`RacePlanner.totalLaps` : GT7
   d'abord). Ensuite :
   - le partage 7 + 7 donne encore T7 ;
   - le maintien des arrêts annoncés (`keptStints`) garde T7 ;
   - les pourcentages de plein annoncés (38–40 %) correspondent à 14 tours.

   La fiche périmée **n'a donc pas déplacé l'arrêt.** **CALCUL**
3. **Fin T7 : aucun passage au stand** (**MESURE** ; le pilote est resté en piste, **DÉCLARATION PILOTE**). Le plan
   est gelé un moment après la ligne d'un tour d'arrêt. 45 s plus tard (T8), il est recalculé :
   - l'arrêt T7 est passé ;
   - 30,2 % à bord pour 9,81 % par tour, soit environ 3,1 tours d'autonomie (**CALCUL**) ;
   - le premier relais est plafonné par le carburant et la marge de 0,3 tour ;
   - d'où l'arrêt fin T9, **dernier tour possible**. Fin T9, il restait 12,8 % (**MESURE**).
4. **`planChangeReason`** ne compare que les arrêts à venir. L'arrêt T7 étant passé, il a conclu « Un arrêt devient
   nécessaire », comme si rien n'était prévu.

**Corrigé (lot 1)** :
- la raison devient « Arrêt du tour 7 non effectué : le tour 9 est le dernier possible avec le carburant mesuré. » ;
- le journal garde le plan précédent, le nouveau plan, les données changées, le gain si le comparateur l'a calculé et
  l'incertitude ;
- « Pourquoi tu changes ma stratégie ? » lit cette raison (`lastPlanChange`).

**Cause de la fiche périmée** :
- le programme de la semaine du 23/11 n'était pas chargé (`schedule.stale`), donc il n'y a pas d'édition courante ;
- le garde-fou existant (« Règlement Sport incompatible ») ne compare que l'édition courante, et il s'est tu ;
- les règles enregistrées du profil (Suzuka) sont restées appliquées.

**Corrigé (lot 1)** : quand la fiche est sans édition courante et que GT7 la contredit sur le nombre de tours :
- une annonce unique ;
- les tours de GT7 font foi ;
- l'usure est dite « d'après une fiche Sport pas à jour » ;
- l'obligation est marquée « fiche Sport pas à jour ».

## 3. Diagnostic de la fin de session

| Fait | Nature |
|---|---|
| Tour 14 commencé à 10:51:58 UTC, P5/9, 15,5 % de carburant | MESURE |
| 10:55:17 (3 min 19 s dans le tour) : GT7 annonce **0 tour**, 187,6 km/h, P5/9 ; position figée ensuite | MESURE |
| Meilleur tour 3:41,6 : la ligne était à environ 20 s | ESTIMATION |
| Fin de course GT7, abandon, retour au menu ou coupure Wi-Fi ? | HYPOTHÈSES : **impossible à trancher sans la trace brute** |

**Corrigé (lot 1)** :
- un « 0 tour » dans une session qui a déjà annoncé ses tours ne déclenche plus « qualification supposée » ;
- une coupure pendant le dernier tour est archivée `LAST_LAP_CUT` : **aucune arrivée ni résultat déduits** ;
- 4 informations sont gardées séparément :
  - la dernière position mesurée en course (P5/9) ;
  - la position à la ligne (inconnue ici) ;
  - les révisions ;
  - le résultat officiel (« non vérifiable »).
- les tours de GT7 de la session priment sur la fiche pour filtrer les tours archivés. Avec 13 tours sur la fiche, un
  14e tour bouclé aurait été écarté.

## 4. Faisable tout de suite avec la télémétrie actuelle

- **Observation de concurrents** : mémoriser, puis surveiller les changements de rang qui suivent (lot 2). Le moteur a
  déjà `activeObservations`.
- **Voie des stands au Mans** :
  - X/Z, vitesse et `inPit` sont mesurés ;
  - l'entrée, l'immobilisation et la sortie sont cartographiables dès la première trace brute ;
  - temps total dans la voie, distinct de l'immobilisation (35,4 s).
- **Rang pendant l'arrêt** : P3 → P5 à l'arrêt (MESURE) ; distinguer les places perdues au stand des places perdues en
  piste.
- **Tours perturbés** : T3–T4 ont des presque-arrêts (23 km/h puis 2,6 km/h), avec P2 → P8. À exclure des références
  de coaching, de régularité et de dégradation, **sans les attribuer à une pénalité**.
- **Deltas chiffrés des secteurs** sur le tableau GT3 (lot 4) : les secteurs actuels sont des tiers de distance, ce
  n'est pas le découpage officiel.
- **Fausse alerte de chauffe après pneus neufs** (lot 3) : la référence doit attendre la stabilisation.
- **Réponses locales** au « lift and coast » et à l'économie de carburant ; délai IA plafonné en course (lot 5).

## 5. Ce qui demande d'autres courses ou mesures

- **Zone de purge des pénalités** : observation du pilote (« secteur 2 du jeu ») à localiser. Les traces brutes de
  plusieurs courses sont nécessaires (vitesses, X/Z). Statut « probable » au mieux, aucun champ UDP ne prouve une
  pénalité.
- **Dégradation des pneus** : plusieurs relais comparables par gomme. Un seul relais ne suffit jamais. La gomme
  montée reste une DÉCLARATION PILOTE.
- **Undercut et overcut** : GT7 ne transmet ni écarts ni arrêts adverses. Seule une approche probabiliste (rang et
  déclarations du pilote) est possible, avec incertitude affichée.
- **Cause de la coupure au tour 14** : la trace brute de la prochaine course.

## 6. Voix gratuites crédibles pour le Fold (à mesurer : aucun banc réel fait)

| Moteur | Voix française | Licence | Performance publiée | Statut |
|---|---|---|---|---|
| sherpa-onnx + Piper `fr_FR-siwis-medium` | féminine, 22 kHz | sherpa-onnx Apache-2.0 ; voix : licence du jeu de données SIWIS **à vérifier** | ×5 à ×8 le temps réel sur un ARM serveur 2 cœurs (discussion sherpa-onnx #3968) | **candidate 1** : la plus sûre pour la latence |
| sherpa-onnx + Kokoro-82M `ff_siwis` | féminine, 24 kHz | Apache-2.0 (modèle) | ×0,91 le temps réel sur le même ARM 2 cœurs : **plus lent que le temps réel** | candidate 2, à mesurer sur le Fold |
| Kyutai Pocket TTS (voix « estelle ») | féminine, français depuis mai 2026 | MIT (code) ; licence de chaque voix **à vérifier** | environ 200 ms jusqu'au premier son sur ordinateur ; port LiteRT Android environ ×1 temps réel sur Pixel 8a ; pris en charge par sherpa-onnx | candidate 3 : la plus naturelle annoncée, la moins sûre sur téléphone |

Toutes produisent du PCM, compatible avec la chaîne Opus de Discord. **Remplacer le TTS n'est pas une intégration
GPT-Live.** La voix par défaut reste inchangée tant que le banc A/B sur le Fold n'est pas fait et que le pilote n'a pas
choisi.

## 7. Plan priorisé

| Lot | Contenu | Prérequis |
|---|---|---|
| **1 (fait, PR brouillon)** | fiche périmée, raison des changements de plan, fin de course | — |
| 2 | observations du pilote sur les concurrents, rang pendant l'arrêt, voie des stands du Mans, tours perturbés | trace brute du Mans pour la géographie |
| 3 | qualité des relais, fausse alerte de chauffe, dégradation comparée par virage | plusieurs relais réels |
| 4 | deltas chiffrés des secteurs (`GtDash`, `GtDashboard`), 3 références, captures Robolectric | — |
| 5 | intentions (observation, commande, question), délai IA en course, banc A/B des voix sur le Fold | essai Fold du pilote |

## Validation

- **Automatisée** : `V512LeMansTest` (5 tests, circuit simulé), suite complète du cœur et du Lab, rejeu des traces
  réelles existantes, `lab compare` contre la version précédente.
- **PS5 et Fold** : aucune. À vérifier à la prochaine course du Mans :
  1. Fiche Sport non rafraîchie : l'annonce « Fiche Sport pas à jour » arrive-t-elle au départ ?
  2. Arrêt planifié volontairement sauté : la raison annoncée est-elle « Arrêt du tour N non effectué » ?
  3. Après la ligne d'arrivée, l'historique dit-il « terminée » ? Si la télémétrie coupe dans le dernier tour, dit-il
     « coupée au dernier tour » ?
