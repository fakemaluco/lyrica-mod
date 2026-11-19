const byId = id => document.getElementById(id);
const fmt = new Intl.NumberFormat("pt-BR");
const time = new Intl.DateTimeFormat("pt-BR", { day: "2-digit", month: "2-digit", hour: "2-digit", minute: "2-digit",
  timeZone: "America/Sao_Paulo", timeZoneName: "short" });
const providers = {
  lrc_red: "lrc.red", lrclib: "LRCLIB", spicy_lyrics: "Spicy Lyrics", amll_ttml_db: "AMLL TTML DB",
  unison: "Unison", spicy_unison: "Spicy Unison", rmm_revival: "RMM Revival", lrcmux: "LRCMux",
  kugou: "Kugou", qq_music: "QQ Music", kuwo: "Kuwo", netease: "NetEase", genius: "Genius", youtube_transcript: "YouTube",
};
const networkNames = {
  hypixel: "Hypixel", cubecraft: "CubeCraft", mcc_island: "MCC Island", wynncraft: "Wynncraft", minemen: "Minemen Club",
  mush: "Mush", craftlandia: "CraftLandia", rede_sky: "Rede Sky", shotbow: "Shotbow",
};
const errorNames = {
  provider_network: "Conexão com uma fonte", provider_timeout: "Tempo esgotado na fonte", provider_auth: "Autenticação na fonte",
  provider_request: "Requisição recusada", provider_server: "Erro na fonte", provider_response: "Resposta inválida",
  provider_other: "Outros erros de letras", provider_limit: "Limite de requisições", bridge_restart: "Reconexão da bridge",
  spotify_api: "Conexão com o Spotify", listen_sync: "Sincronização de escuta",
};

function element(tag, klass, text) {
  const node = document.createElement(tag);
  if (klass) node.className = klass;
  if (text != null) node.textContent = text;
  return node;
}
function row(name, value) {
  const item = element("li");
  item.append(element("span", "row-name", name), value);
  return item;
}
function stateOf(sample) {
  if (!sample) return ["none", "Sem amostra"];
  const total = Number(sample.ok) + Number(sample.errors) + Number(sample.limited);
  if (total < 3) return ["none", "Amostra baixa"];
  if (Number(sample.errors) === total) return ["down", "Indisponível"];
  if ((Number(sample.errors) + Number(sample.limited)) / total >= .25) return ["limited", "Instável"];
  return ["ok", "Operacional"];
}
function providerRows(samples) {
  return Object.entries(providers).map(([id, name]) => {
    const [state, label] = stateOf(samples?.[id]);
    const span = element("span", "provider-state");
    span.dataset.state = state;
    if (state !== "none") {
      const dot = element("span", "dot");
      dot.setAttribute("aria-hidden", "true");
      span.append(dot);
    }
    span.append(label);
    return row(name, span);
  });
}
function values(list, empty) {
  return list.length ? list.map(([name, value]) => row(name, element("span", "row-value", fmt.format(value))))
    : [element("li", "empty", empty)];
}
function render(snapshot) {
  const categories = Object.entries(snapshot.errors).filter(([key]) => key in errorNames).sort((a, b) => b[1] - a[1]);
  byId("active").textContent = fmt.format(snapshot.active);
  byId("presence-active").textContent = Number.isInteger(snapshot.presence_active) ? fmt.format(snapshot.presence_active) : "—";
  byId("requests").textContent = fmt.format(snapshot.lrc_requests);
  byId("errors").textContent = fmt.format(categories.reduce((total, [, count]) => total + Number(count), 0));
  byId("providers").replaceChildren(...providerRows(snapshot.providers));
  byId("categories").replaceChildren(...values(categories.map(([key, count]) => [errorNames[key], count]), "Nenhum erro reportado."));
  byId("networks").replaceChildren(...values(snapshot.networks.filter(item => item.network in networkNames)
    .map(item => [networkNames[item.network], item.sessions]), "Aguardando sessões suficientes para o ranking."));
  byId("freshness").dataset.state = "live";
  byId("freshness-text").textContent = "Atualizado em " + time.format(new Date(snapshot.updated_at));
}
let hasData = false;
async function refresh() {
  const button = byId("refresh");
  if (button.disabled) return;
  button.disabled = true;
  try {
    const response = await fetch("/status/data", { signal: AbortSignal.timeout(9_000) });
    if (!response.ok) throw new Error("Métricas indisponíveis");
    render(await response.json());
    hasData = true;
  } catch {
    byId("freshness").dataset.state = "unavailable";
    byId("freshness-text").textContent = hasData ? "Atualização indisponível · mostrando os últimos dados" : "Dados temporariamente indisponíveis";
    if (!hasData) {
      byId("providers").replaceChildren(...providerRows({}));
      for (const id of ["networks", "categories"]) byId(id).replaceChildren(element("li", "empty", "Dados temporariamente indisponíveis."));
    }
  } finally {
    button.disabled = false;
  }
}
byId("refresh").addEventListener("click", refresh);
refresh();
setInterval(() => { if (!document.hidden) refresh(); }, 60_000);
