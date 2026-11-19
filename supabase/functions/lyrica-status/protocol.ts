export const PROVIDERS = new Set([
  "lrc_red", "lrclib", "spicy_lyrics", "amll_ttml_db", "unison", "spicy_unison",
  "rmm_revival", "kugou", "qq_music", "kuwo", "netease", "lrcmux", "genius", "youtube_transcript",
]);
const NETWORKS = new Set(["hypixel", "cubecraft", "mcc_island", "wynncraft", "minemen", "mush", "craftlandia", "rede_sky", "shotbow"]);
const ERRORS = new Set(["provider_network", "provider_timeout", "provider_auth", "provider_request",
  "provider_server", "provider_response", "provider_other", "provider_limit", "bridge_restart", "spotify_api", "listen_sync"]);
const UUID_V4 = /^[a-f0-9]{8}-[a-f0-9]{4}-4[a-f0-9]{3}-[89ab][a-f0-9]{3}-[a-f0-9]{12}$/;

export function parseReport(raw: unknown) {
  if (!raw || typeof raw !== "object" || Array.isArray(raw)) return null;
  const { session, sequence, network, counts } = raw as Record<string, unknown>;
  if (typeof session !== "string" || !UUID_V4.test(session) || typeof sequence !== "number" ||
      !Number.isSafeInteger(sequence) || sequence <= 0 || sequence > 2_147_483_647) return null;
  if (!counts || typeof counts !== "object" || Array.isArray(counts)) return null;
  const metrics = Object.entries(counts);
  if (metrics.length > 60 || metrics.some(([metric, value]) => !validMetric(metric) ||
      typeof value !== "number" || !Number.isInteger(value) || value <= 0 || value > 600)) return null;
  return { p_id: session, p_sequence: sequence, p_network: typeof network === "string" && NETWORKS.has(network) ? network : null,
    p_counts: Object.fromEntries(metrics) };
}

function validMetric(key: string): boolean {
  if (key === "lrc_requests") return true;
  const [kind, name, state, extra] = key.split(".");
  return kind === "e" && ERRORS.has(name) && state === undefined ||
    kind === "p" && PROVIDERS.has(name) && ["ok", "error", "limited"].includes(state) && extra === undefined;
}
