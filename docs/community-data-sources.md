# GT7 Pitwall — Registre initial des données communautaires et des droits

État au **29 octobre 2026**. Document de recherche **sans import automatique des données dans l’APK**. Les règles de licences ne constituent pas une validation juridique du protocole GT7/Sony pour un produit commercial. Toute intégration passe d'abord par une revue de conformité fichier par fichier.

## Sources vérifiées et traitement prévu

| Source | Chemins utiles | Licence déclarée | Utilisation envisagée / restriction |
| --- | --- | --- | --- |
| [jbhoorasingh/gt7-datalogger-track-data](https://github.com/jbhoorasingh/gt7-datalogger-track-data) | `tracks/*.json`, `index.json` | **CC0 1.0** explicite dans `LICENSE` | Relevés de limites de piste, noms des virages, métriques de couverture et catalogue. Attention au mélange avec métadonnées Sony et autres contributeurs. |
| Même dépôt | `signatures.json` | **Dérivé mixte** : relevés CC0 + captures source MIT | Circuits reconnaissables par X/Z, chemin en ordre de conduite. **Ne pas traiter tout ce fichier comme exclusivement CC0** ; reporter MIT et attribution du fournisseur, garder références amont. |
| Même dépôt | `vendor/circuits.json`, `vendor/gt-telemetry-LICENSE` | **MIT** amont | Captures de 84 tours historiques transformés, provenance des signatures. Conserver notice. |
| [zetetos/gt-telemetry](https://github.com/zetetos/gt-telemetry) | `data/circuits/*.json`, `LICENSE` | **MIT** | Source originale de trajectoires mondiales X/Z, exploitable pour reconnaissance avec notice. Vérifier tout actif tiers avant redistribution. |
| [ddm999/gt7info](https://github.com/ddm999/gt7info) | `_data/db/`, `_data/dailyrace/`, `build/data.json` si disponible | **MIT-0** pour le dépôt/logiciel | Ids/noms voitures & pistes et historique épreuves : inventaire + tests de cohérence, sous réserve des droits sous-jacents, de la fraîcheur et des conditions de collecte. **Exclure `img/`, photos, logos, images Sony/GT7, polices, captures.** |
| [ransh2014/gt7telemtrace](https://github.com/ransh2014/gt7telemtrace) | Code local et formats de reconnaissance | **MIT** pour le code | Étudier algorithme d'empreintes (position + orientation) et format. **Ne pas aspirer la liste hébergée des utilisateurs, ghost laps, Top-10 ou API de classement** sous prétexte que le logiciel est MIT : droits d'usage/redistribution de la base distante non établis. |
| GTPlanet / DG EDGE / autres pages Sport | Fiches hebdomadaires | **À auditer site par site** | Utiliser prudemment des faits et sources avec provenance/date ; ne pas recopier une base entière ni automatiser une extraction commerciale sans vérifier les conditions et droits. |

Les licences MIT ne nécessitent pas de rendre public le moteur propriétaire de Pitwall, mais exigent de maintenir leurs notices de copyright/licence. MIT-0 est distinct de MIT (pas d'obligation de notice d'attribution). CC0 n'accorde ni droit de marque ni droits des tiers.

## Snapshot technique vérifié (sans copie des données)

Dépôt `jbhoorasingh/gt7-datalogger-track-data` HEAD observé : `80d0a1125a79d22a725289e05d03215b88f6559b`.

- `index.json` blob : `e6713d22eaebfb72d10f49bbb631e84a3c22c8f1`. **122 configurations**, **27 relevées**, **135 705 points** de relevé, **139 virages étiquetés**.
- `signatures.json` blob : `a1f99b5aad1c65a615917b66aab5a70cf927d6d9`. **80 signatures** (26 de relevés, 54 de captures), **78 chemins ordonnés**, pas tous sans ambiguïté.
- **SUGO** : entrée `official_id=4bf61b`, `slug=sugo-international-racing-course`, `length_m=3586` dans l'index ; `bundle=null` (pas de relevé détaillé de piste). Une **signature de capture** est bien présente, avec X de -534.1 à 540.3 et Z de -263.9 à 264.3, chemin ordonné. Longueur de la capture **3560 m**, différente de la longueur catalogue (3586 m) : tolérance et méthode de mesure à tester, **ne pas confondre avec preuve de circuit avant feu vert**. Notre application avait UNKNOWN à 702 m sur SUGO faute de référence comparable.

Dépôt `ddm999/gt7info` HEAD observé : `1db60393bdc1e9d286896b2e766b7e96de2b2e18`. Licence `LICENSE` = « MIT No Attribution ». Les images `img/` incluent explicitement des éléments GT7/tiers : exclues de l'import.

## Politique d'ingestion, mise à jour, refus

1. **Liste blanche exacte de chemins**, un seul type de licence et provenance par fichier / composante, SHA-256, version amont, source URL, date et statut `APPROVED | REVIEW | REJECTED`.
2. Pipeline de synchronisation **hors thread UDP**, avec téléchargement seulement de sources autorisées, validateurs JSON, schéma, IDs/layout, cohérence des unités et limites X/Z, vitesse, quaternion, duplicates, direction reverse, couverture, changements de version GT7. Ne jamais écraser un relevé utilisateur par une version externe ; cache local et mode offline.
3. Notices tierces explicites dans `THIRD_PARTY_NOTICES` généré à partir du registre. Limiter les fichiers embarqués aux données nécessaires ; pas de photos, replays/ghosts de joueurs, identifiants PSN, IP, noms de pilotes, images d'actifs GT7 ou ressources dont la licence de redistribution n'est pas vérifiée.
4. Aucune transformation de données communautaires en affirmation `SPORT_OFFICIAL`, `COMPOSE_PNEU_MONTE_CONFIRME` ou preuve de règle GT7 non télémétrée. Provenance distincte `COMMUNITY_REFERENCE`, `WEEKLY_EXPECTED`, `GT7_XZ_MEASURED`, `LAP_LENGTH_COMPATIBLE`, `DRIVER_DECLARED`, `INFERRED_MODEL`.
5. Tests : faux positifs sur circuit inverse / variantes, position de grille P1/P10/P20, départ dans les stands, trajectoire incomplète, SUGO vs circuits voisins, source manquante, licences invalides, données périmées, exactitude des virages, et régression Sport #51. Une signature de capture n'est pas un relevé de bordure/largeur.
6. Aucune nouvelle fonctionnalité en production sur seul test JVM : APK Android + rejouage traces puis PS5 terrain avant validation.

## Utilisation dans notre moteur (données ≠ stratégie)

`identité voiture GT7` + `position X/Z` + `référentiel des circuits` → identification piste/virages avec confiance ; `profil de la semaine` + `tours/voiture/piste` → **épreuve Sport compatible/probable**, jamais mode officiel prouvé à partir d'UDP seul ; `mesures carburant en direct` + `historique comparable` + `règles sourcées` + `scénarios pneus` → stratégie conditionnelle puis personnalisée quand les pneus sont confirmés, **sans inventer les gommes ni l'usure GT7**. Croisements sur freinage, glisse, températures, rythme et stand : modèles et indicateurs **avec validation terrain**.

## Revue commerciale avant distribution

La possibilité de réutiliser des données CC0/MIT ne vaut **ni accord de Sony/Polyphony Digital**, ni licence de leurs marques ou de leurs photos, ni garantie que la télémétrie UDP déchiffrée puisse être commercialisée sans risque contractuel. Revoir conditions PSN/GT7 à la date de distribution avec un juriste IP/numérique, marques, affichage d'absence d'affiliation, collecte de télémétrie, RGPD et contributions sur consentement **désactivées par défaut**. Ne pas déclarer « commercialisable validé » avant cette revue.
