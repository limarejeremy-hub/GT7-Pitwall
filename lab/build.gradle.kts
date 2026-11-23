import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Pitwall Lab : outils de laboratoire hors application (rejeu de traces, rapports). Ne fait PAS partie de l'APK.
plugins {
    id("org.jetbrains.kotlin.jvm")
    application
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

application {
    mainClass.set("com.gt7pitwall.lab.PitwallLabKt")
    applicationDefaultJvmArgs = listOf("-Dfile.encoding=UTF-8")
}

dependencies {
    implementation(project(":core"))
    // Fourni par Android dans l'application ; nécessaire ici à l'exécution sur JVM.
    implementation("org.json:json:20231013")
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}
