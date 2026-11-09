# Diction française, même voix (V505, #73 P1-C)

Symptôme rapporté (course du 06/11) : « onze » entendu « on », /s/ et /z/ parfois tronqués en début ou en fin de
phrase. **La voix féminine installée, le débit et la hauteur ne changent pas**, et le forfait non plus.

## Trois étages distincts

1. **Texte (normalisation audio seulement)** — `SpokenFrench.forAudio`, appliqué juste avant la synthèse (haut-parleur
   du Fold et Discord). L'écran, le journal et la mémoire gardent le texte d'origine.
   - « P11 » → « P onze » (plus de sigle collé lu comme un mot).
   - Entiers isolés de 0 à 20 en lettres (« 11 tours » → « onze tours », « 0 » → « zéro »). Les chronos, décimales,
     heures et codes (« 1:59.523 », « 48,3 », « Gr.3 », « N300 », « T7 ») ne sont jamais touchés.
   - Ponctuation finale garantie : le moteur termine la phrase au lieu de la couper.
2. **Synthèse sur le haut-parleur** — un silence de 150 ms après chaque message : la sortie audio reste ouverte le temps
   que la dernière consonne soit jouée en entier.
3. **Discord (PCM → Opus)** — 60 ms de silence en tête (le salon ouvre la voix, le début de phrase n'est plus mangé) et
   160 ms en queue (au-delà du délai interne de l'encodeur Opus et de la dernière trame complétée).

## Écoute avec le pilote (à faire)

Atelier → Connexion PS5 → **« Test de diction (onze, douze, seize, zéro…) »**. Les phrases passent par la sortie active
(Fold, ou Discord si l'ingénieur y parle) : « P11. », « Position 11. », « Il reste 11 tours. », « 11, 12, 13, 16, 0. »,
« Sans souci, les pneus tiennent seize tours. », « Zéro arrêt obligatoire, mais deux gommes imposées. ».

À noter pour chaque sortie (Fold, Discord) : « onze » entier ? /s/ et /z/ entiers au début et à la fin ? Si « onze »
reste « on » sur le Fold seul, la cause est le moteur de synthèse (forme de texte à essayer : « position numéro onze ») ;
sur Discord seul, l'encodage ou le salon.

## Validation

- `SpokenFrenchTest` (4) : formes de texte et silence ajouté. **Ces tests ne prouvent pas la prononciation.**
- Non vérifié : écoute réelle sur le Fold et dans Discord.
