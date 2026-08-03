# Relay

Relay (technical mod ID `fandroid`) is an AGPL-3.0-licensed retro phone for Minecraft Fabric 1.21.1 and
1.21.11. Press the
remappable **Left Alt** key to open a non-pausing 3:4 phone in the lower-right
corner. The same universal JAR can run client-only or add encrypted server chat
and voice features when installed on a server.

## What is implemented

- A square-cornered, early iPod/iPhone-inspired 768×1024 logical phone UI,
  35–70% height scaling, 3×3 app grid, unread badges, offline states,
  incoming-call modal and an independent lyrics overlay.
- Local per-account SQLite history with migrations, FTS5 search, pagination,
  unread state, drafts, edits, tombstones and blocks.
- Separate `GLOBAL`, `SERVER` and experimental `ESSENTIAL` transports. They are
  never mirrored automatically.
- AES-256-GCM content encryption, RFC 9180 HPKE X25519/HKDF-SHA256/AES-256-GCM
  key wrapping per device, Ed25519 signatures and replay protection.
- A Javalin/PostgreSQL global backend with Mojang session proof, contacts,
  blocks, group invites/ownership transfer, multi-device deliveries, REST,
  WebSocket notifications, ACK deletion, seven-day expiry, health and metrics.
- Optional Simple Voice Chat integration. Voice-note recording cancels
  proximity output and produces Opus frames/waveforms; active call microphone
  packets are cancelled and forwarded only through targeted static channels.
- System media bridge in Rust (Windows GSMTC, Linux MPRIS, macOS Spotify
  AppleScript) plus an optional paired Spicetify loopback extension.
- The paired extension forwards Spotify CDN cover art and reuses the current
  Spicy Lyrics result from its local TTML IndexedDB or processed Cache Storage.
  This path stays on `127.0.0.1`; AMLL and LRCLIB remain network fallbacks.
- Music has separate square `PLAYER` and `LYRICS` views. The player includes
  cover-derived color/background, absolute timeline scrubbing, previous,
  play/pause, next, shuffle, repeat and normalized volume, with unsupported
  controls disabled from the negotiated media capabilities.
- Lyrics are resolved in this order: local imported TTML, Spicy raw TTML,
  Spicy processed cache, AMLL by Spotify ID, AMLL metadata match, then LRCLIB.
  Local imports live in `config/fandroid/lyrics/imports`; ambiguous files are
  shown for an explicit choice instead of being selected silently.
- AMLL TTML DB word-sync lookup by Spotify URI, ETag caches and Unicode-aware
  ranked metadata fallback. Ambiguous community revisions are ranked and
  loaded automatically instead of blocking the lyric view.
- LRCLIB fallback for synchronized LRC, with duration-aware matching, local
  cache and support for classic line timing plus enhanced word timestamps.
- Hardened TTML/LRC parsing for Apple/AMLL line/word timing, ruby, duet agents,
  overlapping vocal groups, multiple backing vocals, inline/sidecar translation
  and romanization. The default Minecraft font is rendered with cached layout,
  fractional spring motion, per-syllable lift/glow, focus-by-distance and
  animated interludes in both the Music app and draggable HUD.

Relay does **not** use the Spotify Web API or OAuth and does not scrape Apple
Music. The Music app uses neutral branding.

## Optional mod compatibility

All integrations remain optional; Relay starts normally when none of these mods
is installed.

- **Minecraft Cursor** (1.21.1) and **Cursors Extended** (1.21.11): pointer,
  grabbing and resize cursors are requested for the phone and lyrics popup.
- **Mouse Tweaks**: Relay owns drag/scrub input while the pointer is over its
  inventory overlay, preventing inventory gestures from stealing it.
- **EMI / REI**: recipe, favorites and search panels are treated as solid GUI
  areas when placing or dragging the standalone lyrics popup and album card.
- **Iris / Sodium / ImmediatelyFast**: custom lyric-background rendering uses
  version-safe GUI submission and restores neutral render state after effects.
- **Controlify**: Relay's directional, confirm, back and virtual-pointer paths
  remain navigable through Controlify's standard GUI controls.
- **Xaero's Minimap / AppleSkin**: the normal HUD popup avoids their common
  minimap and food-information regions.
- **Mod Menu**: the Relay Lyrics standalone build exposes its configuration
  screen through the standard Configure button.

## Repository

| Path | Purpose |
|---|---|
| `mod` | Fabric 1.21.1 client/server mod |
| `mod-1.21.11` | Fabric 1.21.11 client/server port |
| `shared-protocol` | Protocol records, UUIDv7, crypto, media and TTML models |
| `backend` | Global delivery service |
| `media-bridge` | Cross-platform Rust media helper and loopback bridge |
| `spicetify-extension` | Optional richer Spotify Desktop connector |

## Build

Requirements are Java 21, Rust stable and Node 24+.

```text
./gradlew :mod:build :mod-1.21.11:build
cd media-bridge && cargo build --release
cd spicetify-extension && npm ci && npm run typecheck && npm run build
```

The version-specific Fabric JARs are written to
`mod/build/libs/fandroid-0.1.0.jar` (Minecraft 1.21.1) and
`mod-1.21.11/build/libs/fandroid-1.21.11-0.1.0.jar` (Minecraft 1.21.11). Put
only the matching JAR in the client or server `mods` directory with the matching
Fabric API. Simple Voice Chat and Essential are optional and Relay starts
without either one.

To enable Global Chat, host the backend and set `FANDROID_BACKEND_URL` in the
Minecraft launcher environment to its HTTPS origin. The access token used for
proof is posted directly to Mojang's official session server and is never sent
to Relay.

## Backend

```text
copy .env.example .env
docker compose up --build
```

For automatic TLS, configure `FANDROID_DOMAIN` and run
`docker compose --profile tls up --build`. Real hosting, DNS and backups remain
operator responsibilities.

## Media bridge

Release JARs carry the media bridge for their platform and extract it automatically
to `config/fandroid/bin/`. A development build can still override it with the JVM
property `-Dfandroid.mediaBridge=/absolute/path/to/binary`. The helper uses NDJSON
over stdin/stdout. Its optional Spicetify listener binds only to `127.0.0.1:24873` and emits a
temporary six-digit pairing code. The paired token is persisted beside the
helper as `fandroid-spicetify.token`, so restarting Minecraft does not require
pairing again. The Music app always reports whether Spicetify is connected and
keeps the current re-pair code visible. Empty extension snapshots never replace
valid Windows GSMTC, Linux MPRIS or macOS system-media data. Delete the token
file and use **Pair again** from the Spotify phone icon to revoke/re-pair.
When Spicy Lyrics is installed, no extra pairing is required: `fandroid.js`
reads only that extension's same-origin lyric cache, forwards the current
Spotify track result, and Relay stores a local copy for later playback.
The extension is optional: without it, the bundled helper reads the active system
media session (including Windows artwork) and Relay resolves lyrics through
AMLL/LRCLIB. Relay does not request or persist a Spotify Client ID or Client
Secret.
Protocol v2 reports extension version/capabilities and per-track
`HIT_TTML`/`HIT_CACHE`/`MISS`/`ERROR`; Settings warns when Spotify is still
running an old injected bundle. Install or update it on Windows with:

```powershell
powershell -ExecutionPolicy Bypass -File .\spicetify-extension\install.ps1
```

The installer builds, copies, enables and applies the extension. Restarting
Spotify alone does not replace `spicetify apply`.

The lyric motion model is adapted from Spicy Lyrics under AGPL-3.0. Relay
uses Minecraft's default font renderer; attribution is in
[`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md).

## Privacy boundaries

- Delivered decrypted history remains only in the local account cache until
  the player deletes it; clearing that cache is irreversible.
- Services keep routing metadata plus encrypted pending envelopes. Pending
  envelopes disappear after all devices ACK them or after seven days.
- Voice and calls never cross Minecraft servers and require Simple Voice Chat
  on both client and server.
- Essential chats remain under Essential's own storage/privacy rules and are
  not represented as Relay-encrypted conversations.

See [protocol and threat model](docs/PROTOCOL.md) and [test matrix](docs/TESTING.md).
