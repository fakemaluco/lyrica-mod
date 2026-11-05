package dev.fandroid.client.music;

import com.fasterxml.jackson.databind.JsonNode;
import dev.fandroid.protocol.ProtocolCodec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** A bounded vocabulary of presentation decisions; original words and timing are never replaced. */
public record GeminiLyricsSpec(Map<String, WorldLyricsPresentation.Cue> cues, String json, WorldLyricsPresentation.Look look) {
    public static final String CONTRACT = "lyrica-lyrics-direction-v" + WorldLyricsPresentation.CONTRACT_VERSION;
    public GeminiLyricsSpec { cues = Map.copyOf(cues); }

    /** Expand the flat model reply into the validated local/cache format. */
    static GeminiLyricsSpec fromModel(String json, WorldLyricsPresentation.Timeline timeline) throws Exception {
        var mapper = ProtocolCodec.mapper();
        try {
            JsonNode reply = mapper.readTree(json);
            JsonNode array = reply.path("cues");
            if (!array.isArray() || array.size() > 512) throw new IllegalArgumentException("RESPONSE");
            var source = timeline.local(WorldLyricsPresentation.Intensity.DISCREET);
            var normalized = mapper.createArrayNode();
            var used = new HashSet<String>();
            for (JsonNode node : array) {
                if (normalized.size() == 24) break;
                try {
                    fields(node, Set.of("id", "composition", "region", "entrance", "volumetric", "holdMillis", "accentToken", "accentEntrance", "accentCenter"));
                    String id = text(node, "id");
                    var original = source.get(id);
                    if (original == null || used.contains(id)) continue;
                    var composition = WorldLyricsPresentation.Composition.valueOf(text(node, "composition"));
                    var region = WorldLyricsPresentation.Region.valueOf(text(node, "region"));
                    var entrance = WorldLyricsPresentation.Entrance.valueOf(text(node, "entrance"));
                    var tokens = timeline.tokens(original);
                    long hold = integer(node, "holdMillis", 0, WorldLyricsPresentation.MAX_HOLD_MILLIS);
                    if (!node.path("volumetric").isBoolean()) continue;
                    var accent = node.path("accentToken");
                    int index = accent.isIntegralNumber() && accent.canConvertToInt() ? accent.intValue() : -1;
                    var item = mapper.createObjectNode().put("id", id).put("composition", composition.name())
                            .put("region", region.name()).put("entrance", entrance.name())
                            .put("volumetric", node.path("volumetric").booleanValue()).put("holdMillis", hold);
                    var accents = item.putArray("accents");
                    var ends = new TreeSet<>(WorldLyricsPresentation.groups(tokens, composition, WorldLyricsPresentation.Intensity.DISCREET));
                    boolean timed = WorldLyricsPlan.hasWordTiming(original.source().line());
                    if (timed && index >= 0 && index < tokens.size()) {
                        int first = wordStart(tokens, index), end = wordEnd(tokens, index);
                        var effect = choice(WorldLyricsPresentation.Entrance.class, node.path("accentEntrance"), null);
                        boolean center = node.path("accentCenter").asBoolean(false);
                        if (center || effect != null) {
                            if (first > 0) ends.add(first);
                            ends.add(end);
                        }
                        for (int fragment = first; fragment < end; fragment++) {
                            var entry = accents.addObject().put("tokenIndex", fragment).put("scale", 1.35)
                                    .put("color", -1).put("volumetric", false).put("center", center);
                            if (effect != null) entry.put("entrance", effect.name());
                        }
                    }
                    var boundaries = item.putArray("groupEnds");
                    ends.forEach(boundaries::add);
                    normalized.add(item);
                    used.add(id);
                } catch (IllegalArgumentException ignored) {
                    // Other valid lines still apply. This line keeps its local direction.
                }
            }
            var canonical = mapper.createObjectNode();
            canonical.set("cues", normalized);
            String color = reply.path("songColor").asText("");
            if (color.matches("#[0-9a-fA-F]{6}")) canonical.put("songColor", color);
            canonical.put("songFont", choice(WorldLyricsPresentation.SongFont.class, reply.path("songFont"),
                    WorldLyricsPresentation.SongFont.DEFAULT).name());
            return parse(canonical.toString(), timeline);
        } catch (Exception invalid) {
            throw new GeminiShaderClient.Failure("RESPONSE");
        }
    }

    public static GeminiLyricsSpec parse(String json, WorldLyricsPresentation.Timeline timeline) throws Exception {
        if (json == null || json.length() > 262_144) throw new IllegalArgumentException("RESPONSE");
        JsonNode root = ProtocolCodec.mapper().readTree(json);
        fields(root, Set.of("cues", "songColor", "songFont"));
        String rgb = root.path("songColor").asText("");
        var look = new WorldLyricsPresentation.Look(rgb.matches("#[0-9a-fA-F]{6}") ? Integer.parseInt(rgb.substring(1), 16) : -1,
                choice(WorldLyricsPresentation.SongFont.class, root.path("songFont"), WorldLyricsPresentation.SongFont.DEFAULT));
        JsonNode array = root.path("cues");
        if (!array.isArray() || array.isEmpty() || array.size() > 512) throw new IllegalArgumentException("RESPONSE");
        var originals = timeline.local(WorldLyricsPresentation.Intensity.DISCREET);
        Map<String, WorldLyricsPresentation.Cue> result = new LinkedHashMap<>();
        for (JsonNode node : array) {
            fields(node, Set.of("id", "composition", "region", "entrance", "volumetric", "holdMillis", "accents", "groupEnds"));
            var original = originals.get(node.path("id").asText());
            if (original == null || result.containsKey(original.id())) throw new IllegalArgumentException("RESPONSE");
            var tokens = WorldLyricsPlan.tokens(original.source().line(), false);
            var composition = WorldLyricsPresentation.Composition.valueOf(text(node, "composition"));
            var region = WorldLyricsPresentation.Region.valueOf(text(node, "region"));
            var entrance = WorldLyricsPresentation.Entrance.valueOf(text(node, "entrance"));
            if (!node.path("volumetric").isBoolean()) throw new IllegalArgumentException("RESPONSE");
            long hold = integer(node, "holdMillis", 0, WorldLyricsPresentation.MAX_HOLD_MILLIS);
            if (!node.path("groupEnds").isArray() || !node.path("accents").isArray()
                    || node.path("accents").size() > 48) throw new IllegalArgumentException("RESPONSE");
            List<Integer> ends = new ArrayList<>();
            int previous = 0;
            for (JsonNode boundary : node.path("groupEnds")) {
                if (!boundary.isIntegralNumber() || !boundary.canConvertToInt()) throw new IllegalArgumentException("RESPONSE");
                int end = boundary.intValue();
                if (end <= previous || end > tokens.size() || end < tokens.size() && !tokens.get(end).startsWord())
                    throw new IllegalArgumentException("RESPONSE");
                ends.add(end); previous = end;
            }
            if (ends.isEmpty() || previous != tokens.size()) throw new IllegalArgumentException("RESPONSE");
            List<WorldLyricsPresentation.Accent> accents = new ArrayList<>();
            Set<Integer> seen = new HashSet<>();
            for (JsonNode accent : node.path("accents")) {
                fields(accent, Set.of("tokenIndex", "scale", "color", "volumetric", "entrance", "center"));
                int index = (int) integer(accent, "tokenIndex", 0, tokens.size() - 1);
                if (!seen.add(index) || !accent.path("scale").isNumber() || !accent.path("volumetric").isBoolean())
                    throw new IllegalArgumentException("RESPONSE");
                double scale = accent.path("scale").doubleValue();
                if (!Double.isFinite(scale) || scale < 1 || scale > 1.8) throw new IllegalArgumentException("RESPONSE");
                int color = (int) integer(accent, "color", -1, 0xFFFFFF);
                accents.add(new WorldLyricsPresentation.Accent(index, scale, color, accent.path("volumetric").booleanValue(),
                        choice(WorldLyricsPresentation.Entrance.class, accent.path("entrance"), null),
                        accent.path("center").asBoolean(false)));
            }
            var cue = new WorldLyricsPresentation.Cue(original.id(), original.source(), composition, region, entrance,
                    node.path("volumetric").booleanValue(), hold, accents, ends, look);
            result.put(cue.id(), cue);
        }
        return new GeminiLyricsSpec(result, ProtocolCodec.mapper().writeValueAsString(root), look);
    }
    private static int wordStart(List<WorldLyricsPlan.Token> tokens, int index) {
        while (index > 0 && !tokens.get(index).startsWord()) index--;
        return index;
    }

    private static int wordEnd(List<WorldLyricsPlan.Token> tokens, int index) {
        do { index++; } while (index < tokens.size() && !tokens.get(index).startsWord());
        return index;
    }

    private static <E extends Enum<E>> E choice(Class<E> type, JsonNode value, E fallback) {
        if (value.isTextual()) {
            try { return Enum.valueOf(type, value.textValue()); }
            catch (IllegalArgumentException ignored) { }
        }
        return fallback;
    }

    private static void fields(JsonNode node, Set<String> allowed) {
        if (!node.isObject()) throw new IllegalArgumentException("RESPONSE");
        node.fieldNames().forEachRemaining(name -> { if (!allowed.contains(name)) throw new IllegalArgumentException("RESPONSE"); });
    }
    private static String text(JsonNode node, String name) {
        if (!node.path(name).isTextual()) throw new IllegalArgumentException("RESPONSE");
        return node.path(name).textValue();
    }
    private static long integer(JsonNode node, String name, long min, long max) {
        JsonNode value = node.path(name);
        if (!value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() < min || value.longValue() > max)
            throw new IllegalArgumentException("RESPONSE");
        return value.longValue();
    }
    public static String hash(String input) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception impossible) { throw new IllegalStateException(impossible); }
    }
}
