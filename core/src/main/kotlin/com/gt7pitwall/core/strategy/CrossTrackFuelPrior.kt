package com.gt7pitwall.core.strategy

import com.gt7pitwall.core.race.SessionMode

/**
 * Issue #52 : consommation probable sur un circuit jamais roulé avec cette voiture, déduite de la même voiture sur un
 * autre circuit, rapportée au kilomètre :
 *
 *     fuelPerKm = carburant par tour / longueur du circuit source (km)
 *     prédit    = fuelPerKm × longueur du nouveau circuit (km)
 *
 * C'est une estimation provisoire ([Source.CROSS_TRACK_PROVISIONAL]), jamais une mesure : un circuit n'a ni le même
 * profil ni les mêmes pleines charges. Elle ne sert que s'il n'existe AUCUN modèle local même voiture × même circuit, et
 * elle s'efface devant la mesure en course dès les tours 1 et 2 ([withLive]).
 *
 * Elle n'utilise que des tours propres (ni ravitaillement, ni tour de sortie ou d'entrée des stands, ni tour incomplet)
 * de la même voiture, au même multiplicateur de consommation. Une autre version de GT7 : abstention. Un autre mode ou
 * une BoP différente ou inconnue : confiance faible. Elle ne touche jamais aux obligations du règlement (arrêts, gommes).
 */
object CrossTrackFuelPrior {
    enum class Source(val label: String) {
        /** Inter-circuits, même voiture : provisoire, à confirmer par les premiers tours. */
        CROSS_TRACK_PROVISIONAL("estimation inter-circuits provisoire"),

        /** Les tours mesurés de cette course ont pris le relais. */
        LIVE("mesure en course"),
    }

    enum class Confidence(val label: String) {
        /** Assez de tours propres, contexte identique, longueurs proches. */
        MEDIUM("moyenne"),

        /** Peu de tours, contexte incertain ou circuits très différents. */
        LOW("faible"),
    }

    /** Un tour déjà roulé, avec ce qu'il faut pour juger s'il est comparable. */
    data class SourceLap(
        val carCode: Int,
        val trackId: Int,
        /** Longueur du circuit (base GT7), en mètres ; null si inconnue. */
        val trackLengthM: Int?,
        val fuelMultiplier: Int,
        /** Carburant consommé sur le tour (unités GT7), null si le tour n'a pas été observé en entier. */
        val fuelUsed: Float?,
        val refuelled: Boolean = false,
        val inLap: Boolean = false,
        val outLap: Boolean = false,
        val mode: SessionMode? = null,
        val gameVersion: String? = null,
        /** BoP active ; null si on ne le sait pas. */
        val bop: Boolean? = null,
    ) {
        val clean: Boolean get() = !refuelled && !inLap && !outLap && (fuelUsed ?: 0f) > 0.01f
    }

    /** La course à préparer. */
    data class Target(
        val carCode: Int,
        val trackId: Int?,
        val trackLengthM: Int?,
        val fuelMultiplier: Int,
        val laps: Int?,
        val mode: SessionMode? = null,
        val gameVersion: String? = null,
        val bop: Boolean? = null,
    )

    sealed interface Result {
        data class Estimate(
            val perLap: Float,
            /** Carburant pour toute la course (null si le nombre de tours est inconnu). */
            val total: Float?,
            val fuelPerKm: Float,
            val source: Source,
            val confidence: Confidence,
            /** Circuits dont viennent les tours retenus. */
            val sourceTracks: Set<Int>,
            val samples: Int,
            /** Pourquoi la confiance n'est pas plus haute. */
            val caveats: List<String> = emptyList(),
        ) : Result

        data class Abstain(val reason: String) : Result
    }

    /** En dessous, la confiance reste faible. */
    const val MIN_SAMPLES_MEDIUM = 3

    /** Rapport de longueurs au-delà duquel l'extrapolation est jugée fragile. */
    const val MAX_LENGTH_RATIO = 1.6f

    /**
     * [hasLocalModel] : un modèle même voiture × même circuit existe déjà ; il prime, on s'abstient.
     */
    fun predict(target: Target, history: List<SourceLap>, hasLocalModel: Boolean): Result {
        if (hasLocalModel) return Result.Abstain("modèle local même voiture et même circuit disponible")
        val newM = target.trackLengthM?.takeIf { it > 0 }
        if (target.trackId == null || newM == null) return Result.Abstain("circuit inconnu ou longueur inconnue")
        val sameCar = history.filter { it.carCode == target.carCode }
        if (sameCar.isEmpty()) return Result.Abstain("aucun tour avec cette voiture")
        // Le circuit visé n'est jamais une source : ses tours relèvent du modèle local, pas de l'extrapolation.
        val elsewhere = sameCar.filter { it.trackId != target.trackId && (it.trackLengthM ?: 0) > 0 }
        val sameMult = elsewhere.filter { it.fuelMultiplier == target.fuelMultiplier }
        if (sameMult.isEmpty()) {
            return Result.Abstain(
                if (elsewhere.isEmpty()) "aucun autre circuit de longueur connue avec cette voiture"
                else "aucun tour au multiplicateur ×${target.fuelMultiplier}",
            )
        }
        val sameVersion = sameMult.filter { target.gameVersion == null || it.gameVersion == null || it.gameVersion == target.gameVersion }
        if (sameVersion.isEmpty()) return Result.Abstain("tours pris sous une autre version de GT7")
        val clean = sameVersion.filter { it.clean }
        if (clean.isEmpty()) return Result.Abstain("aucun tour propre (ravitaillement, entrée ou sortie des stands)")

        val caveats = mutableListOf<String>()
        val perKm = clean.map { it.fuelUsed!! / (it.trackLengthM!! / 1000f) }.average().toFloat()
        val perLap = perKm * newM / 1000f
        if (clean.size < MIN_SAMPLES_MEDIUM) caveats += "seulement ${clean.size} tour(s) propre(s)"
        if (target.mode == null || clean.any { it.mode != target.mode }) caveats += "mode de course différent ou inconnu"
        if (target.bop == null || clean.any { it.bop != target.bop }) caveats += "BoP différente ou inconnue"
        if (target.gameVersion == null || clean.any { it.gameVersion == null }) caveats += "version de GT7 non précisée"
        val ratio = clean.map { it.trackLengthM!!.toFloat() / newM }.map { maxOf(it, 1f / it) }.max()
        if (ratio > MAX_LENGTH_RATIO) caveats += "circuits de longueurs très différentes"
        return Result.Estimate(
            perLap = perLap,
            total = target.laps?.takeIf { it > 0 }?.let { perLap * it },
            fuelPerKm = perKm,
            source = Source.CROSS_TRACK_PROVISIONAL,
            confidence = if (caveats.isEmpty()) Confidence.MEDIUM else Confidence.LOW,
            sourceTracks = clean.map { it.trackId }.toSet(),
            samples = clean.size,
            caveats = caveats,
        )
    }

    /**
     * Passage à la mesure en course : après le tour 1, moitié estimation, moitié mesure ; dès le tour 2, la mesure seule.
     * La part de la mesure ne fait que croître avec [liveLaps] : pas de retour en arrière vers l'estimation.
     */
    fun withLive(prior: Result.Estimate, livePerLap: Float?, liveLaps: Int, laps: Int? = null): Result.Estimate {
        val w = liveWeight(liveLaps)
        if (livePerLap == null || livePerLap <= 0f || w == 0f) return prior
        val perLap = prior.perLap * (1f - w) + livePerLap * w
        val total = (laps ?: prior.total?.let { kotlin.math.round(it / prior.perLap).toInt() })?.let { perLap * it }
        return prior.copy(perLap = perLap, total = total, source = if (w >= 1f) Source.LIVE else prior.source)
    }

    fun liveWeight(liveLaps: Int): Float = when {
        liveLaps <= 0 -> 0f
        liveLaps == 1 -> 0.5f
        else -> 1f
    }
}
