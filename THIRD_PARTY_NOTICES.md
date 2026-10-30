# Third-party notices

## Settings interface and Inter

The centered settings dialog is adapted from Spicy Lyrics' settings panel and
lavender design tokens, commit `34ad8c683204c522f227060475bf4f8aae36508e`,
under AGPL-3.0. Minecraft-specific widgets, scaling and input handling are local
adaptations.
The settings motion timings and easing curves also follow `settings-panel.css`
and `tokens.css` at that revision.
The glass timeline, volume capsule, white clipped fills, hover expansion and time
preview follow `ContentBox.css`, `CanvasImmersive.css` and `tokens.css` at the same
desktop revision. The Canvas volume placement on the left and the Minecraft hit
regions, rendering cache and input mapping are Lyrica adaptations.
The desktop background selector and artist-header fallback follow
`BackgroundSection.tsx`. The Kawarp domain warp is adapted from Spicy Mobile's
`KawarpBackground.kt`, with `KawarpBlurCore.kt` retained in the JVM engine,
at mobile commit `630587fad8f24e0802e8c99d363e17fda28249fd`. That port follows
Better Lyrics' MIT-licensed `@kawarp/core`; its attribution and license are in
`lyrics-engine/UPSTREAM_NOTICES.md` and are bundled with the engine.
The settings typeface is a static derivative of Inter, by Rasmus Andersson and
contributors, licensed under the SIL Open Font License 1.1. The
bundled derivative is named Lyrica Settings; the original font and license are
in `assets-src/settings-font/`, with the license also in the packaged font folder.

## Spicy Lyrics

The Lyrica presentation is visually inspired by animation concepts and spring-based
motion from [Spicy Lyrics](https://github.com/Spikerko/spicy-lyrics), by
Spikerko and contributors. Spicy Lyrics is distributed under the GNU Affero
General Public License version 3. Lyrica is consequently distributed under
the same license; see `LICENSE`.

World lyrics reuse the popup's Spicy-derived word/glyph springs while preserving
syllable fill timing. The spatial layout and closed glyph extrusion are Lyrica
implementations, using whichever font is selected for the popup.

## Optional LambDynamicLights integration

Terrain lighting uses the public LambDynamicLights 4.8+ API, by LambdAurora and
contributors. This is an optional, separately installed mod. Its API is used only
for compilation: no LambDynamicLights API/runtime classes or binaries are bundled
in Lyrica. LambDynamicLights is distributed under the Lambda License by its author;
obtain it from [the official project](https://modrinth.com/mod/lambdynamiclights).
See [API documentation](https://lambdaurora.dev/projects/lambdynamiclights/docs/v4/)
and [distribution permissions](https://lambdaurora.dev/projects/lambdynamiclights/permissions/).

The supplied playback, heart and volume SVGs in `assets-src/spicy-lyrics-icons`
are exports from Spicy Lyrics and are distributed under that project's
AGPL-3.0 license. The game uses rasterized versions of these SVGs; the pause
icon was drawn for Lyrica to match the set.

## Fraktality Spring

The analytic spring used by the lyrics renderer is a Java adaptation of the
spring implementation credited by Spicy Lyrics to
[Fraktality/spr](https://github.com/Fraktality/spr), originally published
under the MIT License. Its copyright and permission notice remain applicable
to that implementation.

## Lyrics service

Lyrics are requested at runtime from the sources enabled in Lyrica. No lyrics are
bundled with Lyrica.

## Spicy Lyrics Mobile JVM core

The source adapters, metadata matching, TTML/LRC conversion, provider ranking and
cooldowns in `lyrics-engine` are vendored from [Spicy Lyrics Mobile](https://github.com/spicylyrics/mobile),
by its contributors, at commit `630587fad8f24e0802e8c99d363e17fda28249fd`.
These sources retain their packages under AGPL-3.0. Lyrica's desktop adapter and
Java protocol mapping are additions. The upstream license and complete third-party
notices are preserved in `lyrics-engine/LICENSE` and `lyrics-engine/UPSTREAM_NOTICES.md`
and packaged in the engine JAR. Those notices cover the adapted parser/romanization
code and the dependencies, including Kuromoji, OkHttp, Retrofit, Gson, Kotlin and
kotlinx.coroutines.

The word/letter natural spline targets, spring tuning and 50 ms line-scroll trail
in Lyrica's Java renderer are adapted from the same pinned project's
`LyricsAnimator.kt` and `ScrollTrail.kt`, under AGPL-3.0. Existing Lyrica glyph
and glow rendering remains in use.

## Canvas, animated album artwork and video runtime

The Canvas and Apple Music animated-cover finders are reused from the same
pinned Spicy Lyrics Mobile core. Their local adaptations bound the memo files,
expire CDN URLs and preserve cancellation during optional Apple lookups.

Silent video playback uses JavaCV/JavaCPP 1.5.11 and the standard shared FFmpeg
7.1 native build. The complete notices, license texts, matching source and build
recipe links are in `lyrics-engine/VIDEO_NOTICES.md` and `lyrics-engine/licenses/video/`,
also packaged under `META-INF/lyrica-engine/` in the engine JAR.

## Spicy Mobile glass controls

The glass material and interaction styling in `GlassButtonRaster`, `LiquidGlass`,
the popup settings button and the settings utility controls are adapted from
`ui/components/GlassButton.kt`, `ui/components/SpicyGlyphs.kt` and `ui/theme/SpicyTokens.kt` in
[Spicy Lyrics Mobile](https://github.com/spicylyrics/mobile/tree/f806691d0f2e7c2bdaf7a8b3261fd34e295c22c0),
commit `f806691d0f2e7c2bdaf7a8b3261fd34e295c22c0`, by its contributors,
under AGPL-3.0. The upstream license is included in `lyrics-engine/LICENSE`
and in the nested runtime JAR. Lyrica supplies the Minecraft raster, texture
cache and cover-region blur adapters.

## Unicode ICU4J

Missing provider-supplied romanization is generated locally with ICU4J's
Unicode transliterators. ICU4J is distributed under the Unicode License V3.
