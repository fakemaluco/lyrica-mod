import { readFile, writeFile, mkdir } from "node:fs/promises";

const entries = [
  ["/status", "index.html", "text/html"],
  ["/status/assets/status.css", "status.css", "text/css"],
  ["/status/assets/status.js", "status.js", "text/javascript"],
  ["/status/assets/space-grotesk-license.txt", "fonts/OFL.txt", "text/plain"],
];
const assets = Object.fromEntries(await Promise.all(entries.map(async ([route, file, type]) => [
  route, { body: await readFile(new URL(file, import.meta.url), "utf8"), type: type + "; charset=utf-8" },
])));
const binaryEntries = [
  ["/status/assets/space-grotesk-latin.woff2", "fonts/SpaceGrotesk-Latin.woff2", "font/woff2"],
  ["/status/assets/lyrica-icon.png", "lyrica-icon.png", "image/png"],
];
Object.assign(assets, Object.fromEntries(await Promise.all(binaryEntries.map(async ([route, file, type]) => [
  route, { type, base64: (await readFile(new URL(file, import.meta.url))).toString("base64") },
]))));
const source = await readFile(new URL("worker.mjs", import.meta.url), "utf8");
const output = new URL("../../build/", import.meta.url);
await mkdir(output, { recursive: true });
await writeFile(new URL("status-worker.mjs", output), `const assets = ${JSON.stringify(assets)};\n${source}`);
