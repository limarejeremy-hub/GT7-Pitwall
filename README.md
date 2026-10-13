# GT7-Pitwall
Ingénieur de course intelligent pour Gran Turismo 7 sur PS5 : télémétrie, stratégie et radio vocale.

Application Android native (Kotlin, Jetpack Compose) pensée pour le **Samsung Galaxy Fold** :
une colonne sur l'écran externe, deux colonnes une fois déplié. Aucun PC, aucun SimHub,
aucun service payant : le téléphone parle directement à la PS5 sur le Wi-Fi de la maison.

## Ce que fait la version 0.1

- **Télémétrie en direct** reçue de la PS5 (UDP, ~60 paquets/s), déchiffrée et décodée sur le téléphone.
- **Affichage** : vitesse, rapport, régime, accélérateur/frein, tour en cours / total,
  dernier tour, meilleur tour, écart, historique des tours, niveau de carburant.
- **Stratégie carburant** calculée à partir des niveaux réellement mesurés :
  consommation moyenne (3 derniers tours complets), autonomie en tours, carburant nécessaire
  pour finir, et un conseil : *OK pour finir*, *Juste (consommation cible)*, ou
  *Ravitailler (dernier tour possible, quantité à ajouter, nombre d'arrêts)*.
- **Ingénieur vocal en français** (Text-to-Speech d'Android, gratuit) :
  radio check, chrono de chaque tour avec écart au meilleur, point carburant à chaque tour,
  alertes « moins de deux tours » / « moins d'un tour », dernier tour, ravitaillement détecté.
- **Questions à l'ingénieur** par boutons : Carburant ? Course ? Dernier tour ? Meilleur ? Rythme ?
- **Service de premier plan** : l'ingénieur continue de parler écran éteint (notification avec bouton Arrêter).

### Aucune donnée inventée

Tout ce qui est affiché ou annoncé vient des paquets GT7 ou d'un calcul sur ces valeurs.
Quand une information manque, l'application le dit au lieu de l'estimer :

- la consommation n'est mesurée que sur un **tour complet observé depuis la ligne**
  (si l'application démarre en plein tour, ce tour n'est pas compté) ;
- un tour avec ravitaillement est exclu de la moyenne ;
- en course au temps / essais libres, GT7 n'envoie pas de nombre de tours : seule l'autonomie est donnée ;
- GT7 **n'envoie rien sur les adversaires**, ni les secteurs, ni la position en course :
  ces fonctions ne sont donc pas affichées (voir *Architecture*).

## Nouveautés 0.2

- **Secteurs Pitwall** : GT7 ne transmet pas de temps de secteurs. L'application les reconstruit
  à partir de la position de la voiture (60 fois/s) et du chrono officiel : le tour est coupé en
  3 parts de distance égale (ce ne sont pas les secteurs officiels du circuit). À chaque tour,
  l'ingénieur dit où tu perds du temps par rapport à ton meilleur tour.
- **Radio vocale avec mot de réveil** (« radio » par défaut, modifiable) : reconnaissance française
  **hors ligne** avec Vosk (gratuit, open source). Le modèle (~41 Mo) se télécharge une fois depuis
  l'application. Le micro est coupé pendant que l'ingénieur parle.
  Exemples : « radio, combien d'essence ? », « radio, je suis troisième », « radio, j'ai doublé »,
  « radio, je me suis fait doubler », « radio, mes secteurs », « radio, silence », « radio, répète ».
- **Vocabulaire limité** (activé par défaut) : la reconnaissance ne cherche que les mots de la radio,
  ce qui évite les erreurs du type « adieu une place » au lieu de « perdu une place ».
- **Position à chaque tour** : à la fin de chaque tour, l'ingénieur demande « Ta position ? » et la radio écoute
  la réponse sans mot de réveil (« huitième »). Désactivable dans la carte Radio vocale.
- **Appel au stand** : un tour avant le dernier tour possible, « Stand au prochain tour » ; sur ce tour,
  « Box, box ! Rentre au stand à la fin de ce tour, ajoute environ N litres ».
- **Position** : GT7 ne transmet **ni la position en course ni les adversaires**. L'ingénieur part
  de la place sur la grille (transmise par GT7 avant le départ) puis suit ce que tu lui annonces.

## Données GT7 utilisées (0.3)

Référence du format : [PDTools](https://github.com/Nenkai/PDTools) (Nenkai, licence MIT). L'application demande le
format le plus complet (heartbeat `~`, 344 octets) et repasse automatiquement au format `A` (296 octets) si la
console ne répond pas. Le format reçu est affiché dans la carte Connexion.

| Donnée transmise par GT7 | Utilisation dans l'application |
|---|---|
| Vitesse, régime, rapport, rapport conseillé | Pilotage (rapport conseillé en jaune) |
| Seuils d'alerte régime, limiteur | Témoin de passage de rapport (jaune puis rouge) |
| Accélérateur, frein | Barres de pédales |
| Tours, total, dernier / meilleur tour | Chronos, ingénieur, stratégie |
| Carburant (litres) et capacité | Stratégie carburant |
| Position X/Y/Z de la voiture | Secteurs Pitwall + **carte du circuit** |
| Températures de surface des 4 pneus | Carte Voiture + question « radio, pneus ? » |
| Vitesse de rotation et rayon de chaque roue | Détection **roue qui bloque / patine** |
| Vitesse de lacet (rotation) + vitesse | **Forces G** latérales ; G longitudinal = variation de vitesse |
| TCS, ASM, limiteur, frein à main, phares | Témoins ; % du tour avec TCS actif dans l'historique |
| Pression turbo (si turbo), pression d'huile | Carte Voiture |
| Vitesse max de la boîte, rapports de boîte | Carte Voiture |
| Heure en piste, code voiture | Carte Voiture |
| Angle du volant (formats B / ~) | Carte Voiture |
| Récupération d'énergie (format ~, électriques) | Carte Voiture |
| Place sur la grille et nombre de voitures (avant départ) | Ingénieur (position de départ) |

Non affichées volontairement : **température d'eau et d'huile** (GT7 envoie toujours 85 et 110, ce ne sont pas
des mesures), hauteur de caisse et de suspension, embrayage, valeurs "sway / heave / surge" (unités non documentées)
et champs inconnus du format `~`. GT7 ne transmet **ni les adversaires, ni la position en course, ni les secteurs officiels**.

## Installer l'APK sur le Galaxy Fold (sans ordinateur)

1. Sur GitHub (application ou navigateur), ouvrir l'onglet **Actions** du dépôt.
2. Choisir le dernier passage réussi du workflow **APK Android**.
3. En bas, section **Artifacts** : télécharger `GT7-Pitwall-debug-apk-…` (un fichier `.zip`).
   Il faut être connecté à GitHub pour télécharger les Artifacts.
4. Ouvrir le zip avec **Mes fichiers** (Samsung) → *Extraire* → toucher le fichier `.apk`.
5. Autoriser l'installation depuis *Mes fichiers* si Android le demande.

Les APK debug sont tous signés avec la même clé (`app/debug.keystore`, clé de test, pas une clé de publication) :
une nouvelle version s'installe **par-dessus** l'ancienne, sans désinstaller.

## Utilisation

1. Le Fold et la PS5 doivent être sur le **même réseau** (même box). Le Wi-Fi 5 GHz est conseillé.
2. Trouver l'IP de la PS5 : *Paramètres → Réseau → État de la connexion → Afficher l'état de la connexion*
   (ex. `192.168.1.42`).
3. Dans GT7 Pitwall, saisir l'adresse puis **Démarrer la télémétrie**.
   Laisser le champ vide tente une recherche automatique (broadcast, à confirmer sur ta box).
4. Lancer une épreuve dans GT7 : l'indicateur passe à **En direct** et l'ingénieur fait son radio check.
5. Bouton **Tester** : vérifie que la voix française est bien installée.
   Sinon : *Paramètres Android → Gestion globale → Synthèse vocale* et installer le français.

## Ce qui est testé, et ce qui reste à vérifier sur une vraie PS5

**Testé automatiquement** (`./gradlew :core:test`, lancé à chaque build GitHub Actions) :

- l'implémentation Salsa20 contre un vecteur produit par une implémentation de référence indépendante (pycryptodome) ;
- le déchiffrement et le décodage d'un paquet chiffré par cette implémentation de référence selon le schéma GT7 ;
- un échange UDP complet en local : heartbeat → paquets chiffrés → paquets décodés, paquet corrompu ignoré ;
- le suivi des tours (chrono arrivant après le passage de ligne, connexion en cours de tour, nouvelle session) ;
- la stratégie carburant (marge, course juste, arrêt nécessaire, course au temps, voiture sans réservoir) ;
- les phrases de l'ingénieur et les alertes (une seule fois par seuil).

**Non vérifiable sans PS5, à contrôler pendant tes premiers essais :**

- [ ] la PS5 répond bien au heartbeat du téléphone (port 33739 → 33740) sur ta box ;
- [ ] la **recherche automatique** (champ IP vide) fonctionne ; sinon, saisir l'IP ;
- [ ] les valeurs affichées correspondent au jeu : vitesse, tours, chronos, carburant ;
- [ ] le **rapport 0 affiché « R »** correspond bien à la marche arrière ;
- [x] réception en direct, chronos au tour, détection de fin de course (GT7 passe à « tour 6 / 5 ») — vérifié le 13/10 ;
- [x] épreuve sans consommation (niveau bloqué à 100) — vérifié le 13/10 ;
- [ ] l'annonce de la place sur la grille avant le départ (champ GT7 « position de départ ») ;
- [ ] la stratégie carburant sur une épreuve **avec** consommation ;
- [ ] les secteurs Pitwall : cohérents d'un tour à l'autre (un tour avec pause ou retour en piste n'est pas découpé) ;
- [ ] la radio vocale : téléchargement du modèle, détection du mot de réveil avec le bruit du jeu,
      micro d'un casque **filaire / USB-C** (le micro d'un casque **Bluetooth** n'est pas encore géré) ;
- [ ] l'écoute vocale écran éteint (service avec usage micro) ;
- [x] place sur la grille transmise par GT7 avant le départ — vérifié le 13/10 (11e) ;
- [ ] la console répond au format `~` (sinon l'application repasse en `A`, visible dans la carte Connexion) ;
- [ ] le sens de la carte du circuit (elle peut apparaître en miroir) et le sens gauche/droite des forces G ;
- [ ] la détection blocage / patinage des roues ;
- [ ] le niveau de carburant GT7 (souvent sur une échelle de 100) : la stratégie fonctionne dans l'unité du jeu ;
- [ ] la réception continue **écran éteint** (service de premier plan + verrou Wi-Fi) ;
- [ ] la lisibilité de l'interface plié / déplié, et le volume de la voix pendant la course.

En cas d'écart, une capture d'écran du jeu + ce que l'ingénieur a dit suffisent pour corriger.

## Architecture

```
core/   Kotlin pur (JVM), testable sans Android
  telemetry/  Salsa20, déchiffrement GT7, décodage des paquets, client UDP (heartbeat + réception)
  session/    suivi des tours, consommation par tour, détection ravitaillement / nouvelle session
  strategy/   stratégie carburant
  engineer/   ingénieur de course (événements → phrases françaises), formats voix/écran
  analysis/   statistiques de chronos (base de l'analyse de qualification)
  radio/      interfaces VoiceOutput / VoiceInput, questions du pilote, analyse de phrases
  opponents/  point d'extension adversaires (vide : GT7 ne transmet pas ces données)
app/    Android : interface Compose, service de premier plan, Text-to-Speech, réglages
```

Prochaines étapes prévues par l'architecture :

- **Radio bidirectionnelle** : implémenter `VoiceInput` avec `SpeechRecognizer` d'Android (gratuit, français) ;
  `DriverQueryParser` transforme déjà une phrase (« combien d'essence ? ») en question pour l'ingénieur.
- **Qualifications** : enrichir `LapAnalyzer` (meilleur tour, moyenne, régularité déjà calculés).
- **Adversaires** : brancher une vraie source sur `OpponentsSource` si une solution fiable apparaît
  (saisie manuelle, analyse vidéo…). Rien ne sera simulé.
- **Formats de paquets étendus** de GT7 (heartbeats `B` et `~`) pour plus de données.

## Développement

- Kotlin 2.0, Android Gradle Plugin 8.7, compileSdk 35, minSdk 26, Java 17.
- `./gradlew :core:test` — tests unitaires ; `./gradlew :app:assembleDebug` — APK.
- Le workflow `.github/workflows/android.yml` exécute les deux à chaque push et publie l'APK dans les Artifacts.

Protocole : documenté par la communauté (projets PDTools de Nenkai, gt7dashboard de snipem…).
GT7 Pitwall n'est pas affilié à Sony Interactive Entertainment ni à Polyphony Digital.
