const { test } = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");

test("pairs in the extension worker, forwards playback and commands", async () => {
  const sent = [];
  const tabMessages = [];
  let onMessage;
  let onRemoved;
  let socket;
  let socketCount = 0;
  class FakeSocket {
    static OPEN = 1;
    static CONNECTING = 0;
    constructor(url) { this.url = url; this.readyState = 0; this.listeners = {}; socket = this; socketCount++; }
    addEventListener(type, listener) { this.listeners[type] = listener; }
    send(value) { sent.push(JSON.parse(value)); }
    close() { this.readyState = 3; }
    emit(type, value) { this.listeners[type]?.({ data: JSON.stringify(value) }); }
  }
  const context = {
    WebSocket: FakeSocket, Date, setTimeout() {}, clearTimeout() {},
    chrome: {
      storage: { local: { get: async () => ({}), set: async () => {}, remove: async () => {} } },
      runtime: { onMessage: { addListener: listener => { onMessage = listener; } } },
      tabs: {
        sendMessage: (tab, message) => { tabMessages.push({ tab, message }); return Promise.resolve(); },
        onRemoved: { addListener: listener => { onRemoved = listener; } },
      },
    },
  };
  vm.runInNewContext(fs.readFileSync(path.join(__dirname, "background.js"), "utf8"), context);
  onMessage({ type: "snapshot", snapshot: { title: "Track", playing: true } },
    { tab: { id: 4, url: "https://music.youtube.com/watch?v=abcdefghijk" } });
  onMessage({ type: "snapshot", snapshot: { title: "Track", playing: true } },
    { tab: { id: 4, url: "https://music.youtube.com/watch?v=abcdefghijk" } });
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(socket.url, "ws://127.0.0.1:24873");
  assert.equal(socketCount, 1);
  socket.readyState = FakeSocket.OPEN;
  socket.emit("open");
  socket.emit("message", { type: "pairing_code", code: "123456" });
  assert.equal(sent.at(-1).type, "pair");
  assert.equal(sent.at(-1).code, "123456");
  socket.emit("message", { type: "paired", token: "local-token" });
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(sent.at(-2).type, "client_hello");
  assert.equal(sent.at(-2).clientKind, "youtube_music");
  assert.equal(sent.at(-1).snapshot.title, "Track");
  socket.emit("message", { type: "command", command: { action: "NEXT" } });
  assert.equal(tabMessages.at(-1).tab, 4);
  assert.equal(tabMessages.at(-1).message.command.action, "NEXT");
  onRemoved(4);
  socket.emit("message", { type: "request_state" });
  assert.equal(tabMessages.length, 1);
});
