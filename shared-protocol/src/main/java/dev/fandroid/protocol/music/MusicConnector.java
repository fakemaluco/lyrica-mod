package dev.fandroid.protocol.music;

import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

public interface MusicConnector extends AutoCloseable {
    String id();

    MusicCapabilities capabilities();

    CompletableFuture<PlaybackSnapshot> snapshot();

    CompletableFuture<Void> command(MusicCommand command);

    AutoCloseable subscribe(Consumer<PlaybackSnapshot> listener);

    @Override
    void close();
}

