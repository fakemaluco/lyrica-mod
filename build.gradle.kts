plugins {
    base
}

allprojects {
    group = "dev.lyrica"
    version = providers.gradleProperty("version").orElse("0.8.8").get()
    repositories {
        mavenCentral()
        maven("https://libraries.minecraft.net/")
        maven("https://maven.fabricmc.net/")
        maven("https://maven.maxhenkel.de/repository/public")
        maven("https://repo.essential.gg/repository/maven-public")
        maven("https://maven.terraformersmc.com/releases/")
    }
}

subprojects {
    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.release.set(21)
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
    }
}
