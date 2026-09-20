mod platform;
mod spicetify;

use anyhow::Result;
use chrono::{DateTime, Utc};
use serde::{Deserialize, Serialize};
use serde_json::{Value, json};
use std::io::{self, BufRead, Write};
use std::sync::mpsc::{self, Receiver};
use std::thread;
use std::time::{Duration, Instant};

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct MusicCapabilities {
    pub play_pause: bool,
    pub previous: bool,
    pub next: bool,
    pub seek: bool,
    pub volume: bool,
    pub shuffle: bool,
    pub repeat: bool,
    pub spotify_uri: bool,
    pub device_name: bool,
}

impl MusicCapabilities {
    fn unavailable() -> Self {
        Self {
            play_pause: false,
            previous: false,
            next: false,
            seek: false,
            volume: false,
            shuffle: false,
            repeat: false,
            spotify_uri: false,
            device_name: false,
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct PlaybackSnapshot {
    pub title: String,
    pub artist: String,
    pub album: String,
    pub artwork_url: Option<String>,
    pub spotify_uri: Option<String>,
    pub device_name: Option<String>,
    pub position_millis: i64,
    pub duration_millis: i64,
    pub playing: bool,
    pub volume: f64,
    pub shuffled: bool,
    pub repeat_mode: String,
    #[serde(default)]
    pub liked: bool,
    #[serde(default)]
    pub intensity: f64,
    #[serde(default)]
    pub beat_phase: f64,
    #[serde(default)]
    pub bpm: f64,
    #[serde(default)]
    pub spectral_centroid: f64,
    #[serde(default)]
    pub intensity_bands: Vec<f64>,
    pub observed_at: DateTime<Utc>,
    pub capabilities: MusicCapabilities,
}

impl PlaybackSnapshot {
    fn unavailable() -> Self {
        Self {
            title: "Nothing playing".into(),
            artist: String::new(),
            album: String::new(),
            artwork_url: None,
            spotify_uri: None,
            device_name: None,
            position_millis: 0,
            duration_millis: 0,
            playing: false,
            volume: 0.0,
            shuffled: false,
            repeat_mode: "off".into(),
            liked: false,
            intensity: 0.0,
            beat_phase: 0.0,
            bpm: 0.0,
            spectral_centroid: 0.5,
            intensity_bands: Vec::new(),
            observed_at: Utc::now(),
            capabilities: MusicCapabilities::unavailable(),
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct MusicCommand {
    pub action: String,
    pub value: Option<f64>,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct SpicetifyLyricsPayload {
    pub uri: String,
    pub format: String,
    pub payload: String,
    pub source: String,
    pub songwriters: Vec<String>,
    pub provider_code: String,
    pub community_provided: bool,
    pub maker_username: String,
    pub maker_avatar: String,
    pub uploader_username: String,
    pub uploader_avatar: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct SpicetifyClientHello {
    pub protocol_version: u32,
    pub extension_version: String,
    pub capabilities: Value,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct SpicetifyLyricsStatus {
    pub uri: String,
    pub status: String,
    pub detail: Option<String>,
}

enum Event {
    Command(MusicCommand, String),
    Source(String),
    ReloadAudioOutput,
    SpicetifySnapshot(PlaybackSnapshot),
    SpicetifyLyrics(SpicetifyLyricsPayload),
    SpicetifyClientHello(SpicetifyClientHello),
    SpicetifyLyricsStatus(SpicetifyLyricsStatus),
    SpicetifyStatus(bool),
    Shutdown,
}

fn stdin_events(sender: mpsc::Sender<Event>) {
    thread::spawn(move || {
        for line in io::stdin().lock().lines().map_while(Result::ok) {
            let Ok(value) = serde_json::from_str::<Value>(&line) else {
                continue;
            };
            match value.get("type").and_then(Value::as_str) {
                Some("command") => {
                    if let Ok(command) = serde_json::from_value(value["command"].clone()) {
                        let source = value.get("source").and_then(Value::as_str)
                            .unwrap_or("AUTOMATIC").to_owned();
                        if sender.send(Event::Command(command, source)).is_err() { break; }
                    }
                }
                Some("source") => {
                    let source = value.get("source").and_then(Value::as_str)
                        .unwrap_or("AUTOMATIC").to_owned();
                    if sender.send(Event::Source(source)).is_err() { break; }
                }
                Some("reload_audio_output") => {
                    if sender.send(Event::ReloadAudioOutput).is_err() { break; }
                }
                _ => {}
            }
        }
        // The helper is a child of Minecraft. EOF means the Java side closed or
        // crashed, so keeping the loopback port alive would create an orphan that
        // prevents the next game session from producing a pairing code.
        let _ = sender.send(Event::Shutdown);
    });
}

fn output(value: Value) {
    let stdout = io::stdout();
    let mut lock = stdout.lock();
    let _ = serde_json::to_writer(&mut lock, &value);
    let _ = writeln!(&mut lock);
    let _ = lock.flush();
}

fn main() -> Result<()> {
    let (sender, receiver) = mpsc::channel();
    stdin_events(sender.clone());
    let spicetify = spicetify::Server::start(sender)?;
    output(
        json!({"type": "pairing_code", "code": spicetify.pairing_code(), "port": spicetify.port()}),
    );

    let mut system = platform::SystemMedia::new()?;
    let mut intensity = platform::start_intensity_capture();
    let mut latest_spicetify: Option<(PlaybackSnapshot, Instant)> = None;
    let mut spicetify_connected = false;
    let mut active_source = "AUTOMATIC".to_owned();
    let mut last_state_request = Instant::now() - Duration::from_secs(5);
    loop {
        if !drain_events(&receiver, &mut system, &spicetify, &mut latest_spicetify,
            &mut spicetify_connected, &mut active_source, &mut intensity) {
            intensity.stop();
            return Ok(());
        }
        if last_state_request.elapsed() >= Duration::from_secs(2) {
            spicetify.broadcast(&json!({"type": "request_state"}).to_string());
            last_state_request = Instant::now();
        }
        let system_snapshot = system.snapshot_for(&active_source).ok();
        let spicetify_snapshot = || latest_spicetify.as_ref()
                .filter(|(snapshot, observed)| snapshot.is_meaningful()
                    && observed.elapsed() < Duration::from_secs(15 * 60))
                .map(|(snapshot, _)| snapshot.clone())
                .unwrap_or_else(PlaybackSnapshot::unavailable);
        let mut snapshot = match active_source.as_str() {
            "SPICETIFY_BRIDGE" => spicetify_snapshot(),
            "AUTOMATIC" if spicetify_connected => spicetify_snapshot(),
            "WINDOWS_MEDIA" | "YOUTUBE_MUSIC" =>
                system_snapshot.unwrap_or_else(PlaybackSnapshot::unavailable),
            "SPOTIFY_ACCOUNT" => PlaybackSnapshot::unavailable(),
            _ => select_snapshot(latest_spicetify.as_ref(), system_snapshot),
        };
        snapshot.intensity = intensity.intensity();
        snapshot.beat_phase = intensity.beat_phase();
        snapshot.bpm = intensity.bpm();
        snapshot.spectral_centroid = intensity.centroid();
        snapshot.intensity_bands = intensity.bands();
        output(json!({"type": "snapshot", "snapshot": snapshot}));
        thread::sleep(Duration::from_millis(500));
    }
}

fn select_snapshot(
    spicetify: Option<&(PlaybackSnapshot, Instant)>,
    system: Option<PlaybackSnapshot>,
) -> PlaybackSnapshot {
    let Some((rich, observed)) = spicetify.filter(|(snapshot, observed)| {
        snapshot.is_meaningful() && observed.elapsed() < Duration::from_secs(15 * 60)
    }) else {
        return system.unwrap_or_else(PlaybackSnapshot::unavailable);
    };
    let fresh = observed.elapsed() < Duration::from_secs(10);
    match system {
        Some(system) if fresh || same_track(rich, &system) => {
            rich.clone().enrich_from(Some(&system))
        }
        Some(system) => system,
        None => rich.clone(),
    }
}

fn same_track(first: &PlaybackSnapshot, second: &PlaybackSnapshot) -> bool {
    fn normalized(value: &str) -> String {
        value
            .chars()
            .filter(|character| character.is_alphanumeric())
            .flat_map(char::to_lowercase)
            .collect()
    }
    let first_title = normalized(&first.title);
    let second_title = normalized(&second.title);
    !first_title.is_empty() && first_title == second_title
}

fn drain_events(
    receiver: &Receiver<Event>,
    system: &mut platform::SystemMedia,
    spicetify: &spicetify::Server,
    latest: &mut Option<(PlaybackSnapshot, Instant)>,
    spicetify_connected: &mut bool,
    active_source: &mut String,
    intensity: &mut platform::IntensityCapture,
) -> bool {
    while let Ok(event) = receiver.try_recv() {
        match event {
            Event::Command(command, source) => {
                let targets = command_targets(&source, *spicetify_connected);
                if targets.system { let _ = system.command_for(&command, &source); }
                if targets.spicetify { spicetify.broadcast(
                    &json!({"type": "command", "command": command}).to_string()); }
            }
            Event::Source(source) => *active_source = source,
            Event::ReloadAudioOutput => {
                intensity.stop();
                *intensity = platform::start_intensity_capture();
                output(json!({"type": "audio_output_reloaded"}));
            }
            Event::SpicetifySnapshot(snapshot) => {
                if snapshot.is_meaningful() {
                    *latest = Some((snapshot, Instant::now()));
                }
            }
            Event::SpicetifyLyrics(payload) => {
                output(json!({"type": "lyrics_payload", "lyrics": payload}));
            }
            Event::SpicetifyClientHello(hello) => {
                output(json!({"type": "spicetify_client", "client": hello}));
            }
            Event::SpicetifyLyricsStatus(status) => {
                output(json!({"type": "lyrics_status", "lyricsStatus": status}));
            }
            Event::SpicetifyStatus(connected) => {
                *spicetify_connected = connected;
                output(json!({"type": "bridge_status", "spicetifyConnected": connected}));
                if !connected {
                    *latest = None;
                }
            }
            Event::Shutdown => return false,
        }
    }
    true
}

#[derive(Debug, PartialEq, Eq)]
struct CommandTargets { system: bool, spicetify: bool }

fn command_targets(source: &str, spicetify_connected: bool) -> CommandTargets {
    match source {
        "SPOTIFY_ACCOUNT" => CommandTargets { system: false, spicetify: false },
        "SPICETIFY_BRIDGE" => CommandTargets { system: false, spicetify: true },
        "AUTOMATIC" if spicetify_connected => CommandTargets { system: false, spicetify: true },
        _ => CommandTargets { system: true, spicetify: false },
    }
}

impl PlaybackSnapshot {
    fn is_meaningful(&self) -> bool {
        let title = self.title.trim();
        !title.is_empty() && !title.eq_ignore_ascii_case("nothing playing")
    }

    fn enrich_from(mut self, system: Option<&PlaybackSnapshot>) -> Self {
        let Some(system) = system else {
            return self;
        };
        if self.artist.trim().is_empty() {
            self.artist = system.artist.clone();
        }
        if self.album.trim().is_empty() {
            self.album = system.album.clone();
        }
        if self.duration_millis <= 0 {
            self.duration_millis = system.duration_millis;
        }
        if self.position_millis < 0 {
            self.position_millis = system.position_millis;
        }
        if self.device_name.is_none() {
            self.device_name = system.device_name.clone();
        }
        self.capabilities.play_pause |= system.capabilities.play_pause;
        self.capabilities.previous |= system.capabilities.previous;
        self.capabilities.next |= system.capabilities.next;
        self.capabilities.seek |= system.capabilities.seek;
        self.capabilities.device_name |= system.capabilities.device_name;
        self
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn rejects_empty_extension_snapshot_so_system_media_can_win() {
        assert!(!PlaybackSnapshot::unavailable().is_meaningful());
        let mut valid = PlaybackSnapshot::unavailable();
        valid.title = "A real track".into();
        assert!(valid.is_meaningful());
    }

    #[test]
    fn enriches_partial_spicetify_metadata_from_system_media() {
        let mut extension = PlaybackSnapshot::unavailable();
        extension.title = "Track".into();
        extension.spotify_uri = Some("spotify:track:123".into());
        let mut system = PlaybackSnapshot::unavailable();
        system.title = "Track".into();
        system.artist = "Artist".into();
        system.album = "Album".into();
        system.duration_millis = 42_000;
        let merged = extension.enrich_from(Some(&system));
        assert_eq!(merged.artist, "Artist");
        assert_eq!(merged.album, "Album");
        assert_eq!(merged.duration_millis, 42_000);
        assert_eq!(merged.spotify_uri.as_deref(), Some("spotify:track:123"));
    }

    #[test]
    fn keeps_rich_state_when_background_timers_are_throttled_for_the_same_track() {
        let mut extension = PlaybackSnapshot::unavailable();
        extension.title = "Luv (sic) pt2".into();
        extension.artist = "Nujabes".into();
        extension.spotify_uri = Some("spotify:track:123".into());
        extension.artwork_url = Some("https://i.scdn.co/image/abc".into());
        let mut system = PlaybackSnapshot::unavailable();
        system.title = "Luv sic pt2".into();
        system.artist = "Nujabes".into();

        let selected = select_snapshot(
            Some(&(extension, Instant::now() - Duration::from_secs(30))),
            Some(system),
        );

        assert_eq!(selected.spotify_uri.as_deref(), Some("spotify:track:123"));
        assert_eq!(
            selected.artwork_url.as_deref(),
            Some("https://i.scdn.co/image/abc")
        );
    }

    #[test]
    fn releases_stale_spicetify_state_when_system_media_changes_track() {
        let mut extension = PlaybackSnapshot::unavailable();
        extension.title = "Old track".into();
        extension.spotify_uri = Some("spotify:track:old".into());
        let mut system = PlaybackSnapshot::unavailable();
        system.title = "New track".into();

        let selected = select_snapshot(
            Some(&(extension, Instant::now() - Duration::from_secs(30))),
            Some(system),
        );

        assert_eq!(selected.title, "New track");
        assert!(selected.spotify_uri.is_none());
    }

    #[test]
    fn paired_automatic_commands_never_touch_system_media() {
        assert_eq!(command_targets("AUTOMATIC", true),
            CommandTargets { system: false, spicetify: true });
        assert_eq!(command_targets("AUTOMATIC", false),
            CommandTargets { system: true, spicetify: false });
        assert_eq!(command_targets("SPICETIFY_BRIDGE", false),
            CommandTargets { system: false, spicetify: true });
        assert_eq!(command_targets("SPOTIFY_ACCOUNT", true),
            CommandTargets { system: false, spicetify: false });
    }
}
