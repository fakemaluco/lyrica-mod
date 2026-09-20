pluginManagement {
    repositories {
        maven("https://maven.fabricmc.net/")
        gradlePluginPortal()
        mavenCentral()
    }
}

rootProject.name = "lyrica"
include("shared-protocol", "lyrics-popup", "lyrics-popup-1.21.11")
