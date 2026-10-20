# Radio : bips et conversation continue

## Fonctionnement

- **« Radio » une fois**, rien n'est dit :
  - un **bip court** retentit quand le micro écoute vraiment : Google prêt, ou Vosk aussitôt ;
  - la conversation est ouverte, et l'écran affiche « Conversation ouverte ».
- **Questions successives sans « Radio »** :
  - après chaque réponse, l'écoute reprend toute seule, sans bip ;
  - chaque phrase intelligible prolonge la fenêtre (15 s par défaut, réglable 10 / 15 / 20 / 30 s dans la carte
    « Radio vocale ») ;
  - le temps où l'ingénieur parle ne compte pas.
- **Fin** :
  - « Terminé », « Fin de communication » ou « Merci, terminé » ferment tout de suite ;
  - sinon, la conversation se ferme après la fenêtre de silence ;
  - dans les deux cas, un **double bip** plus grave retentit, et le mot de réveil redevient nécessaire.
- **« Radio, combien d'essence ? »** dit d'un trait : la demande part, et la conversation reste ouverte. Le bip
  retentit une fois, à la reprise d'écoute après la réponse.
- **« Je t'écoute » n'est plus jamais prononcé.** L'état reste affiché discrètement.

## Signaux

- **Les bips sont de vrais échantillons PCM** générés par le code (`RadioTones`), pas un ToneGenerator :
  - **casque PS5** : encodés en Opus comme la voix, ils passent dans la même file que les phrases du bot Discord. Un
    bip n'est donc jamais au milieu d'une phrase ;
  - **Fold** : joués par AudioTrack, sur la même sortie que la voix de l'ingénieur.
- **Bip court** : 1 175 Hz, 90 ms.
- **Double bip** : 660 Hz puis 523 Hz, avec un silence de 70 ms entre les deux.

## Synchronisation

- **État de diffusion unifié** : « l'ingénieur parle » réunit deux sources :
  - la voix du Fold (synthèse Android) ;
  - le bot Discord : de la première trame à la fin de la file, plus 300 ms pour la lecture dans le casque.
- **Pendant qu'il parle**, le micro est en pause (casque comme Fold) ; il ne reprend qu'après la fin réelle de la
  phrase.
- **Conversation et silences** : une écoute Google sans parole n'est ni un échec ni un message au pilote. Vosk
  continue d'écouter jusqu'à la fin de la fenêtre.
- **Pas de fermeture au milieu d'une phrase** : la conversation ne se ferme pas pendant que le pilote parle (phrase en
  cours chez Vosk) ni pendant une reconnaissance Google en cours.
- **Arrêt de l'écoute, bot qui quitte le salon, micro perdu** : la conversation est refermée sans bip.

## Sécurité

- **Phrase sans « Radio » devant** pendant la conversation : elle peut venir du jeu ou d'un autre participant. Toute
  modification de la course (arrêt, pneus, règlement, position, météo) attend un « oui » (`CommandSource.CONVERSATION`,
  même garde que pour Gemini).
- **Les questions** ne modifient jamais rien.
- **Micro du casque** : seule la voix du pilote est écoutée (le seul humain du salon, ou le premier qui parle). Le bot
  ne reçoit pas sa propre voix.
- **Messages automatiques de course** : ils n'ouvrent jamais de conversation. Une question de l'ingénieur
  (« As-tu changé les pneus ? ») ouvre comme avant une écoute pour la seule réponse.

## Protocole PS5 (environ 10 minutes)

### A. Casque PS5 via Discord

1. **Ouverture** : bot dans le salon, micro « casque PS5 » actif. Dis « Radio ».
   - Attendu : un bip court dans le casque, aucune phrase.
2. **Trois questions de suite, sans « Radio »** : « Quelle est notre meilleure stratégie ? », puis « Combien de
   carburant pour finir ? », puis « Comment vont mes pneus ? ».
   - Attendu : trois réponses, aucun bip entre elles, la fin des réponses non coupée.
3. **Fermeture sur silence** : 15 s de silence.
   - Attendu : un double bip ; « Combien d'essence ? » sans « Radio » n'a plus de réponse.
4. **Demande d'un trait** : « Radio, quelle est ma position ? », puis après la réponse « et combien de places
   gagnées ? ».
   - Attendu : un bip court après la première réponse, puis la seconde réponse sans « Radio ».
5. **Fermeture sur commande** : dis « Terminé ».
   - Attendu : un double bip immédiat.
6. **Sécurité** : ouvre (« Radio »), attends la première réponse, puis dis « arrêt fait » sans « Radio ».
   - Attendu : « Tu confirmes… ? ». Réponds « non » : aucun arrêt compté.
7. **Messages automatiques** : pendant un tour, un message passe (fin de tour).
   - Attendu : aucune conversation ouverte, aucun bip.

### B. Micro du Fold

8. **Même essai** : refais 1, 2 et 5 avec le micro du Fold (bot hors du salon).
   - Attendu : les bips sortent du Fold, et la réponse n'est pas captée comme une question.

### C. Interruptions

9. **Réponse coupée** : pendant une réponse, une alerte « Box, box » doit passer en priorité. Ensuite, l'écoute reprend
   si la conversation est encore ouverte.
10. **Déconnexion** : fais sortir le bot du salon pendant une conversation.
    - Attendu : l'écoute repasse au Fold, sans double bip ni phrase.

À noter : toute réponse coupée en fin de phrase, tout bip manquant ou en trop, et toute phrase du jeu prise pour une
demande.
