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
