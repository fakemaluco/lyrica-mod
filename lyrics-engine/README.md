# Lyrica lyrics engine

The plain JVM core from [Spicy Lyrics Mobile](https://github.com/spicylyrics/mobile),
pinned to commit `630587fad8f24e0802e8c99d363e17fda28249fd` (2026-10-28).
Upstream Kotlin sources and tests retain their packages and notices. Licensed AGPL-3.0;
see LICENSE and UPSTREAM_NOTICES.md for attribution and third-party licenses.

`dev.lyrica.engine.DesktopLyricsEngine` is Lyrica's desktop adapter. It instantiates all
14 built-in network adapters, uses their metadata matching, parsers, quality ranking,
parallel fallback and provider cooldowns, and adds bounded HTTP responses, cancellation,
a lookup deadline and a source-policy-aware disk cache. No Android runtime is used.

The Java-facing `MultiSourceLyricsClient` maps the selected TTML/LRC/plain payload into
Lyrica's protocol model without inventing word timing. All source switches are explicit.
Genius and YouTube can return unsynchronised text. Service availability remains dependent
on each upstream service and region. Custom HTTP templates and translation services are
present in upstream core but are not exposed by this adapter.

`DesktopMotionArtworkEngine` independently wires the pinned `SpotifyCanvasFinder`
and `MotionCoverFinder` to discover Spotify Canvas MP4 loops and Apple Music album
HLS animations. Canvas can match the playing title/artist anonymously; no account
or lyrics result is required. Each kind can be disabled independently, lookups
have a shared deadline, and cancelling a future cancels its sockets. The metadata
memos are capped at 512 entries each; successful CDN URLs expire after 24 hours.
The upstream missing-result TTL and Canvas retry/backoff remain in use. Local
finder adaptations also preserve cancellation and prevent a failed Apple request
from becoming a month-long cached miss. Only HTTPS media URLs from the relevant
Spotify/Apple CDNs leave this adapter. Video decoding is separate from discovery.
