package dev.fandroid.client.music;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * A render-thread cache for captured glyphs and their blurred textures. Capturing,
 * uploading and releasing always happen on the thread calling {@link #tick()}.
 * The worker only handles CPU masks, never graphics objects.
 */
public final class LyricsEffectCache<K, T> implements AutoCloseable {
    public static final int DEFAULT_MAX_BYTES = 32 * 1024 * 1024;
    public static final int MAX_SOURCES = 256;
    public static final int MAX_VARIANTS = 512;
    private static final int CAPTURES_PER_TICK = 8;
    private static final int UPLOADS_PER_TICK = 16;
    private static final int RETIRE_TICKS = 3;
    private static final long MAX_RASTER_PIXELS = 1024L * 1024L;

    public record Surface<T>(LyricsEffectRaster.Mask mask, T texture) {
        public Surface {
            Objects.requireNonNull(mask, "mask");
            Objects.requireNonNull(texture, "texture");
        }
    }

    private record VariantKey<K>(K source, int radiusStep) {
        double radius() { return radiusStep / 4.0; }
    }

    private static final class Source<K> {
        final K key;
        LyricsEffectRaster.Mask mask;
        int attempts;
        boolean failed;

        Source(K key) { this.key = key; }
    }

    private final class Variant {
        final VariantKey<K> key;
        final Source<K> source;
        Surface<T> surface;
        Job job;
        long maskBytes;
        long textureBytes;
        boolean failed;

        Variant(VariantKey<K> key, Source<K> source) {
            this.key = key;
            this.source = source;
        }
    }

    private final class Job {
        final Variant variant;
        final long generation;
        final long reservedBytes;
        FutureTask<Void> future;
        volatile boolean cancelled;

        Job(Variant variant, long generation, long reservedBytes) {
            this.variant = variant;
            this.generation = generation;
            this.reservedBytes = reservedBytes;
        }
    }

    private final class Ready {
        final Job job;
        final LyricsEffectRaster.Mask mask;

        Ready(Job job, LyricsEffectRaster.Mask mask) {
            this.job = job;
            this.mask = mask;
        }
    }

    private record Retired<T>(T texture, long bytes, long releaseTick) {}

    private final Function<K, LyricsEffectRaster.Mask> capture;
    private final Function<LyricsEffectRaster.Mask, T> upload;
    private final Consumer<T> release;
    private final int maxBytes;
    private final Executor executor;
    private final ExecutorService ownedExecutor;
    private final LinkedHashMap<K, Source<K>> sources = new LinkedHashMap<>(16, .75f, true);
    private final LinkedHashMap<VariantKey<K>, Variant> variants = new LinkedHashMap<>(16, .75f, true);
    private final ArrayDeque<Source<K>> captureQueue = new ArrayDeque<>();
    private final ConcurrentLinkedQueue<Ready> readyQueue = new ConcurrentLinkedQueue<>();
    private final ArrayDeque<Retired<T>> retired = new ArrayDeque<>();
    private long bytes;
    private long tick;
    private volatile long generation;
    private volatile boolean closed;

    public LyricsEffectCache(Function<K, LyricsEffectRaster.Mask> capture,
                             Function<LyricsEffectRaster.Mask, T> upload, Consumer<T> release) {
        this(capture, upload, release, DEFAULT_MAX_BYTES);
    }

    public LyricsEffectCache(Function<K, LyricsEffectRaster.Mask> capture,
                             Function<LyricsEffectRaster.Mask, T> upload, Consumer<T> release,
                             int maxBytes) {
        this(capture, upload, release, maxBytes, null);
    }

    /** The supplied executor belongs to the caller and will not be shut down. */
    public LyricsEffectCache(Function<K, LyricsEffectRaster.Mask> capture,
                             Function<LyricsEffectRaster.Mask, T> upload, Consumer<T> release,
                             int maxBytes, Executor executor) {
        this.capture = Objects.requireNonNull(capture, "capture");
        this.upload = Objects.requireNonNull(upload, "upload");
        this.release = Objects.requireNonNull(release, "release");
        if (maxBytes <= 0) throw new IllegalArgumentException("maxBytes must be positive");
        this.maxBytes = maxBytes;
        if (executor == null) {
            ownedExecutor = Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, "lyrics-effects-raster");
                thread.setDaemon(true);
                return thread;
            });
            this.executor = ownedExecutor;
        } else {
            this.executor = executor;
            ownedExecutor = null;
        }
    }

    /**
     * Returns an existing texture or queues its preparation. Radius is rounded to
     * quarter-unit steps and clamped to the shared effect's nine-unit maximum.
     * While a new radius prepares, the closest ready radius for the same source
     * keeps the effect visible. Its Surface retains its own exact mask bounds.
     * This method does not capture glyphs, blur masks or call graphics functions.
     */
    public Surface<T> request(K key, double blurRadius) {
        if (closed) return null;
        Objects.requireNonNull(key, "key");
        double radius = Double.isNaN(blurRadius) ? 0 : Math.max(0, Math.min(9, blurRadius));
        VariantKey<K> variantKey = new VariantKey<>(key, (int) Math.round(radius * 4));
        Variant existing = variants.get(variantKey);
        if (existing != null) {
            sources.get(key); // Update source recency along with variant recency.
            return existing.surface != null ? existing.surface : nearestReady(existing.source, variantKey.radiusStep());
        }
        Source<K> source = sources.get(key);
        if (!makeVariantSlot()) return nearestReady(source, variantKey.radiusStep());
        if (source == null) {
            if (!makeSourceSlot()) return null;
            source = new Source<>(key);
            sources.put(key, source);
            captureQueue.addLast(source);
        }
        if (source.failed) return null;
        variants.put(variantKey, new Variant(variantKey, source));
        return nearestReady(source, variantKey.radiusStep());
    }

    private Surface<T> nearestReady(Source<K> source, int radiusStep) {
        if (source == null) return null;
        Variant closest = null;
        int closestDistance = Integer.MAX_VALUE;
        for (Variant candidate : variants.values()) {
            if (candidate.source != source || candidate.surface == null) continue;
            int distance = Math.abs(candidate.key.radiusStep() - radiusStep);
            if (distance < closestDistance || distance == closestDistance
                    && closest != null && candidate.key.radiusStep() < closest.key.radiusStep()) {
                closest = candidate;
                closestDistance = distance;
            }
        }
        return closest == null ? null : closest.surface;
    }

    /** Prepare at most eight captures and upload at most sixteen ready masks. */
    public void tick() {
        if (closed) return;
        tick++;
        releaseRetired(false);
        int captureCount = Math.min(CAPTURES_PER_TICK, captureQueue.size());
        for (int i = 0; i < captureCount; i++) {
            Source<K> source = captureQueue.removeFirst();
            if (sources.get(source.key) != source || source.failed || source.mask != null) continue;
            LyricsEffectRaster.Mask mask = null;
            try { mask = capture.apply(source.key); } catch (RuntimeException ignored) {}
            source.attempts++;
            if (mask == null) {
                if (source.attempts < 3) captureQueue.addLast(source);
                else failSource(source);
                continue;
            }
            long maskBytes = mask.alpha().length;
            if (!makeMemory(maskBytes, source, null)) {
                failSource(source);
                continue;
            }
            source.mask = mask;
            bytes += maskBytes;
        }

        // Snapshot permits memory eviction while starting jobs without modifying
        // a map iterator. A miss remains queued if retired textures fill the budget.
        for (Variant variant : new ArrayList<>(variants.values())) {
            if (variant.surface == null && variant.job == null && !variant.failed
                    && variant.source.mask != null && variants.get(variant.key) == variant) {
                startJob(variant);
            }
        }

        int uploads = 0;
        Ready ready;
        while (uploads < UPLOADS_PER_TICK && (ready = readyQueue.poll()) != null) {
            Job job = ready.job;
            Variant variant = job.variant;
            if (job.cancelled || job.generation != generation || variants.get(variant.key) != variant
                    || variant.job != job) continue;
            uploads++;
            variant.job = null;
            bytes -= job.reservedBytes;
            LyricsEffectRaster.Mask mask = ready.mask;
            if (mask == null) {
                variant.failed = true;
                continue;
            }
            long maskBytes = mask.alpha().length;
            long textureBytes = maskBytes * 4;
            if (!makeMemory(maskBytes + textureBytes, variant.source, variant)) {
                variant.failed = true;
                continue;
            }
            T texture = null;
            try { texture = upload.apply(mask); } catch (RuntimeException ignored) {}
            if (texture == null) {
                variant.failed = true;
                continue;
            }
            variant.surface = new Surface<>(mask, texture);
            variant.maskBytes = maskBytes;
            variant.textureBytes = textureBytes;
            bytes += maskBytes + textureBytes;
        }
    }

    private void startJob(Variant variant) {
        LyricsEffectRaster.Mask mask = variant.source.mask;
        long reservation = estimatePixels(mask, variant.key.radius()) * 5;
        if (mask.alpha().length + reservation > maxBytes) {
            variant.failed = true;
            return;
        }
        if (!makeMemory(reservation, variant.source, variant)) return;
        Job job = new Job(variant, generation, reservation);
        variant.job = job;
        bytes += reservation;
        job.future = new FutureTask<>(() -> {
            LyricsEffectRaster.Mask result = null;
            try {
                if (!job.cancelled) result = LyricsEffectRaster.blur(mask, variant.key.radius());
            } catch (RuntimeException ignored) {
                // A failed effect falls back to the regular glyph face.
            }
            synchronized (readyQueue) {
                if (!job.cancelled && !closed && generation == job.generation) {
                    readyQueue.add(new Ready(job, result));
                }
            }
            return null;
        });
        try { executor.execute(job.future); }
        catch (RuntimeException ignored) {
            variant.job = null;
            variant.failed = true;
            bytes -= reservation;
        }
    }

    private static long estimatePixels(LyricsEffectRaster.Mask mask, double radius) {
        if (radius == 0) return mask.alpha().length;
        // Gaussian CSS blur uses sigma = radius / 2. The raster kernel adds
        // transparent padding and limits oversized masks to 1024 by 1024.
        double sigma = radius * mask.pixelsPerUnit() / 2;
        long padding = (long) Math.min(1024, Math.ceil(sigma * 3) + 2);
        long width = Math.min(1024, mask.width() + padding * 2);
        long height = Math.min(1024, mask.height() + padding * 2);
        return Math.min(MAX_RASTER_PIXELS, width * height);
    }

    private boolean makeVariantSlot() {
        if (variants.size() < MAX_VARIANTS) return true;
        Iterator<Map.Entry<VariantKey<K>, Variant>> iterator = variants.entrySet().iterator();
        while (iterator.hasNext()) {
            Variant variant = iterator.next().getValue();
            if (variant.surface != null || variant.failed) {
                iterator.remove();
                retireVariant(variant);
                return true;
            }
        }
        return false;
    }

    private boolean makeSourceSlot() {
        if (sources.size() < MAX_SOURCES) return true;
        for (Source<K> source : new ArrayList<>(sources.values())) {
            if (!sourcePending(source)) {
                removeSource(source);
                return true;
            }
        }
        return false;
    }

    private boolean makeMemory(long needed, Source<K> keepSource, Variant keepVariant) {
        if (needed > maxBytes) return false;
        if (bytes + needed <= maxBytes) return true;
        Iterator<Map.Entry<VariantKey<K>, Variant>> iterator = variants.entrySet().iterator();
        while (iterator.hasNext() && bytes + needed > maxBytes) {
            Variant candidate = iterator.next().getValue();
            if (candidate != keepVariant && candidate.surface != null) {
                iterator.remove();
                retireVariant(candidate);
            }
        }
        for (Source<K> source : new ArrayList<>(sources.values())) {
            if (bytes + needed <= maxBytes) return true;
            if (source != keepSource && (source.failed
                    || source.mask != null && !sourceHasJob(source))) removeSource(source);
        }
        return bytes + needed <= maxBytes;
    }

    private boolean sourcePending(Source<K> source) {
        if (!source.failed && source.mask == null) return true;
        for (Variant variant : variants.values()) {
            if (variant.source == source && variant.surface == null && !variant.failed) return true;
        }
        return false;
    }

    private boolean sourceHasJob(Source<K> source) {
        for (Variant variant : variants.values()) {
            if (variant.source == source && variant.job != null) return true;
        }
        return false;
    }

    private void failSource(Source<K> source) {
        source.failed = true;
        for (Variant variant : variants.values()) {
            if (variant.source == source) variant.failed = true;
        }
    }

    private void removeSource(Source<K> source) {
        sources.remove(source.key);
        captureQueue.remove(source);
        Iterator<Map.Entry<VariantKey<K>, Variant>> iterator = variants.entrySet().iterator();
        while (iterator.hasNext()) {
            Variant variant = iterator.next().getValue();
            if (variant.source == source) {
                iterator.remove();
                retireVariant(variant);
            }
        }
        if (source.mask != null) bytes -= source.mask.alpha().length;
    }

    private void retireVariant(Variant variant) {
        if (variant.job != null) {
            variant.job.cancelled = true;
            variant.job.future.cancel(true);
            bytes -= variant.job.reservedBytes;
            variant.job = null;
        }
        if (variant.surface != null) {
            bytes -= variant.maskBytes;
            retired.addLast(new Retired<>(variant.surface.texture(), variant.textureBytes, tick + RETIRE_TICKS));
            variant.surface = null;
        }
    }

    private void releaseRetired(boolean all) {
        while (!retired.isEmpty() && (all || retired.peekFirst().releaseTick() <= tick)) {
            Retired<T> old = retired.removeFirst();
            bytes -= old.bytes();
            release.accept(old.texture());
        }
    }

    /** Invalidate resources, keeping recently used textures alive for three ticks. */
    public void clear() {
        generation++;
        for (Variant variant : variants.values()) retireVariant(variant);
        variants.clear();
        for (Source<K> source : sources.values()) {
            if (source.mask != null) bytes -= source.mask.alpha().length;
        }
        sources.clear();
        captureQueue.clear();
        synchronized (readyQueue) { readyQueue.clear(); }
    }

    /** Bytes allocated or reserved, including textures awaiting safe retirement. */
    public long bytesUsed() { return bytes; }

    /** Number of requested variants still awaiting capture, blur or upload. */
    public int pendingCount() {
        int count = 0;
        for (Variant variant : variants.values()) {
            if (variant.surface == null && !variant.failed) count++;
        }
        return count;
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        clear();
        releaseRetired(true);
        if (ownedExecutor != null) ownedExecutor.shutdownNow();
    }
}
