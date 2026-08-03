plugins {
    id("fabric-loom") version "1.17.17"
    `maven-publish`
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
    withSourcesJar()
}

base {
    archivesName.set("fandroid")
}

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

val stageBundledMediaBridge by tasks.registering(Copy::class) {
    description = "Stages the native media bridge built for this host inside the Fabric JAR"
    from(bridgeExecutable)
    into(bundledBridgeResources.map { it.dir("fandroid/native/$hostPlatform") })
    onlyIf {
        val available = bridgeExecutable.asFile.isFile
        if (!available) {
            logger.warn("Native media bridge was not bundled because {} does not exist; run cargo build --release first", bridgeExecutable.asFile)
        }
        available
    }
}

dependencies {
    minecraft("com.mojang:minecraft:${property("minecraft_version")}")
    mappings("net.fabricmc:yarn:${property("yarn_mappings")}:v2")
    modImplementation("net.fabricmc:fabric-loader:${property("loader_version")}")
    modImplementation("net.fabricmc.fabric-api:fabric-api:${property("fabric_api_version")}")

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
    modCompileOnly("gg.essential:essential-1.21.1-fabric:${property("essential_version")}") {
        isTransitive = false
    }

    testImplementation(platform("org.junit:junit-bom:5.13.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.assertj:assertj-core:3.27.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

loom {
    splitEnvironmentSourceSets()
    mods {
        create("fandroid") {
            sourceSet(sourceSets.main.get())
            sourceSet(sourceSets.named("client").get())
        }
    }
}

tasks.processResources {
    dependsOn(stageBundledMediaBridge)
    from(bundledBridgeResources)
    inputs.property("version", project.version)
    inputs.property("minecraft_dependency", "~1.21.1")
    inputs.property("fabric_api_dependency", ">=0.116.15")
    inputs.property("voicechat_dependency", ">=1.21.1-2.6.21")
    filesMatching("fabric.mod.json") {
        expand(mapOf(
            "version" to project.version,
            "minecraft_dependency" to "~1.21.1",
            "fabric_api_dependency" to ">=0.116.15",
            "voicechat_dependency" to ">=1.21.1-2.6.21"
        ))
    }
}

tasks.withType<Jar>().configureEach {
    from(rootProject.file("LICENSE")) {
        rename { "LICENSE_fandroid" }
    }
    from(rootProject.file("THIRD_PARTY_NOTICES.md"))
}
