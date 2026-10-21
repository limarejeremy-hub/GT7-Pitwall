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
    SECTORS,
    POSITION,

    /** « Combien de places gagnées ? », « j'ai perdu combien de places ? » : depuis le départ, d'après GT7. */
    PLACES,
    TYRES,
    PLAN,

    /** Détail complet du dernier tour (statistiques, secteurs, carburant). */
    DETAILS,

    /** État des obligations du règlement (arrêts, gommes), avec leurs preuves. */
    OBLIGATIONS,

    /** « Quand dois-je changer mes pneus ? » : âge des pneus, durée de vie et changement prévu. */
    TYRE_CHANGE,
}

/** Événements de l'entrée vocale (mot de réveil puis phrase du pilote). */
sealed interface VoiceInputEvent {
    /** Mot de réveil entendu seul : la conversation s'ouvre (statut affiché, rien n'est dit). */
    data object Wake : VoiceInputEvent

    /** Le micro écoute : bip joué (aucune phrase « Je t'écoute » n'est dite). */
    data object Listening : VoiceInputEvent

    /** Conversation refermée (« terminé » ou silence) : double bip, retour au mot de réveil. */
    data class ConversationClosed(val byDriver: Boolean) : VoiceInputEvent

    /**
     * Phrase du pilote adressée à l'ingénieur. [text] vient de la reconnaissance limitée au vocabulaire radio
     * (fiable pour les commandes) ; [freeText], de la reconnaissance libre, sert aux questions posées à l'IA.
     */
    data class Utterance(
        val text: String,
        val freeText: String? = null,
        /** Faux : phrase entendue pendant la conversation continue sans « Radio » devant (modifications à confirmer). */
        val addressed: Boolean = true,
        /** Reconnaissance qui a transcrit la phrase (journal de course). */
        val recognizer: String = "Vosk",
    ) : VoiceInputEvent

    /** Message de la radio elle-même (« Je n'ai pas reçu ta demande… ») : dit au pilote, jamais silencieux. */
    data class Notice(val text: String) : VoiceInputEvent
}

/**
 * Entrée vocale du pilote (radio bidirectionnelle).
 * Implémentation Android : reconnaissance hors ligne Vosk, avec mot de réveil.
 */
interface VoiceInput {
    val events: Flow<VoiceInputEvent>

    /** L'ingénieur vient de poser une question : écouter la réponse sans mot de réveil. */
    fun expectReply() {}
}

/** Conversion d'une phrase reconnue en question (compatibilité). */
object DriverQueryParser {
    fun parse(text: String): DriverQuery? =
        (DriverCommandParser.parse(text) as? DriverCommand.Ask)?.query
}
