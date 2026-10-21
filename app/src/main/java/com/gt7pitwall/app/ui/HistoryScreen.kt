package com.gt7pitwall.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.gt7pitwall.app.engine.PitwallEngine
import com.gt7pitwall.app.ui.theme.PitColors
import com.gt7pitwall.core.race.JournalEvent
import com.gt7pitwall.core.race.JournalSessionInfo
import com.gt7pitwall.core.race.JournalSource
import com.gt7pitwall.core.race.JournalType
import com.gt7pitwall.core.race.SessionOutcome
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Historique des courses : sessions enregistrées (archive + journal radio), chronologie consultable et export complet
 * en JSON ou en texte lisible, pour l'analyse après la course.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(engine: PitwallEngine, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    var sessions by remember { mutableStateOf<List<JournalSessionInfo>?>(null) }
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    LaunchedEffect(refresh) { sessions = engine.historySessions() }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Historique des courses", fontWeight = FontWeight.Bold) },
                navigationIcon = { TextButton(onClick = onBack) { Text("‹ Retour") } },
                actions = { TextButton(onClick = { refresh++ }) { Text("Actualiser") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        BoxWithConstraints(Modifier.fillMaxSize().padding(padding).padding(horizontal = 12.dp)) {
            val list = sessions
            val wide = maxWidth >= 600.dp
            when {
                list == null -> Text("Chargement…", color = PitColors.Muted)
                list.isEmpty() -> Text(
                    "Aucune session enregistrée. Le journal démarre avec la télémétrie, et chaque course y est conservée.",
                    color = PitColors.Muted,
                )
                wide -> Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    SessionList(list, selected, engine.liveSessionId, Modifier.weight(0.38f)) { selected = it }
                    Column(Modifier.weight(0.62f)) {
                        selected?.let { id -> SessionDetail(engine, list.firstOrNull { it.id == id }, id, refresh) }
                            ?: Text("Choisis une session.", color = PitColors.Muted)
                    }
                }
                selected == null -> SessionList(list, null, engine.liveSessionId, Modifier.fillMaxSize()) { selected = it }
                else -> Column {
                    TextButton(onClick = { selected = null }) { Text("‹ Toutes les sessions") }
                    val id = selected!!
                    SessionDetail(engine, list.firstOrNull { it.id == id }, id, refresh)
                }
            }
        }
    }
    // Retour système : d'abord la liste, puis l'écran principal.
    if (selected != null) BackHandler { selected = null }
}

private val dayFormat get() = SimpleDateFormat("EEE dd/MM HH:mm", Locale.FRANCE)
private val clockFormat get() = SimpleDateFormat("HH:mm:ss", Locale.FRANCE)

@Composable
private fun SessionList(
    sessions: List<JournalSessionInfo>,
    selected: String?,
    liveId: String?,
    modifier: Modifier,
    onSelect: (String) -> Unit,
) {
    val day = remember { dayFormat }
    LazyColumn(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(sessions, key = { it.id }) { s ->
            Card(
                Modifier.fillMaxWidth().clickable { onSelect(s.id) },
                colors = CardDefaults.cardColors(
                    containerColor = if (s.id == selected) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface,
                ),
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text(day.format(Date(s.startedAtMs)), fontWeight = FontWeight.Bold)
                    Text(s.title.removePrefix("Début de session : "), style = MaterialTheme.typography.bodySmall)
                    val status = when {
                        s.id == liveId -> "en cours d'enregistrement"
                        s.outcome == SessionOutcome.FINISHED -> "terminée"
                        s.outcome == SessionOutcome.INTERRUPTED -> "interrompue"
                        s.outcome == SessionOutcome.IN_PROGRESS -> "fermée brutalement"
                        else -> "sans tour mesuré"
                    }
                    Text(
                        "${s.laps} tour(s) · ${s.events} événement(s) · $status",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (s.id == liveId) PitColors.Green else PitColors.Muted,
                    )
                }
            }
        }
    }
}

/** Filtres de la chronologie. */
private enum class Filter(val label: String, val types: Set<JournalType>?) {
    ALL("Tout", null),
    RADIO("Radio", setOf(JournalType.DRIVER, JournalType.ENGINEER, JournalType.ALERT, JournalType.RADIO)),
    DECISIONS("Décisions", setOf(JournalType.DECISION, JournalType.STRATEGY)),
    RACE("Course", setOf(JournalType.LAP, JournalType.PIT, JournalType.POSITION, JournalType.SESSION)),
}

@Composable
private fun SessionDetail(engine: PitwallEngine, info: JournalSessionInfo?, id: String, refresh: Int) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var events by remember(id) { mutableStateOf<List<JournalEvent>?>(null) }
    var filter by rememberSaveable { mutableStateOf(Filter.ALL) }
    var expanded by remember(id) { mutableStateOf<Int?>(null) }
    LaunchedEffect(id, refresh) { events = engine.historyEvents(id) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        info?.let { Text(it.title.removePrefix("Début de session : "), fontWeight = FontWeight.Bold) }
        Text(
            "Aucun son n'est enregistré : les phrases du pilote sont des transcriptions de la reconnaissance vocale.",
            style = MaterialTheme.typography.bodySmall,
            color = PitColors.Muted,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                scope.launch { shareExport(context, engine.exportText(id), "gt7-pitwall-course-$id.txt", "GT7 Pitwall — journal de course") }
            }, modifier = Modifier.weight(1f)) { Text("Export texte") }
            OutlinedButton(onClick = {
                scope.launch {
                    shareExport(context, engine.exportJson(id), "gt7-pitwall-course-$id.json", "GT7 Pitwall — journal de course (JSON)", "application/json")
                }
            }, modifier = Modifier.weight(1f)) { Text("Export JSON") }
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(Filter.entries) { f -> FilterChip(selected = filter == f, onClick = { filter = f }, label = { Text(f.label) }) }
        }
        val all = events
        if (all == null) {
            Text("Chargement…", color = PitColors.Muted)
            return@Column
        }
        val shown = all.filter { filter.types == null || it.type in filter.types!! }
        Text("${shown.size} / ${all.size} événement(s)", style = MaterialTheme.typography.labelSmall, color = PitColors.Muted)
        val clock = remember { clockFormat }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(shown, key = { it.seq }) { e ->
                Column(
                    Modifier.fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(8.dp))
                        .clickable { expanded = if (expanded == e.seq) null else e.seq }
                        .padding(8.dp),
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(clock.format(Date(e.timeMs)), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelSmall, color = PitColors.Muted)
                        Text(e.lap?.let { "T$it" } ?: "—", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelSmall)
                        e.telemetry?.position?.let { Text("P$it", style = MaterialTheme.typography.labelSmall) }
                        Text(e.type.label, style = MaterialTheme.typography.labelSmall, color = sourceColor(e.source))
                        Text(e.source.label, style = MaterialTheme.typography.labelSmall, color = PitColors.Muted)
                    }
                    Text(e.text, style = MaterialTheme.typography.bodyMedium)
                    e.decision?.let { Text("→ $it", style = MaterialTheme.typography.bodySmall, color = PitColors.Yellow) }
                    if (expanded == e.seq) {
                        e.details.forEach { (k, v) -> Text("$k : $v", style = MaterialTheme.typography.bodySmall, color = PitColors.Muted) }
                        e.telemetry?.let { t ->
                            Text(
                                listOfNotNull(
                                    t.speedKmh?.let { "%.0f km/h".format(it) },
                                    t.fuel?.let { "carburant %.1f".format(it) },
                                    t.lapTimeMs?.let { "chrono %.1f s".format(it / 1000f) },
                                    t.gear?.let { "rapport $it" },
                                    if (t.inPit == true) "au stand" else null,
                                ).joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall,
                                color = PitColors.Muted,
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun sourceColor(s: JournalSource): Color = when (s) {
    JournalSource.TRANSCRIPT -> PitColors.Cyan
    JournalSource.SCREEN -> PitColors.Cyan
    JournalSource.GEMINI -> PitColors.Purple
    JournalSource.ENGINE -> PitColors.Orange
    JournalSource.TELEMETRY -> PitColors.Green
    JournalSource.SYSTEM -> PitColors.Muted
}
