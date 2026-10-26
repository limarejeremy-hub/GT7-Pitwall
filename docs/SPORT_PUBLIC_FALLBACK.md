# Pitwall — Source Sport indépendante et sans clé API

Branche : `fix/zero-touch-sport-sources` (depuis `claude/zero-touch`).

## Défaut observé

Le 26 octobre 2026, le Galaxy Fold recevait la télémétrie PS5 mais affichait « Programme inconnu » pour A/B/C. Le flux GTPlanet de la catégorie (et son flux général de secours) n'avait pas encore de fiche valide pour la semaine.

Un accès HTTP 200 à un flux avec uniquement l'ancienne semaine **n'est pas** un succès métier. Il n'est pas autorisé à confirmer le règlement du jour.

## Correction

- Source secondaire réellement indépendante : pages publiques de DG EDGE pour les courses quotidiennes, consultées directement par le Fold.
- Lecture de l'index, découverte **strictement filtrée** des URLs des trois courses de la semaine ISO, puis lecture de leurs résumés publics. Aucun compte ni clé API.
- Extraction stricte de A/B/C, semaine, piste, classe/voiture, tours, pneus, multiplicateurs, BoP, départ, pneus obligatoires explicitement nommés.
- Aucun arrêt obligatoire inventé à partir de deux gommes imposées ; absent = inconnu.
- Si une carte manque, diverge de l'URL, ou n'a pas les valeurs minimales requises : échec contrôlé, ancien cache préservé.
- Cache local privé Android ; aucun relayage ni redistribution du HTML sur GitHub. Rafraîchissement quotidien si valide, toutes les 30 min quand la semaine manque.
- Fusion via `ScheduleBuilder` : sources en désaccord = champ non appliqué, déclarations confirmées du pilote préservées.
- Diagnostic « aucune fiche A/B/C exploitable pour la semaine » et erreurs de sources au lieu de « mise à jour réussie » sur simple HTTP 200.

## Sécurité et limites

Cette lecture est pour l'usage personnel et non commercial de l'utilisateur ; son rythme de requêtes est faible et aucun espace privé, premium ou API non autorisée n'est sollicité. Respecter les conditions du site et arrêter cette collecte si l'exploitant la restreint. La structure HTML peut changer : dans ce cas le parseur doit échouer, pas deviner des règles.

Le 26/10, les pages publiques montrent :
- A — SUGO, Nissan Fairlady Z (Z34) '08, 5 tours, SH, carburant ×1, pneus ×1 ;
- B — Big Willow, Gr.4, 7 tours, RH, ×1 / ×1 ;
- C — SUGO, Gr.3, 14 tours, RM et RS requis, ×2 / ×4.

Tests unitaires locaux : `DgEdgeFeedTest`. Une compilation CI verte et un essai réseau réel sur Android restent obligatoires pour valider le « zéro intervention » complètement. Ne pas fusionner les branches sans vérification PS5/Fold.
