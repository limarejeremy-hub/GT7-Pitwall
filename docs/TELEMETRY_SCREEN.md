# Écran 2 « Télémétrie » (V496)

Maquette validée par le pilote le 09/11. Onglet « Télémétrie » dans la barre du bas, et glissement horizontal entre
« Live » (écran 1) et « Télémétrie » (écran 2). Deux colonnes sur le Fold déplié (≥ 720 dp), une sur l'écran extérieur.

| Zone | Donnée | D'où elle vient |
|---|---|---|
| Tour en cours | chrono | GT7 (`currentLapTimeMs`, si la version du jeu l'envoie ; sinon « — ») |
| Dernier / meilleur tour | chronos | GT7, sinon tours mesurés par Pitwall |
| Secteurs (5 derniers tours) | violet = meilleur de la session, vert = mieux qu'au tour précédent, jaune = plus lent | secteurs **Pitwall** (`LapRecord.sectorsMs`), pas les secteurs officiels du jeu |
| Pneus | 4 températures colorées (froid bleu, idéal vert, chaud orange, très chaud rouge) | GT7 (mesuré) ; repères **généraux** < 60 °C, 60–95, 95–105, > 105 |
| Gomme | anneau de couleur (tendres rouge, mediums jaune, durs blanc, intermédiaires vert, pluie bleu), âge du train | registre des arrêts + déclaration du pilote ; « non confirmée » si supposée |
| Tendance du relais | s/tour, pente des chronos des tours propres du relais | **estimation** : GT7 ne transmet pas l'usure |
| Carburant | %, conso %/tour (5 derniers tours sans ravitaillement), autonomie | GT7 + tours mesurés |
| Relais | une case par tour, couleur de la gomme, ▼ arrêt, cadre orange tour en cours | règlement + registre |

Toute valeur absente s'affiche « — » : rien n'est inventé. Calculs en Kotlin pur (`core/display/TelemetryBoard`),
`TelemetryBoardTest` (7). **Non vérifié** sur le Fold ni en course.
