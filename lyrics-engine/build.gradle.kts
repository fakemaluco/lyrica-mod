import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    `java-library`
    id("org.jetbrains.kotlin.jvm") version "2.4.20"
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
    withSourcesJar()
}
kotlin { compilerOptions.jvmTarget.set(JvmTarget.JVM_21) }

dependencies {
    // A complete decoder runtime is bundled; users do not need VLC, FFmpeg or Fabric Kotlin.
    // Like the native media bridge, release builds target the build host by default.
    val videoPlatform = providers.gradleProperty("videoPlatform").orElse(
        when {
            System.getProperty("os.name").lowercase().contains("win") -> "windows-x86_64"
            System.getProperty("os.name").lowercase().contains("mac") ->
                if (System.getProperty("os.arch").contains("aarch64")) "macosx-arm64" else "macosx-x86_64"
            else -> if (System.getProperty("os.arch").contains("aarch64")) "linux-arm64" else "linux-x86_64"
        }
    ).get()
    api("org.bytedeco:javacv:1.5.11") { isTransitive = false }
    api("org.bytedeco:javacpp:1.5.11")
    api("org.bytedeco:ffmpeg:7.1-1.5.11")
    runtimeOnly("org.bytedeco:javacpp:1.5.11:$videoPlatform")
    runtimeOnly("org.bytedeco:ffmpeg:7.1-1.5.11:$videoPlatform")
    api("com.google.code.gson:gson:2.14.0")
    api("com.squareup.okhttp3:okhttp:5.5.0")
    api("com.squareup.retrofit2:retrofit:3.0.0")
    api("com.squareup.retrofit2:converter-gson:3.0.0")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    api("javax.inject:javax.inject:1")
    implementation("com.atilika.kuromoji:kuromoji-ipadic:0.9.0")
    implementation("net.sf.kxml:kxml2:2.3.0")
    testImplementation("junit:junit:4.13.2")
    testRuntimeOnly("org.junit.vintage:junit-vintage-engine:5.13.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.13.4")
}

// Fabric's nested-JAR mechanism does not include transitive dependencies of a plain JVM
// module. Bundle the standalone provider runtime once, rather than requiring Fabric Kotlin.
tasks.jar {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) })
    exclude("META-INF/*.SF", "META-INF/*.RSA", "META-INF/*.DSA", "module-info.class")
    from("LICENSE") { into("META-INF/lyrica-engine") }
    from("UPSTREAM_NOTICES.md") { into("META-INF/lyrica-engine") }
    from("VIDEO_NOTICES.md") { into("META-INF/lyrica-engine") }
    from("licenses/video") { into("META-INF/lyrica-engine/licenses/video") }
}
