use crate::{MusicCapabilities, MusicCommand, PlaybackSnapshot};
use anyhow::{Context, Result};
use chrono::Utc;
use mpris::{PlaybackStatus, PlayerFinder};

pub struct SystemMedia;

impl SystemMedia {
    pub fn new() -> Result<Self> {
        Ok(Self)
    }

    pub fn snapshot(&mut self) -> Result<PlaybackSnapshot> {
        let player = PlayerFinder::new()?.find_active()?;
        let metadata = player.get_metadata()?;
        let position = player.get_position()?;
        Ok(PlaybackSnapshot {
            title: metadata.title().unwrap_or("Nothing playing").to_owned(),
            artist: metadata
                .artists()
                .and_then(|artists| artists.first().copied())
                .unwrap_or("")
                .to_owned(),
            album: metadata.album_name().unwrap_or("").to_owned(),
            artwork_url: metadata.art_url().map(str::to_owned),
            spotify_uri: metadata
                .url()
                .filter(|url| url.starts_with("spotify:"))
                .map(str::to_owned),
            device_name: Some(player.identity().to_owned()),
            position_millis: position.as_millis() as i64,
            duration_millis: metadata.length().map(|v| v.as_millis() as i64).unwrap_or(0),
            playing: player.get_playback_status()? == PlaybackStatus::Playing,
            volume: player.get_volume().unwrap_or(0.0),
            shuffled: player.get_shuffle().unwrap_or(false),
            repeat_mode: format!(
                "{:?}",
                player.get_loop_status().unwrap_or(mpris::LoopStatus::None)
            )
            .to_lowercase(),
            liked: false,
            observed_at: Utc::now(),
            capabilities: MusicCapabilities {
                play_pause: true,
                previous: true,
                next: true,
                seek: true,
                volume: true,
                shuffle: true,
                repeat: true,
                spotify_uri: metadata.url().is_some(),
                device_name: true,
            },
        })
    }

    pub fn snapshot_for(&mut self, _source: &str) -> Result<PlaybackSnapshot> { self.snapshot() }

    pub fn command(&mut self, command: &MusicCommand) -> Result<()> {
        let player = PlayerFinder::new()?.find_active()?;
        match command.action.as_str() {
            "PLAY_PAUSE" => player.play_pause()?,
            "PREVIOUS" => player.previous()?,
            "NEXT" => player.next()?,
            "SEEK" => player.set_position(
                player
                    .get_metadata()?
                    .track_id()
                    .context("track id unavailable")?,
                std::time::Duration::from_millis(command.value.unwrap_or(0.0) as u64),
            )?,
            "SET_VOLUME" => player.set_volume(command.value.unwrap_or(0.0))?,
            "TOGGLE_SHUFFLE" => player.set_shuffle(!player.get_shuffle().unwrap_or(false))?,
            _ => {}
        }
        Ok(())
    }

    pub fn command_for(&mut self, command: &MusicCommand, _source: &str) -> Result<()> {
        self.command(command)
    }
}
