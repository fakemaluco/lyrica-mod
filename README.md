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

Os JARs standalone são identificados pela versão do jogo: `lyrica-1.21.1`,
`lyrica-1.21.11` e `lyrica-26.2`. Para Minecraft 26.2, use Java 25 e uma
versão atual do Fabric Loader e Fabric API. As letras no mundo são opcionais e
podem ser ativadas em **Experimental**, com os ajustes na aba **Mundo 3D**.

Para compilar os três JARs, instale JDK 21 e JDK 25 e execute
`.\gradlew.bat :lyrics-popup:build :lyrics-popup-1.21.11:build :lyrics-popup-26.2:build`.
No 26.2, o brilho avançado da interface usa o acabamento padrão enquanto a
nova API de efeitos de tela não oferece o caminho anterior de pós-processamento.

## Spicy Lyrics

O Lyrica usa somente a API oficial do Spicy Lyrics para buscas automáticas e
já inclui uma chave **publishable** de cliente. Desenvolvedores podem substituir
essa chave com `SPICY_LYRICS_PUBLISHABLE_KEY` ou
`-Dlyrica.spicyLyricsKey=...` sem alterar os fontes.

Nunca coloque uma chave secreta (`sl_sk_...`) no código, no JAR ou em commits.

## Sobre o projeto

Sempre gostei da forma como o **Spicy Lyrics** apresenta letras sincronizadas, então decidi trazer uma experiência parecida para dentro do Minecraft, adaptada para funcionar naturalmente durante o gameplay.

> **Status:** Lyrica ainda está em desenvolvimento. Bugs, mudanças e recursos ainda não implementados são esperados.

<!-- Adicione um preview aqui:
![Mod Preview](./previews/preview.gif)
-->

*Inspirado em [Spicy Lyrics](https://github.com/Spikerko/spicy-lyrics)*
