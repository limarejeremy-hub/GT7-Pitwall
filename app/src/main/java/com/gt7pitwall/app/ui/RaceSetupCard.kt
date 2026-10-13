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
import androidx.compose.material3.Card
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
import com.gt7pitwall.core.strategy.RaceRules
import java.util.Locale
import kotlin.math.roundToInt

/** Voiture (reconnue), circuit (reconnu ou choisi), règlement de l'épreuve et plan de course. */
@Composable
fun RaceSetupCard(state: PitwallUiState, rules: RaceRules, database: Gt7Database, onRules: (RaceRules) -> Unit) {
    var picking by rememberSaveable { mutableStateOf(false) }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("COURSE & STRATÉGIE", style = MaterialTheme.typography.labelMedium, color = PitColors.Muted, letterSpacing = 1.5.sp)

            val p = state.snapshot.packet
            Text(
                when {
                    p == null -> "Voiture : en attente de télémétrie"
                    state.carName != null -> "Voiture : ${state.carName}"
                    else -> "Voiture : code ${p.carCode} (absent de la base)"
                },
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
                    m.fuelPerLapX1?.let { add(String.format(Locale.FRANCE, "%.2f L/tour à ×1", it)) }
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
                OutlinedTextField(
                    value = rules.requiredTyres,
                    onValueChange = { onRules(rules.copy(requiredTyres = it.take(30))) },
                    label = { Text("Gommes imp.") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
            }
            Text(
                "À la radio aussi : « course de 30 minutes », « arrêt obligatoire », « consommation fois deux », « stratégie ? ».",
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
                        (plan.perLap?.let { String.format(Locale.FRANCE, " • %.2f L/tour", it) } ?: ""),
                    fontWeight = FontWeight.Bold,
                )
                if (plan.stops.isEmpty()) {
                    Text("Pas d'arrêt : le carburant suffit.", color = PitColors.Green, fontWeight = FontWeight.Bold)
                } else {
                    plan.stops.forEachIndexed { i, s ->
                        Text(
                            "Arrêt ${i + 1} : fin du tour ${s.endOfLap} • " + when {
                                s.mandatoryOnly -> "obligatoire, sans essence"
                                s.liters != null -> "${s.liters} L"
                                else -> "litres après un tour mesuré"
                            },
                            color = PitColors.Orange,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    plan.pitLaneDeltaS?.let {
                        Text("Voie des stands ≈ ${it.roundToInt()} s par passage (base GT7), plus le temps du plein.", color = PitColors.Muted, style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (plan.requiredTyres.isNotBlank()) Text("Gommes imposées : ${plan.requiredTyres}", color = PitColors.Yellow)
            }
            if (!rules.isEmpty) {
                TextButton(onClick = { onRules(RaceRules(trackId = rules.trackId)) }) { Text("Effacer le règlement") }
            }
        }
    }

    if (picking) {
        CoursePicker(database, onDismiss = { picking = false }) { course ->
            picking = false
            onRules(rules.copy(trackId = course?.id))
        }
    }
}

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
