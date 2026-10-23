package com.gt7pitwall.app.voice

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.gt7pitwall.app.discord.DiscordRadio
import com.gt7pitwall.core.radio.RadioTone
import com.gt7pitwall.core.radio.RadioTones
import com.gt7pitwall.core.radio.RoutedVoiceOutput
import com.gt7pitwall.core.radio.TonePlayer
import com.gt7pitwall.core.radio.VoiceRoute
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Bips de la radio là où l'ingénieur parle : dans le salon Discord (casque PS5), encodés en Opus comme la voix, ou sur
 * le Fold (même sortie audio que la voix de l'ingénieur). Ce sont de vrais échantillons PCM, pas un ToneGenerator : le
 * même signal passe par les deux chemins.
 */
class RadioTonePlayer(private val discord: DiscordRadio, private val route: RoutedVoiceOutput) : TonePlayer {

    override suspend fun play(tone: RadioTone) {
        when (route.route) {
            VoiceRoute.REMOTE -> if (!discord.playTone(tone)) playOnPhone(tone)
            VoiceRoute.BOTH -> {
                discord.playTone(tone)
                playOnPhone(tone)
            }
            VoiceRoute.PHONE -> playOnPhone(tone)
        }
    }

    private suspend fun playOnPhone(tone: RadioTone) = withContext(Dispatchers.IO) {
        val pcm = RadioTones.pcm(tone, PHONE_RATE)
        val track = runCatching {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(PHONE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                )
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(pcm.size * 2)
                .build()
        }.getOrNull() ?: return@withContext
        try {
            track.write(pcm, 0, pcm.size)
            track.play()
            delay(RadioTones.durationMs(tone) + END_MARGIN_MS)
        } catch (e: Exception) {
            // Pas de bip possible : l'écoute continue quand même.
        } finally {
            runCatching { track.stop() }
            track.release()
        }
    }

    private companion object {
        const val PHONE_RATE = 48_000
        const val END_MARGIN_MS = 40L
    }
}
