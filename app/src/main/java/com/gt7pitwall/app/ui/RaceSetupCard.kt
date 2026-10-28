package com.gt7pitwall.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.RadioButton
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import com.gt7pitwall.app.sport.SportApiCredentialStore
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gt7pitwall.app.engine.PitwallUiState
import com.gt7pitwall.app.ui.theme.PitColors
import com.gt7pitwall.core.engineer.FrenchFormat
import com.gt7pitwall.core.knowledge.Course
import com.gt7pitwall.core.knowledge.Gt7Database
import com.gt7pitwall.core.race.EventProfile
import com.gt7pitwall.core.race.ProfileId
import com.gt7pitwall.core.race.Series
import com.gt7pitwall.core.race.SessionMode
import com.gt7pitwall.core.race.SessionKind
import com.gt7pitwall.core.radio.DriverCommand
import com.gt7pitwall.core.sport.FieldStatus
import com.gt7pitwall.core.sport.RefreshPolicy
import com.gt7pitwall.core.sport.RuleField
import com.gt7pitwall.core.strategy.DataSource
import com.gt7pitwall.core.strategy.FuelUnits
import com.gt7pitwall.core.strategy.PlanWarning
import com.gt7pitwall.core.strategy.RaceRules
import com.gt7pitwall.core.strategy.StopEvidence
import com.gt7pitwall.core.strategy.TyreCompound
import java.util.Locale
import kotlin.math.roundToInt

/** Voiture (reconnue), circuit (reconnu ou choisi), règlement de l'épreuve et plan de course. */
@Composable
fun RaceSetupCard(
    state: PitwallUiState,
    rules: RaceRules,
    database: Gt7Database,
    onRules: (RaceRules) -> Unit,
    onCommand: (DriverCommand) -> Unit,
    onProfile: (ProfileId) -> Unit,
    onProfileInfo: (EventProfile) -> Unit,
    onConfirmRules: () -> Unit,
    onRefreshSchedule: () -> Unit,
    onDeclareRules: (RaceRules, Set<RuleField>) -> Unit,
) {
    var picking by rememberSaveable { mutableStateOf(false) }
    var editingTyres by rememberSaveable { mutableStateOf(false) }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("COURSE & STRATÉGIE", style = MaterialTheme.typography.labelMedium, color = PitColors.Muted, letterSpacing = 1.5.sp)
            state.sessionIdentity?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = if (it.startsWith("Épreuve à confirmer")) PitColors.Yellow else PitColors.Cyan)
            }
            EventProfileSection(state, onProfile, onProfileInfo, onConfirmRules)
            MyCarBriefingCard(state)
            WeeklyEditionSection(state, rules, database, onRules, onRefreshSchedule, onDeclareRules)
            var preRaceOpen by rememberSaveable { mutableStateOf(false) }
            OutlinedButton(onClick = { preRaceOpen = !preRaceOpen }, modifier = Modifier.fillMaxWidth()) {
                Text(if (preRaceOpen) "Réduire le briefing de victoire" else "Briefing avant-course : objectif victoire")
            }
            if (preRaceOpen) {
                Text(
                    "Informations certaines, estimations historiques et limites de données (les adversaires ne sont pas accessibles par UDP).",
                    color = PitColors.Muted, style = MaterialTheme.typography.bodySmall,
                )
                state.preRaceBrief.forEach { line ->
                    Text("• " + line, style = MaterialTheme.typography.bodySmall,
                        color = if (line.startsWith("ALERTE")) PitColors.Yellow else MaterialTheme.colorScheme.onSurface)
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)

            val p = state.snapshot.packet
            Text(
                when {
                    state.carName != null -> "Voiture : ${state.carName}"
                    state.garageCar.code != null -> "Voiture : code ${state.garageCar.code} (absent de la base)"
                    p == null -> "Voiture : en attente de télémétrie"
                    else -> "Voiture : code ${p.carCode} (absent de la base)"
                } + if (state.carChangePending) " — nouvelle voiture, historique et plan recalculés au départ" else "",
                fontWeight = FontWeight.Bold,
            )

            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text(
                    "Circuit : " + (state.course?.label ?: "non choisi"),
                    Modifier.weight(1f),
                    fontWeight = FontWeight.Bold,
                )
                TextButton(onClick = { picking = true }) { Text("Choisir") }
            }
            if (state.course == null && state.trackCandidates.isNotEmpty()) {
                Text("D'après la longueur du tour mesurée :", color = PitColors.Muted, style = MaterialTheme.typography.bodySmall)
                state.trackCandidates.take(4).forEach { c ->
                    OutlinedButton(onClick = { onRules(rules.copy(trackId = c.id)) }, modifier = Modifier.fillMaxWidth()) {
                        Text("${c.label} (${c.lengthM} m)")
                    }
                }
            } else if (state.course == null) {
                Text(
                    "Reconnu automatiquement après un tour complet, ou à choisir. Sert à mémoriser consommation et chronos par voiture et circuit.",
                    color = PitColors.Muted,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            state.memory?.let { m ->
                val parts = buildList {
                    m.bestLapMs?.let { add("meilleur ${FrenchFormat.lapTime(it)}") }
                    m.fuelPerLapX1?.let { add(FuelUnits.perLap(it, state.snapshot.packet?.fuelCapacity) + " à ×1") }
                }
                if (parts.isNotEmpty()) Text("Mémoire : ${parts.joinToString(" • ")}", color = PitColors.Cyan, style = MaterialTheme.typography.bodySmall)
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
            Text("Règlement (non transmis par GT7)", style = MaterialTheme.typography.labelSmall, color = PitColors.Muted)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumberField("Tours", rules.totalLaps, Modifier.weight(1f)) { onRules(rules.copy(totalLaps = it, durationMin = if (it != null) null else rules.durationMin)) }
                NumberField("Durée (min)", rules.durationMin, Modifier.weight(1f)) { onRules(rules.copy(durationMin = it, totalLaps = if (it != null) null else rules.totalLaps)) }
                NumberField("Arrêts oblig.", rules.mandatoryStops.takeIf { it > 0 }, Modifier.weight(1f)) { onRules(rules.copy(mandatoryStops = it ?: 0)) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumberField("Conso ×", rules.fuelMultiplier.takeIf { it > 1 }, Modifier.weight(1f)) { onRules(rules.copy(fuelMultiplier = (it ?: 1).coerceAtLeast(1))) }
                NumberField("Usure ×", rules.tyreMultiplier.takeIf { it > 1 }, Modifier.weight(1f)) { onRules(rules.copy(tyreMultiplier = (it ?: 1).coerceAtLeast(1))) }
                NumberField("Pneus (tours)", rules.tyreStintLaps, Modifier.weight(1f)) { onRules(rules.copy(tyreStintLaps = it?.takeIf { n -> n > 0 })) }
            }
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text(tyreSummary(rules), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { editingTyres = true }) { Text("Gommes") }
            }
            Text(
                "À la radio aussi : « course de 30 minutes », « arrêt obligatoire », « consommation fois deux », « les pneus tiennent huit tours », « stratégie ? ».",
                color = PitColors.Muted,
                style = MaterialTheme.typography.bodySmall,
            )

            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
            val plan = state.plan
            if (plan == null) {
                Text(
                    "Plan : il me faut le nombre de tours (ou la durée et un tour chronométré) et une consommation mesurée ou mémorisée.",
                    color = PitColors.Muted,
                )
            } else {
                Text(
                    (if (plan.estimatedLaps) "Course estimée à ${plan.totalLaps} tours" else "Course de ${plan.totalLaps} tours") +
                        (plan.perLap?.let { " • " + FuelUnits.perLap(it, state.snapshot.packet?.fuelCapacity) } ?: ""),
                    fontWeight = FontWeight.Bold,
                )
                Text(sourceLabel(plan.lapsSource, plan.fuelSource), color = PitColors.Muted, style = MaterialTheme.typography.bodySmall)
                plan.warnings.forEach { w ->
                    Text(warningLabel(w), color = PitColors.Red, fontWeight = FontWeight.Bold)
                }
                if (plan.stops.isEmpty()) {
                    Text("Pas d'arrêt prévu.", color = PitColors.Green, fontWeight = FontWeight.Bold)
                } else {
                    plan.stops.forEachIndexed { i, s ->
                        val reasons = buildList {
                            if (s.forFuel) add(FuelUnits.short((s.fuel ?: 0).toFloat(), state.snapshot.packet?.fuelCapacity))
                            if (s.mandatory) add("obligatoire")
                            if (s.noFuelNeeded) add("sans essence")
                            s.tyreToFit?.let { add("monter ${it.name}") }
                            if (s.changeTyres && s.tyreToFit == null) add("pneus neufs")
                            if (s.fuel == null) add("quantité après un tour mesuré")
                        }
                        Text(
                            "Arrêt ${i + 1} : fin du tour ${s.endOfLap} • ${reasons.joinToString(" • ")}",
                            color = PitColors.Orange,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    plan.pitLaneDeltaS?.let {
                        Text("Voie des stands ≈ ${it.roundToInt()} s par passage (base GT7), plus le temps du plein.", color = PitColors.Muted, style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (plan.startTyreUnknown) Text("Pneus de départ non déclarés.", color = PitColors.Yellow)
            }

            StrategySection(state)

            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
            Text("Registre de course", style = MaterialTheme.typography.labelSmall, color = PitColors.Muted)
            if (state.stops.isEmpty()) {
                Text("Aucun arrêt enregistré.", color = PitColors.Muted, style = MaterialTheme.typography.bodySmall)
            }
            state.stops.forEach { st ->
                val proof = when (st.evidence) {
                    StopEvidence.REFUEL -> "ravitaillement " + FuelUnits.short(st.fuelAdded ?: 0f, state.snapshot.packet?.fuelCapacity)
                    StopEvidence.DRIVER_CONFIRMED -> "confirmé par toi"
                    StopEvidence.DRIVER_DECLARED -> "déclaré par toi"
                }
                Text("Arrêt ${st.number} • tour ${st.lap} • $proof", style = MaterialTheme.typography.bodySmall)
            }
            state.pendingStop?.let {
                Text("Arrêt possible au tour ${it.lap}, sans ravitaillement : à confirmer.", color = PitColors.Yellow, fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { onCommand(DriverCommand.Yes) }, modifier = Modifier.weight(1f)) { Text("C'était un arrêt") }
                    OutlinedButton(onClick = { onCommand(DriverCommand.No) }, modifier = Modifier.weight(1f)) { Text("Non") }
                }
            }
            if (rules.requiredTyres.isNotEmpty()) {
                Text(
                    "Gommes déclarées : " + (state.tyresUsed.joinToString(", ") { it.name }.ifEmpty { "aucune" }) +
                        if (state.missingTyres.isNotEmpty()) " • manque ${state.missingTyres.joinToString(", ") { it.name }}" else "",
                    color = if (state.missingTyres.isEmpty()) PitColors.Green else PitColors.Yellow,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            rules.tyreStintLaps?.let { life ->
                Text(
                    "Pneus actuels : ${state.tyreAgeLaps} tour${if (state.tyreAgeLaps > 1) "s" else ""} sur $life",
                    color = if (state.tyreAgeLaps >= life) PitColors.Red else PitColors.Muted,
                    style = MaterialTheme.typography.bodySmall,
                )
                if (!state.askTyreChange) {
                    OutlinedButton(onClick = { onCommand(DriverCommand.TyresChanged) }, modifier = Modifier.fillMaxWidth()) { Text("Pneus changés") }
                }
            }
            if (state.askTyreChange) {
                Text("As-tu changé les pneus ?", color = PitColors.Yellow, fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { onCommand(DriverCommand.Yes) }, modifier = Modifier.weight(1f)) { Text("Oui, pneus neufs") }
                    OutlinedButton(onClick = { onCommand(DriverCommand.No) }, modifier = Modifier.weight(1f)) { Text("Mêmes pneus") }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { onCommand(DriverCommand.DeclareStop) }, modifier = Modifier.weight(1f)) { Text("Arrêt fait") }
                OutlinedButton(onClick = { onCommand(DriverCommand.CancelStop) }, modifier = Modifier.weight(1f)) { Text("Annuler arrêt") }
            }
            if (!rules.isEmpty) {
                TextButton(onClick = { onRules(RaceRules(trackId = rules.trackId)) }) { Text("Effacer le règlement") }
            }
        }
    }

    if (editingTyres) {
        TyreRulesDialog(rules, onDismiss = { editingTyres = false }, onRules = onRules)
    }

    if (picking) {
        CoursePicker(database, onDismiss = { picking = false }) { course ->
            picking = false
            onRules(rules.copy(trackId = course?.id))
        }
    }
}

/**
 * Épreuve active : chaque épreuve (Sport A, B, C, autre × qualification, course) a son règlement et son historique,
 * jamais mélangés. Version de GT7 et contexte BoP sont déclarés par le pilote ; le règlement se confirme ici.
 */
@Composable
private fun EventProfileSection(
    state: PitwallUiState,
    onProfile: (ProfileId) -> Unit,
    onProfileInfo: (EventProfile) -> Unit,
    onConfirmRules: () -> Unit,
) {
    val profile = state.profile
    val id = profile.id
    // Type de session : la télémétrie ne distingue pas une Course Sport en ligne d'une course hors ligne, le pilote choisit.
    Text("Type de session", style = MaterialTheme.typography.labelSmall, color = PitColors.Muted)
    SessionMode.entries.filter { it.selectable }.chunked(2).forEach { row ->
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            row.forEach { m ->
                if (m == id.mode) {
                    Button(onClick = {}, modifier = Modifier.weight(1f)) { Text(m.label, maxLines = 1) }
                } else {
                    OutlinedButton(onClick = { onProfile(ProfileId.of(m, id.series)) }, modifier = Modifier.weight(1f)) { Text(m.label, maxLines = 1) }
                }
            }
        }
    }
    Text(id.mode.detail, style = MaterialTheme.typography.bodySmall, color = if (id.mode == SessionMode.SPORT_PRACTICE) PitColors.Yellow else PitColors.Muted)
    if (id.mode != SessionMode.CUSTOM) {
        Text("Épreuve", style = MaterialTheme.typography.labelSmall, color = PitColors.Muted)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Series.entries.filter { it != Series.OTHER || id.mode == SessionMode.TIME_TRIAL }.forEach { s ->
                val label = when (s) {
                    Series.DAILY_A -> "A"
                    Series.DAILY_B -> "B"
                    Series.DAILY_C -> "C"
                    Series.OTHER -> "Autre"
                }
                if (s == id.series) {
                    Button(onClick = {}, modifier = Modifier.weight(1f)) { Text(label, maxLines = 1) }
                } else {
                    OutlinedButton(onClick = { onProfile(ProfileId.of(id.mode, s)) }, modifier = Modifier.weight(1f)) { Text(label, maxLines = 1) }
                }
            }
        }
    }
    // Chaque frappe met à jour le profil ; changer la version ou les réglages retire la confirmation du règlement.
    var version by remember(id) { mutableStateOf(profile.gameVersion ?: "") }
    var setup by remember(id) { mutableStateOf(profile.setupNote ?: "") }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = version,
            onValueChange = {
                version = it.take(12)
                onProfileInfo(profile.copy(gameVersion = version.trim().ifEmpty { null }))
            },
            label = { Text("Version GT7", maxLines = 1) },
            singleLine = true,
            modifier = Modifier.weight(1f),
        )
        OutlinedTextField(
            value = setup,
            onValueChange = {
                setup = it.take(60)
                onProfileInfo(profile.copy(setupNote = setup.trim().ifEmpty { null }))
            },
            label = { Text("Réglages / BoP", maxLines = 1) },
            singleLine = true,
            modifier = Modifier.weight(2f),
        )
    }
    if (profile.confirmed) {
        Text(
            "Règlement confirmé" + (profile.eventDate?.let { " le $it" } ?: "") + ".",
            color = PitColors.Green,
            style = MaterialTheme.typography.bodySmall,
        )
    } else {
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text(
                "Règlement non confirmé : vérifie-le ci-dessous.",
                Modifier.weight(1f),
                color = PitColors.Yellow,
                style = MaterialTheme.typography.bodySmall,
            )
            TextButton(onClick = onConfirmRules) { Text("Confirmer") }
        }
    }
    val h = state.history
    Text(
        when {
            h.sameProfile == 0 -> "Historique : aucune session archivée pour cette épreuve (${state.archivedSessions} au total)."
            else -> "Historique : ${h.comparable} session(s) comparable(s) sur ${h.sameProfile} de cette épreuve " +
                "(${h.comparableLaps} tours mesurés), dont ${h.sameEvent} avec le même règlement."
        },
        color = PitColors.Muted,
        style = MaterialTheme.typography.bodySmall,
    )
}

/**
 * Programme de la semaine pour l'épreuve choisie (Sport A, B, C) : chaque champ avec son statut et sa source ; ce qui
 * reste inconnu est montré, avec une saisie rapide. Rien n'est présenté comme vérifié sans source.
 */
@Composable
private fun WeeklyEditionSection(
    state: PitwallUiState,
    rules: RaceRules,
    database: Gt7Database,
    onRules: (RaceRules) -> Unit,
    onRefresh: () -> Unit,
    onDeclare: (RaceRules, Set<RuleField>) -> Unit,
) {
    val id = state.profile.id
    if (id.series == Series.OTHER) return
    val context = LocalContext.current
    val keyStore = remember(context) { SportApiCredentialStore(context) }
    var gridKey by remember { mutableStateOf("") }
    var apiConnected by remember { mutableStateOf(keyStore.configured()) }
    var apiMessage by remember { mutableStateOf<String?>(null) }
    val ed = state.edition
    val status = state.profile.ruleStatus
    val sched = state.schedule
    Text(
        "${id.series.label.uppercase()} — " + (ed?.let { "SEMAINE DU ${FrenchFormat.dayMonth(it.validFrom).uppercase()}" } ?: "PROGRAMME INCONNU"),
        fontWeight = FontWeight.Bold,
    )
    // Course PS5 du 26/10 : profil B chargé pour 6 tours, GT7 annonce 14 tours. L'UDP
    // connaît la distance mais PAS l'identité officielle A/B/C : ne jamais prétendre
    // qu'un règlement hebdomadaire s'applique si la distance ne correspond pas.
    val gt7Laps = state.snapshot.packet?.totalLaps?.takeIf { it > 0 }
    if (id.kind == SessionKind.RACE && gt7Laps != null &&
        rules.totalLaps != null && gt7Laps != rules.totalLaps
    ) {
        Text(
            "⚠ Programme à vérifier : GT7 annonce $gt7Laps tours, mais le profil ${id.series.label} en prévoit ${rules.totalLaps}. " +
                "La distance GT7 est prioritaire ; pneus imposés et arrêts du profil peuvent ne pas être ceux de cette course.",
            color = PitColors.Yellow,
            style = MaterialTheme.typography.bodySmall,
        )
    }
    fun st(f: RuleField): String = status[f]?.label ?: "saisi par toi"
    fun color(f: RuleField) = when (status[f]) {
        FieldStatus.CONFIRMED, FieldStatus.PILOT, null -> PitColors.Muted
        FieldStatus.PROBABLE -> PitColors.Cyan
        FieldStatus.UNKNOWN, FieldStatus.CONFLICT -> PitColors.Yellow
    }
    @Composable
    fun line(label: String, value: String?, f: RuleField?) {
        val v = value ?: "inconnu"
        Text(
            "$label : $v" + (f?.let { " (${st(it)})" } ?: ""),
            style = MaterialTheme.typography.bodySmall,
            color = f?.let { color(it) } ?: PitColors.Muted,
        )
    }
    if (ed != null) {
        // The name may be verified by sources even before the new circuit appears in
        // the local GT7 database. This does NOT establish a telemetry track ID.
        if (rules.trackId == null && ed.trackName.known) {
            Text(
                "Circuit : "+ ed.trackName.value + " (nom " + ed.trackName.status.label +
                    ", reconnaissance PS5 en attente)",
                color = PitColors.Yellow,
                style = MaterialTheme.typography.bodySmall,
            )
        } else {
            line("Circuit", database.course(rules.trackId ?: -1)?.label ?: ed.trackName.value, RuleField.TRACK)
        }
        if (rules.trackId == null && ed.trackCandidates.isNotEmpty()) {
            ed.trackCandidates.mapNotNull(database::course).take(3).forEach { c ->
                OutlinedButton(onClick = { onRules(rules.copy(trackId = c.id)) }, modifier = Modifier.fillMaxWidth()) { Text(c.label) }
            }
        }
        line("Voitures", ed.cars.value, RuleField.CARS)
        if (id.kind == SessionKind.RACE) {
            line("Tours", rules.totalLaps?.toString(), RuleField.LAPS)
            line("Carburant", "×${rules.fuelMultiplier}".takeIf { status[RuleField.FUEL_MULTIPLIER].known() }, RuleField.FUEL_MULTIPLIER)
            line("Usure des pneus", "×${rules.tyreMultiplier}".takeIf { status[RuleField.TYRE_MULTIPLIER].known() }, RuleField.TYRE_MULTIPLIER)
            line("Arrêts obligatoires", rules.mandatoryStops.toString().takeIf { status[RuleField.MANDATORY_STOPS].known() }, RuleField.MANDATORY_STOPS)
        }
        line("Pneus autorisés", rules.allowedTyres.joinToString(", ") { it.name }.ifEmpty { null }, RuleField.ALLOWED_TYRES)
        if (id.kind == SessionKind.RACE) {
            line(
                "Pneus imposés",
                (if (rules.requiredTyres.isEmpty()) "aucun" else rules.requiredTyres.joinToString(", ") { it.name })
                    .takeIf { status[RuleField.REQUIRED_TYRES].known() },
                RuleField.REQUIRED_TYRES,
            )
        }
        line("BoP / réglages", listOfNotNull(ed.bop.value, ed.settings.value).joinToString(" • ").ifEmpty { null }, null)
        listOf(ed.laps, ed.mandatoryStops, ed.fuelMultiplier, ed.tyreMultiplier, ed.trackName,
            ed.cars, ed.requiredTyres).mapNotNull { it.note }.forEach {
            Text("Sources en désaccord : $it", color = PitColors.Red, style = MaterialTheme.typography.bodySmall)
        }
    } else {
        Text(
            if (sched?.stale == true || sched == null) "Aucune fiche publique lue pour cette semaine : saisis le règlement ci-dessous." else "",
            color = PitColors.Yellow,
            style = MaterialTheme.typography.bodySmall,
        )
    }
    val gaps = state.ruleGaps
    if (gaps.isNotEmpty()) {
        Text("À confirmer ou saisir : ${gaps.joinToString(", ") { it.label }}.", color = PitColors.Yellow, style = MaterialTheme.typography.bodySmall)
        if (RuleField.MANDATORY_STOPS in gaps) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text("Arrêts obligatoires :", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                (0..2).forEach { n ->
                    OutlinedButton(onClick = { onDeclare(rules.copy(mandatoryStops = n), setOf(RuleField.MANDATORY_STOPS)) }) { Text("$n") }
                }
            }
        }
        if (RuleField.REQUIRED_TYRES in gaps) {
            OutlinedButton(
                onClick = { onDeclare(rules.copy(requiredTyres = emptySet()), setOf(RuleField.REQUIRED_TYRES)) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Aucune gomme imposée") }
        }
        if (RuleField.FUEL_MULTIPLIER in gaps) {
            OutlinedButton(
                onClick = { onDeclare(rules.copy(fuelMultiplier = rules.fuelMultiplier), setOf(RuleField.FUEL_MULTIPLIER)) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Carburant ×${rules.fuelMultiplier} confirmé") }
        }
    }
    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        val now = System.currentTimeMillis()
        Text(
            buildString {
                append("Source : ")
                append(ed?.sources?.joinToString { it.name } ?: "GTPlanet (flux public)")
                append(", lu ${RefreshPolicy.age(now, sched?.lastFetchMs)}")
                sched?.lastError?.let { append(" • dernière lecture en échec : $it") }
            },
            Modifier.weight(1f),
            color = PitColors.Muted,
            style = MaterialTheme.typography.bodySmall,
        )
        TextButton(onClick = onRefresh) { Text("Actualiser") }
    }
    Text(
        if (apiConnected) "Source additionnelle GT GridStats configurée (clé protégée sur ce téléphone)."
        else "Source complémentaire GT GridStats : clé API facultative, aucune carte bancaire demandée par Pitwall.",
        color = PitColors.Muted,
        style = MaterialTheme.typography.bodySmall,
    )
    OutlinedTextField(
        value = gridKey,
        onValueChange = { gridKey = it.take(1024) },
        label = { Text(if (apiConnected) "Remplacer la clé API" else "Clé API personnelle GT GridStats") },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        modifier = Modifier.fillMaxWidth(),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(
            enabled = gridKey.isNotBlank(),
            onClick = {
                if (keyStore.save(gridKey)) {
                    apiConnected = true
                    apiMessage = "Clé enregistrée. Synchronisation demandée."
                    gridKey = ""
                    onRefresh()
                } else apiMessage = "Impossible de protéger cette clé sur le téléphone."
            },
        ) { Text("Enregistrer et synchroniser") }
        if (apiConnected) TextButton(onClick = {
            keyStore.clear()
            apiConnected = false
            apiMessage = "Clé supprimée du téléphone."
            gridKey = ""
        }) { Text("Retirer") }
    }
    apiMessage?.let { Text(it, color = PitColors.Muted, style = MaterialTheme.typography.bodySmall) }
}

/**
 * Plan recommandé et alternatives : prochain arrêt, carburant (pourcentage du réservoir), pneus, temps estimé ou absence
 * de comparaison fiable, pourquoi, et sur quoi reposent les chiffres.
 */
@Composable
private fun StrategySection(state: PitwallUiState) {
    val report = state.strategy ?: return
    val brief = state.brief
    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
    Text("PLAN RECOMMANDÉ", style = MaterialTheme.typography.labelMedium, color = PitColors.Muted, letterSpacing = 1.5.sp)
    brief?.let { Text(it.recommendation, fontWeight = FontWeight.Bold, color = PitColors.Green) }
    Text(report.reason, style = MaterialTheme.typography.bodySmall)
    if (report.unverifiedRules.isNotEmpty()) {
        Text(
            "Non validé réglementairement : ${report.unverifiedRules.joinToString(", ")} inconnu(s).",
            color = PitColors.Red,
            style = MaterialTheme.typography.bodySmall,
        )
    }
    // Prochain arrêt : motif (pneus / carburant / règlement), tour, intervention, gain et incertitude, sources.
    val lap = (state.snapshot.packet?.currentLap ?: 1).coerceAtLeast(1)
    state.plan?.nextStop(lap)?.let { next ->
        Text("PROCHAIN ARRÊT", style = MaterialTheme.typography.labelMedium, color = PitColors.Muted, letterSpacing = 1.5.sp)
        Text("Fin du tour ${next.endOfLap} • motif : ${next.reasons.joinToString(", ").ifEmpty { "plan" }}", fontWeight = FontWeight.Bold)
        val cap = state.snapshot.packet?.fuelCapacity
        val work = buildList {
            when {
                next.forFuel -> add("carburant : " + (next.fuel?.let { com.gt7pitwall.core.strategy.FuelUnits.amount(it.toFloat(), cap) } ?: "à calculer"))
                next.fuel != null -> add("pas d'essence")
            }
            val fit = next.tyreToFit
            when {
                fit != null -> add("monter les ${fit.spoken}")
                next.changeTyres -> add("pneus neufs")
                else -> add("mêmes pneus")
            }
        }
        Text("Intervention prévue : ${work.joinToString(", ")}", style = MaterialTheme.typography.bodySmall)
    } ?: Text("Aucun arrêt prévu.", style = MaterialTheme.typography.bodySmall)
    Text("Alternatives", style = MaterialTheme.typography.labelSmall, color = PitColors.Muted)
    report.options.forEach { o ->
        val label = when (o.kind) {
            com.gt7pitwall.core.strategy.OptionKind.NORMAL -> "Normale"
            com.gt7pitwall.core.strategy.OptionKind.EARLY -> "Essence plus tôt"
            com.gt7pitwall.core.strategy.OptionKind.EXTENDED -> "Essence plus tard"
            com.gt7pitwall.core.strategy.OptionKind.TYRES_NOW -> "Pneus fin de ce tour"
            com.gt7pitwall.core.strategy.OptionKind.TYRES_NEXT -> "Pneus au prochain tour"
            com.gt7pitwall.core.strategy.OptionKind.TYRES_PLANNED -> "Pneus au meilleur tour"
            com.gt7pitwall.core.strategy.OptionKind.TYRES_WITH_FUEL -> "Pneus au ravitaillement"
            com.gt7pitwall.core.strategy.OptionKind.STAY_OUT -> "Garder ces pneus"
        }
        val stops = if (o.stopLaps.isEmpty()) "sans arrêt" else o.plan?.stops?.joinToString(", ") { s ->
            "T${s.endOfLap} " + s.reasons.joinToString("+").ifEmpty { "arrêt" }
        } ?: ("arrêt fin T" + o.stopLaps.joinToString(", T"))
        val delta = o.deltaMs
        val detail = when {
            !o.feasible -> "irréalisable : ${o.issues.firstOrNull() ?: "?"}"
            delta == null -> "temps non chiffrable"
            o.kind == com.gt7pitwall.core.strategy.OptionKind.NORMAL -> "référence"
            delta == 0L -> "même temps estimé"
            else -> (if (delta < 0) "−" else "+") + com.gt7pitwall.core.strategy.StrategyOptions.seconds(delta)
        }
        Text(
            "${if (o.kind == report.recommended) "▶ " else "   "}$label : $stops • $detail",
            style = MaterialTheme.typography.bodySmall,
            color = if (!o.feasible) PitColors.Muted else if (o.kind == report.recommended) PitColors.Green else androidx.compose.ui.graphics.Color.Unspecified,
        )
    }
    report.uncertaintyMs?.let {
        Text("Incertitude sur les écarts : ± ${com.gt7pitwall.core.strategy.StrategyOptions.seconds(it)}", color = PitColors.Muted, style = MaterialTheme.typography.bodySmall)
    }
    brief?.lastChange?.let { Text("Dernier changement : $it", color = PitColors.Yellow, style = MaterialTheme.typography.bodySmall) }
    Text("Données : ${report.basis.joinToString(" • ")}", color = PitColors.Muted, style = MaterialTheme.typography.bodySmall)
}

private fun FieldStatus?.known(): Boolean = this == null || (this != FieldStatus.UNKNOWN && this != FieldStatus.CONFLICT)

@Composable
private fun NumberField(label: String, value: Int?, modifier: Modifier, onChange: (Int?) -> Unit) {
    OutlinedTextField(
        value = value?.toString() ?: "",
        onValueChange = { txt -> onChange(txt.filter(Char::isDigit).take(3).toIntOrNull()) },
        label = { Text(label, maxLines = 1) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier,
    )
}

@Composable
private fun CoursePicker(database: Gt7Database, onDismiss: () -> Unit, onPick: (Course?) -> Unit) {
    var query by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Fermer") } },
        dismissButton = { TextButton(onClick = { onPick(null) }) { Text("Aucun") } },
        title = { Text("Choisir le circuit") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Rechercher") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                LazyColumn(Modifier.heightIn(max = 380.dp)) {
                    items(database.search(query), key = { it.id }) { c ->
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clickable { onPick(c) }
                                .padding(vertical = 8.dp),
                        ) {
                            Text(c.label)
                            Text("${c.lengthM} m • ${c.category}", color = PitColors.Muted, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        },
    )
}

private fun tyreSummary(r: RaceRules): String = buildString {
    append("Gommes : ")
    append(if (r.allowedTyres.isEmpty()) "autorisées non précisées" else "autorisées ${r.allowedTyres.joinToString(", ") { it.name }}")
    if (r.requiredTyres.isNotEmpty()) append(" • imposées ${r.requiredTyres.joinToString(", ") { it.name }}")
    append(" • départ ${r.startTyre?.name ?: "?"}")
}

private fun sourceLabel(laps: DataSource, fuel: DataSource?): String {
    val l = when (laps) {
        DataSource.GAME -> "tours : GT7"
        DataSource.RULES -> "tours : règlement"
        DataSource.ESTIMATED -> "tours : estimés avec ton rythme"
        else -> "tours : ?"
    }
    val f = when (fuel) {
        DataSource.MEASURED -> "conso : mesurée en course"
        DataSource.HISTORY -> "conso : historique (à confirmer)"
        else -> "conso : inconnue"
    }
    return "$l • $f"
}

private fun warningLabel(w: PlanWarning): String = when (w) {
    PlanWarning.CANNOT_REACH_PITS -> "⚠ Carburant insuffisant pour finir le tour"
    PlanWarning.MANDATORY_STOPS_IMPOSSIBLE -> "⚠ Plus assez de tours pour les arrêts obligatoires"
    PlanWarning.TYRES_IMPOSSIBLE -> "⚠ Plus assez d'arrêts pour les gommes imposées"
    PlanWarning.FUEL_IMPOSSIBLE -> "⚠ Un relais dépasse un plein : économie nécessaire"
    PlanWarning.TYRES_OVERDUE -> "⚠ Pneus au-delà de leur durée de vie : à changer au prochain arrêt"
}

/** Gommes du règlement : autorisées, imposées (à utiliser au moins une fois), et pneus de départ. */
@Composable
private fun TyreRulesDialog(rules: RaceRules, onDismiss: () -> Unit, onRules: (RaceRules) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } },
        title = { Text("Gommes du règlement") },
        text = {
            Column {
                Row {
                    Text("Gomme", Modifier.weight(1.6f), style = MaterialTheme.typography.labelSmall)
                    Text("Autor.", Modifier.weight(1f), style = MaterialTheme.typography.labelSmall)
                    Text("Impos.", Modifier.weight(1f), style = MaterialTheme.typography.labelSmall)
                    Text("Départ", Modifier.weight(1f), style = MaterialTheme.typography.labelSmall)
                }
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items(TyreCompound.entries.filter { it != TyreCompound.D }) { t ->
                        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Column(Modifier.weight(1.6f)) {
                                Text(t.name, fontWeight = FontWeight.Bold)
                                Text(t.spoken, color = PitColors.Muted, style = MaterialTheme.typography.bodySmall)
                            }
                            Checkbox(
                                checked = t in rules.allowedTyres,
                                onCheckedChange = { on -> onRules(rules.copy(allowedTyres = if (on) rules.allowedTyres + t else rules.allowedTyres - t)) },
                                modifier = Modifier.weight(1f),
                            )
                            Checkbox(
                                checked = t in rules.requiredTyres,
                                onCheckedChange = { on -> onRules(rules.copy(requiredTyres = if (on) rules.requiredTyres + t else rules.requiredTyres - t)) },
                                modifier = Modifier.weight(1f),
                            )
                            RadioButton(
                                selected = rules.startTyre == t,
                                onClick = { onRules(rules.copy(startTyre = if (rules.startTyre == t) null else t)) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
        },
    )
}
