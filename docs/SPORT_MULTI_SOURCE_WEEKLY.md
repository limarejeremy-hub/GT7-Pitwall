# Course Sport hebdomadaire : deux sources, pas de saisie répétée

## Contexte (26 octobre 2026)
GTPlanet RSS n'a pas systématiquement publié la nouvelle fiche à l'heure du renouvellement le lundi. Pitwall reste alors sur « programme inconnu ». GT GridStats publie déjà Race A/B/C avec piste, tours, pneus, multiplicateurs et règlement en JSON.

## Sources et flux
1. **GTPlanet** : flux RSS public, reste la source par défaut, aucune clé nécessaire.
2. **GT GridStats** : API documentée `GET https://gt-gridstats.com/api/dailies/this-week (domaine avec tiret ; corrigé le 26/10, le code appelait « gtgridstats.com »)`, avec `Authorization: Bearer`. **Opt-in** : aucun appel sans clé personnelle, jamais incluse dans l'APK, jamais envoyée à un autre site ou dans les journaux.
3. **Fusion champ par champ** : une source seule = probable ; deux sources qui concordent = confirmé ; conflit = inconnu et non appliqué. Une clé manquante ne bloque pas GTPlanet.
4. **Dates** : date de début issue du payload (lundi local), jamais le jour de téléchargement ; cache par semaine, édition expirée le lundi suivant. Les courses terminées ne peuvent pas polluer le nouveau règlement.
5. **Règles essentielles** : `required_compounds` n'est pas équivalent à `pit_constraint`. La course C peut indiquer 0 arrêt imposé directement tout en imposant **deux gommes**, ce qui requiert une transition aux stands pour satisfaire le règlement.

## Mise en service
Dans « Course & Stratégie », choisir Sport A/B/C, rentrer facultativement sa clé personnelle GT GridStats et toucher « Enregistrer et synchroniser ». Elle est chiffrée via Android Keystore (AES-GCM) sur le Fold. Sans clé, la récupération RSS GTPlanet continue à fonctionner comme précédemment. Toucher « Retirer » pour effacer la clé.

## Validation
- Tests unitaires du schéma réel documenté (A/B/C de la semaine 44/2026).
- Pas de valeur devinée si un champ est absent ; conflits non appliqués ; anciennes semaines refusées.
- Tests application Android et essais manuels sur le Fold nécessaires.
- La carte des circuits `course.csv` et la base véhicules restent sous la responsabilité de la branche Claude : quand SUGO manque, afficher son **nom connu** mais laisser l'**ID GT7 inconnu** (ne pas sélectionner un autre circuit).
- Si GridStats modifie son contrat, ne pas écraser un cache lisible avec un résultat vide.
- Attention aux quotas de l'API, conditions d'utilisation et tarifs du fournisseur : aucun abonnement requis par le code Pitwall, mais la création d'une clé est une action volontaire de l'utilisateur.

## Limites
Ce changement concerne les courses Sport A/B/C de la semaine. Il ne permet pas de déduire en direct le règlement à partir de l'UDP ; celui-ci ne l'envoie pas. La fiche publiée doit être vérifiée lorsqu'elle est « probable », et l'utilisateur peut corriger les règles de sa course.
