plugins {
    id("fabric-loom") version "1.17.17"
    `maven-publish`
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
    withSourcesJar()
}

base {
    archivesName.set("lyrica-1.21.1")
}
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
val generatedClientSources = layout.buildDirectory.dir("generated/standaloneClientJava")

val stageBundledMediaBridge by tasks.registering(Copy::class) {
    from(bridgeExecutable) {
        rename { standaloneBridgeName }
    }
    into(bundledBridgeResources.map { it.dir("relaylyrics/native/$hostPlatform") })
    onlyIf { bridgeExecutable.asFile.isFile }
}

val prepareStandaloneClientJava by tasks.registering(Sync::class) {
    from(layout.projectDirectory.dir("src/version/1.21.1/java"))
    from(rootProject.file("mod/src/main/java")) {
        include("dev/fandroid/client/music/LyricsAnimation.java")
        include("dev/fandroid/client/music/LyricsTiming.java")
        include("dev/fandroid/client/music/PopupResizeGesture.java")
        include("dev/fandroid/client/music/PopupSizeProfile.java")
        include("dev/fandroid/client/music/PopupAlignment.java")
        include("dev/fandroid/client/music/PopupHudTargets.java")
        include("dev/fandroid/client/music/KawarpRaster.java")
        include("dev/fandroid/client/music/GlassButtonRaster.java")
        include("dev/fandroid/client/music/ResponsiveLyricsLayout.java")
        include("dev/fandroid/client/music/CanvasPopupLayout.java")
        include("dev/fandroid/client/music/AdaptivePopupLayout.java")
        include("dev/fandroid/client/music/PopupDockLayout.java")
        include("dev/fandroid/client/music/PopupDockMotion.java")
        include("dev/fandroid/client/music/IslandAudioMotion.java")
        include("dev/fandroid/client/music/IslandLyrics.java")
        include("dev/fandroid/client/music/IslandWaitingMotion.java")
        include("dev/fandroid/client/music/RoundedVideoRaster.java")
        include("dev/fandroid/client/music/UnavailableLyricsTransition.java")
        include("dev/fandroid/client/music/LyricsEffectRaster.java")
        include("dev/fandroid/client/music/LyricsEffectCache.java")
        include("dev/fandroid/client/music/LyricsShadowKernel.java")
        include("dev/fandroid/client/music/LyricsRenderText.java")
        include("dev/fandroid/client/music/LyricsRomanizer.java")
        include("dev/fandroid/client/music/WorldLyricsPlan.java")
        include("dev/fandroid/client/music/WorldLyricsDirector.java")
        include("dev/fandroid/client/music/WorldLyricsPlacement.java")
        include("dev/fandroid/client/music/WorldLyricsMotion.java")
        include("dev/fandroid/client/music/WorldLyricsLayout.java")
        include("dev/fandroid/client/music/WorldLyricsExperience.java")
        include("dev/fandroid/client/music/WorldLyricsPresentation.java")
        include("dev/fandroid/client/music/WorldLyricsStage.java")
        include("dev/fandroid/client/music/WorldLyricsRecovery.java")
        include("dev/fandroid/client/music/WorldLyricsSpace.java")
        include("dev/fandroid/client/music/WorldLyricsLights.java")
        include("dev/fandroid/client/music/LyricsViewport.java")
        include("dev/fandroid/client/music/ManualLyricsScroll.java")
        include("dev/fandroid/client/music/SpringValue.java")
        filter { line: String -> line.replace("fandroid", "relaylyrics") }
    }
    from(rootProject.file("mod/src/client/java")) {
        include("dev/fandroid/client/music/**")
        include("dev/fandroid/client/ui/LyricsOverlay.java")
        include("dev/fandroid/client/ui/PopupIcons.java")
        include("dev/fandroid/client/ui/LiquidGlass.java")
        include("dev/fandroid/client/ui/LyricsBloomRenderer.java")
        include("dev/fandroid/client/ui/GuiTransform.java")
        include("dev/fandroid/client/ui/PlatformTextStyles.java")
        include("dev/fandroid/client/ui/RetroDraw.java")
        include("dev/fandroid/client/ui/RoundedGui.java")
        include("dev/fandroid/client/ui/FanDroidScreenBase.java")
        include("dev/fandroid/client/ui/SettingsButtonBase.java")
        include("dev/fandroid/client/ui/GoogleFontScreen.java")
        include("dev/fandroid/client/ui/SpotifyAccountScreen.java")
        include("dev/fandroid/client/ui/YouTubeMusicConnectionScreen.java")
        include("dev/fandroid/client/LyricsEffectsMode.java")
        include("dev/fandroid/client/LyricsFont.java")
        include("dev/fandroid/client/LyricsTextAlignment.java")
        include("dev/fandroid/client/LyricsFontWeight.java")
        include("dev/fandroid/client/LyricsGlowIntensity.java")
        include("dev/fandroid/client/LyricsTransitionSpeed.java")
        include("dev/fandroid/client/MusicSource.java")
        include("dev/fandroid/client/LyricsRenderType.java")
        include("dev/fandroid/client/GoogleFontManager.java")
        include("dev/fandroid/client/CompatibilityManager.java")
        include("dev/fandroid/client/ui/HudSafeArea.java")
        include("dev/fandroid/client/ui/PlatformCursor.java")
        filter { line: String -> line.replace("fandroid", "relaylyrics") }
    }
    from(rootProject.file("shared-protocol/src/main/java")) {
        include("dev/fandroid/protocol/ProtocolCodec.java")
        include("dev/fandroid/protocol/lyrics/**")
        include("dev/fandroid/protocol/music/**")
        filter { line: String -> line.replace("fandroid", "relaylyrics") }
    }
    into(generatedClientSources)
}

dependencies {
    modCompileOnly("maven.modrinth:jade:15.10.6+fabric") { isTransitive = false }
    modCompileOnly("dev.lambdaurora.lambdynamiclights:lambdynamiclights-api:4.8.11+1.21.1")
    implementation(project(":lyrics-engine"))
    include(project(":lyrics-engine"))
    minecraft("com.mojang:minecraft:${property("minecraft_version")}")
    mappings("net.fabricmc:yarn:${property("yarn_mappings")}:v2")
    modImplementation("net.fabricmc:fabric-loader:${property("loader_version")}")
    modImplementation("net.fabricmc.fabric-api:fabric-api:${property("fabric_api_version")}")
    modCompileOnly("com.terraformersmc:modmenu:11.0.4") { isTransitive = false }

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

sourceSets.named("client") {
    java.srcDir(generatedClientSources)
}

tasks.named("compileClientJava") { dependsOn(prepareStandaloneClientJava) }
tasks.named("sourcesJar") { dependsOn(prepareStandaloneClientJava) }

tasks.processResources {
    dependsOn(stageBundledMediaBridge)
    from(bundledBridgeResources)
    from(rootProject.file("mod/src/main/resources/assets/lyrica/shaders")) {
        into("assets/relaylyrics/shaders")
    }
    from(rootProject.file("mod/src/main/resources/assets/lyrica/textures/gui/lyrics")) {
        into("assets/lyrica/textures/gui/lyrics")
    }
    inputs.property("version", project.version)
    filesMatching("fabric.mod.json") {
        expand(mapOf("version" to project.version, "minecraft_dependency" to "1.21.1",
            "fabric_api_dependency" to ">=0.116.15"))
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
