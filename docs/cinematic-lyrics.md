# Letras cinematográficas no mundo

O Lyrica compõe palavras e frases no cenário usando os tempos originais da letra.
Texto plano é a apresentação padrão; espessura e animação são escolhas independentes.
O recurso é local ao cliente e está disponível nos três JARs standalone.

## Controles

Ative as letras na aba **Mundo 3D**, sempre presente nas configurações. A composição
automática local funciona sem conta ou consulta a uma IA. Os controles do Gemini
ficam somente em **Avançado > Experimentos**.

- **Intensidade:** Discreta, Equilibrada ou Expressiva. O padrão é **Discreta**.
- **Cinematográfico:** desligado por padrão. Quando ligado, permite palavras no centro da visão.
- **Estilo** e **Tamanho:** preservam os ajustes de aparência existentes.

Em **Personalizar > Mundo 3D**, **Espessura 3D** dá volume real a todas as palavras.
Desligada, as palavras são planas, embora a direção possa dar volume a palavras
específicas de destaque. O controle de profundidade ajusta a espessura quando há
volume. Fonte e peso acompanham o pop-up. Posição, luz, cores, qualidade e força
da animação continuam nos ajustes detalhados. As molas de palavras e glifos do
Spicy acompanham o canto também em **Discreta**; a intensidade regula a força dos
movimentos. O controle geral **Animações** desliga esses movimentos.

## Composição no cenário

| Composição | Comportamento |
| --- | --- |
| Palavras caindo | Cada palavra desce um percurso curto e assenta na sua posição, formando a frase. |
| Trecho progressivo | Pequenos grupos entram no momento do canto, preservando os grupos anteriores. |
| Frases coexistindo | Uma frase permanece enquanto outra entra em uma posição diferente. |
| Escada | Palavras ou grupos ocupam degraus, com posições planejadas antes da entrada. |
| Frase com destaque | Uma palavra recebe escala, cor ou volume maior dentro de uma composição simples. |

O automático local escolhe composições de forma determinística, misturando linhas,
pequenos trechos e palavras separadas. Cada grupo pode ocupar uma região diferente
do cenário, com pequenas rotações estáveis. A divisão respeita palavras e os
fragmentos da letra, sem cortar glifos ou letras conectadas. **Discreta** preserva
a leitura e já alterna composições locais: trechos progressivos, palavras caindo,
destaques e frases completas, mantendo no máximo a frase atual e uma anterior.
A frase anterior dura até **1.500 ms** após o fim do canto, incluindo o fade final
de **350 ms**. Intensidades maiores ampliam a variedade e a força dos movimentos. A cena tem limites de quatro grupos e 48 palavras visíveis; a frase
atual tem prioridade sobre os grupos antigos.

O preenchimento e os destaques usam os tempos originais, incluindo fragmentos
silábicos quando fornecidos. A composição progressiva revela o grupo no início
de seu primeiro fragmento; a coexistência revela a frase inteira no início da linha.
Palavras caindo e escadas mantêm entrada por palavra. Letras sincronizadas apenas por linha aparecem como frases inteiras;
o mod não inventa tempos por palavra. Letras sem sincronização continuam no
pop-up, sem cenas temporizadas no mundo. Pausar congela a evolução musical da
cena; avançar, voltar ou repetir reconstrói o estado correspondente ao novo tempo.
Trocar de música ou sair do mundo limpa a cena.

## Direção Gemini

Abra **Avançado > Experimentos > Gemini · direção das letras** para configurar a
chave, o modelo, uma orientação opcional e consultar o estado da geração. Ative
**Direção de letras com Gemini** nesse grupo para habilitar as consultas automáticas
quando as letras no mundo estiverem ligadas. **Refazer direção** solicita outra interpretação.
A configuração é independente do experimento de shaders Gemini. Se o modelo
padrão `gemini-flash-latest` estiver temporariamente indisponível, a direção tenta
`gemini-3.5-flash-lite` uma vez.

Com esse modo ativo, o Lyrica envia ao Google o texto da letra, os tempos originais,
as vozes disponíveis e os metadados da faixa. Não envia áudio ou imagens do jogo.
A consulta usa a cota/cobrança da chave configurada. A chave é salva localmente em
`config/lyrica/gemini-lyrics/api-key.txt`, sem criptografia e fora das preferências
exportadas; o cache da direção fica separado da chave.

A direção retornada contém somente opções declarativas conhecidas pelo mod:
composição, região desejada, entrada e permanência para até 24 linhas. O Gemini
pode destacar uma palavra inteira com uma entrada própria e uma posição central.
Em jogo normal, o destaque central tenta ficar acima da mira.

A cor pode ser escolhida para a faixa inteira. O controle **Fonte por música**
permite que o Gemini escolha entre Inter, Oswald e Caveat, já incluídas no mod,
ou mantenha a fonte configurada. As demais linhas usam a composição local com
a mesma aparência da faixa.

A geração ocorre em segundo plano, fora da renderização. Enquanto ela está em
andamento, o automático local continua ativo. Uma direção recebida durante a
música é aplicada às próximas frases, preservando as que já estão visíveis.
O cache considera a letra e seus tempos, o modelo, a orientação e a versão do
contrato. Respostas de faixas anteriores são descartadas. Falta de chave, falhas
de rede, cota ou resposta inválida mantêm a apresentação local disponível.

Esquerda, direita, alto, baixo e centro são intenções de enquadramento no momento
da entrada. Os textos ficam fixos por padrão. Ative **Reposicionamento automático** na aba
**Mundo 3D** para procurar outra posição quando forem encobertos ou saírem do
enquadramento, inclusive com a música pausada. Quando não há espaço para espalhar os grupos,
a frase atual aparece reunida em texto plano perto da câmera, com a ordem e os
tempos preservados. A próxima frase volta a avaliar o espaço disponível.
Sem **Cinematográfico**, as posições deixam espaço para a mira.

## Verificação reproduzível

Os testes unitários exercitam temporização, limites, validação da resposta,
cache, configuração, falhas e respostas atrasadas com um gerador substituto.
O formato do pedido também foi validado na API do Gemini com uma letra sintética
e o modelo `gemini-3.5-flash-lite`.

O roteiro percorre **13 cenas** no cliente de desenvolvimento: as cinco
composições, texto plano, volume, pausa com movimento da câmera, movimentos de
glifos do Spicy (`spicy-glyphs`), palavras distribuídas (`scattered-words`), mistura
de trechos (`mixed-chunks`) e o controle global de animações desligado
(`motion-disabled`). Ele também verifica a entrada sem antecipação de palavras e
a estabilidade das âncoras. Para executá-lo:

```powershell
.\gradlew.bat :lyrics-popup:runClient --init-script scripts/verify-world-presentation.gradle
```

O roteiro usa somente o mundo descartável **LyricaWorldQA**, que precisa existir na
instância de desenvolvimento. Não seleciona um mundo pessoal nem cria esse mundo
automaticamente. As cenas usam dados locais, sem chamar provedores ou Gemini.
As capturas ficam em `build/world-presentation-qa`.
Substitua o módulo por `:lyrics-popup-1.21.11` ou `:lyrics-popup-26.2` para verificar
as demais versões.

### Cenários com obstáculos

Para exercitar floresta densa, túnel baixo, blocos adicionados depois da entrada,
movimento da câmera, recuperação com a música pausada e uma parede próxima:

```powershell
.\gradlew.bat :lyrics-popup:runClient --init-script scripts/verify-world-presentation.gradle -PworldObstacles=true
```

Esse modo percorre seis cenários no mesmo mundo descartável. As quatro palavras
já cantadas precisam continuar legíveis e completas. O túnel e a parede a meio
bloco exigem a apresentação compacta. Depois de uma mudança de câmera ou de um bloqueio novo, o teste exige
recuperação e estabilidade, mesmo sem avanço do relógio da música. As capturas
ficam em `build/world-obstacle-qa`. Use `-PworldStage=2`, por exemplo, para começar
no cenário de obstáculo adicionado.
