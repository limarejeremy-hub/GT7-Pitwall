package com.gt7pitwall.app.ai

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.appcheck.playintegrity.PlayIntegrityAppCheckProviderFactory

/**
 * Version de production (publication sur le Play Store) : App Check atteste l'application avec Play Integrity. Aucun
 * secret de débogage n'est utilisé ni accepté dans cette version.
 */
object AppCheckSetup {
    const val LABEL = "Play Integrity : application publiée sur le Play Store"
    const val USES_DEBUG_SECRET = false

    fun install(context: Context, app: FirebaseApp, debugSecret: String) {
        FirebaseAppCheck.getInstance(app).installAppCheckProviderFactory(PlayIntegrityAppCheckProviderFactory.getInstance())
    }

    fun effectiveSecret(context: Context, app: FirebaseApp, ours: String): String? = null
}
