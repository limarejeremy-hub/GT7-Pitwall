package com.gt7pitwall.core.display

import com.gt7pitwall.core.strategy.FuelAdvice
import com.gt7pitwall.core.strategy.FuelReport
import com.gt7pitwall.core.strategy.PlanWarning
import com.gt7pitwall.core.strategy.RacePlan
import com.gt7pitwall.core.strategy.StopCandidate
import com.gt7pitwall.core.strategy.TyreCompound
import com.gt7pitwall.core.strategy.TyreStint
import com.gt7pitwall.core.telemetry.ConnectionStatus
import com.gt7pitwall.core.telemetry.TelemetryPacket
import com.gt7pitwall.core.tyres.GripLevel

/**
 * Écran RACE (« Race Command Center », refonte V506) : tout ce que l'écran affiche et CHAQUE alerte, décidés ici en
 * Kotlin pur (testé sur PC) à partir de ce que Pitwall sait déjà. L'écran ne calcule rien.
 *
 * Règles d'honnêteté :
 * - une valeur absente reste null et l'écran écrit « indisponible » ou « — » ; jamais 0, jamais une valeur par défaut ;
 * - GT7 ne transmet pas l'usure des pneus ([WEAR_AVAILABLE] = false) ni les écarts avec les autres voitures ;
 * - la gomme est toujours DÉCLARÉE (pilote, règlement), jamais mesurée ; l'autonomie et le plan sont des CALCULS ;
 * - sans télémétrie en direct, aucune alerte de course n'est levée (les dernières valeurs sont dites « figées »).
 */
data class RaceCommandBoard(
    val link: Link,
    /** Silence de la PS5 en secondes ([Link.SILENT]). */
    val silenceS: Int?,
    /** Télémétrie en direct : les valeurs affichées sont actuelles. Faux : figées ou absentes. */
    val live: Boolean,
    val position: Int?,
    val cars: Int?,
    /** Position lue dans GT7 ; faux : déclarée par le pilote à la radio. */
    val positionMeasured: Boolean,
    val lap: Int?,
    val totalLaps: Int?,
    val finished: Boolean,
    val lastLapMs: Int?,
    val bestLapMs: Int?,
    val fuel: Fuel,
    val tyres: Tyres,
    val pit: Pit,
    /** Alertes actives, la plus grave d'abord. */
    val alerts: List<Alert>,
) {
    enum class Link { STOPPED, SEARCHING, LIVE, SILENT, ERROR }

    enum class Tone { NEUTRAL, OK, CAUTION, CRITICAL }

    enum class FuelRisk(val label: String, val tone: Tone) {
        UNAVAILABLE("indisponible", Tone.NEUTRAL),
        MEASURING("mesure en cours", Tone.NEUTRAL),
        NO_CONSUMPTION("sans consommation", Tone.OK),
        RANGE_ONLY("autonomie seule", Tone.NEUTRAL),
        SAFE("suffisant", Tone.OK),
        TIGHT("juste", Tone.CAUTION),
        PIT_NEEDED("arrêt nécessaire", Tone.CAUTION),
        CRITICAL("critique", Tone.CRITICAL),
        FINISHED("course terminée", Tone.NEUTRAL),
    }

    data class Fuel(
        /** Niveau du réservoir, 0 à 100 (mesuré). */
        val levelPct: Float?,
        /** Consommation moyenne par tour en % du réservoir (mesurée sur les tours complets). */
        val perLapPct: Float?,
        /** Tours couverts par le carburant à bord (calcul). */
        val lapsOfFuel: Float?,
        /** Tours restant à couvrir (calcul), null si la distance est inconnue. */
        val lapsToGo: Float?,
        /** Marge à l'arrivée en tours (négative : il en manque). */
        val marginLaps: Float?,
        val risk: FuelRisk,
        /** Dernier tour pour rentrer ravitailler, si un arrêt est nécessaire. */
        val latestPitLap: Int?,
    )

    enum class CompoundStatus(val label: String) {
        DECLARED("déclarés"),
        UNCERTAIN("à confirmer"),
        UNKNOWN("non déclarés"),
    }

    data class Tyres(
        /** AVG, AVD, ARG, ARD : température de surface mesurée. */
        val cells: List<TelemetryBoard.TyreCell>,
        val compound: TyreCompound?,
        val compoundStatus: CompoundStatus,
        val ageLaps: Int?,
        val grip: GripLevel,
        /** Au moins un pneu au-dessus du repère « très chaud ». */
        val overheating: Boolean,
    )

    enum class PitState { UNKNOWN, NONE, PLANNED, NEXT_LAP, THIS_LAP, CONFIRM, FINISHED }

    data class Pit(
        val state: PitState,
        /** Rentrer à la fin de ce tour. */
        val stopLap: Int?,
        val lapsUntil: Int?,
        /** Gomme à monter à cet arrêt (imposée et pas encore utilisée). */
        val tyreToFit: TyreCompound?,
        val refuel: Boolean,
        val changeTyres: Boolean,
        val stopsDone: Int,
        /** Gommes imposées encore à monter ; vide si rien n'est imposé ou si tout est fait. */
        val missingTyres: Set<TyreCompound>,
        val requiredTyres: Set<TyreCompound>,
        /** Champs du règlement encore inconnus : le plan est à vérifier. */
        val rulesIncomplete: Boolean,
        /** Tour de l'arrêt à confirmer ([PitState.CONFIRM]). */
        val confirmLap: Int?,
    )

    enum class AlertKind { TELEMETRY_LOST, TELEMETRY_ERROR, FUEL_CRITICAL, BOX_THIS_LAP, CONFIRM_STOP, FUEL_PIT_NEEDED, BOX_NEXT_LAP, TYRES_HOT, GRIP_LOSS, FUEL_TIGHT, TYRES_MISSING }

    data class Alert(val kind: AlertKind, val tone: Tone, val title: String, val detail: String)

    val topAlert: Alert? get() = alerts.firstOrNull()

    companion object {
        /** GT7 ne transmet pas l'usure des pneus : l'écran le dit, il n'affiche aucun pourcentage. */
        const val WEAR_AVAILABLE = false

        /** En dessous de cette autonomie (en tours), le carburant est critique s'il reste plus à couvrir. */
        const val CRITICAL_LAPS = 1.0f

        fun of(
            connection: ConnectionStatus,
            packet: TelemetryPacket?,
            position: Int?,
            positionMeasured: Boolean,
            cars: Int?,
            finished: Boolean,
            fuel: FuelReport?,
            plan: RacePlan?,
            stint: TyreStint?,
            grip: GripLevel,
            stopsDone: Int,
            pendingStop: StopCandidate?,
            missingTyres: Set<TyreCompound>,
            rulesIncomplete: Boolean,
        ): RaceCommandBoard {
            val link = when (connection) {
                ConnectionStatus.Stopped -> Link.STOPPED
                is ConnectionStatus.Searching -> Link.SEARCHING
                is ConnectionStatus.Receiving -> Link.LIVE
                is ConnectionStatus.NoData -> Link.SILENT
                is ConnectionStatus.Error -> Link.ERROR
            }
            val live = link == Link.LIVE && packet != null
            val lap = packet?.currentLap?.takeIf { it > 0 }
            val total = plan?.totalLaps?.takeIf { it > 0 } ?: packet?.totalLaps?.takeIf { it > 0 }
            val fuelBoard = fuel(packet, fuel, lap, finished)
            val tyres = tyres(packet, stint, grip, lap)
            val pit = pit(plan, lap, finished, stopsDone, pendingStop, missingTyres, rulesIncomplete)
            val alerts = buildList {
                when (link) {
                    Link.SILENT -> add(
                        Alert(
                            AlertKind.TELEMETRY_LOST, Tone.CRITICAL, "TÉLÉMÉTRIE PERDUE",
                            "Plus de données de la PS5 depuis ${(connection as ConnectionStatus.NoData).silenceMs / 1000} s. Valeurs figées.",
                        ),
                    )
                    Link.ERROR -> add(Alert(AlertKind.TELEMETRY_ERROR, Tone.CRITICAL, "CONNEXION EN ERREUR", (connection as ConnectionStatus.Error).message))
                    else -> Unit
                }
                // Sans direct, aucune alerte de course : elle porterait sur des valeurs périmées.
                if (live && !finished) {
                    when (fuelBoard.risk) {
                        FuelRisk.CRITICAL -> add(Alert(AlertKind.FUEL_CRITICAL, Tone.CRITICAL, "CARBURANT CRITIQUE", fuelDetail(fuelBoard)))
                        FuelRisk.PIT_NEEDED -> add(
                            Alert(
                                AlertKind.FUEL_PIT_NEEDED, Tone.CAUTION, "RAVITAILLEMENT NÉCESSAIRE",
                                fuelBoard.latestPitLap?.let { "Rentre au plus tard fin du tour $it." } ?: fuelDetail(fuelBoard),
                            ),
                        )
                        FuelRisk.TIGHT -> add(Alert(AlertKind.FUEL_TIGHT, Tone.CAUTION, "CARBURANT JUSTE", "Tu finis, mais avec peu de marge : économise."))
                        else -> Unit
                    }
                    when (pit.state) {
                        PitState.THIS_LAP -> add(Alert(AlertKind.BOX_THIS_LAP, Tone.CRITICAL, "BOX CE TOUR", pitAction(pit)))
                        PitState.NEXT_LAP -> add(Alert(AlertKind.BOX_NEXT_LAP, Tone.CAUTION, "BOX AU PROCHAIN TOUR", pitAction(pit)))
                        PitState.CONFIRM -> add(
                            Alert(
                                AlertKind.CONFIRM_STOP, Tone.CRITICAL, "ARRÊT À CONFIRMER",
                                "Tour ${pit.confirmLap} : c'était ton arrêt au stand ? Réponds oui ou non à la radio.",
                            ),
                        )
                        else -> Unit
                    }
                    if (tyres.overheating) add(Alert(AlertKind.TYRES_HOT, Tone.CAUTION, "PNEUS TRÈS CHAUDS", hotDetail(tyres)))
                    if (grip == GripLevel.SEVERE || grip == GripLevel.PERSISTENT) {
                        add(Alert(AlertKind.GRIP_LOSS, Tone.CAUTION, "ADHÉRENCE EN BAISSE", grip.label.replaceFirstChar { it.uppercase() } + " (estimation sur tes chronos)."))
                    }
                    if (pit.missingTyres.isNotEmpty() && pit.state == PitState.NONE) {
                        add(
                            Alert(
                                AlertKind.TYRES_MISSING, Tone.CAUTION, "GOMME IMPOSÉE À MONTER",
                                "Il manque : ${pit.missingTyres.joinToString(", ") { it.spoken }}.",
                            ),
                        )
                    }
                }
            }.sortedWith(compareBy({ if (it.tone == Tone.CRITICAL) 0 else 1 }, { it.kind.ordinal }))
            return RaceCommandBoard(
                link = link,
                silenceS = (connection as? ConnectionStatus.NoData)?.let { (it.silenceMs / 1000).toInt() },
                live = live,
                position = position,
                cars = cars,
                positionMeasured = positionMeasured,
                lap = lap,
                totalLaps = total,
                finished = finished,
                lastLapMs = packet?.lastLapMs?.takeIf { it > 0 },
                bestLapMs = packet?.bestLapMs?.takeIf { it > 0 },
                fuel = fuelBoard,
                tyres = tyres,
                pit = pit,
                alerts = alerts,
            )
        }

        private fun fuel(p: TelemetryPacket?, f: FuelReport?, lap: Int?, finished: Boolean): Fuel {
            val capacity = p?.fuelCapacity?.takeIf { it > 0f }
            val level = p?.fuelFraction?.let { it * 100f }
            val perLapPct = if (capacity != null) f?.perLap?.takeIf { it > 0f }?.let { it / capacity * 100f } else null
            val toGo = f?.raceLapsRemaining
            val advice = f?.advice
            val margin = when (advice) {
                is FuelAdvice.Comfortable -> advice.marginLaps
                is FuelAdvice.Tight -> advice.marginLaps
                else -> if (f?.lapsOfFuel != null && toGo != null) f.lapsOfFuel - toGo else null
            }
            val latest = (advice as? FuelAdvice.PitRequired)?.latestPitLap
            val risk = when {
                finished || advice == FuelAdvice.RaceFinished -> FuelRisk.FINISHED
                f == null || advice == null || advice == FuelAdvice.NotAvailable -> FuelRisk.UNAVAILABLE
                advice == FuelAdvice.Measuring -> FuelRisk.MEASURING
                advice == FuelAdvice.NoConsumption -> FuelRisk.NO_CONSUMPTION
                advice is FuelAdvice.RangeOnly -> FuelRisk.RANGE_ONLY
                advice is FuelAdvice.Comfortable -> FuelRisk.SAFE
                advice is FuelAdvice.Tight -> FuelRisk.TIGHT
                advice is FuelAdvice.PitRequired -> {
                    // Critique : moins d'un tour d'autonomie, ou dernier tour possible pour rentrer atteint.
                    val lastChance = lap != null && latest != null && lap >= latest
                    if ((f.lapsOfFuel != null && f.lapsOfFuel < CRITICAL_LAPS) || lastChance) FuelRisk.CRITICAL else FuelRisk.PIT_NEEDED
                }
                else -> FuelRisk.UNAVAILABLE
            }
            return Fuel(level, perLapPct, f?.lapsOfFuel, toGo, margin, risk, latest)
        }

        private fun tyres(p: TelemetryPacket?, stint: TyreStint?, grip: GripLevel, lap: Int?): Tyres {
            val w = p?.tyreTemps
            val cells = listOf("AVG" to w?.fl, "AVD" to w?.fr, "ARG" to w?.rl, "ARD" to w?.rr)
                .map { (l, t) -> TelemetryBoard.TyreCell(l, t?.takeIf { it > 0f }, TelemetryBoard.band(t)) }
            return Tyres(
                cells = cells,
                compound = stint?.compound,
                compoundStatus = when {
                    stint?.compound == null -> CompoundStatus.UNKNOWN
                    stint.certain -> CompoundStatus.DECLARED
                    else -> CompoundStatus.UNCERTAIN
                },
                ageLaps = if (lap != null) stint?.age else null,
                grip = grip,
                overheating = cells.any { it.band == TelemetryBoard.TempBand.HOT },
            )
        }

        private fun pit(
            plan: RacePlan?,
            lap: Int?,
            finished: Boolean,
            stopsDone: Int,
            pending: StopCandidate?,
            missing: Set<TyreCompound>,
            rulesIncomplete: Boolean,
        ): Pit {
            val stop = if (lap != null) plan?.nextStop(lap) else plan?.stops?.firstOrNull()
            val until = if (stop != null && lap != null) stop.endOfLap - lap else null
            val state = when {
                finished -> PitState.FINISHED
                pending != null -> PitState.CONFIRM
                plan == null -> PitState.UNKNOWN
                stop == null -> PitState.NONE
                until == 0 -> PitState.THIS_LAP
                until == 1 -> PitState.NEXT_LAP
                else -> PitState.PLANNED
            }
            return Pit(
                state = state,
                stopLap = stop?.endOfLap,
                lapsUntil = until,
                tyreToFit = stop?.tyreToFit,
                refuel = stop?.forFuel == true,
                changeTyres = stop?.changeTyres == true,
                stopsDone = stopsDone,
                missingTyres = missing,
                requiredTyres = plan?.requiredTyres.orEmpty(),
                rulesIncomplete = rulesIncomplete || plan?.warnings?.contains(PlanWarning.CANNOT_REACH_PITS) == true,
                confirmLap = pending?.lap,
            )
        }

        /** Ce qu'il faut faire à l'arrêt, en clair. */
        fun pitAction(pit: Pit): String = buildList {
            if (pit.refuel) add("Ravitailler")
            pit.tyreToFit?.let { add("Monter les ${it.spoken}") } ?: if (pit.changeTyres) add("Changer les pneus") else Unit
        }.joinToString(" · ").ifEmpty { "Arrêt prévu par le plan" }

        private fun fuelDetail(f: Fuel): String = buildString {
            append(f.lapsOfFuel?.let { "Autonomie ${oneDecimal(it)} tour(s)" } ?: "Autonomie inconnue")
            f.lapsToGo?.let { append(", ${oneDecimal(it)} à couvrir") }
            append(". Rentre au stand.")
        }

        private fun hotDetail(t: Tyres): String =
            t.cells.filter { it.band == TelemetryBoard.TempBand.HOT }.joinToString(", ") { "${it.label} ${it.tempC?.toInt()} °C" } + " (température mesurée)."

        fun oneDecimal(v: Float): String = String.format(java.util.Locale.FRANCE, "%.1f", v)
    }
}
