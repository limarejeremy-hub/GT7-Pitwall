# Touche radio DualSense par la télémétrie GT7 — phase 1 : la sonde

Issue #38. Branche `claude/v438-dualsense-probe`, partie de `claude/v426-radio-hotfix` (APK #432, radio validée).
Elle ne dépend pas de la PR #37 (brouillon) et ne la contamine pas.

## Ce qui est vrai et ce qui ne l'est pas

- **Le téléphone ne lit pas la DualSense.** Elle est appairée à la PS5, pas au Fold. Seul l'**effet** d'une touche
  peut apparaître dans la télémétrie UDP, si GT7 l'y reflète.
- **Piste étudiée** : la touche d'appel de phares. Le mot 0x8E contient des bits d'**état** :
  - bit 7 : feux ;
  - bit 8 : pleins phares ;
  - bit 9 : feux de croisement.

  Sources : gt7-udp, PDTools, et notre `TelemetryPacket`. Ce sont des états, **pas des événements de bouton**. Rien ne
  prouve encore qu'un appel de phares bref fasse passer le bit 8 à 1 puis à 0, ni que le relâchement soit visible.
- Ces bits existent **dès le format A** (296 octets). Si la course Sport n'envoie que le format A (voir
  `docs/UDP_FIELDS.md` dans la PR #37), ils resteraient disponibles.
- **Rien n'est validé** tant qu'un essai PS5 n'a pas été exporté et lu.

## Architecture de la phase 1 (livrée)

- `TelemetryPacket.isHighBeam` / `isLowBeam` : simples accesseurs du mot 0x8E.
- `ControllerRadioProbe` (module core, pur, testé) :
  - désactivée par défaut ;
  - n'émet **aucun** message ;
  - n'appelle **jamais** la radio (`VoskVoiceInput` n'est pas modifié).
- Ce qu'elle suit :
  - les bits feux, pleins phares, croisement ;
  - le frein à main (bit 6, lui aussi lié à une touche) ;
  - les bits non documentés 12 à 15 : une touche pourrait s'y refléter.
- Ce qu'elle enregistre :
  - l'état initial (feux déjà allumés au premier paquet) ;
  - chaque transition, horodatée, avec le format, l'état de session (en piste, pause, chargement) et la vitesse ;
  - la durée de chaque épisode « allumé ».
- Classement des épisodes :

  | Type | Durée |
  |---|---|
  | Impulsion | 1 à 3 paquets |
  | Appui bref | moins de 0,5 s |
  | Appui maintenu | 0,5 à 2 s |
  | Long | plus de 2 s |
  | Permanent | toujours allumé |
  | Non mesurable | coupure du flux de plus de 500 ms, pause, chargement, ou début inconnu |

- Elle note aussi :
  - la fréquence des paquets ;
  - l'écart entre paquets au 95e centile (latence locale approximative) ;
  - les coupures du flux ;
  - les changements de voiture ;
  - le mode de session tel que Pitwall le connaît.
- **Repères du pilote** (« J'appuie maintenant », dans l'Atelier, voiture à l'arrêt) : un allumage dans les 2,5 s qui
  suivent un repère lui est rattaché.
- **Conclusion prudente** :
  - sans repère : « aucune attribution possible » ;
  - avec repères mais un allumage non expliqué : « pas de correspondance démontrée » ;
  - au moins 5 repères tous suivis d'un allumage, et aucun allumage sans repère : « concordance sur cet essai
    seulement, à refaire en Sport ». Jamais « touche détectée ».
- **Journal exportable** : texte (`t_ms;événement`, puis la liste des épisodes). Ni son, ni adresse réseau, ni token.
- **Atelier** → carte « Sonde touche radio DualSense (expérimental) » : interrupteur, repère, résumé rafraîchi chaque
  seconde, export, effacer.

## Tests (JVM, trames synthétiques)

`ControllerRadioProbeTest` (8). Ils prouvent la mesure et la prudence, **pas** le comportement de GT7.

| Cas | Attendu |
|---|---|
| Impulsions de 1 et 3 paquets, 0,3 s, 1 s, 2,4 s | Impulsion, impulsion, bref, maintenu, long |
| Pleins phares allumés au premier paquet | Permanent, puis « non mesurable » à l'extinction (début inconnu) ; verdict « indétectable » |
| Feux automatiques (seul le bit 7 change) | Suivis à part ; pleins phares « jamais changé » |
| Double appui | Deux épisodes |
| Pause pendant l'appui ; coupure UDP de 2 s puis reprise | Non mesurables ; coupure comptée |
| Format A (296 octets) | Fonctionne |
| Transitions sans repère ; repères avec un allumage en trop ; 5 repères concordants | Aucune attribution ; pas de correspondance ; concordance limitée à l'essai |
| Sonde désactivée ; reconnexion avec changement de voiture | Rien n'est enregistré ; coupure et changement journalisés, aucune adresse |

## Protocole PS5 (hors ligne d'abord, jamais en ligne avant validation)

Préparation :
- Atelier → « Sonde touche radio DualSense » : activer.
- Course hors ligne, voiture **à l'arrêt**, Fold en main : tu ne conduis pas pendant cet essai.

1. **Repos.** 10 s sans rien toucher. Le résumé doit montrer l'état initial et aucune transition.
2. **Appels courts.** Cinq fois : toucher « J'appuie maintenant », poser le Fold, faire **un** appel de phares bref.
   Laisser 5 s entre chaque.
3. **Appuis maintenus.** Cinq fois : repère, puis maintenir l'appel de phares environ 1 s.
4. **Feux.** Allumer puis éteindre les phares fixes, s'ils sont disponibles sur la voiture. Si possible, refaire de
   nuit pour voir les feux automatiques.
5. **Autres touches.** Si GT7 permet d'attribuer l'appel de phares à une autre touche, refaire l'étape 2 avec elle.
6. **Exporter** le journal et l'envoyer.
7. Seulement si les étapes 2 et 3 concordent : refaire en **ligne droite sûre**, hors ligne, puis en qualification
   Sport (pas en course).

Ce que le journal permettra de trancher :
- l'appel de phares change-t-il le bit 8 ? ;
- son relâchement est-il visible (impulsion ou durée réelle) ? ;
- les feux automatiques s'y mêlent-ils ? ;
- même chose en Sport ?

## Phase 2 — architecture proposée, **non implémentée**

À ne coder qu'après une concordance démontrée sur PS5 :

1. **Mode « Radio par touche GT7 »** : réglage opt-in, avec un état affiché `VALIDÉ` / `EXPÉRIMENTAL` /
   `INDISPONIBLE`. `VALIDÉ` seulement après un journal PS5 concordant pour **ce** mode de jeu.
2. **Une impulsion = ouverture.**
   - Elle appelle le chemin existant `VoskVoiceInput.openConversation()`, puis l'écoute normale.
   - Bip `READY` seulement quand le micro écoute réellement (`readyBeep()`, déjà conditionné à `micReady`).
   - Conversation sans « Radio », fermeture au silence avec double bip `CLOSED` (`closeConversation`).
   - Aucune phrase « radio » simulée, aucun « Je t'écoute ».
3. **Maintenir = parler** : seulement si l'appui **et** le relâchement sont mesurés. Sinon, pas de fausse fonction.
4. **Sécurité.**
   - Anti-rebond, une seule ouverture à la fois, délai de garde.
   - Rien en pause ou en chargement.
   - Ignorer un bit déjà allumé au départ ou allumé longtemps (feux).
   - Coupure UDP pendant l'écoute : fermeture propre.
   - Le mot de réveil « Radio » reste toujours actif en secours.
   - Si le micro Discord est absent, l'Atelier le dit et aucun bip « prêt » n'est joué.
5. **Comportement social.** En ligne, un appel de phares est visible par les autres pilotes. L'Atelier le dira, et le
   mode restera désactivé en ligne tant qu'il n'est pas validé.
6. **Tests prévus** : signal UDP → ouverture → micro réellement prêt → bip → question sans mot de réveil → réponse →
   silence → double bip, plus non-régression de la radio Discord et du mot de réveil.

## Ce qui reste à faire

- Essai PS5 du protocole ci-dessus. Sans lui, la phase 2 ne démarre pas.
- Vérifier le comportement en PSVR2 (même télémétrie attendue, à confirmer).
