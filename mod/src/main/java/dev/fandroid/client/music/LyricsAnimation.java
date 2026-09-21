package dev.fandroid.client.music;

/** Pure timing curves shared by compatible and enhanced lyric render passes. */
public final class LyricsAnimation {
    /** Spicy-style neutral palette used by the word-fill animation. */
    public static final int ACTIVE_TEXT = 0xFFFFFFFF;
    public static final int UPCOMING_TEXT = 0xFFE2E4E7;
    /** Unsung words in the focused phrase: pure white at about a fifth opacity, so they never compete with the filled white. */
    public static final int UNSYNCED_TEXT = 0x33FFFFFF;

    private static final double NEIGHBOUR_OPACITY = .52;
    private static final double NEIGHBOUR_SCALE = .970;
    private static final double NEIGHBOUR_BRIGHTNESS = .80;
    private static final double DISTANT_MIN_OPACITY = .10;
    private static final double DISTANT_MIN_SCALE = .90;
    private static final double DISTANT_MIN_BRIGHTNESS = .52;
    private static final double MAX_BLUR_RADIUS = 3.6;
    private static final long PER_GLYPH_MOTION_MIN_MILLIS = 1_400;

    private LyricsAnimation() { }

    /**
     * The shared visual hierarchy for lyric groups. It deliberately depends only
     * on the absolute distance from the focused group: past and future lines use
     * the same curve, while manual reading and simple mode stay fully crisp.
     */
    public static FocusProfile focusProfile(int distance, boolean simpleMode, boolean manualReading) {
        if (simpleMode || manualReading) return FocusProfile.CRISP_READING;
        int safeDistance = Math.max(0, distance);
        if (safeDistance == 0) return FocusProfile.ACTIVE;
        if (safeDistance == 1) {
            return new FocusProfile(NEIGHBOUR_OPACITY, NEIGHBOUR_SCALE, 0, 0, 0,
                    NEIGHBOUR_BRIGHTNESS);
        }
        int distant = safeDistance - 2;
        double opacity = Math.max(DISTANT_MIN_OPACITY, .36 * Math.pow(.68, distant));
        double scale = Math.max(DISTANT_MIN_SCALE, .948 - distant * .014);
        double blur = Math.min(MAX_BLUR_RADIUS, .82 + distant * .68);
        // Inactive lines never emit a halo: the only glow comes from the phrase being sung.
        double bloomOpacity = 0;
        double brightness = Math.max(DISTANT_MIN_BRIGHTNESS, .68 - distant * .04);
        return new FocusProfile(opacity, scale, blur, bloomOpacity, 0, brightness);
    }

    /** The latest timing anchor is the only visually focused phrase during overlapping transitions. */
    public static int focusDistance(int groupIndex, int focusedIndex) {
        return focusedIndex < 0 ? 0 : Math.abs(groupIndex - focusedIndex);
    }

    /** Distance from a focus band, used when two synchronized phrases overlap. */
    public static int focusDistance(int groupIndex, int firstFocused, int lastFocused) {
        if (firstFocused < 0 || lastFocused < firstFocused) return 0;
        if (groupIndex < firstFocused) return firstFocused - groupIndex;
        if (groupIndex > lastFocused) return groupIndex - lastFocused;
        return 0;
    }

    /** Immutable values consumed by both the base and framebuffer render passes. */
    public record FocusProfile(double opacity, double scale, double blurRadius,
                               double bloomOpacity, double elevation, double textBrightness) {
        private static final FocusProfile ACTIVE = new FocusProfile(1, 1, 0, 0, 2.4, 1);
        private static final FocusProfile CRISP_READING = new FocusProfile(1, 1, 0, 0, 0, 1);

        public FocusProfile {
            opacity = Math.clamp(opacity, 0, 1);
            scale = Math.max(0, scale);
            blurRadius = Math.clamp(blurRadius, 0, MAX_BLUR_RADIUS);
            bloomOpacity = blurRadius == 0 ? 0 : Math.clamp(bloomOpacity, 0, 1);
            textBrightness = Math.clamp(textBrightness, 0, 1);
        }

        /** One sharp base source per text, plus an optional blurred framebuffer source. */
        public RenderPlan renderPlan() {
            return new RenderPlan(1, bloomOpacity > 0 && blurRadius > 0 ? 1 : 0, 0);
        }
    }

    /**
     * A compact halo behind the filled part of the word currently being sung.
     * It is independent from the distant-line blur, so changing glow intensity
     * never makes inactive text brighter or blurrier.
     */
    public static ActiveGlowProfile activeGlowProfile(double glowAmount, double intensityMultiplier) {
        // The source signal is normalized before reaching the profile. Keep the response
        // bright through the middle of a word while retaining a soft entrance and exit.
        double normalized = Math.pow(Math.clamp(glowAmount, 0, 1), .62);
        if (normalized <= .005) return ActiveGlowProfile.NONE;
        GlowLevel level = intensityMultiplier < .78 ? GlowLevel.SOFT
                : intensityMultiplier > 1.22 ? GlowLevel.STRONG : GlowLevel.NORMAL;
        return switch (level) {
            case SOFT -> new ActiveGlowProfile(.28 * normalized, 1.4, .10 * normalized, 2.8);
            case NORMAL -> new ActiveGlowProfile(.44 * normalized, 1.7, .18 * normalized, 3.5);
            case STRONG -> new ActiveGlowProfile(.58 * normalized, 2.0, .24 * normalized, 4.2);
        };
    }

    /** Stable halo bed only for held words that animate their glyphs independently. */
    public static double activeWordGlow(long durationMillis, double wordProgress) {
        if (!usesPerGlyphMotion(durationMillis) || wordProgress <= 0 || wordProgress >= 1) return 0;
        double envelope = activeGlowEnvelope(wordProgress);
        double heldBoost = .15 * sustainedGlowStrength(durationMillis);
        return (.70 + heldBoost) * envelope;
    }

    /**
     * Keeps a partially filled glyph's shadow matched to its still-muted face.
     * The shadow approaches active opacity only after most of the glyph is white,
     * preventing a black flash at the beginning of a word.
     */
    public static double progressiveShadowOpacity(double futureAlpha, double activeAlpha,
                                                   double coverage) {
        double lateFill = smoother(Math.clamp((coverage - .72) / .28, 0, 1));
        return mix(Math.clamp(futureAlpha, 0, 1), Math.clamp(activeAlpha, 0, 1), lateFill);
    }

    /** Combines the stable word halo with animation energy and a 25% leading-edge pulse. */
    public static double activeGlyphGlow(double motionGlow, long durationMillis,
                                         double wordProgress, double boundaryAmount) {
        if (!usesPerGlyphMotion(durationMillis) || wordProgress <= 0 || wordProgress >= 1) return 0;
        double bed = .70 + .15 * sustainedGlowStrength(durationMillis);
        double motion = Math.clamp(motionGlow, 0, 1);
        double boundary = Math.clamp(boundaryAmount, 0, 1) * .25;
        return Math.clamp((Math.max(bed, motion) + boundary) * activeGlowEnvelope(wordProgress), 0, 1);
    }

    /** Slow, derivative-free entrance and exit so the framebuffer halo never pops on or off. */
    public static double activeGlowEnvelope(double wordProgress) {
        if (wordProgress <= 0 || wordProgress >= 1) return 0;
        double entrance = smoother(Math.clamp(wordProgress / .20, 0, 1));
        double exit = smoother(Math.clamp((1 - wordProgress) / .32, 0, 1));
        return entrance * exit;
    }

    /**
     * A halo peaked exactly on the sung fill boundary, falling off within a few glyph
     * widths behind it: the glow rides the sung edge instead of pulsing mid-word.
     *
     * @param signedGlyphsFromBoundary positive when the glyph lies behind the sung edge
     */
    public static double boundaryGlow(double signedGlyphsFromBoundary) {
        return Math.exp(-Math.pow(signedGlyphsFromBoundary / 1.05, 2));
    }

    /** Only the currently focused, actually singing group may emit an active-word halo. */
    public static boolean activeGlowEligible(int groupIndex, int focusedIndex, boolean groupActive,
                                             boolean simpleMode, boolean manualReading) {
        return !simpleMode && !manualReading && groupActive && groupIndex == focusedIndex;
    }

    /** Held glow for a long word, with no residue before it starts or after it finishes. */
    public static double sustainedWordGlow(long durationMillis, double wordProgress) {
        return sustainedGlowStrength(durationMillis) * sustainedGlowEnvelope(wordProgress) * 1.45;
    }

    public enum GlowBand { OUTER, CORE }

    private enum GlowLevel { SOFT, NORMAL, STRONG }

    /** Two real filtered sources are emitted only for an active glyph with visible glow. */
    public record ActiveGlowProfile(double coreOpacity, double coreRadius,
                                    double outerOpacity, double outerRadius) {
        private static final ActiveGlowProfile NONE = new ActiveGlowProfile(0, 1.4, 0, 2.8);

        public ActiveGlowProfile {
            coreOpacity = Math.clamp(coreOpacity, 0, .58);
            coreRadius = Math.clamp(coreRadius, 1.4, 2.0);
            outerOpacity = Math.clamp(outerOpacity, 0, .24);
            outerRadius = Math.clamp(outerRadius, 2.8, 4.2);
        }

        public double opacity(GlowBand band) {
            return band == GlowBand.CORE ? coreOpacity : outerOpacity;
        }

        public double radius(GlowBand band) {
            return band == GlowBand.CORE ? coreRadius : outerRadius;
        }

        public boolean needsBloom() { return coreOpacity > .005 || outerOpacity > .005; }

        public RenderPlan renderPlan() {
            return new RenderPlan(0, 0, needsBloom() ? 2 : 0);
        }
    }

    /** Counts sources, not shader passes: base text is never redrawn to fake an effect. */
    public record RenderPlan(int baseTextLayers, int distantBloomTextLayers, int activeGlowTextLayers) {
        public boolean needsBloom() { return distantBloomTextLayers > 0; }
        public boolean needsActiveGlow() { return activeGlowTextLayers > 0; }
    }

    /** Leading and trailing bounds of the filled portion of a timed word. */
    public static FillInterval fillInterval(double width, double progress, boolean rightToLeft) {
        double safeWidth = Math.max(0, width);
        double filled = safeWidth * Math.clamp(progress, 0, 1);
        return rightToLeft ? new FillInterval(safeWidth - filled, safeWidth)
                : new FillInterval(0, filled);
    }

    /** Detects the first strong directional character for a glyph-by-glyph fill direction. */
    public static boolean isRightToLeft(String text) {
        if (text == null) return false;
        for (int offset = 0; offset < text.length();) {
            int codePoint = text.codePointAt(offset);
            byte direction = Character.getDirectionality(codePoint);
            if (direction == Character.DIRECTIONALITY_RIGHT_TO_LEFT
                    || direction == Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC
                    || direction == Character.DIRECTIONALITY_RIGHT_TO_LEFT_EMBEDDING
                    || direction == Character.DIRECTIONALITY_RIGHT_TO_LEFT_OVERRIDE) return true;
            if (direction == Character.DIRECTIONALITY_LEFT_TO_RIGHT
                    || direction == Character.DIRECTIONALITY_LEFT_TO_RIGHT_EMBEDDING
                    || direction == Character.DIRECTIONALITY_LEFT_TO_RIGHT_OVERRIDE) return false;
            offset += Character.charCount(codePoint);
        }
        return false;
    }

    public record FillInterval(double start, double end) { }

    public static double durationStrength(long durationMillis) {
        double normalized = Math.clamp((durationMillis - 300.0) / 2_700.0, 0, 1);
        return smooth(normalized);
    }

    /** Quick words move as one readable block; only held words animate their glyphs separately. */
    public static boolean usesPerGlyphMotion(long durationMillis) {
        return durationMillis >= PER_GLYPH_MOTION_MIN_MILLIS;
    }

    /**
     * Chooses one motion owner for a visual word. Syllable-timed words deliberately
     * take precedence over the duration heuristic: moving their whole word would
     * make future syllables jump before their own timing begins.
     */
    public static TokenMotionPlan tokenMotionPlan(long durationMillis, int fragmentCount,
                                                   boolean simpleMode) {
        if (simpleMode) return TokenMotionPlan.NONE;
        boolean syllableMotion = fragmentCount > 1;
        boolean glyphMotion = !syllableMotion && usesPerGlyphMotion(durationMillis);
        return new TokenMotionPlan(!syllableMotion && !glyphMotion, syllableMotion, glyphMotion);
    }

    /** Exactly one of these animation owners is selected outside simple mode. */
    public record TokenMotionPlan(boolean wholeWordMotion, boolean syllableMotion,
                                  boolean glyphMotion) {
        private static final TokenMotionPlan NONE = new TokenMotionPlan(false, false, false);
    }

    public static double shortWordLift(long durationMillis, double progress) {
        if (usesPerGlyphMotion(durationMillis)) return 0;
        if (progress <= 0) return 0;
        double duration = Math.max(1, durationMillis);
        double elapsed = progress * duration;
        // Give compact words a visible, rounded ascent instead of a fast hop.
        // The cap keeps very long short-word intervals from feeling delayed.
        double enterMillis = Math.clamp(duration * .75, 220, 440);
        if (elapsed < enterMillis) return smoother(elapsed / enterMillis) * 1.08;
        // Stay elevated through the spoken interval, then settle while the next
        // word may already be active instead of snapping down beforehand.
        if (elapsed <= duration) return 1.08;
        double exit = smoother((elapsed - duration) / 640.0);
        return (1 - exit) * 1.08;
    }

    /** A gentler timing curve for vertical word, syllable and glyph elevation. */
    public static double liftKeyframe(double progress) {
        if (progress <= 0 || progress >= 1) return 0;
        if (progress < .30) return smoother(progress / .30);
        if (progress <= .64) return 1;
        return 1 - smoother((progress - .64) / .36);
    }

    public static double wordEmphasis(double progress) {
        if (progress <= 0 || progress >= 1) return 0;
        double enter = smooth(progress / .16);
        double leave = smooth((1 - progress) / .22);
        return Math.min(enter, leave);
    }

    public static double sustainedGlowStrength(long durationMillis) {
        double normalized = Math.clamp((durationMillis - 650.0) / 3_350.0, 0, 1);
        return smooth(normalized);
    }

    public static double sustainedGlowEnvelope(double progress) {
        if (progress <= 0 || progress >= 1) return 0;
        double enter = smooth(progress / .08);
        double leave = smooth((1 - progress) / .16);
        return Math.min(enter, leave);
    }

    public static double timedProgress(long now, long startMillis, long endMillis) {
        if (endMillis <= startMillis) return now >= startMillis ? 1 : 0;
        return Math.clamp((now - startMillis) / (double) (endMillis - startMillis), 0, 1);
    }

    /** A staggered, looping pulse used by the three interlude dots. */
    public static double interludePulse(long elapsedMillis, int dotIndex) {
        double cycle = Math.floorMod(elapsedMillis, 1_500L) / 1_500.0;
        double local = cycle - Math.clamp(dotIndex, 0, 2) / 3.0;
        if (local < 0) local += 1;
        if (local >= .34) return 0;
        double wave = Math.sin(local / .34 * Math.PI);
        return smooth(wave * wave);
    }

    /** Permanent left-to-right fill, with each dot owning exactly one third of the silent interval. */
    public static double interludeDotFill(double intervalProgress, int dotIndex) {
        double local = Math.clamp(intervalProgress, 0, 1) * 3 - Math.clamp(dotIndex, 0, 2);
        return smooth(Math.clamp(local, 0, 1));
    }

    /** Uses the same enter/hold/leave glow curve as timed lyric glyphs. */
    public static double interludeDotLift(double intervalProgress, int dotIndex) {
        double local = Math.clamp(intervalProgress, 0, 1) * 3 - Math.clamp(dotIndex, 0, 2);
        return glowKeyframe(Math.clamp(local, 0, 1));
    }

    public static double keyframe(double progress, double start, double peakAt, double peak, double end) {
        if (progress <= peakAt) return mix(start, peak, smooth(progress / peakAt));
        return mix(peak, end, smooth((progress - peakAt) / (1 - peakAt)));
    }

    public static double glowKeyframe(double progress) {
        if (progress < .15) return smooth(progress / .15);
        if (progress <= .60) return 1;
        return 1 - smooth((progress - .60) / .40);
    }

    public static double smooth(double value) {
        double t = Math.clamp(value, 0, 1);
        return t * t * (3 - 2 * t);
    }

    public static double smoother(double value) {
        double t = Math.clamp(value, 0, 1);
        return t * t * t * (t * (t * 6 - 15) + 10);
    }

    public static double mix(double first, double second, double amount) {
        return first + (second - first) * amount;
    }
}
