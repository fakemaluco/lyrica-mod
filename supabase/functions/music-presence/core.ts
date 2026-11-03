import { createHash } from "node:crypto";

export const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
const NAME = /^[A-Za-z0-9_]{1,16}$/;
export function text(value: unknown, max: number): string {
  return typeof value === "string" ? [...value.replace(/§./gu, "").replace(/[\p{Cc}\p{Cf}]/gu, "").trim()].slice(0, max).join("") : "";
}
export function cover(value: unknown): string {
  if (typeof value === "string" && value.startsWith("data:image/png;base64,") && value.length <= 27000) {
    try {
      const bytes = Uint8Array.from(atob(value.slice(22)), c => c.charCodeAt(0));
      const v = new DataView(bytes.buffer);
      if (bytes.length >= 33 && bytes.length <= 20000 && v.getBigUint64(0) === 0x89504e470d0a1a0an &&
          v.getUint32(8) === 13 && v.getUint32(12) === 0x49484452 && v.getUint32(16) > 0 && v.getUint32(16) <= 256 &&
          v.getUint32(20) > 0 && v.getUint32(20) <= 256) return value;
    } catch { /* Invalid inline artwork is omitted. */ }
    return "";
  }
  if (typeof value !== "string" || value.length > 2048) return "";
  try {
    const u = new URL(value);
    const hosts = ["i.scdn.co", "mosaic.scdn.co", "image-cdn-ak.spotifycdn.com", "image-cdn-fa.spotifycdn.com",
      "is1-ssl.mzstatic.com", "is2-ssl.mzstatic.com", "is3-ssl.mzstatic.com", "is4-ssl.mzstatic.com", "is5-ssl.mzstatic.com",
      "i.ytimg.com", "lh3.googleusercontent.com", "lh4.googleusercontent.com"];
    return u.protocol === "https:" && !u.username && !u.password && !u.port && hosts.includes(u.hostname) ? u.href : "";
  } catch { return ""; }
}
export function offlineId(name: string): string {
  const b = createHash("md5").update("OfflinePlayer:" + name).digest();
  b[6] = b[6] & 15 | 48; b[8] = b[8] & 63 | 128;
  const s = b.toString("hex");
  return `${s.slice(0, 8)}-${s.slice(8, 12)}-${s.slice(12, 16)}-${s.slice(16, 20)}-${s.slice(20)}`;
}
export function validIdentity(account: string, player: string, name: string): boolean {
  return UUID.test(account) && UUID.test(player) && NAME.test(name) && (player === account || player === offlineId(name));
}
export function verifiedProfile(profile: { id?: string; name?: string }, account: string, name: string): boolean {
  return profile.id?.toLowerCase() === account.replaceAll("-", "") && profile.name?.toLowerCase() === name.toLowerCase();
}
export async function digest(value: string): Promise<string> {
  return [...new Uint8Array(await crypto.subtle.digest("SHA-256", new TextEncoder().encode(value)))].map(n => n.toString(16).padStart(2, "0")).join("");
}
export function random(bytes = 32): string {
  return [...crypto.getRandomValues(new Uint8Array(bytes))].map(n => n.toString(16).padStart(2, "0")).join("");
}
