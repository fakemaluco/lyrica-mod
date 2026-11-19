import { cover, digest, text, UUID } from "./core.ts";

const INVITE = /^lyr_[0-9a-f]{32}$/;
const SPOTIFY_TRACK = /^spotify:track:[A-Za-z0-9]{22}$/;
const SPOTIFY_QUEUE = /^spotify:(track|episode):[A-Za-z0-9]{22}$/;
const milliseconds = (input: unknown, max = 86_400_000) =>
  typeof input === "number" && Number.isFinite(input) ? Math.max(0, Math.min(max, Math.floor(input))) : 0;

export function listenSong(value: any) {
  if (!value || typeof value !== "object" || !text(value.title, 256)) return null;
  const duration = milliseconds(value.duration);
  const artwork = cover(value.cover);
  return {
    title: text(value.title, 256), artist: text(value.artist, 256), album: text(value.album, 256),
    cover: artwork.startsWith("https://") ? artwork.slice(0, 2048) : "",
    uri: typeof value.uri === "string" && SPOTIFY_TRACK.test(value.uri) ? value.uri : "",
    position: milliseconds(value.position, duration || 86_400_000), duration,
    playing: value.playing === true, shuffled: value.shuffled === true,
    repeat: value.repeat === "context" || value.repeat === "track" ? value.repeat : "off",
  };
}

export async function listenParameters(body: Record<string, any>, tokenHash: string) {
  const invite = body.invite;
  const target = body.target;
  const isCode = typeof target === "string" && INVITE.test(target);
  if (invite != null && (typeof invite !== "string" || !INVITE.test(invite))) return null;
  if (target != null && (typeof target !== "string" || (!isCode && !UUID.test(target)))) return null;
  const song = invite ? listenSong(body.song) : null;
  const queue = song?.uri && Array.isArray(body.queue) ? body.queue.slice(0, 12).flatMap(item =>
    typeof item?.uri === "string" && SPOTIFY_QUEUE.test(item.uri)
      ? [{ uri: item.uri, title: text(item.title, 128), artist: text(item.artist, 96) }] : []) : [];
  return {
    p_token_hash: tokenHash, p_invite_hash: invite ? await digest(invite) : null,
    p_revoke: body.revoke === true, p_song: song, p_queue: queue,
    p_target_hash: isCode ? await digest(target) : null, p_target_player: target && !isCode ? target : null,
  };
}
