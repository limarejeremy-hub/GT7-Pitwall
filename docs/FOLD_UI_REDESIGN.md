# Pitwall : interface Fold / PSVR2 — première refonte

## Principe
Le pilote utilise la PSVR2 ou GT7 sans HUD. L'écran intérieur du Galaxy Fold doit donner en un regard les informations utilisables et vérifiées, sans défilement en paysage quand l'espace le permet.

## Navigation
- **Live** (par défaut) : position, tour, circuit, meilleur/dernier chrono, autonomie et verdict carburant, gomme déclarée, état d'adhérence, dernière consigne de l'ingénieur, régularité, prochain arrêt.
- **Briefing** : règlements, courses Sport et dossier pré-course existants.
- **Analyse** : Driver DNA, progression, secteurs, circuits, télémétrie lisible, historique des tours.
- **Historique** : archive actuelle, chronologie et exports conservés.
- **Atelier** : connexion PS5, bouton démarrer/arrêter, voix et réglages, télémétrie brute, outils expérimentaux, Discord, IA, Hunter. Aucune fonction héritée supprimée.

## Règles de données
- Le carburant est mesuré. Les tours d'autonomie sont **estimés** et le verdict est celui de FuelStrategy ; pas d'annonce répétée inventée.
- Les pneus sont issus de la **déclaration du pilote**, le pourcentage d'usure n'existe pas en UDP GT7 : ne pas afficher de chiffre inventé.
- Pas d'écart réel au leader ni de pneus des adversaires via la télémétrie ; ne pas simuler des valeurs.
- Position affichée comme mesurée ou déclarée (ou inconnue) selon le détecteur existant.
- Éléments graphiques 3D/volume, dont le disque pneu Compose, sont **purement décoratifs** : aucun indicateur de mesures.
- Les vrais conflits règlementaires restent dans le briefing et bloquent les décisions de stand ambiguës.

## Validation
Compilation Android et tests du coeur via GitHub Actions. À vérifier ensuite sur Fold fermé et déplié : pas de rognage du Live, nav, connexion PS5 en Atelier, annonces de l'ingénieur et historique persistants. Les essais PS5/PSVR2 sont indispensables avant fusion dans la branche principale.
