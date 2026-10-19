# Radio Discord — étude de faisabilité et prototype

Objectif : entendre l'ingénieur Pitwall **et** le jeu dans un seul casque filaire branché sur la DualSense, sans PC,
sans serveur personnel ni matériel supplémentaire. Le bot tourne sur le Galaxy Fold.

## Principe

Le casque de la manette ne reçoit que le son de la PS5. La seule entrée audio de la PS5 accessible au téléphone est
le chat vocal : le Fold se connecte comme **bot** à un serveur Discord privé, rejoint un salon vocal et y parle ; le
pilote rejoint ce salon sur la PS5. La voix de l'ingénieur arrive dans le casque, par-dessus le son du jeu.

## Diagnostic de faisabilité (phase 1)

| Point | Constat | Verdict |
|---|---|---|
| Chiffrement DAVE | Obligatoire pour la voix Discord depuis mars 2026, bots compris. | Bloquant sans bibliothèque. |
| JDA 6 | Bytecode Java 8 (lisible par Android). DAVE depuis 6.3 via une `DaveSessionFactory` fournie à part. Version retenue : **6.7.0**. | Compatible avec ajustements. |
| opus-java (JDA) | Bibliothèque native de bureau (JNA). Inutile si l'audio est fourni **déjà encodé** (`isOpus() = true`) ; JDA prévoit son exclusion. | Exclue ; Opus encodé par l'encodeur Android (MediaCodec, Android 10+). |
| tink (JDA) | Utilise protobuf-java, en conflit avec protobuf-lite de ML Kit. | Remplacé par **tink-android** (mêmes classes). |
| okhttp-jvm (JDA) | Variante JVM. | Remplacée par la variante Android d'OkHttp 5.5. |
| JDAVE | Java 25 + API FFM : **incompatible Android**. | Écarté. |
| **libdave-jvm** (KyokoBot) | Java 8, JNI vers le libdave officiel de Discord. Modules `impl-jni` et `adapter-jda` publiés sur Maven Central (0.2.0). Pas de bibliothèque native Android publiée ; un portage Android est en cours (PR #7, non fusionnée). | **Retenu.** |
| libdave-maven (KozyBlake) | Fichiers `.so` Android précompilés par un tiers, sans traçabilité. | **Écarté pour la sécurité** (ils tourneraient à côté du token). |
| Natif ARM64 | Les sources (libdave, mlspp, BoringSSL, json) sont embarquées dans libdave-jvm. Compilées par la CI avec le NDK Android (`scripts/build-dave-android.sh`, tag 0.2.0 dont l'empreinte de commit est vérifiée). Compilation hôte validée (3,2 Mo, 38 points d'entrée JNI) ; chargement par la JVM avec impl-jni 0.2.0 validé. | Faisable, sans binaire tiers. |
| Chargement | libdave-jvm cherche sa bibliothèque via `lava.native.dave-jvm.path` : on lui donne le `.so` extrait par Android (`useLegacyPackaging`). | Validé sur JVM ; à confirmer sur le Fold. |
| Service de premier plan | Le service de télémétrie existant peut porter la connexion du bot (phase 3). Le prototype fonctionne application ouverte. | À intégrer en phase 3. |

Conclusion : **faisable sur le Fold seul — validé le 19/10/2026** : bot connecté, salon vocal rejoint avec DAVE actif,
phrase test (400 trames Opus de 20 ms) entendue dans le casque de la DualSense via le chat vocal Discord de la PS5.

### Correctifs Android découverts pendant la validation

- `javax.sound.sampled.AudioFormat` absent d'Android (référencé par AudioSendHandler/AudioReceiveHandler) : descripteur
  minimal fourni dans l'app.
- `Pattern.UNICODE_CHARACTER_CLASS` refusé par Android (classe `Checks` de JDA) : copie de JDA corrigée à la
  compilation (`patchJdaForAndroid`, drapeau remplacé par 0, motif vérifié).
- OkHttp 5.4+ exige compileSdk 36/37 : OkHttp 5.3.2.
- Côté portail Discord : « Requires OAuth2 Code Grant » doit rester désactivé pour inviter le bot.

## Prototype (phase 2)

Carte « Radio Discord (prototype) » : token → connecter le bot → choisir le salon → rejoindre → **Phrase test** →
quitter / déconnecter. Le test ne nécessite pas GT7.

Chaîne audio : synthèse vocale Android (même voix française) → WAV → 48 kHz mono → Opus 20 ms (MediaCodec) →
JDA → chiffrement DAVE (libdave) + chiffrement de transport → Discord → PS5 → casque de la DualSense.

## Sécurité du token

- Saisi uniquement sur le téléphone, jamais dans le code, GitHub, l'APK ni les journaux.
- Chiffré AES-256-GCM avec une clé de l'**Android Keystore** (la clé ne quitte pas le matériel sécurisé) ; seul le
  texte chiffré est stocké. Sauvegarde Android désactivée.
- Journal de repli de JDA désactivé ; le token est masqué dans tout message d'erreur.
- « Effacer le token » supprime le texte chiffré et la clé.

## Mise en place côté Discord (une fois, ~10 min)

1. Créer un compte Discord et le **relier au compte PlayStation** (Discord → Paramètres → Connexions → PlayStation
   Network).
2. Créer un serveur privé (« + » → Créer le mien → Pour moi et mes amis), par exemple « Pitwall », avec un salon
   vocal « Radio ».
3. Sur <https://discord.com/developers/applications> (navigateur, mode ordinateur) : *New Application* « Pitwall » →
   onglet **Bot** → *Reset Token* → copier le token. Aucun « Privileged Gateway Intent » n'est nécessaire.
4. Onglet **OAuth2 → URL Generator** : portée `bot` ; permissions **View Channels**, **Connect**, **Speak**. Ouvrir
   l'URL générée et ajouter le bot au serveur « Pitwall ».
5. Dans Pitwall : carte Radio Discord → coller le token → *Enregistrer* → *Connecter le bot* → choisir « Radio » →
   *Rejoindre le salon*.
6. Rejoindre le salon « Radio » **sur la PS5** (depuis l'appli Discord du téléphone : « Rejoindre sur PlayStation »,
   ou depuis la PS5 si Discord y est disponible), puis *Phrase test* : la voix doit sortir dans le casque de la manette.

## Limites identifiées

- JDA n'est pas officiellement pris en charge sur Android ; un portage communautaire signale son fonctionnement
  (envoi de voix), mais rien ne remplace l'essai sur le Fold.
- DAVE : une session à deux participants (bot + pilote) est le cas le plus simple ; des soucis sont signalés côté
  JDAVE au-delà de deux (non concerné ici, mais à surveiller).
- Bibliothèque DAVE pour **arm64 uniquement** (le Fold) ; absente d'une compilation locale sans NDK.
- Latence ajoutée par Discord : de l'ordre de quelques centaines de millisecondes.
- Connexion permanente du bot : un peu de batterie et de données mobiles / Wi-Fi.
- Prototype : la connexion vit tant que l'application tourne ; reconnexion automatique, priorités des alertes et retour
  au haut-parleur du Fold en cas de panne arrivent en phase 3.
- Réception du micro du casque PS5 par le bot : étape ultérieure (non commencée).
