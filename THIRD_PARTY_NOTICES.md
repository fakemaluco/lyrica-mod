# Third-party notices

## Spicy Lyrics

The Lyrica presentation is visually inspired by animation concepts and spring-based
motion from [Spicy Lyrics](https://github.com/Spikerko/spicy-lyrics), by
Spikerko and contributors. Spicy Lyrics is distributed under the GNU Affero
General Public License version 3. Lyrica is consequently distributed under
the same license; see `LICENSE`.

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

Lyrics are requested at runtime through the official
[Spicy Lyrics API](https://developers.spicylyrics.org/docs). No lyrics are
bundled with Lyrica.

## Unicode ICU4J

Missing provider-supplied romanization is generated locally with ICU4J's
Unicode transliterators. ICU4J is distributed under the Unicode License V3.
