# V507 : écran RACE en tableau de bord GT3

Demande du pilote (16/11) : « je veux que mon Fold se transforme en vrai dashboard de GT3 : rapport engagé, LED de
rupteur, position, temps au tour, secteurs, pneus… ».

## Ce qui est affiché (une seule page, sans défilement)

| Zone | Donnée | Source |
|---|---|---|
| Rampe de 15 LED | vert, jaune, rouge selon le régime ; **bleu clignotant** au moment de passer le rapport | seuils de rupteur **envoyés par GT7 pour la voiture** ; sans seuils, LED éteintes (jamais de seuil inventé) |
| Rapport | géant ; R en rouge ; bleu au rupteur ; « conseil GT7 : 5 » | paquet GT7 |
| Vitesse, régime, pédales | km/h, tr/min, accélérateur et frein | paquet GT7 |
| TCS / ASM | témoins allumés quand ils agissent | paquet GT7 |
| Position, tour | P7 sur 16, 5/11 | GT7 (ou déclarée à la radio, en gris) |
| Tour en cours | chrono | GT7 s'il l'envoie, sinon **mesuré par Pitwall** (paquets à 60/s), c'est indiqué |
| Delta | écart avec ton meilleur tour au même endroit, barre verte/rouge | **mesuré par Pitwall** : profil distance → temps du meilleur tour complet de la session ; estimation |
| Secteurs S1 S2 S3 | violet = meilleur de la session, vert = mieux que le tour de référence, jaune = plus lent ; secteur en cours cerclé | secteurs Pitwall (tiers de la distance, **pas les secteurs officiels**) |
| Dernier / meilleur | chronos | GT7 |
| Pneus | 4 températures colorées, gomme, âge du train, **usure n/d** | GT7 (températures), pilote (gomme) |
| Carburant | tours restants, niveau, conso/tour, risque | calcul Pitwall sur mesures GT7 |
| Arrêt | T8, dans 3 tours, quoi faire | plan Pitwall (calcul) |
| Moteur | eau, huile, turbo s'il existe | GT7 |
| Bas d'écran | alerte de course (deux critiques visibles ensemble), sinon le dernier message de Véga | |

Télémétrie absente ou figée : tout est atténué. Une valeur inconnue s'écrit « — ».

## Fold
- Déplié : plein écran, **sans rail de navigation** ; bouton « ☰ MENU » en haut à gauche pour l'afficher. Le rail se
  masque de nouveau en revenant sur RACE.
- Écran extérieur : même tableau en format étroit (pneus et carburant, puis arrêt et moteur), barre de navigation en bas.
- Tailles de texte **mesurées** (largeur et hauteur réelles des chiffres) : rien n'est tronqué, quelle que soit la
  taille de police du téléphone.

## Performance
Flux dédié à ~30 images/s (`PitwallEngine.dash`), séparé de l'état complet (20/s) : seuls les instruments rapides sont
redessinés. Seules animations continues : LED bleues au rupteur et alerte critique.

## Validation
- `GtDashTest` (6) : LED sur seuils GT7, aucune LED sans seuils, rapport et pédales, chrono + delta + secteurs sur
  une course simulée (tour plus lent : delta positif, secteur jaune ; plus rapide : delta négatif, violet), pas de
  delta sans tour de référence. Suite JVM complète : 910 tests, 0 échec.
- **Rendus de contrôle** (`docs/design/v507-gt3-*.png`) : produits avec le **vrai code Compose** de l'écran, rendu sur
  PC (Compose Desktop, moteur Skia comme Android), données de démonstration. Ce ne sont pas des captures du Fold :
  polices du PC, pas de barre système. Le rendu sur le téléphone reste à confirmer.
- Non vérifié : course réelle (PS5), seuils de rupteur réellement envoyés par GT7 pour chaque voiture, fluidité.
