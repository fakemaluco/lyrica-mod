# Letras no mundo 3D

Na aba **Experimental**, ative **Letras posicionadas no mundo**. A aba **Mundo 3D** aparece
imediatamente e desaparece ao desligar a opção. Nela, escolha a **Intensidade 3D**
(Suave, Normal ou Intensa) e configure **Reagir ao jogador**. O controle geral
**Animações** desliga todos os movimentos, incluindo as reações. Os padrões de
intensidade e reação são Normal e ligado; o recurso 3D continua opt-in.

## Direção visual e controles novos

O diretor automático escolhe entre Clássico, Cinema, Escultura, Arco, Cascata, Voo,
Paisagem, Terreno e Intro chanfrada
quando uma nova frase nasce. O preset da frase não muda até ela sair. Frases
simultâneas compartilham a mesma composição. É possível desligar o diretor, escolher
um preset fixo, selecionar quais participam da rotação e ajustar sua frequência.
Configurações antigas continuam no estilo Clássico; uma primeira ativação usa Cinema.
Terreno mantém uma composição menor junto ao solo para quem está a pé e entra com
uma queda curta. Intro chanfrada acrescenta uma borda 3D inclinada ao contorno dos
glifos e surge com expansão de escala e profundidade. Os novos estilos podem ser
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
não são escolhidas durante corrida, voo ou no preset Paisagem. Se uma aceleração
fizer o jogador alcançar a frase antes do fim, o grupo desliza suavemente até uma
nova âncora fixa mais adiante. A trajetória considera o terreno entre os pontos;
não acompanha a câmera a cada quadro. Pausar a música congela esse deslocamento.
Ao criar uma âncora livre, o terreno sob o centro e as extremidades é medido e a
composição sobe quando seu limite inferior encostaria no chão.
O painel oferece controles de tamanho, distância, sensibilidade, raio, região livre
da mira, profundidade, cores frontal e lateral, força e velocidade das animações,
rastros e qualidade. Há restauração por grupo e um botão para recriar a frase atual.

A profundidade usa uma malha fechada a partir da máscara dos glifos realmente
renderizados pelo Minecraft. Há frente, verso e faces laterais apenas no contorno
exposto, incluindo os contornos internos de letras como O e B. Retângulos vizinhos
são unidos; não existem paredes internas entre pixels adjacentes. Fontes bitmap
preservam o contorno pixelado, enquanto fontes com sobreamostragem usam a resolução
do atlas. A frente branca tem orientação voltada ao jogador após a escala vertical
do texto, e é desenhada depois do verso e das laterais. O preenchimento sincronizado
também corta a geometria lateral.

A leitura do atlas ocorre somente na preparação pendente de novos textos, até duas
capturas por tick; a construção da malha usa um worker. O desenho consulta o cache,
limitado a 256 textos e 180 mil faces. Recarregar recursos ou trocar fontes invalida
as malhas. Enquanto uma malha não estiver pronta, usa-se texto plano. Os testes
de integração abriram clientes separados 1.21.1 e 1.21.11, com fontes carregadas e
contexto OpenGL real, e validaram captura de glifos latinos, acentos e árabe.

Os controles **Tamanho no mundo** (75–200%, padrão 120%), **Colar em paredes
próximas** e **Apresentação cinematográfica** (ambos ligados por padrão) ficam na
aba Mundo 3D. Use a roda do mouse ou Page Up/Down se as opções excederem a altura da tela.

## Comportamento

- Busca automática de paredes: um leque de 15 raios na direção da câmera, até
  12 blocos, seguido por no máximo duas grades de 11×7 amostras. Só faces sólidas,
  planas, visíveis e com área suficiente são escolhidas. Buracos e bordas reduzem
  o retângulo útil. Se nada servir, a frase aparece à frente, próxima da altura
  dos olhos e antes de obstáculos detectados.
- Texto na parede fica a 0,065 bloco da face, com movimento de profundidade
  limitado a 0,015 bloco e inclinação de até 2 graus.
- Frases curtas recebem letras maiores. A escala considera distância inicial,
  preferência e largura/altura disponíveis, reservando espaço para animações.
- Frases longas quebram por palavra; palavras largas demais podem quebrar entre
  grafemas inteiros, sem perder texto nem reiniciar o preenchimento. Sílabas são
  mantidas juntas quando a palavra cabe; escrita RTL conectada permanece inteira.
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
./gradlew.bat :lyrics-popup:build :lyrics-popup-1.21.11:build --console=plain
```

Cobertura: movimentos curtos e sustentados, sílabas, limites combinados, paredes,
pausa/retomada, seeks, limpeza de reações, suavização a 30 e 144 Hz, prioridade de
vozes, intervalos, romanização, RTL, UVs de preenchimento em planos rotacionados
e compatibilidade das preferências antigas. A expansão cinematográfica acrescenta
cobertura de quebra de palavras/grafemas, preservação de sílabas, paginação rápida,
espaço de animação, texto RTL e seleção de retângulos sem buracos.

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

| Versão | Mediana | p95 |
| --- | --- | --- |
| 1.21.1 | 0,013 ms | 0,020 ms |
| 1.21.11 | 0,016 ms | 0,030 ms |

Esses números excluem TextRenderer, layout, raycasts, envio à GPU e renderização
do mundo. Não representam FPS nem uma medição completa de renderização.

## Validação visual pendente no jogo

Executar em ambas as versões, com letra sincronizada e uma faixa sem tempos por
palavra. Os clientes de desenvolvimento validam automaticamente os atlas OpenGL,
as faces extrudadas, o preenchimento e a visibilidade condicional da aba. A leitura
em um mundo durante corrida/elytra e a medição completa de frametime ainda precisam
de validação visual; as sondas não medem o custo total de renderização.

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
