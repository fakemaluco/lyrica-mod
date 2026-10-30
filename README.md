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
podem ser ativadas diretamente na aba **Mundo 3D**, sempre visível nas configurações.
Ela reúne oito controles principais e os estilos **Spicy**, **Esculpido** e
**Volume sutil**, todos com malhas 3D fechadas, sem painel de fundo ou halo.
A fonte e o peso acompanham o pop-up; os glifos reutilizam suas molas de animação.
As posições **Ao redor**, **No céu** e **Adaptativa** deixam espaço na mira.
Na elytra, a trajetória, a velocidade e o tamanho da frase determinam o espaço à frente.
Para iluminar os blocos próximos às palavras, instale também o
[LambDynamicLights](https://modrinth.com/mod/lambdynamiclights): 4.8.11 para 1.21.1,
4.9.1 para 1.21.11 ou 4.12.4 para 26.2. A integração é opcional; ele é distribuído
separadamente pelo autor. Os detalhes ficam em **Personalizar > Mundo 3D**.
O pop-up guarda tamanhos separados para capa estática, vídeo com letras e vídeo sem letras.
Os controles de reprodução ficam acima da barra de progresso. Com
[Ji AFK Cinematic](https://modrinth.com/mod/ji-afk-cinematic), o modo AFK mostra
apenas a letra atual centralizada embaixo, sem painel, acima das barras pretas.
As letras 3D continuam funcionando com F1.
Veja as mudanças e os limites em [0.8.61](docs/releases/0.8.61.md).

Para compilar os três JARs, instale JDK 21 e JDK 25 e execute
`.\gradlew.bat :lyrics-popup:build :lyrics-popup-1.21.11:build :lyrics-popup-26.2:build`.

## Configurações

O menu usa um painel lavanda centralizado, com navegação lateral e busca, seguindo
o painel de configurações do Spicy Lyrics. **Geral**, **Aparência**,
**Personalizar**, **Conexões**, **Fontes** e **Avançado** continuam disponíveis.
Os controles usam switches e seletores próprios, descrições visíveis e uma fonte
Inter incluída no mod. Em Aparência, os conjuntos **Sem efeitos**, **Suaves** e **Spicy**
combinam brilho, sombra e transição; escolhas diferentes aparecem como
**Personalizado**. Abrir o menu preserva todas as preferências existentes.
Switches, hover, pressão dos botões e troca de abas usam os tempos e as curvas
do Spicy desktop. A opção **Animações** também controla essas transições.
A música atrás do painel mantém a posição e o tamanho configurados do pop-up.
As fontes e os ícones do painel são desenhados na resolução real da janela,
com antialiasing nos contornos. Em **Aparência > Fundo do pop-up**, **Dinâmico
(Kawarp)** deforma a capa acompanhando a música; **Foto do artista** usa a foto
do cabeçalho do perfil e retorna à capa quando essa foto não está disponível.

Use **Geral > Posição e tamanho do pop-up > Ajustar na tela** para mover e
redimensionar por arrasto. A visualização mostra a **música atual**, com sua capa
e sincronização reais. Os detalhes de fonte, efeitos, fundo, posição e Mundo 3D
ficam em grupos recolhíveis de **Personalizar**; desempenho e recursos
experimentais ficam em **Avançado**. As opções se abrem abaixo do próprio
controle, sem trocar de tela, e os sliders têm botões de diminuir e aumentar.
O menu mantém foco, teclado e navegação nativos, sem exigir Cloth Config. A busca
também encontra opções dentro de grupos recolhidos; Esc limpa a busca antes de
fechar o painel e Ctrl+F foca o campo de pesquisa.

O painel e o pop-up adaptam a escala à área disponível da GUI, incluindo janelas
pequenas e escalas altas do Minecraft. A posição e as dimensões salvas são
preservadas ao trocar de resolução. O limite manual do pop-up é de 96 × 64;
em tamanhos muito pequenos, o conteúdo também encolhe para caber.

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

## Detecção e fontes de letras

Em **Conexões > Automático**, o Lyrica acompanha a sessão de mídia em reprodução
do sistema. No Windows, isso usa os controles de mídia publicados pelos aplicativos,
o equivalente desktop às sessões descobertas por acesso às notificações no Android.
Funciona sem login com players que publicam título, artista e posição. Uma extensão
conectada e pausada não toma o lugar de outro aplicativo tocando. A conta Spotify
continua disponível como alternativa automática ou como conexão escolhida manualmente.

A aba **Fontes** permite ativar ou desativar Spicy Lyrics, AMLL TTML DB, Unison,
Unison via Spicy, RMM Revival, lrc.red, Kugou, QQ Music, Kuwo, NetEase, LRCLIB,
LRCMux, Genius e transcrições do YouTube. As buscas usam título e artista sem exigir
uma conta Spotify. O núcleo consulta e classifica as respostas por qualidade de
sincronização, preservando TTML e tempos por palavra. Letras locais importadas têm
prioridade. Fontes indisponíveis não impedem as outras de responder.

**Fontes > Prioridade das fontes** permite reordenar as origens, como no Mobile.
**Priorizar Spicy Lyrics** fica ligado por padrão: uma resposta válida do Spicy
tem preferência, mesmo se um fallback responder antes. Você pode desligar essa
opção para usar somente a classificação por qualidade e ordem. Nos fallbacks,
letras sincronizadas por palavra têm preferência sobre sincronização por linha,
que tem preferência sobre texto simples. A ordem escolhida desempata respostas
com a mesma qualidade; a primeira resposta da rede não ganha automaticamente.
Um fallback em cache aparece de imediato, mas falhas temporárias nas fontes de
maior prioridade são consultadas novamente. Alterar a ordem ou os provedores
habilitados cria uma busca independente dos resultados anteriores.

Os provedores e codecs vêm do núcleo JVM do Spicy Lyrics Mobile, fixado em uma
revisão e mantido em `lyrics-engine/`, com licença e atribuição nesse diretório.
Disponibilidade, cobertura e precisão variam por serviço; nem toda música tem
sincronização por palavra. Genius também pode fornecer texto sem sincronização.
O diagnóstico da última busca fica na aba Fontes.

## Vídeos e Canvas

Em **Aparência > Vídeos e Canvas**, o Spotify Canvas da faixa tem prioridade sobre
a capa animada do álbum do Apple Music. Quando não houver vídeo, ou se o vídeo
falhar, a capa estática continua disponível. A busca usa os mesmos serviços e
critérios do Spicy Lyrics Mobile e não exige login nas contas desses serviços.

Quando um vídeo estiver pronto, ele preenche toda a altura da esquerda, com
recorte proporcional, transparência leve e desfoque suave. A borda direita do
vídeo desaparece gradualmente sobre o fundo, e a letra fica à direita. O pop-up cresce horizontalmente para acomodar a arte, respeitando a
janela e a escala da GUI. Passar o mouse no vídeo revela os controles de reprodução
sem ampliar a capa. Sem vídeo, o pop-up volta à largura normal; as dimensões salvas
não são alteradas por essa adaptação.

Sem letra disponível, a arte em vídeo preenche um painel quadrado. O canto de
redimensionamento mantém a proporção nesse modo; com letra, compensa o espaço
da arte para acompanhar o cursor. A busca tenta catálogos e edições correspondentes
e repete falhas temporárias com intervalos e limite de tentativas. Use **Buscar
vídeos novamente**, em **Aparência > Vídeos e Canvas**, para forçar nova consulta.

Canvas e capas animadas estão ligados por padrão. **Canvas no fundo** é opcional
e independente do Canvas na capa. Os vídeos são silenciosos, repetem em loop e
pausam com a música por padrão. Desligar as animações do Lyrica também desliga
esses vídeos. O decodificador trabalha fora da thread de renderização e suspende
o trabalho quando a arte não está sendo desenhada.

O JAR inclui o decodificador FFmpeg/JavaCV: não é necessário instalar um player
externo. Como a bridge nativa, o pacote de vídeo é compilado para a plataforma do
build; `-PvideoPlatform=windows-x86_64`, `linux-x86_64`, `linux-arm64`,
`macosx-x86_64` ou `macosx-arm64` permite selecionar o runtime de vídeo.
Disponibilidade de Canvas e capas em vídeo depende de cada faixa/álbum e serviço.

## Spicy Lyrics

O provedor Spicy Lyrics já inclui uma chave **publishable** de cliente.
Desenvolvedores podem substituir
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
