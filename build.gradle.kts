plugins {
    base
}

allprojects {
    group = "dev.lyrica"
    version = providers.gradleProperty("version").orElse("0.8.96").get()
    repositories {
        mavenCentral()
        maven("https://api.modrinth.com/maven") { content { includeGroup("maven.modrinth") } }
        maven("https://libraries.minecraft.net/")
        maven("https://maven.fabricmc.net/")
        maven("https://maven.maxhenkel.de/repository/public")
        maven("https://repo.essential.gg/repository/maven-public")
        maven("https://maven.terraformersmc.com/releases/")
        maven("https://maven.gegy.dev")
    }
}

subprojects {
    if (name == "mod" || name == "mod-1.21.11" || name.startsWith("lyrics-popup")) {
        tasks.withType<ProcessResources>().configureEach {
            if (name == "processResources") {
                from(rootProject.file("THIRD_PARTY_NOTICES.md")) {
                    into("META-INF/lyrica")
                }
                from(rootProject.file("integrations/youtube-music-extension")) {
                    include("manifest.json", "content.js", "background.js", "README.md")
                    into("youtube-music-extension")
                }
            }
        }
    }
    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.release.set(21)
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
    }
}
