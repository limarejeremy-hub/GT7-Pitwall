pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "GT7-Pitwall"

// :core  -> logique pure Kotlin/JVM (décodage GT7, tours, carburant, ingénieur). Testable sans Android.
// :app   -> application Android (UI Compose, service, synthèse vocale).
include(":core")
include(":app")
