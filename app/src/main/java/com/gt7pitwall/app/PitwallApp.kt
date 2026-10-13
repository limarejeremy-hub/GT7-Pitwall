package com.gt7pitwall.app

import android.app.Application
import com.gt7pitwall.app.engine.PitwallEngine
import com.gt7pitwall.app.settings.SettingsStore
import com.gt7pitwall.app.voice.TtsVoiceOutput

/** Point d'entrée : le moteur vit au niveau du processus (survit aux pliages/dépliages de l'écran). */
class PitwallApp : Application() {
    lateinit var settings: SettingsStore
        private set
    lateinit var voice: TtsVoiceOutput
        private set
    lateinit var engine: PitwallEngine
        private set

    override fun onCreate() {
        super.onCreate()
        settings = SettingsStore(this)
        voice = TtsVoiceOutput(this).also { it.speechRate = settings.speechRate }
        engine = PitwallEngine(voice).also { it.voiceEnabled = settings.voiceEnabled }
    }
}
