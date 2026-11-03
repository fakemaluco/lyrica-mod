import { UUID, text, cover, digest, random, validIdentity, verifiedProfile } from "./core.ts";

const base = Deno.env.get("SUPABASE_URL")!;
const service = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!;
const headers = { apikey: service, Authorization: `Bearer ${service}`, "Content-Type": "application/json" };
class Failure extends Error { constructor(public status: number, message: string) { super(message); } }
async function db(path: string, method = "POST", data?: unknown): Promise<any> {
  const r = await fetch(base + "/rest/v1/" + path, { method,
    headers: { ...headers, Prefer: "return=representation" },
    body: data === undefined ? undefined : JSON.stringify(data), signal: AbortSignal.timeout(8000) });
  if (!r.ok) {
    const error = await r.text();
    if (error.includes("invalid_session")) throw new Failure(401, "invalid_session");
    if (error.includes("rate_limited")) throw new Failure(429, "rate_limited");
    throw new Failure(503, "database_unavailable");
  }
  const body = await r.text(); return body ? JSON.parse(body) : null;
}
async function notify(topic?: string): Promise<void> {
  if (!topic) return;
  // Public messages contain no player identity or music; clients use them only to request
  // an authenticated snapshot. A forged broadcast can never forge a player's music.
  try {
    await fetch(base + "/realtime/v1/api/broadcast", { method: "POST", headers,
      body: JSON.stringify({ messages: [{ topic, event: "changed", payload: {}, private: false }] }),
      signal: AbortSignal.timeout(3000) });
  } catch { /* The periodic snapshot and TTL recover a dropped invalidation. */ }
}
const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), {
  status, headers: { "Content-Type": "application/json", "Cache-Control": "no-store", "X-Content-Type-Options": "nosniff",
    ...(status === 429 ? { "Retry-After": "60" } : {}) } });
async function readBody(req: Request): Promise<string> {
  const reader = req.body?.getReader();
  if (!reader) throw new Failure(400, "invalid_json");
  const chunks: Uint8Array[] = []; let size = 0;
  try {
    while (true) {
      const { done, value } = await reader.read(); if (done) break;
      size += value.length;
      if (size > 65536) { await reader.cancel(); throw new Failure(413, "request_too_large"); }
      chunks.push(value);
    }
  } finally { reader.releaseLock(); }
  const bytes = new Uint8Array(size); let offset = 0;
  for (const chunk of chunks) { bytes.set(chunk, offset); offset += chunk.length; }
  return new TextDecoder().decode(bytes);
}

Deno.serve(async req => {
  try {
    if (req.method !== "POST") throw new Failure(405, "post_required");
    if (Number(req.headers.get("content-length") ?? 0) > 65536) throw new Failure(413, "request_too_large");
    const raw = await readBody(req);
    let body: Record<string, any>; try { body = JSON.parse(raw); } catch { throw new Failure(400, "invalid_json"); }
    if (!body || typeof body !== "object" || Array.isArray(body)) throw new Failure(400, "invalid_request");
    const route = new URL(req.url).pathname.split("/").pop();
    if (route === "challenge") {
      const { account_id: account, player_id: player, player_name: name, server_hash: room } = body;
      if (typeof account !== "string" || typeof player !== "string" || typeof name !== "string" ||
          !validIdentity(account, player, name) || typeof room !== "string" || !/^[0-9a-f]{64}$/.test(room)) throw new Failure(400, "invalid_identity");
      await db("rpc/music_presence_limit", "POST", { p_key: "challenge:" + account, p_max: 3 });
      const serverId = random(20);
      const rows = await db("music_presence_challenges", "POST", { account_id: account, player_id: player,
        player_name: name, server_hash: room, server_id: serverId });
      return json({ challenge: rows[0].id, server_id: serverId });
    }
    if (route === "authenticate") {
      if (typeof body.challenge !== "string" || !UUID.test(body.challenge)) throw new Failure(400, "invalid_challenge");
      // DELETE RETURNING atomically consumes the challenge: no proof replay or cross-room substitution.
      const rows = await db(`music_presence_challenges?id=eq.${body.challenge}&expires_at=gt.${encodeURIComponent(new Date().toISOString())}`, "DELETE");
      const c = rows?.[0]; if (!c) throw new Failure(401, "challenge_expired");
      const response = await fetch(`https://sessionserver.mojang.com/session/minecraft/hasJoined?username=${encodeURIComponent(c.player_name)}&serverId=${c.server_id}`,
        { signal: AbortSignal.timeout(8000), redirect: "error" });
      if (response.status !== 200) throw new Failure(401, "minecraft_verification_failed");
      const profile = await response.json();
      if (!verifiedProfile(profile, c.account_id, c.player_name)) throw new Failure(401, "minecraft_verification_failed");
      const token = random();
      const result = await db("rpc/music_presence_open", "POST", { p_account_id: c.account_id, p_player_id: c.player_id,
        p_player_name: c.player_name, p_server_hash: c.server_hash, p_token_hash: await digest(token) });
      return json({ token, topic: result.topic, expires_in_ms: result.expires_in_ms });
    }
    if (route === "viewer") {
      const { player_id: player, server_hash: room } = body;
      if (typeof player !== "string" || !UUID.test(player) || typeof room !== "string" || !/^[0-9a-f]{64}$/.test(room))
        throw new Failure(400, "invalid_identity");
      const ip = req.headers.get("cf-connecting-ip") ?? req.headers.get("x-real-ip") ?? "unknown";
      await db("rpc/music_presence_limit", "POST", { p_key: "viewer:" + await digest(ip + new Date().toISOString().slice(0, 10)), p_max: 120 });
      const token = random();
      const result = await db("rpc/music_presence_open_viewer", "POST", { p_player_id: player, p_server_hash: room, p_token_hash: await digest(token) });
      return json({ token, topic: result.topic, expires_in_ms: result.expires_in_ms, can_publish: false });
    }
    const token = req.headers.get("x-lyrica-session");
    if (!token || !/^[0-9a-f]{64}$/.test(token)) throw new Failure(401, "session_required");
    const tokenHash = await digest(token);
    if (route === "sync") {
      if (!Array.isArray(body.visible) || body.visible.length > 512 || body.visible.some(x => typeof x !== "string" || !UUID.test(x))) throw new Failure(400, "invalid_players");
      const title = text(body.title, 256);
      const result = await db("rpc/music_presence_sync", "POST", { p_token_hash: tokenHash,
        p_visible: [...new Set(body.visible)], p_write: body.write === true, p_sharing: body.sharing === true && !!title,
        p_title: title, p_artist: text(body.artist, 256), p_cover_url: cover(body.cover_url) });
      if (result.changed) await notify(result.topic);
      return json({ peers: result.peers });
    }
    if (route === "leave") {
      const result = await db("rpc/music_presence_leave", "POST", { p_token_hash: tokenHash });
      await notify(result?.topic); return json({ ok: true });
    }
    throw new Failure(404, "unknown_action");
  } catch (e) {
    if (e instanceof Failure) return json({ error: e.message }, e.status);
    return json({ error: "service_unavailable" }, 503);
  }
});
