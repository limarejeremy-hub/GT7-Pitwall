package com.gt7pitwall.app.voice

import android.speech.tts.TextToSpeech
import com.gt7pitwall.core.radio.VoiceChoice

/** Meilleure voix française locale installée (voir [VoiceChoice]) ; nom de la voix retenue, ou null (voix par défaut). */
fun TextToSpeech.useBestFrenchVoice(): String? = runCatching {
    val all = voices.orEmpty().toList()
    val best = VoiceChoice.pick(
        all.map { v ->
            VoiceChoice.Candidate(
                name = v.name,
                language = v.locale.toLanguageTag(),
                quality = v.quality,
                latency = v.latency,
                network = v.isNetworkConnectionRequired,
                installed = TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in v.features.orEmpty(),
            )
        },
    ) ?: return@runCatching null
    val voice = all.first { it.name == best.name }
    if (setVoice(voice) == TextToSpeech.SUCCESS) voice.name else null
}.getOrNull()
