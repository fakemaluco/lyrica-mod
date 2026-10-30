use crate::{MusicCapabilities, MusicCommand, PlaybackSnapshot};
use anyhow::{Result, bail};
use chrono::Utc;
use std::process::Command;

pub struct SystemMedia;

impl SystemMedia {
    pub fn new() -> Result<Self> {
        Ok(Self)
    }

    fn script(script: &str) -> Result<String> {
        let output = Command::new("osascript").args(["-e", script]).output()?;
        if !output.status.success() {
            bail!("AppleScript failed")
        }
        Ok(String::from_utf8_lossy(&output.stdout).trim().to_owned())
    }

    pub fn snapshot(&mut self) -> Result<PlaybackSnapshot> {
        let value = Self::script(
            "tell application \"Spotify\" to return {name of current track, artist of current track, album of current track, spotify url of current track, player position, duration of current track, player state as text}",
        )?;
        let parts: Vec<_> = value.split(", ").collect();
        Ok(PlaybackSnapshot {
            title: parts.first().unwrap_or(&"Nothing playing").to_string(),
            artist: parts.get(1).unwrap_or(&"").to_string(),
            album: parts.get(2).unwrap_or(&"").to_string(),
            artwork_url: None,
            spotify_uri: parts.get(3).map(|v| v.to_string()),
            device_name: Some("Spotify Desktop".into()),
            position_millis: parts
                .get(4)
                .and_then(|v| v.parse::<f64>().ok())
                .map(|v| (v * 1000.0) as i64)
                .unwrap_or(0),
            duration_millis: parts.get(5).and_then(|v| v.parse().ok()).unwrap_or(0),
            playing: parts.get(6).is_some_and(|value| value.contains("playing")),
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
            capabilities: MusicCapabilities {
                play_pause: true,
                previous: true,
                next: true,
                seek: true,
                volume: false,
                shuffle: false,
                repeat: false,
                spotify_uri: true,
                device_name: true,
            },
        })
    }

    pub fn snapshot_for(&mut self, _source: &str) -> Result<PlaybackSnapshot> { self.snapshot() }

    pub fn command(&mut self, command: &MusicCommand) -> Result<()> {
        let script = match command.action.as_str() {
            "PLAY_PAUSE" => "tell application \"Spotify\" to playpause".to_owned(),
            "PREVIOUS" => "tell application \"Spotify\" to previous track".to_owned(),
            "NEXT" => "tell application \"Spotify\" to next track".to_owned(),
            "SEEK" => format!(
                "tell application \"Spotify\" to set player position to {}",
                command.value.unwrap_or(0.0) / 1000.0
            ),
            _ => return Ok(()),
        };
        Self::script(&script)?;
        Ok(())
    }

    pub fn command_for(&mut self, command: &MusicCommand, _source: &str) -> Result<()> {
        self.command(command)
    }
}
