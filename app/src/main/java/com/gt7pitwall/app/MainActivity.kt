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
        setContent {
            PitwallTheme {
                PitwallScreen(
                    engine = app.engine,
                    voice = app.voice,
                    settings = app.settings,
                    onStart = { address ->
                        app.settings.ps5Address = address
                        TelemetryService.start(this, address)
                    },
                    onStop = { TelemetryService.stop(this) },
                )
            }
        }
    }
}
