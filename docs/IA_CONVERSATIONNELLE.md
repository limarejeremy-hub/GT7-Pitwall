# Ingénieur conversationnel : Gemini cloud, Gemini Nano, moteur local

Objectif : discuter de la course avec l'ingénieur, pas consulter un tableau de bord. Le moteur Kotlin reste l'autorité
sur toutes les mesures, tous les calculs, les alertes et les modifications de la course ; l'IA comprend, explique,
compare et propose.

## Architecture

| Niveau | Rôle | Quand |
|---|---|---|
| **Gemini 3.5 Flash-Lite** (cloud) | conversation par défaut | questions ouvertes, conseils, relances |
| **Gemini 3.8 Flash** (cloud) | questions stratégiques complexes | mode automatique : plan, scénarios (« et si… »), choix des pneus, comparaison des relais, risques ; ou choisi à la main |
| **Gemini Nano** (téléphone) | alternative locale | mode « Gemini Nano », ou secours si le cloud ne répond pas |
| **Moteur Pitwall** | calculs, alertes critiques, secours sans IA | toujours : il calcule, et il répond seul si aucune IA ne le fait |

**Aiguillage** (`ConversationRouter`) :
- **Vers le modèle** : questions ouvertes, conseils, analyses, relances, discussions et hypothèses. La question part
  avec le **calcul du moteur** sur le sujet : plan, scénario « deux tours de plus », prochaine gomme, comparaison des
  relais, marge de carburant.
- **Restent au moteur** :
  - les commandes qui modifient la course (arrêt, pneus, règlement, position), qui passent par le garde des commandes,
    avec confirmation au besoin ;
  - les réponses aux questions de l'ingénieur (« oui », « course »…) ;
  - les questions de chiffre simples (essence, dernier tour, état des pneus), répondues tout de suite et sans quota ;
  - le test radio, les salutations et les remerciements.

**Une requête cloud correspond toujours à une vraie phrase du pilote** (ou à un test demandé à l'écran). Jamais un
paquet UDP, jamais une mise à jour du tableau de bord.

**Replis** (`AiRouter`) :
- **Quota (429) ou modèle surchargé (503)** : échec rapide, on essaie le modèle suivant. Le modèle fautif est mis en
  pause : 1 minute, doublée à chaque refus ; jusqu'à minuit (heure du Pacifique) si Google parle d'un plafond
  journalier.
- **Réseau ou délai dépassé** : pas de second essai qui ferait attendre le pilote. Le calcul du moteur est dit tel quel.
- **Aucun calcul du moteur sur le sujet** : le chemin habituel répond (Gemini Nano s'il est prêt, sinon le moteur).
- **La télémétrie n'attend jamais l'IA** : l'appel au modèle se fait hors du verrou de la course.

## Mémoire de la course (`RaceMemory`)

Elle dure le temps de la session et est remise à zéro à chaque nouvelle session. Chaque information porte sa
provenance : mesure GT7, calcul du moteur, déclaration du pilote, décision prise ensemble ou hypothèse de l'IA.

- **Plans proposés par le moteur** : chaque changement, avec sa raison.
- **Décisions prises ensemble** : « d'accord, on fait comme ça » juste après une discussion de stratégie. La décision
  est inscrite dans la mémoire et dans le journal, sans requête cloud et sans rien modifier dans la course.
- **Intentions du pilote** : par exemple « je change seulement les pneus au prochain arrêt ».
- **Faits confirmés ou corrigés** : gomme montée ou corrigée, arrêts, règlement confirmé.
- **Questions de l'ingénieur encore sans réponse.**
- **Évolution du rythme et du carburant** : moyenne par relais sur les tours propres, consommation, autonomie.
- **Six derniers échanges**, plus un résumé des plus anciens.

Le contexte envoyé au modèle est **synthétique** (`EngineerPrompt`) : faits, calcul du moteur, mémoire, quatre
derniers échanges. Jamais le flux UDP.

## Sécurité des réponses

- **Chiffres** : chaque chiffre doit figurer dans les données fournies. Une phrase qui en invente un est retirée.
- **Autres voitures** : une phrase qui parle d'écart, d'avance ou d'adversaire est retirée.
- **Pilotage** : un conseil de pilotage faux ou creux est retiré (contrôles existants).
- **Commandes** : une commande proposée par le modèle (`COMMANDE:`) passe par le garde des commandes et demande
  confirmation. Le modèle ne modifie jamais rien lui-même.
- **Réponse écartée** : c'est le calcul du moteur qui est dit, jamais une invention.

## Ce qui fonctionne tout de suite, sans Firebase

- **Sans Firebase** : l'application compile, s'installe et fonctionne exactement comme avant (télémétrie,
  chronométrage, Discord, reconnaissance vocale, bips, Fold).
- **Nouvelles réponses du moteur, même sans IA** :
  - « on garde la même stratégie ? » : comparaison avec le plan décidé ensemble ou annoncé ;
  - « on avait décidé quoi ? » ;
  - « je pilote mieux depuis le changement de pneus ? » : relais comparés sur les tours propres, en précisant que ce
    n'est pas que le pilotage ;
  - « qu'est-ce qu'on risque si j'attaque ? » ;
  - « et si je garde les durs deux tours de plus ? ».
- **Mémoire et décisions communes** : actives dans tous les modes.
- **Réglage IA** : affiche « Cloud : non configuré ». **Aucune réponse cloud n'est jamais simulée.** Le fournisseur
  simulé n'existe que dans les tests automatiques.

## Ce qui attend la configuration Firebase

- Les réponses de Gemini Flash-Lite et Flash 3.8.
- La vérification réelle des modèles disponibles pour ton projet : la première requête ou le bouton « Tester » le dira.
- Les quotas réels de ton projet. Aucun chiffre n'est codé. Le réglage affiche les requêtes **réellement envoyées**,
  et la pause suit les réponses de Google.

## Connecter ton compte Google depuis Chrome sur le Fold (sans PC, sans carte bancaire)

Conseil : fais-le Fold déplié. Si une page est trop étroite, menu ⋮ de Chrome → « Version pour ordinateur ».

1. **Créer le projet** :
   - ouvre **console.firebase.google.com** et connecte-toi avec ton compte Google ;
   - « Créer un projet », nom `gt7-pitwall` ;
   - désactive Google Analytics (inutile), puis « Créer le projet ».
   - Le projet est sur le plan gratuit **Spark**. Ne clique jamais sur « Passer au forfait Blaze » : c'est l'offre
     payante.
2. **Déclarer l'application Android** :
   - dans le projet, icône Android (« Ajouter une application ») ;
   - nom du package : **`com.gt7pitwall.app`**, surnom libre ;
   - si on te demande le certificat SHA-1, colle celui de l'APK debug :
     `87:86:5D:B8:D5:04:CF:61:DA:AD:45:80:51:B0:C1:8B:89:6A:6D:11` ;
   - « Enregistrer l'application ». Le fichier `google-services.json` est inutile : passe les étapes suivantes jusqu'à
     « Continuer vers la console ».
3. **Activer Gemini** :
   - menu → **Créer** → **AI Logic** → « Commencer » ;
   - choisis **Gemini Developer API** (sans frais) et suis les étapes ;
   - ne choisis pas l'autre fournisseur (Vertex AI / Agent Platform) : il exige la facturation.
4. **Copier la configuration dans Pitwall** :
   - Firebase : ⚙ → **Paramètres du projet** → onglet Général ;
   - Pitwall : carte **IA de l'ingénieur** → « Configurer Firebase » ;
   - colle **ID du projet**, **ID de l'application** (ligne de ton application Android, « 1:…:android:… ») et
     **Clé API Web** ;
   - « Enregistrer ». L'état passe à « configuré, pas encore vérifié ».
5. **Autoriser ton téléphone (App Check)** :
   - Pitwall, même carte : « **Copier le jeton** » ;
   - Firebase : menu → **Créer** → **App Check** → onglet **Applications** → ton application Android → ⋮ →
     **Gérer les jetons de débogage** → « Ajouter » ;
   - nom `Fold`, colle le jeton, « Enregistrer ».
   - Si la console demande d'abord d'enregistrer un fournisseur, choisis Play Integrity et donne l'empreinte SHA-256 de
     l'APK debug : `83:A2:E0:95:17:83:9E:0A:44:84:61:02:4F:30:F2:7F:45:4E:74:74:95:38:1A:65:3D:40:BA:64:F7:D4:03:D5`.
6. **Vérifier avec deux requêtes seulement** :
   - « **Tester Flash-Lite** » : 1 requête, puis « **Tester Flash 3.8** » : 1 requête ;
   - attendu : « a répondu en … ms » et l'état « Cloud : connecté » ;
   - « accès refusé (App Check ou clé du projet) » : refais l'étape 5, ou vérifie la clé de l'étape 4 ;
   - « modèle introuvable » : ce modèle n'est pas disponible pour ton projet. Dis-le-moi, il ne faut rien changer au
     hasard.
7. **Facultatif** : pour lire tes vrais quotas, ouvre **aistudio.google.com/rate-limit**.

Sécurité :
- **Clé API Web** : elle reste chiffrée sur le téléphone. Ce n'est pas un secret au sens strict (Firebase la protège
  par App Check), mais elle n'est ni dans le dépôt ni dans l'APK.
- **Jeton de débogage** : il ouvre l'accès sans attestation, garde-le pour toi. S'il fuit : « Nouveau jeton » dans
  Pitwall, puis supprime l'ancien dans la console.
- **Version publiée sur le Play Store** (build release) : Play Integrity est utilisé, sans aucun jeton de débogage.
- **Niveau gratuit** : Google peut utiliser les requêtes pour améliorer ses produits. Pitwall n'envoie que des faits de
  course résumés et tes phrases transcrites. Ni son, ni identifiant, ni flux UDP.

## Premier essai en course (2 ou 3 questions par modèle)

Mode **Automatique**. Course hors ligne de 5 à 6 tours, avec un arrêt.

1. **Tour 2** : « Radio, tu me conseilles quels pneus ? » (Flash 3.8), puis « Pourquoi ? ».
2. **Tour 3** : « Et si je garde les durs deux tours de plus ? », puis « Bon, on fait quoi alors ? », puis
   « D'accord, on fait comme ça ». La validation n'envoie aucune requête.
3. **Tour 4** : « On garde la même stratégie ? ». La réponse doit citer la décision du tour 3.
4. **Questions simples** : « combien d'essence ? », « comment sont les pneus ? ». Réponse immédiate du moteur, le
   compteur ne bouge pas.
5. **Coupure Internet** : débranche quelques minutes le câble Internet (fibre ou ADSL) de ta box. Le Wi-Fi local reste
   actif, donc la télémétrie aussi. Pose une question ouverte. Attendu : la réponse du moteur, sans blocage de la
   télémétrie ni de l'affichage. Discord a lui aussi besoin d'Internet : il se reconnecte quand tu rebranches.

Après la course : **Historique** → la session → « Export texte ». Chaque appel y figure (modèle, délai, essais,
réponse brute, verdict, repli). Envoie-moi le fichier.

## Tests automatisés (`AiConversationTest`, modèle simulé, aucune requête réelle)

1. **Choix des pneus** : la phrase réelle du tour 5 atteint Flash 3.8, avec le calcul du moteur et le sujet « pneus » ;
   rien n'est modifié.
2. **Relances** : « Pourquoi ? », puis « Et si je garde les durs deux tours de plus ? » (scénario calculé par le
   moteur), puis « Bon, on fait quoi alors ? ». Sujet et échanges conservés. « D'accord, on fait comme ça » devient une
   décision commune, sans requête.
3. **« On garde la même stratégie ? »** (phrase réelle du tour 6) : le modèle reçoit la décision. Sans modèle, la
   mémoire répond.
4. **Commande proposée par le modèle** : vérifiée par le garde, rien n'est enregistré sans confirmation.
5. **Firebase non configuré** : aucune requête, le moteur répond, la raison est affichée.
6. **Quotas** : 429 journalier sur Flash 3.8, alors Flash-Lite répond et Flash est en pause jusqu'au lendemain ;
   tout refusé, alors le calcul du moteur est dit, avec l'avis « quota atteint ».
7. **Connexion perdue** : délai dépassé ou hôte injoignable, alors le moteur répond ; la télémétrie continue pendant
   l'attente.
8. **Rien n'est gaspillé** : test radio, essence, pneus, dernier tour, paquets UDP n'envoient aucune requête. Une réponse
   qui invente un écart est écartée.

S'y ajoutent l'aiguillage des phrases réelles et des exemples, la lecture des erreurs (429, 503, réseau, App Check,
modèle introuvable, clé masquée), les compteurs par jour du Pacifique et le choix des modèles selon le mode.
