<!-- Suggested project summary (use in Modrinth's Summary field):
Animated, synchronized lyrics for Minecraft. Keep your music close with a customizable overlay, immersive 3D lyrics, and animated artwork.

The images below reuse existing repository media. For Modrinth, upload current
in-game captures to the project and replace these URLs with the uploaded URLs.
The existing GIF may not showcase the newest features.
-->

![Lyrica](https://raw.githubusercontent.com/fakemaluco/lyrica-mod/main/docs/media/banner.png)

# Your music. Your world. Your lyrics.

Your next build. A long flight home. That one song on repeat.

**Lyrica brings synchronized music lyrics into Minecraft**, with expressive word-by-word animation, artwork in motion, and a presentation you can make your own. Keep your song in a compact overlay, or let its words become part of the world around you.

**Fabric · Client-side · Minecraft 1.21.1 / 1.21.11 / 26.2 / 26.3**

![Lyrica in Minecraft](https://raw.githubusercontent.com/fakemaluco/lyrica-mod/main/docs/media/preview.gif)

## Feel every word

Follow each phrase as it unfolds, with flowing transitions and words that light up in time with the vocals. Lyrica searches multiple lyric sources, prioritizing richer synchronization when available. Translation and romanization help you follow songs beyond your own language.

Not every track has word-level timing. Depending on the available lyrics, Lyrica can also display line-synced or plain text, and supports imported local lyrics.

## Let the lyrics into your world

Take the experience beyond the interface. Optional **3D lyrics** place animated words in the Minecraft world, adapting their placement to the terrain and the way you move—from a quiet walk to an elytra flight.

Choose a subtle overlay for everyday play or give the song more room. Both are part of Lyrica.

## A little closer to your music

Album artwork, **Spotify Canvas**, and animated album covers bring the current track into view. Dynamic backgrounds draw from the artwork, while fonts, effects, position, and size let you shape the presentation around your game.

Move and resize the overlay directly on screen. Keep it understated, make it expressive, or adjust when it appears. Playback controls stay within reach when supported by your connection.

## Discover what others are listening to

**Music Presence** adds a musical note to the player list and lets you discover a player's current song through an in-world card or `/music <player>`.

It works between players using Lyrica, without a Minecraft server installation. Sharing is optional, can be turned off, and requires a verified Minecraft account.

## Start listening

1. Install **Fabric Loader** and **Fabric API** for your Minecraft version.
2. Download the matching Lyrica `.jar` from [Releases](https://github.com/fakemaluco/lyrica-mod/releases) and place it in your `mods` folder.
3. Launch the game and open Lyrica's settings. Under **Connections**, choose your music source and follow the setup steps.

On Windows, automatic detection can follow apps that expose a system media session, without a music-service login. Connect your Spotify account for Spotify Connect playback and queue controls. YouTube Music has its own local companion.

Lyrica follows music playing in another app; it does not stream music itself. You only need to install the mod on your client, including when playing on a server. Minecraft 26.2 and 26.3 require Java 25; the 1.21.x builds require Java 21.

<details>
<summary>Good to know</summary>

- Lyrics, translations, Canvas, and animated covers depend on the track and the services providing them. Some songs will only have static artwork or no available lyrics.
- Translation sends lyric text to your selected provider. Some providers require your own API key and may charge for usage; automatic translation starts disabled.
- Optional, experimental Gemini world shaders can give the scene a music-inspired visual treatment. They start disabled, require your own API key, and use your Google API quota. Track metadata and your visual direction are sent to Google, without audio, lyrics, or game captures.
- Native media detection and video playback depend on the platform packaged with the release. Check the release notes for your system.
- Lyrica is in active development. Features and compatibility may change between releases. [Report an issue](https://github.com/fakemaluco/lyrica-mod/issues).

</details>

---

Inspired by [Spicy Lyrics](https://github.com/Spikerko/spicy-lyrics), with lyric-provider and codec foundations from Spicy Lyrics Mobile. See [third-party credits](https://github.com/fakemaluco/lyrica-mod/blob/main/THIRD_PARTY_NOTICES.md) for attribution and licenses.

An independent project, not affiliated with Apple, Spotify, or YouTube.

<details>
<summary>Português · Conheça o Lyrica</summary>

## Sua música. Seu mundo. Sua letra.

Sua próxima construção. Um longo voo de volta. Aquela música no repeat.

**O Lyrica traz letras sincronizadas para dentro do Minecraft**, com animação palavra por palavra, capas em movimento e uma apresentação do seu jeito. Acompanhe sua música em um pop-up discreto ou deixe as palavras fazerem parte do mundo ao seu redor.

### Sinta cada palavra

Transições fluidas acompanham as frases, enquanto cada palavra ganha destaque no tempo da voz. O Lyrica busca em várias fontes e prioriza letras com sincronização mais detalhada. Tradução e romanização ajudam a acompanhar músicas em outros idiomas.

Nem toda faixa tem marcação por palavra: o mod também exibe letras sincronizadas por linha ou texto simples e aceita letras locais importadas.

### Dê espaço para a música

As **letras 3D opcionais** levam as palavras para o mundo, com posicionamento que considera o terreno e seus movimentos, de uma caminhada a um voo de elytra.

No pop-up, **Spotify Canvas**, capas animadas e fundos dinâmicos aproximam você da música. Ajuste fonte, efeitos, visibilidade, posição e tamanho; mova e redimensione diretamente na tela. Os controles de reprodução ficam por perto quando a conexão permite.

### Descubra o que está tocando

O **Music Presence** mostra uma nota musical na lista de jogadores e permite descobrir a música de outra pessoa por um cartão no mundo ou por `/music <jogador>`. Funciona entre jogadores com Lyrica, sem instalar nada no servidor. Compartilhar é opcional, pode ser desativado e exige uma conta Minecraft verificada.

### Comece a ouvir

1. Instale **Fabric Loader** e **Fabric API** para sua versão do Minecraft.
2. Baixe o `.jar` correspondente em [Releases](https://github.com/fakemaluco/lyrica-mod/releases) e coloque na pasta `mods`.
3. Abra o jogo e, nas configurações do Lyrica, escolha sua origem de música em **Conexões**. Siga as etapas exibidas.

No Windows, a detecção automática acompanha aplicativos que disponibilizam uma sessão de mídia do sistema, sem login no serviço de música. Conecte sua conta Spotify para controlar a reprodução e a fila pelo Spotify Connect. O YouTube Music tem sua própria extensão local.

O Lyrica acompanha a música de outro aplicativo; ele não transmite áudio por conta própria. A instalação é apenas no cliente, inclusive para jogar em servidores. Há builds para **1.21.1**, **1.21.11**, **26.2** e **26.3**. As versões 26.2 e 26.3 exigem Java 25; as versões 1.21.x usam Java 21.

Letras, vídeos e precisão da sincronização variam conforme a faixa e os serviços. Traduções enviam o texto ao provedor escolhido; alguns exigem chave própria e podem cobrar pelo uso. A tradução automática começa desligada.

Shaders experimentais do mundo via Gemini também estão disponíveis, desligados por padrão. Exigem chave própria, consomem sua cota Google e enviam metadados da faixa e sua direção visual, sem áudio, letras ou capturas do jogo. Detecção nativa e reprodução de vídeos dependem da plataforma incluída no arquivo da release.

O projeto está em desenvolvimento. Consulte as notas da release e [relate problemas no GitHub](https://github.com/fakemaluco/lyrica-mod/issues).

Inspirado em [Spicy Lyrics](https://github.com/Spikerko/spicy-lyrics), com a base de provedores e codecs do Spicy Lyrics Mobile. [Créditos e licenças](https://github.com/fakemaluco/lyrica-mod/blob/main/THIRD_PARTY_NOTICES.md). Projeto independente, sem afiliação com Apple, Spotify ou YouTube.

</details>
