# Lyrica

[![Latest release](https://img.shields.io/github/v/release/fakemaluco/lyrica-mod?label=release)](https://github.com/fakemaluco/lyrica-mod/releases)
<a href="https://github.com/fakemaluco/lyrica-mod/stargazers"><img src="https://img.shields.io/github/stars/fakemaluco/lyrica-mod?style=flat&logo=github&label=Stars" height="18"></a>

![Lyrica Banner](banner%20Lyrica%201.png)

**A better way to experience music lyrics inside Minecraft.**

Lyrica brings beautifully animated, **Apple-inspired lyrics** directly into Minecraft, featuring **word-by-word synchronization**, smooth animations, and plenty of customization options.

The mod adapts to the way you play. Customize when and how lyrics appear, keeping them visible when you want them and out of the way when you don't.

![Lyrica preview](gif%201.gif)

## Como instalar

1. Baixe o arquivo `.jar` mais recente na página de [Releases](https://github.com/fakemaluco/lyrica-mod/releases).
2. Certifique-se de ter a versão correta do **Minecraft** e o loader **Fabric** instalados.
3. Coloque o arquivo `.jar` dentro da pasta `mods` do Minecraft.
4. Inicie o jogo.
5. Prontinho! O Lyrica já estará disponível.

## Spicy Lyrics

O Lyrica usa somente a API oficial do Spicy Lyrics para buscas automáticas.
Para usar a conta do Spotify sem a extensão do Spicetify, crie uma chave
**publishable** (`sl_pk_...`) no painel do Spicy Lyrics, habilite acesso sem
header de origem para cliente nativo e defina `SPICY_LYRICS_PUBLISHABLE_KEY`
antes de iniciar o Minecraft. Para desenvolvimento local também é possível
usar `-Dlyrica.spicyLyricsKey=...`.

Nunca coloque uma chave secreta (`sl_sk_...`) no código, no JAR ou em commits.

## Sobre o projeto

Sempre gostei da forma como o **Spicy Lyrics** apresenta letras sincronizadas, então decidi trazer uma experiência parecida para dentro do Minecraft, adaptada para funcionar naturalmente durante o gameplay.

> **Status:** Lyrica ainda está em desenvolvimento. Bugs, mudanças e recursos ainda não implementados são esperados.

<!-- Adicione um preview aqui:
![Mod Preview](./previews/preview.gif)
-->

*Inspirado em [Spicy Lyrics](https://github.com/Spikerko/spicy-lyrics)*
