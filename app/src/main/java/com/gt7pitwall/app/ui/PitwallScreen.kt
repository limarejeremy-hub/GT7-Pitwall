package com.gt7pitwall.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.RadioButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gt7pitwall.app.ai.AiState
import com.gt7pitwall.app.ai.GeminiNano
import com.gt7pitwall.core.ai.AiTrace
import com.gt7pitwall.app.discord.DiscordRadio
import com.gt7pitwall.app.discord.DiscordState
import com.gt7pitwall.app.engine.PitwallEngine
import com.gt7pitwall.app.engine.PitwallUiState
import com.gt7pitwall.app.settings.SettingsStore
import com.gt7pitwall.app.ui.theme.PitColors
import com.gt7pitwall.app.voice.BenchModel
import com.gt7pitwall.app.voice.ListenState
import com.gt7pitwall.app.voice.SherpaBench
import com.gt7pitwall.app.voice.ModelState
import com.gt7pitwall.app.voice.TtsVoiceOutput
import com.gt7pitwall.app.voice.VoiceModelManager
import com.gt7pitwall.app.voice.VoiceState
import com.gt7pitwall.app.voice.VoskVoiceInput
import com.gt7pitwall.core.analysis.SectorComparison
import com.gt7pitwall.core.engineer.EngineerMessage
import com.gt7pitwall.core.engineer.FrenchFormat
import com.gt7pitwall.core.engineer.MessageCategory
import com.gt7pitwall.core.engineer.Priority
import com.gt7pitwall.core.radio.DriverCommand
import com.gt7pitwall.core.radio.DriverQuery
import com.gt7pitwall.core.session.LapRecord
import com.gt7pitwall.core.strategy.FuelAdvice
import com.gt7pitwall.core.strategy.FuelReport
import com.gt7pitwall.core.telemetry.ConnectionStatus
import com.gt7pitwall.core.telemetry.TelemetryPacket
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PitwallScreen(
    engine: PitwallEngine,
    voice: TtsVoiceOutput,
    settings: SettingsStore,
    voiceModels: VoiceModelManager,
    voiceInput: VoskVoiceInput,
    gemini: GeminiNano,
    discord: DiscordRadio,
    bench: SherpaBench,
    onDiscordMirror: (Boolean) -> Unit,
    onDiscordMic: (Boolean) -> Unit,
    onAiToggle: (Boolean) -> Unit,
    onGoogleOnHeadset: (Boolean) -> Unit,
    onDevTools: (Boolean) -> Unit,
    onStart: (String) -> Unit,
    onStop: () -> Unit,
    onVoiceInputToggle: (Boolean) -> Unit,
) {
    val state by engine.state.collectAsStateWithLifecycle()
    val connection by engine.connection.collectAsStateWithLifecycle()
    val running by engine.running.collectAsStateWithLifecycle()
    val radio by engine.radio.collectAsStateWithLifecycle()
    val voiceState by voice.state.collectAsStateWithLifecycle()
    val rules by engine.rules.collectAsStateWithLifecycle()
    val modelState by voiceModels.state.collectAsStateWithLifecycle()
    val listenState by voiceInput.state.collectAsStateWithLifecycle()
    val heard by voiceInput.heard.collectAsStateWithLifecycle()
    val listenError by voiceInput.error.collectAsStateWithLifecycle()
    var wakeWord by rememberSaveable { mutableStateOf(settings.wakeWord) }
    var limitedVocab by rememberSaveable { mutableStateOf(settings.limitedVocabulary) }
    var askPosition by rememberSaveable { mutableStateOf(settings.askPositionEachLap) }
    var googleSpeech by rememberSaveable { mutableStateOf(settings.googleSpeech) }
    var aiOn by rememberSaveable { mutableStateOf(settings.aiEnabled) }
    var discordAutoJoin by rememberSaveable { mutableStateOf(settings.discordAutoJoin) }
    var discordMirror by rememberSaveable { mutableStateOf(settings.discordMirrorPhone) }
    var discordMic by rememberSaveable { mutableStateOf(settings.discordMicInput) }
    var googleHeadset by rememberSaveable { mutableStateOf(settings.googleOnHeadset) }
    var devTools by rememberSaveable { mutableStateOf(settings.devTools) }
    val micLabel by voiceInput.micLabel.collectAsStateWithLifecycle()
    val aiState by gemini.state.collectAsStateWithLifecycle()
    val aiDetail by gemini.detail.collectAsStateWithLifecycle()
    val aiTest by gemini.testResult.collectAsStateWithLifecycle()
    val aiTrace by engine.aiTrace.collectAsStateWithLifecycle()

    val voiceRadioCard: @Composable () -> Unit = {
        VoiceRadioCard(
            modelState = modelState,
            listenState = listenState,
            heard = heard,
            error = listenError,
            wakeWord = wakeWord,
            onWakeWordChange = {
                wakeWord = it
                settings.wakeWord = it
                voiceInput.wakeWord = settings.wakeWord
            },
            onDownload = voiceModels::download,
            onToggle = onVoiceInputToggle,
            limitedVocabulary = limitedVocab,
            onLimitedVocabularyChange = {
                limitedVocab = it
                settings.limitedVocabulary = it
                voiceInput.limitedVocabulary = it
            },
            askPosition = askPosition,
            onAskPositionChange = {
                askPosition = it
                settings.askPositionEachLap = it
            },
            googleSpeech = googleSpeech,
            onGoogleSpeechChange = {
                googleSpeech = it
                settings.googleSpeech = it
                voiceInput.useGoogleSpeech = it
            },
        )
        RadioDiagnosticsCard(
            voiceInput,
            googleSpeech,
            googleHeadset = googleHeadset,
            onGoogleHeadset = {
                googleHeadset = it
                onGoogleOnHeadset(it)
            },
            devTools = devTools,
            onDevTools = {
                devTools = it
                onDevTools(it)
            },
        )
        if (devTools) BenchCard(bench)
        DiscordCard(
            discord = discord,
            autoJoin = discordAutoJoin,
            onAutoJoinChange = {
                discordAutoJoin = it
                settings.discordAutoJoin = it
            },
            mirror = discordMirror,
            onMirrorChange = {
                discordMirror = it
                onDiscordMirror(it)
            },
            micModelDir = voiceModels::installedDir,
            micInput = discordMic,
            onMicInputChange = {
                discordMic = it
                onDiscordMic(it)
            },
            listeningOn = micLabel,
        )
        AiCard(
            on = aiOn,
            state = aiState,
            detail = aiDetail,
            wakeWord = wakeWord,
            onToggle = {
                aiOn = it
                onAiToggle(it)
            },
            testResult = aiTest,
            onTest = { gemini.selfTest(engine::sayAiTest) },
            onAsk = engine::askAiFromScreen,
            trace = aiTrace,
        )
    }

    var address by rememberSaveable { mutableStateOf(settings.ps5Address) }
    var voiceOn by rememberSaveable { mutableStateOf(settings.voiceEnabled) }

    val connectionCard: @Composable () -> Unit = {
        ConnectionCard(
            connection = connection,
            running = running,
            address = address,
            onAddressChange = { address = it },
            onStart = { onStart(address.trim()) },
            onStop = onStop,
            voiceOn = voiceOn,
            onVoiceChange = {
                voiceOn = it
                settings.voiceEnabled = it
                engine.voiceEnabled = it
            },
            voiceState = voiceState,
            onTestVoice = engine::testVoice,
        )
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("GT7 Pitwall", fontWeight = FontWeight.Bold) },
                actions = { StatusPill(connection) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        BoxWithConstraints(Modifier.fillMaxSize().padding(padding)) {
            val wide = maxWidth >= 600.dp
            if (wide) {
                // Fold déplié : deux colonnes.
                Row(
                    Modifier.fillMaxSize().padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    ScrollColumn(Modifier.weight(1f)) {
                        DriveCard(state.snapshot.packet)
                        LapCard(state)
                        CarCard(state.snapshot.packet, state.gForce)
                        LapHistoryCard(state.snapshot.laps)
                        connectionCard()
                    }
                    ScrollColumn(Modifier.weight(1f)) {
                        RaceSetupCard(state, rules, engine.database, engine::setRules, engine::command, engine::selectProfile, engine::updateProfile, engine::confirmRules)
                        TrackMapCard(state.snapshot.trackOutline, state.snapshot.packet)
                        FuelCard(state.fuel, state.snapshot.packet)
                        RadioCard(radio, engine::command)
                        voiceRadioCard()
                    }
                }
            } else {
                // Écran externe (plié) : une colonne, l'essentiel en haut.
                ScrollColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
                    if (!running) connectionCard()
                    DriveCard(state.snapshot.packet)
                    LapCard(state)
                    FuelCard(state.fuel, state.snapshot.packet)
                    RaceSetupCard(state, rules, engine.database, engine::setRules, engine::command, engine::selectProfile, engine::updateProfile, engine::confirmRules)
                    CarCard(state.snapshot.packet, state.gForce)
                    TrackMapCard(state.snapshot.trackOutline, state.snapshot.packet)
                    RadioCard(radio, engine::command)
                    voiceRadioCard()
                    LapHistoryCard(state.snapshot.laps)
                    if (running) connectionCard()
                }
            }
        }
    }
}

@Composable
private fun ScrollColumn(modifier: Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.verticalScroll(rememberScrollState()).padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
}

@Composable
private fun PitCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                title.uppercase(),
                style = MaterialTheme.typography.labelMedium,
                color = PitColors.Muted,
                letterSpacing = 1.5.sp,
            )
            content()
        }
    }
}

@Composable
private fun StatusPill(status: ConnectionStatus) {
    val (label, color) = when (status) {
        ConnectionStatus.Stopped -> "Arrêté" to PitColors.Muted
        is ConnectionStatus.Searching -> "Recherche" to PitColors.Yellow
        is ConnectionStatus.Receiving -> "En direct" to PitColors.Green
        is ConnectionStatus.NoData -> "Silence" to PitColors.Orange
        is ConnectionStatus.Error -> "Erreur" to PitColors.Red
    }
    Row(
        Modifier
            .padding(end = 12.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(50))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(10.dp).background(color, CircleShape))
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.labelLarge)
    }
}

private fun describe(status: ConnectionStatus): String = when (status) {
    ConnectionStatus.Stopped -> "Réception arrêtée."
    is ConnectionStatus.Searching ->
        if (status.target == null) "Recherche automatique de la PS5 sur le Wi-Fi…" else "Connexion à ${status.target}…"
    is ConnectionStatus.Receiving -> buildString {
        append("PS5 ${status.ps5Address} • ${status.packetsPerSecond} paquets/s • format ${status.format.label}")
        if (status.fullFormatUnanswered) append("\nLe format complet (~) n'a pas donné de paquet lisible : format A utilisé.")
        if (status.invalidPackets > 0) {
            append("\nDiagnostic : ${status.invalidPackets} paquet(s) illisible(s), dernier de ${status.lastInvalidSize} octets.")
        }
    }
    is ConnectionStatus.NoData ->
        "PS5 ${status.ps5Address} : plus de données depuis ${status.silenceMs / 1000} s (menu ou rediffusion ?)"
    is ConnectionStatus.Error -> status.message
}

@Composable
private fun ConnectionCard(
    connection: ConnectionStatus,
    running: Boolean,
    address: String,
    onAddressChange: (String) -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    voiceOn: Boolean,
    onVoiceChange: (Boolean) -> Unit,
    voiceState: VoiceState,
    onTestVoice: () -> Unit,
) {
    PitCard("Connexion PS5") {
        Text(describe(connection), style = MaterialTheme.typography.bodyMedium)
        OutlinedTextField(
            value = address,
            onValueChange = onAddressChange,
            enabled = !running,
            singleLine = true,
            label = { Text("Adresse IP de la PS5") },
            placeholder = { Text("Vide = recherche automatique") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
            modifier = Modifier.fillMaxWidth(),
        )
        if (running) {
            OutlinedButton(onClick = onStop, modifier = Modifier.fillMaxWidth()) { Text("Arrêter") }
        } else {
            Button(onClick = onStart, modifier = Modifier.fillMaxWidth()) {
                Text("Démarrer la télémétrie", fontWeight = FontWeight.Bold)
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Ingénieur vocal", Modifier.weight(1f))
            TextButton(onClick = onTestVoice) { Text("Tester") }
            Switch(checked = voiceOn, onCheckedChange = onVoiceChange)
        }
        when (voiceState) {
            VoiceState.FRENCH_MISSING -> Text(
                "Voix française non installée : Paramètres Android > Gestion globale > Synthèse vocale.",
                color = PitColors.Yellow,
                style = MaterialTheme.typography.bodySmall,
            )
            VoiceState.UNAVAILABLE -> Text(
                "Aucun moteur de synthèse vocale disponible sur cet appareil.",
                color = PitColors.Red,
                style = MaterialTheme.typography.bodySmall,
            )
            else -> Unit
        }
    }
}

@Composable
private fun DriveCard(p: TelemetryPacket?) {
    PitCard("Pilotage") {
        if (p == null) {
            Text("En attente de télémétrie", color = PitColors.Muted)
            return@PitCard
        }
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                p.speedKmh.roundToInt().toString(),
                fontSize = 64.sp,
                fontWeight = FontWeight.Black,
                fontFamily = FontFamily.Monospace,
            )
            Text(" km/h", color = PitColors.Muted, modifier = Modifier.padding(bottom = 12.dp))
            Spacer(Modifier.weight(1f))
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    p.suggestedGearOrNull?.let { "CONSEIL $it" } ?: "RAPPORT",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (p.suggestedGearOrNull != null) PitColors.Yellow else PitColors.Muted,
                )
                Text(
                    gearLabel(p.currentGear),
                    fontSize = 48.sp,
                    fontWeight = FontWeight.Black,
                    color = if (p.isRevLimiterAlert) PitColors.Red else PitColors.Cyan,
                )
            }
        }
        val rpmMax = p.rpmAlertMax.takeIf { it > 0 }?.toFloat()
        Row {
            Text("${p.engineRpm.roundToInt()} tr/min", fontFamily = FontFamily.Monospace)
            Spacer(Modifier.weight(1f))
            if (p.isPaused) Text("PAUSE", color = PitColors.Yellow, fontWeight = FontWeight.Bold)
        }
        if (rpmMax != null) {
            // Témoin de passage : jaune à partir du seuil d'alerte bas transmis par GT7, rouge au limiteur.
            val shift = p.rpmAlertMin > 0 && p.engineRpm >= p.rpmAlertMin
            Bar(
                p.engineRpm / rpmMax,
                when {
                    p.isRevLimiterAlert -> PitColors.Red
                    shift -> PitColors.Yellow
                    else -> PitColors.Orange
                },
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f)) {
                Text("Accélérateur", style = MaterialTheme.typography.labelSmall, color = PitColors.Muted)
                Bar(p.throttle / 255f, PitColors.Green)
            }
            Column(Modifier.weight(1f)) {
                Text("Frein", style = MaterialTheme.typography.labelSmall, color = PitColors.Muted)
                Bar(p.brake / 255f, PitColors.Red)
            }
        }
    }
}

/** 0 est affiché "R" (marche arrière) : à confirmer en jeu, voir README. */
private fun gearLabel(gear: Int): String = if (gear == 0) "R" else gear.toString()

@Composable
private fun Bar(fraction: Float, color: Color) {
    LinearProgressIndicator(
        progress = { fraction.coerceIn(0f, 1f) },
        modifier = Modifier.fillMaxWidth().height(8.dp),
        color = color,
        trackColor = MaterialTheme.colorScheme.surfaceVariant,
    )
}

@Composable
private fun LapCard(state: PitwallUiState) {
    val p = state.snapshot.packet
    PitCard("Tours et chronos") {
        if (p == null) {
            Text("En attente de télémétrie", color = PitColors.Muted)
            return@PitCard
        }
        val lapText = when {
            p.currentLap <= 0 -> "Avant départ"
            p.totalLaps > 0 && p.currentLap > p.totalLaps -> "Terminée"
            p.totalLaps > 0 -> "${p.currentLap} / ${p.totalLaps}"
            else -> "${p.currentLap}"
        }
        Row(verticalAlignment = Alignment.Bottom) {
            Text("Tour ", color = PitColors.Muted)
            Text(lapText, fontSize = 34.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            state.driverPosition?.let {
                Column(horizontalAlignment = Alignment.End) {
                    Text("POSITION*", style = MaterialTheme.typography.labelSmall, color = PitColors.Muted)
                    Text("P$it", fontSize = 34.sp, fontWeight = FontWeight.Bold, color = PitColors.Cyan)
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Metric("Dernier", FrenchFormat.lapTime(p.lastLapMs.takeIf { it > 0 }), Modifier.weight(1f))
            Metric("Meilleur", FrenchFormat.lapTime(p.bestLapMs.takeIf { it > 0 }), Modifier.weight(1f), PitColors.Purple)
        }
        if (p.lastLapMs > 0 && p.bestLapMs > 0) {
            val d = p.lastLapMs - p.bestLapMs
            Text(
                "Écart dernier / meilleur : ${FrenchFormat.delta(d)}",
                color = if (d <= 0) PitColors.Green else PitColors.Yellow,
                fontFamily = FontFamily.Monospace,
            )
        }
        if (state.raining) {
            Text("Pluie signalée : chronos comparés aux tours sous la pluie", color = PitColors.Cyan, fontWeight = FontWeight.Bold)
        }
        SectorRow(state.snapshot.laps)
        if (state.driverPosition != null) {
            Text(
                "* position de départ GT7 puis d'après tes annonces radio (GT7 ne la transmet pas en course)",
                color = PitColors.Muted,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        state.stats.recentAverageMs?.let { avg ->
            Text(
                "Moyenne récente ${FrenchFormat.lapTime(avg)} • régularité ±${FrenchFormat.delta(state.stats.recentStdDevMs ?: 0).drop(1)}",
                color = PitColors.Muted,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/** Secteurs Pitwall du dernier tour comparés au meilleur tour. */
@Composable
private fun SectorRow(laps: List<LapRecord>) {
    val last = laps.lastOrNull { it.sectorsMs != null } ?: return
    val reference = laps
        .filter { it.sectorsMs != null && it.timeMs != null && it.lapNumber != last.lapNumber }
        .minByOrNull { it.timeMs!! }
    val deltas = SectorComparison.deltas(last.sectorsMs, reference?.sectorsMs)
    Text(
        if (deltas == null) "Secteurs du tour ${last.lapNumber}" else "Secteurs tour ${last.lapNumber} vs meilleur (tour ${reference!!.lapNumber})",
        style = MaterialTheme.typography.labelSmall,
        color = PitColors.Muted,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        last.sectorsMs!!.forEachIndexed { i, ms ->
            val d = deltas?.get(i)
            val color = when {
                d == null -> MaterialTheme.colorScheme.onSurface
                d <= -50 -> PitColors.Green
                d >= 50 -> PitColors.Red
                else -> PitColors.Yellow
            }
            Column(
                Modifier
                    .weight(1f)
                    .background(color.copy(alpha = 0.12f), RoundedCornerShape(8.dp))
                    .padding(6.dp),
            ) {
                Text("S${i + 1}", style = MaterialTheme.typography.labelSmall, color = PitColors.Muted)
                Text(
                    if (d == null) FrenchFormat.lapTime(ms).substringAfter(':').trimStart('0') else FrenchFormat.delta(d),
                    color = color,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

@Composable
private fun Metric(label: String, value: String, modifier: Modifier = Modifier, color: Color = Color.Unspecified) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = PitColors.Muted)
        Text(value, fontSize = 22.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = color)
    }
}

private fun f1(v: Float): String = String.format(Locale.FRANCE, "%.1f", v)
private fun f2(v: Float): String = String.format(Locale.FRANCE, "%.2f", v)

@Composable
private fun FuelCard(fuel: FuelReport?, p: TelemetryPacket?) {
    PitCard("Carburant") {
        if (fuel == null || p == null) {
            Text("En attente de télémétrie", color = PitColors.Muted)
            return@PitCard
        }
        if (fuel.advice == FuelAdvice.NotAvailable) {
            Text("GT7 ne transmet pas de réservoir pour cette voiture.", color = PitColors.Muted)
            return@PitCard
        }
        val fraction = p.fuelFraction ?: 0f
        Row(verticalAlignment = Alignment.Bottom) {
            Text("${(fraction * 100).roundToInt()} %", fontSize = 34.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(8.dp))
            Text("${f1(fuel.fuelLevel)} L / ${f1(fuel.fuelCapacity)} L", color = PitColors.Muted, modifier = Modifier.padding(bottom = 6.dp))
        }
        Bar(fraction, if (fraction < 0.15f) PitColors.Red else PitColors.Cyan)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Metric("Conso / tour", fuel.perLap?.let { "${f2(it)} L" } ?: "—", Modifier.weight(1f))
            Metric("Autonomie", fuel.lapsOfFuel?.let { "${f1(it)} t" } ?: "—", Modifier.weight(1f))
            Metric("Reste course", fuel.raceLapsRemaining?.let { "${f1(it)} t" } ?: "—", Modifier.weight(1f))
        }
        if (fuel.samples > 0) {
            Text(
                "Moyenne sur ${fuel.samples} tour(s) complet(s) sans ravitaillement.",
                color = PitColors.Muted,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        AdviceBox(fuel.advice)
    }
}

@Composable
private fun AdviceBox(advice: FuelAdvice) {
    val (text, color) = when (advice) {
        FuelAdvice.Measuring -> "Mesure en cours : il faut un tour complet observé depuis la ligne." to PitColors.Muted
        FuelAdvice.NoConsumption -> "Le niveau ne baisse pas : pas de consommation sur cette épreuve." to PitColors.Green
        FuelAdvice.NotAvailable -> "Pas de données de réservoir." to PitColors.Muted
        FuelAdvice.RaceFinished -> "Course terminée." to PitColors.Muted
        is FuelAdvice.RangeOnly ->
            "Distance de course inconnue (course au temps ou essais) : autonomie ${f1(advice.lapsOfFuel)} tours." to PitColors.Cyan
        is FuelAdvice.Comfortable -> "OK pour finir • marge ${f1(advice.marginLaps)} tour(s)" to PitColors.Green
        is FuelAdvice.Tight -> "JUSTE • vise ${f2(advice.targetPerLap)} par tour" to PitColors.Yellow
        is FuelAdvice.PitRequired -> buildString {
            append("RAVITAILLER • au plus tard fin du tour ${advice.latestPitLap} • ajouter ~${advice.fuelToAdd.roundToInt()}")
            if (advice.stopsNeeded > 1) append(" • ${advice.stopsNeeded} arrêts")
            if (advice.savingNeeded in 0f..0.10f) {
                append("\nOu économiser ${(advice.savingNeeded * 100).roundToInt()} % (cible ${f2(advice.targetPerLap)} / tour)")
            }
        } to PitColors.Red
    }
    Text(
        text,
        color = color,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .fillMaxWidth()
            .background(color.copy(alpha = 0.12f), RoundedCornerShape(10.dp))
            .padding(10.dp),
    )
}

@Composable
private fun RadioCard(messages: List<EngineerMessage>, onCommand: (DriverCommand) -> Unit) {
    val ask: (DriverQuery) -> Unit = { onCommand(DriverCommand.Ask(it)) }
    PitCard("Radio ingénieur") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            QueryButton("Essence ?", Modifier.weight(1f)) { ask(DriverQuery.FUEL) }
            QueryButton("Course ?", Modifier.weight(1f)) { ask(DriverQuery.RACE_STATUS) }
            QueryButton("Secteurs ?", Modifier.weight(1f)) { ask(DriverQuery.SECTORS) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            QueryButton("Dernier ?", Modifier.weight(1f)) { ask(DriverQuery.LAST_LAP) }
            QueryButton("Meilleur ?", Modifier.weight(1f)) { ask(DriverQuery.BEST_LAP) }
            QueryButton("Rythme ?", Modifier.weight(1f)) { ask(DriverQuery.PACE) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            QueryButton("J'ai doublé", Modifier.weight(1f)) { onCommand(DriverCommand.Overtook) }
            QueryButton("Doublé", Modifier.weight(1f)) { onCommand(DriverCommand.Overtaken) }
            QueryButton("Répète", Modifier.weight(1f)) { onCommand(DriverCommand.Repeat) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            QueryButton("Il pleut", Modifier.weight(1f)) { onCommand(DriverCommand.Rain) }
            QueryButton("Piste sèche", Modifier.weight(1f)) { onCommand(DriverCommand.Dry) }
            QueryButton("Pneus ?", Modifier.weight(1f)) { ask(DriverQuery.TYRES) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            QueryButton("Stratégie ?", Modifier.weight(1f)) { ask(DriverQuery.PLAN) }
            QueryButton("Obligations ?", Modifier.weight(1f)) { ask(DriverQuery.OBLIGATIONS) }
            QueryButton("Détails", Modifier.weight(1f)) { ask(DriverQuery.DETAILS) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            QueryButton("Radio complète", Modifier.weight(1f)) { onCommand(DriverCommand.Talk) }
            QueryButton("Essentielle", Modifier.weight(1f)) { onCommand(DriverCommand.Essential) }
            QueryButton("Critique", Modifier.weight(1f)) { onCommand(DriverCommand.Quiet) }
        }
        if (messages.isEmpty()) {
            Text("Aucun message pour l'instant.", color = PitColors.Muted)
        }
        val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.FRANCE)
        messages.take(15).forEach { m ->
            Row(Modifier.fillMaxWidth()) {
                Text(
                    timeFormat.format(Date(m.timestampMs)),
                    color = PitColors.Muted,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(end = 8.dp, top = 2.dp),
                )
                if (m.fromAi) {
                    Text(
                        "✦ Gemini",
                        color = PitColors.Purple,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(end = 6.dp, top = 2.dp),
                    )
                }
                Text(
                    m.text,
                    color = when {
                        m.fromAi -> PitColors.Purple
                        m.category == MessageCategory.DRIVER -> PitColors.Cyan
                        m.priority == Priority.CRITICAL -> PitColors.Red
                        m.priority == Priority.HIGH -> PitColors.Orange
                        else -> MaterialTheme.colorScheme.onSurface
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

@Composable
private fun QueryButton(label: String, modifier: Modifier, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = modifier,
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
        contentPadding = ButtonDefaults.TextButtonContentPadding,
    ) {
        Text(label, maxLines = 1, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun LapHistoryCard(laps: List<LapRecord>) {
    if (laps.isEmpty()) return
    val best = laps.mapNotNull { it.timeMs }.filter { it > 0 }.minOrNull()
    PitCard("Historique des tours") {
        Row {
            Text("Tour", Modifier.weight(0.5f), color = PitColors.Muted)
            Text("Temps", Modifier.weight(1.3f), color = PitColors.Muted)
            Text("Vmax", Modifier.weight(0.8f), color = PitColors.Muted)
            Text("TCS", Modifier.weight(0.6f), color = PitColors.Muted)
            Text("Carb.", Modifier.weight(0.8f), color = PitColors.Muted)
        }
        laps.asReversed().take(30).forEach { lap ->
            Row {
                Text("${lap.lapNumber}", Modifier.weight(0.5f), fontFamily = FontFamily.Monospace)
                Text(
                    FrenchFormat.lapTime(lap.timeMs),
                    Modifier.weight(1.3f),
                    fontFamily = FontFamily.Monospace,
                    color = if (lap.timeMs != null && lap.timeMs == best) PitColors.Purple else Color.Unspecified,
                )
                Text(lap.maxSpeedKmh?.roundToInt()?.toString() ?: "—", Modifier.weight(0.8f), fontFamily = FontFamily.Monospace)
                Text(
                    lap.tcsShare?.let { "${(it * 100).roundToInt()}%" } ?: "—",
                    Modifier.weight(0.6f),
                    fontFamily = FontFamily.Monospace,
                    color = if ((lap.tcsShare ?: 0f) >= 0.05f) PitColors.Yellow else Color.Unspecified,
                )
                Text(
                    when {
                        lap.refuelled -> "stand"
                        lap.fuelUsed != null -> f2(lap.fuelUsed!!)
                        else -> "—"
                    },
                    Modifier.weight(0.8f),
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
    }
}

/**
 * Diagnostic de la radio, pour comprendre une écoute sans réponse : micro écouté, état de la reconnaissance Google,
 * délai avant qu'elle soit prête, niveau sonore reçu, code d'erreur, passage du micro et repli sur Vosk. Aucun son.
 */
@Composable
private fun RadioDiagnosticsCard(
    voiceInput: VoskVoiceInput,
    googleOn: Boolean,
    googleHeadset: Boolean,
    onGoogleHeadset: (Boolean) -> Unit,
    devTools: Boolean,
    onDevTools: (Boolean) -> Unit,
) {
    val listen by voiceInput.state.collectAsStateWithLifecycle()
    val mic by voiceInput.micLabel.collectAsStateWithLifecycle()
    val handover by voiceInput.handover.collectAsStateWithLifecycle()
    val fallback by voiceInput.fallbackInfo.collectAsStateWithLifecycle()
    val heard by voiceInput.heard.collectAsStateWithLifecycle()
    val history by voiceInput.history.collectAsStateWithLifecycle()
    val google = voiceInput.googleSpeech
    val diag by (google?.diag ?: remember { kotlinx.coroutines.flow.MutableStateFlow(com.gt7pitwall.app.voice.GoogleDiag()) })
        .collectAsStateWithLifecycle()
    PitCard("Diagnostic radio") {
        DiagLine("Écoute", listen.name.lowercase().replace('_', ' '))
        DiagLine("Micro écouté", mic ?: "aucun")
        DiagLine("Google", if (!googleOn) "désactivé (Vosk seul)" else if (google?.available == false) "indisponible sur ce téléphone" else diag.phase)
        if (googleOn && diag.engine != null) {
            DiagLine("Dernier essai Google", listOfNotNull(
                diag.engine,
                diag.source,
                diag.readyMs?.let { "prête en $it ms" } ?: if (diag.outcome != null) "jamais prête" else "pas encore prête",
                if (diag.speechDetected) "parole détectée" else "aucune parole détectée",
                diag.maxRmsDb?.let { "niveau max ${"%.1f".format(it)} dB" },
                diag.streamVoice?.let { if (it) "voix présente dans le flux" else "aucune voix dans le flux" },
            ).joinToString(" • "))
            diag.stream?.let { DiagLine("Flux transmis", it) }
            diag.verdict?.let { DiagLine("Conclusion", it, if ("NON lu" in it) PitColors.Red else PitColors.Muted) }
            diag.errorName?.let { DiagLine("Erreur Google", it, PitColors.Red) }
            diag.switch?.let { DiagLine("Bascule casque", it, PitColors.Yellow) }
            handover?.let { DiagLine("Passage du micro", it, if ("NON" in it) PitColors.Red else PitColors.Muted) }
        }
        fallback?.let { DiagLine("Repli", it, PitColors.Yellow) }
        DiagLine("Dernière phrase", heard.ifBlank { "—" })
        if (history.isNotEmpty()) {
            Text("Phrases reconnues (la plus récente en haut)", style = MaterialTheme.typography.labelMedium, color = PitColors.Muted)
            history.forEach { r ->
                DiagLine(
                    "${r.time} • ${r.by}",
                    listOfNotNull(r.text?.let { "« $it »" }, r.why).joinToString(" — ").ifBlank { "—" },
                    if (r.text == null) PitColors.Yellow else PitColors.Muted,
                )
            }
        }
        SwitchLine(
            "Essayer Google sur le casque PS5 (expérimental)",
            "Désactivé : le casque passe par Vosk seul (Google ne lit pas ce flux sur ton Fold). Google reste utilisé " +
                "sur le micro du Fold.",
            googleHeadset,
            onGoogleHeadset,
        )
        SwitchLine(
            "Outils de développement",
            "Affiche le banc d'essai de reconnaissance (sherpa-onnx). Masqué, il est arrêté : ni calcul ni mémoire.",
            devTools,
            onDevTools,
        )
        if (googleHeadset && googleOn && google != null && google.streamPolicy.disabledReasons().isNotEmpty()) {
            OutlinedButton(onClick = { voiceInput.retryGoogleOnHeadset() }, modifier = Modifier.fillMaxWidth()) {
                Text("Réessayer Google sur le casque PS5")
            }
        }
    }
}

/**
 * Banc d'essai sherpa-onnx : télécharger un modèle, l'écouter en parallèle de Vosk, et lire une série de phrases radio
 * pour mesurer précision, délai, calcul, mémoire, écran éteint et batterie. N'agit jamais sur la radio.
 */
@Composable
private fun BenchCard(bench: SherpaBench) {
    val states by bench.models.states.collectAsStateWithLifecycle()
    val status by bench.status.collectAsStateWithLifecycle()
    val attempts by bench.attempts.collectAsStateWithLifecycle()
    val summary by bench.summary.collectAsStateWithLifecycle()
    val phrase by bench.phrase.collectAsStateWithLifecycle()
    val progress by bench.progress.collectAsStateWithLifecycle()
    var chosen by rememberSaveable { mutableStateOf(BenchModel.ZIPFORMER_FR) }
    PitCard("Banc d'essai sherpa-onnx (prototype)") {
        Text(
            "Écoute le même micro que Vosk (casque PS5 ou Fold) et compare. N'envoie jamais de commande ; aucun son " +
                "n'est enregistré. Vosk reste la reconnaissance de la radio.",
            style = MaterialTheme.typography.bodySmall,
            color = PitColors.Muted,
        )
        BenchModel.entries.forEach { m ->
            val st = states[m] ?: ModelState.NotInstalled
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = chosen == m, onClick = { if (!status.running) chosen = m })
                Column(Modifier.weight(1f)) {
                    Text(m.label)
                    Text(
                        when (st) {
                            ModelState.Installed -> "${m.detail} — installé (${bench.models.sizeMb(m)} Mo)"
                            ModelState.NotInstalled -> "${m.detail} — à télécharger (~${m.downloadMb} Mo, Wi-Fi conseillé)"
                            is ModelState.Downloading -> "téléchargement… ${st.progress?.let { "${(it * 100).roundToInt()} % de l'archive" } ?: ""}"
                            is ModelState.Failed -> "échec : ${st.message}"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (st is ModelState.Failed) PitColors.Red else PitColors.Muted,
                    )
                }
                when (st) {
                    ModelState.NotInstalled, is ModelState.Failed ->
                        TextButton(onClick = { bench.models.download(m) }) { Text("Télécharger") }
                    ModelState.Installed -> if (!(status.running && status.model == m)) {
                        TextButton(onClick = { bench.models.delete(m) }) { Text("Supprimer") }
                    }
                    is ModelState.Downloading -> Unit
                }
            }
        }
        val installed = states[chosen] == ModelState.Installed
        if (status.running) {
            OutlinedButton(onClick = bench::stop, modifier = Modifier.fillMaxWidth()) { Text("Arrêter le banc d'essai") }
        } else {
            Button(onClick = { bench.start(chosen) }, enabled = installed, modifier = Modifier.fillMaxWidth()) {
                Text("Lancer le banc d'essai")
            }
        }
        DiagLine("État", status.message, if (status.message.startsWith("erreur")) PitColors.Red else PitColors.Muted)
        status.load?.let { DiagLine("Modèle", it) }
        status.nativeHeapMb?.let { DiagLine("Mémoire native de l'appli", "$it Mo") }
        status.battery?.let { DiagLine("Batterie", it) }
        if (status.dropped > 0) DiagLine("Blocs perdus (calcul trop lent)", status.dropped.toString(), PitColors.Yellow)
        if (status.running && status.partial.isNotBlank()) DiagLine("En cours", status.partial)
        if (status.running) {
            val p = phrase
            if (p != null) {
                Text("Dis : « $p »  ($progress)", style = MaterialTheme.typography.titleMedium, color = PitColors.Green)
                Text(
                    "Sans « radio » : la radio ne réagit pas pendant la série.",
                    style = MaterialTheme.typography.bodySmall,
                    color = PitColors.Muted,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = bench::skipPhrase) { Text("Passer") }
                    OutlinedButton(onClick = bench::stopSeries) { Text("Arrêter la série") }
                }
            } else {
                Button(onClick = bench::startSeries, modifier = Modifier.fillMaxWidth()) {
                    Text("Série d'essai (20 phrases radio)")
                }
            }
        }
        if (summary.phrases > 0) {
            fun pct(v: Double?) = v?.let { "${(it * 100).roundToInt()} %" } ?: "—"
            DiagLine("Moteur testé", "${summary.engineExact}/${summary.phrases} phrases exactes, erreurs de mots ${pct(summary.engineWer)}")
            DiagLine("Vosk, mêmes phrases", "${summary.voskExact}/${summary.voskPhrases} exactes, erreurs de mots ${pct(summary.voskWer)}")
        }
        if (attempts.isNotEmpty()) {
            summary.meanLatencyMs?.let { DiagLine("Délai moyen après la fin de la voix", "$it ms") }
            summary.realTimeFactor?.let {
                DiagLine("Calcul", "${(it * 100).roundToInt()} % de la durée parlée" + if (it < 1) " (plus rapide que le temps réel)" else " (plus lent que le temps réel)")
            }
            if (summary.screenOff > 0) DiagLine("Phrases écran éteint", summary.screenOff.toString())
            Text("Dernières phrases (la plus récente en haut)", style = MaterialTheme.typography.labelMedium, color = PitColors.Muted)
            attempts.takeLast(8).asReversed().forEach { a ->
                fun w(v: Double?) = v?.let { " (${(it * 100).roundToInt()} % d'erreurs)" } ?: ""
                Column(Modifier.padding(vertical = 2.dp)) {
                    a.expected?.let { DiagLine("Attendu", "« $it »", PitColors.Green) }
                    DiagLine("sherpa", "« ${a.text} »${w(a.wer)} — ${a.latencyMs} ms" + if (a.screenOff) " — écran éteint" else "")
                    DiagLine(if (a.voskFree) "Vosk (libre)" else "Vosk (radio)", a.vosk?.let { "« $it »${w(a.voskWer)}" } ?: "rien au même moment")
                }
            }
        }
    }
}

@Composable
private fun SwitchLine(title: String, detail: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = PitColors.Muted)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun DiagLine(label: String, value: String, color: androidx.compose.ui.graphics.Color = PitColors.Muted) {
    Text("$label : $value", style = MaterialTheme.typography.bodySmall, color = color)
}

@Composable
private fun DiscordCard(
    discord: DiscordRadio,
    autoJoin: Boolean,
    onAutoJoinChange: (Boolean) -> Unit,
    mirror: Boolean,
    onMirrorChange: (Boolean) -> Unit,
    micModelDir: () -> java.io.File?,
    micInput: Boolean,
    onMicInputChange: (Boolean) -> Unit,
    listeningOn: String?,
) {
    val state by discord.state.collectAsStateWithLifecycle()
    val audience by discord.audience.collectAsStateWithLifecycle()
    val mic by discord.mic.collectAsStateWithLifecycle()
    val detail by discord.detail.collectAsStateWithLifecycle()
    val channels by discord.channels.collectAsStateWithLifecycle()
    var token by remember { mutableStateOf("") }
    var selected by rememberSaveable { mutableStateOf(discord.savedChannel) }
    PitCard("Radio Discord") {
        Text(
            "Le Fold se connecte comme bot à ton serveur Discord privé et parle dans un salon vocal. Sur la PS5, rejoins ce " +
                "salon : l'ingénieur arrive dans le casque de la manette, par-dessus le jeu. Si Discord décroche, il " +
                "reparle sur le haut-parleur du Fold le temps de la reconnexion.",
            color = PitColors.Muted,
            style = MaterialTheme.typography.bodySmall,
        )
        if (state == DiscordState.NO_TOKEN) {
            OutlinedTextField(
                value = token,
                onValueChange = { token = it },
                singleLine = true,
                label = { Text("Token du bot") },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = {
                    discord.saveToken(token)
                    token = ""
                },
                enabled = token.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Enregistrer (chiffré sur le téléphone)") }
        }
        val (label, color) = when (state) {
            DiscordState.NO_TOKEN -> "Aucun token enregistré." to PitColors.Muted
            DiscordState.OFFLINE -> "Déconnecté." to PitColors.Muted
            DiscordState.CONNECTING -> "Connexion…" to PitColors.Yellow
            DiscordState.ONLINE -> "Bot connecté." to PitColors.Green
            DiscordState.JOINING -> "Connexion au salon vocal…" to PitColors.Yellow
            DiscordState.IN_VOICE -> "Dans le salon vocal." to PitColors.Green
            DiscordState.ERROR -> "Erreur." to PitColors.Red
        }
        Text(label, color = color, fontWeight = FontWeight.Bold)
        val route = when {
            state != DiscordState.IN_VOICE -> "haut-parleur du Fold"
            !audience -> "haut-parleur du Fold (personne d'autre dans le salon : rejoins-le sur la PS5)"
            mirror -> "Discord (casque PS5) et haut-parleur du Fold"
            else -> "Discord (casque PS5)"
        }
        Text("Voix de l'ingénieur : $route", style = MaterialTheme.typography.bodySmall)
        Text(
            "Ta voix : " + (listeningOn ?: "écoute radio coupée (active « Écoute radio » dans la carte Radio vocale)"),
            style = MaterialTheme.typography.bodySmall,
        )
        detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = PitColors.Muted) }
        if (state == DiscordState.ONLINE || state == DiscordState.JOINING || state == DiscordState.IN_VOICE) {
            channels.forEach { ch ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = selected == ch.id, onClick = { selected = ch.id })
                    Text("${ch.guild} • 🔊 ${ch.name}", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            when (state) {
                DiscordState.OFFLINE, DiscordState.ERROR ->
                    Button(onClick = { discord.connect(joinSaved = autoJoin) }, modifier = Modifier.weight(1f)) {
                        Text("Connecter le bot")
                    }
                DiscordState.ONLINE -> Button(
                    onClick = { selected?.let(discord::join) },
                    enabled = selected != null,
                    modifier = Modifier.weight(1f),
                ) { Text("Rejoindre le salon") }
                DiscordState.IN_VOICE -> {
                    Button(onClick = discord::sayTest, modifier = Modifier.weight(1f)) { Text("Phrase test") }
                    OutlinedButton(onClick = discord::leave, modifier = Modifier.weight(1f)) { Text("Quitter le salon") }
                }
                else -> Unit
            }
        }
        if (state == DiscordState.IN_VOICE || mic != null) {
            OutlinedButton(
                onClick = { if (mic == null) discord.startMicTest(micModelDir()) else discord.stopMicTest() },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (mic == null) "Test micro PS5 (prototype)" else "Arrêter le test micro") }
        }
        mic?.let { m ->
            Text(
                "Micro PS5 : ${m.packets} paquets reçus" + (m.speaker?.let { " de $it" } ?: "") + " • niveau " +
                    "▮".repeat((m.level + 9) / 10).ifEmpty { "—" },
                style = MaterialTheme.typography.bodySmall,
            )
            m.partial?.let { Text("… $it", style = MaterialTheme.typography.bodySmall, color = PitColors.Muted) }
            m.heard.forEach { Text("Entendu : « $it »", style = MaterialTheme.typography.bodySmall, color = PitColors.Green) }
            m.note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = PitColors.Muted) }
        }
        if (state != DiscordState.NO_TOKEN) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Rejoindre automatiquement")
                    Text(
                        "Au lancement de la télémétrie (et à la connexion du bot), le bot rejoint le dernier salon utilisé.",
                        color = PitColors.Muted,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(checked = autoJoin, onCheckedChange = onAutoJoinChange)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Parler avec le micro du casque PS5")
                    Text(
                        "Quand le bot est dans le salon, la radio écoute ta voix dans Discord (« radio, … ») au lieu du " +
                            "micro du Fold. Google est essayé sur ce flux, et Vosk écoute la même phrase en secours.",
                        color = PitColors.Muted,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(checked = micInput, onCheckedChange = onMicInputChange)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Aussi sur le haut-parleur du Fold")
                    Text(
                        "Désactivé : une seule voix, sur Discord quand le bot est dans le salon.",
                        color = PitColors.Muted,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(checked = mirror, onCheckedChange = onMirrorChange)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state != DiscordState.OFFLINE && state != DiscordState.ERROR) {
                    OutlinedButton(onClick = discord::disconnect, modifier = Modifier.weight(1f)) { Text("Déconnecter") }
                }
                TextButton(onClick = discord::forgetToken, modifier = Modifier.weight(1f)) { Text("Effacer le token") }
            }
        }
    }
}

@Composable
private fun AiCard(
    on: Boolean,
    state: AiState,
    detail: String?,
    wakeWord: String,
    onToggle: (Boolean) -> Unit,
    testResult: String?,
    onTest: () -> Unit,
    trace: AiTrace?,
    onAsk: (String) -> Unit,
) {
    var question by rememberSaveable { mutableStateOf("") }
    PitCard("Ingénieur IA (Gemini Nano)") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Questions libres à la radio")
                Text(
                    "Gemini Nano tourne sur le téléphone : gratuit, hors ligne. Il ne reçoit que les données de la course " +
                        "(télémétrie GT7, plan, ce que tu as déclaré) ; une réponse avec un chiffre absent de ces données est refusée.",
                    color = PitColors.Muted,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Switch(checked = on, onCheckedChange = onToggle)
        }
        if (on) {
            val (label, color) = when (state) {
                AiState.OFF -> "Désactivé." to PitColors.Muted
                AiState.CHECKING -> "Vérification de Gemini Nano…" to PitColors.Yellow
                AiState.DOWNLOADING -> "Téléchargement du modèle (une seule fois, Wi-Fi conseillé)…" to PitColors.Yellow
                AiState.READY -> "Prêt." to PitColors.Green
                AiState.UNAVAILABLE -> "Gemini Nano indisponible sur cet appareil." to PitColors.Red
                AiState.ERROR -> "Erreur." to PitColors.Red
            }
            Text(label, color = color, fontWeight = FontWeight.Bold)
            detail?.let { Text(it, color = PitColors.Muted, style = MaterialTheme.typography.bodySmall) }
            OutlinedButton(onClick = onTest, modifier = Modifier.fillMaxWidth()) { Text("Tester Gemini") }
            Text("Demander à Gemini (sans micro) :", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { onAsk("résume ma course") }, modifier = Modifier.weight(1f)) { Text("Résumé") }
                OutlinedButton(onClick = { onAsk("comment je peux être plus rapide") }, modifier = Modifier.weight(1f)) { Text("Conseils") }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = question,
                    onValueChange = { question = it },
                    singleLine = true,
                    label = { Text("Ta question") },
                    modifier = Modifier.weight(1f),
                )
                Button(onClick = { if (question.isNotBlank()) onAsk(question.trim()) }) { Text("Envoyer") }
            }
            testResult?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            trace?.let { t ->
                Text("Dernière question : « ${t.question} »", style = MaterialTheme.typography.bodySmall)
                Text(
                    "Gemini (${t.latencyMs} ms) : ${t.raw?.trim()?.take(160)?.let { "« $it »" } ?: "—"} → ${t.verdict}",
                    color = PitColors.Muted,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Text(
                "Exemples : « $wakeWord, comment je peux être plus rapide ? » • « $wakeWord, je bloque mes roues ? » • " +
                    "« $wakeWord, résume ma course » • « $wakeWord, question : … » (force Gemini). " +
                    "Les commandes (arrêt, pneus, position, règlement) restent traitées sans l'IA.",
                color = PitColors.Muted,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun VoiceRadioCard(
    modelState: ModelState,
    listenState: ListenState,
    heard: String,
    error: String?,
    wakeWord: String,
    onWakeWordChange: (String) -> Unit,
    onDownload: () -> Unit,
    onToggle: (Boolean) -> Unit,
    limitedVocabulary: Boolean,
    onLimitedVocabularyChange: (Boolean) -> Unit,
    askPosition: Boolean,
    onAskPositionChange: (Boolean) -> Unit,
    googleSpeech: Boolean,
    onGoogleSpeechChange: (Boolean) -> Unit,
) {
    PitCard("Radio vocale") {
        when (modelState) {
            ModelState.NotInstalled, is ModelState.Failed -> {
                Text(
                    "Parle à ton ingénieur avec un mot de réveil. Reconnaissance hors ligne et gratuite (Vosk) : " +
                        "le modèle français (~${VoiceModelManager.MODEL_SIZE_MB} Mo) se télécharge une seule fois, en Wi-Fi de préférence.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (modelState is ModelState.Failed) {
                    Text("Échec : ${modelState.message}", color = PitColors.Red, style = MaterialTheme.typography.bodySmall)
                }
                Button(onClick = onDownload, modifier = Modifier.fillMaxWidth()) { Text("Télécharger la voix française") }
                return@PitCard
            }
            is ModelState.Downloading -> {
                Text("Téléchargement du modèle vocal…")
                val p = modelState.progress
                if (p == null) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                } else {
                    Bar(p, PitColors.Cyan)
                    Text("${(p * 100).roundToInt()} %", color = PitColors.Muted)
                }
                return@PitCard
            }
            ModelState.Installed -> Unit
        }

        val on = listenState != ListenState.OFF && listenState != ListenState.ERROR
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Écoute du mot de réveil", Modifier.weight(1f))
            Switch(checked = on, onCheckedChange = onToggle)
        }
        OutlinedTextField(
            value = wakeWord,
            onValueChange = onWakeWordChange,
            enabled = !on,
            singleLine = true,
            label = { Text("Mot de réveil") },
            modifier = Modifier.fillMaxWidth(),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Vocabulaire limité à la radio")
                Text(
                    "Plus fiable dans le bruit. À couper si ton mot de réveil n'est jamais reconnu. Réglable micro coupé.",
                    color = PitColors.Muted,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Switch(checked = limitedVocabulary, onCheckedChange = onLimitedVocabularyChange, enabled = !on)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Demander ma position à chaque tour")
                Text(
                    "GT7 ne transmet pas la position. Après « Ta position ? », réponds directement (« huitième »), sans mot de réveil.",
                    color = PitColors.Muted,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Switch(checked = askPosition, onCheckedChange = onAskPositionChange)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Reconnaissance Google pour les phrases")
                Text(
                    "Après « $wakeWord » seul, et pour répondre à une question de l'ingénieur : reconnaissance d'Android, " +
                        "bien plus précise en français. Dis « $wakeWord », attends « Je t'écoute », puis parle.",
                    color = PitColors.Muted,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Switch(checked = googleSpeech, onCheckedChange = onGoogleSpeechChange)
        }
        val (label, color) = when (listenState) {
            ListenState.OFF -> "Micro coupé." to PitColors.Muted
            ListenState.LOADING -> "Chargement du modèle…" to PitColors.Yellow
            ListenState.WAITING_WAKE_WORD -> "En attente de « $wakeWord »" to PitColors.Green
            ListenState.AWAKE -> "Je t'écoute…" to PitColors.Cyan
            ListenState.PAUSED -> "L'ingénieur parle (micro en pause)" to PitColors.Muted
            ListenState.ERROR -> "Erreur : ${error ?: "micro indisponible"}" to PitColors.Red
        }
        Text(label, color = color, fontWeight = FontWeight.Bold)
        if (heard.isNotBlank()) {
            Text("Entendu : « $heard »", color = PitColors.Muted, style = MaterialTheme.typography.bodySmall)
        }
        Text(
            "Exemples : « $wakeWord, combien d'essence ? » • « $wakeWord, je suis troisième » • " +
                "« $wakeWord, je suis dernier » • « $wakeWord, j'ai doublé » • « $wakeWord, j'ai perdu une place » • « $wakeWord, mes secteurs » • " +
                "« $wakeWord, silence » / « radio essentielle » / « tu peux parler » • « $wakeWord, répète » • " +
                "« $wakeWord, arrêt fait » / « annule l'arrêt » • « $wakeWord, j'ai mis les durs » • « $wakeWord, obligations » • « $wakeWord, détails »",
            color = PitColors.Muted,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
