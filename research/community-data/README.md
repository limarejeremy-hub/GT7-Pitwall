# Traçabilité des jeux de données — recherche uniquement

**Non inclus dans l'APK / non branché sur le moteur de course.**
Données collectées le 29/10/2026 pour étude de compatibilité commerciale, sans validation juridique finale.

## Sources
- `gt7-track-index.json` : copie inchangée de `index.json` depuis [jbhoorasingh/gt7-datalogger-track-data](https://github.com/jbhoorasingh/gt7-datalogger-track-data), commit `80d0a1125a79d22a725289e05d03215b88f6559b`, blob `e6713d22eaebfb72d10f49bbb631e84a3c22c8f1`. Licence du fichier source : **CC0 1.0** telle que déclarée par `LICENSE`.
- `gt7-track-signatures.json` : copie inchangée de `signatures.json` depuis le même commit, blob `a1f99b5aad1c65a615917b66aab5a70cf927d6d9`. Données **dérivées mixtes** : 26 relevés CC0 + 54 captures MIT, selon `counts`. **Ne pas requalifier ce fichier intégralement CC0.**
- Sources MIT des captures : [zetetos/gt-telemetry](https://github.com/zetetos/gt-telemetry), `data/circuits/*.json`, réduites dans `vendor/circuits.json` du dépôt ci-dessus. La notice originale est reproduite ci-dessous.
- Ces copies incluent des noms officiels de circuits GT7 : identification descriptive uniquement, pas de relation d'affiliation ou autorisation Sony. Revue juridique avant toute distribution.

## Restrictions d'exploitation
Ne pas injecter en production avant validation des schémas, de la licence fichier par fichier, de la provenance et des tests de reconnaissance. Ne pas aspirer de photos, captures du jeu, fichiers de joueurs ou données associées à des comptes. Les fichiers `tracks/` de relevés détaillés ne sont pas encore importés : attendre le pipeline et sa vérification.

## Licence originale MIT du fournisseur des captures
```text
MIT License

Copyright (c) 2024 vwhitteron

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

## Licence CC0 des relevés
Source et texte officiel : https://github.com/jbhoorasingh/gt7-datalogger-track-data/blob/main/LICENSE ; https://creativecommons.org/publicdomain/zero/1.0/legalcode.
