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
podem ser ativadas em **Avançado > Experimental**, com os ajustes na aba
**Mundo 3D**, que aparece somente quando o recurso está ligado.

Para compilar os três JARs, instale JDK 21 e JDK 25 e execute
`.\gradlew.bat :lyrics-popup:build :lyrics-popup-1.21.11:build :lyrics-popup-26.2:build`.

## Configurações

O menu usa abas **Geral**, **Aparência**, **Personalizar**, **Conexões** e
**Avançado**. Em Aparência, os conjuntos **Sem efeitos**, **Suaves** e **Spicy**
combinam brilho, sombra e transição; escolhas diferentes aparecem como
**Personalizado**. Abrir o menu preserva todas as preferências existentes.

Use **Geral > Posição e tamanho do pop-up > Ajustar na tela** para mover e
redimensionar por arrasto. A visualização mostra a **música atual**, com sua capa
e sincronização reais. Os detalhes de fonte, efeitos, fundo, posição e Mundo 3D
ficam em grupos recolhíveis de **Personalizar**; desempenho e recursos
experimentais ficam em **Avançado**. As opções se abrem abaixo do próprio
controle, sem trocar de tela, e os sliders têm botões de diminuir e aumentar.
O menu usa os widgets nativos do jogo, sem exigir Cloth Config.

**Conexões** separa estado da conexão, música detectada e disponibilidade das
letras. Métodos manuais, pareamento e IDs ficam em **Detalhes da conexão**.
Restaurar as preferências do mod preserva contas, Client ID, pareamentos e a
origem da música. **Configurar conexão** abre etapas para Spotify ou YouTube
Music e instruções para as bridges locais. Avisos de letras indisponíveis
permanecem por dez segundos, depois somem suavemente. O pop-up mantém seu tamanho
e sua posição configurados, com a capa e os dados da música; letras carregadas
e trechos instrumentais não são escondidos por esse prazo.

Para conferir o menu no cliente de desenvolvimento, execute
`.\gradlew.bat :lyrics-popup:runClient --init-script scripts/verify-lyrics-gui.gradle`;
substitua o módulo para verificar 1.21.11 ou 26.2. Use `-PnoticeOnly=true` para
verificar somente o aviso, seu fade sem redimensionamento e o retorno das letras.
As capturas ficam em `build/lyrics-gui-qa`. O teste é opt-in e usa dados locais.

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
