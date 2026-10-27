# Sport Intelligence & open source — audit vérifié et phase 2

Branche `claude/sport-intelligence`, partie de `feat/gridstats-public-profile-readonly` (e930c42, référence APK #408),
qui contient déjà toutes les branches de travail récentes (V5, zero touch, sources Victory et DG EDGE, briefing
Victory, tableau de bord Fold, carrière locale). Rien n'a été retiré ni réécrit.

## 1. Projets communautaires (vérifiés le 27/10/2026)

Vérification : README et licence lus sur GitHub (raw.githubusercontent), dépôts clonés quand c'était utile. Aucun
code copié.

| Projet | Licence vérifiée | Ce qu'il fait réellement | Ce qu'il sait et que Pitwall ne sait pas | Réutilisable légalement | Intérêt pour l'ingénieur |
|---|---|---|---|---|---|
| ddm999/gt7info | MIT-0 (sans attribution) | Base GT7 : voitures, constructeurs, circuits, **catégories de voitures** (`cargrp.csv`), concessionnaires. Courses quotidiennes : **arrêtées au 31/10/2022** | Catégorie de chaque voiture (Gr.1-4, B, N, X) | Oui, données | **Intégré** : catégorie de voiture = preuve pour reconnaître l'épreuve |
| jbhoorasingh/gt7-datalogger | MIT | Enregistreur Python/FastAPI (PC ou Raspberry Pi) : 60 Hz, 39 colonnes, SQLite, comparaison multi-tours, bulletin par virage, ingénieur vocal (voix du navigateur) | Traces 60 Hz archivées, comparaison à distance identique, détection de layout inversé | Idées seulement (Python, serveur) | Black Box : modèle de stockage par tour |
| jbhoorasingh/gt7-datalogger-track-data | **CC0** (données) + MIT (outils) ; une partie des signatures vient de zetetos/gt-telemetry (**MIT**, avis à conserver) | 121 configurations GT7 ; `signatures.json` (388 Ko) : longueur, boîte englobante et tracé tous les 20 m dans le sens de roulage pour **79 configurations** | Nommer un circuit **et son sens** à partir de la position du jeu, dès un tour | **Oui** (CC0 + avis MIT) | Prochaine étape : circuit exact sans dépendre de la longueur (voir § 6) |
| ransh2014/gt7telemtrace (TRACE) | MIT | Tableau de bord PC + analyse 20 graphiques, tour théorique en micro-secteurs de 25 m, score de régularité (tours à +7 % exclus), alertes vocales PC | Micro-secteurs, analyse virage par virage contre le meilleur tour | Idées (Python) | Diagnostic freinage / trajectoire / réaccélération par virage |
| MoebiusX/gt7-telemetry-analyzer | MIT | Node.js sur PC Windows : HUD 60 Hz, micro-secteurs (10 à 100 tranches), coach audio, enregistrements JSONL bruts en ajout seul | Enregistrement brut complet en ajout seul | Idées (JavaScript, PC) | Format Black Box en ajout seul, tolérant aux coupures |

Applications commerciales ou fermées. **Correction du 27/10** : mon premier audit déclarait à tort Durfu Pit Wall,
Hector et GT Coach introuvables, et assimilait Traces à TRACE. Les quatre existent. Leurs sites ne répondent pas
depuis mon environnement (DNS) : les fonctions ci-dessous viennent des fiches officielles des magasins, des
dépôts publics et des pages indexées. Ce sont des fonctions déclarées, pas testées.

| Application | Plate-forme | Reconnaissance de circuit | Données Sport | Coaching / ingénieur | Code réutilisable |
|---|---|---|---|---|---|
| **Durfu Pit Wall** (durfu.ro/pitwall) | **Android** (Google Play `com.durfu.pitwall`), Windows, navigateur. Relais PC/Raspberry Pi facultatif (`durfu/pit-wall-relay`) pour partager une connexion | Fiche Microsoft Store : « reconnaît le circuit et la configuration à partir de la position de la voiture » ; carte en direct dans l'offre payante | Non annoncées | Offre payante « Race engineer » : carte, zones de freinage, stratégie carburant ; offre gratuite : tableau de bord | Non : le relais n'a pas de fichier de licence (tous droits réservés par défaut) |
| **Hector AI** (hector-ai.app) | **Android** (Google Play `svr.league.hector.ai`, mise à jour du 24/10/2026) ; une version de bureau a demandé .NET selon un utilisateur GTPlanet | Non documentée publiquement | Non documentées | « Ingénieur de course basé sur l'IA et HUD pour Gran Turismo 7 » | Non (fermé) |
| **GT Coach** (gtcoach.app, GT COACH LIMITED) | **PC Windows ou Mac obligatoire** sur le même réseau | Pas de reconnaissance automatique décrite : le pilote choisit la session dans l'application | Oui : les **trois courses quotidiennes et les Time Trials de la semaine**, avec un tour de référence par épreuve qui suit la rotation du jeu | Coaching **vocal en temps réel** contre un tour de référence ; audio par Discord vers le casque PS5 (utile en VR) ; formule annuelle payante | Non (fermé) |
| **Traces** (traces.dg-edge.com) | Service DG EDGE, **Premium** (4,99 €/mois ou 49,99 €/an) ; plate-forme Android non précisée | Comparaison **à distance identique** (même mètre de piste, pas même seconde) | DG EDGE publie les courses de la semaine (source déjà lue par Pitwall quand elle répond) | **Segments courts par virage** plutôt que secteurs, traces freinage/accélérateur, cohérence sur les 5 meilleurs tours ; pas de voix annoncée | Non (fermé) |
| Victory Race Engineer | iOS (Cosmic Kettle Software) | Non documentée | Page publique `victorydash.io/daily-races` (source Sport de Pitwall) | Traces des pédales, carburant, températures, voix d'ingénieur | Non (fermé) |
| Coach Dave Delta | PC Windows obligatoire, abonnement | — | — | Traces, comparaison, coaching IA | Non |

Ce qui en ressort pour l'ingénieur :
- **Reconnaître le circuit par la position** (Durfu le fait) est la bonne voie. Pitwall peut le faire sans code
  fermé grâce aux signatures CC0 de jbhoorasingh.
- **Comparer à distance identique et par segment de virage** (Traces, TRACE) est la base du diagnostic par virage.
  Pitwall en aura besoin pour la Black Box.
- **GT Coach** est le seul à relier coaching et courses Sport de la semaine, mais il exige un PC et ne reconnaît pas
  la session. Pitwall reste le seul à reconnaître l'épreuve et à appliquer son règlement, sur téléphone seul.

## 2. Ce que Pitwall a déjà (référence #408)

- Programme Sport depuis quatre sources sans clé ou à clé facultative : GTPlanet (flux et secours), Victory (vérifié
  en HTTP 200 par la CI le 26/10), DG EDGE (403 depuis la CI, essayé sur le téléphone), GridStats (clé). Chaque champ
  garde sa source, sa date et son statut (confirmé par deux sources, probable avec une seule, inconnu, contradictoire).
- Reconnaissance du circuit par le nom publié et la longueur mesurée ; circuit incohérent écarté (V5).
- Reconnaissance A/B/C par les tours (zero touch) ; arrêts suspendus si les tours contredisent la course choisie.
- Archives : résumés par tour, journal de course, passages en virage (profil de pilotage), séries thermiques autour
  des arrêts. **Pas de trace haute fréquence complète.**

## 3. Ce qui manquait vraiment pour les courses Sport

1. Une épreuve désignée par **une seule** preuve (les tours) était annoncée « reconnue ». Or la même distance peut
   correspondre à une autre course ou à un salon privé.
2. La catégorie de la voiture n'était pas utilisée, alors que la télémétrie donne la voiture et que gt7info donne sa
   catégorie.
3. L'annonce ne distinguait pas une obligation vérifiée d'une obligation inconnue.
4. Quand deux courses restaient possibles, Pitwall attendait sans rien demander.

## 4. Ce qui a changé (phase 2, premier lot à faible risque)

- **Trois preuves** dans `SessionMatcher` : les tours (GT7), la catégorie de la voiture (gt7info `cargrp.csv`,
  embarqué dans l'APK et mis à jour avec la base) et le circuit reconnu. Une preuve contraire exclut l'épreuve : une
  voiture de route exclut une course Gr.3.
- **Quatre états**, affichés dans la carte Course et tracés au journal :
  - **reconnue** : les tours et le circuit concordent, et la voiture ne contredit pas ;
  - **probable** : une seule épreuve compatible, mais pas encore toutes les preuves ;
  - **à confirmer** : plusieurs épreuves possibles ;
  - **hors programme / non identifiée**.

  Le journal précise toujours la même limite : rien dans l'UDP ne prouve qu'il s'agit de la course officielle plutôt
  que d'un salon aux mêmes réglages.
- **Annonce vérifiée** (`SportAnnouncement`), par exemple « Course Sport C reconnue. 14 tours. Gommes course durs et
  course mediums imposées. 1 arrêt obligatoire. Carburant fois 2. Je prépare la stratégie. »
  - Les tours dits sont ceux de GT7.
  - Une obligation donnée par une seule source est dite « selon Victory ».
  - Une obligation absente ou contradictoire est dite « non confirmé », jamais inventée.
  - Une épreuve probable est annoncée comme telle, avec ses preuves.
- **Question au pilote** : si deux épreuves restent possibles avec des règlements différents, l'ingénieur demande
  « Course de 11 tours : A ou C ? Dis-moi la lettre. ». La réponse « la C », « C » ou « course B » applique le
  règlement. La question expire après une minute.
- Une course personnalisée ne passe en Sport que si l'épreuve est **reconnue** (tours et circuit), plus sur une seule
  coïncidence.

## 4 bis. Corrections après contrôle (27/10)

- **Identifier A/B/C ≠ course officielle.** La reconnaissance automatique n'attribue plus jamais le type « Sport
  officiel ». Elle pose un type « Sport reconnu » : le règlement de la semaine sert à la stratégie, mais la session
  n'est **jamais comptée** dans les courses Sport vérifiées de la carrière locale.
  - « Sport officiel » n'est gardé que si le pilote l'avait choisi pour la même épreuve.
  - Un entraînement reste un entraînement.
  - Le pilote peut toucher « Sport officiel » pour compter la course dans sa carrière.
- **Règlement incomplet.**
  - Une fiche sans arrêts obligatoires ni gommes imposées n'est jamais présentée comme « aucune obligation ». Le
    plan sans arrêt et les réponses (« pourquoi pas d'arrêt », « prolonger », « box », « avancer ») ajoutent
    « Attention : l'arrêt obligatoire et les gommes imposées ne sont pas confirmés par le règlement. »
  - L'usure fait maintenant partie des champs importants.
  - Une épreuve seulement probable est ajoutée aux réserves de la stratégie.
- **Multiplicateurs dans l'annonce** : la valeur et sa source quand une seule fiche la donne ; « sources
  contradictoires » ou « non confirmé » sinon ; jamais « fois 1 » par défaut.

## 5. Tests

- `SportIntelligenceTest` (5) :
  - catégories lues sur la fiche et dans la base ;
  - voiture de route qui exclut la B (Gr.3) ;
  - « reconnue » seulement quand tours et circuit concordent ;
  - annonce exacte avec deux sources, avec une source, puis avec des obligations inconnues ;
  - question « A ou C ? », réponse « la C », règlement appliqué, question close.
- `SessionIdentityTest` (7) : mis à jour (une seule preuve donne « probable » ; type « Sport reconnu »).
- `SportOfficialityTest` (5) :
  - salon privé reproduisant la B : règlement appliqué, jamais compté comme course officielle ;
  - épreuve probable jamais promue officielle ;
  - choix « officiel » du pilote gardé quand les preuves concordent ;
  - règlement incomplet jamais transformé en « aucune obligation » ;
  - sources contradictoires dites comme telles.
- Suite complète du module `core` et compilation Android : voir le compte rendu de la PR et la CI.

## 6. Black Box — étude (phase 3, pas encore implémentée)

| Point | Proposition | Coût estimé |
|---|---|---|
| Échantillons | 20 Hz au lieu de 60 Hz (GT7 envoie 60 Hz). Par échantillon : temps, position x/z, vitesse, régime, rapport, accélérateur, frein, carburant, 4 températures, drapeaux, numéro de paquet. Environ 40 octets en binaire | ≈ 2,9 Mo/h brut, ≈ 1 Mo/h compressé par tour (gzip) |
| Fichiers | Un fichier par session, en ajout seul, découpé par tour (`.pwbb`, en-tête versionné). Écriture tamponnée toutes les quelques secondes | Batterie : négligeable devant le décodage UDP déjà fait |
| Pertes | Les trous sont marqués par le numéro de paquet, jamais comblés. Un tour incomplet est marqué comme tel | — |
| Limite | Plafond réglable (par défaut 300 Mo). Les sessions les plus anciennes sont effacées en premier ; les sessions marquées (records, courses Sport) sont gardées | ≈ 300 h de course |
| Compatibilité | Ajoutée **à côté** des archives JSON actuelles (`SessionRecord`) : rien n'est réécrit | — |
| Usages ouverts | Comparaison à distance identique, perte par virage (freinage, vitesse minimale, réaccélération), relecture après course, vérification des conseils donnés | — |

Prochaines étapes, par ordre d'impact :

1. **Signatures de circuits CC0** (jbhoorasingh) : identifier le circuit exact, sens compris, dès la grille ou au
   premier tour. Une épreuve passerait alors à « reconnue » bien plus tôt, et la longueur seule ne serait plus
   nécessaire.
2. **Prototype Black Box** mesuré sur la Fold (taille, CPU, batterie) sur une vraie course.
3. **Perte par virage** à partir de la Black Box : freinage, minimum ou réaccélération, avec preuve.
4. **Bilan d'efficacité des conseils** sur traces comparables.
