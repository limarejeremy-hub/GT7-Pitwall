package com.gt7pitwall.app.profile

import android.content.Context
import com.gt7pitwall.core.profile.Gt7CareerJson
import com.gt7pitwall.core.profile.Gt7CareerStats
import com.gt7pitwall.core.profile.Gt7CareerImport
import com.gt7pitwall.core.profile.Gt7PlayerProfile
import com.gt7pitwall.core.profile.ProfileEvidence
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Only an ID and career snapshots, stored in Android app-private storage.
 * PSN session secrets are NEVER accepted or saved in Pitwall.
 *
 * This is NOT "Sign in with PlayStation" and must not claim PSN OAuth.
 */
class Gt7ProfileStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(
        "pitwall_gt7_career_v1",Context.MODE_PRIVATE)

    // One observable source of truth shared by Briefing and Atelier.
    // Existing SharedPreferences data remains in place; no migration or reset.
    private val _profile = MutableStateFlow(loadExisting())
    val profile: StateFlow<Gt7PlayerProfile> = _profile.asStateFlow()

    private fun loadExisting(): Gt7PlayerProfile = runCatching {
        prefs.getString("data",null)?.let(Gt7CareerJson::decode) ?: Gt7PlayerProfile()
    }.getOrDefault(Gt7PlayerProfile())

    fun read(): Gt7PlayerProfile = _profile.value

    private fun persist(profile: Gt7PlayerProfile): Gt7PlayerProfile {
        check(prefs.edit().putString("data",Gt7CareerJson.encode(profile)).commit()) {
            "Impossible d'enregistrer le profil localement"
        }
        _profile.value = profile
        return profile
    }

    /**
     * Atomic form save: a player may type their PSN ID and all racing stats
     * BEFORE pressing Save. Both are committed together or neither is.
     */
    fun saveCompleteProfile(
        psn: String, nick: String, officialUrl: String, stats: Gt7CareerStats?,
    ): Gt7PlayerProfile {
        val previous = read()
        val newIdentity = previous.psnId.isNotBlank() && !previous.psnId.equals(psn.trim(), ignoreCase = true)
        require(!newIdentity || stats == null) {
            "Nouvel ID PSN : enregistre d'abord l'identité seule pour ne pas mélanger deux pilotes."
        }
        require(officialUrl.isBlank() || Gt7PlayerProfile.validProfileLink(officialUrl)) {
            "Lien invalide : utilise gran-turismo.com ou un lien Google share.google / search.app."
        }
        require(stats == null || stats.validate() == null) {
            stats?.validate() ?: "Données invalides"
        }
        val identity = previous.changeIdentity(psn).copy(
            nickname=nick.trim().take(80), officialProfileUrl=officialUrl.trim()
        )
        val updated = if (stats == null) identity
            else identity.add(stats,ProfileEvidence.USER_ENTERED,System.currentTimeMillis())
        return persist(updated)
    }

    fun saveIdentity(psn: String, nick: String, officialUrl: String): Gt7PlayerProfile {
        val next = read().changeIdentity(psn)
        require(officialUrl.isBlank() || Gt7PlayerProfile.validProfileLink(officialUrl)) {
            "Utilise gran-turismo.com ou un lien Google share.google / search.app"
        }
        return persist(next.copy(nickname = nick.trim().take(80),
            officialProfileUrl = officialUrl.trim()))
    }

    fun recordManual(stats: Gt7CareerStats): Gt7PlayerProfile =
        persist(read().add(stats,ProfileEvidence.USER_ENTERED,System.currentTimeMillis()))

    fun importUserJson(raw: String): Gt7PlayerProfile {
        val current = read()
        val stats = Gt7CareerImport.fromRacerJson(raw,current.psnId)
        return persist(current.add(stats,ProfileEvidence.USER_IMPORT,System.currentTimeMillis()))
    }

    fun clear(): Gt7PlayerProfile = persist(Gt7PlayerProfile())
}
