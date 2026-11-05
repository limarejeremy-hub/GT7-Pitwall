# ChatGPT via le forfait du pilote (issue #67, jalon 1)

Prototype **opt-in**, isolé. Connexion et réponse GPT texte **confirmées par le pilote sur le Galaxy Fold le 05/11/2026** (V488, modèle `gpt-6.1-sol`, test carburant vérifié en ~4 s) ; la radio (voix, Discord PS5) n'est pas validée. Le mode par défaut ne change pas ; ChatGPT n'est utilisé que si
le pilote choisit « ChatGPT via ton forfait (Plus), sans clé d'API » dans la carte « IA de l'ingénieur ».

## Ce qui est utilisé

« Sign in with ChatGPT » d'OpenAI (aperçu pour les applications open source ou hébergées localement), d'après la
documentation officielle `developers.openai.com` lue le 05/11/2026 (pages Sign in, Accounts and sessions, Models and
inference, Errors and recovery, Preview limitations, UI/UX guidelines).

- **Aucune clé d'API.** Ni dans l'APK, ni dans le code, ni dans GitHub Actions, ni dans les journaux.
- Connexion dans le navigateur (OAuth 2.0 + PKCE S256, client public sans secret). Retour sur
  `http://127.0.0.1:1455/auth/callback` (ou un autre port libre : seul le port peut varier).
- Première inscription : `client_id=dynamic_agent_client` + `agent_name_hint=GT7 Pitwall` ; ensuite le client attribué
  (`oaiapp_…`) est réutilisé, avec le même `ext_agent_host_id` stable.
- Vérifications avant d'accepter quoi que ce soit : `state`, `client_id` renvoyé, puis jeton d'identité (signature RS256
  contre le JWKS, émetteur `https://auth.openai.com`, audience = client attribué, expiration, `nonce`).
- Portée `chatgpt.tokens.use.direct` absente de la réponse → état **« Connecté mais quota non autorisé »** : aucune
  requête au modèle.
- Jetons et e-mail chiffrés par l'Android Keystore (AES-256-GCM), jamais affichés ni journalisés (`toString()` masqué).
  En clair dans les préférences privées : identifiant d'hôte (opaque), client attribué (pas un secret), étapes de connexion
  (sans secrets), drapeau de bienvenue. (V488 gardait aussi l'e-mail en clair : retiré en V489.) Une erreur de
  déchiffrement est signalée, jamais une raison d'effacer.
  Rafraîchissement sérialisé près de l'expiration (jeton d'accès 1 h, jeton de rafraîchissement tournant).
  Déconnexion : révocation via le `revocation_endpoint` de la découverte OpenID, puis effacement local.
- Modèle : premier modèle `visibility: "list"` de `GET /v1/models` pour le compte.
- Inférence : `POST /v1/responses` avec `store: false`, `stream: true`, consigne dans `instructions`, aucun champ non pris
  en charge (température, nombre maximal de jetons…). Réponse acceptée seulement après `response.completed`.

## Garde-fous de la radio

ChatGPT est un fournisseur de plus du routeur existant : mêmes contrôles que Gemini. Une phrase dont un chiffre n'est
pas dans les faits du moteur est retirée ; une commande est rejouée par l'analyseur et confirmée par le moteur.
Tout échec (pas de réseau, limite du forfait, refus, flux coupé) → **réponse du moteur local immédiatement**. Aucune
bascule vers une API payante.

## États affichés

| État | Quand |
|---|---|
| Non connecté | aucun jeton enregistré |
| Connecté mais quota non autorisé | connecté sans la portée du forfait |
| Disponible | portée accordée, jetons valides |
| Limite atteinte | `subscription_sharing_usage_limit_exceeded` (429) ; « Gérer l'utilisation » ouvre `chatgpt.com/settings/usage` |

Pour ChatGPT Plus, la limite sur 5 heures est partagée entre toutes les applications qui utilisent le forfait.

## Étapes de connexion (V489, revue #69)

Chaque tentative note : Navigateur lancé → Retour OAuth reçu → Code échangé → ID token vérifié → Permissions accordées →
Credentials enregistrés → Modèle trouvé → Premier appel GPT confirmé. La carte affiche la dernière étape et l'erreur
(codes HTTP / OAuth seulement) ; « Copier le diagnostic » produit un texte sans jeton, code, JWT, e-mail, UUID ni
adresse de retour (`SignInTrace.redact`). « Autorisé » et « GPT réellement interrogé » sont distingués.

Le retour est reçu par `LoopbackReceiver` (core, testé avec de vraies sockets) : écoute ouverte avant le navigateur,
fermée tout de suite à l'annulation, délai par connexion (une connexion muette ne bloque pas), délai total de 5 min,
une seule tentative à la fois. Le client attribué est enregistré dès le retour : un `invalid_grant` à l'échange ne
refait pas une inscription.

## Écouter Véga

« Écouter Véga » lit la réponse déjà affichée du test avec la voix Android du téléphone (haut-parleur du Fold), sans
nouvelle requête. C'est GPT texte + synthèse vocale locale : pas une voix « GPT Live », pas Discord.

## Test du jalon

« Tester : Quel est mon plan carburant ? » envoie **une** requête sur des faits de **simulation** (`FuelPlanCheck`,
affichés comme tels). La réponse n'est montrée que si chaque chiffre vient des faits ; sinon, ou sans réseau/forfait, la
réponse locale est donnée.

## Vérifié / non vérifié

- Vérifié sur PC (`ChatGptPlanTest` 16 tests, `ChatGptSignInTest` 8 tests avec sockets réelles, aucun appel réseau, jetons et clés simulés) : paramètres d'autorisation,
  PKCE (vecteur RFC 7636), validation du retour, corps des requêtes de jetons, vérification du jeton d'identité,
  lecture du flux, codes d'erreur officiels, repli local, mode local sans appel.
- Vérifié par le pilote sur le Fold (V488) : connexion, retour 127.0.0.1, modèle listé, réponse au test carburant.
- **Non vérifié** : V489 sur le Fold (étapes, Écouter Véga), autres navigateurs, Fold plié/déplié pendant la connexion,
  redémarrage de l'application, rafraîchissement après 1 h, la radio (voix en course, Discord PS5).

## Licence

L'aperçu vise les applications open source ou hébergées localement. Le dépôt n'a pas de fichier `LICENSE` : le choix
d'une licence revient au propriétaire du dépôt (proposition seulement, rien n'est imposé ici).
