# Lyrica - Minecraft Music & Synced Lyrics Mod

Lyrica is a Fabric Minecraft mod that displays real-time synchronized lyrics from Spotify, YouTube Music, Apple Music, and more. Enjoy customizable 2D and 3D lyrics, playback controls, and multiplayer karaoke.

![Lyrica banner](https://i.postimg.cc/ryYtqpRn/lyricabanner.png)

[![Latest release](https://img.shields.io/github/v/release/fakemaluco/lyrica-mod?label=release)](https://github.com/fakemaluco/lyrica-mod/releases/latest)

**Music, playback controls, and synchronized lyrics inside Minecraft.**

[Download](#download) · [Features](#features) · [Multiplayer](#multiplayer) · [Privacy](#privacy) · [Documentation](#documentation)

## New in 0.9.4

Lyrica now supports Minecraft 26.3. The karaoke panel can open the Spotify host’s playback queue, and world lyrics now have a minimum and maximum placement distance. The sung-text gradient spans the whole phrase by default. See the [0.9.4 release notes](docs/releases/0.9.4.md).

## Features

### Your music in Minecraft

![In-game player animation](https://i.postimg.cc/d3vnvmHZ/musicingame-animado.gif)

Pause, skip, seek, and adjust the volume from the in-game player. On Windows, Lyrica can pick up Spotify, YouTube, Apple Music, and other players that expose media controls to the system. Start your music and open the player in Minecraft.

![Player and synchronized lyrics](https://i.postimg.cc/zDpFK5bV/01-player-letras.webp)

![Section divider](https://i.postimg.cc/Nf39rP4P/divisor.png)

### Lyrics in your world

![Animated world lyrics](https://i.postimg.cc/rmWc7c3w/Imerssive-Lyrics-ondas-v2.gif)

Follow synchronized lyrics in the world, in the on-screen panel, or in the compact player. Word-by-word timing depends on the lyrics available for the track.

![Lyrics rendered in a Minecraft scene](https://i.postimg.cc/3JJ9cW5V/02-letras-no-cenario.webp)

![Section divider](https://i.postimg.cc/Nf39rP4P/divisor.png)

### Make it yours

![Customization preview](https://i.postimg.cc/T1Y7gJV2/Customization-animado.gif)

Choose fonts, adjust the layout, and change where lyrics appear. Lyrica supports multiple lyrics sources, romanization, and animated artwork or Canvas video when available.

![Lyrica appearance settings](https://i.postimg.cc/t40wJfSy/Appearance-original-restaurado.png)

## Multiplayer

![Listening together preview](https://i.postimg.cc/tRwmvwHP/listenwithothers-animado.webp)

**Music Presence** shows what other Lyrica players are listening to when they share their music. Manage sharing in the connection settings or use `/music sharing off`.

With Lyrica installed on the server and clients, use **`/karaoke pop-up`** to place a shared player in the world. Guests accept on the panel before receiving the host’s playback and lyrics. The host can move or resize it, and connected Spotify guests can sync their local playback. The session follows the host’s next songs until it is closed.

## Download

1. Install [Fabric Loader](https://fabricmc.net/use/installer/) and [Fabric API](https://modrinth.com/mod/fabric-api) for your Minecraft version.
2. Get the matching Lyrica JAR from [GitHub Releases](https://github.com/fakemaluco/lyrica-mod/releases/latest).
3. Place the JAR in your instance’s `mods` folder and launch Minecraft.

| Minecraft | Java | JAR prefix |
| --- | --- | --- |
| 1.21.1 | 21 | `lyrica-1.21.1-` |
| 1.21.11 | 21 | `lyrica-1.21.11-` |
| 26.2 | 25 | `lyrica-26.2-` |
| 26.3 | 25 | `lyrica-26.3-` |

The release JARs include the native Windows x64 media bridge.

## Privacy

Enabled online lyrics providers receive track information such as title and artist to look up lyrics. You can manage those providers in **Sources**. Spotify account access and Gemini experiments are optional.

Music Presence shares track metadata when sharing is enabled. With Lyrica on the server, the Minecraft server relays that information. On other servers, the optional verified sharing path uses Supabase. A karaoke room sends track details, playback position, and timed lyrics to guests who accept the invitation.

See [Privacy and data controls](docs/PRIVACY.md) for settings and details.

## Documentation

- [User guide in Portuguese](docs/GUIDE.md)
- [Cinematic world lyrics](docs/cinematic-lyrics.md)
- [Project structure and build instructions](docs/DEVELOPMENT.md)
- [License](LICENSE) and [third-party notices](THIRD_PARTY_NOTICES.md)

Created by **Fakiz**. Inspired by [Spicy Lyrics](https://github.com/Spikerko/spicy-lyrics).
