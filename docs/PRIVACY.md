# Privacy and data controls

[Back to Lyrica](../README.md)

| Feature | Data used | Control |
| --- | --- | --- |
| Media detection | The local media session provides track details and playback state. | Choose a source under **Connections**. |
| Lyrics lookup | Enabled online providers receive lookup information such as track title, artist, or a track identifier. | Enable or disable providers in **Sources**. |
| Spotify connection | The optional connection uses Spotify account authorization to read and control playback. | Disconnect Spotify under **Connections**. |
| Music Presence | Sharing makes track title, artist, and cover available to other Lyrica players. Servers running Lyrica relay presence directly. The fallback uses Supabase and requires a verified account to share. | Turn sharing off under **Music Presence** or run `/music sharing off`. |
| Karaoke | The Minecraft server relays the room and its position. Guests who accept receive the current track, playback position, and timed lyrics. Players near the panel can see participants’ pointers. Connected Spotify guests can sync playback on their own device. | Accept or dismiss the invitation on the panel. Use `/karaoke stop` to leave or end the session. |
| Gemini experiments | When enabled, the selected feature can send music context to Google. Lyrics direction uses lyrics, timings, and track metadata. | Leave experiments disabled or turn them off under **Advanced → Experiments**. |
