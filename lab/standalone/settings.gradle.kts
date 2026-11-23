// Construction autonome de Pitwall Lab (sans le module Android, donc sans SDK Android) :
//   ./gradlew -p lab/standalone :lab:test :lab:installDist
// Utilisée par les sessions Claude Code dans le cloud et par le serveur MCP du Lab.
pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}
rootProject.name = "pitwall-lab-standalone"
include(":core")
project(":core").projectDir = file("../../core")
include(":lab")
project(":lab").projectDir = file("..")
