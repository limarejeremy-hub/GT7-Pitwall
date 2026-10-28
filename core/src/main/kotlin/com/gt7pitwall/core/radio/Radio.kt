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

    /** « As-tu enlevé l'arrêt ? » : état du plan d'arrêt, sans rien modifier. */
    STOP_STATUS,

    /** « Quand dois-je changer mes pneus ? » : âge des pneus, durée de vie et changement prévu. */
    TYRE_CHANGE,

    /** « Au prochain arrêt, je mets quoi comme pneus ? » : choix de la prochaine gomme (jamais un rappel du carburant). */
    NEXT_TYRE,

    /** Questions sur le pilotage (course du 26/10) : qualité des tours, où je perds du temps, freinage, progression, conseil. */
    DRIVING_QUALITY,
    DRIVING_LOSS,
    DRIVING_BRAKING,
    DRIVING_PROGRESS,
    DRIVING_ADVICE,

    /** « Qu'est-ce que tu as appris sur mon pilotage ? » : le diagnostic mesuré du profil de pilotage. */
    DRIVING_LEARNED,
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
        /**
         * Phrase commencée pendant que l'ingénieur parlait ou dans les 2 s qui suivent : seule une telle phrase peut être
         * son écho dans le micro (issue #45), elle est donc comparée à ce qu'il vient de dire.
         */
        val nearEngineer: Boolean = false,
        /** Faux : phrase entendue pendant la conversation continue sans « Radio » devant (modifications à confirmer). */
        val addressed: Boolean = true,
        /** Reconnaissance qui a transcrit la phrase (journal de course). */
        val recognizer: String = "Vosk",
    ) : VoiceInputEvent

    /** Message de la radio elle-même (« Je n'ai pas reçu ta demande… ») : dit au pilote, jamais silencieux. */
    data class Notice(val text: String) : VoiceInputEvent

    /**
     * Le pilote a coupé l'ingénieur ([heard] : ce qui a été reconnu). La voix doit s'arrêter, la réponse en préparation
     * devient périmée, et l'écoute reprend pour la suite de sa phrase.
     */
    data class BargeIn(val heard: String, val mode: DuplexMode) : VoiceInputEvent

    /**
     * État de l'écoute à consigner dans le journal (reprise après blocage, micro muet) : jamais dit, jamais de son
     * enregistré, seulement la raison.
     */
    data class Diagnostic(val text: String) : VoiceInputEvent
}

/**
 * Entrée vocale du pilote (radio bidirectionnelle).
 * Implémentation Android : reconnaissance hors ligne Vosk, avec mot de réveil.
 */
interface VoiceInput {
    val events: Flow<VoiceInputEvent>

    /** L'ingénieur vient de poser une question : écouter la réponse sans mot de réveil. */
    fun expectReply() {}

    /** La réponse tarde : petit signal non verbal (jamais une phrase). */
    fun thinking() {}

    /** Début et fin de la dernière phrase du pilote (horloge monotone, ms), si l'entrée vocale les mesure. */
    val speechTiming: Pair<Long?, Long?> get() = null to null
}

/** Conversion d'une phrase reconnue en question (compatibilité). */
object DriverQueryParser {
    fun parse(text: String): DriverQuery? =
        (DriverCommandParser.parse(text) as? DriverCommand.Ask)?.query
}
