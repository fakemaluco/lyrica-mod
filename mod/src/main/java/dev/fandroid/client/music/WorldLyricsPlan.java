package dev.fandroid.client.music;

import dev.fandroid.protocol.lyrics.LyricAlignment;
import dev.fandroid.protocol.lyrics.LyricLine;
import dev.fandroid.protocol.lyrics.LyricVocalGroup;
import dev.fandroid.protocol.lyrics.LyricsDocument;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Pure scene planning for the experimental client-side lyrics placed in the world. */
public final class WorldLyricsPlan {
    public static final long ENTER_LEAD_MILLIS = 180;
    public static final long EXIT_TAIL_MILLIS = 450;
    private static final int MAX_VISIBLE_LINES = 3;

    private WorldLyricsPlan() { }

    public static List<WorldLine> visibleLines(LyricsDocument document, long now) {
        if (document == null || document.metadata().syncMode()
                == dev.fandroid.protocol.lyrics.LyricsSyncMode.STATIC) return List.of();
        List<WorldLine> result = new ArrayList<>();
        if (document.groups().isEmpty()) {
            for (LyricLine line : document.lines()) {
                addIfVisible(result, line, LyricAlignment.NORMAL, false, now);
            }
            result.sort(Comparator.comparing(WorldLine::backing)
                    .thenComparing(Comparator.comparingLong((WorldLine line) -> line.line().startMillis()).reversed()));
            if (result.size() <= MAX_VISIBLE_LINES) return chronological(result);
            return chronological(result.subList(0, MAX_VISIBLE_LINES));
        }
        for (LyricVocalGroup group : document.groups()) {
            addIfVisible(result, group.lead(), group.alignment(), false, now);
            for (LyricLine backing : group.backingVocals()) {
                addIfVisible(result, backing, group.alignment(), true, now);
            }
        }
        result.sort(Comparator.comparing(WorldLine::backing)
                    .thenComparing(Comparator.comparingLong((WorldLine line) -> line.line().startMillis()).reversed()));
        if (result.size() <= MAX_VISIBLE_LINES) return chronological(result);
        return chronological(result.subList(0, MAX_VISIBLE_LINES));
    }

    private static List<WorldLine> chronological(List<WorldLine> lines) {
        return lines.stream().sorted(Comparator.comparingLong(value -> value.line().startMillis())).toList();
    }

    public static boolean hasWordTiming(LyricLine line) {
        return !line.words().isEmpty() && !(line.words().size() == 1
                && line.words().getFirst().text().strip().codePoints().anyMatch(Character::isWhitespace));
    }

    /** Romanization that cannot retain supplied timing falls back to phrase animation. */
    public static boolean hasWordTiming(LyricLine line, boolean romanized) {
        if (!hasWordTiming(line)) return false;
        if (!romanized) return true;
        for (var word : LyricsRenderText.visualWords(line, true)) {
            for (var fragment : word.fragments()) {
                long start = fragment.timing().startMillis(), end = fragment.timing().endMillis();
                if (line.words().stream().noneMatch(source -> source.startMillis() == start)
                        || line.words().stream().noneMatch(source -> source.endMillis() == end)) return false;
            }
        }
        return true;
    }

    private static void addIfVisible(List<WorldLine> result, LyricLine line,
                                     LyricAlignment alignment, boolean backing, long now) {
        if (now < line.startMillis() - ENTER_LEAD_MILLIS
                || now > line.endMillis() + EXIT_TAIL_MILLIS) return;
        result.add(new WorldLine(line, alignment, backing));
    }

    public static List<Token> tokens(LyricLine line, boolean romanized) {
        if (!hasWordTiming(line, romanized)) {
            String text = romanized && line.romanization() != null && !line.romanization().isBlank()
                    ? line.romanization() : line.text();
            if (romanized && text.equals(line.text())) {
                String generated = LyricsRomanizer.romanize(text);
                if (generated != null) text = generated;
            }
            return text.isBlank() ? List.of() : List.of(new Token(text, line.startMillis(), line.endMillis(), true));
        }
        List<Token> result = new ArrayList<>();
        for (LyricsRenderText.VisualWord word : LyricsRenderText.visualWords(line, romanized)) {
            boolean first = true;
            for (LyricsRenderText.Fragment fragment : word.fragments()) {
                result.add(new Token(fragment.text(), fragment.timing().startMillis(),
                        fragment.timing().endMillis(), first));
                first = false;
            }
        }
        return List.copyOf(result);
    }

    public static double lineVisibility(LyricLine line, long now) {
        double enter = LyricsAnimation.smooth((now - (line.startMillis() - ENTER_LEAD_MILLIS)) / 320.0);
        double leave = LyricsAnimation.smooth((line.endMillis() + EXIT_TAIL_MILLIS - now) / 450.0);
        return Math.min(enter, leave);
    }

    public record WorldLine(LyricLine line, LyricAlignment alignment, boolean backing) { }

    public record Token(String text, long startMillis, long endMillis, boolean startsWord) {
        public long durationMillis() { return Math.max(0, endMillis - startMillis); }
        public boolean activeAt(long now) { return now >= startMillis && now < endMillis; }
        public boolean sungAt(long now) { return now >= endMillis; }

        /** Short words lift as a block; sustained fragments articulate their glyphs. */
        public double emphasis(long now) {
            if (!LyricsAnimation.usesPerGlyphMotion(durationMillis())) {
                return Math.clamp(LyricsAnimation.shortWordLift(durationMillis(),
                        (now - startMillis) / (double) Math.max(1, durationMillis())), 0, 1);
            }
            if (!activeAt(now)) return 0;
            return LyricsAnimation.activeGlowEnvelope(
                    LyricsAnimation.timedProgress(now, startMillis, endMillis));
        }
    }

    public record Motion(double x, double y, double depth, double scale, double tilt) {
        public static final Motion STILL = new Motion(0, 0, 0, 1, 0);
        public Motion {
            double length = Math.sqrt(x * x + y * y + depth * depth);
            if (length > .25) { x *= .25 / length; y *= .25 / length; depth *= .25 / length; }
            scale = Math.clamp(scale, 1, 1.10);
            tilt = Math.clamp(tilt, -5, 5);
        }
    }

    /** Coordinates are local to the fixed anchor; depth points out toward the viewer. */
    public static Motion motion(LyricLine line, Token token, long now, double glyphFraction,
                                boolean timed, boolean enabled, double intensity,
                                double distance, PlayerReaction player, boolean wall) {
        if (!enabled) return Motion.STILL;
        double comfort = LyricsAnimation.mix(.25, 1, LyricsAnimation.smooth((distance - 1) / 3));
        double strength = Math.clamp(intensity, .5, 1.5) * comfort;
        double enter = 1 - LyricsAnimation.smoother((now - line.startMillis() + ENTER_LEAD_MILLIS) / 320.0);
        double leave = LyricsAnimation.smoother((now - line.endMillis()) / (double) EXIT_TAIL_MILLIS);
        double envelope = (1 - enter) * (1 - leave);
        int variant = Math.floorMod(Objects.hash(line.startMillis(), line.text(), line.agent()), 3);
        double transition = (enter - leave) * .12;
        double x = variant == 2 ? transition : 0;
        double y = variant == 0 ? -transition : 0;
        double depth = variant == 1 ? transition : 0;
        double lift = timed ? token.emphasis(now) : LyricsAnimation.activeGlowEnvelope(
                LyricsAnimation.timedProgress(now, line.startMillis(), line.endMillis())) * .35;
        double wave = 0;
        if (timed && LyricsAnimation.usesPerGlyphMotion(token.durationMillis())) {
            double progress = LyricsAnimation.timedProgress(now, token.startMillis(), token.endMillis());
            wave = Math.sin(progress * Math.PI * 2 - glyphFraction * Math.PI * 1.4) * lift;
        }
        y += .11 * lift + .045 * wave + player.impulse() * .06;
        depth += .065 * lift + .035 * wave;
        double sway = Math.sin(now / 230.0) * player.speed();
        x += .025 * sway;
        depth = depth * strength + (1 - comfort) * -.055 * player.enabled();
        // Wall normal points out of the block, so never animate behind the surface.
        if (wall) depth = Math.max(0, depth);
        return new Motion(x * strength, y * strength, depth,
                1 + .07 * lift * strength * envelope,
                (wave * 3 + sway * 1.5) * strength * envelope);
    }

    public record PlayerReaction(double speed, double impulse, double flight, double enabled) {
        public static final PlayerReaction NONE = new PlayerReaction(0, 0, 0, 0);
    }

    /** Frame-rate independent smoothing, edge-triggered jump/landing and playback discontinuity detection. */
    public static final class SceneState {
        private Object world, document;
        private String track;
        private long position, clock;
        private boolean initialized, playing, grounded, flyingInput;
        private double speed, travelSpeed, flight, impulse, impulseAge = 10;
        public boolean update(Object world, Object document, String track, long position, boolean playing,
                              long clockNanos, boolean onGround, boolean flying, double horizontalSpeed,
                              boolean reactions) {
            double elapsed = initialized ? Math.max(0, (clockNanos - clock) / 1_000_000_000.0) : 0;
            long expected = this.position + (this.playing ? Math.round(elapsed * 1000) : 0);
            boolean reset = !initialized || this.world != world || this.document != document
                    || !Objects.equals(this.track, track) || Math.abs(position - expected) > 750;
            if (reset) {
                speed = 0; travelSpeed = Math.max(0, horizontalSpeed * 20);
                flight = 0; impulse = 0; impulseAge = 10; grounded = onGround;
            }
            double dt = Math.min(.1, elapsed);
            if (playing) {
                double blend = 1 - Math.exp(-dt * 7);
                speed += (Math.clamp(horizontalSpeed / .3, 0, 1) - speed) * blend;
                double target = Math.clamp(horizontalSpeed * 20, 0, 160);
                travelSpeed += (target - travelSpeed) * (1 - Math.exp(-dt * (target > travelSpeed ? 14 : 3)));
                flight += ((flying ? 1 : 0) - flight) * blend;
                if (reactions && !reset && grounded != onGround && !flying) {
                    impulse = onGround ? -.8 : 1; impulseAge = 0;
                } else impulseAge += dt;
            }
            if (!reactions) { impulse = 0; impulseAge = 10; }
            this.world = world; this.document = document; this.track = track;
            this.position = position; this.playing = playing; this.clock = clockNanos;
            grounded = onGround; flyingInput = flying; initialized = true;
            return reset;
        }
        public PlayerReaction reaction(boolean enabled) {
            return enabled ? new PlayerReaction(speed,
                    impulse * Math.exp(-impulseAge * 7) * Math.cos(impulseAge * 12), flight, 1)
                    : PlayerReaction.NONE;
        }
        public double movementSpeed() { return speed; }
        public double blocksPerSecond() { return travelSpeed; }
        public boolean flying() { return flyingInput; }
        public void clear() { initialized = false; world = null; document = null; track = null; }
    }

    public record Interlude(long startMillis, long endMillis) {
        public double visibility(long now) {
            return LyricsAnimation.interludeVisibility(now - startMillis, endMillis - startMillis);
        }
        public double progress(long now) {
            return LyricsAnimation.interludeProgress(now - startMillis, endMillis - startMillis);
        }
    }

    public static Interlude interlude(LyricsDocument document, long now) {
        if (document == null) return null;
        long previousEnd = 0, nextStart = Long.MAX_VALUE;
        for (LyricLine line : document.lines()) {
            if (line.startMillis() <= now && line.endMillis() > now) return null;
            if (line.endMillis() <= now) previousEnd = Math.max(previousEnd, line.endMillis());
            if (line.startMillis() > now) nextStart = Math.min(nextStart, line.startMillis());
        }
        return nextStart != Long.MAX_VALUE && nextStart - previousEnd >= LyricsAnimation.INTERLUDE_MIN_GAP_MILLIS
                ? new Interlude(previousEnd, nextStart) : null;
    }

}
