# Mission Black Box : une course, un fichier, un clic pour partager

Branche `claude/black-box` (base `claude/pitwall-token-optimization-l58g31`, PR #97). PR brouillon, jamais fusionnée.

## 1. Inventaire de l'existant (audit du 26/11, avant tout code)

| Brique | Ce qui est conservé | Ce qui est perdu ou manquant | À synchroniser |
|---|---|---|---|
| `TelemetryRecorder` / `.gt7trace` (`core/telemetry/TelemetryTrace.kt`) | Paquet GT7 déchiffré tel quel + ms depuis le début ; formats A/B/C ; pertes UDP (numéros 0x70), perdus par Pitwall (file pleine), silences > 2 s, délai p95/p99 | **Manuel seulement**, coupé à chaque lancement. À 60 Hz, la décimation (écart minimal 14 ms) **jette les paquets rapprochés par le Wi-Fi** sans les compter. gzip vidé seulement à la fermeture : un crash perd les derniers Ko compressés (jusqu'à plusieurs secondes). Arrêt **définitif** à 80 Mo ou 4 h (pas de suite). Au plus 5 traces, sans lien avec la session. Rien avant le démarrage. | Identifiant de session, heure de début, raison d'arrêt dans la chronologie |
| `SessionJournal` (`core/race/SessionJournal.kt`) | Une ligne JSON ajoutée aussitôt par événement : `seq`, heure, session, tour, type (SESSION, LAP, DRIVER, ENGINEER, ALERT, DECISION, STRATEGY, PIT, POSITION, RADIO), source (TRANSCRIPT, SCREEN, ENGINE, GEMINI, TELEMETRY, SYSTEM), instantané (vitesse, carburant, P, pneus, stand) ; masquage IP et jetons | Pas de position sur le circuit (x/z) ; heure = horloge du téléphone (pas monotone) ; pas de lien vers la trace | C'est **la** chronologie commune : la Black Box la réutilise, pas de second moteur de journal |
| `SessionRecord` / archive (`SessionArchive.kt`, `RaceStore`) | Tours (chrono, carburant, pneus déclarés, TCS, blocages, patinages, temp. 4 pneus), arrêts + preuve, plans successifs, notes du pilote, grille, ligne, classement consolidé, révisions après la ligne, zone d'arrêt | Pas de secteurs (GT7 ne les transmet pas) ; pénalités : aucun champ GT7 | Repris tel quel dans `race.json` |
| `RaceController` | Point d'entrée de tous les paquets ; ouvre/ferme la session (`journal.begin`/`end`) ; issue FINISHED / INTERRUPTED / LAST_LAP_CUT ; reprise d'une session IN_PROGRESS après fermeture brutale (→ INTERRUPTED) | Aucun signal de cycle de vie vers l'extérieur | Signal début/fin de session pour démarrer/arrêter la Black Box |
| Diagnostics UDP (`TelemetryClient`, `PositionProbe`, Gap Hunter, scan de formats) | État de connexion à l'écran ; formats vus | Non archivés avec la course | Résumé dans `diagnostics.json` (calculé depuis la trace) |
| Stands (`PitDetector`, registre des arrêts) | Journal PIT (passage, zone d'arrêt, verdict thermique), `StopEntry` (preuve, carburant ajouté, pneus déclarés) | Carburant avant/après et phases de remplissage seulement dans la trace brute | Phases mesurées depuis la trace + journal |
| Pneus (`TyreDiagnostics`, `GripDiagnosis`, Tyre Change Hunter) | Par tour dans l'archive ; verdict thermique dans `StopEntry.thermal` | Courbe continue seulement dans la trace | `telemetry_10hz.csv` + par tour |
| Carburant (`FuelTracker`, décision carburant V512) | Journal STRATEGY (décision carburant, levée) ; conso par tour | — | `strategy.jsonl` |
| V515/V515.1 (`StrategyAssurance`) | Journal STRATEGY à chaque changement de verdict/plan : plan retenu, proposé, fenêtre, contrôles | Stratégies rejetées seulement via « plan proposé » et contrôles | `strategy.jsonl` |
| Véga (`PitwallEngine.journalMessage`, IA) | Questions (transcription ou écran), réponses (moteur ou Gemini contrôlé), alertes, rappels critiques « Box », délai, modèle, repli | Aucun son (voulu) | `radio.jsonl` |
| Position / événements (`PositionTracker`) | Journal POSITION (grille, changements), révisions après la ligne | Cause d'un changement jamais transmise par GT7 | `race.json` : changements classés, jamais « dépassement » sans preuve |
| Connexion / performance | Délais radio et IA dans le journal ; stats du recorder à l'écran | Non archivés | `diagnostics.json` |

Exports existants : journal texte et JSON (Historique), trace `.gt7trace` seule (Atelier), Gap Hunter. **Six gestes séparés**
pour une course, et la trace n'est jamais rattachée à sa session.

### Décisions d'architecture
- **Pas de second journal** : la Black Box ajoute seulement la trace (le recorder existant) rangée par session, et un
  assembleur qui lit le journal, l'archive et la trace au moment du partage.
- Format `.gt7trace` inchangé (`GT7PWTR1`) : les traces existantes restent lisibles, le Lab les rejoue sans changement.

## 2. Enregistrement automatique

- Réglage **« Enregistrement automatique »** (écran Historique) : désactivé tant que le pilote ne l'a pas activé, puis
  gardé entre les lancements (`SettingsStore.blackBoxAuto`).
- Démarrage et arrêt suivent la session du journal (`SessionJournal.Lifecycle`, appelé par `journal.begin`/`end` dans
  `RaceController`) : aucune manipulation pendant la conduite, aucune nouvelle interface en course.
- Réception UDP **inchangée** : la boîte noire se branche sur le même `rawTap` que Gap Hunter et la trace manuelle.
- Pré-enregistrement de 10 s (grille, départ) tant que la session n'a pas commencé.
- Arrêt de la course → trace finalisée (`meta.json` : FINISHED). Télémétrie arrêtée → STOPPED.
- Fermeture brutale ou crash → la session reste « RECORDING » sur le disque et passe « RECOVERED » au lancement
  suivant ; elle reste partageable. Le gzip est vidé toutes les 2 s (« sync flush ») : **2 s au plus perdues**.
- Essai court ou séance de test, boîte noire coupée : bouton « Enregistrer un essai » (session en cours ou suivante).
- Arrêt anormal (budget atteint, disque plein, paquets perdus) : **jamais silencieux** — événement au journal marqué
  `anomalie`, message affiché dans l'Historique après la course, et ligne « manquant » dans le pack.

### Fidélité, 60 Hz, stockage, batterie
| Point | Décision | Nature |
|---|---|---|
| Fréquence | **Tous les paquets reçus** (`TelemetryRecorder.ALL_PACKETS`), aucune décimation. L'ancien mode « 60 Hz » écartait les paquets rapprochés par le Wi-Fi (écart < 14 ms) : ils sont désormais comptés (`decimated`) dans la trace manuelle. | CALCUL (test JVM) |
| Pertes | File bornée à 1 024 : si le disque ne suit pas, l'échantillon est perdu **et compté** (`dropped`, listé dans le pack). Pertes Wi-Fi d'après les numéros 0x70, coupures > 0,5 s listées. | MESURE |
| Taille | Paquet 296 à 368 octets × 60/s ≈ 64 à 80 Mo/h avant compression. Ratio gzip réel **non mesuré** (le gabarit est trop régulier pour servir de référence) : ordre de grandeur attendu 20 à 40 Mo/h. | ESTIMATION, à mesurer sur PS5 |
| Segments | Fichiers de 64 Mo ; les suivants n'ont pas d'en-tête, leur concaténation est **une seule trace `GT7PWTR1` valide** (gzip à plusieurs membres). Le pack contient un seul `telemetry.gt7trace`. | CALCUL (test) |
| Limites | Par session 1 Go et 30 h ; au total 2 Go (réglable 1 à 20 Go) et 10 courses (1 à 50). Les plus anciennes partent, jamais la course en cours. | réglages |
| Batterie | gzip de ~20 Ko/s sur un fil à part, écritures par blocs de 64 Ko : coût processeur attendu négligeable. **Non mesuré sur le Fold.** La sonde « Mesure Black Box » existante relève batterie et température pendant une course. | ESTIMATION, à mesurer |

### Limites de l'ancien recorder (80 Mo, 4 h, 5 traces) : audit
- La trace **manuelle** (Atelier) garde exactement ses limites et son comportement (tests `TelemetryTraceTest` inchangés et verts).
- La boîte noire utilise son propre recorder sans ces limites, remplacées par segments + budget par session + rétention.
  4 h à 60 Hz dépassaient probablement 80 Mo : une course d'endurance aurait été coupée sans suite.

## 3 et 5. Contenu du pack (format `gt7pitwall-blackbox` v1)

Nom : `Pitwall_<AAAA-MM-JJ>_<Circuit>_<Course|Qualif|Essai><n>.zip` ; séance : `Pitwall_<date>_Seance_<n>courses.zip`
(un dossier `course-NN_<session>/` par course, README et manifeste communs).

| Fichier | Contenu | Nature dominante |
|---|---|---|
| `README.md` | résumé lisible, **données manquantes explicites**, inventaire, mode d'emploi | CALCUL |
| `manifest.json` | format, version, application/build, sessions, liste des fichiers avec taille et **SHA-256**, manquants, natures | — |
| `telemetry.gt7trace` | paquets GT7 déchiffrés + heure de réception (format inchangé, rejouable) | MESURE |
| `telemetry_10hz.csv` | télémétrie lisible (vitesse, pédales, rapport, régime, carburant, 4 températures, 4 glissements, x/y/z, tour, position) | MESURE |
| `race.json` | B voiture, circuit, épreuve, règlement et sa source, tours, départ/ligne/classement, pénalités connues ; C changements de position classés ; D stands ; E pneus | mixte, chaque bloc nommé |
| `laps.csv` | un tour par ligne (archive) | MESURE |
| `timeline.jsonl` / `.csv` | chronologie commune (section 4) | mixte |
| `strategy.jsonl` | verdicts Strategy Assurance, stratégies rejetées et raison, carburant, rappels « Box », plans successifs | CALCUL / ESTIMATION |
| `radio.jsonl` | questions du pilote (transcriptions), réponses de Véga, système radio | DECLARATION / CALCUL |
| `diagnostics.json` | version et build, téléphone et Android, réglages utiles (liste blanche), fonctions expérimentales, qualité du flux (formats, fréquence mesurée, pertes, coupures), état de la boîte noire, défaillances | MESURE |

Règles :
- **Dépassements** : un changement de classement est MESURÉ, sa cause est `INCONNU`. Pendant notre arrêt : « effet du
  passage aux stands, pas un dépassement ». En roulant : « dépassement possible, NON prouvé ». Les déclarations du
  pilote à ±30 s sont jointes.
- **Stands** : immobilisation et ravitaillement mesurés (carburant avant/après, durée, débit) ; entrée/sortie de voie
  non transmises par GT7 ; pneus déclarés ou suspectés (verdict thermique = ESTIMATION).
- **Absents par construction et dits comme tels** : secteurs, pénalités (aucun champ GT7), version de GT7, son, vidéo.
- **Confidentialité** : aucune clé, aucun jeton, aucune adresse ; textes re-masqués à l'export ; la vérification
  refuse un pack où une adresse IP ou une forme de secret reste visible. Réglages exportés par liste blanche.

## 4. Chronologie commune
Chaque ligne de `timeline.jsonl` : `session`, `t_ms` (ms depuis le début de la session), `heure`, `seq` (ordre du
journal), `tour`, `type`, `source`, `nature`, `origine: EN_COURSE`, `texte`, `x`/`z` (position mesurée à moins d'1 s,
si une trace existe), `details`, `instantane`. S'y ajoutent les événements mesurés dans la trace : `TOUR_MESURE`,
`POSITION_MESUREE`, `IMMOBILISATION`, `RAVITAILLEMENT`, `COUPURE_FLUX`.
Limite : l'horloge commune est celle du téléphone (la même pour la trace et le journal), pas une horloge monotone ;
un réglage de l'heure pendant la course décalerait les deux ensemble. L'ordre exact du journal reste donné par `seq`.

## 6. Interface
Écran Historique seulement (jamais pendant le pilotage) : réglage « Enregistrement automatique », puis sur chaque
course un bouton principal **« Partager ma course »** : finalise si la course est finie, assemble le ZIP hors du fil
principal, le **rouvre pour vérifier** (SHA-256, fichiers obligatoires, trace relue en entier), ouvre la feuille de
partage Android (FileProvider `blackbox/exports/`, un seul fichier `application/zip`). Affiché : taille, durée,
manquants, statut. Bouton « Séance (n) » pour toutes les courses du jour. Les anciens exports texte/JSON restent en
second plan. Les 3 derniers packs seulement sont gardés dans le dossier de partage.

## 8. Pitwall Lab : `lab import-course <pack.zip> [rapport.md]`
Vérifie le pack, lit le manifeste, rejoue la trace dans le moteur **actuel** (épreuve lue dans `race.json`), compare
`EN_COURSE` (pack) et `SIMULATION` (rejeu) : tours, chronos, arrêts, suite des verdicts, messages de Véga, et
liste les manquants. Rapport JSON + Markdown. Les deux origines ne sont jamais mélangées.

## 9. Tests (PC/CI ; rien n'est validé sur PS5 ni sur le Fold)
- `BlackBoxTest` (core, 15) : tous les paquets gardés malgré les rafales Wi-Fi, segments concaténés, crash après
  vidage, cycle de vie avec pré-enregistrement, essai manuel, reprise après fermeture brutale, rétention, budget non
  silencieux, pack complet vérifié (dépassement apparent pendant l'arrêt, ravitaillement, pénalité déclarée, masquage
  d'adresse, chronologie triée), pack sans trace/son/vidéo, trace tronquée, séance, pack falsifié refusé, 1 h à 60 Hz
  (216 000 paquets) en flux, noms de fichier.
- `BlackBoxEndToEndTest` (lab, 3) : course rejouée « en direct » dans le vrai moteur → ZIP → réouverture → `import-course`
  → ravitaillement, arrêt et changement de position retrouvés ; mêmes tours, arrêts et messages que le moteur actuel ;
  plusieurs courses et pack de séance ; coupure Wi-Fi + fermeture brutale.
- `BlackBoxShareTest` (app, Robolectric) : partage par FileProvider, intention `ACTION_SEND` ZIP, relecture par le
  ContentResolver et vérification.
- Non-régression : suite complète core + lab, tests Android de la CI.

### À valider sur PS5 / Fold
1. Activer « Enregistrement automatique », courir 15 min : `diagnostics.json` → `dropped` = 0, Mo/h réel, batterie.
2. Fermer Pitwall brutalement en course, relancer : la course est « récupérée » et partageable.
3. « Partager ma course » → ChatGPT et Claude : le ZIP arrive entier, README lisible.
4. `lab import-course` sur ce pack.
