plugins {
    id("net.fabricmc.fabric-loom") version "1.17.17"
    `maven-publish`
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
    withSourcesJar()
}
tasks.withType<JavaCompile>().configureEach {
    options.release.set(25)
    options.compilerArgs.addAll(listOf("-Xmaxerrs", "500"))
}

base { archivesName.set("lyrica-26.2") }
group = "dev.relaylyrics"

val hostOs = System.getProperty("os.name", "").lowercase()
val hostArch = when (System.getProperty("os.arch", "").lowercase()) {
    "amd64", "x86_64" -> "x86_64"
    "aarch64", "arm64" -> "arm64"
    else -> System.getProperty("os.arch", "unknown").lowercase().replace(Regex("[^a-z0-9_-]"), "_")
}
val hostPlatform = when {
    hostOs.contains("win") -> "windows-$hostArch"
    hostOs.contains("mac") || hostOs.contains("darwin") -> "macos-$hostArch"
    else -> "linux-$hostArch"
}
val originalBridgeName = if (hostOs.contains("win")) "fandroid-media-bridge.exe" else "fandroid-media-bridge"
val standaloneBridgeName = if (hostOs.contains("win")) "relaylyrics-media-bridge.exe" else "relaylyrics-media-bridge"
val bridgeExecutable = rootProject.layout.projectDirectory.file("media-bridge/target/release/$originalBridgeName")
val bundledBridgeResources = layout.buildDirectory.dir("generated/bundledMediaBridge")
val generatedSharedSources = layout.buildDirectory.dir("generated/sharedClientJava")

val stageBundledMediaBridge by tasks.registering(Copy::class) {
    from(bridgeExecutable) { rename { standaloneBridgeName } }
    into(bundledBridgeResources.map { it.dir("relaylyrics/native/$hostPlatform") })
    onlyIf { bridgeExecutable.asFile.isFile }
}

// Reuse the version-independent music, protocol, preferences and layout sources.
// The 26.2 source tree contains only files that need Mojang/API-specific changes.
val prepareSharedClientJava by tasks.registering(Sync::class) {
    dependsOn(":lyrics-popup-1.21.11:prepareStandaloneClientJava")
    from(rootProject.file("lyrics-popup/src/client/java")) {
        include("dev/relaylyrics/client/ClientPreferences.java")
        include("dev/relaylyrics/client/MusicPresence.java")
        include("dev/relaylyrics/client/presence/**")
        include("dev/relaylyrics/client/compat/**")
        include("dev/relaylyrics/client/WorldLyricsLightAdapter.java")
        include("dev/relaylyrics/client/LyricsSettingsCatalog.java")
        include("dev/relaylyrics/client/LyricsSettingsPresets.java")
        include("dev/relaylyrics/client/LyricsSettingsDemo.java")
        include("dev/relaylyrics/client/InventoryPopupTransition.java")
        include("dev/relaylyrics/client/RelayLyricsModMenu.java")
        include("dev/relaylyrics/client/ui/WorldGlyphMesh.java")
        include("dev/relaylyrics/client/ui/SettingsMotion.java")
        include("dev/relaylyrics/client/ui/SettingsListMotion.java")
        include("dev/relaylyrics/client/ui/SettingsTextRaster.java")
        include("dev/relaylyrics/client/ui/SettingsIconRaster.java")
    }
    from(rootProject.file("lyrics-popup-1.21.11/build/generated/standaloneClientJava")) {
        include("dev/fandroid/protocol/**")
        include("dev/fandroid/client/music/**")
        exclude("dev/fandroid/client/music/CoverArtTexture.java")
        exclude("dev/fandroid/client/music/MusicService.java")
        exclude("dev/fandroid/client/music/PlatformTextures.java")
        exclude("dev/fandroid/client/music/WorldGeminiRenderer.java")
        include("dev/fandroid/client/CompatibilityManager.java")
        include("dev/fandroid/client/LyricsEffectsMode.java")
        include("dev/fandroid/client/LyricsFontWeight.java")
        include("dev/fandroid/client/LyricsGlowIntensity.java")
        include("dev/fandroid/client/LyricsTextAlignment.java")
        include("dev/fandroid/client/LyricsTransitionSpeed.java")
        include("dev/fandroid/client/MusicSource.java")
        include("dev/fandroid/client/ui/HudSafeArea.java")
        eachFile { path = path.replace("dev/fandroid/", "dev/relaylyrics/") }
        includeEmptyDirs = false
    }
    into(generatedSharedSources)
}

sourceSets.main {
    java.setSrcDirs(listOf(rootProject.file("lyrics-popup/src/main/java")))
    resources.setSrcDirs(listOf(rootProject.file("lyrics-popup/src/main/resources")))
}
sourceSets.test {
    java.setSrcDirs(listOf(rootProject.file("lyrics-popup/src/test/java"), file("src/test/java")))
    java.exclude("**/WorldTextFillTest.java", "**/WorldLyricsBenchmark.java")
}

dependencies {
    compileOnly("maven.modrinth:jade:26.2.11+fabric") { isTransitive = false }
    compileOnly("dev.lambdaurora.lambdynamiclights:lambdynamiclights-api:4.12.4+26.2")
    implementation(project(":lyrics-engine"))
    include(project(":lyrics-engine"))
    minecraft("com.mojang:minecraft:26.2")
    implementation("net.fabricmc:fabric-loader:${property("loader_version")}")
    implementation("net.fabricmc.fabric-api:fabric-api:0.161.0+26.2")
    compileOnly("com.terraformersmc:modmenu:20.0.3") { isTransitive = false }

    implementation("com.fasterxml.jackson.core:jackson-annotations:2.19.2")
    include("com.fasterxml.jackson.core:jackson-annotations:2.19.2")
    implementation("com.fasterxml.jackson.core:jackson-core:2.19.2")
    include("com.fasterxml.jackson.core:jackson-core:2.19.2")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.19.2")
    include("com.fasterxml.jackson.core:jackson-databind:2.19.2")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.19.2")
    include("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.19.2")

    testImplementation(platform("org.junit:junit-bom:5.13.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.assertj:assertj-core:3.27.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

loom {
    splitEnvironmentSourceSets()
    mods {
        create("relaylyrics") {
            sourceSet(sourceSets.main.get())
            sourceSet(sourceSets.named("client").get())
        }
    }
}
sourceSets.named("client") { java.srcDir(generatedSharedSources) }
tasks.named("compileClientJava") { dependsOn(prepareSharedClientJava) }
tasks.named("sourcesJar") { dependsOn(prepareSharedClientJava) }

tasks.processResources {
    dependsOn(stageBundledMediaBridge)
    from(bundledBridgeResources)
    from(rootProject.file("mod/src/main/resources/assets/lyrica/textures/gui/lyrics")) {
        into("assets/lyrica/textures/gui/lyrics")
    }
    inputs.property("version", project.version)
    inputs.property("mixin_config", "26.2-client-only")
    filesMatching("fabric.mod.json") {
        expand(mapOf("version" to project.version, "minecraft_dependency" to "~26.2",
            "fabric_api_dependency" to ">=0.161.0"))
        filter { line: String ->
            when {
                line.contains("\"environment\"") -> "$line\n  \"mixins\": [\"relaylyrics.client.mixins.json\"],"
                line.contains("\"mixins\"") -> ""
                line.contains("\"java\": \">=21\"") -> line.replace("\">=21\"", "\">=25\"")
                else -> line
            }
        }
    }
    filesMatching("relaylyrics.client.mixins.json") {
        filter { line: String -> line.replace("JAVA_21", "JAVA_25") }
    }
}
tasks.withType<Jar>().configureEach {
    from(rootProject.file("LICENSE")) { rename { "LICENSE_relaylyrics" } }
    from(rootProject.file("THIRD_PARTY_NOTICES.md"))
}

val verifyStandaloneIsolation by tasks.registering {
    dependsOn(tasks.named("jar"))
    doLast {
        val archive = tasks.named<Jar>("jar").get().archiveFile.get().asFile
        val entries = zipTree(archive).files.map { it.invariantSeparatorsPath }
        require(entries.none { it.contains("FanDroidScreen.class") || it.contains("ClientRuntime.class")
                || it.contains("/chat/") || it.contains("/voice/") || it.contains("/server/") }) {
            "Standalone lyrics JAR contains phone, chat, call or server classes"
        }
    }
}
tasks.named("check") { dependsOn(verifyStandaloneIsolation) }
