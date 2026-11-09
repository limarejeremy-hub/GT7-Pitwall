# Briefing : courses Sport A/B/C d'un coup d'œil (V497)

Demande du pilote (09/11) : connaître en un coup d'œil le circuit et le type de voiture de chaque course Sport.

En haut de l'onglet **Briefing**, une carte par course (trois côte à côte sur le Fold déplié, empilées sinon) :

- lettre A/B/C, dates de la semaine (« cette semaine » / « semaine ancienne »), nombre de tours en gros ;
- **tracé du circuit dessiné par Pitwall** à partir des captures libres CC0 de `track_signatures.json`
  (jbhoorasingh/gt7-datalogger-track-data), point blanc = départ ; nom et longueur ; pas de tracé si le circuit n'est
  pas identifié de façon sûre ;
- **voiture imposée** : photo officielle GT7 (APK de débogage seulement, droits Sony / Polyphony, même règle que
  `PitwallCarArtwork` : une image demandée puis mise en cache sur le téléphone, rien dans le dépôt), sinon silhouette ;
  **catégorie** : badge dessiné (Gr.1 rouge, Gr.2 orange, Gr.3 bleu, Gr.4 vert, Gr.B violet, N… cyan) ;
- **gommes** autorisées en anneaux de couleur, les **obligatoires** encadrées en orange avec « au moins un passage au
  stand pour changer de gomme » quand il y en a deux ou plus ;
- carburant ×, usure ×, arrêts obligatoires, sources.

Un champ inconnu ou contradictoire est affiché comme tel (jamais deviné). Le détail textuel par champ reste plus bas.

**Images officielles des circuits** : pas utilisées. L'adresse n'a pas pu être vérifiée (site officiel non joignable
depuis l'environnement de développement) ; le tracé dessiné ne pose aucune question de droits.

`SportCardTest` (4) sur la vraie base de l'APK. Non vérifié sur le Fold.

## V498 : logo officiel et fiche du circuit

Accès à www.gran-turismo.com autorisé par le pilote le 09/11. Structure du catalogue « tracklist » vérifiée sur le
site (voir `TrackCatalogPolicy`) : 122 tracés, 42 logos de sites ; les 72 tracés de `track_signatures.json` y sont
tous, par leur `official_id`. Chaque carte affiche, sous le tracé dessiné :

- le **logo officiel** du site (ex. Circuit de Spa-Francorchamps) ;
- la **fiche officielle** : pays, nombre de virages, dénivelé, plus longue ligne droite (catalogue en français).

Même règle que la photo des voitures : contenus de Sony / Polyphony, **APK de débogage seulement**, rien dans le dépôt,
catalogue relu au plus une fois par semaine, un logo par site en cache 30 jours, site officiel seulement. En cas
d'échec, rien n'est affiché (le tracé dessiné reste). Au passage : l'adresse des photos de voitures
(`car_thumbnails/carNNNN.png`) est **confirmée** (HTTP 200, Audi TT Cup '16).

`TrackCatalogPolicyTest` (4, extraits synthétiques), lecture validée sur les vrais fichiers du site (non versionnés).

## V500 : tracés reconstitués (Suzuka East…)

Le dépôt amont (gt7-datalogger-track-data, commit 637aaa3 du 08/11) a maintenant un relevé de bords de Suzuka East,
mais toujours pas de signature utilisable (sans tracé, signalée). `tools/track_outlines/build.py` reconstitue un
**tracé d'affichage** à partir de deux données CC0 réelles : la portion du tracé complet du même site couverte par le
relevé (d'un seul tenant), plus la liaison propre à la configuration (moyenne des points du relevé hors du tracé
complet, tous les 20 m). Gardé seulement si la longueur est à 5 % de l'officielle.

Résultat (`assets/gt7db/track_outlines.json`) : Suzuka East 2 236 m (officiel 2 243), Lago Maggiore Centre et East
End, Red Bull Ring Short Track. Mention « Tracé reconstitué d'un relevé de bords » sous le dessin. **Jamais utilisé pour
reconnaître le circuit** (affichage seulement ; `track_signatures.json` inchangé). Toujours sans tracé : Barcelona
National et Rallycross, Spa 24h, Streets of Willow Springs, Northern Isle (pas de relevé exploitable).
