# Protocole de validation sur PS5 — géométrie, courses Sport, Black Box

Objectif : prouver sur la PS5 et la Galaxy Fold, avec des **journaux réels**, ce que les tests ne prouvent que sur
simulation. Rien n'est validé tant que le journal exporté ne montre pas la ligne attendue.

## Préparation (une fois)

1. Installer l'APK de la PR #28 (elle contient aussi les corrections de la PR #27).
2. Vérifier l'indicateur sous le titre : `● Sport JJ/MM · Base …` en vert. Sinon, noter le texte affiché.
3. Atelier → « Mesurer pendant la prochaine course » : laisser **coupé** pour les essais 1 à 4.
4. Après chaque essai : Historique → la course → **Export JSON**, puis envoyer le fichier. Nommer l'essai (« Essai 2 »).

Les lignes à chercher sont dans `events[].text` et `events[].details` du JSON.

## Essai 1 — Repère X/Z et géométrie (contre-la-montre ou course personnalisée, 2 tours)

Circuit présent dans les signatures (Suzuka, Interlagos, Monza, Spa sont couverts ; Yas Marina et Eiger ne le sont pas).

| Attendu | Ligne de journal | Réussi si |
|---|---|---|
| Circuit reconnu pendant le 1er tour | `Circuit reconnu par la géométrie : <circuit>` | Bon circuit ; `distance_m` < longueur du circuit ; `ecart_moyen_m` < 12 |
| Repère correct | même ligne, `premiere_position` | Présente |
| Sinon : diagnostic | `Géométrie : aucune signature ne correspond` | Voir ci-dessous |

Lecture du diagnostic `plus_proche` :
- `normal : <bon circuit> à 5 m`, mais rien de reconnu : seuils trop stricts, à ajuster ;
- `z inversé : <bon circuit> à 5 m` (ou `x inversé`) : **repère différent**. On corrige le repère, sans rien toucher
  aux données ;
- aucun tracé proche dans aucun repère : le circuit manque aux signatures, ou les positions sont absentes ;
- `Géométrie : pas de conclusion après un tour` avec `echantillons` = 0 : les positions ne sont pas échantillonnées
  (voiture jugée à l'arrêt ou aux stands), à corriger.

Variante : le même circuit **en sens inverse**. Réussi si la ligne nomme la configuration inverse avec `sens=inverse`.

## Essai 2 — Course Sport réelle, en ligne

Avant la course : laisser le type de session tel quel (ne pas toucher « Sport officiel »).

| Attendu | Ligne de journal / radio | Réussi si |
|---|---|---|
| Course reconnue | `Épreuve reconnue : Course Sport X (reconnue automatiquement)` ; `preuves` contient les tours, et le circuit après la géométrie | Bonne lettre ; `certitude=RECOGNIZED` au plus tard au 1er tour |
| Annonce radio | « Course Sport X reconnue. N tours. … » | Chaque obligation dite correspond à la fiche du jeu ; inconnue ⇒ « non confirmé » |
| Bilan de fin de course | `Bilan : …` | Dans l'export de **cette** course, juste avant `Fin de session` ; absent de la course suivante |
| Pas d'officialité implicite | Type de session = « Sport reconnu » | La carrière (Briefing) ne compte **pas** cette course |
| Appel au stand (si arrêt obligatoire) | « Box, box ! … » | Si une seule source donne l'obligation : « Obligation à confirmer : seule la fiche … la donne. » |

Puis, après l'arrivée seulement : toucher « Sport officiel ». Réussi si la course **déjà terminée** n'est **pas**
ajoutée à la carrière (elle ne l'est que pour les courses suivantes).

## Essai 3 — Salon privé reproduisant une course Sport

Mêmes circuit, voiture et tours qu'une course de la semaine, en salon privé ou en course personnalisée.

| Attendu | Réussi si |
|---|---|
| Règlement de la course appliqué pour la stratégie | `Épreuve reconnue` avec preuves tours + circuit |
| Jamais compté comme officiel | Type « Sport reconnu » ; carrière inchangée |
| Jamais dit officiel à la radio | Aucune phrase « course officielle » ou « Sport officiel » |

## Essai 4 — Ambiguïté et course hors programme

- Course personnalisée de N tours, N absent du programme : `Course hors programme Sport` si un profil Sport était
  actif, et aucun règlement Sport appliqué.
- Si deux courses de la semaine ont le même nombre de tours : la radio demande « A ou C ? Dis-moi la lettre. ».
  Répondre « la C » à la voix. Réussi si le journal contient `Épreuve désignée par le pilote : Course Sport C`, et si
  la transcription (`TRANSCRIPT`) montre ce que la reconnaissance vocale a entendu.

## Essai 5 — Mesures Black Box (Fold)

1. Batterie entre 40 % et 90 %, chargeur débranché, luminosité et volume habituels.
2. Atelier → activer « Mesurer pendant la prochaine course ».
3. Course de **15 minutes ou plus**, puis couper la mesure dans l'Atelier.
4. Le journal contient `Mesure Black Box terminée`, avec les points d'étape toutes les 10 minutes. Les champs
   `mesure_1` à `mesure_4` sont :
   - durée, paquets/s et paquets perdus ;
   - temps de calcul par paquet (moyenne, 95e centile, maximum) ;
   - trace 20 Hz brute et compressée par heure ;
   - batterie (% par heure) et température maximale.

   Les champs `appareil` et `android` identifient le téléphone.
   Rattachement : couper la mesure **au menu, après l'arrivée**. Réussi si l'événement figure dans l'export de la
   course mesurée, en dernier, avec `apres_cloture=oui` et `courses_mesurees` égal à cet identifiant. Lancer ensuite
   une nouvelle course : son export ne doit contenir **ni** « Mesure Black Box » **ni** de ligne « Bilan : »
   de la course précédente.
5. Refaire une course comparable **sans** la mesure et noter la batterie à la main (début, fin, durée) : la
   différence entre les deux courses donne le coût de la mesure.

Seuils pour décider de la Black Box :
- paquets perdus < 1 % ;
- 95e centile du calcul < 4 ms (un paquet arrive toutes les 16 ms) ;
- trace compressée ≤ 3 Mo par heure ;
- écart de batterie entre avec et sans mesure ≤ 2 % par heure.

## Ce qu'il faut renvoyer

Pour chaque essai : l'export JSON, le circuit, la voiture, le type de course (en ligne, salon, personnalisée), et ce
qui a été entendu à la radio s'il diffère du journal. Chaque essai est évalué sur son journal, sans le comparer aux
simulations.
