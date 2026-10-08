const { test } = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");

test("reports YouTube playback through the extension worker and routes controls", () => {
  const sent = [];
  let onMessage;
  const video = { paused: false, ended: false, duration: 210, currentTime: 42, volume: .6 };
  const next = { clicked: 0, click() { this.clicked++; } };
  const selectors = new Map([
    ["ytmusic-player video, #movie_player video, video", video],
    ["ytmusic-player-bar .next-button", next],
  ]);
  const context = {
    URL, Date, location: { href: "https://music.youtube.com/watch?v=abcdefghijk" },
    navigator: { mediaSession: { metadata: { title: "Track", artist: "Artist", album: "Album",
      artwork: [{ src: "https://lh3.googleusercontent.com/example" }] } } },
    document: { querySelector: selector => selectors.get(selector) || null, addEventListener() {} },
    chrome: { runtime: {
      sendMessage: message => { sent.push(message); return Promise.resolve(); },
      onMessage: { addListener: listener => { onMessage = listener; } },
    } },
    setInterval() {}, setTimeout(callback) { callback(); },
  };
  vm.runInNewContext(fs.readFileSync(path.join(__dirname, "content.js"), "utf8"), context);

  assert.equal(sent[0].type, "snapshot");
  assert.equal(sent[0].snapshot.title, "Track");
  assert.equal(sent[0].snapshot.spotifyUri, "youtube:video:abcdefghijk");
  onMessage({ type: "command", command: { action: "SEEK", value: 12_000 } });
  assert.equal(video.currentTime, 12);
  onMessage({ type: "command", command: { action: "NEXT" } });
  assert.equal(next.clicked, 1);
  onMessage({ type: "request_state" });
  assert.equal(sent.at(-1).snapshot.positionMillis, 12_000);
});
