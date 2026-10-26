# Pitwall Zero Touch : j'allume, je lance, je joue

Branche `claude/zero-touch` (partie de `claude/race-engineer-v5`).

## Ce qui se synchronise seul, sans clé ni compte

| Donnée | Source | Quand | Hors ligne | Vérifié |
|---|---|---|---|---|
| Courses Sport A/B/C de la semaine et règlement (circuit, tours, voitures, pneus autorisés/imposés, arrêts, multiplicateurs, BoP, réglages) | Flux RSS public GTPlanet, étiquette « daily races » | À chaque ouverture, puis toutes les 30 min si la politique le demande (semaine manquante, lundi/mardi, ou une fois par jour) ; au retour du réseau | Dernier programme en cache | Analyseur testé sur la fiche réelle du 19/10. **Accès en direct non testé depuis mon environnement** (GTPlanet bloqué par mon proxy, pas par le site) |
| Secours Sport | Flux général GTPlanet (même site, même format) | Si le flux principal échoue ou n'a pas la semaine | Cache séparé, articles dédoublonnés | Non testé en direct (même raison) |
| Voitures, constructeurs, circuits et configurations | Base communautaire gt7info (ddm999, MIT-0) sur GitHub | À l'ouverture si plus de 6 h, une fois par jour, dès qu'une voiture inconnue arrive par la télémétrie (au plus une fois par heure), au retour du réseau | Dernière version valide, sinon celle de l'APK | **Testé en direct le 26/10** : 591 voitures, 83 constructeurs, 123 circuits, fichiers identiques à ceux validés par les tests |
| GT GridStats | API avec clé personnelle | Seulement si une clé est saisie | Cache | Facultatif : jamais nécessaire au fonctionnement |
| Références de stratégie (consommation, rythme, usure, profil de pilotage) | Mesurées par Pitwall pendant tes courses | En continu | Sur le téléphone | Aucune base externe n'existe pour ces chiffres |

## Reconnaissance automatique de la session

1. **Voiture** : identifiant GT7 de la télémétrie, nom dans la base. Une voiture inconnue déclenche la relecture de la
   base.
2. **Épreuve** (`SessionMatcher`), dès le départ :
   - GT7 annonce le nombre de tours ; une seule course de la semaine avec ce nombre de tours → son règlement est
     appliqué, sans toucher l'écran ;
   - une épreuve active contredite (course réelle du 26/10 : profil B à 6 tours, GT7 annonce 14) est **retirée** :
     course hors programme Sport, ou attente du circuit si deux épreuves restent possibles ;
   - au premier tour, le circuit reconnu (nom publié et longueur) confirme ou départage ;
   - une course personnalisée ne passe en Sport qu'avec **deux** preuves (tours et circuit) ;
   - qualification et chrono : rien (GT7 n'annonce pas de tours).
3. **Circuit** : nom publié de l'épreuve rapproché de la base et de la longueur mesurée ; sinon longueur seule si un
   seul circuit correspond ; un circuit enregistré incompatible avec le tour est écarté (V5).

Jamais deux règlements mélangés : on change de profil d'épreuve entier, et une épreuve contredite n'est jamais gardée.
Ce qui reste incertain est affiché comme tel (« Épreuve à confirmer : Course Sport B ou Course Sport C »).

## Indicateur de fraîcheur

Sous le titre : `● Sport 26/10 · Base 3 h`.
- **Vert** : les trois courses de la semaine sont lues et la base a été vérifiée depuis moins de 3 jours.
- **Jaune** : lundi ou mardi avant la fiche (« Sport en attente »), une partie seulement des courses, ou une base non
  vérifiée récemment.
- **Rouge** : programme de la semaine introuvable après mardi.

Un appui affiche le détail (source, âge, dernière erreur) et un bouton « Actualiser ».

## Ce qui manque encore pour garantir le critère du lundi

- **Une seule source Sport sans clé** : GTPlanet. Le secours est le même site. Si GTPlanet change sa mise en page
  ou ne publie pas, Pitwall le montre (jaune, puis rouge) mais n'a pas d'autre source sans clé. gt7info
  n'a plus de données de courses depuis le 31/10/2022.
- **Proposition durable, à valider** : un relais gratuit dans ton dépôt GitHub (GitHub Actions, gratuit pour un dépôt
  public). Le lundi et le mardi, il lirait les sources publiques et publierait un fichier JSON normalisé au même endroit
  que gt7info (raw.githubusercontent). Le téléphone n'aurait plus qu'une adresse stable à lire. Ce relais tournerait
  seul chaque semaine et écrirait dans le dépôt : je ne le mets pas en place sans ton accord.
- **Essai réel nécessaire** : ouvrir Pitwall un lundi soir ou un mardi. L'indicateur doit passer au vert avec la
  semaine du jour, et la première course Sport doit annoncer « Course Sport X reconnue ».

## Tests

- `SessionIdentityTest` (7) : désignation par les tours, preuves contraires, départage par le circuit, absence de
  programme, profil B périmé retiré pour 14 tours (26/10), bonne course choisie sans écran, course personnalisée à
  deux preuves.
- `DataFreshnessTest` (3) : vert, lundi en attente, semaine introuvable avec erreur affichée.
