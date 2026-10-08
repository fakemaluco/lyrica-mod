const PORT = 24873;
const TOKEN_KEY = "lyrica.youtubeMusicBridgeToken";
const VERSION = "0.2.0";

let socket;
let ready = false;
let connecting = false;
let reconnectTimer;
let token;
let activeTab;
let latestSnapshot;
let latestSnapshotAt = 0;

function send(value) {
  if (socket?.readyState === WebSocket.OPEN) socket.send(JSON.stringify(value));
}

function sendSnapshot() {
  if (ready && latestSnapshot && Date.now() - latestSnapshotAt < 5_000) {
    send({ type: "snapshot", snapshot: latestSnapshot });
  }
}

function announce() {
  ready = true;
  send({ type: "client_hello", clientKind: "youtube_music",
    protocolVersion: 2, extensionVersion: VERSION, capabilities: { artwork: true } });
  sendSnapshot();
}

async function connect() {
  clearTimeout(reconnectTimer);
  if (connecting || socket?.readyState === WebSocket.OPEN
    || socket?.readyState === WebSocket.CONNECTING) return;
  connecting = true;
  ready = false;
  try {
    token = (await chrome.storage.local.get(TOKEN_KEY))[TOKEN_KEY] || null;
    socket = new WebSocket(`ws://127.0.0.1:${PORT}`);
  } catch {
    reconnectTimer = setTimeout(connect, 2500);
    return;
  } finally {
    connecting = false;
  }
  socket.addEventListener("open", () => {
    if (token) send({ type: "authenticate", token });
  });
  socket.addEventListener("message", async event => {
    let message;
    try { message = JSON.parse(event.data); } catch { return; }
    if (message.type === "pairing_code" && !token) {
      send({ type: "pair", code: message.code });
    } else if (message.type === "paired" && message.token) {
      token = message.token;
      await chrome.storage.local.set({ [TOKEN_KEY]: token });
      announce();
    } else if (message.type === "ready") {
      announce();
    } else if (message.type === "error") {
      token = null;
      await chrome.storage.local.remove(TOKEN_KEY);
      socket.close();
    } else if (message.type === "command" && activeTab !== undefined) {
      chrome.tabs.sendMessage(activeTab, { type: "command", command: message.command }).catch(() => {});
    } else if (message.type === "request_state" && activeTab !== undefined) {
      chrome.tabs.sendMessage(activeTab, { type: "request_state" }).catch(() => {});
      sendSnapshot();
    }
  });
  socket.addEventListener("close", () => {
    ready = false;
    reconnectTimer = setTimeout(connect, 2500);
  });
  socket.addEventListener("error", () => socket?.close());
}

chrome.runtime.onMessage.addListener((message, sender) => {
  if (message?.type !== "snapshot" || !sender.tab?.url?.startsWith("https://music.youtube.com/")) return;
  const tab = sender.tab.id;
  if (activeTab !== undefined && activeTab !== tab && !message.snapshot?.playing) return;
  activeTab = tab;
  latestSnapshot = message.snapshot;
  latestSnapshotAt = Date.now();
  if (ready) sendSnapshot();
  else void connect();
});

chrome.tabs.onRemoved.addListener(tab => {
  if (tab === activeTab) {
    activeTab = undefined;
    latestSnapshot = undefined;
    latestSnapshotAt = 0;
  }
});
