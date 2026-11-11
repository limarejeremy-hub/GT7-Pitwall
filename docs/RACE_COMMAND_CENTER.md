# V506 : refonte visuelle, « Race Command Center »

Demande du pilote (11/11) : que Pitwall ressemble à un vrai système d'ingénierie de course, lisible d'un coup d'œil sur
le Galaxy Fold pendant qu'il conduit en regardant la télévision.

## 1. Audit (avant)

- **Technique** : Jetpack Compose + Material 3, une activité, toute la logique dans `core` (pur Kotlin, testé).
  `PitwallScreen.kt` : 2 579 lignes, 45 cartes.
- **Navigation** : 6 onglets (Live, Télémétrie, Briefing, Analyse, Historique, Atelier), symboles texte, cibles
  d'environ 40 dp, barre de titre permanente.
- **Hiérarchie** : sur « Live », sept panneaux de même poids, aides en 10-11 sp ; une alerte (carburant, télémétrie
  perdue) ne changeait pas l'écran.
- **Rangement** : « Atelier » mêlait connexion, règlement, sondes expérimentales, IA et Discord.
- **Données réellement disponibles** : position (GT7 ou déclarée), tour, chronos, secteurs Pitwall, niveau de carburant,
  consommation mesurée, autonomie (calcul), température des 4 pneus, gomme **déclarée**, âge du train, plan d'arrêts
  (calcul), adhérence (estimation sur les chronos).
- **Non transmis par GT7** : usure des pneus, écarts avec les autres voitures, météo. Jamais affichés comme des valeurs.

## 2. Deux propositions

Maquettes (données de démonstration, **pas des captures de l'application**) :
`docs/design/v506-proposition-a-carbon-grid.png`, `docs/design/v506-proposition-b-apex-focus.png`.

| Critère | A « Carbon Grid » (mur de 20 tuiles) | B « Apex Focus » (hiérarchie, 3 instruments) |
|---|---|---|
| Lecture en un coup d'œil, téléphone à 60-80 cm | 20 chiffres de même taille | Position très grande, 3 instruments |
| Priorités de course | Tout au même niveau | Position > carburant / pneus / arrêt > Véga |
| Visibilité d'une alerte | Une tuile sur 20 change | Panneau entier + bandeau en haut |
| Compréhension par un débutant | Sigles | Jauge, voiture vue de dessus, « BOX T8 » |
| Densité | Meilleure | Moindre (le détail est dans TELEMETRY) |

**Retenue : B.** La densité de A reste disponible dans TELEMETRY.

## 3. Ce qui est implémenté

### Identité (`ui/theme/Theme.kt`)
Noir carbone et anthracite ; **un** accent de marque (orange Pitwall) ; couleurs d'état réservées à leur sens : vert
bon, jaune attention, rouge critique, violet meilleur temps, cyan Véga. Chiffres à chasse fixe (« tnum »). Aucune police
ni bibliothèque ajoutée.

### Navigation (`ui/PitwallShell.kt`)
Cinq espaces : **RACE, STRATEGY, TELEMETRY, VEGA, GARAGE**. Rail latéral sur le Fold déplié (largeur ≥ 600 dp), barre
basse sur l'écran extérieur. Cibles de 60 à 68 dp, pictogrammes au trait dessinés dans le code. Pastille d'alerte sur
RACE quand on regarde un autre espace. Retour Android : revient à RACE. Glisser : RACE ⇄ TELEMETRY (inchangé).

| Espace | Contenu (toutes les cartes existantes sont conservées) |
|---|---|
| RACE | Race Command Center |
| STRATEGY | Courses Sport de la semaine, règlement et plan (`RaceSetupCard`), carburant, pneus, carrière |
| TELEMETRY | En direct (écran V496), Analyse (pilotage, virages, tours, carte, voiture), Historique |
| VEGA | Radio (messages et commandes), micro et mot de réveil, Discord, IA (cloud, ChatGPT, Nano), personnalité |
| GARAGE | Connexion PS5, historique, profil ; Lab : diagnostics radio, bancs, sondes, Black Box, traces, base |

### Écran RACE (`ui/RaceCommandCenter.kt`, modèle `core/display/RaceCommandBoard.kt`)
- **Ligne d'état** : liaison GT7 (en direct, recherche, perdue depuis N s, arrêtée), épreuve / circuit / voiture connus,
  état réel de Véga (à l'écoute, en veille, micro coupé…).
- **Position, tour, dernier tour** (meilleur en violet, écart).
- **Carburant** : arc = niveau mesuré ; chiffre = autonomie en tours (calcul) ; étiquette de risque (suffisant, juste,
  arrêt nécessaire, critique) ; niveau, consommation mesurée, reste à couvrir.
- **Pneus** : voiture vue de dessus, température mesurée par roue avec sa couleur de repère ; gomme et son statut
  (déclarés, à confirmer, non déclarés) ; âge du train ; adhérence (estimée) ; **usure : indisponible**.
- **Prochain arrêt** : « BOX T8 », « BOX CE TOUR », « AUCUN ARRÊT », « PLAN INDISPONIBLE », « ARRÊT T7 ? » ; quoi
  faire à l'arrêt ; frise des relais ; arrêts faits ; gommes imposées **seulement si le règlement en impose** ;
  « règlement à vérifier » s'il est incomplet ; conseil de gomme de départ (conseil, jamais la gomme montée).
- **Véga** : dernier message, état.

### Intelligence visuelle (testée dans `RaceCommandBoardTest`)
| Événement réel | Réaction |
|---|---|
| Carburant critique (moins d'un tour, ou dernier tour pour rentrer) | Bandeau rouge qui pulse lentement, panneau cerclé |
| Arrêt au prochain tour / ce tour | Bandeau jaune puis rouge, panneau cerclé |
| Télémétrie perdue | Bandeau « TÉLÉMÉTRIE PERDUE », valeurs **atténuées (figées)**, aucune alerte de course sur des données périmées |
| Donnée inconnue | « indisponible » ou « — », jamais 0 |
| Arrêt contesté (V503) | « ARRÊT À CONFIRMER » |
| Pneu au-dessus du repère très chaud | Bandeau et panneau cerclé, avec la température mesurée |

Seule une alerte critique est animée (pouls de 0,7 s) ; les changements de couleur et de jauge sont des transitions
courtes. Rien ne bouge en l'absence d'événement.

### Galaxy Fold
- Plein écran immersif (barres d'état, de navigation et barre des tâches masquées ; un glissement depuis un bord les
  montre un instant) ; réappliqué au retour dans l'application.
- Interface entièrement dans la zone sûre (`WindowInsets.safeDrawing` : barres et découpe de l'écran).
- Déplié : rail + grille qui remplit l'écran, sans défilement. Écran extérieur : une colonne défilante, mêmes
  informations, Véga et prochain arrêt en premier.
- Instruments dimensionnés en dp : la taille de police du système ne casse pas la mise en page.
- **Non traité** : posture à demi plié (charnière au milieu) ; l'écran se comporte comme déplié.

## 4. Ce qui ne change pas
Télémétrie UDP, moteur de course, stratégie, radio, voix, Discord, IA : aucun changement. `RaceCommandBoard` ne fait que
lire. Aucune dépendance ajoutée, aucun service payant.

## 5. Performances
Le modèle RACE est recalculé seulement quand l'état ou la connexion changent (`remember`). Une seule animation continue,
et seulement pendant une alerte critique. Fond noir (AMOLED). **Non mesuré sur l'appareil** : fluidité et consommation
sont à vérifier sur le Fold.

## 6. Validation
- `RaceCommandBoardTest` (8) : course calme, données inconnues, carburant critique, fenêtre d'arrêt, télémétrie
  perdue, arrêt contesté et pneus chauds, ordre des alertes et obligations, course terminée.
- Suite JVM complète : 902 tests, 0 échec. Compilation Android et APK : CI.
- **Aucun émulateur ni appareil ici : pas de capture réelle de l'application.** La validation visuelle (Fold déplié
  portrait et paysage, écran extérieur), la PS5 et Discord restent à faire avec le pilote.
