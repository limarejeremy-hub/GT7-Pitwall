# Opération Le Mans 2026 — Course Sport C (semaine du 23/11/2026)

## Règlement publié (REGLEMENT, jamais transmis par GT7)

| Champ | Valeur | Source |
|---|---|---|
| Circuit | 24 Heures du Mans Racing Circuit | gtsh-rank.com/daily, semaine du 23/11 |
| Tours | 14 | format identique publié par GTPlanet (22/06/2026) |
| Voitures | Gr.1 : BMW M Hybrid V8 '25, Ferrari 499P '23, Peugeot 9X8 '25, Porsche 963 '24, Toyota GR010 '21 | GTPlanet, racinggames.gg |
| Pneus | RM, IM, W | gtsh-rank |
| Carburant / usure | ×2 / ×4 | gtsh-rank |
| Arrêt obligatoire | 1 | gtsh-rank |
| Changement de pneus imposé | **aucune source ne l'impose** | — |
| Départ, BoP, dégâts | lancé, BoP actif, dégâts moyens ; réglages : répartition de freinage | gtsh-rank, GTPlanet |
| Météo | changement d'heure, risque de pluie (format de juin) | GTPlanet |

Fichier : `lab/le-mans/regles-sport-c-2026-11-23.json`. À vérifier sur l'écran de l'épreuve dans le jeu avant la course.

## Verdict d'enregistrement (23/11) : GO sous conditions

Vérifié dans le code de l'APK candidate. **Ce n'est pas une validation PS5/Fold.**

| # | Point | Constat (code) | Niveau |
|---|---|---|---|
| 1 | Reconnaissance du circuit | Géométrie du Mans présente (`TrackGeometry`, « c272b2 »), avec la garde de longueur 13,6 km (course du 26/10) | GO |
| 2 | Identification Gr.1 | Code voiture lu dans le paquet (MESURE). Les 5 Gr.1 sont dans `cars.csv` (3499 GR010, 3604 M Hybrid V8, 3605 499P, 3606 9X8, 3607 963 ; la trace du 28/10 est en 963) | GO |
| 3 | Règlement | Le flux Sport C peut le remplir (GridStats, GTPlanet, DG Edge). L'arrêt obligatoire et les pneus imposés sont des champs **séparés** : aucun changement de pneus n'est déduit de l'arrêt | GO, à confirmer dans la carte Course |
| 4 | Capture de la trace | Limites de 80 Mo et 4 h (≈ 15 Mo et 55 min attendus à 20 Hz). **Au plus 5 traces gardées.** La trace est coupée à chaque relance de l'app | GO si activée avant le départ et exportée après chaque course |
| 5 | Arrêt, plein et pneus | Plein mesuré (hausse du carburant). Pneus : déclaration du pilote, plus un indice de température | GO (plein), pneus déclarés |
| 6 | Position Sport | Rang à la ligne figé, révision et consolidation (V508/V511) | GO, vérifier sur le HUD |
| 7 | Discord et Véga sur une heure | Chien de garde toutes les 5 s avec reconnexion ; service au premier plan ; verrou de veille de 6 h ; écran maintenu allumé | GO, non éprouvé sur une heure réelle |
| 8 | Alertes carburant | « Carburant critique » de priorité CRITICAL ; elle préempte la file vocale (V511) | GO |
| 9 | Longue session | Aucun plafond sous 4 h | GO, à mesurer |
| 10 | Version installée | Nom affiché « 0.11.5 · V511 Candidate », identique depuis V511 ; le code de version est le numéro de build CI (590 pour la dernière APK) | GO, noter le numéro |

## Analyse des traces (Pitwall Lab)

```
lab endurance <trace> lab/le-mans/regles-sport-c-2026-11-23.json
lab vega <trace> lab/vega-golden/le-mans.json <rapport.md>
lab replay <trace> DAILY_C RACE
lab anomalies <trace>
```

Outils MCP correspondants : `lab_endurance` (avec `regles`), `lab_vega` (avec `scenarios: "le-mans.json"`),
`lab_replay`, `lab_anomalies`, `lab_compare`.

**`endurance`** lit les paquets sans le moteur :
- par tour : chrono (0x7C), carburant au début et à la fin, consommation, position 0x84, trous de flux ;
- arrêts : immobilisation en course ≥ 3 s, durée, carburant ajouté, indice de pneus neufs ;
- calculs : tours par plein, arrêts carburant nécessaires sur 14 tours ;
- contrôle du règlement : tours, arrivée, arrêt obligatoire, pneus non imposés.

Natures :
- `MESURE` : valeur lue dans le paquet ;
- `CALCUL` : opération du Lab sur les mesures ;
- `INDICE` : signature observée, pas une preuve. Exemple : les 4 températures retombent ensemble à 60 °C, vu une fois le 25/10, pneus changés confirmés par le pilote ;
- `REGLEMENT` : valeur publiée de l'épreuve ;
- `NON_MESURABLE` : météo, usure, gomme, écarts, validation de l'arrêt par GT7.

### Premiers constats sur les traces existantes

| Trace | Constat `endurance` |
|---|---|
| 06/11 (3 courses de 11 tours) | Arrêt de 7 s au tour 7 dans chaque course, **sans plein ni indice de pneus**. Un arrêt obligatoire peut donc se faire sans service. Le moteur avait planifié « fin du tour 7, obligatoire sans essence » |
| 28/10 (10 tours) | Arrêt de 10 s au tour 6 sans plein, puis arrêt de 12 s au tour 7 : plein de 56,7 L et indice de pneus neufs. Un fragment de 6 s de la session précédente est écarté |
| 19/11, 22/11 (7 tours) | Aucun arrêt ; 0 trou de flux en course |

`vega` Le Mans (12 scénarios), moteur déterministe :
- 22/11 : 6 réussis, 1 échoué, 5 non évaluables (course de 7 tours) ;
- 06/11 : 10 réussis, 1 échoué, 1 non évaluable.

**Nouveau défaut P2** : « Est-ce qu'il va pleuvoir ? » reçoit « Je n'ai pas compris ». Véga devrait répondre que GT7 ne
transmet pas la météo et demander au pilote ce qu'il voit.

Correctif du banc : un point `tour:N` n'est atteint que si N fait partie de la course. Auparavant, le « 8/7 » d'après
l'arrivée déclenchait `tour:8`.

## Limites

- **Pluie** : aucun champ météo dans l'UDP GT7. Seules la déclaration du pilote et ses observations comptent. Le radar
  météo du jeu n'est pas accessible.
- **Indice de pneus neufs** : établi sur une seule course réelle ; à confirmer à chaque arrêt par la déclaration.
- **Le Mans réel** : aucune trace de 14 tours n'existe encore. Tous les seuils restent à vérifier sur la course de
  référence.
