# Zero Touch Sport — sources publiques et cache

## Défaut reproduit le 26 octobre 2026
L'APK 258 affichait « Programme inconnu » malgré une requête GTPlanet récente : les deux flux GTPlanet dépendaient du même site et ne publiaient pas encore les courses de la semaine.

## Correction Android
- **Victory** : page publique `https://victorydash.io/daily-races`, accessible sans clé ni compte. JSON-LD SportsEvent pour A/B/C (voiture, circuit, dates), plus cartes HTML (tours, pneus autorisés, multiplicateurs).
- **DG EDGE** : source supplémentaire expérimentale. Dans le test réseau GitHub Actions du 26/10, l'index répondait **HTTP 403**. Aucun contournement : sur le Fold elle est utilisée uniquement si accessible publiquement.
- **GTPlanet** reste en place ; **GT GridStats** reste facultatif avec clé personnelle.
- Cache privé Android, synchronisation non bloquante et mise à jour périodique.
- Validation stricte des 3 courses et de la semaine ISO, pas de succès trompeur sur une réponse HTTP 200 de la semaine précédente.
- Les conflits entre sources bloquent l'application des champs concernés.

### Limites
Victory décrit les gommes **autorisées**, pas les **gommes obligatoires**. Une source supplémentaire explicite est nécessaire pour renseigner une obligation. Pour la Course C du 26/10, RM et RS sont publiés comme autorisés dans Victory, mais leur obligation ne doit pas être inventée depuis cette seule page.

## Vérifications
- Tests unitaires : `VictoryDailyFeedTest`, `DgEdgeFeedTest` et historiques.
- Test réseau GitHub Actions : Victory HTTP 200, HTML ~360 kB, JSON-LD et cartes A/B/C ; DG EDGE HTTP 403 depuis le runner.
- Compilation Android et essai réel sur Fold/PS5 restent les critères de validation finale.

Usage strictement personnel, non commercial, de pages publiques. Pas d'extraction d'API cachée, de contournement ni de redistribution centrale des pages.
