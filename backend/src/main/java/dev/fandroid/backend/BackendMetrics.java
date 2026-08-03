package dev.fandroid.backend;

import java.util.concurrent.atomic.LongAdder;

public final class BackendMetrics {
    private final LongAdder requests = new LongAdder();
    private final LongAdder sentEnvelopes = new LongAdder();
    private final LongAdder websocketConnections = new LongAdder();

    public void request() {
        requests.increment();
    }

    public void envelope() {
        sentEnvelopes.increment();
    }

    public void websocketConnected() {
        websocketConnections.increment();
    }

    public void websocketDisconnected() {
        websocketConnections.decrement();
    }

    public String prometheus() {
        return """
                # TYPE fandroid_http_requests_total counter
                fandroid_http_requests_total %d
                # TYPE fandroid_envelopes_accepted_total counter
                fandroid_envelopes_accepted_total %d
                # TYPE fandroid_websocket_connections gauge
                fandroid_websocket_connections %d
                """.formatted(requests.sum(), sentEnvelopes.sum(), websocketConnections.sum());
    }
}

