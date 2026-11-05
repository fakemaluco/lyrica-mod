# Lyrica YouTube Music Bridge

This local browser companion connects an open `music.youtube.com` tab to Lyrica. It does not ask for a Google password, read cookies, or call Google APIs. Playback information and controls travel only over the loopback WebSocket opened by the mod's media bridge.

1. In Chrome or Edge, open `chrome://extensions` or `edge://extensions`.
2. Enable **Developer mode**, choose **Load unpacked**, and select this folder. If an older version is loaded, click **Reload** on its extension card.
3. In Lyrica, click **Connect YouTube Music** under **Connections**, then open a YouTube Music tab. The button selects YouTube Music as the source; the browser companion pairs automatically while Minecraft is running.

The companion reports the current track and supports playback controls where YouTube Music exposes them. Spicy Lyrics' API requires a Spotify track ID. For Spicy Lyrics on YouTube Music, connect a Spotify developer account in Lyrica's Connections screen so it can search for a matching ID; imported lyrics work without it. A YouTube account alone cannot provide that ID through Google's official API, so lyrics availability varies.
