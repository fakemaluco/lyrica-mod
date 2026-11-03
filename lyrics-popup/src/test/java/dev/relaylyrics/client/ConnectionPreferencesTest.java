package dev.relaylyrics.client;

import dev.relaylyrics.client.music.SpicyLyricsApiClient;

import dev.relaylyrics.client.ClientPreferences;
import dev.relaylyrics.client.LyricsSettingsCatalog;
import dev.relaylyrics.protocol.ProtocolCodec;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class ConnectionPreferencesTest {
    @Test void personalPublishableKeyOverridesEnvironmentAndSecretKeysAreRejected() {
        assertThat(SpicyLyricsApiClient.configuredKey(" sl_pk_fixture_client_123 ")).isEqualTo("sl_pk_fixture_client_123");
        assertThat(SpicyLyricsApiClient.configuredKey("sl_sk_fixture_secret_123")).isEmpty();
        assertThat(SpicyLyricsApiClient.isPublishableKey("sl_pk_short")).isFalse();
        assertThat(SpicyLyricsApiClient.isPublishableKey("sl_pk_fixture_client_123\nheader: nope")).isFalse();
        assertThat(SpicyLyricsApiClient.isPublishableKey("sl_pk_" + "a".repeat(300))).isFalse();
    }
    @Test void panelZoomAndPersonalKeyPersistWithoutChangingPopupGeometryOrFontSize() throws Exception {
        var preferences = new ClientPreferences();
        preferences.setLyricsTextScale(1.25); preferences.setLyricsWidth(360); preferences.setLyricsHeight(240);
        preferences.setSettingsUiScale(.7); preferences.setSpicyLyricsApiKey(" sl_pk_fixture_client_123 ");
        var restored = ClientPreferences.decode(ProtocolCodec.mapper().writeValueAsBytes(preferences));
        assertThat(restored.getSettingsUiScale()).isEqualTo(.7);
        assertThat(restored.getLyricsTextScale()).isEqualTo(1.25);
        assertThat(restored.getLyricsWidth()).isEqualTo(360); assertThat(restored.getLyricsHeight()).isEqualTo(240);
        assertThat(restored.getSpicyLyricsApiKey()).isEqualTo("sl_pk_fixture_client_123");
        restored.setSettingsUiScale(Double.NaN); assertThat(restored.getSettingsUiScale()).isEqualTo(1);
        restored.setSettingsUiScale(-10); assertThat(restored.getSettingsUiScale()).isEqualTo(.55);
        restored.setSettingsUiScale(100); assertThat(restored.getSettingsUiScale()).isEqualTo(1.5);
    }
    @Test void oldPreferencesRetainDefaultZoomAndNoPersonalCredential() throws Exception {
        var restored = ClientPreferences.decode("{\"spotifyClientId\":\"keep-client\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThat(restored.getSettingsUiScale()).isEqualTo(1);
        assertThat(restored.getSpicyLyricsApiKey()).isEmpty();
        assertThat(restored.getSpotifyClientId()).isEqualTo("keep-client");
    }
    @Test void accountFormsStayInsideTheCatalogAndApiKeyFieldIsMasked() {
        var catalog = new LyricsSettingsCatalog(new ClientPreferences(), new LyricsSettingsCatalog.Actions() {
            public void run(String action) { }
            public String status(String name) { return ""; }
        });
        catalog.expand("spotify_account"); catalog.expand("youtube_account"); catalog.expand("spicy_account");
        assertThat(catalog.entries(LyricsSettingsCatalog.Category.CONNECTION))
                .anyMatch(entry -> entry.key.endsWith(".spotify.client_input") && entry.kind == LyricsSettingsCatalog.Kind.TEXT)
                .anyMatch(entry -> entry.key.endsWith(".youtube.setup.prepare"));
        assertThat(catalog.entries(LyricsSettingsCatalog.Category.SOURCES))
                .anyMatch(entry -> entry.key.endsWith(".spicy.key_input") && entry.masked);
    }
}
