# Desenvolvimento do Lyrica

## Estrutura

| Pasta | Conteúdo |
| --- | --- |
| `lyrics-popup/` | Mod standalone para Minecraft 1.21.1, configurações, testes e código compartilhado entre versões. |
| `lyrics-popup-1.21.11/` | Configuração da compilação para Minecraft 1.21.11. |
| `lyrics-popup-26.2/` | Adaptações do cliente para Minecraft 26.2. |
| `mod/`, `mod-1.21.11/` | Fontes e recursos reutilizados pelas tarefas Gradle dos mods standalone. Continuam necessários mesmo sem aparecer em `settings.gradle.kts`. |
| `lyrics-engine/` | Núcleo JVM de letras, tradução e vídeo, com licenças e atribuições upstream. |
| `shared-protocol/` | Protocolo compartilhado com a bridge de mídia. |
| `media-bridge/` | Bridge nativa em Rust para sessões de mídia e áudio. |
| `integrations/spicetify-extension/` | Extensão para Spotify/Spicetify. |
| `integrations/youtube-music-extension/` | Extensão de navegador para YouTube Music. |
| `supabase/` | Serviço Music Presence, migrações e testes. Veja seu README. |
| `assets-src/` | Fontes e ícones originais, incluindo licenças. |
| `scripts/` | Exportação de recursos e verificações visuais opcionais. |
| `docs/` | Guias, notas de versão e referências de design. |
| `docs/media/` | Banner e demonstração usados pelo README e pela página Modrinth. |
| `.github/workflows/` | Integração contínua e publicação por tags. |

## Compilar e verificar

Instale JDK 21 e JDK 25; use JDK 25 para executar o Gradle. As toolchains
selecionam o Java adequado para cada versão do Minecraft.

```powershell
.\gradlew.bat build
```

Os JARs ficam em `lyrics-popup/build/libs/`,
`lyrics-popup-1.21.11/build/libs/` e `lyrics-popup-26.2/build/libs/`.

Para incluir a bridge nativa atualizada, compile-a antes dos mods:

```powershell
cd media-bridge
cargo test --locked
cargo build --release --locked
cd ..
.\gradlew.bat build
```

Para verificar as extensões:

```powershell
cd integrations/spicetify-extension
npm ci
npm run typecheck
npm run build
cd ../youtube-music-extension
node --test background.test.cjs content.test.cjs
cd ../..
```

## Arquivos locais e publicação

`build/`, `.gradle/`, `.kotlin/`, `media-bridge/target/`, `node_modules/`,
`dist/`, `artifacts/` e `release-assets/` são saídas ou caches locais ignorados
pelo Git. `artifacts/` reúne capturas de QA; `release-assets/` é uma área de
preparação da publicação. Não guarde código-fonte ou arquivos únicos nessas pastas.
As instâncias de desenvolvimento em `run/` podem conter configurações pessoais.

Não versione `.env`, chaves privadas, tokens ou configurações locais de contas.
Preserve as licenças em `assets-src/`, `lyrics-engine/` e nos recursos do mod.

A publicação de código acontece pelo Git. A criação de uma tag `v*` aciona
o workflow de release, que compila os JARs e o ZIP da extensão YouTube Music.
