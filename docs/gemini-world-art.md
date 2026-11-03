# Biblioteca artística do mundo — 0.8.90

O projeto inclui 18 técnicas de pós-processamento do mundo. As oito técnicas iniciais estão descritas abaixo; as dez novas atmosferas e a interpretação genérica das músicas estão em [Direção musical criativa](gemini-creative-direction.md). O Gemini recebe funções GLSL, técnicas, custo e parâmetros escolhidos; no modo Automático também pode escrever um tratamento próprio. A biblioteca fica antes do HUD. Os 96 itens em `gemini-art-references.json` registram referências para expansão; não são 96 efeitos instalados.

## Como usar

Abra Configurações > Avançado > Experimentos > Shaders do mundo com Gemini. Salve sua chave, escolha o modelo e ative o experimento. Escolha uma receita ou um estilo principal e, opcionalmente, um secundário. Com geração automática ativada, a alteração prepara uma nova geração. Em modo manual, clique em Gerar novamente. A geração é assíncrona e respeita o intervalo de requisições; os parâmetros artísticos fazem parte da identidade do cache.

O modo Automático deixa o Gemini escolher a técnica pela música, respeitando escala, textura, seed e demais controles. A direção adicional aceita pedidos como: “paisagem em grafite sobre papel creme, céu azul claro, sem pulsação”, ou “quadrinhos com contornos finos e céu mais saturado”. A intensidade geral mistura o efeito com a cena original imediatamente.

## Técnicas executáveis

| ID | Técnica | Parâmetros aplicáveis | Custo adicional por pixel |
|---|---|---|---|
| handdrawn | Papel quente, bordas orgânicas e variação de espessura | escala, traço, grão, seed, redesenho | 4 amostras da cena |
| pencil | Grafite, papel marfim, contornos e pigmento por luminosidade | escala, traço, grão, seed, redesenho | 4 amostras |
| crosshatch | Linhas diagonais e cruzamento nas sombras | escala, traço, grão, seed, redesenho | 4 amostras |
| ink | Tinta preta, massas de sombra e contornos fortes | escala, traço, grão, seed, redesenho | 4 amostras |
| watercolor | Lavagem de cor local, papel e bordas de pigmento | escala, traço, grão, seed, redesenho | 6 amostras |
| cel | Faixas de cor e contornos | traço | 4 amostras |
| comic | Cel e retícula dependente da luminosidade | escala, traço, seed, redesenho | 4 amostras |
| terminal | Fósforo verde, níveis de luminosidade, linhas de varredura | escala, grão, seed, redesenho | sem amostras adicionais |

Contagem das funções-base, além da leitura original; não é uma medição de FPS. Composição de dois estilos soma trabalho. O registro Java inclui ID, categoria, técnica, função, custo, suporte e fallback. Os módulos usam cor e profundidade da cena; não precisam de arquivos externos de shaderpacks.

## Receitas editáveis

| Receita | Principal + secundário | Escala / traço / grão | Mistura secundária | Redesenho |
|---|---|---|---|---|
| Pencil Notebook | pencil + crosshatch | 100 / 90 / 35% | 25% | não |
| Living Ink | ink | 120 / 130 / 20% | 0% | sim |
| Dreamy Watercolor | watercolor + pencil | 150 / 70 / 25% | 15% | não |
| Vintage Comic | comic | 130 / 120 / 15% | 0% | não |
| Anime Opening | cel | 100 / 90 / 0% | 0% | não |
| Retro Terminal | terminal | 100 / 100 / 15% | 0% | não |
| Handdrawn Motion | handdrawn + pencil | 110 / 110 / 25% | 20% | sim |

As receitas mantêm sua seed e a preferência de reação à música. Alterar qualquer parâmetro relevante passa a receita para Personalizado. As preferências são persistidas com a configuração do mod. Esta etapa não inclui um gerenciador de receitas nomeadas pelo usuário com salvar/duplicar/excluir.

## Composição e estabilidade

Cena original → estilo principal e secundário calculados a partir da mesma cena → mistura secundária → tratamento do céu → acentos de áudio → intensidade geral. Assim um contorno não acaba recalculado sobre a textura de outro estilo. O Gemini recebe essa ordem como instrução.

Papéis e padrões usam coordenadas estáveis da tela, com seed reproduzível. Não ficam presos às superfícies do mundo: a geometria se move sob o papel quando a câmera se move. Redesenho é opcional, desativado por padrão, em passos de 8 Hz. Pausar a música congela o tempo. Desligar as animações congela o relógio. Desligar reação à música zera energia, batida e bandas na GPU, mesmo se o código gerado ainda as consultar.

Há amostragem controlada `sampleScene(uv)` e `sampleDepth(uv)`, com coordenadas limitadas à tela e profundidade normalizada entre versões. O modelo não declara texturas/uniforms e continua sujeito ao contrato restrito. A mudança de parâmetros invalida a identidade antiga; respostas atrasadas não substituem a escolha atual. Somente shaders renderizados com sucesso entram no cache. Falha de geração ou compilação preserva o mundo original e informa o estado no menu.

## Limites desta etapa

Aquarela aproxima pigmento; não simula fluido. Cel usa a imagem final; não recalcula a luz dos materiais. Terminal não tem persistência temporal. Sem normais, UV de materiais, histórico de frames ou vetores de movimento, não se promete geometria deformada, volumetria verdadeira, datamosh, tinta física ou materiais reais por bloco. Os shaders de letras descritos no texto original permanecem referências; esta biblioteca atua no cenário.

Não houve requisição real ao Gemini nem uso de credenciais do usuário na validação. A API foi exercitada com respostas simuladas; as funções foram renderizadas de verdade em um mundo descartável nas três versões. Isso valida a biblioteca e o caminho gráfico, mas a composição final de uma resposta do modelo continua dependendo do código que ele gerar.

## Referências visuais pesquisadas

- [SketchPaper](https://modrinth.com/shader/skpp): referência de grafite, contornos e papel.
- [Inkboil](https://modrinth.com/shader/inkboil): referência de redesenho em cadência discreta.
- [Dither](https://github.com/Snorfield/Dither): referência para futura expansão de padrões retro.

As funções desta biblioteca foram implementadas no projeto; esses shaderpacks não são dependências e seu código não foi copiado.
