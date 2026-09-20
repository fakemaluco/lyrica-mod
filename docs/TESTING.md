# Test matrix

Automated checks:

- UUIDv7 ordering/version, JSON codecs, HPKE multi-device round trips, Ed25519,
  tampering, wrong-device rejection and replay detection.
- TTML line/word timing, ruby, metadata, `IsPartOfWord`, overlapping duet
  groups, multiple backing vocals, translations, malformed XML and XXE;
  classic/enhanced LRC timestamps, offsets and bounded input.
- PostgreSQL/Testcontainers contact acceptance, direct conversation,
  encrypted offline delivery, per-device ACK and final deletion.
- Fabric common/client compilation, deterministic playback-clock correction
  and voice-call registry lifecycle.
- Rust compilation on Windows, Linux and macOS; TypeScript strict checking and
  extension bundling.

Release candidates also require the manual integration matrix below because
audio devices and Minecraft windows cannot be faithfully represented by a unit
test:

1. Run dedicated Fabric server plus two clients at 1280×720, 1920×1080 and 4K,
   varying GUI scale. Verify Alt, Escape, phone scaling, badges and lyrics drag.
2. Repeat with no server mod, no Voice Chat, no Essential and no media helper.
   The universal JAR must still load and the phone must open.
3. Exercise Server Chat create/edit/delete/reply, reconnect and seven-day queue
   using a shortened retention fixture.
4. With Simple Voice Chat 1.21.1-2.6.21, record/play a 60-second Opus note, make
   1:1 and group calls, mute/hang up/disconnect, and use a third proximity
   listener to confirm the caller's outgoing audio does not leak.
5. Install through `spicetify-extension/install.ps1`, verify protocol v2 hello
   and `HIT_TTML`/`HIT_CACHE`/`MISS` status, pair Spicetify, revoke by restarting
   the helper, test system-only fallback,
   then verify Spotify cover art and use Spicy Lyrics cached TTML/JSON plus word,
   line, duet, backing, translation and romanization AMLL tracks,
   plus an LRCLIB-only line-synchronised track and an offline cached track.
   Confirm the active syllable lifts and changes color, duet lines alternate
   alignment, background vocals remain secondary, and large lines wrap without
   leaving the lyric panel.
6. Drag and click the Player timeline, click a lyric line, test volume,
   shuffle/repeat and a snapshot with each capability disabled. Exercise local
   imports by exact Spotify ID, metadata, ambiguity, oversize and XXE fixtures.
7. Compare `Compatible`, `Enhanced` and `Auto`; force an enhanced rendering
   failure and verify the automatic compatible fallback without closing Music.
