# V510 — Prototype B « GPT-Live » simulé

GPT-Live réel : **désactivé**. La radio actuelle (A) reste disponible pour le comparatif, dans le même salon Discord.

## Ce que fait B simulé

- **Activation manuelle uniquement** : bouton « B simulé (5 min) » de la carte VEGA « Voix de Véga ». Le salon Discord
  doit être ouvert.
- **Durée** : 5 minutes au plus (`LiveSession.FIRST_TRIAL_MAX_MS`), puis fermeture automatique. Le bouton « Arrêter B »
  la ferme avant.
- **Protocole** : le même que GPT-Live en délégation client. Pitwall joue le rôle du « backend » :
  1. `session.start` (modèle `gpt-live-1`, voix `brise`, française féminine, délégation client) ;
  2. `session.started` ;
  3. `session.input_audio.append` : le micro du casque est envoyé, à 24 kHz ;
  4. transcription de ta phrase, puis `session.delegation.created` ;
  5. Pitwall calcule la réponse sur l'état de course (même calcul que A) et la rend par `session.thinking.append` puis
     `session.commentary.append` ;
  6. `session.output_audio.delta` : la réponse arrive en morceaux de 20 ms, encodés en Opus en flux (un seul encodeur
     par réponse).
- **Relances sans « Véga »** : pendant la session, chaque phrase va à B. La radio garde l'écoute ouverte
  (`expectReply`).
- **Coupure** : dès que la radio entend ta voix pendant que Véga parle, la lecture locale est vidée et la génération
  s'arrête. Une réponse arrivée après une nouvelle question est jetée.
- **Voix A pendant B** : seules les alertes critiques sont dites, pour ne pas mêler deux voix pendant l'essai.

## Ce que la simulation ne montre PAS

- **La voix de GPT-Live.** Le serveur simulé fait parler la synthèse Android (la voix A).
- **La compréhension de GPT-Live.** Le simulateur ne comprend pas l'audio : il s'appuie sur la transcription de la radio
  locale (Vosk ou Google). Le vrai service écouterait lui-même le micro.
- **Le délai de GPT-Live.** Les délais affichés sont ceux de la simulation.

Cet essai juge donc le **déroulé** : relances sans mot d'appel, coupure à tout moment, rendu de la parole, limite de
durée. Il ne juge ni la voix française ni le naturel de GPT-Live : il faudra le vrai service pour cela.

## Future intégration réelle : clé côté serveur

Il n'y a aucune clé dans l'APK, le code, GitHub Actions ou les journaux. `LiveConfig` n'a pas de champ de clé, et un
test le vérifie. Le passage au réel ne change que le transport (`LiveTransport`) :

```
Fold (Pitwall) ──WebRTC ou WebSocket──► relais du pilote (garde la clé OpenAI) ──► api.openai.com/v1/live/sessions
```

- **Le relais.** C'est un petit serveur ou une fonction cloud qui appartient au pilote. Il crée la session avec la clé
  du projet, comme l'exemple WebRTC d'OpenAI où « le serveur crée la session et échange l'offre de connexion ». Il ne
  renvoie au Fold que la réponse de connexion, jamais la clé.
- **Côté Fold.** Pitwall garde la délégation client : l'état de course ne quitte le Fold que sous forme de réponses
  courtes.
- **Avant de le construire, il faut décider de plusieurs points :**
  - ce relais déroge à « zéro backend » et à « aucun serveur obligatoire ». Il resterait facultatif, B étant une option ;
  - le coût : 0,05 $ par minute de voix, plus le modèle ;
  - l'hébergement et la protection du relais (accès réservé au Fold du pilote).

## Essai au casque (5 minutes au plus)

1. Rejoins le salon Discord sur la PS5, puis appuie sur « B simulé (5 min) ».
2. « Véga, je finis avec ce carburant ? »
3. Sans redire « Véga » : « Et si je rentre maintenant ? »
4. Pendant sa réponse : « Attends, il reste combien de tours ? ». Elle doit se taire tout de suite et répondre à la
   nouvelle question.
5. Compare avec A : « Démarrer A » et les mêmes phrases, puis « Prononciation » pour « onzième sur quinze ».

Rien n'est validé sur PS5 ni sur Fold depuis le PC.
