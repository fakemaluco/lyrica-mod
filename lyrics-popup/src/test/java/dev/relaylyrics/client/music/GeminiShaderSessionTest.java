package dev.relaylyrics.client.music;

import dev.relaylyrics.client.ClientPreferences;
import dev.relaylyrics.protocol.music.MusicCapabilities;
import dev.relaylyrics.protocol.music.PlaybackSnapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.nio.file.Files;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import static org.assertj.core.api.Assertions.*;

class GeminiShaderSessionTest {
    @TempDir Path temp;
    @Test void acceptsDottedCredentialsAndRejectsHeaderInjection() throws Exception {
        var store = new GeminiShaderStore(temp);
        String fake = "AQ.fixture_credential.with-dot-123456789";
        assertThat(GeminiShaderStore.validKey(fake)).isTrue();
        store.saveKey(fake);
        assertThat(new GeminiShaderStore(temp).configured()).isTrue();
        assertThat(GeminiShaderStore.validKey("fixture_credential\r\nInjected: value")).isFalse();
        assertThat(GeminiShaderStore.validKey("fixture credential with spaces")).isFalse();
    }

    @Test void disabledExperimentDoesNoGenerationAndStableTrackGeneratesOnlyOnce() throws Exception {
        var p = new ClientPreferences(); var nanos = new AtomicLong(); var calls = new AtomicInteger();
        try (var session = session(p, nanos, (key, model, track, direction) -> { calls.incrementAndGet(); return spec(track.title()); })) {
            assertThat(session.saveKey("fixture_key_1234567890")).isTrue();
            session.tick(song("A", true), 0, 0); nanos.set(3_000_000_000L); session.tick(song("A", true), 3_000, 0);
            assertThat(calls).hasValue(0); assertThat(session.status()).isEqualTo("disabled");
            p.setGeminiShaderEnabled(true); session.tick(song("A", true), 3_000, 0);
            assertThat(session.pending()).isNull();
            nanos.addAndGet(3_000_000_000L); session.tick(song("A", true), 6_000, 0); finish(session);
            for (int i = 0; i < 30; i++) { nanos.addAndGet(500_000_000L); session.tick(song("A", true), 6_000 + i * 500, 0); }
            assertThat(calls).hasValue(1); assertThat(session.shaderName()).isEqualTo("A");
            assertThat(session.status()).isEqualTo("ready");
        }
    }
    @Test void lateOldSongResponseCannotReplaceTheNewSongAndChangingTracksRespectsCooldown() throws Exception {
        var p = new ClientPreferences(); p.setGeminiShaderEnabled(true);
        var nanos = new AtomicLong(); var started = new CountDownLatch(1); var release = new CountDownLatch(1);
        var calls = new AtomicInteger();
        try (var session = session(p, nanos, (key, model, track, direction) -> {
            calls.incrementAndGet();
            if (track.title().equals("A")) {
                started.countDown();
                boolean done = false;
                while (!done) { try { done = release.await(3, TimeUnit.SECONDS); } catch (InterruptedException ignored) { } }
            }
            return spec(track.title());
        })) {
            session.saveKey("fixture_key_1234567890"); session.tick(song("A", true), 0, 0);
            nanos.set(3_000_000_000L); session.tick(song("A", true), 3_000, 0);
            assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
            session.tick(song("B", true), 0, 0); release.countDown();
            nanos.set(6_000_000_000L); session.tick(song("B", true), 3_000, 0); finish(session);
            assertThat(session.shaderName()).isEmpty(); assertThat(session.status()).isEqualTo("cooldown"); assertThat(calls).hasValue(1);
            nanos.set(25_000_000_000L); session.tick(song("B", true), 22_000, 0); finish(session);
            assertThat(session.shaderName()).isEqualTo("B"); assertThat(calls).hasValue(2);
        } finally { release.countDown(); }
    }
    @Test void cachedShaderCanBeUsedWithoutSpendingAnotherRequestAndKeyRemovalDisablesEverything() throws Exception {
        var p = new ClientPreferences(); p.setGeminiShaderEnabled(true); var nanos = new AtomicLong(); String id;
        try (var first = session(p, nanos, (key, model, track, direction) -> spec("Cached song"))) {
            first.saveKey("fixture_key_1234567890"); first.tick(song("A", true), 0, 0);
            nanos.set(3_000_000_000L); first.tick(song("A", true), 3_000, 0); finish(first);
            id = first.activeKey().substring(0, 64);
        }
        new GeminiShaderStore(temp).put(id, spec("Cached song"));
        var calls = new AtomicInteger();
        try (var second = session(p, nanos, (key, model, track, direction) -> { calls.incrementAndGet(); throw new AssertionError("Cache missed"); })) {
            second.tick(song("A", true), 3_000, 0); nanos.addAndGet(3_000_000_000L); second.tick(song("A", true), 6_000, 0); finish(second);
            assertThat(second.status()).isEqualTo("cached"); assertThat(calls).hasValue(0);
            second.clearCache(); assertThat(second.keyConfigured()).isTrue(); assertThat(second.shaderName()).isEqualTo("Cached song");
            second.removeKey(); assertThat(p.isGeminiShaderEnabled()).isFalse(); assertThat(second.keyConfigured()).isFalse();
            assertThat(second.activeKey()).isEmpty(); assertThat(Files.exists(temp.resolve("api-key.txt"))).isFalse();
        }
    }
    @Test void manualModeDoesNotQueryOnSongChangesAndApiFailureDoesNotRetryEachTick() throws Exception {
        var p = new ClientPreferences(); p.setGeminiShaderEnabled(true); p.setGeminiShaderAutomatic(false);
        var nanos = new AtomicLong(); var calls = new AtomicInteger();
        try (var session = session(p, nanos, (key, model, track, direction) -> { calls.incrementAndGet(); throw new GeminiShaderClient.Failure("QUOTA"); })) {
            session.saveKey("fixture_key_1234567890"); session.tick(song("A", true), 0, 0);
            nanos.set(3_000_000_000L); session.tick(song("A", true), 3_000, 0); assertThat(calls).hasValue(0);
            assertThat(session.status()).isEqualTo("manual"); session.regenerate(); session.tick(song("A", false), 3_000, 0); finish(session);
            assertThat(session.status()).isEqualTo("quota");
            for (int i = 0; i < 20; i++) { nanos.addAndGet(1_000_000_000L); session.tick(song("A", true), 3_000, 0); }
            assertThat(calls).hasValue(1); p.setGeminiShaderEnabled(false); session.tick(song("A", true), 3_000, 0);
            assertThat(session.activeKey()).isEmpty();
        }
    }
    @Test void audioSwitchZerosGpuSignalsEvenWhenGeneratedCodeUsesThem() throws Exception {
        var p=new ClientPreferences();p.setGeminiShaderEnabled(true);var nanos=new AtomicLong();
        try(var session=session(p,nanos,(key,model,track,direction)->spec("Audio"))){
            session.saveKey("fixture_key_1234567890");session.tick(song("A",true),0,0);
            nanos.set(3_000_000_000L);session.tick(song("A",true),3000,0);finish(session);
            assertThat(session.worldEffect().energy()).isGreaterThan(0);
            p.setGeminiArtAudio(false);var effect=session.worldEffect();
            assertThat(effect.energy()).isZero();assertThat(effect.beat()).isZero();assertThat(effect.bass()).isZero();assertThat(effect.mid()).isZero();assertThat(effect.treble()).isZero();
        }
    }
    @Test void regenerationChangesTheCommissionAndIncludesRecentLookWithoutRequestingEveryTick() throws Exception {
        var p=new ClientPreferences();p.setGeminiShaderEnabled(true);var nanos=new AtomicLong();
        var directions=new java.util.concurrent.CopyOnWriteArrayList<String>();
        try(var session=session(p,nanos,(key,model,track,direction)->{directions.add(direction);return spec("Unique atmospheric world");})){
            session.saveKey("fixture_key_1234567890");session.tick(song("A",true),0,0);
            nanos.set(3_000_000_000L);session.tick(song("A",true),3000,0);finish(session);
            for(int i=0;i<8;i++)session.tick(song("A",true),3000,0);
            assertThat(directions).hasSize(1);
            nanos.set(30_000_000_000L);session.regenerate();session.tick(song("A",true),3000,0);finish(session);
            assertThat(directions).hasSize(2);assertThat(directions.get(1)).isNotEqualTo(directions.get(0)).contains("Unique atmospheric world");
            for(int i=0;i<8;i++)session.tick(song("A",true),3000,0);
            assertThat(directions).hasSize(2);
        }
    }
    @Test void credentialStaysSeparateAndCacheIsBoundedAndRejectsCorruptEntries() throws Exception {
        var store = new GeminiShaderStore(temp); store.saveKey("fixture_key_1234567890");
        for (int i = 0; i < 67; i++) store.put(GeminiShaderSpec.hash("song " + i), spec("Visual " + i));
        try (var files = Files.list(temp.resolve("cache"))) { assertThat(files.count()).isEqualTo(64); }
        String id = GeminiShaderSpec.hash("corrupt"); Files.writeString(temp.resolve("cache").resolve(id + ".json"), "not JSON");
        assertThat(store.read(id)).isNull(); store.clear();
        assertThat(store.configured()).isTrue(); assertThat(new GeminiShaderStore(temp).configured()).isTrue();
        try (var files = Files.list(temp.resolve("cache"))) { assertThat(files.count()).isZero(); }
        assertThat(store.read("../api-key")).isNull();
    }
    private GeminiShaderSession session(ClientPreferences p, AtomicLong nanos, GeminiShaderSession.Generator generator) {
        return new GeminiShaderSession(p, temp, generator, nanos::get, Runnable::run);
    }
    private static void finish(GeminiShaderSession session) throws Exception {
        var pending = session.pending(); if (pending != null) pending.get(3, TimeUnit.SECONDS);
    }
    private static GeminiShaderSpec spec(String title) { return new GeminiShaderSpec(title, "Fixture", GeminiShaderClientTest.CODE); }
    static PlaybackSnapshot song(String name, boolean playing) {
        return new PlaybackSnapshot(name, "Artist", "Album", null, "spotify:track:" + name, "Fixture", 3_000, 120_000,
                playing, .5, false, "off", false, .4, .2, 120, .5, new double[]{.3, .4, .2}, Instant.now(), MusicCapabilities.unavailable());
    }
}
