package com.gt7pitwall.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.gt7pitwall.app.MainActivity
import com.gt7pitwall.app.PitwallApp
import com.gt7pitwall.app.R

/**
 * Service de premier plan : garde la réception de télémétrie et la voix actives
 * quand l'écran est éteint ou que l'application est en arrière-plan.
 */
class TelemetryService : Service() {

    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val engine = (application as PitwallApp).engine
        when (intent?.action) {
            ACTION_STOP -> {
                engine.stop()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_REFRESH -> {
                goForeground()
                return START_NOT_STICKY
            }
        }

        goForeground()
        acquireLocks()
        engine.start(intent?.getStringExtra(EXTRA_HOST))
        return START_NOT_STICKY
    }

    private fun goForeground() {
        val app = application as PitwallApp
        val mic = app.settings.voiceInputEnabled &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val micType = if (mic && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or micType
        } else {
            micType
        }
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(), type)
        } catch (e: Exception) {
            // L'écoute fonctionne quand même tant que l'application est affichée.
            Log.w(TAG, "Service de premier plan refusé", e)
        }
    }

    override fun onDestroy() {
        (application as PitwallApp).engine.stop()
        releaseLocks()
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    private fun acquireLocks() {
        if (wifiLock == null) {
            val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                WifiManager.WIFI_MODE_FULL_LOW_LATENCY
            } else {
                WifiManager.WIFI_MODE_FULL_HIGH_PERF
            }
            wifiLock = wifi.createWifiLock(mode, "gt7pitwall:wifi").apply {
                setReferenceCounted(false)
                acquire()
            }
        }
        if (wakeLock == null) {
            val power = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "gt7pitwall:telemetry").apply {
                setReferenceCounted(false)
                acquire(MAX_WAKE_MS)
            }
        }
    }

    private fun releaseLocks() {
        wifiLock?.takeIf { it.isHeld }?.release()
        wifiLock = null
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
    }

    private fun buildNotification(): Notification {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Télémétrie GT7", NotificationManager.IMPORTANCE_LOW),
            )
        }
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, TelemetryService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_pitwall)
            .setContentTitle("GT7 Pitwall")
            .setContentText("Ingénieur en écoute de la télémétrie")
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(0, "Arrêter", stop)
            .build()
    }

    companion object {
        private const val TAG = "TelemetryService"
        private const val CHANNEL_ID = "telemetry"
        private const val NOTIFICATION_ID = 7
        private const val ACTION_STOP = "com.gt7pitwall.app.STOP"
        private const val ACTION_REFRESH = "com.gt7pitwall.app.REFRESH"
        private const val EXTRA_HOST = "host"
        private const val MAX_WAKE_MS = 6L * 60 * 60 * 1000

        fun start(context: Context, host: String) {
            val intent = Intent(context, TelemetryService::class.java).putExtra(EXTRA_HOST, host)
            ContextCompat.startForegroundService(context, intent)
        }

        /** Met à jour les usages déclarés (micro) du service déjà démarré. */
        fun refresh(context: Context) {
            context.startService(Intent(context, TelemetryService::class.java).setAction(ACTION_REFRESH))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, TelemetryService::class.java))
        }
    }
}
