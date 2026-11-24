# Provenance et licences — revue du 24/11/2026

Objet : vérifier si Pitwall contient une dépendance ou un emprunt qui demande une revue de licence ou de provenance,
en particulier vis-à-vis de Nenkai/PDTools.

## Décision sur PDTools (pilote, 24/11)

L'`AGENTS.md` de Nenkai/PDTools interdit l'usage du dépôt par des IA : entraînement, génération, indexation, analyse,
lecture. Formulation lue le 24/11 via Parallel Search, sur ce seul fichier, uniquement pour connaître la règle.

- **Exclu** de toute nouvelle recherche, analyse ou génération assistée par IA : la règle est dans `CLAUDE.md`.
- **Conservé** : les citations historiques et leur attribution (README, `docs/GAP_HUNTER_RECHERCHE.md`,
  `docs/UDP_FIELDS.md`, `docs/DUALSENSE_PTT.md`, `docs/GAP_HUNTER.md`, commentaires de `GT7Crypto.kt`,
  `TelemetryPacket.kt`, `GapHunter.kt`, `ExperimentalFieldsTest.kt`). Aucune référence supprimée, aucun code modifié
  pour effacer une provenance.
- Sources de remplacement pour tout nouveau travail : MacManley/gt7-udp et zetetos/gt-telemetry, toutes deux sous
  licence MIT (vérifié le 24/11).

## Ce qui vient de PDTools dans le code

| Élément | Où | Nature | Revue |
|---|---|---|---|
| Clé Salsa20 (32 premiers octets de « Simulator Interface Packet GT7 ver 0.0 »), constantes XOR, tailles A/B/~, heartbeat, ports 33739/33740 | `core/telemetry/GT7Crypto.kt` | **faits d'interopérabilité** (constantes du protocole de la PS5), pas du code | recoupés avec gt7-udp (MIT) et gt7-datalogger ; le format C vient de gt7-udp ; tests contre des vecteurs indépendants |
| Algorithme Salsa20 | `core/telemetry/Salsa20.kt` | **implémentation propre** de l'algorithme public de D. J. Bernstein | testée contre pycryptodome (`CryptoAndParserTest`) et contre le vecteur de zetetos/gt-telemetry (`PacketCTest`) |
| Offsets des champs | `TelemetryPacket.kt`, `GapHunter.kt` | faits du format, avec les noms de plusieurs projets cités côte à côte | aucune ligne de code C# reprise ; les décodages sont validés sur nos traces (MESURE) |
| Mentions dans les docs | docs listées plus haut | citations avec attribution | conservées (décision du pilote) |

**Conclusion** : aucune dépendance de build ni aucun fichier source ne vient de PDTools. On ne trouve aucune copie
de code, seulement des constantes de protocole et des noms de champs cités avec leur source. Ces éléments sont
recoupés avec des projets MIT. **Aucune action n'est requise** au-delà de l'exclusion des prochains travaux.

## Autres dépendances notables (APK)

Toutes sont déclarées dans `app/build.gradle.kts` :
- sherpa-onnx : Apache 2.0, AAR officiel téléchargé à la compilation ;
- Vosk : Apache 2.0 ;
- JDA, avec un correctif Android appliqué à la compilation : Apache 2.0 ;
- libdave : licence à confirmer avant toute distribution publique de l'APK ;
- jsoup : MIT ;
- OkHttp : Apache 2.0 ;
- Tink : Apache 2.0 ;
- AndroidX et Compose : Apache 2.0 ;
- Firebase AI et ML Kit : conditions Google.

Les licences de cette liste sont celles que publient les projets. Elles n'ont pas été revérifiées une à une dans
cette revue.

Aucun projet GPL (gt7dashboard, Bornhall) n'est copié. Le dépôt ne contient aucune référence de code à ces projets.

`app/src/main/assets/licenses` embarque trois fichiers :
- la licence MIT de gt-telemetry ;
- les données de circuits de gt7-datalogger (CC0) ;
- la notice des signatures de circuits.

**Point à suivre** : vérifier la licence de `moe.kyokobot.libdave` avant toute publication hors usage personnel (non
vérifiée dans cette revue).
