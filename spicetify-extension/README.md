# Relay Spicetify extension

On Windows, run `powershell -ExecutionPolicy Bypass -File .\install.ps1`. The
installer typechecks/builds the extension, copies `dist/fandroid.js`, verifies
that `config-xpui.ini` enables it, and runs `spicetify apply`. Copying the file
without applying it leaves Spotify on the previous injected bundle.

For a manual install, build with `npm ci && npm run build`, copy
`dist/fandroid.js` into the Spicetify Extensions directory, add `fandroid.js`
to `extensions` in `config-xpui.ini`, and run `spicetify apply`.

The extension talks only to `ws://127.0.0.1:24873`. Pair it with the temporary
six-digit code emitted by the Relay media bridge. No Spotify Web API key or
OAuth flow is used.

After applying the extension, use the phone icon in Spotify's top bar. It shows
offline, ready-to-pair and connected states without repeatedly opening pairing
dialogs. A saved token is retried once; an expired token returns the button to
the ready-to-pair state. While connected, open the same icon and choose
**Pair again** to discard the saved browser token and enter the current code
shown in Minecraft.

If Spicy Lyrics is installed, the paired extension also looks up the current
track in Spicy Lyrics' same-origin IndexedDB/Cache Storage. Raw local TTML is
preferred; otherwise the processed syllable/line data is sent. Cover artwork is
sent as a Spotify CDN URL and downloaded by Minecraft with size and host checks.
Protocol v2 reports whether the current track hit raw TTML, processed Spicy
cache, missed, or failed, so Minecraft can diagnose old extension bundles.
