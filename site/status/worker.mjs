const headers = {
  "X-Content-Type-Options": "nosniff",
  "Referrer-Policy": "no-referrer",
  "Content-Security-Policy": "default-src 'none'; script-src 'self'; style-src 'self'; font-src 'self'; img-src 'self'; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'",
};
const upstream = "https://thfukfqgjmgelikrdeti.supabase.co/functions/v1/lyrica-status/snapshot";
const publishableKey = "sb_publishable_t8VixNoVtAslOR40K6mNKA_EKtikJf4";

export default {
  async fetch(request, _env, ctx) {
    const url = new URL(request.url);
    if (request.method !== "GET" && request.method !== "HEAD") {
      return new Response("Method not allowed", { status: 405, headers: { ...headers, Allow: "GET, HEAD" } });
    }
    const head = request.method === "HEAD";
    if (url.pathname === "/status/") return Response.redirect(new URL("/status", request.url), 308);
    if (url.pathname === "/status/data") {
      const cacheKey = new Request(new URL("/status/data", request.url));
      const cached = await caches.default.match(cacheKey);
      if (cached) return head ? new Response(null, cached) : cached;
      try {
        const snapshot = await fetch(upstream, { headers: { apikey: publishableKey }, signal: AbortSignal.timeout(8_000) });
        if (!snapshot.ok || !snapshot.headers.get("content-type")?.startsWith("application/json")) {
          throw new Error("snapshot_unavailable");
        }
        const response = new Response(snapshot.body, {
          headers: { ...headers, "Content-Type": "application/json; charset=utf-8", "Cache-Control": "public, max-age=30" },
        });
        ctx.waitUntil(caches.default.put(cacheKey, response.clone()).catch(() => {}));
        return head ? new Response(null, response) : response;
      } catch (error) {
        console.error(JSON.stringify({ event: "snapshot_unavailable", cause: error instanceof Error ? error.name : "Unknown" }));
        return new Response(head ? null : '{"error":"snapshot_unavailable"}', {
          status: 503, headers: { ...headers, "Cache-Control": "no-store", "Content-Type": "application/json" },
        });
      }
    }
    const asset = Object.hasOwn(assets, url.pathname) ? assets[url.pathname] : null;
    if (!asset) return new Response(head ? null : "Not found", { status: 404, headers });
    const body = head ? null : asset.base64 ? Uint8Array.from(atob(asset.base64), char => char.charCodeAt(0)) : asset.body;
    return new Response(body, {
      headers: { ...headers, "Content-Type": asset.type, "Cache-Control": "public, max-age=300" },
    });
  },
};
