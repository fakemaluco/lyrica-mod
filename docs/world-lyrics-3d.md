# Letras no mundo 3D

A composição e os controles atuais estão no [guia de letras cinematográficas](cinematic-lyrics.md).
Esse guia substitui as descrições históricas de apresentações e padrões abaixo.

Na aba **Experimental**, ative **Letras posicionadas no mundo**. A aba **Mundo 3D** aparece
imediatamente e desaparece ao desligar a opção. Nela, escolha a **Intensidade 3D**
(Suave, Normal ou Intensa) e configure **Reagir ao jogador**. O controle geral
**Animações** desliga todos os movimentos, incluindo as reações. Os padrões de
intensidade e reação são Normal e ligado; o recurso 3D continua opt-in.

## Direção visual e controles novos

O diretor automático escolhe entre Letreiro, Mural, Gravado, Monólito, Paisagem e Voo
quando uma nova frase nasce. A apresentação permanece durante a página cantada. Frases
simultâneas compartilham a mesma composição. É possível desligar o diretor, escolher
um preset fixo, selecionar quais participam da rotação e ajustar sua frequência.
Configurações antigas mantêm a ativação, a fonte e os ajustes gerais, mas recebem os
seis novos estilos e o diretor contextual. Não há um segundo motor legado.
Letreiro mantém uma composição menor junto ao solo para quem está a pé e se ergue
sem atravessar o bloco. Monólito usa profundidade e chanfro mais fortes para frases
curtas; Mural desliza junto à parede; Gravado usa um relevo raso e só é escolhido
quando a parede pode ser lida de frente. Os novos estilos podem ser
fixados manualmente ou incluídos na rotação automática.

O jogador parado coloca novas frases a 6 blocos. A distância utiliza a velocidade
completa em blocos por segundo e o tempo restante da frase, com 1,25 segundo de
antecedência adicional. Corrida projeta frases a dezenas de blocos; elytra e voo
criativo usam um afastamento inicial de 32 blocos, chegando ao limite padrão de 192
blocos (ajustável até 256 e limitado pela distância de renderização). O preset
Paisagem começa a 48 blocos mesmo parado. Escala e margens crescem com a distância
para manter o tamanho angular do texto. As posições laterais, diagonais e em
arco são relativas à mira no instante em que a frase aparece, mantendo-se fixas no
mundo. A orientação considera a inclinação da câmera e a trajetória de voo. Quando
uma posição lateral é bloqueada, usa-se a posição frontal segura. Paredes próximas
não são escolhidas durante corrida ou voo na direção automática. Uma frase longa
sincronizada mantém a mesma âncora nas viradas de página. Se uma aceleração fizer o
jogador alcançar a página atual, ela desliza até uma nova âncora. A trajetória
considera o terreno entre os pontos;
não acompanha a câmera a cada quadro. Pausar a música congela esse deslocamento.
O deslocamento termina antes de outro começar, e tentativas bloqueadas pelo terreno
aguardam 1,2 segundo antes de uma nova sondagem. Curvas com elytra só mudam a âncora
após um desvio amplo sustentado por 450 ms, com tempo de leitura restante; pequenas
correções, voo quase parado e mudanças de página preservam a posição. Novas frases
podem reutilizar a âncora considerando a trajetória de voo e a inclinação completa,
mesmo quando o jogador olha para o lado ou usa uma câmera afastada.

Durante uma sessão ativa do **Ji AFK Cinematic**, as letras 3D continuam visíveis
quando o modo Mundo 3D está ligado. A posição usa o enquadramento da câmera do Ji,
sem herdar a trajetória ou a velocidade do jogador com elytra. As dimensões e o
eixo vertical se adaptam à câmera, às barras pretas e ao espaço da legenda inferior.
Cortes, deslocamentos
e panorâmicas amplas reposicionam a frase com uma entrada suave de 240 ms; pequenas
oscilações preservam a âncora. O enquadramento continua se adaptando com a música
pausada. Ao sair do AFK, uma nova âncora acompanha a câmera do jogador. A legenda
cinematográfica continua disponível e só aparece durante a reprodução.

Em **Personalizar > Efeitos > Animação das letras por linha**, **De cima para baixo**
é o padrão: o preenchimento acompanha toda a duração da linha, do início até o fim,
com um degradê vertical que cobre todas as linhas quebradas. O efeito não inventa tempos por palavra. **Por tempo**
restaura o preenchimento horizontal anterior. Letras com sincronização por palavra
preservam seus tempos e animações; sem animações, a frase acende imediatamente.
O brilho suave da frase cresce até o meio do tempo da linha e diminui até o fim,
com uma mola compartilhada por todas as palavras e linhas quebradas. As intensidades
Suave, Normal e Forte também controlam esse halo. Ele aparece no pop-up e na legenda
do Ji; o modo simples e a opção de desativar animações suprimem o brilho.

Ao criar uma âncora livre, o terreno sob o centro e as extremidades é medido e a
composição sobe quando seu limite inferior encostaria no chão.
O painel oferece preferência por contexto, superfícies ou horizonte. Com a primeira,
frases caminhando variam entre chão, parede e ar; corrida e voo priorizam o horizonte.
A frase é medida, incluindo vozes simultâneas, antes das sondagens. Chão irregular,
líquidos, blocos parciais, chunks ausentes e posições ocultas são rejeitados.
Há controles de tamanho, distância, sensibilidade, raio, região livre da mira,
profundidade, cores frontal e lateral, material de borda (automático, pedra, metal
ou luz suave), chanfro e sua largura, sombra de contato, iluminação do mundo,
entrada automática ou fixa, força e velocidade das animações, rastros e qualidade.
Há restauração por grupo e um botão que mostra uma frase de demonstração temporária
no mundo, mesmo sem música com letra sincronizada.

A profundidade usa uma malha fechada a partir da máscara dos glifos realmente
renderizados pelo Minecraft. Há frente, verso e faces laterais apenas no contorno
exposto, incluindo os contornos internos de letras como O e B. Retângulos vizinhos
são unidos; não existem paredes internas entre pixels adjacentes. Fontes bitmap
preservam o contorno pixelado, enquanto fontes com sobreamostragem usam a resolução
do atlas. A frente branca tem orientação voltada ao jogador após a escala vertical
do texto. A face final usa a própria fonte modelada pelo Minecraft, desenhada à
frente do volume para preservar acentos e legibilidade. O preenchimento sincronizado
também corta a geometria lateral.

A leitura do atlas ocorre somente na preparação pendente de novos textos, até duas
capturas por tick; a construção da malha usa um worker. O desenho consulta o cache,
limitado a 256 textos e aproximadamente 16 MiB. As máscaras de captura não ficam no
cache depois da construção. Recarregar recursos ou trocar fontes invalida
as malhas. Enquanto uma malha não estiver pronta, usa-se texto plano. Os testes
de integração abriram clientes separados 1.21.1, 1.21.11 e 26.2, com fontes carregadas e
contexto OpenGL real, e validaram captura de glifos latinos, acentos e árabe.

Os controles **Tamanho no mundo** (75–200%, padrão 120%), **Colar em paredes
próximas** e **Apresentação cinematográfica** (ambos ligados por padrão) ficam na
aba Mundo 3D. Use a roda do mouse ou Page Up/Down se as opções excederem a altura da tela.

## Comportamento

- Busca automática de paredes: um leque de 15 raios na direção da câmera, até
  12 blocos, seguido por no máximo duas grades de 11×7 amostras. Só faces sólidas,
  planas, visíveis e com área suficiente são escolhidas. Buracos e bordas reduzem
  o retângulo útil. Se nada servir, a busca tenta uma âncora aérea à frente e
  antes de obstáculos detectados. Se nem ela couber, a frase não atravessa blocos.
- Texto na parede fica a 0,065 bloco da face, com movimento de profundidade
  limitado a 0,015 bloco e inclinação de até 2 graus.
- Frases curtas recebem letras maiores. A escala considera distância inicial,
  preferência e largura/altura disponíveis, reservando espaço para animações.
- Frases longas quebram apenas nos espaços, em grupos de até três palavras por
  linha. Uma palavra comprida permanece inteira e a escala se ajusta ao espaço;
  sílabas sincronizadas e escrita RTL conectada também ficam juntas.
- Frases sincronizadas são divididas em blocos de duas linhas, trocados nos tempos
  originais com dissolução de até 160 ms. Fragmentos sobrepostos ficam juntos.
  Letras sem tempos individuais mostram todas as linhas, sem inventar paginação.
- A apresentação cinematográfica acrescenta aproximação suave, variação de escala
  por bloco e uma faixa escura discreta por linha, respeitando a profundidade do
  mundo. Desligar animações mantém quebra, contraste e trocas de bloco instantâneas.

- Palavras curtas e sílabas fazem um salto suave; fragmentos sustentados usam
  ondas por caractere. As curvas de duração são compartilhadas com o lyrics.
- O preenchimento corta os quads da fonte em coordenadas locais, interpolando
  UVs, com profundidade e sombra preservadas. Não usa recorte de tela ou bloom.
- Texto RTL preserva a modelagem contextual da palavra inteira e preenche da
  direita para a esquerda; não separa letras conectadas para formar ondas.
- Letras sem tempos individuais, ou romanizações que não possam preservar os
  tempos fornecidos, recebem apenas animação da frase.
- Frases sobrepostas compartilham uma âncora com posições verticais estáveis;
  frases seguintes criam novas âncoras diante do jogador. Até três ficam visíveis,
  priorizando vozes principais. Girar a câmera não move âncoras existentes.
- Entradas e saídas usam dissolução, deslize, cascata, rotação ou profundidade conforme o preset. Intervalos reais de pelo
  menos três segundos exibem pontos com o progresso do lyrics.
- Corrida, salto/pouso, proximidade e voo são reações locais e amortecidas.
  Deslocamentos combinados: até 0,25 bloco; inclinação: até 5 graus; expansão:
  até 10%, incluindo voo, sobre a escala adaptativa do bloco. A apresentação
  cinematográfica acrescenta zoom de entrada/saída e até 2,5% durante a sustentação.
  Paredes bloqueiam movimento para dentro da superfície.
- Pausa congela o movimento. Mudanças de faixa/documento/mundo e seeks explícitos
  reinicializam a cena; saltos externos do relógio acima de 750 ms também a limpam.

## Verificação automatizada

```powershell
./gradlew.bat :lyrics-popup:build :lyrics-popup-1.21.11:build :lyrics-popup-26.2:build --console=plain
```

Cobertura: movimentos curtos e sustentados, sílabas, limites combinados, paredes,
pausa/retomada, seeks, limpeza de reações, suavização a 30 e 144 Hz, prioridade de
vozes, intervalos, romanização, RTL, UVs de preenchimento em planos rotacionados
e compatibilidade das preferências antigas. A expansão cinematográfica acrescenta
cobertura de quebra entre palavras, preservação de sílabas, paginação rápida,
espaço de animação, texto RTL e seleção de retângulos sem buracos.

A regressão de estabilidade cobre curvas breves e sustentadas, voo quase parado,
pausa, seek, bloqueio de deslocamentos simultâneos, novas tentativas após obstáculos
e aproximação em alta velocidade. A sonda OpenGL com Ji AFK Cinematic 2.3.1 passou
em 1.21.1, 1.21.11 e 26.2, mantendo o modo 3D ligado durante o AFK, verificando a
legenda com velocidade de elytra simulada, pausa e retorno ao mundo. A sonda espacial
1.21.11 também passou nos cenários de frases curtas/longas e curva ampla com elytra;
ela injeta a velocidade e não substitui a inspeção de um voo real.

O layout (segmentação, textos estilizados e medidas) fica em cache por frase.
Alterações de fonte/romanização invalidam o layout sem mover suas âncoras.
Raycasts só ocorrem ao criar uma âncora, nunca por caractere.

### Sonda opcional de CPU

```powershell
$env:LYRICA_WORLD_BENCHMARK = 'true'
./gradlew.bat :lyrics-popup:test :lyrics-popup-1.21.11:test --tests '*WorldLyricsBenchmark'
Remove-Item Env:LYRICA_WORLD_BENCHMARK
```

A sonda executa 240 cálculos de movimento e três recortes de quad por quadro,
com 1.000 quadros de aquecimento e 2.000 amostras. Medição local:

| Versão | Mediana anterior | p95 anterior | Mediana atual | p95 atual |
| --- | --- | --- | --- | --- |
| 1.21.1 | 0,013 ms | 0,018 ms | 0,013 ms | 0,019 ms |
| 1.21.11 | 0,018 ms | 0,029 ms | 0,013 ms | 0,020 ms |

Esses números excluem TextRenderer, layout, raycasts, envio à GPU e renderização
do mundo. Não representam FPS nem uma medição completa de renderização.

## Validação visual e medição de quadros pendentes

O cliente 1.21.1 foi aberto em um mundo plano com letra sincronizada: a frase apareceu
no cenário e a face da fonte ficou à frente do volume. As sondas opcionais com contexto
OpenGL validaram atlas, faces extrudadas, preenchimento e aba condicional nas três
versões. A inspeção das seis apresentações em 1.21.11 e 26.2, corrida/elytra e a
medição completa de frametime ainda precisam ser executadas; a sonda de CPU não
mede o custo total de renderização.

1. Observar palavras rápidas, sílabas e notas longas nas três intensidades:
   leitura contínua, sem saltos de espaçamento ou flashes ao desaparecer.
2. Testar dueto e backing simultâneos: até três frases, sem sobreposição; caminhar
   e virar a câmera para confirmar âncoras fixas e novas frases diante do jogador.
3. Repetir em chão e nas quatro orientações de parede; preenchimento acompanha
   a geometria, caracteres não entram na parede e sombras não piscam.
4. Aproximar, correr, saltar, pousar e voar: um impulso por evento, voo suave e
   nenhuma reação após desligar o controle. Pausar e retomar no meio de uma nota.
5. Buscar para frente/trás, trocar faixa e mundo; conferir ausência de resíduos.
6. Testar fontes personalizadas, romanização, árabe/hebraico e frases extensas;
   conferir blocos de duas linhas, palavras partidas, símbolos compostos e ausência
   de trechos perdidos nas trocas. Testar letras sem sincronização por palavra.
7. Mirar entre paredes, em portas e nas bordas de construções: confirmar busca
   lateral, texto rente à face, faixa de contraste e ajuste de escala. Testar os
   controles novos, rolagem da configuração e ausência de cliques em opções ocultas.
8. Comparar frametime com três frases simultâneas, 3D ligado/desligado, mantendo
   mundo, resolução, distância de renderização e limite de FPS iguais. Registrar
   CPU/GPU, mediana e p95 antes de afirmar qualquer resultado de FPS.
