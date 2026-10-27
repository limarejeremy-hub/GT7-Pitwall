# Correctif après l'essai APK #426 (SUGO, 27/10) — issues #32 et #33

Branche `claude/v426-radio-hotfix`, partie de `claude/v421-engineer` (PR #31). Tout ce qui suit est vérifié par des
tests sur PC et par la compilation Android en CI. **Rien n'est validé sur le Fold et la PS5 tant que le protocole
ci-dessous n'a pas été joué.**

## État par fonction

| Fonction | En course PS5 | Preuve / test |
|---|---|---|
| Télémétrie UDP, voiture, tours, secteurs | Opérationnelle (27/10) | Journal SUGO 14 tours |
| Voix de l'ingénieur (annonces) | Opérationnelle (27/10) | Journal SUGO |
| « Radio » avant le départ | Opérationnelle (20:57, 905 ms) | Journal SUGO |
| « Radio » pendant la course | **En panne sur #426** ; reprise automatique ajoutée, **à valider** | `ListenWatchdogTest` (JVM seulement) |
| Photo de la voiture | **En panne sur #426** ; nouvelle adresse, **à valider** | `CarImagePolicyTest` ; adresse non joignable depuis le développement |
| SUGO | Appris sous un nom d'épreuve sur #426 ; fiche des notes officielles 1.72 ajoutée | `OfficialUpdatesTest` |
| Place de départ au bilan | P1 erroné sur #426 ; corrigée | `LivePositionTest.aTransientP1Of1IsNeverTakenAsTheGrid` |
| Virages numérotés, pneus/pilotage, arrêts vocaux (#31) | Jamais essayés en course | Tests JVM de la PR #31 |

## Radio muette : ce qui a été trouvé dans le code

Cinq manières de bloquer l'écoute sans aucun signe à l'écran. Aucune n'a pu être confirmée sans le journal audio,
qui n'existe pas (aucun son n'est enregistré). Toutes sont maintenant surveillées chaque seconde par `ListenWatchdog` :

1. **Écoute en erreur jamais relancée.** Une bascule Discord → Fold (salon reconnecté au départ) qui trouvait le
   micro occupé laissait l'écoute en erreur. Les redémarrages suivants étaient ignorés, faute d'écoute « en cours ».
   Correction : l'écoute activée est relancée, à 0, 5, 15, 30 puis 60 s d'intervalle (pas de boucle).
2. **Pause sans fin.** Le micro se met en pause pendant que l'ingénieur parle. Si la fin de lecture n'est jamais
   signalée (rappel de synthèse perdu), il restait en pause toute la course pendant que les annonces continuaient.
   Correction : au bout de 60 s, la voix bloquée est réinitialisée et l'écoute reprend.
3. **Micro prêté à Google, jamais rendu.** C'était le cas quand le résultat d'une écoute annulée arrivait. Correction :
   le micro est rendu immédiatement, et dans tous les cas 3 s après que Google a cessé d'écouter.
4. **Boucle audio sans aucun bloc depuis 5 s** : écoute relancée.
5. **Bascule de micro manquée** (casque PS5 disponible mais écoute restée sur le Fold, ou l'inverse) : écoute
   relancée sur le bon micro.

Un micro du Fold qui ne rend que des zéros pendant 20 s (micro coupé par Android) est signalé une fois, sans boucle.

Chaque reprise est écrite au journal de course (« Écoute radio : … — écoute relancée. »). L'Atelier → **Diagnostic
radio** affiche :
- les blocs audio reçus par seconde (≈ 4 attendus), le dernier bloc et le niveau ;
- la durée de la pause en cours ;
- le dernier « radio » reconnu ;
- la dernière voix du pilote reçue du salon Discord ;
- les reprises automatiques et la dernière raison.

## Photo

L'APK #426 demandait `…/carlist/car_images/car3474.webp`. Ce chemin était supposé et n'a jamais été vérifié. Le
nouveau chemin, `…/carlist/car_thumbnails/car3474.png`, est celui qu'utilisent plusieurs projets publics indépendants
avec le même identifiant UDP :
- nikitafedorov008/gt7-companion ;
- francmarra/bop-radar ;
- patkosmarci/GT7_Tuning_Share.

Le site officiel est inaccessible depuis l'environnement de développement : l'adresse n'est **pas** vérifiée ici.

Dans l'Atelier → **Photo de la voiture** :
- la dernière requête (adresse, code HTTP, type, taille annoncée) ;
- un bouton « Réessayer la photo maintenant », sans attendre 6 h.

Le cache est versionné : l'ancien n'est jamais relu. Une seule voiture à la fois, pas d'en-tête falsifié, silhouette
en secours.

## SUGO et mise à jour 1.72

`course.csv` (dans l'APK comme dans gt7info) n'a pas SUGO : la base communautaire met ses circuits à jour à la main.
Le nouveau fichier versionné `official_updates.json` contient :
- SUGO International Racing Course : 3 586 m, 13 virages officiels (valeurs relevées dans l'issue #33, à revérifier
  sur la page officielle) ;
- les six voitures 1.72, déjà présentes dans `cars.csv` ;
- la Spec IV partie 2, annoncée mais pas encore dans le jeu (affichée, jamais utilisée).

Règles de cette fiche :
- Pas d'identifiant GT7 inventé, pas de perte aux stands, aucun virage placé, aucune géométrie supposée.
- Un SUGO déjà appris garde son identifiant : chronos, consommation et historique restent attachés.
- La fiche gt7info la remplace dès sa parution.
- La longueur seule reste ambiguë avec Laguna Seca (3 602 m) : jamais de choix forcé.

L'Atelier → **Base GT7 : couverture** affiche les voitures, les circuits par source, la reconnaissance par la
géométrie, les virages numérotés et la dernière mise à jour connue avec sa source.

## Journal du 27/10 : autres corrections

- **Grille transitoire.** La grille lue avant le départ n'est retenue qu'après une seconde stable, et jamais « P1 sur
  1 ». Le bilan et l'archive utilisent la même place de départ.
- **Provenance de la gomme.** Champ `source_gomme` : gomme déclarée, jamais transmise par GT7.
- **Règlement non confirmé.** L'appel au stand dit « Monte les X, si le règlement l'impose. ».

## Protocole Fold + PS5 (trois étapes)

1. **Avant la course.**
   - Installer l'APK.
   - Atelier → Diagnostic radio : « Blocs audio reçus » doit montrer environ 4/s.
   - Dire « Radio, tu m'entends ? » : réponse attendue, et « Dernier « radio » reconnu : à l'instant ».
   - Atelier → Photo de la voiture : noter la ligne « Dernière requête » (code HTTP), et toucher « Réessayer » si besoin.
2. **Pendant la course** (SUGO ou autre, 5 tours suffisent).
   - Dire « Radio, combien d'essence ? » au tour 1, au tour 3 et au dernier tour.
   - Si une demande reste sans réponse, la répéter 15 s plus tard (temps d'une reprise automatique).
3. **Après l'arrivée.**
   - Faire une capture de l'Atelier → Diagnostic radio (reprises automatiques et raison).
   - Exporter le journal JSON de la course : chercher les lignes « Écoute radio : … ».
   - Envoyer la capture, le journal et la capture de la carte Photo.
