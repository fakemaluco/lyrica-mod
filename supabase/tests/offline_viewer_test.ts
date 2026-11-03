import { strict as assert } from "node:assert";
const qa = JSON.parse(await Deno.readTextFile(Deno.args[0]));
const base = "https://thfukfqgjmgelikrdeti.supabase.co/functions/v1/music-presence/";
const key = "sb_publishable_t8VixNoVtAslOR40K6mNKA_EKtikJf4";
async function call(route: string, body: unknown, token?: string) {
  const headers: Record<string, string> = { apikey: key, "Content-Type": "application/json" };
  if (token) headers["x-lyrica-session"] = token;
  const response = await fetch(base + route, { method: "POST", headers, body: JSON.stringify(body) });
  const result = await response.json(); assert.equal(response.status, 200, JSON.stringify(result)); return result;
}
const visible = [qa.a.id];
try {
  await call("sync", { visible, write: true, sharing: true, title: "Verified song", artist: "QA", cover_url: "" }, qa.a.token);
  // The unverified viewer deliberately claims the verified player's UUID.
  const viewer = await call("viewer", { player_id: qa.a.id, server_hash: qa.room });
  assert.equal(viewer.can_publish, false);
  const data = await call("sync", { visible, write: false }, viewer.token);
  assert.equal(data.peers[0].title, "Verified song");
  const forged = await call("sync", { visible, write: true, sharing: true, title: "Forged song", artist: "Attacker" }, viewer.token);
  assert.equal(forged.peers[0].title, "Verified song", "Read-only sessions must not impersonate or overwrite verified players");
  await call("leave", {}, viewer.token);
  const stillPlaying = await call("sync", { visible, write: false }, qa.a.token);
  assert.equal(stillPlaying.peers[0].title, "Verified song", "Viewer leaving must not remove the verified session");
  const anotherRoom = await call("viewer", { player_id: qa.a.id, server_hash: qa.other_room });
  assert.equal((await call("sync", { visible, write: false }, anotherRoom.token)).peers.length, 0);
  await call("leave", {}, anotherRoom.token);
  console.log("PASS: offline viewer reads verified music, cannot impersonate, cannot remove the sender, and remains room-scoped.");
} finally {
  for (const who of ["a", "b", "c"]) try { await call("leave", {}, qa[who].token); } catch { /* TTL cleanup is a fallback. */ }
}
