package dev.fandroid.client.music;

import dev.fandroid.client.ClientPreferences;
import dev.fandroid.protocol.lyrics.LyricsDocument;
import dev.lyrica.engine.LyricaReleaseLookup;
import net.fabricmc.loader.api.FabricLoader;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/** No telemetry: a bounded GitHub check, and an explicit allowlist for copied diagnostics. */
public final class ReleaseSupport implements AutoCloseable {
    private final ClientPreferences preferences;
    private final String installed = version("lyrica"), minecraft = version("minecraft");
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private CompletableFuture<HttpResponse<String>> pending;
    private String state = "idle", available = "", notified = "";
    private long nextCheck, lastCheck;
    private boolean closed, requestedPrereleases;
    public ReleaseSupport(ClientPreferences preferences) { this.preferences = preferences; }
    public String status() { return state; }
    public String availableVersion() { return available; }
    public String takeNotification() {
        if (!state.equals("available") || available.equals(notified)) return "";
        notified = available; return available;
    }
    public URI releasePage() {
        return URI.create("https://github.com/fakemaluco/lyrica-mod/releases" +
                (available.isEmpty() ? "" : "/tag/v" + available));
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
            pending = null; available = ""; state = "idle"; nextCheck = 0;
            requestedPrereleases = preferences.isUpdatePrereleases();
        }
        if (pending != null && pending.isDone()) {
            try {
                var response = pending.join();
                if (response.statusCode() != 200 || response.body().length() > 2_000_000) throw new IllegalStateException();
                available = LyricaReleaseLookup.newest(response.body(), installed, minecraft, requestedPrereleases);
                state = available.isEmpty() ? "current" : "available";
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
