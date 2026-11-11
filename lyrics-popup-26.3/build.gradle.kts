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

base { archivesName.set("lyrica-26.3") }
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
// Share the Mojang sources with 26.2, replacing files only where 26.3 requires an adapter.
val prepareSharedClientJava by tasks.registering(Sync::class) {
    val versionSources = layout.projectDirectory.dir("src/client/java")
    from(rootProject.file("lyrics-popup-26.2/src/client/java")) {
        exclude { versionSources.file(it.relativePath.pathString).asFile.isFile }
    }
    dependsOn(":lyrics-popup-1.21.11:prepareStandaloneClientJava")
    from(rootProject.file("lyrics-popup/src/client/java")) {
        include("dev/relaylyrics/client/ClientPreferences.java")
        include("dev/relaylyrics/client/SungTextColor.java")
        include("dev/relaylyrics/mixin/XaeroMinimapAlignmentMixin.java")
        include("dev/relaylyrics/mixin/JiAfkSessionMixin.java")
        include("dev/relaylyrics/mixin/JiAfkMusicMixin.java")
        include("dev/relaylyrics/mixin/SimpleVoiceDuckingMixin.java")
        include("dev/relaylyrics/mixin/PlasmoVoiceDuckingMixin.java")
        include("dev/relaylyrics/client/MusicPresence.java")
        include("dev/relaylyrics/client/presence/**")
        include("dev/relaylyrics/client/karaoke/KaraokeClient.java")
        include("dev/relaylyrics/client/karaoke/KaraokePopup.java")
        include("dev/relaylyrics/client/karaoke/KaraokeQueueView.java")
        include("dev/relaylyrics/client/karaoke/KaraokeQueueSearch.java")
        include("dev/relaylyrics/client/karaoke/KaraokeInvitationLayout.java")
        include("dev/relaylyrics/client/karaoke/KaraokeSurfaces.java")
        include("dev/relaylyrics/client/compat/**")
        include("dev/relaylyrics/client/LyricsSettingsCatalog.java")
        include("dev/relaylyrics/client/LyricsSettingsPresets.java")
        include("dev/relaylyrics/client/LyricsSettingsDemo.java")
        include("dev/relaylyrics/client/InventoryPopupTransition.java")
        include("dev/relaylyrics/client/RelayLyricsModMenu.java")
        include("dev/relaylyrics/client/ui/WorldGlyphMesh.java")
        include("dev/relaylyrics/client/ui/SettingsMotion.java")
        include("dev/relaylyrics/client/ui/KaraokePanelMotion.java")
        include("dev/relaylyrics/client/ui/SettingsColor.java")
        include("dev/relaylyrics/client/ui/SettingsListMotion.java")
        include("dev/relaylyrics/client/ui/SettingsTextRaster.java")
        include("dev/relaylyrics/client/ui/SettingsIconRaster.java")
    }
    // Queue editing is shared with Yarn versions. Translate its key constants for the SDL client.
    filesMatching("dev/relaylyrics/client/karaoke/KaraokeClient.java") {
        filter { line: String ->
            line.replace("import static org.lwjgl.glfw.GLFW.*;", "import static com.mojang.blaze3d.platform.InputConstants.*;")
                .replace("GLFW_KEY_KP_ENTER", "KEY_NUMPADENTER").replace("GLFW_KEY_ENTER", "KEY_RETURN")
                .replace("GLFW_KEY_", "KEY_").replace("action == GLFW_RELEASE", "action == 0")
                .replace("(modifiers & GLFW_MOD_SHIFT) != 0", "dev.relaylyrics.client.PlatformInput.shift(modifiers)")
                .replace("(modifiers & (GLFW_MOD_CONTROL | GLFW_MOD_SUPER)) != 0", "dev.relaylyrics.client.PlatformInput.shortcut(modifiers)")
        }
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
    val versionSources = layout.projectDirectory.dir("src/main/java")
    java.srcDir(rootProject.file("lyrics-popup-26.2/src/main/java"))
    java.exclude {
        val shared = rootProject.file("lyrics-popup-26.2/src/main/java")
        it.file.toPath().startsWith(shared.toPath()) && versionSources.file(it.relativePath.pathString).asFile.isFile
    }
    java.srcDir(rootProject.file("lyrics-popup/src/main/java"))
    java.exclude { it.file == rootProject.file("lyrics-popup/src/main/java/dev/relaylyrics/karaoke/KaraokeNetwork.java") }
    resources.setSrcDirs(listOf(rootProject.file("lyrics-popup/src/main/resources")))
}
sourceSets.test {
    java.setSrcDirs(listOf(rootProject.file("lyrics-popup/src/test/java"), rootProject.file("lyrics-popup-26.2/src/test/java"), file("src/test/java")))
    java.exclude("**/WorldTextFillTest.java", "**/WorldLyricsBenchmark.java")
}

dependencies {
    compileOnly("maven.modrinth:jade:26.3.5+fabric") { isTransitive = false }
    compileOnly("dev.lambdaurora.lambdynamiclights:lambdynamiclights-api:4.13.0+26.3")
    implementation(project(":lyrics-engine"))
    include(project(":lyrics-engine"))
    minecraft("com.mojang:minecraft:26.3")
    implementation("net.fabricmc:fabric-loader:0.20.1")
    implementation("net.fabricmc.fabric-api:fabric-api:0.166.0+26.3")
    compileOnly("com.terraformersmc:modmenu:21.0.0") { isTransitive = false }

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
    inputs.property("mixin_config", "26.3-client-and-server")
    filesMatching("fabric.mod.json") {
        expand(mapOf("version" to project.version, "minecraft_dependency" to "26.3",
            "fabric_api_dependency" to ">=0.166.0"))
        filter { line: String ->
            when {
                line.contains("\"java\": \">=21\"") -> line.replace("\">=21\"", "\">=25\"")
                line.contains("\"fabricloader\": \">=0.19.3\"") -> line.replace("0.19.3", "0.20.1")
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
