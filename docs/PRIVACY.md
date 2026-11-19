# Privacy and data controls

[Back to Lyrica](../README.md)

| Feature | Data used | Control |
| --- | --- | --- |
| Media detection | The local media session provides track details and playback state. | Choose a source under **Connections**. |
| Lyrics lookup | Enabled online providers receive lookup information such as track title, artist, or a track identifier. | Enable or disable providers in **Sources**. |
| Spotify connection | The optional connection uses Spotify account authorization to read and control playback. | Disconnect Spotify under **Connections**. |
| Music Presence | Sharing makes track title, artist, and cover available to other Lyrica players. Servers running Lyrica relay presence directly. The fallback uses Supabase and requires a verified account to share. Its public status metric counts verified accounts with recent sync activity in aggregate. | Turn sharing off under **Music Presence** or run `/music sharing off`. Turn Music Presence off to stop using the service. |
| Group listening | A host who starts a session shares the current track, playback position, pause state, and an ordered Spotify queue with listeners in the same server or with the invitation code. Supabase expires the current snapshot 25 seconds after the last update. | Start with `/music listen share` and revoke the code with `/music listen unshare`. |
| Public status | The mod reports an ephemeral random session ID, counters, error categories and an optional known public Minecraft network code. The public page shows aggregates. The limiter keeps a short-lived daily digest of the network address. | Turn off **Anonymous status metrics** under **Connections → Connection details**. |
| Karaoke | The Minecraft server relays the room and its position. Guests who accept receive the current track, playback position, and timed lyrics. Players near the panel can see participants’ pointers. Connected Spotify guests can sync playback on their own device. | Accept or dismiss the invitation on the panel. Use `/karaoke stop` to leave or end the session. |
| Gemini experiments | When enabled, the selected feature can send music context to Google. Lyrics direction uses lyrics, timings, and track metadata. | Find lyrics direction under **World 3D → Customize World 3D → Gemini** and shader generation under **Advanced → Experiments**. |
