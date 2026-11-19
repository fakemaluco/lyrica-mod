import { parseReport } from "./protocol.ts";

const projectUrl = Deno.env.get("SUPABASE_URL")!;
const serviceKey = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!;
const headers = { apikey: serviceKey, Authorization: `Bearer ${serviceKey}`, "Content-Type": "application/json" };

async function rpc(name: string, body: unknown) {
  return await fetch(`${projectUrl}/rest/v1/rpc/${name}`, {
    method: "POST", headers, body: JSON.stringify(body), signal: AbortSignal.timeout(6500),
  });
}

async function readReport(req: Request) {
  const input = req.body?.getReader();
  if (!input) return null;
  const decoder = new TextDecoder();
  let raw = "", length = 0;
  try {
    while (true) {
      const { done, value } = await input.read();
      if (done) break;
      length += value.length;
      if (length > 4096) { await input.cancel(); return null; }
      raw += decoder.decode(value, { stream: true });
    }
    return parseReport(JSON.parse(raw + decoder.decode()));
  } catch {
    return null;
  } finally {
    input.releaseLock();
  }
}

async function limitKey(req: Request): Promise<string> {
  const ip = req.headers.get("cf-connecting-ip") ?? "unknown";
  const date = new Date().toISOString().slice(0, 10);
  const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(`${serviceKey}|${date}|${ip}`));
  return "status:" + Array.from(new Uint8Array(digest), b => b.toString(16).padStart(2, "0")).join("");
}

function json(data: unknown, status = 200) {
  return Response.json(data, { status, headers: { "Cache-Control": status === 200 ? "public, max-age=30" : "no-store",
    "X-Content-Type-Options": "nosniff", ...(status === 429 ? { "Retry-After": "60" } : {}) } });
}

Deno.serve(async (req: Request) => {
  try {
    const route = new URL(req.url).pathname.split("/").pop();
    if (route === "snapshot" && req.method === "GET") {
      const snapshot = await rpc("lyrica_status_snapshot", {});
      return snapshot.ok ? json(await snapshot.json()) : json({ error: "snapshot_unavailable" }, 503);
    }
    if (route !== "report" || req.method !== "POST") return json({ error: "not_found" }, 404);
    if (Number(req.headers.get("content-length") ?? 0) > 4096) return json({ error: "invalid_report" }, 400);
    const report = await readReport(req);
    if (!report) return json({ error: "invalid_report" }, 400);
    const result = await rpc("lyrica_status_record", { ...report, p_limit_key: await limitKey(req) });
    if (result.ok) return new Response(null, { status: 204, headers: { "Cache-Control": "no-store" } });
    if ((await result.text()).includes("rate_limited")) return json({ error: "rate_limited" }, 429);
    return json({ error: "metrics_unavailable" }, 503);
  } catch {
    return json({ error: "status_unavailable" }, 503);
  }
});
