# Status do Lyrica

O painel público fica em [lyrica.fakiz.dev/status](https://lyrica.fakiz.dev/status). Rode `node site/status/build.mjs` na raiz para gerar `build/status-worker.mjs`. O Worker `lyrica-status` serve esse painel, os recursos em `/status/assets/` e a leitura de `/status/data`. A regra de redirecionamento da Cloudflare exclui somente a rota e seus recursos.

O cliente agrega a atividade em memória e envia um lote por minuto enquanto estiver em um mundo. O Supabase deduplica a sequência da sessão antes de somar os contadores. Os identificadores aleatórios expiram. Cada rede conhecida precisa de pelo menos três sessões para entrar no ranking dos últimos sete dias. As categorias de falha e a saúde dos provedores usam as observações das últimas 24 horas.

A contagem de Music Presence inclui contas Minecraft verificadas que sincronizaram pelo Supabase nos últimos três minutos. Ela cobre builds antigas que já usavam esse serviço. A API retorna somente o total agregado, que pode se sobrepor à contagem da coleta do mod.

As tabelas e funções de escrita aceitam somente a credencial administrativa da Edge Function. O Worker usa a chave publicável para consultar a API agregada. Em caso de indisponibilidade, o painel sinaliza a falha e mantém os dados que já apareceram na sessão do navegador.
