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
