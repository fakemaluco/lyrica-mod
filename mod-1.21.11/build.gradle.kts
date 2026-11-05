plugins {
    id("fabric-loom") version "1.17.17"
    `maven-publish`
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
    withSourcesJar()
}

base {
    archivesName.set("lyrica-1.21.11")
}

val minecraftVersion = "1.21.11"
val yarnMappings = "1.21.11+build.6"
val fabricApiVersion = "0.141.2+1.21.11"

val hostOs = System.getProperty("os.name", "").lowercase()
val hostArch = when (System.getProperty("os.arch", "").lowercase()) {
    "amd64", "x86_64" -> "x86_64"
    "aarch64", "arm64" -> "arm64"
    else -> System.getProperty("os.arch", "unknown").lowercase()
        .replace(Regex("[^a-z0-9_-]"), "_")
}
val hostPlatform = when {
    hostOs.contains("win") -> "windows-$hostArch"
    hostOs.contains("mac") || hostOs.contains("darwin") -> "macos-$hostArch"
    else -> "linux-$hostArch"
}
val bridgeFileName = if (hostOs.contains("win")) "fandroid-media-bridge.exe" else "fandroid-media-bridge"
val bridgeExecutable = rootProject.layout.projectDirectory.file("media-bridge/target/release/$bridgeFileName")
val bundledBridgeResources = layout.buildDirectory.dir("generated/bundledMediaBridge")
val sharedClientSources = layout.buildDirectory.dir("generated/sharedClientJava")

val stageBundledMediaBridge by tasks.registering(Copy::class) {
    from(bridgeExecutable)
    into(bundledBridgeResources.map { it.dir("lyrica/native/$hostPlatform") })
    onlyIf { bridgeExecutable.asFile.isFile }
}

val prepareSharedClientJava by tasks.registering(Sync::class) {
    from(rootProject.file("mod/src/client/java"))
    exclude(
        "dev/fandroid/client/PlatformKeyBindings.java",
        "dev/fandroid/client/ui/GuiTransform.java",
        "dev/fandroid/client/ui/PlatformTextStyles.java",
        "dev/fandroid/client/ui/PlatformUiTextures.java",
        "dev/fandroid/client/ui/PlatformGalleryTextures.java",
        "dev/fandroid/client/ui/PlatformCursor.java",
        "dev/fandroid/client/ui/FanDroidScreenBase.java",
        "dev/fandroid/client/ui/LyricsBloomRenderer.java",
        "dev/fandroid/client/music/PlatformTextures.java"
    )
    into(sharedClientSources)
}

sourceSets.main {
    java.setSrcDirs(listOf(rootProject.file("mod/src/main/java")))
    resources.setSrcDirs(listOf(rootProject.file("mod/src/main/resources")))
}

sourceSets.test {
    java.setSrcDirs(listOf(rootProject.file("mod/src/test/java")))
}

dependencies {
    minecraft("com.mojang:minecraft:$minecraftVersion")
    mappings("net.fabricmc:yarn:$yarnMappings:v2")
    modImplementation("net.fabricmc:fabric-loader:${property("loader_version")}")
    modImplementation("net.fabricmc.fabric-api:fabric-api:$fabricApiVersion")

    implementation(project(":shared-protocol"))
    include(project(":shared-protocol"))
    implementation("com.fasterxml.jackson.core:jackson-annotations:2.19.2")
    include("com.fasterxml.jackson.core:jackson-annotations:2.19.2")
    implementation("com.fasterxml.jackson.core:jackson-core:2.19.2")
    include("com.fasterxml.jackson.core:jackson-core:2.19.2")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.19.2")
    include("com.fasterxml.jackson.core:jackson-databind:2.19.2")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.19.2")
    include("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.19.2")
    implementation("org.bouncycastle:bcprov-jdk18on:1.84")
    include("org.bouncycastle:bcprov-jdk18on:1.84")
    implementation("org.xerial:sqlite-jdbc:3.50.3.0")
    include("org.xerial:sqlite-jdbc:3.50.3.0")

    modCompileOnly("de.maxhenkel.voicechat:voicechat-api:${property("voicechat_api_version")}")

    testImplementation(platform("org.junit:junit-bom:5.13.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.assertj:assertj-core:3.27.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

loom {
    splitEnvironmentSourceSets()
    mods {
        create("lyrica") {
            sourceSet(sourceSets.main.get())
            sourceSet(sourceSets.named("client").get())
        }
    }
}

sourceSets.named("client") {
    java.srcDir(sharedClientSources)
}

tasks.named("compileClientJava") {
    dependsOn(prepareSharedClientJava)
}

tasks.named("sourcesJar") {
    dependsOn(prepareSharedClientJava)
}

tasks.processResources {
    dependsOn(stageBundledMediaBridge)
    from(bundledBridgeResources)
    inputs.property("version", project.version)
    inputs.property("minecraft_dependency", "1.21.11")
    inputs.property("fabric_api_dependency", ">=0.141.2")
    inputs.property("voicechat_dependency", ">=1.21.11-2.6.21")
    filesMatching("fabric.mod.json") {
        expand(mapOf(
            "version" to project.version,
            "minecraft_dependency" to "1.21.11",
            "fabric_api_dependency" to ">=0.141.2",
            "voicechat_dependency" to ">=1.21.11-2.6.21"
        ))
        filter { line: String ->
            if (line.contains("\"environment\""))
                "$line\n  \"mixins\": [\"fandroid.glow.mixins.json\"],"
            else line
        }
    }
}

tasks.withType<Jar>().configureEach {
    from(rootProject.file("LICENSE")) {
        rename { "LICENSE_fandroid" }
    }
    from(rootProject.file("THIRD_PARTY_NOTICES.md"))
}
