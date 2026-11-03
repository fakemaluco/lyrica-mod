# Serviço Music Presence

Projeto implantado: `thfukfqgjmgelikrdeti` — região `sa-east-1`.
URL da função: `https://thfukfqgjmgelikrdeti.supabase.co/functions/v1/music-presence/`.

O mod usa a chave publicável do projeto. A chave administrativa permanece exclusivamente no ambiente da Edge Function. A função tem `verify_jwt = false` porque implementa autenticação própria com desafio Minecraft e sessão opaca; isso não torna `/sync` ou `/leave` anônimos.

## Protocolo

1. `POST /challenge`: UUID da conta, UUID do jogador, nome e SHA-256 do endereço normalizado. Retorna um desafio de 90 segundos e um identificador de servidor.
2. O cliente executa `MinecraftSessionService.joinServer` somente contra a Mojang, em uma thread de trabalho.
3. `POST /authenticate`: consome atomicamente o desafio e verifica a prova em `sessionserver.mojang.com`. Retorna um token de presença e o tópico da sala.
4. `POST /sync` com `x-lyrica-session`: publica apenas metadados atuais quando necessário e consulta os UUIDs online. Sessão, jogador e sala são decididos pelo servidor. Dados enviados no corpo não podem reatribuir a identidade.
5. Realtime Broadcast avisa as alterações, sem conter metadados musicais. O cliente solicita uma nova fotografia autenticada do estado; avisos falsos não se tornam músicas falsas.
6. `POST /viewer` cria uma sessão de 30 minutos somente para leitura, para clientes sem autenticação Minecraft. O banco impede publicação mesmo que um cliente modificado tente forjar `write: true`.
7. `POST /leave` revoga a sessão e remove a faixa em cascata.

As tabelas têm RLS e acesso direto negado a `anon` e `authenticated`; funções RPC são exclusivas de `service_role`. A função de borda é o único caminho de dados. Há limites de corpo, tamanhos, hosts de imagem, consultas e tentativas de autenticação. `pg_cron` limpa registros expirados a cada minuto.

## Desenvolvimento

As migrações em `migrations/` correspondem às versões aplicadas no projeto. `schema.sql` reúne o esquema para leitura; não o execute junto das migrações.

Use o Supabase CLI 2.122.0 ou posterior após consultar `--help`. Para outro projeto, aplique as migrações, implante `functions/music-presence` e substitua a URL e a chave publicável do cliente. Não coloque uma chave `service_role` em Java, recursos do mod ou arquivos versionados.

Verificação local da função:

```powershell
deno check supabase/functions/music-presence/index.ts
deno test supabase/functions/music-presence/core_test.ts
```

`tests/realtime_test.ts` usa sessões temporárias criadas por um administrador exclusivamente para QA. O arquivo de sessões fica em `build/`, ignorado pelo Git, e as sessões são revogadas ao terminar. Não existe endpoint de autenticação de teste na função publicada.

Limites do modelo apenas no cliente, autenticação Minecraft e verificações: consulte `docs/releases/0.8.63.md`.
