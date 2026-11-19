package dev.lyrica.engine;

import com.google.gson.*;
import com.tx24.spicyplayer.update.Releases;
import java.net.URI;

/** Only published releases carrying a JAR for this Minecraft version are offered. */
public final class LyricaReleaseLookup {
    private LyricaReleaseLookup() { }
    public record Update(String version, URI page, URI asset, long size, String sha256) {
        public boolean installable() {
            return sha256.matches("[0-9a-f]{64}") && size > 0 && size <= 128L * 1024 * 1024;
        }
    }

    public static String newest(String json, String installed, String minecraft, boolean prereleases) {
        Update update = find(json, installed, minecraft, prereleases);
        return update == null ? "" : update.version();
    }

    public static Update find(String json, String installed, String minecraft, boolean prereleases) {
        installed = installed.split("\\+", 2)[0];
        Update newest = null;
        for (JsonElement element : JsonParser.parseString(json).getAsJsonArray()) {
            JsonObject release = element.getAsJsonObject();
            if (release.get("draft").getAsBoolean() || !prereleases && release.get("prerelease").getAsBoolean()) continue;
            String tag = release.get("tag_name").getAsString();
            String version = tag.replaceFirst("^v", "");
            if (!version.matches("[0-9]+(?:\\.[0-9]+){1,3}(?:-[A-Za-z0-9.-]+)?")) continue;
            if (Releases.INSTANCE.compareVersions(version, installed) <= 0
                    || newest != null && Releases.INSTANCE.compareVersions(version, newest.version()) <= 0) continue;
            for (JsonElement asset : release.getAsJsonArray("assets")) {
                JsonObject file = asset.getAsJsonObject();
                String name = string(file, "name");
                if (!name.equals("lyrica-" + minecraft + "-" + version + ".jar")
                        || !name.matches("[A-Za-z0-9_.-]+")) continue;
                String base = "https://github.com/fakemaluco/lyrica-mod/releases";
                newest = new Update(version,
                        URI.create(base + "/tag/" + tag), URI.create(base + "/download/" + tag + "/" + name),
                        file.has("size") ? file.get("size").getAsLong() : 0,
                        string(file, "digest").replaceFirst("^sha256:", ""));
                break;
            }
        }
        return newest;
    }

    private static String string(JsonObject object, String member) {
        return object.has(member) && !object.get(member).isJsonNull() ? object.get(member).getAsString() : "";
    }
}
