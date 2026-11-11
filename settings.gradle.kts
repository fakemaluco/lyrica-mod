pluginManagement {
    repositories {
        maven("https://maven.fabricmc.net/")
        gradlePluginPortal()
        mavenCentral()
    }
}

rootProject.name = "lyrica"
include("shared-protocol", "lyrics-engine", "lyrics-popup", "lyrics-popup-1.21.11", "lyrics-popup-26.2", "lyrics-popup-26.3")
