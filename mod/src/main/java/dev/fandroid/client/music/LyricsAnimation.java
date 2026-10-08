package dev.fandroid.client.music;

/** Pure timing curves shared by compatible and enhanced lyric render passes. */
public final class LyricsAnimation {
    public static final long INTERLUDE_MIN_GAP_MILLIS = 3_000;
    private static final long INTERLUDE_DOT_TIMELINE_TRIM_MILLIS = 550;
    private static final long INTERLUDE_PRE_HIDE_MILLIS = 500;
    private static final long INTERLUDE_EXIT_MILLIS = 400;
    private static final long INTERLUDE_ENTER_MILLIS = 140;
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
    private static final long PER_GLYPH_MOTION_MIN_MILLIS = 1_000;
    private static final long WHOLE_WORD_LIFT_TAPER_START_MILLIS = 800;
    private static final long PER_GLYPH_FULL_STRENGTH_MILLIS = 2_500;

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
     * A centered Gaussian halo from the active word's full glyph coverage.
     * Intensity changes its opacity; typography and radius remain independent.
     */
    public static ActiveGlowProfile activeGlowProfile(double glowAmount, double intensityMultiplier) {
        return activeGlowProfile(glowAmount, intensityMultiplier, false);
    }

    /** Spicy's single centered text-shadow; CSS pixels normalized to its 28px popup font. */
    public static ActiveGlowProfile activeGlowProfile(double glowAmount, double intensityMultiplier,
                                                      boolean emphasized) {
        double signal = Math.clamp(glowAmount, 0, 2);
        if (signal <= .001) return ActiveGlowProfile.NONE;
        double opacity = Math.clamp((emphasized ? 1.85 : .35) * signal * intensityMultiplier, 0, 1);
        double radius = (4 + (emphasized ? 12 : 2) * signal) * 9.0 / 28.0;
        return new ActiveGlowProfile(opacity, radius, 0, radius);
    }

    /** Natural cubic spline from Spicy's (0,0),(.15,1),(.6,1),(1,0) glow timeline. */
    public static double spicyGlowTarget(double progress) {
        if (progress <= 0 || progress >= 1) return 0;
        double left, right, first, second, curvatureLeft, curvatureRight;
        if (progress < .15) {
            left = 0; right = .15; first = 0; second = 1;
            curvatureLeft = 0; curvatureRight = -100.0 / 3;
        } else if (progress < .6) {
            left = .15; right = .6; first = second = 1;
            curvatureLeft = -100.0 / 3; curvatureRight = 0;
        } else {
            return (1 - progress) / .4;
        }
        double h = right - left, a = (right - progress) / h, b = (progress - left) / h;
        return a * first + b * second
                + ((a * a * a - a) * curvatureLeft + (b * b * b - b) * curvatureRight) * h * h / 6;
    }

    /** Stable halo bed only for held words that animate their glyphs independently. */
    public static double activeWordGlow(long durationMillis, double wordProgress) {
        if (!usesPerGlyphMotion(durationMillis) || wordProgress <= 0 || wordProgress >= 1) return 0;
        double envelope = activeGlowEnvelope(wordProgress);
        double heldBoost = .15 * sustainedGlowStrength(durationMillis);
        return (.70 + heldBoost) * envelope;
    }

    /** Only sung glyphs cast a shadow; muted future text stays light and readable. */
    public static double sungShadowOpacity(double activeAlpha, double coverage) {
        double sung = smoother(Math.clamp((coverage - .45) / .55, 0, 1));
        return Math.clamp(activeAlpha, 0, 1) * sung;
    }

    /** Every timed word has a smooth glow envelope, even when it is too short for glyph motion. */
    public static double activeGlyphGlow(double motionGlow, long durationMillis,
                                         double wordProgress, double boundaryAmount) {
        if (wordProgress <= 0 || wordProgress >= 1) return 0;
        double bed = .48 + .37 * sustainedGlowStrength(durationMillis);
        double motion = Math.clamp(motionGlow, 0, 1);
        double boundary = Math.clamp(boundaryAmount, 0, 1) * .25;
        return Math.clamp((Math.max(bed, motion) + boundary) * activeGlowEnvelope(wordProgress), 0, 1);
    }

    /** Slow, derivative-free entrance and exit so the framebuffer halo never pops on or off. */
    public static double activeGlowEnvelope(double wordProgress) {
        if (wordProgress <= 0 || wordProgress >= 1) return 0;
        double entrance = smoother(Math.clamp(wordProgress / .26, 0, 1));
        double exit = smoother(Math.clamp((1 - wordProgress) / .38, 0, 1));
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

    /** A single alpha-blurred source, separate from the sharp face and optional black shadow. */
    public record ActiveGlowProfile(double coreOpacity, double coreRadius,
                                    double outerOpacity, double outerRadius) {
        private static final ActiveGlowProfile NONE = new ActiveGlowProfile(0, 1.4, 0, 2.8);

        public ActiveGlowProfile {
            coreOpacity = Math.clamp(coreOpacity, 0, 1);
            coreRadius = Math.clamp(coreRadius, 0, 9);
            outerOpacity = 0;
            outerRadius = coreRadius;
        }

        public double opacity(GlowBand band) {
            return band == GlowBand.CORE ? coreOpacity : outerOpacity;
        }

        public double radius(GlowBand band) {
            return band == GlowBand.CORE ? coreRadius : outerRadius;
        }

        public boolean needsBloom() { return coreOpacity > .005 || outerOpacity > .005; }

        public RenderPlan renderPlan() {
            return new RenderPlan(0, 0, needsBloom() ? 1 : 0);
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
     * Makes the first individually animated words visibly articulate instead of
     * inheriting the very weak low-duration end of the general emphasis curve.
     */
    public static double individualMotionStrength(long durationMillis) {
        if (!usesPerGlyphMotion(durationMillis)) return 0;
        double progress = (durationMillis - PER_GLYPH_MOTION_MIN_MILLIS)
                / (double) (PER_GLYPH_FULL_STRENGTH_MILLIS - PER_GLYPH_MOTION_MIN_MILLIS);
        return mix(.55, 1, smoother(progress));
    }

    /**
     * Chooses the outer motion owner for a visual word. Syllable-timed words never
     * move as a whole; each fragment may additionally articulate its own glyphs
     * when that fragment independently reaches the held-duration threshold.
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

        /** A long synchronized syllable may articulate its own letters without moving sibling syllables. */
        public boolean glyphMotionForFragment(long fragmentDurationMillis) {
            return glyphMotion || (syllableMotion && usesPerGlyphMotion(fragmentDurationMillis));
        }
    }

    public static double shortWordLift(long durationMillis, double progress) {
        if (usesPerGlyphMotion(durationMillis)) return 0;
        if (progress <= 0) return 0;
        double duration = Math.max(1, durationMillis);
        double elapsed = progress * duration;
        // Near the one-second handoff, ease down the block motion so switching
        // to independently animated glyphs does not create a visual step.
        double taper = smoother((duration - WHOLE_WORD_LIFT_TAPER_START_MILLIS)
                / (double) (PER_GLYPH_MOTION_MIN_MILLIS - WHOLE_WORD_LIFT_TAPER_START_MILLIS));
        double liftAmount = 1.08 * mix(1, .72, taper);
        // Give compact words a visible, rounded ascent instead of a fast hop.
        // The cap keeps very long short-word intervals from feeling delayed.
        double enterMillis = Math.clamp(duration * .75, 220, 440);
        if (elapsed < enterMillis) return smoother(elapsed / enterMillis) * liftAmount;
        // Stay elevated through the spoken interval, then settle while the next
        // word may already be active instead of snapping down beforehand.
        if (elapsed <= duration) return liftAmount;
        double exit = smoother((elapsed - duration) / 640.0);
        return (1 - exit) * liftAmount;
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

    /** Spicy-style dot timeline: all three dots finish 550 ms before the next lyric. */
    public static double interludeProgress(long elapsedMillis, long gapMillis) {
        if (elapsedMillis <= 0 || gapMillis <= INTERLUDE_DOT_TIMELINE_TRIM_MILLIS) return 0;
        long activeTimeline = gapMillis - INTERLUDE_DOT_TIMELINE_TRIM_MILLIS;
        return Math.clamp(elapsedMillis / (double) activeTimeline, 0, 1);
    }

    /** Fast 140 ms entrance, then a 400 ms collapse beginning 500 ms before the lyric. */
    public static double interludeVisibility(long elapsedMillis, long gapMillis) {
        if (gapMillis < INTERLUDE_MIN_GAP_MILLIS || elapsedMillis <= 0
                || elapsedMillis >= gapMillis) return 0;
        double enter = smooth(elapsedMillis / (double) INTERLUDE_ENTER_MILLIS);
        long remaining = gapMillis - elapsedMillis;
        double leave = remaining >= INTERLUDE_PRE_HIDE_MILLIS ? 1
                : smooth((remaining - (INTERLUDE_PRE_HIDE_MILLIS - INTERLUDE_EXIT_MILLIS))
                / (double) INTERLUDE_EXIT_MILLIS);
        return Math.min(enter, leave);
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
