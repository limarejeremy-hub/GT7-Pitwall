import java.net.URI
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
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
        // Banc d'essai sherpa-onnx : seulement pour le Fold (arm64), pas de copie armeabi-v7a (~25 Mo de moins). Le code
        // Kotlin ne charge que libsherpa-onnx-jni (qui ne dépend que de libonnxruntime) : les API C et C++ sont retirées.
        jniLibs.excludes += setOf(
            "lib/armeabi-v7a/libonnxruntime.so",
            "lib/armeabi-v7a/libsherpa-onnx-jni.so",
            "**/libsherpa-onnx-c-api.so",
            "**/libsherpa-onnx-cxx-api.so",
        )
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

val JDA_VERSION = "6.7.0"

/** JDA tel que publié, seulement pour en produire la copie Android. */
val jdaOriginal: Configuration by configurations.creating { isTransitive = false }

/**
 * JDA compile deux expressions régulières de `Checks` avec Pattern.UNICODE_CHARACTER_CLASS (256), drapeau refusé par
 * Android : la classe ne s'initialise pas et aucune connexion n'est possible. Sur Android, `\w` couvre déjà l'Unicode :
 * on remplace ce drapeau par 0 (même longueur d'instruction). Le motif exact est vérifié, sinon la compilation échoue.
 */
val patchJdaForAndroid by tasks.registering {
    val source = jdaOriginal
    val target = layout.buildDirectory.file("jda-android/JDA-$JDA_VERSION-android.jar")
    inputs.files(source)
    outputs.file(target)
    doLast {
        val checks = "net/dv8tion/jda/internal/utils/Checks.class"
        // sipush 256 ; invokestatic #366 (Pattern.compile(String, int))
        val before = byteArrayOf(0x11, 0x01, 0x00, 0xB8.toByte(), 0x01, 0x6E)
        val out = target.get().asFile.apply { parentFile.mkdirs() }
        ZipFile(source.singleFile).use { zip ->
            ZipOutputStream(out.outputStream()).use { dest ->
                for (entry in zip.entries()) {
                    var bytes = zip.getInputStream(entry).use { it.readBytes() }
                    if (entry.name == checks) {
                        var found = 0
                        var i = 0
                        while (i <= bytes.size - before.size) {
                            if (before.indices.all { bytes[i + it] == before[it] }) {
                                bytes[i + 1] = 0x00
                                found++
                                i += before.size
                            } else i++
                        }
                        check(found == 2) { "JDA $JDA_VERSION : motif UNICODE_CHARACTER_CLASS trouvé $found fois au lieu de 2" }
                    }
                    dest.putNextEntry(ZipEntry(entry.name))
                    dest.write(bytes)
                    dest.closeEntry()
                }
            }
        }
    }
}

val SHERPA_VERSION = "1.13.8"
val SHERPA_SHA256 = "633c24321e06b1fe79feafa03ea16cbc0f8a286641e2da3559bac91bdb13bd96"
val sherpaAar = layout.buildDirectory.file("sherpa/sherpa-onnx-$SHERPA_VERSION.aar")

/**
 * sherpa-onnx (Apache 2.0) pour le banc d'essai de reconnaissance : l'AAR officiel des versions GitHub du projet, dont
 * l'empreinte SHA-256 est vérifiée (la compilation échoue si le fichier change).
 */
val downloadSherpaAar by tasks.registering {
    outputs.file(sherpaAar)
    doLast {
        val out = sherpaAar.get().asFile.apply { parentFile.mkdirs() }
        fun sha(f: File) = MessageDigest.getInstance("SHA-256").digest(f.readBytes()).joinToString("") { "%02x".format(it) }
        if (out.isFile && sha(out) == SHERPA_SHA256) return@doLast
        val url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/v$SHERPA_VERSION/sherpa-onnx-$SHERPA_VERSION.aar"
        URI(url).toURL().openStream().use { input -> out.outputStream().use { input.copyTo(it) } }
        val got = sha(out)
        check(got == SHERPA_SHA256) { "sherpa-onnx $SHERPA_VERSION : empreinte $got au lieu de $SHERPA_SHA256" }
    }
}

dependencies {
    implementation(project(":core"))

    // Banc d'essai de reconnaissance locale (sherpa-onnx) : modèles téléchargés à la demande depuis les archives
    // .tar.bz2 du projet, extraites au fil du téléchargement (commons-compress, Apache 2.0).
    implementation(files(sherpaAar).builtBy(downloadSherpaAar))
    implementation("org.apache.commons:commons-compress:1.27.1")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")

    // Ingénieur IA : Gemini Nano sur le téléphone (ML Kit GenAI Prompt API, gratuit, hors ligne via AICore).
    implementation("com.google.mlkit:genai-prompt:1.0.0-beta4")

    // Ingénieur IA dans le cloud : Gemini (Flash-Lite, Flash 3.8) via le SDK officiel Firebase AI Logic et la Gemini
    // Developer API, niveau gratuit. Inactif sans la configuration du projet, saisie sur le téléphone (rien dans l'APK).
    // App Check : fournisseur de débogage dans l'APK debug (installée hors Play Store), Play Integrity en release.
    implementation(platform("com.google.firebase:firebase-bom:34.11.0"))
    implementation("com.google.firebase:firebase-ai")
    implementation("com.google.firebase:firebase-appcheck")
    debugImplementation("com.google.firebase:firebase-appcheck-debug")
    releaseImplementation("com.google.firebase:firebase-appcheck-playintegrity")

    // Radio Discord (prototype) : bot JDA 6 + chiffrement de bout en bout DAVE (libdave-jvm). La bibliothèque native
    // libdave-jvm.so est compilée par la CI depuis les sources (scripts/build-dave-android.sh), jamais téléchargée.
    // JDA est utilisé via une copie corrigée pour Android (voir patchJdaForAndroid) ; ses dépendances sont déclarées
    // ici, sans opus-java (voix déjà encodée en Opus par MediaCodec), avec tink-android (protobuf lite, comme ML Kit)
    // et la variante Android d'OkHttp.
    jdaOriginal("net.dv8tion:JDA:$JDA_VERSION")
    implementation(files(patchJdaForAndroid))
    implementation("org.slf4j:slf4j-api:2.0.19")
    implementation("com.neovisionaries:nv-websocket-client:2.14")
    implementation("org.apache.commons:commons-collections4:4.6.0")
    implementation("net.sf.trove4j:core:3.1.0")
    implementation("com.fasterxml.jackson.core:jackson-core:2.22.2")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.22.2")
    // 5.3.2 : dernière version sans exigence de compileSdk (5.4 demande 36, 5.5 demande 37).
    implementation("com.squareup.okhttp3:okhttp") { version { strictly("5.3.2") } }
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
