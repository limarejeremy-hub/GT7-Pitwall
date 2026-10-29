package com.gt7pitwall.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.gt7pitwall.app.service.TelemetryService
import com.gt7pitwall.app.ui.PitwallScreen
import com.gt7pitwall.app.ui.theme.PitwallTheme

class MainActivity : ComponentActivity() {

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* facultative */ }

    private val micPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) (application as PitwallApp).setVoiceInput(true)
        }

    override fun onStart() {
        super.onStart()
        val app = application as PitwallApp
        app.syncOnOpen()
        // Issue #55 : l'activité est visible, le service de premier plan peut démarrer. Adresse vide = recherche automatique.
        if (app.autoConnect.onOpen(app.settings.autoConnectOnOpen, app.engine.running.value)) {
            TelemetryService.start(this, app.settings.ps5Address)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Tableau de bord : l'écran reste allumé tant que l'application est affichée.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        val app = application as PitwallApp
        // Reprend l'écoute si elle était active (et toujours autorisée).
        if (app.settings.voiceInputEnabled && app.voiceModels.installedDir() != null &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        ) {
            app.voiceInput.start()
        }
        setContent {
            PitwallTheme {
                PitwallScreen(
                    engine = app.engine,
                    voice = app.voice,
                    settings = app.settings,
                    voiceModels = app.voiceModels,
                    voiceInput = app.voiceInput,
                    gemini = app.gemini,
                    cloudAi = app.cloudAi,
                    onAiMode = app::setAiMode,
                    discord = app.discord,
                    bench = app.bench,
                    onDiscordMirror = app::setDiscordMirror,
                    onDiscordMic = app::setDiscordMic,
                    onAiToggle = app::setAi,
                    onGoogleOnHeadset = app::setGoogleOnHeadset,
                    onDevTools = app::setDevTools,
                    onRefreshSchedule = { app.sport.refreshNow() },
                    freshness = app.freshness,
                    onStart = { address ->
                        app.settings.ps5Address = address
                        app.autoConnect.onUserStart()
                        TelemetryService.start(this, address)
                    },
                    onAutoConnectEnabled = {
                        if (app.autoConnect.onOpen(enabled = true, running = app.engine.running.value)) {
                            TelemetryService.start(this, app.settings.ps5Address)
                        }
                    },
                    onStop = {
                        app.autoConnect.onUserStop()
                        TelemetryService.stop(this)
                    },
                    onVoiceInputToggle = { enabled ->
                        if (enabled && ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) !=
                            PackageManager.PERMISSION_GRANTED
                        ) {
                            micPermission.launch(Manifest.permission.RECORD_AUDIO)
                        } else {
                            app.setVoiceInput(enabled)
                        }
                    },
                )
            }
        }
    }
}
