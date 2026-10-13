package com.gt7pitwall.core.radio

import com.gt7pitwall.core.engineer.EngineerMessage
import kotlinx.coroutines.flow.Flow

/** Sortie vocale de l'ingénieur (Android TextToSpeech aujourd'hui). */
interface VoiceOutput {
    fun speak(message: EngineerMessage)
    fun stop()
}

/** Questions que le pilote peut poser à l'ingénieur. */
enum class DriverQuery {
    FUEL,
    LAST_LAP,
    BEST_LAP,
    RACE_STATUS,
    PACE,
}

/**
 * Entrée vocale du pilote, pour la future radio bidirectionnelle.
 *
 * Implémentation prévue : android.speech.SpeechRecognizer (gratuit, reconnaissance française),
 * déclenché par un bouton "push-to-talk" ou un bouton de volant / casque Bluetooth.
 * Le texte reconnu est converti en [DriverQuery] puis traité par
 * [com.gt7pitwall.core.engineer.RaceEngineer.answer], déjà utilisé par les boutons de l'écran.
 */
interface VoiceInput {
    val queries: Flow<DriverQuery>
    fun startListening()
    fun stopListening()
}

/** Conversion d'une phrase reconnue en question. Simple et extensible. */
object DriverQueryParser {
    fun parse(text: String): DriverQuery? {
        val t = text.lowercase()
        return when {
            listOf("essence", "carburant", "fuel", "réservoir").any { it in t } -> DriverQuery.FUEL
            listOf("meilleur", "record").any { it in t } -> DriverQuery.BEST_LAP
            listOf("dernier tour", "chrono", "temps").any { it in t } -> DriverQuery.LAST_LAP
            listOf("rythme", "régularité", "moyenne").any { it in t } -> DriverQuery.PACE
            listOf("tour", "course", "reste").any { it in t } -> DriverQuery.RACE_STATUS
            else -> null
        }
    }
}
