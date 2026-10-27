# Pitwall V421 — ingénieur intelligent et zéro touch

Base : APK #420 (commit 3fd11c0, branche `claude/track-geometry`). Spécification : issues #29 et #30. Test réel de
référence : Suzuka, Subaru BRZ GT300 '21, course personnalisée de 5 tours, P10 → P2, le 27/10.

Le journal du 27/10 n'était pas disponible localement : les tests reconstruisent ses extraits cités dans l'issue #29.
Tout ce qui suit a été vérifié par des tests sur PC et par la compilation Android en CI. **Rien n'est validé sur PS5
tant que le protocole ci-dessous n'a pas été joué.**

## Phase 1 — arrêts vocaux, réponses locales, box concis, positions, fin de course

Quatre intentions distinctes, toutes reconnues **localement**, sans attendre le cloud (cible < 2 s) :

| Phrase | Intention | Effet |
|---|---|---|
| « oublie l'arrêt au stand », « on ne rentre pas », « pas besoin de rentrer » | `CancelPlannedStop` | Confirmation, puis plus d'arrêt prévu, rappels d'entrée aux stands effacés |
| « annule le dernier arrêt », « j'ai pas fait d'arrêt » | `CancelStop` | Supprime l'arrêt **enregistré** à tort |
| « zéro arrêt obligatoire » | `SetMandatoryStops(0)` | Règlement déclaré par le pilote |
| « as-tu enlevé l'arrêt ? », « est-ce que je rentre ? » | `Ask(STOP_STATUS)` | « Oui : aucun arrêt n'est prévu… » / « Non : un arrêt est encore prévu fin du tour N… » |

Règles de protection :
- Une obligation d'arrêt d'un règlement Sport **vérifié** n'est jamais retirée par la voix. Réponse : « Je garde
  l'arrêt fin du tour N : il est obligatoire dans le règlement vérifié… ».
- Un arrêt imposé par le carburant est refusé, avec la consommation cible par tour pour finir.
- La confirmation survit à une question sur l'arrêt (« tu l'as enlevé ? »). Elle expire après 45 s.

Autres corrections :
- Appel au stand : « Box à la fin de ce tour. ». S'y ajoute seulement l'essentiel : « Monte les X » si la gomme est
  imposée.
- Positions :
  - stabilité de 2 s ;
  - regroupement sur 1,5 s ;
  - 5 s minimum entre deux annonces (10 s en mode calme) ;
  - message de position libéré après 0,5 s de calme radio, jamais rejoué périmé.
- Après l'arrivée : « Course terminée — 5/5 » au lieu de « Tour 6 sur 5 » (Live et Analyse).

## Phase 2 — photo officielle de la voiture

Mécanisme :
- Lien direct identifiant UDP → `car{id}.webp` sur gran-turismo.com, une seule voiture à la fois.
- Cache disque de 30 jours, limité à 60 voitures. Pas de récupération massive.
- Redirections suivies seulement vers *.gran-turismo.com en HTTPS.
- Vérification que les octets sont une image. Pas d'en-tête falsifié.

L'Atelier affiche la raison exacte d'un échec :
- HTTP 403 ou 404 ;
- réponse qui n'est pas une image ;
- image indécodable ;
- image trop lourde ;
- réseau ;
- attente avant nouvel essai.

**Expérimental** : le serveur d'images est inaccessible depuis l'environnement de développement. La cause réelle sur la
Fold reste à lire dans l'Atelier. La silhouette reste le secours.

## Phase 3 — règles zéro touch, courses personnalisées

Pour la Sport, rien ne change : reconnaissance par circuit (géométrie), tours, voiture et programme de la semaine. Chaque règle a sa
source et sa certitude, et « reconnu » n'est jamais « officiel ».

Pour une course personnalisée avec un règlement local en mémoire :
- La radio pose **une** question, au tour 1, seulement si ce règlement contient des obligations : « Course
  personnalisée : j'ai en mémoire 1 arrêt obligatoire. Toujours valable pour cette course ? ».
- « Oui » : le règlement est gardé pour cette course.
- « Non » : plus d'arrêt obligatoire ni de gomme imposée.
- Sans réponse : la stratégie précise « règlement local mémorisé, non confirmé pour cette course ». Le journal porte
  `source_reglement`.

## Phase 4 — virages numérotés et bilan par virage

La source est [jbhoorasingh/gt7-datalogger-track-data](https://github.com/jbhoorasingh/gt7-datalogger-track-data)
(CC0). Ses apex sont convertis par `tools/track_signatures/build_corners.py`.

Couverture réelle vérifiée : **10 circuits sur 27** relevés.
- Suzuka : 19 virages relevés pour 20 officiels, signalé « à vérifier ».
- Tsukuba : 12 relevés pour 8 officiels, signalé.

Sur la carte :
- « T5 » quand l'apex détecté par Pitwall est à moins de 45 m d'un apex de la source ;
- « V3 » (numéro estimé) sinon, en sens inverse, ou sans relevé.

Le bilan par virage est affiché dans Analyse. Pour chaque virage :
- passages comparables et meilleur temps ;
- perte moyenne ;
- vitesses d'entrée et minimale ;
- blocages et patinages ;
- cause probable **mesurée** (freinage plus tôt ou plus tard, vitesse minimale, réaccélération tardive, blocages,
  patinage) et confiance.

La radio cite le virage le plus coûteux seulement si la perte est d'au moins 0,1 s et que la confiance est au moins
moyenne, c'est-à-dire si elle se répète. Le détail reste dans Analyse.

## Phase 5 — pneus ou pilotage ?

Pour le relais en cours, Pitwall compare les mêmes virages en début et en fin de relais (2 premiers et 2 derniers
tours). Sont écartés :
- le premier tour du relais ;
- les tours d'arrêt et le tour suivant ;
- les incidents déclarés.

| Verdict | Condition |
|---|---|
| Pneus probables | Au moins la moitié des virages plus lents (≥ 40 ms et vitesse minimale en baisse), perte non concentrée ; blocages ou patinages en hausse cités |
| Pilotage probable | Un virage porte au moins 60 % de la perte, le reste du tour au niveau du début de relais |
| Mixte | Entre les deux, et c'est dit |
| Stable / indéterminé | Moins de 0,15 s de perte au total, ou moins de 4 tours et 3 virages comparables |

Ce diagnostic complète celui de l'adhérence, tour par tour, qui croise déjà :
- les 4 températures ;
- l'antipatinage ;
- les blocages ;
- la régression des chronos ;
- les déclarations du pilote, étiquetées « (déclaré) ».

Il est ajouté à la réponse radio sur les pneus et à la carte Virages d'Analyse.

Ce qui n'est jamais fait :
- donner un pourcentage d'usure ;
- inventer une gomme ;
- poser une question vocale de plus.

## Protocole PS5 simple

1. Installer l'APK de la PR. Suzuka, BRZ GT300, course personnalisée de **5 tours**, avec un arrêt obligatoire
   en mémoire.
2. **Tour 1** : la radio demande si le règlement est toujours valable. Répondre « non ». Attendu : « D'accord : pas
   d'arrêt obligatoire… ».
3. **Tour 2** : dire « oublie l'arrêt au stand ». Attendu en moins de 2 s : la question de confirmation. Répondre
   « oui ». Puis demander « tu as enlevé l'arrêt ? ». Attendu : « Oui : aucun arrêt n'est prévu… ».
4. Dépasser deux voitures d'affilée. Attendu : une seule annonce groupée, sans position périmée.
5. Après l'arrivée : l'écran affiche « Course terminée — 5/5 ».
6. Analyse :
   - la carte porte des T1… (V pour les virages non appariés) ;
   - la carte Virages liste les pertes ;
   - « Pneus ou pilotage ? » apparaît à partir de 5 tours propres.
7. Atelier : lire la carte « Photo de la voiture ». Si la silhouette est affichée, noter la raison donnée.
8. Exporter le journal JSON de la course et le renvoyer, avec ce qui a été entendu à la radio s'il diffère.

Une course Sport réelle reste à jouer selon `docs/VALIDATION_PS5.md` (essai 2) pour vérifier qu'aucune obligation
vérifiée n'est retirée par la voix.
