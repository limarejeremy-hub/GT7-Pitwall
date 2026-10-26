# GT7 Pitwall — Driver DNA V3 + Radio V4 + sécurité pneus

**Base** : Human Radio V4 `7f9d430`. **Source DNA** : Race Engineer V3. Travail sur branche dédiée, aucune PR historique fusionnée.

## Fonctionnalités intégrées
- Réutilisation des moteurs V3 de trajectoire, analyse des virages, freinage, réaccélération, patinage et comparaison de passages.
- Driver DNA enregistré dans `driver_dna.json` par voiture, tracé et type de session ; le profil est sauvegardé à la clôture.
- Coaching du pilote relié aux événements de tour, journal et données factuelles transmises au modèle conversationnel.
- Bilan de course, positions de départ et d'arrivée, suivi des erreurs de prévision et briefing Sport V3.
- Le moteur V4 conserve **la radio silencieuse**, les bips de conversation, la gestion des annonces périmées, les réponses courtes, la mémoire déclarative et les corrections de position.
- Les conseils V3 utilisent le pacer existant : ils sont filtrés par le freinage, la validité temporelle et le mode Sport.

## Pneus
Le premier vrai arrêt avec changement confirmé, le 25/10 au Mans, a montré une remise simultanée des quatre températures à 60 °C. Le Tyre Change Hunter détecte ce motif. Cette branche protège **les deux chemins de détection** contre les transitions de phase, la voiture encore en mouvement et les séries UDP discontinues.

**Les verdicts restent SUSPECTED ou UNDETERMINED**. Le pilote doit toujours confirmer le changement ; l'âge du relais n'est jamais remis à zéro à partir de températures seules.

## Calibration terrain : 26/10/2026, Interlagos

L'essai de la Porsche 911 GT3 R (992) '22, course de 14 tours, session `1793017813739-3600`, fournit un vrai positif **étiqueté après la course par le pilote**. L'arrêt du tour 7 n'a comporté aucun ravitaillement, mais bien le remplacement des quatre pneus course durs (RH) par des course mediums (RM). Le Tyre Change Hunter avait indiqué `SUSPECTED` avec les sauts AVG/AVD/ARG/ARD `−29,2 / −20,2 / −18,5 / −13,4 °C` et un retour autour de 60 °C. Le journal vocal ne contient **pas** la confirmation du pilote ; celle-ci a été fournie ultérieurement dans la conversation de calibration.

Fixture machine-readable : `core/src/test/resources/tyre_calibration/2026-10-26-interlagos-t7.json`, vérifiée par `TyreSignatureTest.realInterlagosPositiveConfirmsThermalSignatureButNotAutomaticCalibration`. **Un seul positif** n'est pas une démonstration de spécificité ni un motif d'activer la confirmation automatique. Il faut plusieurs arrêts de contrôle en gardant réellement les mêmes pneus et des positifs sur différentes voitures/circuits.

## Tests et vérifications requises
1. CI `:core:test` et build APK verts sur cette branche.
2. Sur PS5 : au moins 8 tours d'entraînement GT7 (virage répété lentement, puis amélioré), journaliser coaching et validation de la consigne.
3. Course Sport : vérifier 3 consignes max, jamais en gros freinage ou dans une bataille, radio V4 concise.
4. Stands : tester des pneus effectivement **changés** et effectivement **conservés**, séries thermiques exportées et étiquetées, pour étudier les faux positifs/négatifs.
5. Bip de fin, interruptions, questions pneus, sommeil Fold, reconnection, position, stratégie et ancien JSON de courses.
6. Ne pas activer la confirmation automatique tant que la reproductibilité n'a pas été établie sur plusieurs situations.

**Attention** : le Driver DNA et les seuils de pneus sont encore expérimentaux sur le terrain. Ne pas fusionner sans tests techniques et validation PS5.

## Correction (relecture Claude)
Le coaching de virage ne suivait les règles de course (3 consignes au plus, espacées de 4 tours, brèves) qu'en Course
Sport officielle. Les courses réelles du pilote sont des **courses personnalisées** à 20 voitures : elles recevaient une
consigne tous les deux tours, avec relance, contre la radio silencieuse. Désormais toute course (Sport officielle ou
personnalisée) suit les règles de course ; l'entraînement Sport, la qualification et le chrono restent en mode coach.
Test : `DriverDnaTest.aCustomRaceIsCoachedLikeARaceAndPracticeLikePractice`.
