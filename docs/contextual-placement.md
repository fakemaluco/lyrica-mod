# Posicionamento contextual das letras

O posicionamento automático combina paredes, piso e espaços livres. A busca usa oito
setores ao redor da trajetória e escolhe posições legíveis no campo de visão atual.
As frases ficam no mundo ao olhar para outro lado; a câmera não arrasta suas âncoras.
Os limites de distância, tamanho, superfícies, animações e reposicionamento continuam
nos controles existentes. Não é necessário configurar um perfil para cada velocidade.
Para experimentar sem tocar música, entre em um mundo e use **Mundo 3D → Mostrar prévia**.

## Movimento e superfícies

Parado ou agachado, o texto privilegia superfícies próximas e estabilidade. Caminhada,
corrida, salto prolongado/queda, água, veículos e voo têm contextos próprios. Mudanças
de estado precisam persistir por 300 ms; velocidade e direção têm suavização temporal.
Saltos breves e movimentos de câmera não devem refazer toda a composição.

Corrida e voo distribuem pequenos grupos de duas ou três palavras, com a antecipação
calculada pelo trecho seguinte, em vez da duração do verso inteiro. Os tempos originais,
as sílabas e a ordem de leitura são preservados. Sem tempos por palavra, a frase continua
inteira. Novas posições se aproximam gradualmente ao parar; palavras já visíveis mantêm
sua posição e seu tamanho.

Uma parede precisa oferecer área plana e suporte para o grupo inteiro. No piso, o texto
pode ficar deitado quando a perspectiva for legível, ou erguido sobre a superfície.
O volume e a margem das animações também precisam caber. Superfícies rasantes perdem
visibilidade suavemente. Se não houver espaço, o modo compacto procura primeiro dentro
do intervalo de distância escolhido e só depois tenta mais perto da câmera, sem cobrir a mira.

## Perspectiva e obstáculos

A separação considera os limites orientados dos grupos no mundo e sua projeção na tela,
incluindo profundidade e margem para animação. A palavra cantada tem prioridade; grupos
antigos e vozes secundárias cedem antes dela quando uma mudança de ângulo causa conflito.

Visibilidade, suporte e colisão são verificações separadas. Vidro e vidraças deixam passar
a maior parte da visão; folhas têm obstrução parcial cumulativa. Um bloco opaco continua
bloqueando, mesmo atrás de vidro. A colisão usa as formas reais dos blocos, impedindo
que o texto atravesse superfícies transparentes, lajes e outros blocos parciais.

As buscas têm orçamento compartilhado de 128 segmentos de raio por tick. Consultas
adiadas preservam a composição anterior e retomam a transação em vez de interpretar
falta de orçamento como obstáculo. Não há raycast por caractere.

## Verificação local

Execute os testes das políticas e a verificação do JAR com Java 25 e JDK 21 disponível:

```powershell
.\gradlew.bat :lyrics-popup-26.3:test :lyrics-popup-26.3:check
```

As cenas gráficas usam somente o mundo descartável `LyricaWorldQA`, letras sintéticas e
preferências de teste. Não conectam serviços musicais nem alteram preferências pessoais.
Se não existir um mundo de QA, `scripts/create-world-qa.py` pode criar um mundo vazio
26.3 a partir do JAR oficial, com `nbtlib` instalado; o script recusa sobrescrever mundos.

```powershell
.\gradlew.bat :lyrics-popup-26.3:runClient --init-script scripts/verify-world-context.gradle
```

As 14 cenas cobrem paredes nas quatro direções, piso deitado/erguido, vidro, vidro colorido,
folhas esparsas/densas, vidro diante de pedra, perspectiva, parada após corrida e olhar
para outro lado. Capturas e diagnósticos ficam em `build/world-context-qa`.

Para medir cadência completa de quadros, mantendo resolução, mundo, render distance,
VSync e limite de FPS iguais, use `-PworldBenchmark=true -PworldStage=11`.
O medidor descarta 500 quadros de aquecimento e grava 2.000 intervalos, mediana e p95 em
`frametime.json`. Ele fica inativo fora desse modo e não mede apenas CPU do posicionamento.
`scripts/BenchmarkBaseline.java` permite instrumentar uma cópia local do JAR original
com a mesma fixture e o mesmo medidor, sem substituir seu renderer. Essa cópia é somente
para QA; não é o mod atualizado e não deve ser instalada como atualização.

Não deduza ganho de FPS a partir dos testes unitários: o resultado depende do cenário,
CPU/GPU e das configurações. Registre os dois resultados e a diferença percentual do p95.
