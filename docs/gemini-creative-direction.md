# Direção musical e novas atmosferas — 0.8.90

O modo Automático permite que o Gemini invente um tratamento GLSL para o mundo. As funções prontas são ingredientes opcionais: ele pode escrever helpers próprios, mudar paletas, compor máscaras espaciais, criar céus e adaptar o tratamento à identidade da música. Não existe cadastro de músicas com shaders predefinidos.

## Configuração recomendada

Avançado > Experimentos > Shaders do mundo com Gemini:

- Estilo artístico: **Automático pela música**.
- Estilo secundário: **Nenhum**, para deixar a composição livre.
- **Interpretar música e referências**: ligado.
- **Liberdade criativa**: 85% por padrão; aumente para incentivar mudanças mais autorais.
- **Referências e direção adicional**: opcional. Pode indicar uma atmosfera, intenção ou referência cultural. A direção salva vale para todas as faixas até ser alterada.
- **Criar outra interpretação** solicita um novo conceito para a faixa atual. A requisição recebe uma revisão diferente e os seis conceitos recentes da sessão para evitar repetir sua assinatura visual. Continua respeitando o intervalo da API.

Um estilo escolhido manualmente continua sendo uma restrição. Para a IA escolher outra linguagem, volte para Automático. Os controles de liberdade orientam o conceito; não alteram a temperatura da API nem garantem a qualidade de uma resposta do modelo.

## Como a música orienta o resultado

A instrução solicita que o modelo relacione título, artista e álbum, reconheça referências culturais quando souber e transforme a intenção em composição: materiais aparentes, contornos, sombras, paleta, espaço vazio, profundidade, céu e movimento. O modelo recebe orientações gerais, não exemplos de músicas cadastradas. Música rápida não é automaticamente raivosa; intensidade alta não significa necessariamente hostilidade.

Também deve evitar o uso repetido de papel bege, céu magenta, ruído por toda a imagem e contornos em cada texel. Uma atmosfera agressiva pode combinar terreno carbonizado, brasas e céu turbulento; uma ilustração intimista pode usar branco puro e poucos contornos pretos. São possibilidades de direção, não regras de gênero.

A API continua recebendo **metadados e direção visual**, sem gravação, letra completa ou imagem do jogo. O reconhecimento depende do conhecimento do modelo. Para faixas desconhecidas ou títulos ambíguos, a instrução pede que ele evite inventar associações e explique a incerteza. Esta implementação não consulta automaticamente sites ou catálogos para identificar a faixa.

## Dez técnicas novas — total de 18

| Técnica | Tratamento real da imagem do mundo |
|---|---|
| Line art | Superfícies brancas, contornos pretos por estrutura de cor/profundidade, sem sépia ou grão |
| Inferno | Terreno escuro com relevo, acentos de calor nos contornos, céu procedural de fumaça/chamas e brasas esparsas |
| Abismo | Silhuetas, profundidade fria e céu azul escuro |
| Dreamscape | Cores pastéis, distância suave e nuvens procedurais |
| Aurora | Terreno noturno legível, faixas luminosas no céu e estrelas esparsas |
| Tempestade | Paisagem dessaturada, céu turbulento e chuva discreta sem flashes |
| Neon | Contornos luminosos da própria cena, sombras preservadas e céu noturno |
| Blueprint | Desenho técnico azul, contornos claros e grid discreto |
| Duotone | Regiões gráficas em duas famílias de cor com contornos |
| 1-bit | Preto e branco com dithering espacial |

As oito técnicas anteriores continuam disponíveis. Handdrawn e grafite receberam menos sujeira visual no tratamento-base. Há 17 receitas editáveis no total. As novas atmosferas têm movimento suave pelo relógio da música; **redesenho em 8 FPS** continua sendo um controle específico de traços/papel. Desligar animações congela o relógio; desligar reação ao áudio zera as bandas fornecidas à GPU.

## Limites e validação

Os efeitos atuam na imagem e profundidade do mundo antes do HUD. Não alteram blocos, geometria, física, clima real nem materiais de shaderpacks. Névoa e brasas são aproximações visuais em tela. Permanecem os limites de recursos descritos na documentação da biblioteca artística.

O contrato/cache mudou para impedir a reutilização automática dos shaders gerados pela direção anterior. O cache permanece estável por faixa e configuração; só uma nova geração explícita pede outra interpretação para a mesma faixa. O histórico recente dura a sessão.

A validação gráfica usa respostas simuladas e um mundo descartável. Ela demonstra o funcionamento dos módulos e do pipeline, mas não comprova que uma chamada real ao Gemini reconhecerá uma música ou produzirá um resultado artisticamente bom. Nenhuma chave real foi usada na validação.
