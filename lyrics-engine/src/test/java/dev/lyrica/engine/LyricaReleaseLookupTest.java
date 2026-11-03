package dev.lyrica.engine;

import org.junit.Test;
import static org.junit.Assert.*;

public class LyricaReleaseLookupTest {
    private static String release(String version, String game, boolean beta) {
        return "{\"draft\":false,\"prerelease\":" + beta + ",\"tag_name\":\"v" + version +
                "\",\"assets\":[{\"name\":\"lyrica-" + game + "-" + version + ".jar\"}]}";
    }
    @Test public void skipsWrongGameAndBetaAndComparesNumericVersions() {
        String json = "[" + release("0.8.100", "1.21.1", false) + "," + release("0.9.0-beta.1", "1.21.1", true)
                + "," + release("1.0.0", "26.2", false) + "]";
        assertEquals("0.8.100", LyricaReleaseLookup.newest(json, "0.8.95", "1.21.1", false));
        assertEquals("0.9.0-beta.1", LyricaReleaseLookup.newest(json, "0.8.95", "1.21.1", true));
        assertEquals("", LyricaReleaseLookup.newest(json, "0.8.95", "1.21.11", true));
        assertEquals("", LyricaReleaseLookup.newest(json, "1.0.0", "26.2", false));
    }
    @Test public void sourcesAndDraftsAreNeverOffered() {
        assertEquals("", LyricaReleaseLookup.newest("[" + release("2.0.0", "1.21.1", false)
                .replace(".jar", "-sources.jar") + "]", "1.0.0", "1.21.1", false));
        assertEquals("", LyricaReleaseLookup.newest("[" + release("2.0.0", "1.21.1", false)
                .replace("\"draft\":false", "\"draft\":true") + "]", "1.0.0", "1.21.1", false));
    }
}
