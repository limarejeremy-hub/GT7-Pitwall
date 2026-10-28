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

## Révision après la revue de la PR #39

| Point de la revue | Correction |
|---|---|
| 1. Repères non appariés 1:1 | Appariement chronologique **un repère ↔ un épisode distinct**. L'allumage doit commencer entre le repère et 2,5 s après ; un repère posé après l'allumage n'est jamais rattaché (même horloge). |
| 2. Concordance sur signal perturbé | Seuls les épisodes **fermés et mesurables** comptent (montée et descente vues). Sont non mesurables : coupure UDP, pause, chargement, changement de voiture, de mode déclaré ou de série pendant l'épisode, allumage moins d'1 s après un changement de mode ou de série, début inconnu, bit coincé. Un épisode non mesurable dans la fenêtre d'un repère rend la série « non concluante ». |
| 3. Contrôles négatifs, séries, mode | Séries **témoin sans appui**, **appels brefs**, **maintiens d'environ 1 s**. Par série : repères, montées, appariées, relâchements mesurés, non mesurables, verdict. Un témoin avec une montée rend tout l'essai non concluant. Le **mode de jeu est déclaré** par le pilote (hors ligne, Sport qualification, Sport course) ; le profil Pitwall n'est noté qu'« à titre indicatif, pas une détection du mode ». |
| 4. Limites | 0x8E = **état des feux**, pas un bouton brut. L'écart entre paquets au 95e centile mesure la **régularité du flux reçu**, pas une latence de bout en bout. |

## Tests (JVM, trames synthétiques)

`ControllerRadioProbeTest` (12). Ils prouvent la mesure et la prudence, **pas** le comportement de GT7.

| Cas | Attendu |
|---|---|
| Impulsions de 1 et 3 paquets, 0,3 s, 1 s, 2,4 s | Impulsion, impulsion, bref, maintenu, long |
| Pleins phares allumés au premier paquet | Permanent ; « non mesurable » à l'extinction (début inconnu) ; verdict « indétectable » |
| Feux automatiques (seul le bit 7 change) | Suivis à part ; pleins phares « jamais changé » |
| Double appui | Deux épisodes |
| Format A (296 octets) | Fonctionne |
| Témoin propre + 5 appels brefs + 5 maintiens, repères avant chaque appui, hors ligne | Concordance « sur cet essai seulement », jamais « touche détectée » |
| 5 repères groupés puis une seule montée | 1/5 appariés : non concluant |
| Repère posé après l'allumage | Jamais apparié |
| Coupure UDP, pause, changement de voiture, changement de mode pendant un maintien ; bit coincé à 1 | Non concluant |
| Allumage 0,16 s après le début d'une série | Non mesurable |
| Témoin avec une montée sans appui | Tout l'essai non concluant |
| Sonde désactivée ; reconnexion, changement de voiture ; export | Rien n'est enregistré ; coupure et changement journalisés ; limites écrites ; aucune adresse |

## Protocole PS5 (hors ligne d'abord, jamais en ligne avant validation)

Préparation : Atelier → « Sonde touche radio DualSense » : activer, puis toucher **Hors ligne** (mode déclaré). Course
hors ligne, voiture **à l'arrêt**, Fold en main : tu ne conduis pas pendant cet essai.

1. **Témoin sans appui** : toucher le bouton, puis 20 s sans rien toucher (phares fixes ou automatiques tels quels).
2. **Appels brefs** : toucher le bouton. Cinq fois : « J'appuie maintenant », poser le Fold, **un** appel de phares bref,
   attendre 5 s.
3. **Maintiens d'environ 1 s** : toucher le bouton, puis cinq fois la même chose en maintenant environ 1 s.
4. **Exporter** le journal et l'envoyer. Le résumé donne, par série, les repères, montées, appariements, relâchements
   et épisodes non mesurables.
5. Seulement si tout concorde : refaire en déclarant **Sport, qualification** (pas en course).

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

## Essai PS5 du 28/10 et coupures brèves (issue #41, P2)

**Résultat rapporté** : 47 341 paquets en 790 s, 6 repères, 12 montées des pleins phares, 5 repères appariés, 6 montées
sans repère. Les états pleins phares sont souvent longs ou permanents (jusqu'à ~228 s). **Verdict : non concluant.**
Ce sont des bits d'état des feux, pas des touches brutes. Aucun micro n'est activé, aucune fonction PTT n'est annoncée.

**Ajout** : la sonde suit aussi la polarité inverse. Avec les pleins phares allumés en continu, un appel de phares peut
donner une **coupure brève** (éteint puis rallumé en 1,2 s au plus) plutôt qu'une montée.
- Une période éteinte plus longue est l'état normal, jamais un appui.
- Mêmes règles que pour les montées : repère avant l'effet, appariement un pour un, épisodes perturbés non mesurables, témoin propre, au moins 5 repères.
- Nouvelle série « coupures brèves, phares allumés ». L'export ajoute la colonne `polarite` (ON/OFF).

**Protocole hors ligne reproductible (à faire)** :
1. Course hors ligne, voiture à l'arrêt ou en ligne droite. Couper les feux automatiques si le jeu le permet. Noter l'heure GT7 (jour ou nuit).
2. Sonde activée, mode « hors ligne », série **témoin** de 20 s sans toucher.
3. Série **appels brefs** : 5 repères, chacun suivi d'un appui bref environ 1 s plus tard.
4. Allumer les pleins phares en continu, puis série **témoin** de 20 s (phares allumés, sans appui).
5. Série **coupures brèves** : 5 repères, chacun suivi d'un appui bref.
6. Exporter. Phase 2 (PTT) seulement si l'une des séries concorde **et** que les témoins sont propres, sur deux essais distincts.

Tests JVM ajoutés : `shortDipsWithHighBeamOnAreMeasuredAndPaired`, `longOffPeriodsBetweenPressesAreNotDips`,
`anUnmarkedDipMakesTheDipSeriesInconclusive`. **PS5 : non testé.**
