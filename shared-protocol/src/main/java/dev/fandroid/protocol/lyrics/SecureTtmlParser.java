package dev.fandroid.protocol.lyrics;

import org.w3c.dom.Attr;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Hardened AMLL-flavoured TTML parser. It deliberately has no network-capable
 * XML features and accepts both word- and line-synchronised documents.
 */
public final class SecureTtmlParser {
    private static final Pattern CLOCK = Pattern.compile("(?:(\\d+):)?(\\d{1,2}):(\\d{1,2})(?:[.,](\\d{1,9}))?");
    private static final Pattern OFFSET = Pattern.compile("([0-9]+(?:\\.[0-9]+)?)(ms|s|m|h)?");
    private static final int MAX_DOCUMENT_BYTES = 4 * 1024 * 1024;

    public LyricsDocument parse(byte[] source) {
        if (source.length > MAX_DOCUMENT_BYTES) {
            throw new IllegalArgumentException("TTML document exceeds 4 MiB");
        }
        String prefix = new String(source, 0, Math.min(source.length, 4_096), StandardCharsets.UTF_8)
                .toUpperCase(Locale.ROOT);
        if (prefix.contains("<!DOCTYPE") || prefix.contains("<!ENTITY")) {
            throw new IllegalArgumentException("DTD and entities are not allowed in TTML");
        }

        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            feature(factory, "http://apache.org/xml/features/disallow-doctype-decl", true);
            feature(factory, "http://xml.org/sax/features/external-general-entities", false);
            feature(factory, "http://xml.org/sax/features/external-parameter-entities", false);
            feature(factory, "http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            attribute(factory, XMLConstants.ACCESS_EXTERNAL_DTD, "");
            attribute(factory, XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");

            var builder = factory.newDocumentBuilder();
            builder.setEntityResolver((publicId, systemId) -> new InputSource(new ByteArrayInputStream(new byte[0])));
            Document xml = builder.parse(new ByteArrayInputStream(source));

            Sidecars sidecars = readSidecars(xml);
            List<LyricVocalGroup> groups = new ArrayList<>();
            String firstAgent = "";
            for (Element paragraph : elements(xml, "p")) {
                ParagraphResult result = readParagraph(paragraph, sidecars);
                if (result == null) continue;
                if (firstAgent.isBlank()) firstAgent = result.lead.agent();
                LyricAlignment alignment = !firstAgent.isBlank() && !firstAgent.equals(result.lead.agent())
                        ? LyricAlignment.OPPOSITE : LyricAlignment.NORMAL;
                groups.add(new LyricVocalGroup(result.lead.startMillis(), result.endMillis(),
                        result.lead, result.backgrounds, alignment));
            }
            List<LyricVocalGroup> repaired = repairGroupEnds(groups);
            List<LyricLine> flattened = repaired.stream()
                    .flatMap(group -> java.util.stream.Stream.concat(
                            java.util.stream.Stream.of(group.lead()), group.backingVocals().stream()))
                    .toList();
            return new LyricsDocument(flattened, repaired, readMetadata(xml, flattened));
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("invalid TTML", e);
        }
    }

    private ParagraphResult readParagraph(Element paragraph, Sidecars sidecars) {
        long start = elementStart(paragraph, 0);
        long end = elementEnd(paragraph, start);
        String agent = firstNonBlank(attribute(paragraph, "agent"), "v1");
        String role = firstNonBlank(attribute(paragraph, "role"), "").toLowerCase(Locale.ROOT);
        boolean paragraphBackground = role.contains("x-bg") || booleanAttribute(paragraph, "background");
        String key = attribute(paragraph, "key");

        LineBuilder main = new LineBuilder(start, end, agent, paragraphBackground);
        List<LyricLine> backgrounds = new ArrayList<>();
        walk(paragraph, main, backgrounds, false);
        if (main.translation == null) main.translation = sidecars.translations.get(key);
        if (main.romanization == null) main.romanization = sidecars.romanizations.get(key);
        LyricLine line = main.build();
        if (line.text().isBlank()) return null;
        long groupEnd = Math.max(line.endMillis(), backgrounds.stream()
                .mapToLong(LyricLine::endMillis).max().orElse(line.endMillis()));
        return new ParagraphResult(line, List.copyOf(backgrounds), groupEnd);
    }

    private void walk(Element parent, LineBuilder line, List<LyricLine> destination, boolean insideBackground) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() == Node.TEXT_NODE || child.getNodeType() == Node.CDATA_SECTION_NODE) {
                line.appendLooseText(child.getNodeValue());
                continue;
            }
            if (!(child instanceof Element element)) continue;

            String role = firstNonBlank(attribute(element, "role"), attribute(element, "type"), "")
                    .toLowerCase(Locale.ROOT);
            if (role.contains("translation")) {
                line.translation = cleanText(visibleText(element));
                continue;
            }
            if (role.contains("roman") || role.contains("translit")) {
                line.romanization = cleanText(visibleText(element));
                continue;
            }
            if (role.contains("x-bg") && !insideBackground) {
                long start = elementStart(element, line.start);
                long end = elementEnd(element, line.end);
                LineBuilder background = new LineBuilder(start, end, line.agent, true);
                walk(element, background, destination, true);
                LyricLine backgroundLine = background.build();
                if (!backgroundLine.text().isBlank()) destination.add(backgroundLine);
                continue;
            }

            String ruby = attribute(element, "ruby");
            if (ruby != null && (ruby.equalsIgnoreCase("text") || ruby.equalsIgnoreCase("textContainer"))) {
                continue;
            }
            if (ruby != null && ruby.equalsIgnoreCase("container")) {
                String base = rubyBaseText(element);
                long start = earliestTimedStart(element, elementStart(element, line.start));
                long end = latestTimedEnd(element, elementEnd(element, line.end));
                line.appendTimed(base, start, end);
                continue;
            }

            if (hasRenderableElementChildren(element)) {
                walk(element, line, destination, insideBackground);
            } else {
                String value = visibleText(element);
                long start = elementStart(element, line.start);
                long end = elementEnd(element, line.end);
                line.appendTimed(value, start, end);
            }
        }
    }

    private static List<LyricVocalGroup> repairGroupEnds(List<LyricVocalGroup> source) {
        List<LyricVocalGroup> sorted = source.stream()
                .sorted(Comparator.comparingLong(LyricVocalGroup::startMillis))
                .toList();
        List<LyricVocalGroup> repaired = new ArrayList<>(sorted.size());
        for (int i = 0; i < sorted.size(); i++) {
            LyricVocalGroup group = sorted.get(i);
            long nextStart = i + 1 < sorted.size() ? sorted.get(i + 1).startMillis() : Long.MAX_VALUE;
            LyricLine lead = repairLineEnd(group.lead(), nextStart);
            List<LyricLine> backing = group.backingVocals().stream()
                    .map(line -> repairLineEnd(line, lead.endMillis()))
                    .toList();
            long end = Math.max(lead.endMillis(), backing.stream()
                    .mapToLong(LyricLine::endMillis).max().orElse(lead.endMillis()));
            repaired.add(new LyricVocalGroup(Math.min(group.startMillis(), lead.startMillis()), end,
                    lead, backing, group.alignment()));
        }
        return repaired;
    }

    private static LyricLine repairLineEnd(LyricLine line, long nextStart) {
        long lastWordEnd = line.words().stream().mapToLong(TimedWord::endMillis).max().orElse(line.startMillis());
        long end = Math.max(line.endMillis(), lastWordEnd);
        if (end <= line.startMillis()) {
            end = nextStart != Long.MAX_VALUE ? nextStart : line.startMillis() + 3_000;
        }
        return new LyricLine(line.startMillis(), end, line.text(), line.words(), line.agent(),
                line.background(), line.translation(), line.romanization());
    }

    private static LyricsMetadata readMetadata(Document document, List<LyricLine> lines) {
        Map<String, String> values = new HashMap<>();
        for (Element meta : elements(document, "meta")) {
            String key = firstNonBlank(attribute(meta, "key"), attribute(meta, "name"),
                    attribute(meta, "property"), attribute(meta, "item"));
            String value = firstNonBlank(attribute(meta, "value"), attribute(meta, "content"),
                    cleanText(meta.getTextContent()));
            if (!key.isBlank() && !value.isBlank()) values.putIfAbsent(key.toLowerCase(Locale.ROOT), value);
        }
        boolean wordSynced = lines.stream().anyMatch(line -> line.words().size() > 1);
        return new LyricsMetadata(LyricsSource.UNKNOWN,
                wordSynced ? LyricsSyncMode.WORD : LyricsSyncMode.LINE,
                metadata(values, "spotifyid", "spotify-id", "spotify_id", "trackid", "track-id"),
                metadata(values, "title", "musicname", "music-name", "songtitle", "song-title"),
                metadata(values, "artists", "artist", "songartists", "song-artist"),
                metadata(values, "album", "albumname", "album-name"),
                metadata(values, "songwriters", "songwriter", "song-writers", "song-writer",
                        "writers", "writer", "composers", "composer", "lyricists", "lyricist"));
    }

    private static String metadata(Map<String, String> values, String... keys) {
        for (String key : keys) {
            String value = values.get(key);
            if (value != null && !value.isBlank()) return value;
        }
        return "";
    }

    private static Sidecars readSidecars(Document document) {
        Map<String, String> translations = new HashMap<>();
        Map<String, String> romanizations = new HashMap<>();
        for (Element text : elements(document, "text")) {
            String key = attribute(text, "for");
            if (key == null || key.isBlank()) continue;
            String value = cleanText(text.getTextContent());
            if (value.isBlank()) continue;
            Node cursor = text.getParentNode();
            while (cursor instanceof Element parent) {
                String name = localName(parent).toLowerCase(Locale.ROOT);
                if (name.equals("translation")) {
                    translations.putIfAbsent(key, value);
                    break;
                }
                if (name.equals("transliteration")) {
                    romanizations.putIfAbsent(key, value);
                    break;
                }
                cursor = cursor.getParentNode();
            }
        }
        return new Sidecars(translations, romanizations);
    }

    private static List<Element> elements(Node root, String wantedLocalName) {
        List<Element> result = new ArrayList<>();
        collectElements(root, wantedLocalName, result);
        return result;
    }

    private static void collectElements(Node root, String wantedLocalName, List<Element> destination) {
        NodeList children = root.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child instanceof Element element) {
                if (localName(element).equals(wantedLocalName)) destination.add(element);
                collectElements(element, wantedLocalName, destination);
            }
        }
    }

    private static boolean hasRenderableElementChildren(Element element) {
        NodeList children = element.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (!(children.item(i) instanceof Element child)) continue;
            String role = firstNonBlank(attribute(child, "role"), attribute(child, "type"), "")
                    .toLowerCase(Locale.ROOT);
            String ruby = attribute(child, "ruby");
            if (!role.contains("translation") && !role.contains("roman") && !role.contains("x-bg")
                    && (ruby == null || (!ruby.equalsIgnoreCase("text") && !ruby.equalsIgnoreCase("textContainer")))) {
                return true;
            }
        }
        return false;
    }

    private static String rubyBaseText(Element container) {
        StringBuilder result = new StringBuilder();
        for (Element base : elements(container, "span")) {
            String ruby = attribute(base, "ruby");
            if (ruby != null && ruby.equalsIgnoreCase("base")) result.append(base.getTextContent());
        }
        return result.isEmpty() ? visibleText(container) : result.toString();
    }

    private static String visibleText(Element element) {
        StringBuilder result = new StringBuilder();
        appendVisibleText(element, result);
        return result.toString();
    }

    private static void appendVisibleText(Node node, StringBuilder result) {
        NodeList children = node.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() == Node.TEXT_NODE || child.getNodeType() == Node.CDATA_SECTION_NODE) {
                result.append(child.getNodeValue());
            } else if (child instanceof Element element) {
                String role = firstNonBlank(attribute(element, "role"), attribute(element, "type"), "")
                        .toLowerCase(Locale.ROOT);
                String ruby = attribute(element, "ruby");
                if (role.contains("translation") || role.contains("roman") || role.contains("x-bg")) continue;
                if (ruby != null && (ruby.equalsIgnoreCase("text") || ruby.equalsIgnoreCase("textContainer"))) continue;
                appendVisibleText(element, result);
            }
        }
    }

    private static long earliestTimedStart(Element element, long fallback) {
        long result = attribute(element, "begin") == null ? Long.MAX_VALUE : elementStart(element, fallback);
        for (Element span : elements(element, "span")) {
            if (attribute(span, "begin") != null) result = Math.min(result, elementStart(span, fallback));
        }
        return result == Long.MAX_VALUE ? fallback : result;
    }

    private static long latestTimedEnd(Element element, long fallback) {
        long result = attribute(element, "end") == null && attribute(element, "dur") == null
                ? Long.MIN_VALUE : elementEnd(element, fallback);
        for (Element span : elements(element, "span")) {
            if (attribute(span, "end") != null || attribute(span, "dur") != null) {
                result = Math.max(result, elementEnd(span, fallback));
            }
        }
        return result == Long.MIN_VALUE ? fallback : result;
    }

    private static long elementStart(Element element, long fallback) {
        return time(attribute(element, "begin"), fallback);
    }

    private static long elementEnd(Element element, long fallback) {
        String end = attribute(element, "end");
        if (end != null && !end.isBlank()) return time(end, fallback);
        String duration = attribute(element, "dur");
        return duration == null || duration.isBlank() ? fallback : elementStart(element, fallback) + time(duration, 0);
    }

    private static String attribute(Element element, String localName) {
        for (int i = 0; i < element.getAttributes().getLength(); i++) {
            Attr attribute = (Attr) element.getAttributes().item(i);
            String name = attribute.getLocalName() == null ? attribute.getName() : attribute.getLocalName();
            if (name.equalsIgnoreCase(localName)) return attribute.getValue();
        }
        return null;
    }

    private static String localName(Element element) {
        return element.getLocalName() == null ? element.getTagName() : element.getLocalName();
    }

    private static boolean booleanAttribute(Element element, String name) {
        String value = attribute(element, name);
        return value != null && (value.equalsIgnoreCase("true") || value.equals("1"));
    }

    static long time(String value, long fallback) {
        if (value == null || value.isBlank()) return fallback;
        Matcher clock = CLOCK.matcher(value.strip());
        if (clock.matches()) {
            long hours = clock.group(1) == null ? 0 : Long.parseLong(clock.group(1));
            long minutes = Long.parseLong(clock.group(2));
            long seconds = Long.parseLong(clock.group(3));
            long millis = fractionMillis(clock.group(4));
            return Duration.ofHours(hours).plusMinutes(minutes).plusSeconds(seconds).toMillis() + millis;
        }
        Matcher offset = OFFSET.matcher(value.strip().toLowerCase(Locale.ROOT));
        if (offset.matches()) {
            double amount = Double.parseDouble(offset.group(1));
            String unit = offset.group(2);
            return switch (unit == null ? "s" : unit) {
                case "ms" -> Math.round(amount);
                case "s" -> Math.round(amount * 1_000);
                case "m" -> Math.round(amount * 60_000);
                case "h" -> Math.round(amount * 3_600_000);
                default -> fallback;
            };
        }
        throw new IllegalArgumentException("unsupported TTML time: " + value);
    }

    private static long fractionMillis(String fraction) {
        if (fraction == null || fraction.isEmpty()) return 0;
        String padded = (fraction + "000").substring(0, 3);
        return Long.parseLong(padded);
    }

    private static String cleanText(String value) {
        if (value == null) return "";
        return value.replace('\u00A0', ' ').replaceAll("[\\t\\r\\n ]+", " ").strip();
    }

    private static String cleanWord(String value) {
        if (value == null) return "";
        return value.replace('\u00A0', ' ').replaceAll("[\\t\\r\\n]+", " ");
    }

    private static void feature(DocumentBuilderFactory factory, String key, boolean value) {
        try { factory.setFeature(key, value); } catch (Exception ignored) { }
    }

    private static void attribute(DocumentBuilderFactory factory, String key, String value) {
        try { factory.setAttribute(key, value); } catch (IllegalArgumentException ignored) { }
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) if (value != null && !value.isBlank()) return value;
        return "";
    }

    private record Sidecars(Map<String, String> translations, Map<String, String> romanizations) { }

    private record ParagraphResult(LyricLine lead, List<LyricLine> backgrounds, long endMillis) { }

    private static final class LineBuilder {
        private final long start;
        private final long end;
        private final String agent;
        private final boolean background;
        private final StringBuilder text = new StringBuilder();
        private final List<WordDraft> words = new ArrayList<>();
        private String translation;
        private String romanization;

        private LineBuilder(long start, long end, String agent, boolean background) {
            this.start = start;
            this.end = end;
            this.agent = agent;
            this.background = background;
        }

        private void appendLooseText(String value) {
            if (value == null || value.isEmpty()) return;
            String cleaned = cleanWord(value);
            if (cleaned.isBlank() && (value.contains("\n") || value.contains("\r") || value.contains("\t"))) return;
            text.append(cleaned);
            if (!words.isEmpty() && cleaned.isBlank()) words.getLast().text.append(cleaned);
            else if (!cleaned.isBlank()) words.add(new WordDraft(start, end, cleaned));
        }

        private void appendTimed(String value, long wordStart, long wordEnd) {
            String cleaned = cleanWord(value);
            if (cleaned.isEmpty()) return;
            boolean joinPrevious = text.length() > 0
                    && !Character.isWhitespace(text.charAt(text.length() - 1))
                    && !Character.isWhitespace(cleaned.charAt(0));
            text.append(cleaned);
            words.add(new WordDraft(wordStart, Math.max(wordStart, wordEnd), cleaned, joinPrevious));
        }

        private LyricLine build() {
            String normalized = cleanText(text.toString());
            List<TimedWord> timed = words.stream()
                    .filter(word -> !word.text.isEmpty())
                    .map(word -> new TimedWord(word.start, word.end, word.text.toString(), word.joinPrevious))
                    .toList();
            if (timed.isEmpty() && !normalized.isBlank()) timed = List.of(new TimedWord(start, end, normalized));
            return new LyricLine(start, end, normalized, timed, agent, background,
                    blankToNull(translation), blankToNull(romanization));
        }

        private static String blankToNull(String value) {
            return value == null || value.isBlank() ? null : value;
        }
    }

    private static final class WordDraft {
        private final long start;
        private final long end;
        private final StringBuilder text;
        private final boolean joinPrevious;

        private WordDraft(long start, long end, String text) {
            this(start, end, text, false);
        }

        private WordDraft(long start, long end, String text, boolean joinPrevious) {
            this.start = start;
            this.end = end;
            this.text = new StringBuilder(text);
            this.joinPrevious = joinPrevious;
        }
    }
}
