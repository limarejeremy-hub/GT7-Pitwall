# Croisement des données GT7 — Sprint A (intégrité) et Sprint B (motricité)

Issues #35 et #36. Branche `claude/v436-tyre-traction`, partie de `claude/v426-radio-hotfix` (APK #432, radio validée).
Données utilisées : **UDP seulement**. Rien ne vient du HUD, de Remote Play, d'une image, d'un PC ni d'un autre matériel.

Tout ce qui suit est vérifié par des tests JVM et par la compilation Android. **Rien n'est validé sur PS5** : les seuils
sont des valeurs de départ, à recalibrer sur de vraies sorties de virage.

## Champs utilisés

| Champ | Source | Fréquence | Disponible | Limite |
|---|---|---|---|---|
| Accélérateur, frein (0x91/0x92) | UDP A/B/~/C | ~60 Hz | Oui | 50 % de pédale n'est pas 50 % de couple |
| Vitesse voiture (0x4C) | UDP | ~60 Hz | Oui | — |
| Rotation (0xA4..B0) et rayon (0xB4..C0) des 4 roues | UDP | ~60 Hz | Oui | Le **glissement est calculé** : roue / voiture, rien sous 20 km/h |
| Rapport, régime moteur | UDP | ~60 Hz | Oui | — |
| Accélération longitudinale | **Calculée** | ~60 Hz | Oui | Δv entre paquets successifs ; chocs au-delà de 25 m/s² écartés |
| Charge latérale | **Calculée** | ~60 Hz | Oui | Vitesse × lacet (0x30) ; approximation |
| TCS / ASM (drapeaux 0x8E) | UDP | ~60 Hz | Oui | Le TCS peut masquer le patinage |
| 4 températures pneus (0x60..6C) | UDP | ~60 Hz | Oui | Une température élevée n'est pas une usure |
| Surface sous chaque roue (0x158..15B) | UDP **format C seulement** | ~60 Hz | Selon le format reçu | 152 969 paquets de 296 octets mesurés par Gap Hunter : sans surface |
| Tours annoncés par GT7 (0x76) | UDP | ~60 Hz | Oui | — |
| Gomme montée, usure en %, écarts adverses | **Absents de l'UDP** | — | Non | Jamais inventés |

## Sprint A — intégrité (commit 49759a3)

1. **Surfaces par roue conservées.** Avant, `LapRecorder` réduisait les 4 surfaces à « deux roues hors piste ». Un
   vibreur sous une seule roue disparaissait. Elles vont maintenant jusqu'à `CornerPass` : part de la zone sur vibreur,
   part de surface inconnue.
2. **Inconnu ≠ hors piste.** `TyreSurfaces.wheelsOff` comptait un code non documenté comme une sortie. Il ne compte
   plus que l'herbe, la terre, le sable et la neige. Deux codes inconnus donnent « surface incertaine » (`null`).
3. **null ≠ propre prouvé.**
   - Les références de virage prennent d'abord les passages prouvés sur la piste.
   - Une surface inconnue (formats A/B) n'est retenue que s'il n'y en a pas assez.
   - Une sortie mesurée n'est jamais retenue.
   - La mémoire voiture × circuit n'accepte plus un tour avec sortie mesurée comme référence.
4. **Règlement contredit par GT7** (#36-D2 : « 6 tours » déclarés, 14 annoncés et courus).
   - La session n'est jamais « même épreuve » ; elle est mise en quarantaine avant apprentissage.
   - Ni relais, ni arrêts, ni prévision, ni gomme n'en sont appris.
   - Le rythme et la consommation restent appris.
   - La raison s'affiche dans la base de stratégie.
   - Une archive ancienne est détectée aux tours courus.
5. **Données pour la motricité.** `LapSample` garde le glissement des 4 roues, le rapport, le régime, l'accélération
   obtenue et les températures.

## Sprint B — motricité à commande comparable (commit 33538b6)

`TractionMonitor`, raccordé à `DriverCoach` et `RaceController`.

**Fenêtres.**
- Prises dans chaque virage, entre la vitesse minimale et la fin de la zone.
- Paquets retenus : frein ≤ 5/255, accélérateur ≥ 30 %, vitesse ≥ 29 km/h.
- Rangés par conditions comparables : même virage, tranche d'accélérateur de 15 %, même rapport, tranche de vitesse de
  10 km/h, tranche de charge latérale de 3 m/s².
- **Les 4 roues doivent être sur l'asphalte.** Un vibreur, l'herbe ou un code inconnu écartent le paquet.
- Sans surface (formats A/B/~), la qualité est dite réduite.

**Comparaison.**
- Relais en cours. Écartés : premier tour du relais, tours d'arrêt et suivants, incidents déclarés, tours rejetés par
  le coach (plus de 7 % du meilleur, stands).
- Référence : les 2 premiers tours retenus. Comparaison : les 2 derniers.
- Roues motrices : celles qui glissent nettement plus en réaccélération ; sinon « non identifiées », et la plus forte
  est retenue.
- Dans une fenêtre, **perte** = accélération obtenue plus faible d'au moins 0,3 m/s², **et** glissement des roues
  motrices en hausse d'au moins 0,03 **ou** TCS en hausse d'au moins 15 points.
- Virage retenu s'il montre la perte sur **chacun** des deux derniers tours.
- Perte suspectée : au moins 2 virages, et au moins un tiers des virages comparés.

**Niveaux.**
- `INSUFFICIENT` : moins de 4 tours, ou moins de 2 virages comparables, ou pneus plus froids de 10 °C.
- `STABLE`.
- `DEGRADATION_SUSPECTED`.
- `DEGRADATION_REPEATED` : deux constats successifs.
- Retour au calme après deux tours stables.

**Causes écartées.**

| Situation | Effet |
|---|---|
| Vibreur, herbe, terre, code inconnu | Paquets écartés |
| Autre rapport, autre vitesse, autre tranche d'accélérateur | Fenêtres non comparées |
| Pneus froids | Comparaison suspendue |
| Une seule sortie ratée | Pas une tendance |
| Accélération plus faible sans patinage ni TCS (aspiration, moteur, trajectoire) | Explicitement pas une perte de motricité |
| Premier tour, arrêts, incidents | Écartés |

**Sorties.**
- **Radio** : seulement à un changement de niveau, dans une fenêtre sûre du pacer.
  - « Les pneus semblent perdre de la motricité en sortie de virage. Je surveille. »
  - Puis « Motricité en baisse confirmée sur N virages, environ X dixièmes par tour. J'en tiens compte pour la
    stratégie. »
  - **Jamais de « Box » direct** : la perte répétée entre dans la stratégie, qui seule compare le temps restant au
    coût de l'arrêt.
  - Relais incertain : journal seulement.
- **Arbitrage** (#36-D4) : le conseil « dose l'accélérateur, ça patine » n'est plus donné quand ce patinage est mesuré
  comme une perte de motricité à geste égal.
- **Écran** : la carte Virages d'Analyse affiche le niveau, les virages, et les écarts de glissement, d'accélération et
  de TCS.
- **Journal** : chaque changement de niveau, avec tours comparés, virages, perte par tour et qualité de surface.

## Découvertes réelles et limites

- **Le cas Porsche / Interlagos du 26/10** (−1,45 s par tour, TCS stable) **ne peut pas être rejoué** : les journaux
  ne gardent que des agrégats par tour, pas les fenêtres à 60 Hz. Le nouveau moteur aurait pu le classer « accélération
  plus faible sans patinage : pas une perte de motricité », mais ce n'est pas démontrable sur ce journal.
- Le moteur ne voit que les tours que le coach analyse, et seulement si le circuit a une carte des virages (un tour
  propre suffit à la créer).
- Les seuils (0,3 m/s², 0,03 de glissement, 15 points de TCS, 10 °C) sont **choisis pour les tests**. Ce ne sont pas
  des valeurs GT7. Il faudra les recalibrer.
- Freinage, sous-virage, survirage, delta personnel en direct et enregistreur 20–60 Hz (Sprints C et D) : **pas faits**.
- Aucune usure en %, aucune gomme montée, aucun écart adverse : ces données ne sont pas dans l'UDP.

## Protocole PS5 (HUD désactivé, comme d'habitude)

1. Course de **10 tours ou plus**, même voiture et même circuit, pneus d'usure forte (multiplicateur ×3 ou plus), sans
   arrêt.
2. Pendant la course, ne rien changer. Noter à quel tour tu sens la voiture patiner davantage en sortie de virage.
3. Après l'arrivée :
   - Analyse → carte Virages : lire « Motricité en sortie ».
   - Exporter le journal JSON et chercher les lignes « Motricité : ».
4. Renvoyer le journal, le format des paquets (Atelier : 296 ou 368 octets) et ton ressenti tour par tour.
   - Concordance attendue : « perte suspectée » vers le tour où tu as senti la perte, et aucun constat sur une course
     de pneus durs ×1.
5. En option, une seule fois, hors course ordinaire : une capture du HUD en fin de relais servira à vérifier, sans jamais
   devenir un prérequis.
