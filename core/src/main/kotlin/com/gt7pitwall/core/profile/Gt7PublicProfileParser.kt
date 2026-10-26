package com.gt7pitwall.core.profile

/** Reads only visibly published DR/SR letters, never misleading 0 counters. */
object Gt7PublicProfileParser {
    fun parse(visibleText: String, expectedPsn: String): Gt7CareerStats {
        require(Gt7PlayerProfile.validPsnId(expectedPsn)) { "ID PSN invalide" }
        val text = visibleText.replace(Regex("\\s+"), " ").trim()
        val index = text.indexOf("Driver Rating", ignoreCase = true)
        require(index >= 0) { "Classement public introuvable" }
        val header = text.substring(maxOf(0, index - 400), index)
        val id = Regex("(?i)(?<![A-Za-z0-9_-])" + Regex.escape(expectedPsn) + "(?![A-Za-z0-9_-])")
        require(id.containsMatchIn(header)) { "Profil différent de l'ID PSN demandé" }
        val region = text.substring(index, minOf(text.length, index + 130))
        val match = Regex(
            """(?i)Driver Rating\s+(?:(?:\d+(?:[.,]\d+)?)\s*%\s+)?(A\+|A|B|C|D|E)\s*/\s*(S|A|B|C|D|E)"""
        ).find(region) ?: throw IllegalArgumentException("DR/SR non affichés sur cette page")
        return Gt7CareerStats(
            dr = match.groupValues[1].uppercase(),
            sr = match.groupValues[2].uppercase()
        )
    }
}
