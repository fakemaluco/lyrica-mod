package dev.fandroid.client;

import com.fasterxml.jackson.core.type.TypeReference;
import dev.fandroid.protocol.ProtocolCodec;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.io.InputStream;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RelayResourcesTest {
    @Test
    void translationsHaveIdenticalKeys() throws Exception {
        Map<String, String> english = language("en_us");
        Map<String, String> portuguese = language("pt_br");
        assertThat(portuguese.keySet()).containsExactlyInAnyOrderElementsOf(english.keySet());
        assertThat(english).allSatisfy((key, value) -> assertThat(value).isNotBlank());
        assertThat(portuguese).allSatisfy((key, value) -> assertThat(value).isNotBlank());
    }

    @Test
    void everyAppIconIsA128PixelPngWithTransparency() throws Exception {
        for (PhoneApp app : PhoneApp.values()) {
            String path = "/assets/fandroid/" + app.texture().getPath();
            try (InputStream stream = getClass().getResourceAsStream(path)) {
                assertThat(stream).as(path).isNotNull();
                var image = ImageIO.read(stream);
                assertThat(image.getWidth()).as(path).isEqualTo(128);
                assertThat(image.getHeight()).as(path).isEqualTo(128);
                assertThat(image.getColorModel().hasAlpha()).as(path).isTrue();
                boolean transparent = false;
                for (int y = 0; y < image.getHeight() && !transparent; y++) {
                    for (int x = 0; x < image.getWidth(); x++) {
                        if ((image.getRGB(x, y) >>> 24) < 255) { transparent = true; break; }
                    }
                }
                assertThat(transparent).as(path).isTrue();
            }
        }
    }

    private Map<String, String> language(String locale) throws Exception {
        String path = "/assets/fandroid/lang/" + locale + ".json";
        try (InputStream stream = getClass().getResourceAsStream(path)) {
            assertThat(stream).as(path).isNotNull();
            return ProtocolCodec.mapper().readValue(stream, new TypeReference<>() { });
        }
    }
}
