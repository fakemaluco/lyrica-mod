package dev.relaylyrics.client;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class WorldLightingOptionalAdapterTest {
    @Test void missingOptionalApiDoesNotLoadItsEntrypointClass() throws Exception {
        var adapter=new WorldLyricsLightAdapter();
        Runnable inactive=adapter.create(null,"missing.optional.IntegrationMustNotBeLoaded",Runnable.class);
        inactive.run();
        assertThat(inactive.toString()).contains("Inactive Lyrica");
        assertThat(inactive.equals(inactive)).isTrue();
    }
}
