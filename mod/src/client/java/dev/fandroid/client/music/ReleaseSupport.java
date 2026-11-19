package dev.fandroid.client.music;

import dev.fandroid.client.ClientPreferences;
import dev.fandroid.protocol.lyrics.LyricsDocument;
import dev.lyrica.engine.LyricaReleaseLookup;
import dev.lyrica.engine.LyricaReleaseLookup.Update;
import dev.lyrica.engine.LyricaUpdateInstaller;
import dev.fandroid.protocol.ProtocolCodec;
import net.fabricmc.loader.api.FabricLoader;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.concurrent.CompletableFuture;

/** No telemetry: a bounded GitHub check, and an explicit allowlist for copied diagnostics. */
public final class ReleaseSupport implements AutoCloseable {
    private final ClientPreferences preferences;
    private final String installed = version("lyrica"), minecraft = version("minecraft");
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL).build();
    private CompletableFuture<HttpResponse<String>> pending;
    private String state = "idle";
    private final java.util.Set<String> notified = new java.util.HashSet<>();
    private Update available;
    private CompletableFuture<Boolean> installation;
    private long nextCheck, lastCheck;
    private boolean closed, requestedPrereleases;
    public ReleaseSupport(ClientPreferences preferences) { this.preferences = preferences; }
    public String status() { return state; }
    public String availableVersion() { return available == null ? "" : available.version(); }
    public String installedVersion() { return installed; }
    public Update update() { return available; }
    public Update takeNotification() {
        if (available == null || !state.equals("available") || preferences.isUpdateDismissed(available.version())
                || !notified.add(available.version())) return null;
        return available;
    }
    public void dismissUpdate(String version) {
        if (preferences.dismissUpdateVersion(version)) preferences.save();
    }
    public URI releasePage() {
        return available == null ? URI.create("https://github.com/fakemaluco/lyrica-mod/releases") : available.page();
    }
    public String installationStatus() {
        if (installation == null) return "ready";
        if (!installation.isDone()) return "downloading";
        return installation.getNow(false) ? "queued" : "failed";
    }
    public void schedule(Update update) {
        if (closed || update == null || !update.equals(available) || !update.installable()) return;
        String current = installationStatus();
        if (current.equals("queued") || current.equals("downloading")) return;
        installation = CompletableFuture.supplyAsync(() -> {
            try { prepare(update); return true; }
            catch (Exception error) { return false; }
        });
    }

    public boolean canSchedule(Update update) {
        return update != null && update.installable() && installedJar() != null;
    }

    private static Path installedJar() {
        var loader = FabricLoader.getInstance();
        return loader.getModContainer("lyrica").map(mod -> {
            var origin = mod.getOrigin();
            if (origin.getKind() != net.fabricmc.loader.api.metadata.ModOrigin.Kind.PATH
                    || origin.getPaths().size() != 1) return null;
            Path jar = origin.getPaths().getFirst().toAbsolutePath().normalize();
            Path mods = loader.getGameDir().resolve("mods").toAbsolutePath().normalize();
            return mods.equals(jar.getParent()) && jar.getFileName().toString().endsWith(".jar")
                    && Files.isRegularFile(jar, java.nio.file.LinkOption.NOFOLLOW_LINKS) ? jar : null;
        }).orElse(null);
    }

    private void prepare(Update update) throws Exception {
        Path target = installedJar();
        if (target == null) throw new IllegalStateException("No local Lyrica JAR");
        Path root = Files.createDirectories(FabricLoader.getInstance().getGameDir().resolve(".lyrica-updates"));
        Path staging = Files.createTempDirectory(root, "update-");
        Path downloaded = staging.resolve("download.jar");
        Path helper = staging.resolve("installer.jar");
        boolean started = false;
        try {
            String oldHash = LyricaUpdateInstaller.hash(target);
            var request = HttpRequest.newBuilder(update.asset()).timeout(Duration.ofMinutes(4))
                    .header("User-Agent", "Lyrica-update-check").GET().build();
            var response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (var input = response.body()) {
                String host = response.uri().getHost();
                if (response.statusCode() != 200 || !("github.com".equals(host)
                        || "release-assets.githubusercontent.com".equals(host)
                        || "objects.githubusercontent.com".equals(host))) throw new IllegalStateException("Unexpected download");
                try (var output = Files.newOutputStream(downloaded)) {
                    byte[] buffer = new byte[32 * 1024];
                    long total = 0;
                    for (int read; (read = input.read(buffer)) != -1;) {
                        total += read;
                        if (total > update.size()) throw new IllegalStateException("Update download too large");
                        output.write(buffer, 0, read);
                    }
                    if (total != update.size()) throw new IllegalStateException("Incomplete update");
                }
            }
            if (!LyricaUpdateInstaller.hash(downloaded).equals(update.sha256()))
                throw new IllegalStateException("Update checksum differs");
            try (var jar = new JarFile(downloaded.toFile());
                 var input = jar.getInputStream(jar.getJarEntry("fabric.mod.json"))) {
                var manifest = ProtocolCodec.mapper().readTree(input.readNBytes(64 * 1024));
                if (!manifest.path("id").asText().equals("lyrica")
                        || !manifest.path("version").asText().equals(update.version())
                        || !manifest.path("depends").path("minecraft").asText().equals(minecraft))
                    throw new IllegalStateException("Unexpected mod metadata");
            }
            String entry = LyricaUpdateInstaller.class.getName().replace('.', '/') + ".class";
            try (var stream = LyricaUpdateInstaller.class.getResourceAsStream("/" + entry);
                 var archive = new JarOutputStream(Files.newOutputStream(helper))) {
                if (stream == null) throw new IllegalStateException("Missing installer");
                archive.putNextEntry(new JarEntry(entry));
                stream.transferTo(archive);
                archive.closeEntry();
            }
            Path bin = Path.of(System.getProperty("java.home"), "bin");
            Path java = bin.resolve(Files.isRegularFile(bin.resolve("javaw.exe")) ? "javaw.exe" : "java");
            var current = ProcessHandle.current();
            new ProcessBuilder(java.toString(), "-cp", helper.toString(), LyricaUpdateInstaller.class.getName(),
                    Long.toString(current.pid()), current.info().startInstant().orElseThrow().toString(),
                    downloaded.toString(), target.toString(), update.sha256(), oldHash)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            started = true;
        } finally {
            if (!started) {
                Files.deleteIfExists(downloaded);
                Files.deleteIfExists(helper);
                Files.deleteIfExists(staging);
            }
        }
    }
    public void check() {
        if (closed || pending != null || System.currentTimeMillis() - lastCheck < 30_000) return;
        state = "checking";
        lastCheck = System.currentTimeMillis();
        requestedPrereleases = preferences.isUpdatePrereleases();
        nextCheck = System.currentTimeMillis() + Duration.ofHours(6).toMillis();
        var request = HttpRequest.newBuilder(URI.create("https://api.github.com/repos/fakemaluco/lyrica-mod/releases?per_page=30"))
                .timeout(Duration.ofSeconds(20)).header("Accept", "application/vnd.github+json")
                .header("User-Agent", "Lyrica-update-check").GET().build();
        pending = http.sendAsync(request, HttpResponse.BodyHandlers.ofString());
    }
    public void tick() {
        if (closed) return;
        if (requestedPrereleases != preferences.isUpdatePrereleases()) {
            if (pending != null) pending.cancel(true);
            pending = null; available = null; state = "idle"; nextCheck = 0;
            requestedPrereleases = preferences.isUpdatePrereleases();
        }
        if (pending != null && pending.isDone()) {
            try {
                var response = pending.join();
                if (response.statusCode() != 200 || response.body().length() > 2_000_000) throw new IllegalStateException();
                available = LyricaReleaseLookup.find(response.body(), installed, minecraft, requestedPrereleases);
                state = available == null ? "current" : "available";
            } catch (Exception ignored) { state = "failed"; }
            pending = null;
        }
        if (preferences.isUpdateChecks() && System.currentTimeMillis() >= nextCheck) check();
    }
    private static String version(String id) {
        return FabricLoader.getInstance().getModContainer(id).map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("unknown");
    }
    public String diagnosticReport(ClientPreferences p, LyricsDocument document, boolean bridgeAlive) {
        StringBuilder report = new StringBuilder("Lyrica diagnostics\n");
        report.append("Lyrica: ").append(installed).append("\nMinecraft: ").append(minecraft)
                .append("\nFabric Loader: ").append(version("fabricloader"))
                .append("\nJava: ").append(Runtime.version())
                .append("\nOS: ").append(System.getProperty("os.name")).append(" / ").append(System.getProperty("os.arch"))
                .append("\nMusic source: ").append(p.getMusicSource().name())
                .append("\nBridge running: ").append(bridgeAlive)
                .append("\nAnimations: ").append(p.isLyricsAnimations()).append("\nVideos: ").append(p.isMotionVideos())
                .append("\nLyrics delay (ms): ").append(p.getLyricsDelayMillis())
                .append("\nSources: ").append(p.getLyricsSources()).append("\nTranslation: ")
                .append(p.getTranslationProvider()).append(" / ").append(p.getTranslationLanguage())
                .append(" / automatic=").append(p.isAutomaticTranslation());
        if (document != null) report.append("\nLyrics: ").append(document.metadata().source().name())
                .append(" / ").append(document.metadata().syncMode().name()).append(" / lines=").append(document.lines().size());
        report.append("\nCompatibility mods:");
        for (String id : new String[]{"sodium", "iris", "immediatelyfast", "jade", "lambdynlights", "ji-afk-cinematic", "modmenu"}) {
            if (FabricLoader.getInstance().isModLoaded(id)) report.append("\n  ").append(id).append(": ").append(version(id));
        }
        return report.toString();
    }
    @Override public void close() { closed = true; if (pending != null) pending.cancel(true); http.shutdown(); }
}
