# Croisement des données GT7 — audit des 13 croisements, Sprint A (intégrité) et Sprint B (motricité)

Issues #35 et #36. Branche `claude/v436-tyre-traction`, partie de `claude/v426-radio-hotfix` (APK #432, radio validée).
Données utilisées : **UDP seulement**. Rien ne vient du HUD, de Remote Play, d'une image, d'un PC ni d'un autre matériel.

Tout ce qui suit est vérifié par des tests JVM et par la compilation Android. **Rien n'est validé sur PS5** : les seuils
sont des valeurs de départ, à recalibrer sur de vraies sorties de virage.

## Progression 13/13 (issue #36, périmètre global)

États :
- **à vérifier** : données présentes, diagnostic pas encore construit ;
- **partiel** : des briques existent, le croisement complet n'est pas fait ;
- **implémenté** : code et tests JVM ;
- **testé sur PS5** : vérifié en jeu ;
- **impossible sans source** : la donnée n'existe pas dans l'UDP.

**Aucun croisement n'est encore « testé sur PS5 ».**

| # | Croisement | Lot | État | Où dans le code |
|---|---|---|---|---|
| 1 | Accélérateur × glissement × surfaces × TCS × accélération obtenue ⇒ motricité | 1 | **implémenté** (25 tests, corrections de la revue #37 comprises) | `TractionMonitor`, `DriverCoach`, `RaceController.tractionCheck` |
| 2 | Frein × décélération × blocage × surface ⇒ efficacité de freinage | 1 | **partiel** : pédale (0x92) × décélération × blocage implémentés (JVM) ; frein **appliqué** (0x13D) seulement observé ; Analyse seulement | `BrakingMonitor`, `DriverCoach.braking` |
| 3 | Braquage × lacet × trajectoire ⇒ sous-virage / survirage | 1 | **implémenté** (JVM), format C seulement, Analyse seulement | `BalanceTrend` |
| 4 | 4 températures × blocages et patinages par essieu ⇒ stress thermique | 1 | **implémenté** (JVM), Analyse seulement ; sans le sens gauche/droite | `ThermalStress` |
| 5 | Temps par virage × causes mesurées ⇒ où et combien la voiture perd | 1 | **implémenté** (JVM) | `CornerReport`, `TyreOrDriving`, `tractionLossMs` |
| 6 | Arrêt avec ou sans pneus × carburant ⇒ séparer effet pneus et effet masse | 2 | partiel | `TyreChangeHunter` (soupçon thermique), `CompoundBook` (pente carburant), gain de pneus neufs dans `TyreModel.pool` |
| 7 | Perte par tour × tours restants × coût d'arrêt ⇒ Box ou continuer | 2 | partiel | `StrategyOptions`, `TyreScenarios` ; la motricité répétée y entre (`degrading`) |
| 8 | Voiture × circuit × version × règlement × multiplicateurs ⇒ historique comparable | 2 | **implémenté** (JVM) ; version physique encore déclarée à la main | `Compatibility`, `SessionRecord.rulesQuarantine` (Sprint A) |
| 9 | Position X/Z × temps ⇒ delta en direct contre son propre meilleur tour | 3 | à vérifier | rien encore ; `LapRecorder` et `TrackProjection` le permettent |
| 10 | Rapport × régime × accélérateur × accélération × glissement ⇒ rendement par rapport | 3 | partiel | les fenêtres de `TractionMonitor` sont déjà par rapport ; aucun bilan par rapport |
| 11 | Hauteurs de suspension × caisse × heave/sway/surge × vibreur ⇒ talonnage | 3 | à vérifier sur PS5 | champs lus (`suspensionHeight` 0xC4, `bodyHeight` 0x38, 0x130–0x138 en B/~/C), jamais utilisés |
| 12 | Rang 0x84 × participants 0x86 ⇒ places fiables | 3 | **implémenté** pour les places ; **impossible sans source** pour les écarts en secondes | `PositionTracker` |
| 13 | Consigne radio × passages avant/après × état des pneus ⇒ la consigne a-t-elle aidé ? | 3 | partiel | `DriverCoach` (vérification sur 3 passages, virages « bloqués »), arbitrage motricité (Sprint B) |
| 14 | Accélérateur × **couple délivré (0x154)** × rapport × glissement × TCS × accélération × surface | 1 | **expérimental** : décodé, **observation seulement** (verdict invariant, testé) | `engineTorqueNorm`, `TractionMonitor`, `TorqueProbe` ; voir [UDP_FIELDS.md](UDP_FIELDS.md) |

### Détail par croisement

**1. Motricité.** Détail plus bas (Sprint B).
- Implémenté et testé sur données synthétiques.
- Non calibré : il manque une vraie trace PS5.

**2. Freinage.**
- Sources : frein 0x92, vitesse 0x4C (décélération = Δv entre paquets), glissement avant (< 1 = blocage), surfaces,
  TCS/ASM.
- Déjà en place : début de freinage, pression maximale, montée en pression, blocage, causes « freinage trop tôt/tard ».
- Manque : la décélération obtenue à pression comparable, au même endroit et sur la même surface (même méthode que le
  n°1, côté frein). Sans cela, impossible de séparer « freiné trop tard » de « grip avant moindre ».
- Limite : GT7 ne transmet pas la température des freins, donc aucune « usure des freins ».
- Cas négatifs à tester : freinage sur vibreur, freinage en appui, pneus froids.
- Coût Fold : du même ordre que le n°1.
- Prochaine étape (lot 1).

**3. Rotation.**
- Le rapport lacet mesuré / lacet attendu existe, mais seulement en format C (angle des roues + empattement).
- En format B/~, l'angle de direction (0x128) n'est pas encore utilisé.
- Angle de dérive (vitesse dans le monde + quaternion) : **convention des axes non vérifiée**. Il faut une trace PS5
  avant de le calculer ; sinon le signe serait deviné.
- Ne jamais présenter un patinage longitudinal comme une dérive latérale.

**4. Stress thermique.**
- Déjà fait : écarts avant/arrière et gauche/droite (réponse pneus), tendance par tour (`GripDiagnosis`), alertes de
  `TyreCoach`.
- Manque : le croisement avec le sens du virage et la charge latérale.
- Limite : le signe gauche/droite de la charge latérale (vitesse × lacet) reste à confirmer en jeu (note de
  `Dynamics.kt`).
- Une température haute n'est jamais une usure.

**5. Temps perdu.**
- Bilan par virage (PR #31) et pneus ou pilotage.
- La motricité convertit maintenant sa perte en dixièmes par tour, sur les virages concernés.

**6. Pneus ou carburant.**
- Briques en place : soupçon de changement de pneus par saut thermique (toujours à confirmer par le pilote) et pente
  « carburant » apprise entre relais.
- Manque : la motricité avant/après un arrêt **sans** pneus (même masse retirée) comparée à un arrêt **avec** pneus.
  Il faut des courses avec les deux types d'arrêt.

**7. Box ou continuer.**
- La comparaison temps restant / coût d'arrêt existe ; la motricité répétée y entre comme « pneus dégradés ».
- Manque : convertir la perte mesurée en pente pour l'intervalle d'incertitude.
- Aucun « Box » ne vient de la motricité seule (vérifié dans le code).

**8. Historique comparable.**
- Compatibilité voiture, circuit, multiplicateurs, version déclarée.
- Quarantaine des règlements contredits par GT7 (Sprint A, testée).
- Limite : la version physique (1.71) n'est pas lue dans l'UDP. Elle reste déclarée dans le profil.

**9. Delta personnel.**
- Données présentes : X/Z à ~60 Hz et tour de référence enregistrable.
- Il faudrait garder le meilleur tour propre (≈ 5 400 échantillons pour 90 s) et projeter la position courante dessus.
- À faire au lot 3. Afficher « contre ton meilleur tour », jamais un écart adverse.

**10. Rendement par rapport.**
- Les fenêtres de motricité sont déjà séparées par rapport.
- Il manque un bilan « rapport × section » (coupure TCS, patinage, régime hors plage).
- Découverte : le régime **ne doit pas** servir de critère de comparaison, car un patinage le fait monter à vitesse
  égale.

**11. Suspension.**
- Champs lus mais d'unité et d'axes non vérifiés.
- Aucune analyse avant une trace PS5 sur vibreurs connus. Pas de diagnostic de casse.

**12. Places.**
- Positions stables, groupées, arrivée figée.
- Les sondes Gap Hunter (73 000 paquets en format C) n'ont trouvé aucun champ d'écart : **écarts en secondes
  impossibles sans source**. Aucun n'est inventé.

**13. Effet des consignes.**
- La vérification après trois passages existe ; un virage sans amélioration n'est plus conseillé.
- Le patinage attribué aux pneus n'est plus reproché au pilote.
- La causalité n'est pas démontrable (le pilote change aussi pour d'autres raisons). Ce sera dit tel quel.

### Revue de la PR #37 : corrections

| Point | Correction | Test |
|---|---|---|
| P0-A : diagnostic périmé resté « confirmé » | Comparaison suspendue = niveau actif « insuffisant », ancien niveau en simple historique ; la stratégie ne compte qu'une perte répétée, active et à surfaces connues (`strategyEligible`) | `p0aASuspendedComparisonNeverStaysConfirmed` (pluie, pneus froids, données manquantes) |
| P0-B : conseil en retard d'un tour | La motricité est évaluée par le coach, juste après l'ajout du tour et avant son conseil ; la radio ne fait que lire | `p0bTheCoachNeverBlamesTheThrottleOnTheLapTheTractionLossIsSeen` (circuit simulé, bout en bout) |
| P0-C : trop affirmatif sans surfaces | Jamais « répétée » sans surface des roues ; annonce « à confirmer » ; jamais dans la stratégie | `p0cWithoutWheelSurfacesALossIsNeverConfirmed` |
| P1-D : temps des virages attribué à la motricité | Plus de dixièmes à la radio ; « temps des virages concernés » au journal. Perte répartie : « pneus possibles » ; « probables » seulement si la motricité baisse ; « non démontrés » si elle est stable | `p1dASpreadLossIsOnlyProbablyTheTyresWhenTractionAgrees` |
| P1-E : 0x154 | Décodé, expérimental (voir [UDP_FIELDS.md](UDP_FIELDS.md)) | `ExperimentalFieldsTest`, `TorqueProbeTest` |

Défaut trouvé en écrivant ces tests : la sortie du dernier virage, qui passe la ligne, était ignorée par la motricité
(les passages la prenaient en compte). Corrigé.

### Croisements 2 à 4 (lot 1)

- **Freinage (n°2).** Même méthode que la motricité, côté frein.
  - Fenêtres comparées : avant la vitesse minimale, frein ≥ 15 %, accélérateur relâché, même tranche de pression
    (15 %), de vitesse (10 km/h) et de charge latérale, 4 roues sur l'asphalte.
  - Signal : décélération plus faible d'au moins 0,5 m/s², ou blocage avant en hausse d'au moins 4 points sans
    meilleure décélération.
  - Écartés : vibreur, autre vitesse (= freiné à un autre endroit, rôle du coach), pneus froids, pluie, freinage raté
    isolé.
  - Jamais « freins usés » : GT7 ne transmet pas leur température.
- **Équilibre (n°3).** Rapport de rotation au milieu des virages, début contre fin de relais.
  - Majorité des virages en baisse d'au moins 8 % : sous-virage en hausse ; en hausse : survirage.
  - Format C seulement. Passages sur vibreur (plus de 20 % de la zone) et sorties de piste écartés.
- **Thermique (n°4).** Pneu le plus chaud par rapport aux trois autres, son évolution, blocages avant et patinages
  arrière.
  - Toujours suivi de « Température élevée ≠ usure ». Aucune conclusion sur le sens du virage (signe non vérifié).
- Tests : `HandlingCrossTest` (4). Affichage : carte Virages d'Analyse. **Aucune radio** avant validation PS5.

### Veille GTPlanet (relayée dans #36 le 27/10)

GTPlanet n'est pas joignable depuis l'environnement de développement. Ces constats sont repris **tels que rapportés**
dans l'issue #36, avec leur source, et **pas relus à la source**.

| # | Constat rapporté | Source citée (date) | Niveau de preuve | Croisements | Ce qu'en fait Pitwall |
|---|---|---|---|---|---|
| 1 | 0x13D = frein réellement appliqué (après ABS), distinct de la pédale 0x92 ; nul en replay | GTPlanet, Mumster, message 386 (05/10/2026), environ 400 freinages | Essai communautaire | 2 | Observé dans `BrakingMonitor` (rapport appliqué/demandé, replay détecté), sans effet sur le verdict |
| 2 | 0x154 = couple ou charge moteur délivré ; chute 0,93 → 0,57 après un dommage moteur | Mumster, message 386 (05/10/2026), BRZ S, Barker, WRX Gr.3 | Essai communautaire | 1, 14 | Observation seulement ; `TorqueProbe` pour valider |
| 3 | La 1.71 (20/08/2026) change glissement, adhérence, chauffe et usure, TCS, ABS ; la 1.72 (15/10) change suspension et différentiel par défaut Gr.1–Gr.4 | Notes officielles, fil des changements non documentés | Officiel + communauté | 1–4, 8 | Version de GT7 déjà dans la compatibilité ; protocole de calibration ci-dessous |
| 4 | 0x15C = chrono du tour en cours (C) | MacManley, messages 335 et 337 (03/2026) | Confirmé chez nous le 21/10 | 9 | Décodé ; delta personnel pas encore fait |
| 5 | X/Y/Z seuls n'identifient pas le circuit ; certains tracés ne divergent qu'à la voie des stands | Lord Grey, Bornhall, messages 364 et 369 | Communauté | Reconnaissance | Signatures géométriques déjà prudentes ; à garder en tête pour SUGO |
| 6 | Replay ≠ direct : 0x13D et d'autres mesures moteur nuls en replay | Message 386 | Communauté | Tous | Replay détecté pour 0x13D ; étiquette direct/replay à généraliser |
| 7 | Endurance Le Mans : retour tour 250 → 248 et carburant 94,32 → 10,00 | Lord Grey, messages 388 et 392 (06/10/2026) | Deux fichiers | 6, 7 | Non traité ; le suivi de tours garde ses garde-fous (tour qui recule = nouvelle session) |
| 8 | Température « optimale » non universelle (75–87 °C non généralisable) | Simtezilo, message 325 | Communauté | 4 | `ThermalStress` ne compare que des écarts entre pneus, aucun seuil fixe |
| 9 | 0x13F, 0x140–0x14F, 0x150 actifs seulement sur certains hybrides et électriques ; à zéro sur 23 voitures thermiques (2,06 millions de paquets) | Lord Grey, Mumster, messages 379 à 385 | Communauté | 10 | Décodés, jamais utilisés pour identifier l'essieu moteur |
| 10 | Décalage de l'affichage et des appels au stand si l'on traite tout à 60 Hz ; GC Android délicat | EzioDash, messages 349 à 354 et 317 | Retour d'expérience | Tous | Analyses une fois par tour ; à instrumenter (âge des paquets, files) |
| 11 | Bibliothèques de référence entre pilotes (Tractivvity, Victory Dash) | Messages 350 et 357 | Annonces | 13 | Rien : pas d'accès ni de consentement |
| 12 | Pas d'usure ni de coordonnées adverses dans l'UDP ; le Live Timing API demande une autorisation | Message 384 (03/10/2026) | Communauté | 12 | Écarts adverses déclarés impossibles sans source ; aucun contournement |

### Protocole de calibration selon la version de GT7

1. Chaque session archive la version déclarée dans le profil. La compatibilité refuse déjà deux versions différentes
   déclarées.
2. **Première course sous une nouvelle version** (1.71, 1.72, puis les suivantes) : seuils de motricité et de freinage
   considérés **non calibrés**. Il faut une course de 10 tours ou plus hors ligne, voiture thermique Gr.3, puis la même
   en voiture électrique si possible, avec export du journal et de la carte « Champs GT7 expérimentaux ».
3. Ne jamais comparer des relais de versions différentes. Ne jamais former de seuil sur un replay.
4. À faire : rappel automatique « première course sous la version X » dès que la version déclarée change (pas encore
   codé).

### Plan des lots suivants
- **Lot 1** : n°2 (freinage à pression comparable), puis n°3 et n°4. Même méthode de fenêtres comparables que le n°1.
- **Lot 2** : n°6 et n°7 (motricité avant/après arrêt, pente mesurée dans la stratégie).
- **Lot 3** : n°9, n°10, puis n°11 et n°13 selon les traces.
- **Lot 0 (en continu)** : enregistreur compact opt-in (Sprint D). Sans trace PS5 réelle, aucun seuil ne peut être
  calibré.

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

**Ajouts après la mise à jour du cahier des charges.**
- Paquets sous correction ASM écartés.
- Pluie déclarée : comparaison suspendue. L'UDP ne transmet pas la météo.
- Pneus plus chauds de 15 °C ou plus : « surchauffe possible » est dit, sans être exclu.
- **Découverte : pas de tranche de régime.** Un patinage fait monter le régime à vitesse égale ; trier par régime
  empêcherait la comparaison (test `wheelspinThatRaisesTheRevsIsStillCompared`).
- Coût de calcul : **6,6 ms par tour de 5 400 paquets sur le PC de développement**, une fois par tour. Le coût réel
  sur le Fold n'est pas mesuré ; il faudra la mesure Black Box de l'Atelier pendant un essai.

**Causes écartées.**

| Situation | Effet |
|---|---|
| Vibreur, herbe, terre, code inconnu | Paquets écartés |
| Autre rapport, autre vitesse, autre tranche d'accélérateur | Fenêtres non comparées |
| Pneus froids | Comparaison suspendue |
| Une seule sortie ratée | Pas une tendance |
| Accélération plus faible sans patinage ni TCS (aspiration, moteur, trajectoire) | Explicitement pas une perte de motricité |
| Correction ASM | Paquets écartés |
| Pluie déclarée | Comparaison suspendue |
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
