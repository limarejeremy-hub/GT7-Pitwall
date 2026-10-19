import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.gt7pitwall.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.gt7pitwall.app"
        // Android 12 minimum : Gemini Nano (ML Kit GenAI) ; le Fold est sous Android 16.
        minSdk = 31
        targetSdk = 35
        // Sur GitHub Actions, chaque build a un numéro croissant : l'APK s'installe par-dessus le précédent.
        versionCode = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
        versionName = "0.8.0"

        // Le Fold est en arm64 ; on garde aussi armeabi-v7a. Réduit la taille des bibliothèques natives (Vosk).
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    signingConfigs {
        // Clé de debug fixe versionnée : tous les APK debug ont la même signature,
        // donc une mise à jour n'exige pas de désinstaller l'application.
        // Ce n'est PAS une clé de publication.
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    sourceSets["main"].jniLibs.srcDir(layout.buildDirectory.dir("dave-natives"))

    packaging {
        // libdave-jvm se charge par chemin de fichier : la bibliothèque doit être extraite à l'installation.
        jniLibs.useLegacyPackaging = true
        resources.excludes += setOf(
            "META-INF/versions/*/module-info.class",
            "module-info.class",
            "META-INF/DEPENDENCIES",
            "META-INF/INDEX.LIST",
            "META-INF/LICENSE*",
            "META-INF/NOTICE*",
            "META-INF/FastDoubleParser-*",
        )
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core"))

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")

    // Ingénieur IA : Gemini Nano sur le téléphone (ML Kit GenAI Prompt API, gratuit, hors ligne via AICore).
    implementation("com.google.mlkit:genai-prompt:1.0.0-beta4")

    // Radio Discord (prototype) : bot JDA 6 + chiffrement de bout en bout DAVE (libdave-jvm). La bibliothèque native
    // libdave-jvm.so est compilée par la CI depuis les sources (scripts/build-dave-android.sh), jamais téléchargée.
    implementation("net.dv8tion:JDA:6.7.0") {
        exclude(group = "club.minnced", module = "opus-java") // voix déjà encodée en Opus par Android (MediaCodec)
        exclude(group = "com.google.crypto.tink", module = "tink") // remplacé par tink-android (protobuf lite)
        exclude(group = "com.squareup.okhttp3", module = "okhttp-jvm") // remplacé par la variante Android
    }
    implementation("com.squareup.okhttp3:okhttp:5.5.0")
    implementation("com.google.crypto.tink:tink-android:1.18.0")
    implementation("moe.kyokobot.libdave:impl-jni:0.2.0")
    implementation("moe.kyokobot.libdave:adapter-jda:0.2.0")

    // Reconnaissance vocale hors ligne, gratuite et open source (Apache 2.0).
    implementation("com.alphacephei:vosk-android:0.3.47@aar")
    implementation("net.java.dev.jna:jna:5.13.0@aar")

    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
}
