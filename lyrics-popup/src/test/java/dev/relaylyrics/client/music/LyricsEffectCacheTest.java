package dev.relaylyrics.client.music;

import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class LyricsEffectCacheTest {
    private static LyricsEffectRaster.Mask mask() {
        return new LyricsEffectRaster.Mask(2, 2, 0, 0, 1, new byte[]{0, (byte) 255, (byte) 255, 0});
    }

    @Test
    void missesPrepareOutsideRequestAndNearbyRadiiReuseOneTexture() {
        AtomicInteger captures = new AtomicInteger();
        AtomicInteger uploads = new AtomicInteger();
        List<Integer> released = new ArrayList<>();
        try (LyricsEffectCache<String, Integer> cache = new LyricsEffectCache<>(key -> {
            captures.incrementAndGet();
            return mask();
        }, pixels -> uploads.incrementAndGet(), released::add, 1_000_000, Runnable::run)) {
            assertThat(cache.request("sung glyph", 1.01)).isNull();
            assertThat(cache.request("sung glyph", 1.10)).isNull();
            assertThat(cache.pendingCount()).isEqualTo(1);
            assertThat(captures).hasValue(0);
            assertThat(uploads).hasValue(0);
            cache.tick();
            var first = cache.request("sung glyph", 1.01);
            assertThat(first).isNotNull();
            assertThat(cache.request("sung glyph", 1.10)).isSameAs(first);
            assertThat(captures).hasValue(1);
            assertThat(uploads).hasValue(1);
            assertThat(cache.pendingCount()).isZero();
            assertThat(cache.bytesUsed()).isGreaterThan(0).isLessThanOrEqualTo(1_000_000);
        }
        assertThat(released).containsExactly(1);
    }

    @Test
    void captureAndUploadStayOnCallerWhileOnlyBlurRunsOnExecutor() {
        QueuedExecutor worker = new QueuedExecutor();
        Thread caller = Thread.currentThread();
        List<Thread> capturedThreads = new ArrayList<>();
        List<Thread> uploadedThreads = new ArrayList<>();
        try (LyricsEffectCache<String, String> cache = new LyricsEffectCache<>(key -> {
            capturedThreads.add(Thread.currentThread());
            return mask();
        }, pixels -> {
            uploadedThreads.add(Thread.currentThread());
            return "texture";
        }, texture -> {}, 1_000_000, worker)) {
            cache.request("word", 2);
            cache.tick();
            assertThat(capturedThreads).containsExactly(caller);
            assertThat(uploadedThreads).isEmpty();
            assertThat(cache.request("word", 2)).isNull();
            worker.runAll();
            assertThat(uploadedThreads).isEmpty();
            cache.tick();
            assertThat(uploadedThreads).containsExactly(caller);
            assertThat(cache.request("word", 2)).isNotNull();
        }
    }

    @Test
    void captureAndUploadBudgetsPreventLargeBursts() {
        AtomicInteger captures = new AtomicInteger();
        AtomicInteger uploads = new AtomicInteger();
        try (LyricsEffectCache<String, Integer> cache = new LyricsEffectCache<>(key -> {
            captures.incrementAndGet();
            return mask();
        }, pixels -> uploads.incrementAndGet(), texture -> {}, 1_000_000, Runnable::run)) {
            for (int key = 0; key < 10; key++) {
                cache.request("word" + key, 0);
                cache.request("word" + key, 1);
                cache.request("word" + key, 2);
            }
            cache.tick();
            assertThat(captures).hasValue(8);
            assertThat(uploads).hasValue(16);
            assertThat(cache.pendingCount()).isEqualTo(14);
            cache.tick();
            assertThat(captures).hasValue(10);
            assertThat(uploads).hasValue(30);
            assertThat(cache.pendingCount()).isZero();
        }
    }

    @Test
    void temporarilyMissingFontsRetryOnLaterTicksAndStopAfterThreeAttempts() {
        AtomicInteger captures = new AtomicInteger();
        try (LyricsEffectCache<String, String> cache = new LyricsEffectCache<>(key -> {
            int attempt = captures.incrementAndGet();
            return key.equals("available") && attempt == 3 ? mask() : null;
        }, pixels -> "texture", texture -> {}, 1_000_000, Runnable::run)) {
            cache.request("available", 0);
            cache.tick();
            assertThat(captures).hasValue(1);
            cache.tick();
            assertThat(captures).hasValue(2);
            cache.tick();
            assertThat(cache.request("available", 0)).isNotNull();
            cache.request("missing", 0);
            for (int i = 0; i < 5; i++) cache.tick();
            assertThat(captures).hasValue(6);
            assertThat(cache.request("missing", 0)).isNull();
            assertThat(cache.pendingCount()).isZero();
        }
    }

    @Test
    void clearKeepsTexturesAliveForThreeTicksAndCancelsOldJobs() {
        List<String> released = new ArrayList<>();
        QueuedExecutor worker = new QueuedExecutor();
        try (LyricsEffectCache<String, String> cache = new LyricsEffectCache<>(key -> mask(),
                pixels -> "uploaded", released::add, 1_000_000, worker)) {
            cache.request("ready", 0);
            cache.tick();
            worker.runAll();
            cache.tick();
            assertThat(cache.request("ready", 0)).isNotNull();
            cache.request("not ready", 1);
            cache.tick();
            cache.clear();
            assertThat(released).isEmpty();
            assertThat(cache.pendingCount()).isZero();
            assertThat(cache.bytesUsed()).isEqualTo(16);
            worker.runAll();
            cache.tick();
            cache.tick();
            assertThat(released).isEmpty();
            cache.tick();
            assertThat(released).containsExactly("uploaded");
            assertThat(cache.bytesUsed()).isZero();
        }
    }

    @Test
    void boundedMemoryAndSourceSlotsDropWorkThatCannotFit() {
        AtomicInteger uploads = new AtomicInteger();
        try (LyricsEffectCache<String, Integer> cache = new LyricsEffectCache<>(key -> mask(),
                pixels -> uploads.incrementAndGet(), texture -> {}, 80, Runnable::run)) {
            for (int i = 0; i < 300; i++) cache.request("word" + i, 0);
            assertThat(cache.pendingCount()).isEqualTo(LyricsEffectCache.MAX_SOURCES);
            for (int i = 0; i < 40; i++) {
                cache.tick();
                assertThat(cache.bytesUsed()).isLessThanOrEqualTo(80);
            }
            cache.clear();
            for (int i = 0; i < 3; i++) cache.tick();
            assertThat(cache.bytesUsed()).isZero();
            cache.request("too large", 9);
            cache.tick();
            assertThat(cache.pendingCount()).isZero();
            assertThat(cache.request("too large", 9)).isNull();
        }
    }

    @Test
    void pendingVariantsAreBoundedAndRadiusClamps() {
        QueuedExecutor worker = new QueuedExecutor();
        try (LyricsEffectCache<String, String> cache = new LyricsEffectCache<>(key -> mask(),
                pixels -> "texture", texture -> {}, 1_000_000, worker)) {
            for (int i = 0; i < 20; i++) {
                for (int radius = 0; radius <= 36; radius++) cache.request("word" + i, radius / 4.0);
            }
            assertThat(cache.pendingCount()).isEqualTo(LyricsEffectCache.MAX_VARIANTS);
            cache.clear();
            cache.request("same", 9);
            cache.request("same", 999);
            cache.request("same", Double.POSITIVE_INFINITY);
            assertThat(cache.pendingCount()).isEqualTo(1);
        }
    }

    @Test
    void closeReleasesImmediatelyAndDoesNotUploadCancelledMasks() {
        List<String> released = new ArrayList<>();
        QueuedExecutor worker = new QueuedExecutor();
        AtomicInteger uploads = new AtomicInteger();
        LyricsEffectCache<String, String> cache = new LyricsEffectCache<>(key -> mask(), pixels -> {
            uploads.incrementAndGet();
            return "texture";
        }, released::add, 1_000_000, worker);
        cache.request("ready", 0);
        cache.tick();
        worker.runAll();
        cache.tick();
        cache.request("pending", 1);
        cache.tick();
        cache.close();
        worker.runAll();
        cache.tick();
        assertThat(released).containsExactly("texture");
        assertThat(uploads).hasValue(1);
        assertThat(cache.bytesUsed()).isZero();
        assertThat(cache.pendingCount()).isZero();
        assertThat(cache.request("closed", 0)).isNull();
    }

    @Test
    void changingRadiusUsesNearestPreparedSurfaceWithItsActualBounds() {
        QueuedExecutor worker = new QueuedExecutor();
        AtomicInteger captures = new AtomicInteger();
        AtomicInteger uploads = new AtomicInteger();
        try (LyricsEffectCache<String, Integer> cache = new LyricsEffectCache<>(key -> {
            captures.incrementAndGet();
            return mask();
        }, pixels -> uploads.incrementAndGet(), texture -> {}, 1_000_000, worker)) {
            cache.request("word", 1);
            cache.tick();
            worker.runAll();
            cache.tick();
            var small = cache.request("word", 1);
            assertThat(cache.request("word", 2)).isSameAs(small);
            assertThat(cache.request("word", 2)).isSameAs(small);
            cache.tick();
            worker.runAll();
            cache.tick();
            var large = cache.request("word", 2);
            assertThat(large).isNotSameAs(small);
            assertThat(large.mask().width()).isGreaterThan(small.mask().width());
            assertThat(large.mask().left()).isLessThan(small.mask().left());
            assertThat(cache.request("word", 1.75)).isSameAs(large);
            assertThat(cache.request("word", 1.5)).isSameAs(small);
            assertThat(captures).hasValue(1);
            assertThat(uploads).hasValue(2);
        }
    }

    @Test
    void fontInvalidationDoesNotReuseOldSourceOrFallbackTexture() {
        AtomicInteger captures = new AtomicInteger();
        AtomicInteger uploads = new AtomicInteger();
        List<Integer> released = new ArrayList<>();
        try (LyricsEffectCache<String, Integer> cache = new LyricsEffectCache<>(key -> {
            int width = captures.incrementAndGet();
            return new LyricsEffectRaster.Mask(width, 1, 0, 0, 1, new byte[width]);
        }, pixels -> uploads.incrementAndGet(), released::add, 1_000_000, Runnable::run)) {
            cache.request("same text", 0);
            cache.tick();
            var firstFont = cache.request("same text", 0);
            cache.clear();
            assertThat(cache.request("same text", 0)).isNull();
            assertThat(cache.request("same text", 1)).isNull();
            assertThat(released).isEmpty();
            cache.tick();
            var secondFont = cache.request("same text", 0);
            assertThat(secondFont).isNotSameAs(firstFont);
            assertThat(secondFont.texture()).isNotEqualTo(firstFont.texture());
            assertThat(secondFont.mask().width()).isEqualTo(2);
            assertThat(captures).hasValue(2);
            cache.tick();
            cache.tick();
            assertThat(released).containsExactly(firstFont.texture());
        }
    }

    private static final class QueuedExecutor implements Executor {
        private final ArrayDeque<Runnable> jobs = new ArrayDeque<>();

        @Override public void execute(Runnable command) { jobs.addLast(command); }

        void runAll() {
            Runnable job;
            while ((job = jobs.pollFirst()) != null) job.run();
        }
    }
}
