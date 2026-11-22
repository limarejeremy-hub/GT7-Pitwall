# V509 — Voix de Véga : diagnostic du moteur et banc A/B

## Qui produit réellement le son (build V508)

- **Discord (casque PS5).** `DiscordRadio` → `SpeechPcm` → `TextToSpeech.synthesizeToFile` (synthèse Android du
  téléphone). Le WAV est ensuite rééchantillonné en 48 kHz puis encodé en Opus.
- **Haut-parleur du Fold.** `TtsVoiceOutput` → `TextToSpeech.speak` (même moteur Android).
- **Aucune voix GPT.** En mode ChatGPT, seul le **texte** de la réponse peut venir de GPT ; le son vient toujours de la
  synthèse Android. C'est pourquoi la voix du casque est celle du Fold.

La carte « Voix de Véga : diagnostic et banc A/B » (onglet VEGA) affiche, relevés sur le téléphone :
- le moteur par défaut (paquet et nom) ;
- la voix (nom, langue, voix locale ou réseau, qualité) ;
- la fréquence native de la dernière phrase envoyée sur Discord et la durée de sa synthèse.

## Pourquoi B (GPT-Live) n'est pas branché

- GPT-Live (`gpt-live-1`, voix française féminine `brise`) s'ouvre avec une **clé d'API de projet OpenAI**. Il est
  facturé 0,05 $ par minute de voix, plus le modèle d'arrière-plan. Source : developers.openai.com, guides GPT-Live,
  lus le 19/11/2026.
- La connexion « Sign in with ChatGPT » de Pitwall ne couvre que l'API Responses en texte. La page *Preview
  limitations* précise : « Audio/video input… not supported by this flow ».
- Le pilote a choisi de ne pas utiliser de clé payante. B reste désactivé, et rien n'est simulé à sa place.

## Protocole (voix A, dans le casque Discord)

1. « Véga, je finis avec ce carburant ? » : on mesure le délai et on juge le naturel.
2. « Et si je rentre maintenant ? », sans redire « Véga » : la relance doit être prise sans mot d'appel.
3. Pendant sa réponse : « Attends, il reste combien de tours ? ». On mesure depuis combien de temps elle parlait,
   puis le délai de la nouvelle réponse.
4. Bouton « Prononciation » : « Onzième sur quinze. », « Tu es onzième sur quinze… », « P11 sur 15. ».

Les trois phrases sont comprises localement : réponse carburant, stratégie « arrêt plus tôt », état de course.

**Délai mesuré** = de la fin de ta phrase au premier son envoyé au salon (`LatencyTrace`). Le tampon de Discord et de
la PS5 s'y ajoute et n'est pas observable. Aucun son n'est enregistré. « Exporter » partage le texte des résultats.

Rien n'est validé sur PS5 ni sur Fold depuis le PC.
