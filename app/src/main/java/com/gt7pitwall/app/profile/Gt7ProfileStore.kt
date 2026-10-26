package com.gt7pitwall.app.profile

import android.content.Context
import com.gt7pitwall.core.profile.Gt7CareerJson
import com.gt7pitwall.core.profile.Gt7CareerStats
import com.gt7pitwall.core.profile.Gt7CareerImport
import com.gt7pitwall.core.profile.Gt7PlayerProfile
import com.gt7pitwall.core.profile.ProfileEvidence

/**
 * Only an ID and career snapshots, stored in Android app-private storage.
 * PSN session secrets are NEVER accepted or saved in Pitwall.
 *
 * This is NOT "Sign in with PlayStation" and must not claim PSN OAuth.
 */
class Gt7ProfileStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(
        "pitwall_gt7_career_v1",Context.MODE_PRIVATE)

    fun read(): Gt7PlayerProfile = runCatching {
        prefs.getString("data",null)?.let(Gt7CareerJson::decode) ?: Gt7PlayerProfile()
    }.getOrDefault(Gt7PlayerProfile())

    private fun persist(profile: Gt7PlayerProfile): Gt7PlayerProfile {
        check(prefs.edit().putString("data",Gt7CareerJson.encode(profile)).commit()) {
            "Impossible d'enregistrer le profil localement"
        }
        return profile
    }

    fun saveIdentity(psn: String, nick: String, officialUrl: String): Gt7PlayerProfile {
        val next = read().changeIdentity(psn)
        require(officialUrl.isBlank() || Gt7PlayerProfile.validOfficialUrl(officialUrl)) {
            "Utilise uniquement le lien officiel de ton profil Gran Turismo"
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
