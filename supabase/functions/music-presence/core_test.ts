import { strict as assert } from "node:assert";
import { cover, digest, offlineId, text, validIdentity, verifiedProfile } from "./core.ts";
Deno.test("Minecraft identity requires the verified account or its own offline UUID", () => {
  const account = "069a79f4-44e9-4726-a5be-fca90e38aaf5";
  assert.equal(offlineId("Notch"), "b50ad385-829d-3141-a216-7e7d7539ba7f");
  assert(validIdentity(account, account, "Notch"));
  assert(validIdentity(account, offlineId("Notch"), "Notch"));
  assert(!validIdentity(account, offlineId("SomeoneElse"), "Notch"));
  assert(verifiedProfile({ id: account.replaceAll("-", ""), name: "Notch" }, account, "Notch"));
  assert(!verifiedProfile({ id: account.replaceAll("-", ""), name: "SomeoneElse" }, account, "Notch"));
});
Deno.test("Only bounded, unformatted metadata and exact trusted HTTPS cover hosts are shared", async () => {
  assert.equal(text("\u0000§aSong\n", 256), "Song");
  assert.equal(text("x".repeat(1000), 256).length, 256);
  assert.equal(text("x".repeat(255) + "🎵🎵", 256), "x".repeat(255) + "🎵");
  for (const url of ["http://i.scdn.co/x", "https://i.scdn.co.evil.test/x", "https://user@i.scdn.co/x", "file:///secret", "data:image/png;base64,AAA", "https://127.0.0.1/x"])
    assert.equal(cover(url), "");
  assert.equal(cover("https://i.scdn.co/image/test"), "https://i.scdn.co/image/test");
  assert.equal((await digest("test")).length, 64);
});

Deno.test("Inline covers allow higher resolution within the existing byte budget", () => {
  const bytes = new Uint8Array(33), v = new DataView(bytes.buffer);
  v.setBigUint64(0, 0x89504e470d0a1a0an); v.setUint32(8, 13); v.setUint32(12, 0x49484452);
  v.setUint32(16, 256); v.setUint32(20, 256);
  const inline = () => "data:image/png;base64," + btoa(String.fromCharCode(...bytes));
  assert.equal(cover(inline()), inline());
  v.setUint32(16, 257); assert.equal(cover(inline()), "");
});
