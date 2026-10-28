package com.gt7pitwall.app.ui

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.platform.LocalContext
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
import com.gt7pitwall.app.profile.Gt7ProfileStore
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
import com.gt7pitwall.core.telemetry.PositionProbe
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
    cloudAi: com.gt7pitwall.app.ai.CloudAi,
    onAiMode: (com.gt7pitwall.core.ai.AiMode) -> Unit,
    discord: DiscordRadio,
    bench: SherpaBench,
    onDiscordMirror: (Boolean) -> Unit,
    onDiscordMic: (Boolean) -> Unit,
    onAiToggle: (Boolean) -> Unit,
    onGoogleOnHeadset: (Boolean) -> Unit,
    onDevTools: (Boolean) -> Unit,
    onRefreshSchedule: () -> Unit,
    freshness: kotlinx.coroutines.flow.StateFlow<com.gt7pitwall.core.sport.DataFreshness?>,
    onStart: (String) -> Unit,
    onStop: () -> Unit,
    onVoiceInputToggle: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    val gt7Profile = remember(context) { Gt7ProfileStore(context) }
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
    var conversationIdle by rememberSaveable { mutableStateOf(settings.conversationIdleS) }
    var detailedLaps by rememberSaveable { mutableStateOf(settings.detailedLapReports) }
    val conversationOpen by voiceInput.conversationOpen.collectAsStateWithLifecycle()
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
            conversationOpen = conversationOpen,
            conversationIdleS = conversationIdle,
            onConversationIdleChange = {
                conversationIdle = it
                settings.conversationIdleS = it
                voiceInput.conversationIdleMs = it * 1_000L
            },
            detailedLaps = detailedLaps,
            onDetailedLapsChange = {
                detailedLaps = it
                settings.detailedLapReports = it
                engine.detailedLapReports = it
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
        if (devTools) PositionProbeCard(engine)
        ExperimentalFieldsCard(engine)
        if (devTools) GapHunterCard(engine)
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
        CloudAiCard(cloudAi, onAiMode)
        HumanRadioCard(engine, settings)
        DrivingCard(engine)
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

    var selectedTab by rememberSaveable { mutableStateOf("Live") }
    if (selectedTab == "Historique") {
        HistoryScreen(engine, onBack = { selectedTab = "Live" })
        return
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("GT7 Pitwall", fontWeight = FontWeight.ExtraBold)
                        FreshnessLine(freshness, onRefreshSchedule)
                    }
                },
                actions = {
                    TextButton(onClick = { selectedTab = "Atelier" }) {
                        Text(if (running) "● PS5 active" else "○ Connecter PS5",
                            color = if (running) PitColors.Green else PitColors.Muted)
                    }
                    StatusPill(connection)
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background),
            )
        },
        bottomBar = {
            // Android 15+/Samsung Fold: the persistent taskbar can overlap a
            // custom Scaffold bottomBar when the Activity uses enableEdgeToEdge().
            // Include the system's ACTUAL safe-drawing bottom inset in the bar's
            // measured height, so Scaffold also reserves it for the dashboard.
            // Do not hard-code a gap: the taskbar size changes with posture/mode.
            Row(
                Modifier.fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))
                    .padding(horizontal = 6.dp, vertical = 7.dp),
                horizontalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                listOf("Live", "Briefing", "Analyse", "Historique", "Atelier").forEach { tab ->
                    val active = selectedTab == tab
                    val symbol = when (tab) {
                        "Live" -> "◉"
                        "Briefing" -> "▤"
                        "Analyse" -> "▥"
                        "Historique" -> "◷"
                        else -> "⚙"
                    }
                    Column(
                        Modifier.weight(1f)
                            .background(
                                if (active) PitColors.Orange.copy(alpha = 0.19f)
                                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.18f),
                                RoundedCornerShape(13.dp),
                            )
                            .clickable { selectedTab = tab }
                            .padding(vertical = 6.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(symbol, fontSize = 17.sp,
                            color = if (active) PitColors.Orange else PitColors.Muted)
                        Text(tab, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                            color = if (active) PitColors.Orange else MaterialTheme.colorScheme.onSurface)
                    }
                }
            }
        },
    ) { padding ->
        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .padding(padding)
                // Pass consumed Scaffold paddings to nested content: no
                // duplicate inset, and no final card hidden behind the taskbar.
                .consumeWindowInsets(padding),
        ) {
            val wide = maxWidth >= 600.dp
            when (selectedTab) {
                "Live" -> PitwallLiveDashboard(
                    state = state,
                    messages = radio,
                    onBriefing = { selectedTab = "Briefing" },
                    onAnalysis = { selectedTab = "Analyse" },
                    onAtelier = { selectedTab = "Atelier" },
                )
                "Briefing" -> ScrollColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
                    Text("PRÉPARE TA COURSE", fontSize = 22.sp, fontWeight = FontWeight.Bold,
                        color = PitColors.Orange)
                    Text("Comprends le règlement et choisis ton plan avant de prendre le départ.",
                        color = PitColors.Muted, style = MaterialTheme.typography.bodySmall)
                    Gt7CareerPanel(gt7Profile, editable = false, localCareer = state.localCareer)
                    RaceSetupCard(state, rules, engine.database, engine::setRules, engine::command,
                        engine::selectProfile, engine::updateProfile, engine::confirmRules,
                        onRefreshSchedule, engine::declareRules)
                    if (!running) connectionCard()
                }
                "Analyse" -> ScrollColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
                    Text("COMPRENDS TON PILOTAGE", fontSize = 22.sp,
                        fontWeight = FontWeight.Bold, color = PitColors.Orange)
                    Text("Tes points forts, ce que tu peux améliorer, et pourquoi.",
                        color = PitColors.Muted, style = MaterialTheme.typography.bodySmall)
                    DrivingCard(engine)
                    CornerReportCard(state)
                    LapCard(state)
                    TrackMapCard(state.snapshot.trackOutline, state.snapshot.packet, state.cornerMarks)
                    LapHistoryCard(state.snapshot.laps)
                    CarCard(state.snapshot.packet, state.gForce)
                    FuelCard(state.fuel, state.snapshot.packet)
                    TyreCard(state, rules)
                }
                else -> {
                    // All legacy tools and experiments stay accessible, but no longer
                    // steal space from the PSVR2 / no-HUD cockpit.
                    if (wide) {
                        Row(Modifier.fillMaxSize().padding(horizontal = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            ScrollColumn(Modifier.weight(1f)) {
                                Text("ATELIER & CONNEXION", fontSize = 20.sp,
                                    fontWeight = FontWeight.Bold, color = PitColors.Orange)
                                Gt7CareerPanel(gt7Profile, editable = true, localCareer = state.localCareer)
                                connectionCard()
                                DriveCard(state.snapshot.packet)
                                LapHistoryCard(state.snapshot.laps)
                                CarCard(state.snapshot.packet, state.gForce)
                            }
                            ScrollColumn(Modifier.weight(1f)) {
                                Text("OUTILS AVANCÉS", fontSize = 20.sp,
                                    fontWeight = FontWeight.Bold, color = PitColors.Orange)
                                RaceSetupCard(state, rules, engine.database, engine::setRules,
                                    engine::command, engine::selectProfile, engine::updateProfile,
                                    engine::confirmRules, onRefreshSchedule, engine::declareRules)
                                TrackMapCard(state.snapshot.trackOutline, state.snapshot.packet, state.cornerMarks)
                                FuelCard(state.fuel, state.snapshot.packet)
                                TyreCard(state, rules)
                                RadioCard(radio, engine::command)
                                voiceRadioCard()
                                BlackBoxMeasureCard(engine)
                                CarImageDiagnosticCard()
                                DatabaseCoverageCard(engine)
                            }
                        }
                    } else {
                        ScrollColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
                            Text("ATELIER & CONNEXION", fontSize = 20.sp,
                                fontWeight = FontWeight.Bold, color = PitColors.Orange)
                            Gt7CareerPanel(gt7Profile, editable = true, localCareer = state.localCareer)
                            connectionCard()
                            RaceSetupCard(state, rules, engine.database, engine::setRules,
                                engine::command, engine::selectProfile, engine::updateProfile,
                                engine::confirmRules, onRefreshSchedule, engine::declareRules)
                            DriveCard(state.snapshot.packet)
                            CarCard(state.snapshot.packet, state.gForce)
                            TrackMapCard(state.snapshot.trackOutline, state.snapshot.packet, state.cornerMarks)
                            FuelCard(state.fuel, state.snapshot.packet)
                            TyreCard(state, rules)
                            RadioCard(radio, engine::command)
                            voiceRadioCard()
                            LapHistoryCard(state.snapshot.laps)
                            BlackBoxMeasureCard(engine)
                            CarImageDiagnosticCard()
                            DatabaseCoverageCard(engine)
                        }
                    }
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
        if (status.unanswered.isNotEmpty()) {
            append("\nSans réponse lisible : format ${status.unanswered.joinToString(", ") { it.label }} ; format ${status.format.label} utilisé.")
        } else if (status.requested != status.format) {
            append("\nFormat ${status.requested.label} demandé, ${status.format.label} reçu (flux déjà ouvert par une autre app ?).")
        }
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
        val lapText = com.gt7pitwall.core.session.LapDisplay.of(p.currentLap, p.totalLaps, state.raceFinished).line
        // Type de session toujours visible : un entraînement n'est jamais pris pour une Course Sport officielle.
        Text(
            state.profile.id.label,
            style = MaterialTheme.typography.labelMedium,
            color = if (state.profile.id.mode == com.gt7pitwall.core.race.SessionMode.SPORT_OFFICIAL) PitColors.Cyan else PitColors.Yellow,
        )
        Row(verticalAlignment = Alignment.Bottom) {
            Text("Tour ", color = PitColors.Muted)
            Text(lapText, fontSize = 34.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            state.driverPosition?.let {
                Column(horizontalAlignment = Alignment.End) {
                    Text(if (state.positionMeasured) "POSITION GT7" else "POSITION*", style = MaterialTheme.typography.labelSmall, color = PitColors.Muted)
                    Text(
                        "P$it" + (state.positionCars?.takeIf { state.positionMeasured }?.let { "/$it" } ?: ""),
                        fontSize = 34.sp,
                        fontWeight = FontWeight.Bold,
                        color = PitColors.Cyan,
                    )
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
        if (state.driverPosition != null && !state.positionMeasured) {
            Text(
                "* d'après tes annonces radio (pas de position stable transmise par GT7 en ce moment)",
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

/**
 * Pneus & endurance : relais pneus (indépendant du carburant), températures mesurées, état de l'adhérence, tendance du
 * rythme, estimation de durée seulement si l'historique la justifie, et prochain changement conseillé avec sa raison.
 * GT7 ne transmet pas l'usure : aucun pourcentage n'est affiché.
 */
@Composable
private fun TyreCard(state: PitwallUiState, rules: com.gt7pitwall.core.strategy.RaceRules) {
    val st = state.tyreStint
    val g = state.grip
    PitCard("Pneus & endurance") {
        Text(
            buildString {
                append(st?.compound?.spoken ?: "Gomme non déclarée")
                st?.let { append(" • ${it.age} tour${if (it.age > 1) "s" else ""}") }
                if (st != null && !st.certain) append(" • âge incertain")
            },
            fontWeight = FontWeight.Bold,
        )
        state.snapshot.packet?.tyreTemps?.let { t ->
            Text(
                "Températures (mesurées) : AV ${t.fl.roundToInt()} / ${t.fr.roundToInt()}  •  AR ${t.rl.roundToInt()} / ${t.rr.roundToInt()} °C",
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        val color = when (g.level) {
            com.gt7pitwall.core.tyres.GripLevel.INSUFFICIENT -> PitColors.Muted
            com.gt7pitwall.core.tyres.GripLevel.STABLE -> PitColors.Green
            com.gt7pitwall.core.tyres.GripLevel.POSSIBLE -> PitColors.Yellow
            else -> PitColors.Red
        }
        Text("Adhérence : ${g.level.label}" + if (g.level == com.gt7pitwall.core.tyres.GripLevel.INSUFFICIENT) " (${g.cleanLaps} tours propres sur ${com.gt7pitwall.core.tyres.GripDiagnosis.MIN_LAPS})" else "", color = color)
        if (g.areas.isNotEmpty()) DiagLine("Zone", g.areas.joinToString(", ") { it.label })
        if (g.evidence.isNotEmpty()) DiagLine("Mesuré", g.evidence.joinToString(", "))
        g.slopeMsPerLap?.let { DiagLine("Rythme", "dégradation de ${com.gt7pitwall.core.tyres.GripDiagnosis.seconds(it)} par tour (mesuré)") }
        if (g.declared.isNotEmpty()) DiagLine("Déclaré", g.declared.joinToString(", "))
        if (g.alternatives.isNotEmpty()) DiagLine("Autres causes possibles", g.alternatives.joinToString(", "))
        // Durée restante : seulement si elle est justifiée (déclarée par le pilote, ou apprise sur des relais comparables).
        val m = state.tyreModel
        val life = m.performantLaps?.takeIf { it.confidence != com.gt7pitwall.core.strategy.Confidence.INSUFFICIENT }
        when {
            rules.tyreStintLaps != null -> DiagLine("Durée de vie", "${rules.tyreStintLaps} tours (déclaré)")
            life != null -> DiagLine("Durée estimée", "perte nette vers ${life.mean.roundToInt()} tours d'âge (${life.confidence.label} : ${m.basis()})")
            else -> DiagLine("Durée estimée", "non justifiable : pas assez de relais comparables")
        }
        m.slopeMsPerLap?.takeIf { it.confidence != com.gt7pitwall.core.strategy.Confidence.INSUFFICIENT }?.let {
            DiagLine("Historique", "${com.gt7pitwall.core.tyres.GripDiagnosis.seconds(it.mean)} par tour d'âge (${it.confidence.label}, ${m.basis()})")
        }
        state.lastThermal?.let { DiagLine("Dernier arrêt (températures)", "${it.label} — indice expérimental, seul ton « oui » remet l'âge à zéro") }
        state.tyreScenario?.let { DiagLine("Scénarios", it) }
        val lap = (state.snapshot.packet?.currentLap ?: 1).coerceAtLeast(1)
        val next = state.plan?.stops?.firstOrNull { it.changeTyres && it.endOfLap >= lap }
        Text(
            next?.let { "Prochain changement conseillé : fin du tour ${it.endOfLap} (${it.reasons.joinToString(", ")})" } ?: "Aucun changement de pneus prévu",
            style = MaterialTheme.typography.bodySmall,
            color = if (next != null) PitColors.Yellow else PitColors.Muted,
        )
        state.strategy?.takeIf { it.recommended in com.gt7pitwall.core.strategy.StrategyOptions.TYRE_KINDS || "pneus" in it.reason }?.let {
            Text(it.reason, style = MaterialTheme.typography.bodySmall)
        }
        Text("GT7 ne transmet pas l'usure : Pitwall juge sur tes chronos, la glisse et tes déclarations.", color = PitColors.Muted, style = MaterialTheme.typography.bodySmall)
    }
}

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
            // Unités internes de GT7 (pas des litres démontrés) : affichées pour information seulement.
            Text("du réservoir (GT7 : ${f1(fuel.fuelLevel)} / ${f1(fuel.fuelCapacity)})", color = PitColors.Muted, modifier = Modifier.padding(bottom = 6.dp))
        }
        Bar(fraction, if (fraction < 0.15f) PitColors.Red else PitColors.Cyan)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Metric("Conso / tour", fuel.perLap?.let { com.gt7pitwall.core.strategy.FuelUnits.short(it, fuel.fuelCapacity) } ?: "—", Modifier.weight(1f))
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
        AdviceBox(fuel.advice, fuel.fuelCapacity)
    }
}

@Composable
private fun AdviceBox(advice: FuelAdvice, capacity: Float) {
    fun pct(units: Float) = com.gt7pitwall.core.strategy.FuelUnits.short(units, capacity)
    val (text, color) = when (advice) {
        FuelAdvice.Measuring -> "Mesure en cours : il faut un tour complet observé depuis la ligne." to PitColors.Muted
        FuelAdvice.NoConsumption -> "Le niveau ne baisse pas : pas de consommation sur cette épreuve." to PitColors.Green
        FuelAdvice.NotAvailable -> "Pas de données de réservoir." to PitColors.Muted
        FuelAdvice.RaceFinished -> "Course terminée." to PitColors.Muted
        is FuelAdvice.RangeOnly ->
            "Distance de course inconnue (course au temps ou essais) : autonomie ${f1(advice.lapsOfFuel)} tours." to PitColors.Cyan
        is FuelAdvice.Comfortable -> "OK pour finir • marge ${f1(advice.marginLaps)} tour(s)" to PitColors.Green
        is FuelAdvice.Tight -> "JUSTE • vise ${pct(advice.targetPerLap)} du réservoir par tour" to PitColors.Yellow
        is FuelAdvice.PitRequired -> buildString {
            append("RAVITAILLER • au plus tard fin du tour ${advice.latestPitLap} • ajouter ~${pct(advice.fuelToAdd)} du réservoir")
            if (advice.stopsNeeded > 1) append(" • ${advice.stopsNeeded} arrêts")
            if (advice.savingNeeded in 0f..0.10f) {
                append("\nOu économiser ${(advice.savingNeeded * 100).roundToInt()} % (cible ${pct(advice.targetPerLap)} / tour)")
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
    val watch by voiceInput.diagnostic.collectAsStateWithLifecycle()
    PitCard("Diagnostic radio") {
        DiagLine("Écoute", listen.name.lowercase().replace('_', ' '))
        DiagLine("Micro écouté", mic ?: "aucun")
        watch?.let { w ->
            fun s(ms: Long?) = ms?.let { if (it < 1_000) "à l'instant" else "il y a ${it / 1000} s" } ?: "jamais"
            val stalled = w.state != ListenState.OFF && !w.micLentToGoogle && (w.lastBlockAgoMs ?: Long.MAX_VALUE) > 3_000
            DiagLine(
                "Blocs audio reçus",
                "${"%.1f".format(w.blocksPerSecond)}/s (attendu ≈ 4), dernier ${s(w.lastBlockAgoMs)}, niveau ${w.levelPct} %",
                if (stalled) PitColors.Red else PitColors.Muted,
            )
            if (w.mic?.contains("Fold") == true) {
                DiagLine("Son non nul (Fold)", s(w.lastSoundAgoMs), if ((w.lastSoundAgoMs ?: Long.MAX_VALUE) > 20_000) PitColors.Red else PitColors.Muted)
            }
            if (w.mic?.contains("Discord") == true) DiagLine("Voix du pilote reçue du salon", s(w.remoteVoiceAgoMs))
            w.pausedForMs?.let { DiagLine("Micro en pause (ingénieur parle)", "depuis ${it / 1000} s", if (it > 30_000) PitColors.Red else PitColors.Muted) }
            if (w.micLentToGoogle) DiagLine("Micro", "prêté à Google pour une phrase", PitColors.Yellow)
            DiagLine("Dernier « radio » reconnu", s(w.lastWakeAgoMs))
            DiagLine("Reprises automatiques", "${w.restarts}" + (w.lastAction?.let { " — $it" } ?: ""), if (w.restarts > 0) PitColors.Yellow else PitColors.Muted)
        }
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

/**
 * Diagnostic des champs 0x84 / 0x86 : valeurs brutes, état, proportions, journal exportable. Rien n'est annoncé
 * à la radio ni utilisé par la stratégie : il sert à comparer avec la position affichée par le HUD de GT7.
 */
@Composable
private fun PositionProbeCard(engine: PitwallEngine) {
    val s by engine.probe.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var hud by rememberSaveable { mutableStateOf("") }
    fun mark(label: String) {
        engine.positionProbe.mark(label, System.currentTimeMillis())
        engine.refreshProbe()
    }
    PitCard("Diagnostic position UDP (0x84 / 0x86)") {
        Text(
            "Valeurs brutes envoyées par GT7, à comparer avec la position du HUD. Aucune position n'est annoncée ni " +
                "utilisée par la stratégie. Les écarts avec les adversaires ne sont dans aucun paquet connu.",
            style = MaterialTheme.typography.bodySmall,
            color = PitColors.Muted,
        )
        DiagLine("0x84 (position ?)", s.raw84?.toString() ?: "—", PitColors.Green)
        DiagLine("0x86 (voitures ?)", s.raw86?.toString() ?: "—", PitColors.Green)
        DiagLine("Tour / état", "${s.lap ?: "—"} / ${s.state ?: "aucun paquet"}")
        DiagLine("Paquets", s.packets.toString())
        fun shares(c: PositionProbe.Counts) = "${c.share(c.valid)} % positifs, ${c.share(c.minusOne)} % à -1, ${c.share(c.other)} % autres"
        DiagLine("0x84", shares(s.counts84))
        DiagLine("0x86", shares(s.counts86))
        if (s.dropped > 0) DiagLine("Lignes anciennes retirées", s.dropped.toString(), PitColors.Yellow)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { mark("dépassement fait") }, modifier = Modifier.weight(1f)) { Text("J'ai doublé") }
            OutlinedButton(onClick = { mark("je me suis fait doubler") }, modifier = Modifier.weight(1f)) { Text("Doublé") }
            OutlinedButton(onClick = { mark("arrivée") }, modifier = Modifier.weight(1f)) { Text("Arrivée") }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = hud,
                onValueChange = { v -> hud = v.filter { it.isDigit() }.take(2) },
                label = { Text("Position HUD") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                modifier = Modifier.weight(1f),
            )
            Button(onClick = { mark("HUD P$hud"); hud = "" }, enabled = hud.isNotEmpty()) { Text("Noter") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_SUBJECT, "GT7 Pitwall — diagnostic position UDP")
                        putExtra(Intent.EXTRA_TEXT, engine.positionProbe.export())
                    }
                    context.startActivity(Intent.createChooser(send, "Exporter le journal"))
                },
                modifier = Modifier.weight(1f),
            ) { Text("Exporter") }
            OutlinedButton(onClick = { engine.positionProbe.reset(); engine.refreshProbe() }, modifier = Modifier.weight(1f)) {
                Text("Réinitialiser")
            }
        }
        if (s.events.isNotEmpty()) {
            Text("Derniers changements (le plus récent en haut)", style = MaterialTheme.typography.labelMedium, color = PitColors.Muted)
            val fmt = remember { java.text.SimpleDateFormat("HH:mm:ss", Locale.FRANCE) }
            s.events.asReversed().forEach { e ->
                Text(
                    "${fmt.format(java.util.Date(e.timeMs))}  tour ${e.lap}  0x84=${e.raw84}  0x86=${e.raw86}  ${e.state} — ${e.reason}",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = PitColors.Muted,
                )
            }
        }
    }
}

/**
 * Radio humaine : interruption à la voix (selon le matériel), dernière mesure de latence, et mémoire durable de
 * l'ingénieur — chaque souvenir se consulte, se supprime ; la mémoire se désactive ou s'efface entièrement.
 */
@Composable
private fun HumanRadioCard(engine: PitwallEngine, settings: com.gt7pitwall.app.settings.SettingsStore) {
    val memory by engine.pilotMemory.collectAsStateWithLifecycle()
    val memoryOn by engine.pilotMemoryEnabled.collectAsStateWithLifecycle()
    val latency by engine.latency.collectAsStateWithLifecycle()
    var bargeIn by rememberSaveable { mutableStateOf(settings.bargeIn) }
    androidx.compose.runtime.LaunchedEffect(Unit) { engine.refreshPilotMemory() }
    PitCard("Radio humaine") {
        SwitchLine(
            "Interruption à la voix",
            "Dis « attends » ou « stop » pendant qu'il parle. Casque PS5 (Discord) ou écouteurs : à tout moment. " +
                "Haut-parleur du Fold : entre ses phrases seulement. Une alerte critique n'est jamais coupée.",
            bargeIn,
        ) {
            bargeIn = it
            settings.bargeIn = it
        }
        latency?.let { DiagLine("Dernière latence", it) }
        SwitchLine(
            "Mémoire de l'ingénieur",
            "Seulement ce que tu lui demandes de retenir (« retiens que… ») et que tu confirmes. Aucun audio n'est gardé.",
            memoryOn,
        ) { engine.setPilotMemoryEnabled(it) }
        if (memory.isEmpty()) {
            Text("Rien de retenu pour l'instant.", style = MaterialTheme.typography.bodySmall, color = PitColors.Muted)
        } else {
            memory.forEach { e ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(e.text, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { engine.forgetPilotMemory(e.id) }) { Text("Oublier") }
                }
            }
            OutlinedButton(onClick = { engine.clearPilotMemory() }, modifier = Modifier.fillMaxWidth()) { Text("Tout effacer") }
        }
    }
}

/** Pilotage : bilan de la dernière course et ce que Pitwall a réellement appris (mesures vérifiables, pas l'IA). */
@Composable
private fun DrivingCard(engine: PitwallEngine) {
    val state by engine.state.collectAsStateWithLifecycle()
    PitCard("Pilotage") {
        if (state.drivingReport.isNotEmpty()) {
            Text("Bilan de la dernière course", style = MaterialTheme.typography.bodyMedium)
            state.drivingReport.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
        } else {
            Text("Aucun bilan : il est produit à l'arrivée d'une course.", style = MaterialTheme.typography.bodySmall, color = PitColors.Muted)
        }
        Text("Ce que Pitwall a appris", style = MaterialTheme.typography.bodyMedium)
        state.learning.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
    }
}

/** Bilan par virage (Analyse) : passages comparables, perte, cause mesurée et confiance. Le détail reste ici, pas à la radio. */
@Composable
private fun CornerReportCard(state: PitwallUiState) {
    PitCard("Virages") {
        if (state.cornerReport.isEmpty()) {
            Text("Le bilan par virage apparaît après deux tours propres complets (départ, stands et tours perturbés écartés).",
                style = MaterialTheme.typography.bodySmall, color = PitColors.Muted)
        } else {
            state.cornerReport.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
            Text(state.cornerNumbering, style = MaterialTheme.typography.bodySmall, color = PitColors.Muted)
        }
        if (state.tyreOrDriving.isNotEmpty()) {
            Text("Pneus ou pilotage ? ${state.tyreOrDriving}", style = MaterialTheme.typography.bodySmall)
            Text("Comparaison début/fin du relais sur les mêmes virages. GT7 ne transmet pas l'usure : aucun pourcentage.",
                style = MaterialTheme.typography.bodySmall, color = PitColors.Muted)
        }
        if (state.traction.isNotEmpty()) {
            Text("Motricité en sortie : ${state.traction}", style = MaterialTheme.typography.bodySmall)
            Text("Même virage, même tranche d'accélérateur, rapport et vitesse ; vibreurs exclus (format C). Mesure de motricité, pas d'usure en %.",
                style = MaterialTheme.typography.bodySmall, color = PitColors.Muted)
        }
        state.handling.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
    }
}

/**
 * Champs GT7 expérimentaux (0x154, 0x140, 0x94) : ce que la PS5 envoie réellement, comparé aux affirmations de la
 * communauté. Rien n'est utilisé pour décider tant que ce n'est pas validé.
 */
@Composable
private fun ExperimentalFieldsCard(engine: PitwallEngine) {
    var lines by remember { mutableStateOf(engine.torqueProbe.report()) }
    PitCard("Champs GT7 expérimentaux") {
        Text("Couple moteur (0x154) et autres champs non documentés : observés pour validation, jamais utilisés pour décider.",
            style = MaterialTheme.typography.bodySmall, color = PitColors.Muted)
        lines.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
        Text("Formats reçus par épreuve (journal automatique) :", style = MaterialTheme.typography.labelMedium, color = PitColors.Muted)
        var formats by remember { mutableStateOf(engine.formatLog.summary()) }
        formats.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
        val context = LocalContext.current
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { lines = engine.torqueProbe.report(); formats = engine.formatLog.summary() }) { Text("Actualiser") }
            OutlinedButton(onClick = {
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, "GT7 Pitwall — formats de télémétrie")
                    putExtra(Intent.EXTRA_TEXT, engine.formatLog.export() + "\n" + engine.torqueProbe.report().joinToString("\n"))
                }
                context.startActivity(Intent.createChooser(send, "Exporter"))
            }) { Text("Exporter") }
            OutlinedButton(onClick = { engine.torqueProbe.reset(); lines = engine.torqueProbe.report() }) { Text("Remettre à zéro") }
        }
    }
}

/** Couverture de la base GT7 (Atelier) : voitures, circuits, géométrie, dernière mise à jour connue et sources. */
@Composable
private fun DatabaseCoverageCard(engine: PitwallEngine) {
    val lines = remember(engine.database.cars.size, engine.database.courses.size) {
        runCatching { com.gt7pitwall.core.knowledge.DatabaseCoverage.lines(engine.database) }.getOrElse { listOf("Couverture indisponible.") }
    }
    PitCard("Base GT7 : couverture") {
        lines.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall, color = PitColors.Muted) }
    }
}

/** Diagnostic de la photo officielle de la voiture (Atelier) : pourquoi la silhouette est affichée, le cas échéant. */
@Composable
private fun CarImageDiagnosticCard() {
    val status by CarImageLoader.status.collectAsStateWithLifecycle()
    val request by CarImageLoader.request.collectAsStateWithLifecycle()
    val context = LocalContext.current
    PitCard("Photo de la voiture") {
        Text(status.text, style = MaterialTheme.typography.bodySmall,
            color = if (status is com.gt7pitwall.core.knowledge.CarImagePolicy.Outcome.Shown) PitColors.Green else PitColors.Muted)
        request?.let { Text("Dernière requête : $it", style = MaterialTheme.typography.bodySmall, color = PitColors.Muted) }
        Text("Vignette du catalogue officiel GT7 (images Sony / Polyphony, une seule voiture, en cache sur le téléphone).",
            style = MaterialTheme.typography.bodySmall, color = PitColors.Muted)
        OutlinedButton(onClick = { CarImageLoader.retryNow(context) }) { Text("Réessayer la photo maintenant") }
    }
}

/**
 * Mesures préalables à la Black Box, sur la Fold, pendant une vraie course : débit et pertes de paquets, temps de
 * calcul, taille de trace compressée, batterie. Rien n'est enregistré.
 */
@Composable
private fun BlackBoxMeasureCard(engine: PitwallEngine) {
    val report by engine.blackBoxReport.collectAsStateWithLifecycle()
    var on by remember { mutableStateOf(engine.blackBox.running) }
    PitCard("Mesure Black Box") {
        SwitchLine(
            "Mesurer pendant la prochaine course",
            "Taille, calcul et batterie, sans rien enregistrer. Au moins 10 minutes pour la batterie.",
            on,
        ) {
            on = it
            engine.setBlackBoxMeasure(it)
        }
        report.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall, color = PitColors.Muted) }
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
    conversationOpen: Boolean = false,
    conversationIdleS: Int = 8,
    onConversationIdleChange: (Int) -> Unit = {},
    detailedLaps: Boolean = false,
    onDetailedLapsChange: (Boolean) -> Unit = {},
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
                Text("Messages de tour détaillés")
                Text(
                    "Coupé : temps, écart avec ton meilleur et position seulement (le carburant s'il devient juste). " +
                        "Le détail reste disponible avec « détails ».",
                    color = PitColors.Muted,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Switch(checked = detailedLaps, onCheckedChange = onDetailedLapsChange)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Reconnaissance Google pour les phrases")
                Text(
                    "Après « $wakeWord » seul, et pour répondre à une question de l'ingénieur : reconnaissance d'Android, " +
                        "bien plus précise en français. Dis « $wakeWord », attends le bip, puis parle.",
                    color = PitColors.Muted,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Switch(checked = googleSpeech, onCheckedChange = onGoogleSpeechChange)
        }
        // Conversation continue : « radio » une fois, puis questions successives sans mot de réveil.
        Column {
            Text("Conversation continue", style = MaterialTheme.typography.bodyMedium)
            Text(
                "Dis « $wakeWord » une fois : bip court, puis pose tes questions à la suite. Fin avec « terminé » ou après " +
                    "$conversationIdleS s de silence (double bip). Sans « $wakeWord » devant, un arrêt ou un changement de pneus " +
                    "est toujours confirmé avant d'être noté.",
                color = PitColors.Muted,
                style = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(5, 8, 12, 20).forEach { sec ->
                    if (sec == conversationIdleS) {
                        Button(onClick = {}, modifier = Modifier.weight(1f)) { Text("$sec s") }
                    } else {
                        OutlinedButton(onClick = { onConversationIdleChange(sec) }, modifier = Modifier.weight(1f)) { Text("$sec s") }
                    }
                }
            }
        }
        val (label, color) = when (listenState) {
            ListenState.OFF -> "Micro coupé." to PitColors.Muted
            ListenState.LOADING -> "Chargement du modèle…" to PitColors.Yellow
            ListenState.WAITING_WAKE_WORD -> "En attente de « $wakeWord »" to PitColors.Green
            ListenState.AWAKE -> (if (conversationOpen) "Conversation ouverte, à l'écoute" else "À l'écoute") to PitColors.Cyan
            ListenState.PAUSED -> (if (conversationOpen) "L'ingénieur répond (conversation ouverte)" else "L'ingénieur parle (micro en pause)") to PitColors.Muted
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

/**
 * Gap Hunter : exploration expérimentale des octets de la télémétrie, à la recherche d'un champ d'écart. Silencieux :
 * rien n'est annoncé ni utilisé par la stratégie. Une piste affichée est une corrélation, jamais une preuve.
 */
@Composable
private fun GapHunterCard(engine: PitwallEngine) {
    val s by engine.gap.collectAsStateWithLifecycle()
    val scan by engine.formatScan.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var note by rememberSaveable { mutableStateOf("") }
    fun mark(label: String) {
        engine.gapHunter.mark(label, System.currentTimeMillis())
        engine.refreshGap()
    }
    PitCard("Gap Hunter (expérimental)") {
        Text(
            "Observe tous les octets reçus de GT7 pour chercher un champ d'écart. Aucune source connue ne donne les " +
                "écarts : une piste ici n'est qu'une corrélation à vérifier. Rien n'est dit à la radio.",
            style = MaterialTheme.typography.bodySmall,
            color = PitColors.Muted,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Exploration active", modifier = Modifier.weight(1f))
            Switch(checked = s.enabled, onCheckedChange = { engine.setGapHunter(it) })
        }
        DiagLine("Paquets lisibles / illisibles", "${s.frames} / ${s.invalid}")
        if (s.bySize.isNotEmpty()) DiagLine("Tailles", s.bySize.entries.joinToString { "${it.key} o" })
        if (s.invalidBySize.isNotEmpty()) {
            DiagLine("Tailles illisibles", s.invalidBySize.entries.joinToString { "${it.key} o × ${it.value}" }, PitColors.Yellow)
        }
        s.events.forEach { (kind, n) ->
            val verdict = s.verdicts[kind]
            DiagLine(
                "${kind.label} ($n)",
                verdict?.label ?: "—",
                if (verdict == com.gt7pitwall.core.telemetry.GapHunter.Verdict.CANDIDATE) PitColors.Yellow else PitColors.Muted,
            )
        }
        if (s.candidates.isNotEmpty()) {
            s.candidates.forEach { c ->
                DiagLine(
                    "Piste 0x%03X".format(c.field.offset),
                    "${c.field.label} — ${c.kind.label} ${c.eventsWithChange}/${c.events} (à vérifier)",
                    PitColors.Yellow,
                )
            }
        }
        s.hudMarks.takeLast(3).forEach { m ->
            DiagLine("« ${m.label} »", m.coincidences.ifEmpty { listOf("aucun mot proche") }.joinToString(), PitColors.Muted)
        }
        DiagLine("Journal", "${s.lines} lignes" + if (s.dropped > 0) ", ${s.dropped} retirées" else "")
        if (!s.enabled) {
            Text("Active l'exploration pour poser des repères.", style = MaterialTheme.typography.bodySmall, color = PitColors.Muted)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { mark("dépassement fait") }, enabled = s.enabled, modifier = Modifier.weight(1f)) { Text("J'ai doublé") }
            OutlinedButton(onClick = { mark("je me suis fait doubler") }, enabled = s.enabled, modifier = Modifier.weight(1f)) { Text("Doublé") }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = note,
                onValueChange = { v -> note = v.take(40) },
                label = { Text("Écart lu sur le HUD (ex. devant 1,2)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                modifier = Modifier.weight(1f),
            )
            Button(onClick = { mark("HUD $note"); note = "" }, enabled = s.enabled && note.isNotBlank()) { Text("Noter") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { shareExport(context, engine.gapHunter.export()) }, modifier = Modifier.weight(1f)) { Text("Exporter") }
            OutlinedButton(onClick = { engine.gapHunter.reset(); engine.refreshGap() }, modifier = Modifier.weight(1f)) {
                Text("Réinitialiser")
            }
        }

        Text("Sonde des formats de heartbeat", style = MaterialTheme.typography.labelMedium, color = PitColors.Muted)
        Text(
            "Essaie chaque lettre A à Z et « ~ » (environ 15 s chacune) pour voir si GT7 répond avec un autre format. " +
                "La télémétrie est coupée pendant la sonde : à lancer dans les menus, jamais en course.",
            style = MaterialTheme.typography.bodySmall,
            color = PitColors.Muted,
        )
        if (scan.running) {
            DiagLine("En cours", "${scan.done}/${scan.total}" + (scan.current?.let { " — lettre « $it »" } ?: " — attente du silence"))
            OutlinedButton(onClick = { engine.cancelFormatScan() }) { Text("Arrêter la sonde") }
        } else {
            OutlinedButton(onClick = { engine.startFormatScan() }) { Text("Lancer la sonde") }
        }
        scan.results.filter { it.packets > 0 }.forEach { r ->
            DiagLine(
                "« ${r.heartbeat} »",
                r.sizes.entries.joinToString { "${it.key} o" } + " — " +
                    (if (r.keys.isEmpty()) "chiffrement inconnu" else "clé ${r.keys.joinToString("/") { it.label }}") +
                    if (r.previousStreamStillOn) " (flux précédent encore actif)" else "",
                if (r.sizes.keys.any { it != 296 && it != 316 && it != 344 && it != 368 }) PitColors.Green else PitColors.Muted,
            )
        }
        if (!scan.running && scan.results.isNotEmpty()) {
            DiagLine("Sans réponse", scan.results.filter { it.packets == 0 }.joinToString(" ") { it.heartbeat.toString() }.ifEmpty { "—" })
        }
    }
}

/** Partage un export texte sous forme de fichier (le texte peut dépasser la taille admise par un Intent). */
internal fun shareExport(
    context: android.content.Context,
    text: String,
    fileName: String = "gt7-pitwall-gap-hunter.txt",
    subject: String = "GT7 Pitwall — Gap Hunter",
    mime: String = "text/plain",
) {
    runCatching {
        val dir = java.io.File(context.cacheDir, "exports").apply { mkdirs() }
        val file = java.io.File(dir, fileName).apply { writeText(text) }
        val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.exports", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, subject))
    }
}

/**
 * Réglage IA, volontairement minimal : modèle choisi, état de la connexion, délai, requêtes réellement envoyées,
 * dernière erreur et retour au moteur local. Configuration Firebase saisie ici, gardée chiffrée sur le téléphone.
 */
@Composable
private fun CloudAiCard(cloud: com.gt7pitwall.app.ai.CloudAi, onMode: (com.gt7pitwall.core.ai.AiMode) -> Unit) {
    val status by cloud.status.collectAsStateWithLifecycle()
    val trace by cloud.trace.collectAsStateWithLifecycle()
    val tick by cloud.tick.collectAsStateWithLifecycle()
    val test by cloud.testResult.collectAsStateWithLifecycle()
    var mode by rememberSaveable { mutableStateOf(cloud.mode) }
    var showConfig by rememberSaveable { mutableStateOf(!cloud.store.configured) }
    var projectId by rememberSaveable { mutableStateOf(cloud.store.projectId) }
    var appId by rememberSaveable { mutableStateOf(cloud.store.appId) }
    var apiKey by remember { mutableStateOf("") }
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    val context = LocalContext.current
    var configMessage by remember { mutableStateOf<String?>(null) }
    var keyHint by remember { mutableStateOf(cloud.savedKeyHint()) }
    // Import du fichier google-services.json téléchargé depuis la console Firebase (aucune saisie à la main).
    val importer = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val text = runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) } }.getOrNull()
        configMessage = if (text == null) "Impossible de lire ce fichier." else cloud.importGoogleServices(text)
        projectId = cloud.store.projectId
        appId = cloud.store.appId
        keyHint = cloud.savedKeyHint()
    }
    PitCard("IA de l'ingénieur") {
        com.gt7pitwall.core.ai.AiMode.entries.forEach { m ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = mode == m, onClick = { mode = m; onMode(m) })
                Text(m.label, style = MaterialTheme.typography.bodyMedium)
            }
        }
        val (label, color) = when (status.link) {
            com.gt7pitwall.app.ai.CloudAi.Link.NOT_CONFIGURED -> "Cloud : non configuré (moteur local et Gemini Nano seulement)" to PitColors.Muted
            com.gt7pitwall.app.ai.CloudAi.Link.CONFIGURED -> "Cloud : configuré, pas encore vérifié" to PitColors.Yellow
            com.gt7pitwall.app.ai.CloudAi.Link.CONNECTED -> "Cloud : connecté" to PitColors.Green
            com.gt7pitwall.app.ai.CloudAi.Link.ERROR -> "Cloud : erreur" to PitColors.Red
        }
        Text(label, color = color, fontWeight = FontWeight.Bold)
        status.detail?.let { Text(it, color = PitColors.Muted, style = MaterialTheme.typography.bodySmall) }
        // Relu à chaque requête (tick) : compteurs réels, jamais un quota supposé.
        tick.let {
            listOf(cloud.lite, cloud.flash).forEach { m ->
                val st = cloud.usage.stats(m.backend)
                Text(
                    "${m.backend.label} (${m.modelId}) : ${st.requestsToday} requête${if (st.requestsToday > 1) "s" else ""} aujourd'hui " +
                        "(${st.successesToday} réussie${if (st.successesToday > 1) "s" else ""}, ${st.failuresToday} échec${if (st.failuresToday > 1) "s" else ""})" +
                        (st.lastLatencyMs?.let { " · dernier délai $it ms" } ?: "") +
                        (cloud.usage.blockedReason(m.backend)?.let { " · $it" } ?: "") +
                        (st.lastError?.let { " · dernière erreur : ${it.kind.label}" } ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        trace?.let { t ->
            Text(
                "Dernière question : « ${t.question.take(80)} » → " +
                    (if (t.fallback) "moteur local" else (t.modelId ?: t.backend.label)) + " en ${t.latencyMs} ms : ${t.verdict}",
                color = if (t.fallback) PitColors.Yellow else PitColors.Muted,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { cloud.test(com.gt7pitwall.core.ai.AiBackend.FLASH_LITE) }, modifier = Modifier.weight(1f)) { Text("Tester Flash-Lite") }
            OutlinedButton(onClick = { cloud.test(com.gt7pitwall.core.ai.AiBackend.FLASH) }, modifier = Modifier.weight(1f)) { Text("Tester Flash 3.8") }
        }
        Text("Chaque test envoie exactement une requête.", color = PitColors.Muted, style = MaterialTheme.typography.bodySmall)
        test?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        TextButton(onClick = { showConfig = !showConfig }) { Text(if (showConfig) "Masquer la configuration Firebase" else "Configurer Firebase") }
        if (showConfig) {
            Text(
                "Projet Firebase sans facturation (plan Spark) : Paramètres du projet → Vos applications → application Android " +
                    "com.gt7pitwall.app. Ces valeurs restent sur le téléphone ; la clé est chiffrée.",
                color = PitColors.Muted,
                style = MaterialTheme.typography.bodySmall,
            )
            Button(onClick = { importer.launch(arrayOf("application/json", "text/plain", "application/octet-stream", "*/*")) }, modifier = Modifier.fillMaxWidth()) {
                Text("Importer google-services.json")
            }
            Text(
                "Le plus simple : console Firebase → Paramètres du projet → Vos applications → application Android → " +
                    "« google-services.json », puis choisis ce fichier ici. Ou saisis les trois valeurs à la main :",
                color = PitColors.Muted,
                style = MaterialTheme.typography.bodySmall,
            )
            configMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold) }
            keyHint?.let { Text("Clé enregistrée : $it", color = PitColors.Muted, style = MaterialTheme.typography.bodySmall) }
            OutlinedTextField(projectId, { projectId = it }, singleLine = true, label = { Text("ID du projet") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(appId, { appId = it }, singleLine = true, label = { Text("ID de l'application (1:…:android:…)") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(
                apiKey, { apiKey = it }, singleLine = true,
                label = { Text(if (cloud.store.hasApiKey) "Clé API Web (enregistrée, laisser vide pour la garder)" else "Clé API Web") },
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        configMessage = cloud.saveConfig(projectId, appId, apiKey) ?: "Enregistré. Touche « Tester Flash-Lite »."
                        apiKey = ""
                        keyHint = cloud.savedKeyHint()
                    },
                    modifier = Modifier.weight(1f),
                ) { Text("Enregistrer") }
                OutlinedButton(onClick = { cloud.clearConfig(); projectId = ""; appId = ""; keyHint = null; configMessage = null }, modifier = Modifier.weight(1f)) { Text("Effacer") }
            }
            Text("App Check : ${status.appCheckMode}", style = MaterialTheme.typography.bodySmall)
            status.debugSecret?.let { secret ->
                Text(
                    "Jeton de débogage de CE téléphone, à ajouter dans la console Firebase (App Check → Applications → ⋮ → " +
                        "Gérer les jetons de débogage). Ne le partage pas.",
                    color = PitColors.Muted,
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(secret, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { clipboard.setText(androidx.compose.ui.text.AnnotatedString(secret)) }, modifier = Modifier.weight(1f)) { Text("Copier le jeton") }
                    OutlinedButton(onClick = { cloud.renewDebugSecret() }, modifier = Modifier.weight(1f)) { Text("Nouveau jeton") }
                }
            }
        }
    }
}

/**
 * Indicateur discret de fraîcheur des données sous le titre (« Sport 26/10 · Base 3 h ») ; un appui ouvre le détail
 * et permet une actualisation immédiate. Couleur : vert à jour, jaune en attente, rouge introuvable.
 */
@Composable
private fun FreshnessLine(
    freshness: kotlinx.coroutines.flow.StateFlow<com.gt7pitwall.core.sport.DataFreshness?>,
    onRefresh: () -> Unit,
) {
    val f by freshness.collectAsStateWithLifecycle()
    val value = f ?: return
    var open by remember { mutableStateOf(false) }
    val color = when (value.level) {
        com.gt7pitwall.core.sport.DataFreshness.Level.OK -> PitColors.Green
        com.gt7pitwall.core.sport.DataFreshness.Level.WAITING -> PitColors.Yellow
        com.gt7pitwall.core.sport.DataFreshness.Level.PROBLEM -> PitColors.Red
    }
    Text(
        "● ${value.text}",
        color = color,
        style = MaterialTheme.typography.labelSmall,
        modifier = Modifier.clickable { open = true },
    )
    if (open) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { open = false },
            title = { Text("Données") },
            text = { Text(value.detail, style = MaterialTheme.typography.bodySmall) },
            confirmButton = {
                TextButton(onClick = {
                    onRefresh()
                    open = false
                }) { Text("Actualiser") }
            },
            dismissButton = { TextButton(onClick = { open = false }) { Text("Fermer") } },
        )
    }
}
