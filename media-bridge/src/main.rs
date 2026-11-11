mod ducking;
mod platform;
mod youtube_companion;
mod timeline;
mod session_selection;
mod reactive_audio;
mod island_meter;

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

enum Event {
    Command(MusicCommand, String),
    Source(String),
    ReloadAudioOutput,
    YoutubeSnapshot(PlaybackSnapshot),
    YoutubeStatus(bool),
    Shutdown,
}

fn stdin_events(sender: mpsc::Sender<Event>, audio: std::sync::Arc<reactive_audio::ReactiveAudioStream>,
                ducking: mpsc::Sender<ducking::Command>) {
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
                Some("ducking") => {
                    if let Ok(request) = serde_json::from_value::<Option<ducking::Request>>(value["request"].clone()) {
                        let _ = ducking.send(ducking::Command::Update(request));
                    }
                }
                Some("audio_levels_enabled") => audio.set_enabled(value["enabled"].as_bool().unwrap_or(false)),
                Some("reload_audio_output") => {
                    if sender.send(Event::ReloadAudioOutput).is_err() { break; }
                }
                _ => {}
            }
        }
        // The helper is a child of Minecraft. EOF means the Java side closed or
        // crashed, so keeping the loopback port alive would create an orphan that
        // prevents the next game session from producing a pairing code.
        let _ = ducking.send(ducking::Command::Shutdown);
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
    let audio = reactive_audio::ReactiveAudioStream::start();
    let companion = match youtube_companion::Server::start(sender.clone()) {
        Ok(companion) => Some(companion),
        Err(error) => {
            eprintln!("YouTube Music companion is unavailable: {error}");
            None
        }
    };
    let mut system = platform::SystemMedia::new()?;
    let ducking = ducking::Controller::start();
    stdin_events(sender, audio.clone(), ducking.sender.clone());
    if let Some(companion) = &companion {
        output(json!({"type": "pairing_code", "code": companion.pairing_code(), "port": companion.port()}));
    }

    let mut intensity = platform::start_intensity_capture();
    audio.replace_source(intensity.clone());
    let mut latest_youtube: Option<(PlaybackSnapshot, Instant)> = None;
    let mut youtube_connected = false;
    let mut active_source = "AUTOMATIC".to_owned();
    let mut selected_source = SelectedSource::System;
    let mut last_state_request = Instant::now() - Duration::from_secs(5);
    let mut timeline = timeline::TimelineTracker::default();
    loop {
        if !drain_events(&receiver, &mut system, companion.as_ref(), &mut latest_youtube,
            &mut youtube_connected,
            &mut active_source, &mut intensity, &audio, selected_source) {
            ducking.stop();
            audio.stop();
            intensity.stop();
            return Ok(());
        }
        if last_state_request.elapsed() >= Duration::from_secs(2) {
            if let Some(companion) = &companion {
                companion.broadcast(&json!({"type": "request_state"}).to_string());
            }
            last_state_request = Instant::now();
        }
        let system_snapshot = system.snapshot_for(&active_source).ok();
        let (mut snapshot, route) = select_playback(&active_source,
            latest_youtube.as_ref().filter(|_| youtube_connected), system_snapshot.clone());
        let active_media_app = system_snapshot.as_ref()
            .filter(|os| os.playing && same_track(&snapshot, os))
            .and_then(|os| os.device_name.as_deref());
        selected_source = route;
        snapshot.intensity = intensity.intensity();
        timeline.update(&mut snapshot, Instant::now());
        snapshot.beat_phase = intensity.beat_phase();
        snapshot.bpm = intensity.bpm();
        snapshot.spectral_centroid = intensity.centroid();
        snapshot.intensity_bands = intensity.bands();
        output(json!({"type": "snapshot", "snapshot": snapshot, "activeSource": selected_source.name(), "activeMediaApp": active_media_app}));
        thread::sleep(Duration::from_millis(500));
    }
}

fn select_snapshot_with_timeout(
    companion: Option<&(PlaybackSnapshot, Instant)>,
    system: Option<PlaybackSnapshot>,
    max_age: Duration,
) -> PlaybackSnapshot {
    let Some((rich, observed)) = companion.filter(|(snapshot, observed)| {
        snapshot.is_meaningful() && observed.elapsed() < max_age
    }) else {
        return system.unwrap_or_else(PlaybackSnapshot::unavailable);
    };
    let fresh = observed.elapsed() < Duration::from_secs(10);
    match system {
        Some(system) if same_track(rich, &system) => {
            let mut selected = rich.clone().enrich_from(Some(&system));
            if !fresh {
                selected.position_millis = system.position_millis;
                selected.playing = system.playing;
                selected.observed_at = system.observed_at;
            }
            selected
        }
        Some(_) if fresh => rich.clone(),
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
    if first_title.is_empty() || first_title != second_title { return false; }
    let first_artist = normalized(&first.artist);
    let second_artist = normalized(&second.artist);
    first_artist.is_empty() || second_artist.is_empty() || first_artist == second_artist
}

fn drain_events(
    receiver: &Receiver<Event>,
    system: &mut platform::SystemMedia,
    companion: Option<&youtube_companion::Server>,
    latest_youtube: &mut Option<(PlaybackSnapshot, Instant)>,
    youtube_connected: &mut bool,
    active_source: &mut String,
    intensity: &mut platform::IntensityCapture,
    audio: &reactive_audio::ReactiveAudioStream,
    selected_source: SelectedSource,
) -> bool {
    while let Ok(event) = receiver.try_recv() {
        match event {
            Event::Command(command, source) => {
                let targets = command_targets(&source, selected_source);
                if targets.system { let _ = system.command_for(&command, &source); }
                if targets.youtube {
                    if let Some(companion) = companion {
                        companion.broadcast(&json!({"type": "command", "command": command}).to_string());
                    }
                }
            }
            Event::Source(source) => *active_source = source,
            Event::ReloadAudioOutput => {
                intensity.stop();
                *intensity = platform::start_intensity_capture();
                audio.replace_source(intensity.clone());
                output(json!({"type": "audio_output_reloaded"}));
            }
            Event::YoutubeSnapshot(snapshot) => {
                if snapshot.is_meaningful() {
                    *latest_youtube = Some((snapshot, Instant::now()));
                } else {
                    *latest_youtube = None;
                }
            }
            Event::YoutubeStatus(connected) => {
                *youtube_connected = connected;
                output(json!({"type": "youtube_status", "youtubeConnected": connected}));
                if !connected { *latest_youtube = None; }
            }
            Event::Shutdown => return false,
        }
    }
    true
}

#[derive(Debug, PartialEq, Eq)]
struct CommandTargets { system: bool, youtube: bool }

fn command_targets(source: &str, selected: SelectedSource) -> CommandTargets {
    let route = match source {
        "SPOTIFY_ACCOUNT" => return CommandTargets { system: false, youtube: false },
        "WINDOWS_MEDIA" => SelectedSource::System,
        "YOUTUBE_MUSIC" if selected != SelectedSource::Youtube => SelectedSource::System,
        _ => selected,
    };
    CommandTargets {
        system: route == SelectedSource::System,
        youtube: route == SelectedSource::Youtube,
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum SelectedSource { System, Youtube }

impl SelectedSource {
    fn name(self) -> &'static str {
        match self {
            Self::System => "WINDOWS_MEDIA", Self::Youtube => "YOUTUBE_MUSIC",
        }
    }
}

fn select_playback(
    source: &str,
    youtube: Option<&(PlaybackSnapshot, Instant)>,
    system: Option<PlaybackSnapshot>,
) -> (PlaybackSnapshot, SelectedSource) {
    let system_route = |system: Option<PlaybackSnapshot>| {
        (system.unwrap_or_else(PlaybackSnapshot::unavailable), SelectedSource::System)
    };
    if source == "WINDOWS_MEDIA" || source == "SPOTIFY_ACCOUNT" {
        return system_route(system);
    }
    let max_age = Duration::from_secs(60);
    let Some((snapshot, observed)) = youtube.filter(|(snapshot, observed)| {
        snapshot.is_meaningful() && observed.elapsed() < max_age
    }) else {
        return system_route(system);
    };
    let fresh = observed.elapsed() < Duration::from_secs(10);
    let same = system.as_ref().is_some_and(|os| same_track(snapshot, os));
    let has_system = system.as_ref().is_some_and(PlaybackSnapshot::is_meaningful);
    let system_playing = has_system && system.as_ref().is_some_and(|os| os.playing);
    let use_youtube = if source == "YOUTUBE_MUSIC" {
        fresh || same || !has_system
    } else if system_playing {
        snapshot.playing && same
    } else {
        same || !has_system || (snapshot.playing && fresh)
    };
    if use_youtube {
        let matching_system = system.filter(|os| same_track(snapshot, os));
        (select_snapshot_with_timeout(youtube, matching_system, max_age), SelectedSource::Youtube)
    } else {
        system_route(system)
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
    fn enriches_partial_companion_metadata_from_system_media() {
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

        let selected = select_snapshot_with_timeout(
            Some(&(extension, Instant::now() - Duration::from_secs(30))),
            Some(system), Duration::from_secs(60),
        );

        assert_eq!(selected.spotify_uri.as_deref(), Some("spotify:track:123"));
        assert_eq!(
            selected.artwork_url.as_deref(),
            Some("https://i.scdn.co/image/abc")
        );
    }

    #[test]
    fn youtube_uses_windows_time_when_browser_updates_slow_down() {
        let mut youtube = PlaybackSnapshot::unavailable();
        youtube.title = "Track".into();
        youtube.artist = "Artist".into();
        youtube.spotify_uri = Some("youtube:video:abcdefghijk".into());
        youtube.position_millis = 1_000;
        youtube.playing = true;
        let mut system = PlaybackSnapshot::unavailable();
        system.title = "Track".into();
        system.artist = "Artist".into();
        system.position_millis = 20_000;
        system.playing = true;

        let selected = select_snapshot_with_timeout(
            Some(&(youtube, Instant::now() - Duration::from_secs(20))),
            Some(system), Duration::from_secs(60));
        assert_eq!(selected.position_millis, 20_000);
        assert_eq!(selected.spotify_uri.as_deref(), Some("youtube:video:abcdefghijk"));
    }

    #[test]
    fn releases_stale_companion_state_when_system_media_changes_track() {
        let mut extension = PlaybackSnapshot::unavailable();
        extension.title = "Old track".into();
        extension.spotify_uri = Some("spotify:track:old".into());
        let mut system = PlaybackSnapshot::unavailable();
        system.title = "New track".into();

        let selected = select_snapshot_with_timeout(
            Some(&(extension, Instant::now() - Duration::from_secs(30))),
            Some(system), Duration::from_secs(60),
        );

        assert_eq!(selected.title, "New track");
        assert!(selected.spotify_uri.is_none());
    }

    #[test]
    fn automatic_commands_follow_displayed_player_not_connected_companion() {
        assert_eq!(command_targets("AUTOMATIC", SelectedSource::System),
            CommandTargets { system: true, youtube: false });
        assert_eq!(command_targets("AUTOMATIC", SelectedSource::Youtube),
            CommandTargets { system: false, youtube: true });
        assert_eq!(command_targets("SPOTIFY_ACCOUNT", SelectedSource::Youtube),
            CommandTargets { system: false, youtube: false });
        assert_eq!(command_targets("YOUTUBE_MUSIC", SelectedSource::System),
            CommandTargets { system: true, youtube: false });
    }

    #[test]
    fn playing_system_app_beats_fresh_paused_companion() {
        let mut youtube = PlaybackSnapshot::unavailable();
        youtube.title = "Paused YouTube".into();
        let mut os = PlaybackSnapshot::unavailable();
        os.title = "Playing VLC".into();
        os.playing = true;
        let (selected, route) = select_playback("AUTOMATIC", Some(&(youtube, Instant::now())), Some(os));
        assert_eq!(selected.title, "Playing VLC");
        assert_eq!(route, SelectedSource::System);
    }

    #[test]
    fn playing_youtube_companion_works_without_os_session() {
        let mut youtube = PlaybackSnapshot::unavailable();
        youtube.title = "Playing YouTube".into();
        youtube.playing = true;
        let (selected, route) = select_playback("AUTOMATIC", Some(&(youtube, Instant::now())), None);
        assert_eq!(selected.title, "Playing YouTube");
        assert_eq!(route, SelectedSource::Youtube);
    }

    #[test]
    fn does_not_enrich_unrelated_songs_with_wrong_artist_or_duration() {
        let mut youtube = PlaybackSnapshot::unavailable();
        youtube.title = "YouTube song".into();
        let mut os = PlaybackSnapshot::unavailable();
        os.title = "Different song".into();
        os.artist = "Unrelated artist".into();
        os.duration_millis = 120_000;
        let selected = select_snapshot_with_timeout(Some(&(youtube, Instant::now())), Some(os), Duration::from_secs(60));
        assert!(selected.artist.is_empty());
        assert_eq!(selected.duration_millis, 0);
    }

    #[test]
    fn same_song_retains_companion_identity_and_correct_command_route() {
        let mut youtube = PlaybackSnapshot::unavailable();
        youtube.title = "Track".into();
        youtube.spotify_uri = Some("youtube:video:abcdefghijk".into());
        youtube.playing = true;
        let mut os = youtube.clone();
        os.spotify_uri = None;
        let (selected, route) = select_playback("AUTOMATIC", Some(&(youtube, Instant::now())), Some(os));
        assert_eq!(selected.spotify_uri.as_deref(), Some("youtube:video:abcdefghijk"));
        assert_eq!(route, SelectedSource::Youtube);
    }
}
