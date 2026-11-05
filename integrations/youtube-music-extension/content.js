(() => {
  function text(selector) {
    return document.querySelector(selector)?.textContent?.trim() || "";
  }

  function control(...selectors) {
    for (const selector of selectors) {
      const element = document.querySelector(selector);
      if (element) return element;
    }
    return null;
  }

  function video() {
    return document.querySelector("ytmusic-player video, #movie_player video, video");
  }

  function nowPlaying() {
    const media = navigator.mediaSession?.metadata;
    const player = video();
    const title = media?.title || text("ytmusic-player-bar .title") || "";
    const artist = media?.artist || text("ytmusic-player-bar .byline a") || "";
    const album = media?.album || "";
    const artwork = Array.from(media?.artwork || []).at(-1)?.src
      || control("ytmusic-player-bar img#img", "ytmusic-player-bar img")?.src || null;
    const playing = !!player && !player.paused && !player.ended;
    const duration = Number.isFinite(player?.duration) ? Math.max(0, player.duration) : 0;
    const position = Number.isFinite(player?.currentTime) ? Math.max(0, player.currentTime) : 0;
    const volume = Number.isFinite(player?.volume) ? player.volume : 1;
    const repeat = control("ytmusic-player-bar .repeat", "ytmusic-player-bar [aria-label*='Repeat']");
    const shuffle = control("ytmusic-player-bar .shuffle", "ytmusic-player-bar [aria-label*='Shuffle']");
    const like = control("ytmusic-like-button-renderer button[aria-pressed]", "ytmusic-player-bar [aria-label*='Like']");
    const repeatLabel = (repeat?.getAttribute("aria-label") || repeat?.getAttribute("title") || "").toLowerCase();
    const videoId = new URL(location.href).searchParams.get("v");
    return {
      title: title || "Nothing playing", artist, album,
      artworkUrl: artwork?.startsWith("https://") ? artwork : null,
      // The YouTube video identity is carried here only for stable page changes.
      // Spotify matching never treats it as a Spotify track ID.
      spotifyUri: videoId && /^[\w-]{11}$/.test(videoId) ? `youtube:video:${videoId}` : null,
      deviceName: "YouTube Music", positionMillis: Math.round(position * 1000),
      durationMillis: Math.round(duration * 1000), playing, volume,
      shuffled: shuffle?.getAttribute("aria-pressed") === "true",
      repeatMode: repeatLabel.includes("one") || repeatLabel.includes("uma") ? "track"
        : repeat?.getAttribute("aria-pressed") === "true" ? "context" : "off",
      liked: like?.getAttribute("aria-pressed") === "true",
      intensity: 0, beatPhase: 0, bpm: 0, spectralCentroid: .5, intensityBands: [],
      observedAt: new Date().toISOString(),
      capabilities: {
        playPause: !!player, previous: !!control("ytmusic-player-bar .previous-button"),
        next: !!control("ytmusic-player-bar .next-button"), seek: !!player,
        volume: !!player, shuffle: !!shuffle, repeat: !!repeat,
        spotifyUri: false, deviceName: true
      }
    };
  }

  function sendSnapshot() {
    chrome.runtime.sendMessage({ type: "snapshot", snapshot: nowPlaying() }).catch(() => {});
  }

  function click(...selectors) {
    const button = control(...selectors);
    button?.click();
  }

  function applyCommand(command) {
    const player = video();
    switch (command?.action) {
      case "PLAY_PAUSE":
        click("ytmusic-player-bar .play-pause-button");
        break;
      case "PREVIOUS": click("ytmusic-player-bar .previous-button"); break;
      case "NEXT": click("ytmusic-player-bar .next-button"); break;
      case "SEEK": if (player) player.currentTime = Math.max(0, Number(command.value) || 0) / 1000; break;
      case "SET_VOLUME": if (player) player.volume = Math.max(0, Math.min(1, Number(command.value) || 0)); break;
      case "TOGGLE_SHUFFLE": click("ytmusic-player-bar .shuffle"); break;
      case "CYCLE_REPEAT": click("ytmusic-player-bar .repeat"); break;
      case "TOGGLE_HEART": click("ytmusic-like-button-renderer button[aria-pressed]"); break;
      default: return;
    }
    setTimeout(sendSnapshot, 150);
  }

  chrome.runtime.onMessage.addListener(message => {
    if (message?.type === "command") applyCommand(message.command);
    if (message?.type === "request_state") sendSnapshot();
  });
  setInterval(sendSnapshot, 500);
  document.addEventListener("visibilitychange", sendSnapshot);
  sendSnapshot();
})();
