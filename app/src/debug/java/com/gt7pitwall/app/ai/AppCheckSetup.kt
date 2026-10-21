package com.gt7pitwall.app.ai

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory

/**
 * APK de débogage, installée depuis GitHub Actions hors Play Store : Play Integrity ne peut pas l'attester, on utilise
 * donc le fournisseur de débogage d'App Check. Son secret est propre au téléphone (tiré au hasard sur le téléphone,
 * jamais dans le dépôt ni l'APK) et doit être autorisé une fois dans la console Firebase.
 */
object AppCheckSetup {
    const val LABEL = "débogage : APK installée depuis GitHub, hors Play Store"
    const val USES_DEBUG_SECRET = true

    fun install(context: Context, app: FirebaseApp, debugSecret: String) {
        // Le fournisseur de débogage lit son secret dans ses préférences : on y place celui du pilote avant de l'activer.
        prefs(context, app).edit().putString(SECRET_KEY, debugSecret).commit()
        FirebaseAppCheck.getInstance(app).installAppCheckProviderFactory(DebugAppCheckProviderFactory.getInstance())
    }

    /** Secret réellement utilisé par le SDK : le nôtre, sauf s'il en a tiré un autre (détail interne qui pourrait changer). */
    fun effectiveSecret(context: Context, app: FirebaseApp, ours: String): String {
        val uuid = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
        val found = prefs(context, app).all.values.filterIsInstance<String>().filter { uuid.matches(it) }
        return found.firstOrNull { it != ours } ?: ours
    }

    private fun prefs(context: Context, app: FirebaseApp) =
        context.getSharedPreferences("com.google.firebase.appcheck.debug.store.${app.persistenceKey}", Context.MODE_PRIVATE)

    private const val SECRET_KEY = "com.google.firebase.appcheck.debug.DEBUG_SECRET"
}
