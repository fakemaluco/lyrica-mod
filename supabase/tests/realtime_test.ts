// Run against short-lived QA sessions seeded by an administrator, never a production auth bypass.
import { strict as assert } from "node:assert";
const qa = JSON.parse(await Deno.readTextFile(Deno.args[0]));
const base = "https://thfukfqgjmgelikrdeti.supabase.co";
const key = "sb_publishable_t8VixNoVtAslOR40K6mNKA_EKtikJf4";
const visible = [qa.a.id, qa.b.id, qa.c.id];
async function sync(who: string, data: Record<string, unknown> = {}) {
  const r = await fetch(base + "/functions/v1/music-presence/sync", { method: "POST",
    headers: { apikey: key, "x-lyrica-session": qa[who].token, "Content-Type": "application/json" },
    body: JSON.stringify({ visible, write: false, ...data }) });
  const result = await r.json(); assert.equal(r.status, 200, JSON.stringify(result)); return result.peers;
}
const ws = new WebSocket(base.replace("https", "wss") + "/realtime/v1/websocket?vsn=1.0.0&apikey=" + key);
let notify: (() => void) | undefined;
const joined = new Promise<void>((resolve, reject) => {
  const timer = setTimeout(() => reject(Error("Realtime subscription timed out")), 12000);
  ws.onopen = () => ws.send(JSON.stringify({ topic: "realtime:" + qa.topic, event: "phx_join", ref: "1", join_ref: "1",
    payload: { config: { private: false, broadcast: { ack: false, self: false }, presence: { enabled: false } } } }));
  ws.onmessage = e => {
    const message = JSON.parse(e.data);
    if (message.event === "phx_reply" && message.ref === "1") {
      clearTimeout(timer);
      message.payload.status === "ok" ? resolve() : reject(Error(JSON.stringify(message.payload)));
    }
    if (message.event === "broadcast" && message.payload.event === "changed") notify?.();
  };
  ws.onerror = () => { clearTimeout(timer); reject(Error("Realtime connection failed")); };
});
try {
  await joined;
  const changed = new Promise<void>((resolve, reject) => {
    const timer = setTimeout(() => reject(Error("Server did not push the song update")), 12000);
    notify = () => { clearTimeout(timer); resolve(); };
  });
  await sync("a", { write: true, sharing: true, title: "Shared QA song", artist: "Lyrica", cover_url: "" });
  await changed;
  const b = await sync("b");
  assert.equal(b.find((p: any) => p.player_id === qa.a.id)?.title, "Shared QA song");
  assert.equal((await sync("c")).length, 0, "Different server must not see the song");
  await sync("b", { write: true, sharing: true, title: "Own QA song", artist: "QA", player_id: qa.a.id, player_name: "Impersonation" });
  const state = await sync("a");
  assert.equal(state.find((p: any) => p.player_id === qa.a.id).title, "Shared QA song");
  assert.equal(state.find((p: any) => p.player_id === qa.b.id).player_name, "LyricaQA_B");
  await sync("a", { write: true, sharing: false, title: "", artist: "" });
  assert(!((await sync("b")).some((p: any) => p.player_id === qa.a.id)), "Pausing removes the song");
  for (const who of ["a", "b", "c"]) {
    const r = await fetch(base + "/functions/v1/music-presence/leave", { method: "POST", headers: {
      apikey: key, "x-lyrica-session": qa[who].token, "Content-Type": "application/json" }, body: "{}" });
    assert.equal(r.status, 200);
  }
  console.log("PASS: live Supabase WebSocket push, second-client snapshot, cross-server isolation, identity binding, pause and leave.");
} finally { ws.close(); }
