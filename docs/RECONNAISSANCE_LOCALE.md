# Reconnaissance vocale du micro PS5 : audit et alternatives locales

## 1. Audit de Google sur le flux du casque

### Ce que dit Android
`RecognizerIntent.EXTRA_AUDIO_SOURCE` (Android 13+) est **facultatif**. D'après la documentation du framework,
une reconnaissance qui ne gère pas cette option ouvre le micro du téléphone, sans erreur ni signal. C'est pourquoi
`onReadyForSpeech`, `onBeginningOfSpeech` et le niveau sonore (`onRmsChanged`) ne prouvent **rien** : ils peuvent
venir du micro du Fold, qui capte la télé et la pièce, et non du casque.

### Ce que Pitwall transmet (vérifié dans le code)
- **Format :** PCM 16 bits little-endian, mono, 16 kHz, déclaré à Google par `EXTRA_AUDIO_SOURCE_ENCODING`,
  `CHANNEL_COUNT` et `SAMPLING_RATE`. C'est le même flux que Vosk reconnaît correctement.
- **Cadence :** blocs de 0,25 s, au rythme de Discord. Entre deux paquets de voix, `DiscordMicSource` insère du silence.
- **Transport :** un tube `ParcelFileDescriptor.createPipe()`. Un fil dédié écrit dans le tube, ce qui évite de
  bloquer la boucle d'écoute.
- **Début :** le flux commence quand la radio passe à Google, juste après « radio » seul.
- **Fin :** `SpeechEndpointer` ferme le flux après 1,2 s de silence suivant la voix, après 7 s sans voix, ou au bout
  de 10 s. La fermeture du tube signale à Google la fin du flux.
- **Délai de grâce (nouveau) :** une fois le flux fermé, Google a 2,5 s pour répondre. Sinon l'essai est arrêté sans
  attendre ses 12 s.

### La preuve ajoutée
Le flux passe par un tube. Les octets écrits dans le tube, moins ceux qui y sont encore à la fin, ont forcément été
**lus par la reconnaissance**. Pitwall ne lit jamais ce tube : `available()` donne seulement le nombre d'octets en
attente. Au premier usage, une vérification sur un tube d'essai de 1 000 octets s'assure que cette mesure fonctionne
sur le téléphone. Si elle ne fonctionne pas, aucune conclusion n'est tirée.

| Mesure | Conclusion |
|---|---|
| ≥ 0,5 s d'audio sortie du tube | flux **lu** |
| ≥ 0,5 s d'audio écrite, rien n'est sorti (tube plein) | flux **NON lu** : le moteur écoutait autre chose |
| moins de 0,5 s, ou mesure impossible | aucune conclusion |

La carte « Diagnostic radio » affiche pour chaque essai :
- le moteur utilisé ;
- les octets envoyés, la durée, les octets lus ;
- le délai avant le 1er bloc et la raison de la fermeture du flux ;
- la conclusion et la bascule éventuelle.

### Décisions automatiques (`StreamEnginePolicy`, testées)
- **Ordre d'essai :** Google sur l'appareil, puis le service Google standard.
- **Écart immédiat :** un moteur dont on a **prouvé** qu'il ne lit pas le flux.
- **Écart au 2e coup :** « lu, voix présente, mais rien compris », un refus, une absence de réponse.
- **Sans conséquence :** une erreur réseau, une annulation, aucune voix dans le flux.
- **Les deux moteurs écartés :** Google est coupé **pour le casque seulement**. Après « radio » seul, Vosk écoute
  directement, sans nouvel échec ni attente. Ce choix est mémorisé ; le bouton « Réessayer Google sur le casque PS5 »
  l'efface.
- **Micro du Fold :** ses échecs sont comptés à part. Ceux du casque ne suspendent plus Google sur le Fold.
- **Aucune question perdue :** pendant chaque essai de Google sur le casque, Vosk écoute la même phrase. Si Google ne
  rend rien, la phrase de Vosk est utilisée. « Répète » n'est demandé que si Vosk n'a rien entendu non plus.

## 2. Alternatives gratuites et locales qui lisent directement le PCM de Discord

Toutes ces solutions fonctionnent sans réseau ni abonnement, et reçoivent directement les échantillons 16 kHz décodés
de Discord. Elles n'ont donc pas besoin du micro, ni de l'autorisation micro en arrière-plan.

Les chiffres ci-dessous sont des **ordres de grandeur publiés ou estimés**, mesurés en anglais et sur d'autres
téléphones. Rien n'a encore été mesuré sur le Fold, en français, avec la voix du casque PS5.

| | Vosk (actuel) | sherpa-onnx streaming zipformer FR | Whisper base, via sherpa-onnx ou whisper.cpp | Whisper small |
|---|---|---|---|---|
| Mode | continu (streaming) | continu (streaming) | phrase entière | phrase entière |
| Précision FR libre | moyenne (petit modèle) | à mesurer (entraîné sur Common Voice FR) | correcte | bonne |
| Vocabulaire limité possible | oui (grammaire) | mots-clés (« hotwords ») | non | non |
| Latence après la fin de phrase | ~immédiate | ~immédiate | ~0,5-1,5 s estimé | ~2-5 s estimé |
| Taille du modèle | ~40 Mo | ~70-120 Mo (int8) | ~60-150 Mo | ~190-480 Mo |
| Mémoire vive | ~100 Mo | ~150-250 Mo estimé | ~200-300 Mo estimé | ~500-800 Mo estimé |
| Risque particulier | erreurs sur les phrases libres | modèle de 2023, peu d'usage en français | invente du texte sur le silence (à filtrer par détection de voix) | lent, chauffe |
| Android ARM64 | oui (AAR) | oui (bibliothèques arm64 publiées, API Kotlin) | sherpa-onnx : oui ; whisper.cpp : compilation NDK en CI, comme libdave | idem |
| Écran verrouillé | oui (service au premier plan) | oui en principe (calcul CPU dans le service) | oui en principe | oui en principe, mais lent si le CPU est bridé |
| Licence | Apache 2.0 | Apache 2.0 | Apache 2.0 / MIT, modèles Whisper MIT | idem |

### Recommandation
1. **Garder Vosk** comme moteur principal du casque tant qu'aucun prototype n'est validé sur le Fold.
2. **Prototyper avec sherpa-onnx :** une seule bibliothèque donne deux candidats, le zipformer français en continu et
   Whisper base ou small. Elle fournit aussi une détection de voix (Silero), qui empêche Whisper d'inventer du texte sur
   le silence.
3. **Mode « banc d'essai » :** le prototype écoute la même phrase que Vosk, en parallèle, et n'affiche que son texte et
   sa latence dans le diagnostic. Il ne déclenche **aucune commande**. Le modèle est téléchargé au premier usage, comme
   celui de Vosk, pour ne pas alourdir l'APK.
4. **Mesures à faire sur le Fold :**
   - 20 phrases radio types ;
   - phrases correctes en % pour chaque moteur ;
   - latence ;
   - mémoire ;
   - écran verrouillé en course ;
   - batterie et chauffe sur 30 minutes.
5. **Remplacement :** il ne se fait qu'après ces mesures.

whisper.cpp reste une solution de repli si sherpa-onnx pose problème. Elle est plus lourde à intégrer (compilation NDK
dans la CI), pour une précision équivalente à modèle égal.
