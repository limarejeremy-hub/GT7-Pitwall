package com.gt7pitwall.app

import android.app.Application
import com.gt7pitwall.app.engine.PitwallEngine
import com.gt7pitwall.app.service.TelemetryService
import com.gt7pitwall.app.settings.SettingsStore
import com.gt7pitwall.app.voice.TtsVoiceOutput
import com.gt7pitwall.app.voice.VoiceModelManager
import com.gt7pitwall.app.voice.VoskVoiceInput

/** Point d'entrée : le moteur vit au niveau du processus (survit aux pliages/dépliages de l'écran). */
class PitwallApp : Application() {
    lateinit var settings: SettingsStore
        private set
    lateinit var voice: TtsVoiceOutput
        private set
    lateinit var engine: PitwallEngine
        private set
    lateinit var voiceModels: VoiceModelManager
        private set
    lateinit var voiceInput: VoskVoiceInput
        private set

    override fun onCreate() {
        super.onCreate()
        settings = SettingsStore(this)
        voice = TtsVoiceOutput(this).also { it.speechRate = settings.speechRate }
        voiceModels = VoiceModelManager(this)
        voiceInput = VoskVoiceInput(voiceModels, voice).also {
            it.wakeWord = settings.wakeWord
            it.limitedVocabulary = settings.limitedVocabulary
        }
        engine = PitwallEngine(voice, voiceInput).also { it.voiceEnabled = settings.voiceEnabled }
    }

    /** Active ou coupe l'écoute du mot de réveil (le micro doit déjà être autorisé). */
    fun setVoiceInput(enabled: Boolean) {
        settings.voiceInputEnabled = enabled
        if (enabled) voiceInput.start() else voiceInput.stop()
        // Le service doit déclarer l'usage du micro pour qu'il reste actif écran éteint.
        if (engine.running.value) TelemetryService.refresh(this)
    }
}
