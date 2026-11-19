import { parseReport } from "./protocol.ts";

const session = "25775475-e0df-4006-833e-1f1a79bda8cf";

Deno.test("Status accepts bounded aggregate counters and strips private server addresses", () => {
  const parsed = parseReport({ session, sequence: 2, network: "192.168.0.14:25565",
    counts: { lrc_requests: 7, "p.lrc_red.ok": 2 }, player: "PrivateName", error: "Private stack trace" });
  if (!parsed || parsed.p_network !== null || parsed.p_counts.lrc_requests !== 7 ||
    JSON.stringify(parsed).includes("Private") || JSON.stringify(parsed).includes("192.168")) {
    throw new Error("Unexpected report contents");
  }
  if (parseReport({ session, sequence: 1, network: "hypixel", counts: {} })?.p_network !== "hypixel")
    throw new Error("Public network dropped");
});

Deno.test("Status rejects arbitrary counters and invalid sequences", () => {
  for (const counts of [{ "p.my_provider.ok": 1 }, { lrc_requests: 601 }, { "e.stack": 1 }, { lrc_requests: -1 }]) {
    if (parseReport({ session, sequence: 1, counts })) throw new Error("Accepted invalid counter");
  }
  if (parseReport({ session, sequence: 0, counts: {} })) throw new Error("Accepted invalid sequence");
});
