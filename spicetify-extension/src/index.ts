(() => {
  const PORT = Number(localStorage.getItem("fandroid.port") ?? "24873");
  const TOKEN_KEY = "fandroid.pairingToken";
  const EXTENSION_VERSION = "0.4.1";
  const PROTOCOL_VERSION = 2;
  const PHONE_ICON = `
    <svg viewBox="0 0 24 24" aria-hidden="true">
      <rect x="6.5" y="2.5" width="11" height="19" rx="3" fill="none" stroke="currentColor" stroke-width="1.8"/>
      <path d="M9.5 5.5h5M10 18.2h4" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round"/>
      <path d="M9.2 12.2l1.7 1.7 3.9-4.1" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"/>
    </svg>`;

  type ConnectionState = "offline" | "authenticating" | "ready-to-pair" | "paired";
  type TopbarButton = InstanceType<NonNullable<typeof Spicetify.Topbar>["Button"]>;

  let socket: WebSocket | undefined;
  let reconnectTimer: number | undefined;
  let state: ConnectionState = "offline";
  let topbarButton: TopbarButton | undefined;
  let pairInput: HTMLInputElement | undefined;
  let pairSubmit: HTMLButtonElement | undefined;
  let pairStatus: HTMLElement | undefined;
  let pairingInFlight = false;
  let bridgeNoticeShown = false;
  let lastPlayerState: PlayerState | undefined;
  let lastLyricsFingerprint = "";
  let lastLyricsStatusFingerprint = "";
  let lyricsLookupInFlight = false;
  let lastArtworkTrack = "";
  let lastArtworkUrl = "";

  function capabilities() {
    const player = Spicetify.Player as typeof Spicetify.Player;
    return {
      playPause: typeof player.togglePlay === "function",
      previous: typeof player.back === "function",
      next: typeof player.next === "function",
      seek: typeof player.seek === "function",
      volume: typeof player.setVolume === "function" && typeof player.getVolume === "function",
      shuffle: typeof player.toggleShuffle === "function" && typeof player.getShuffle === "function",
      repeat: typeof player.toggleRepeat === "function" && typeof player.getRepeat === "function",
      spotifyUri: true,
      deviceName: false,
    };
  }

  function firstText(...values: Array<string | null | undefined>) {
    return values.find(value => value != null && value.trim().length > 0)?.trim() ?? "";
  }

  function metadataValue(metadata: Record<string, string>, ...keys: string[]) {
    return firstText(...keys.map(key => metadata[key]));
  }

  function trackArtists(item: PlayerState["item"], metadata: Record<string, string>, mediaArtist?: string) {
    const itemNames = Array.from(new Map((item?.artists ?? [])
      .map(artist => artist?.name?.trim() ?? "")
      .filter(Boolean)
      .map(name => [name.toLocaleLowerCase(), name])).values());
    if (itemNames.length > 0) return itemNames.join(", ");

    const encodedNames = metadataValue(metadata, "artist_names");
    if (encodedNames) {
      try {
        const parsed = JSON.parse(encodedNames) as unknown;
        if (Array.isArray(parsed)) {
          const names = parsed.map(entry => typeof entry === "string" ? entry
            : entry && typeof entry === "object" && typeof (entry as { name?: unknown }).name === "string"
              ? (entry as { name: string }).name : "")
            .map(name => name.trim()).filter(Boolean);
          if (names.length > 0) return Array.from(new Set(names)).join(", ");
        }
      } catch {
        // Some Spotify builds expose the already formatted artist list instead of JSON.
      }
      return encodedNames;
    }
    return firstText(metadataValue(metadata, "artist_name", "artist"), mediaArtist);
  }

  function normalizeArtworkUrl(value: string | null | undefined) {
    let artwork = value?.trim();
    if (!artwork) return null;
    const cssUrl = artwork.match(/^url\(["']?(.*?)["']?\)$/i);
    if (cssUrl) artwork = cssUrl[1];
    if (artwork.startsWith("//")) return `https:${artwork}`;
    if (artwork.startsWith("spotify:image:")) {
      const imageId = artwork.slice("spotify:image:".length);
      return imageId ? `https://i.scdn.co/image/${imageId}` : null;
    }
    if (!/^https:\/\//i.test(artwork)) return null;
    try {
      const host = new URL(artwork).hostname.toLocaleLowerCase();
      return host === "i.scdn.co" || host.endsWith(".scdn.co") || host.endsWith(".spotifycdn.com")
        ? artwork : null;
    } catch {
      return null;
    }
  }

  function nestedArtworkCandidates(value: unknown) {
    const result: string[] = [];
    const visited = new WeakSet<object>();
    const visit = (current: unknown, hint: string, depth: number) => {
      if (depth > 5 || current == null) return;
      if (typeof current === "string") {
        if (/image|artwork|cover|thumbnail|src|url/i.test(hint)
          || current.startsWith("spotify:image:")
          || /spotifycdn|scdn\.co/i.test(current)) result.push(current);
        return;
      }
      if (typeof current !== "object" || visited.has(current)) return;
      visited.add(current);
      if (Array.isArray(current)) {
        for (const item of current.slice(0, 24)) visit(item, hint, depth + 1);
        return;
      }
      for (const [key, nested] of Object.entries(current as Record<string, unknown>)) {
        visit(nested, key, depth + 1);
      }
    };
    visit(value, "", 0);
    return result;
  }

  function renderedArtworkUrl(trackTitle: string) {
    const selectors = [
      ".main-nowPlayingWidget-coverArt img",
      ".main-nowPlayingBar-left img",
      ".main-nowPlayingView-coverArt img",
      "[data-testid='cover-art-image'] img",
      "[data-testid='now-playing-widget'] img",
    ];
    for (const selector of selectors) {
      const image = document.querySelector<HTMLImageElement>(selector);
      const source = firstText(image?.currentSrc, image?.src,
        image?.srcset?.split(",").at(-1)?.trim().split(/\s+/)[0]);
      if (source) return source;
    }
    for (const element of Array.from(document.querySelectorAll<HTMLElement>(
      "[data-testid='cover-art-image'],[class*='coverArt'],[class*='CoverArt']"))) {
      const background = getComputedStyle(element).backgroundImage;
      if (background && background !== "none") return background;
    }
    let best = "";
    let bestScore = -1;
    for (const image of Array.from(document.querySelectorAll<HTMLImageElement>(
      "img[src*='scdn'],img[src*='spotifycdn'],img[src^='spotify:image:']"))) {
      const source = firstText(image.currentSrc, image.src);
      if (!source) continue;
      const bounds = image.getBoundingClientRect();
      if (bounds.width < 32 || bounds.height < 32) continue;
      const label = firstText(image.alt, image.getAttribute("aria-label"));
      const parent = image.closest("[data-testid='now-playing-widget'],[class*='nowPlayingBar'],[class*='nowPlayingWidget']");
      const titleMatch = trackTitle && label.toLocaleLowerCase().includes(trackTitle.toLocaleLowerCase());
      const nearPlayer = bounds.bottom > window.innerHeight - 180;
      const score = Math.min(bounds.width * bounds.height, 40_000)
        + (parent ? 100_000 : 0) + (titleMatch ? 60_000 : 0) + (nearPlayer ? 30_000 : 0);
      if (score > bestScore) { best = source; bestScore = score; }
    }
    return best;
  }

  function snapshot() {
    const state = Spicetify.Player.data ?? lastPlayerState;
    if (state) lastPlayerState = state;
    const item = state?.item;
    const metadata = item?.metadata ?? {};
    const media = navigator.mediaSession?.metadata;
    const title = firstText(item?.name,
      metadataValue(metadata, "title", "track_name", "name"), media?.title);
    if (!title || title.toLowerCase() === "nothing playing") return null;
    const artist = trackArtists(item, metadata, media?.artist);
    const album = firstText(metadataValue(metadata, "album_title", "album_name", "album"), media?.album);
    const itemDuration = typeof item?.duration === "number"
      ? item.duration : item?.duration?.milliseconds;
    const images = item?.images ?? item?.show?.images ?? [];
    const artworkCandidates = [
      ...["xlarge", "large", "standard", "small"].flatMap(label =>
        images.filter(image => image.label === label).map(image => image.url)),
      ...images.map(image => image.url),
      metadataValue(metadata, "image_xlarge_url"),
      metadataValue(metadata, "image_large_url"),
      metadataValue(metadata, "image_url"),
      metadataValue(metadata, "image_small_url"),
      metadataValue(metadata, "cover_url"),
      ...Array.from(media?.artwork ?? []).reverse().map((image: MediaImage) => image.src),
      renderedArtworkUrl(title),
      ...nestedArtworkCandidates(item),
      ...nestedArtworkCandidates(metadata),
    ];
    const spotifyUri = firstText(item?.uri, metadataValue(metadata, "uri", "track_uri")) || null;
    const discoveredArtwork = artworkCandidates.map(normalizeArtworkUrl).find(Boolean) ?? null;
    if (discoveredArtwork) {
      lastArtworkTrack = spotifyUri ?? `${title}\n${artist}`;
      lastArtworkUrl = discoveredArtwork;
    }
    const artworkKey = spotifyUri ?? `${title}\n${artist}`;
    const artwork = discoveredArtwork ?? (artworkKey === lastArtworkTrack ? lastArtworkUrl : null);
    const artworkStatus = artwork ?? "MISS";
    if (localStorage.getItem("fandroid.lastArtwork") !== artworkStatus) {
      localStorage.setItem("fandroid.lastArtwork", artworkStatus);
    }
    return {
      type: "snapshot",
      snapshot: {
        title,
        artist,
        album,
        artworkUrl: artwork,
        spotifyUri,
        deviceName: null,
        positionMillis: Spicetify.Player.getProgress?.() ?? 0,
        durationMillis: Spicetify.Player.getDuration?.() ?? itemDuration ?? 0,
        playing: Spicetify.Player.isPlaying?.() ?? false,
        volume: Spicetify.Player.getVolume?.() ?? 0,
        shuffled: Spicetify.Player.getShuffle?.() ?? false,
        repeatMode: String(Spicetify.Player.getRepeat?.() ?? 0),
        liked: Spicetify.Player.getHeart?.() ?? false,
        observedAt: new Date().toISOString(),
        capabilities: capabilities(),
      },
    };
  }

  function sendSnapshot() {
    if (state === "paired" && socket?.readyState === WebSocket.OPEN) {
      const current = snapshot();
      if (current) socket.send(JSON.stringify(current));
    }
  }

  function sendClientHello() {
    if (socket?.readyState !== WebSocket.OPEN || state !== "paired") return;
    socket.send(JSON.stringify({
      type: "client_hello",
      protocolVersion: PROTOCOL_VERSION,
      extensionVersion: EXTENSION_VERSION,
      capabilities: { artwork: true, spicyCache: "caches" in window, rawTtml: "indexedDB" in window },
    }));
  }

  function sendLyricsStatus(uri: string, status: "HIT_TTML" | "HIT_CACHE" | "MISS" | "ERROR", detail?: string) {
    if (socket?.readyState !== WebSocket.OPEN || state !== "paired") return;
    const fingerprint = `${uri}\n${status}\n${detail ?? ""}`;
    if (fingerprint === lastLyricsStatusFingerprint) return;
    lastLyricsStatusFingerprint = fingerprint;
    socket.send(JSON.stringify({ type: "lyrics_status", uri, status, detail }));
  }

  function spotifyTrackId(uri: string | null | undefined) {
    const match = uri?.match(/^spotify:track:([A-Za-z0-9]+)$/);
    return match?.[1] ?? null;
  }

  function extractRawTtml(value: unknown): string | null {
    if (typeof value === "string") return value.includes("<tt") ? value : null;
    if (!value || typeof value !== "object") return null;
    const record = value as Record<string, unknown>;
    for (const key of ["ttml", "TTML", "lyrics", "Lyrics", "payload", "Content"]) {
      const candidate = record[key];
      if (typeof candidate === "string" && candidate.includes("<tt")) return candidate;
    }
    return null;
  }

  function readIndexedDbValue(database: IDBDatabase, keys: string[]): Promise<string | null> {
    return new Promise(resolve => {
      if (!database.objectStoreNames.contains("lyricsStore")) {
        resolve(null);
        return;
      }
      const transaction = database.transaction("lyricsStore", "readonly");
      const store = transaction.objectStore("lyricsStore");
      let index = 0;
      const next = () => {
        if (index >= keys.length) { resolve(null); return; }
        const request = store.get(keys[index++]);
        request.onsuccess = () => {
          const ttml = extractRawTtml(request.result);
          if (ttml) resolve(ttml);
          else next();
        };
        request.onerror = next;
      };
      next();
    });
  }

  function readSpicyLocalTtml(uri: string, trackId: string): Promise<string | null> {
    return new Promise(resolve => {
      const request = indexedDB.open("spicylyrics");
      request.onsuccess = () => {
        const database = request.result;
        void readIndexedDbValue(database, [uri, trackId, `/${trackId}`])
          .then(value => resolve(value && new TextEncoder().encode(value).byteLength <= 4 * 1024 * 1024 ? value : null))
          .finally(() => database.close());
      };
      request.onerror = () => resolve(null);
      request.onupgradeneeded = () => {
        request.transaction?.abort();
        resolve(null);
      };
    });
  }

  async function readSpicyProcessedLyrics(trackId: string): Promise<unknown | null> {
    if (!("caches" in window)) return null;
    try {
      const available = await caches.keys();
      const names = ["SpicyLyrics_LyricsStore_g1", ...available
        .filter(name => name.startsWith("SpicyLyrics_LyricsStore") && name !== "SpicyLyrics_LyricsStore_g1")
        .reverse()];
      for (const name of names) {
        if (!available.includes(name)) continue;
        const cache = await caches.open(name);
        const response = await cache.match(`/${trackId}`);
        if (!response?.ok) continue;
        const declaredLength = Number(response.headers.get("content-length") ?? 0);
        if (declaredLength > 2 * 1024 * 1024) continue;
        const body = await response.text();
        if (new TextEncoder().encode(body).byteLength > 2 * 1024 * 1024) continue;
        const wrapper = JSON.parse(body) as { ExpiresAt?: number; Content?: unknown };
        if (!wrapper.Content) continue;
        if (typeof wrapper.ExpiresAt === "number" && wrapper.ExpiresAt <= Date.now()) continue;
        return wrapper.Content;
      }
      return null;
    } catch {
      return null;
    }
  }

  function payloadFingerprint(uri: string, format: string, payload: string) {
    return `${uri}\n${format}\n${payload.length}\n${payload.slice(0, 96)}\n${payload.slice(-96)}`;
  }

  function extractSongwriters(value: unknown): string[] {
    if (!value || typeof value !== "object") return [];
    const names: string[] = [];
    const readNames = (candidate: unknown, depth = 0): void => {
      if (depth > 3 || candidate == null) return;
      if (typeof candidate === "string") {
        const name = candidate.trim();
        if (name) names.push(name.slice(0, 160));
        return;
      }
      if (Array.isArray(candidate)) {
        candidate.forEach(entry => readNames(entry, depth + 1));
        return;
      }
      if (typeof candidate === "object") {
        const record = candidate as Record<string, unknown>;
        for (const key of ["name", "Name", "fullName", "FullName", "displayName", "DisplayName"]) {
          if (record[key] != null) readNames(record[key], depth + 1);
        }
      }
    };
    const containers = [value];
    const root = value as Record<string, unknown>;
    for (const key of ["Metadata", "metadata", "Credits", "credits"]) {
      if (root[key] && typeof root[key] === "object") containers.push(root[key]);
    }
    for (const container of containers) {
      const record = container as Record<string, unknown>;
      for (const key of ["SongWriters", "songWriters", "songwriters", "Writers", "writers"]) {
        if (record[key] != null) readNames(record[key]);
      }
    }
    const unique = new Map<string, string>();
    names.forEach(name => unique.set(name.toLocaleLowerCase(), name));
    return Array.from(unique.values()).slice(0, 32);
  }

  type LyricsAttribution = {
    providerCode: string;
    communityProvided: boolean;
    makerUsername: string;
    makerAvatar: string;
    uploaderUsername: string;
    uploaderAvatar: string;
  };

  function extractLyricsAttribution(value: unknown): LyricsAttribution {
    const record = value && typeof value === "object" ? value as Record<string, unknown> : {};
    const providerCode = typeof record.source === "string"
      ? record.source.trim().toLocaleLowerCase().slice(0, 24) : "spl";
    const upload = record.TTMLUploadMetadata && typeof record.TTMLUploadMetadata === "object"
      ? record.TTMLUploadMetadata as Record<string, unknown> : {};
    const contributor = (key: "Maker" | "Uploader") => {
      const raw = upload[key] && typeof upload[key] === "object"
        ? upload[key] as Record<string, unknown> : {};
      return {
        username: typeof raw.username === "string" ? raw.username.trim().slice(0, 160) : "",
        avatar: typeof raw.avatar === "string" ? raw.avatar.trim().slice(0, 2048) : "",
      };
    };
    const maker = contributor("Maker");
    const uploader = contributor("Uploader");
    return {
      providerCode,
      communityProvided: providerCode === "spl",
      makerUsername: maker.username,
      makerAvatar: maker.avatar,
      uploaderUsername: uploader.username,
      uploaderAvatar: uploader.avatar,
    };
  }

  function sendLyricsPayload(uri: string, format: "ttml" | "spicy-json", payload: string, source: string,
                             songwriters: string[] = [], attribution: LyricsAttribution = extractLyricsAttribution(null)) {
    const maxBytes = format === "ttml" ? 4 * 1024 * 1024 : 2 * 1024 * 1024;
    if (new TextEncoder().encode(payload).byteLength > maxBytes) return;
    const fingerprint = payloadFingerprint(uri, format, payload) + "\n"
      + JSON.stringify({ songwriters, ...attribution });
    if (fingerprint === lastLyricsFingerprint || socket?.readyState !== WebSocket.OPEN || state !== "paired") return;
    socket.send(JSON.stringify({ type: "lyrics_payload", uri, format, payload, source, songwriters,
      ...attribution }));
    lastLyricsFingerprint = fingerprint;
  }

  async function sendSpicyLyrics() {
    if (lyricsLookupInFlight || state !== "paired" || socket?.readyState !== WebSocket.OPEN) return;
    const current = snapshot();
    const uri = current?.snapshot.spotifyUri;
    const trackId = spotifyTrackId(uri);
    if (!uri || !trackId) return;
    lyricsLookupInFlight = true;
    try {
      const localTtml = await readSpicyLocalTtml(uri, trackId);
      if (localTtml) {
        const processed = await readSpicyProcessedLyrics(trackId);
        sendLyricsStatus(uri, "HIT_TTML", "spicylyrics/lyricsStore");
        sendLyricsPayload(uri, "ttml", localTtml, "Spicy Lyrics local TTML",
          extractSongwriters(processed), extractLyricsAttribution(processed));
        return;
      }
      const processed = await readSpicyProcessedLyrics(trackId);
      if (processed) {
        const parsed = processed as { Type?: unknown; Content?: unknown };
        const type = typeof parsed.Type === "string" ? parsed.Type.toLocaleLowerCase() : "";
        if (!Array.isArray(parsed.Content) || parsed.Content.length === 0
          || (type !== "syllable" && type !== "line")) {
          sendLyricsStatus(uri, "MISS", "Spicy cache entry is static or empty");
          return;
        }
        sendLyricsStatus(uri, "HIT_CACHE", "SpicyLyrics_LyricsStore");
        sendLyricsPayload(uri, "spicy-json", JSON.stringify(processed), "Spicy Lyrics cache",
          extractSongwriters(processed), extractLyricsAttribution(processed));
        return;
      }
      sendLyricsStatus(uri, "MISS", "No entry for the current Spotify track");
    } catch (error) {
      sendLyricsStatus(uri, "ERROR", error instanceof Error ? error.message.slice(0, 220) : "Cache read failed");
    } finally {
      lyricsLookupInFlight = false;
    }
  }

  function applyCommand(message: { command?: { action?: string; value?: number } }) {
    const command = message.command;
    try {
      switch (command?.action) {
        case "PLAY_PAUSE": Spicetify.Player.togglePlay(); break;
        case "PREVIOUS": Spicetify.Player.back(); break;
        case "NEXT": Spicetify.Player.next(); break;
        case "SEEK": Spicetify.Player.seek(command.value ?? 0); break;
        case "SET_VOLUME": Spicetify.Player.setVolume?.(command.value ?? 0); break;
        case "TOGGLE_SHUFFLE": Spicetify.Player.toggleShuffle?.(); break;
        case "TOGGLE_HEART": {
          const current = Spicetify.Player.getHeart?.();
          if (typeof current === "boolean" && typeof Spicetify.Player.setHeart === "function") {
            Spicetify.Player.setHeart(!current);
          } else {
            Spicetify.Player.toggleHeart?.();
          }
          break;
        }
        case "CYCLE_REPEAT": Spicetify.Player.toggleRepeat?.(); break;
      }
    } catch (error) {
      console.error("Relay player command failed", command?.action, error);
    }
    window.setTimeout(sendSnapshot, 100);
  }

  function setState(next: ConnectionState) {
    state = next;
    if (!topbarButton) return;
    const labels: Record<ConnectionState, string> = {
      offline: "Relay offline",
      authenticating: "Connecting Relay",
      "ready-to-pair": "Pair Relay",
      paired: "Relay connected",
    };
    const colors: Record<ConnectionState, string> = {
      offline: "#8f949b",
      authenticating: "#e7b85b",
      "ready-to-pair": "#c59be8",
      paired: "#7fd38a",
    };
    topbarButton.label = labels[next];
    topbarButton.element.style.color = colors[next];
    topbarButton.element.style.borderRadius = "999px";
    topbarButton.element.style.transition = "color 160ms ease, background 160ms ease, transform 160ms ease";
    topbarButton.element.style.background = next === "ready-to-pair" ? "rgba(197,155,232,.14)" : "transparent";
    topbarButton.tippy?.setContent(labels[next]);
    updatePairControls();
  }

  function setPairStatus(message: string, error = false) {
    if (pairStatus) {
      pairStatus.textContent = message;
      pairStatus.style.color = error ? "#ff9c96" : "#b3b3b3";
    }
  }

  function updatePairControls() {
    const connected = socket?.readyState === WebSocket.OPEN;
    if (pairInput) pairInput.disabled = !connected || pairingInFlight || state === "paired";
    if (pairSubmit) {
      pairSubmit.disabled = !connected || pairingInFlight;
      pairSubmit.textContent = pairingInFlight ? "Connecting…"
        : !connected ? "Waiting for bridge…"
        : state === "paired" ? "Pair again"
        : "Pair Relay";
      pairSubmit.style.opacity = pairSubmit.disabled ? ".58" : "1";
    }
  }

  function openPairModal() {
    const wrapper = document.createElement("div");
    wrapper.style.cssText = "padding:4px 2px 2px;color:#fff;font-family:var(--encore-body-font-stack,system-ui);";
    wrapper.innerHTML = `
      <div style="display:flex;gap:16px;align-items:center;margin-bottom:20px">
        <div style="width:52px;height:52px;display:grid;place-items:center;border-radius:18px;background:linear-gradient(145deg,#b879e1,#6d4b9b);box-shadow:0 12px 32px rgba(109,75,155,.3);color:white">
          <div style="width:28px;height:28px">${PHONE_ICON}</div>
        </div>
        <div>
          <div style="font-size:18px;font-weight:700;line-height:1.2">Connect Minecraft</div>
          <div style="margin-top:5px;color:#b3b3b3;font-size:13px">Local connection · 127.0.0.1:${PORT}</div>
        </div>
      </div>
      <label for="fandroid-code" style="display:block;margin-bottom:8px;font-size:13px;font-weight:650">Six-digit code</label>
      <input id="fandroid-code" maxlength="6" inputmode="numeric" autocomplete="one-time-code" placeholder="000000"
        style="box-sizing:border-box;width:100%;padding:14px 16px;border-radius:14px;border:1px solid #555;background:#202124;color:#fff;font-size:22px;font-weight:700;letter-spacing:.35em;text-align:center;outline:none" />
      <div id="fandroid-status" style="min-height:18px;margin:10px 2px 14px;color:#b3b3b3;font-size:13px"></div>
      <button id="fandroid-pair" style="box-sizing:border-box;width:100%;padding:13px 18px;border:0;border-radius:999px;background:linear-gradient(135deg,#5f9faf,#4f6570);color:#fff;font-size:14px;font-weight:750;cursor:pointer;transition:transform 140ms ease,opacity 140ms ease">Pair Relay</button>
      <p style="margin:16px 2px 0;color:#8f949b;font-size:12px;line-height:1.45">The code is shown in Relay's Music app. No Spotify password or OAuth token leaves this computer.</p>`;

    pairInput = wrapper.querySelector<HTMLInputElement>("#fandroid-code") ?? undefined;
    pairSubmit = wrapper.querySelector<HTMLButtonElement>("#fandroid-pair") ?? undefined;
    pairStatus = wrapper.querySelector<HTMLElement>("#fandroid-status") ?? undefined;
    pairingInFlight = false;

    const submit = () => {
      if (state === "paired") {
        localStorage.removeItem(TOKEN_KEY);
        setPairStatus("Reconnecting for a new pairing code…");
        setState("offline");
        socket?.close();
        return;
      }
      const code = pairInput?.value.replace(/\D/g, "") ?? "";
      if (!/^\d{6}$/.test(code)) {
        setPairStatus("Enter all six digits shown in Minecraft.", true);
        pairInput?.focus();
        return;
      }
      if (socket?.readyState !== WebSocket.OPEN) {
        setPairStatus("Bridge is offline. Keep Minecraft open while it reconnects.", true);
        updatePairControls();
        return;
      }
      pairingInFlight = true;
      setPairStatus("Checking the code…");
      updatePairControls();
      socket.send(JSON.stringify({ type: "pair", code }));
    };

    pairInput?.addEventListener("input", () => {
      if (pairInput) pairInput.value = pairInput.value.replace(/\D/g, "").slice(0, 6);
      setPairStatus("");
    });
    pairInput?.addEventListener("keydown", event => {
      if (event.key === "Enter") submit();
    });
    pairSubmit?.addEventListener("click", submit);

    Spicetify.PopupModal.display({ title: "Pair Relay", content: wrapper });
    if (state === "paired") {
      setPairStatus("Connected. Playback and controls are being sent to Minecraft.");
    } else if (socket?.readyState === WebSocket.OPEN) {
      setPairStatus("Enter the current code from Minecraft.");
      window.setTimeout(() => pairInput?.focus(), 50);
    } else {
      setPairStatus("Bridge is offline. Start Minecraft with Relay first.", true);
    }
    updatePairControls();
  }

  function createTopbarButton() {
    if (!Spicetify.Topbar?.Button || topbarButton) return;
    topbarButton = new Spicetify.Topbar.Button("Pair Relay", PHONE_ICON, openPairModal, false, true);
    setState(state);
  }

  function connect() {
    window.clearTimeout(reconnectTimer);
    if (socket?.readyState === WebSocket.OPEN || socket?.readyState === WebSocket.CONNECTING) return;
    setState("offline");
    socket = new WebSocket(`ws://127.0.0.1:${PORT}`);
    socket.addEventListener("open", () => {
      const token = localStorage.getItem(TOKEN_KEY);
      if (token) {
        setState("authenticating");
        socket?.send(JSON.stringify({ type: "authenticate", token }));
      } else {
        setState("ready-to-pair");
        if (!bridgeNoticeShown) {
          bridgeNoticeShown = true;
          Spicetify.showNotification("Relay found — use the phone icon to pair");
        }
        setPairStatus("Enter the current code from Minecraft.");
        updatePairControls();
      }
    });
    socket.addEventListener("message", event => {
      const message = JSON.parse(String(event.data)) as {
        type?: string;
        token?: string;
        error?: string;
        command?: { action?: string; value?: number };
      };
      if (message.type === "paired" && message.token) {
        localStorage.setItem(TOKEN_KEY, message.token);
        pairingInFlight = false;
        setState("paired");
        Spicetify.PopupModal.hide();
        Spicetify.showNotification("Relay connected");
        sendClientHello();
        sendSnapshot();
        void sendSpicyLyrics();
      } else if (message.type === "ready") {
        pairingInFlight = false;
        setState("paired");
        updatePairControls();
        sendClientHello();
        sendSnapshot();
        void sendSpicyLyrics();
      } else if (message.type === "command") {
        applyCommand(message);
      } else if (message.type === "request_state") {
        sendSnapshot();
        void sendSpicyLyrics();
      } else if (message.type === "error") {
        localStorage.removeItem(TOKEN_KEY);
        pairingInFlight = false;
        setState("ready-to-pair");
        const invalidCode = message.error === "invalid_code";
        setPairStatus(invalidCode
          ? "That code was not accepted. Check the current code in Minecraft and try again."
          : "The saved connection expired. Re-enter the current Minecraft code.", true);
        updatePairControls();
      }
    });
    socket.addEventListener("close", () => {
      setState("offline");
      updatePairControls();
      reconnectTimer = window.setTimeout(connect, 2500);
    });
    socket.addEventListener("error", () => socket?.close());
  }

  function init() {
    if (!Spicetify.Player || !Spicetify.PopupModal) {
      window.setTimeout(init, 100);
      return;
    }
    createTopbarButton();
    const playerChanged = (event?: { data?: PlayerState | number }) => {
      if (event?.data && typeof event.data === "object") lastPlayerState = event.data;
      lastLyricsFingerprint = "";
      lastLyricsStatusFingerprint = "";
      sendSnapshot();
      window.setTimeout(() => void sendSpicyLyrics(), 250);
    };
    lastPlayerState = Spicetify.Player.data;
    Spicetify.Player.addEventListener("songchange", playerChanged);
    Spicetify.Player.addEventListener("onplaypause", playerChanged);
    window.setInterval(sendSnapshot, 2_000);
    window.setInterval(() => void sendSpicyLyrics(), 3_000);
    document.addEventListener("visibilitychange", () => {
      sendSnapshot();
      void sendSpicyLyrics();
    });
    connect();
  }

  init();
})();
