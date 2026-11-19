import { digest } from "./core.ts";
import { listenParameters, listenSong } from "./listen.ts";

Deno.test("Listen relay clamps timing and only retains public track metadata", () => {
  const song = listenSong({
    title: " §aTrack ", duration: 90_000, position: 110_000, playing: true,
    uri: "spotify:track:1234567890123456789012", token: "private",
    cover: "https://i.scdn.co.evil.example/cover",
  });
  if (!song || song.title !== "Track" || song.position !== 90_000 || !song.playing
      || song.cover !== "" || "token" in song) throw new Error("Invalid song shape");
});

Deno.test("Listen relay stores only hashed invites and preserves Spotify queue order and duplicates", async () => {
  const code = "lyr_1234567890abcdef1234567890abcdef";
  const first = { uri: "spotify:track:1234567890123456789012", title: "First" };
  const second = { uri: "spotify:track:1234567890123456789013", title: "Second" };
  const result = await listenParameters({
    invite: code, target: code, song: first, queue: [second, null, first, second],
  }, "session");
  if (!result || result.p_invite_hash !== await digest(code) || result.p_target_hash !== result.p_invite_hash
      || JSON.stringify(result).includes(code) || result.p_queue.map(x => x.title).join() !== "Second,First,Second")
    throw new Error("Invalid listen parameters");
  if (await listenParameters({ target: "SomePlayer" }, "session")) throw new Error("Unscoped lookup");
});
