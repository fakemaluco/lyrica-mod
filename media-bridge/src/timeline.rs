use std::time::Instant;

use chrono::Utc;

use crate::PlaybackSnapshot;

/** Keeps lyric time moving when Windows or a background browser repeats a stale position. */
#[derive(Default)]
pub struct TimelineTracker {
    track: String,
    raw_position: i64,
    position: f64,
    observed: Option<Instant>,
    playing: bool,
}

impl TimelineTracker {
    pub fn update(&mut self, snapshot: &mut PlaybackSnapshot, now: Instant) {
        if !snapshot.is_meaningful() {
            *self = Self::default();
            return;
        }
        let track = format!("{}\n{}\n{}\n{}", snapshot.spotify_uri.as_deref().unwrap_or(""),
            snapshot.title, snapshot.artist, snapshot.duration_millis);
        let raw = snapshot.position_millis.max(0);
        if self.observed.is_none() || self.track != track {
            self.track = track;
            self.raw_position = raw;
            self.position = raw as f64;
            self.observed = Some(now);
            self.playing = snapshot.playing;
        } else {
            let elapsed = now.duration_since(self.observed.unwrap()).as_secs_f64().min(3.0);
            let predicted = self.position + if self.playing { elapsed * 1_000.0 } else { 0.0 };
            let backwards_seek = raw < self.raw_position - 750;
            let forwards_seek = raw as f64 > predicted + 1_500.0;
            if backwards_seek || forwards_seek {
                self.position = raw as f64;
            } else if raw > self.raw_position && (raw as f64) >= predicted - 1_000.0 {
                self.position = predicted + (raw as f64 - predicted) * 0.3;
            } else {
                self.position = predicted;
            }
            self.raw_position = raw;
            self.observed = Some(now);
            self.playing = snapshot.playing;
        }
        if snapshot.duration_millis > 0 {
            self.position = self.position.min(snapshot.duration_millis as f64);
        }
        snapshot.position_millis = self.position.round().max(0.0) as i64;
        snapshot.observed_at = Utc::now();
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::time::Duration;

    fn song(raw: i64, playing: bool) -> PlaybackSnapshot {
        let mut value = PlaybackSnapshot::unavailable();
        value.title = "Track".into();
        value.position_millis = raw;
        value.duration_millis = 30_000;
        value.playing = playing;
        value
    }

    #[test]
    fn advances_through_repeated_windows_positions() {
        let mut tracker = TimelineTracker::default();
        let start = Instant::now();
        for index in 0..7 {
            let mut value = song(1_000, true);
            tracker.update(&mut value, start + Duration::from_millis(index * 500));
            if index == 6 { assert!((3_900..=4_100).contains(&value.position_millis)); }
        }
    }

    #[test]
    fn follows_seek_and_freezes_on_pause() {
        let mut tracker = TimelineTracker::default();
        let start = Instant::now();
        tracker.update(&mut song(8_000, true), start);
        let mut sought = song(2_000, true);
        tracker.update(&mut sought, start + Duration::from_millis(500));
        assert_eq!(sought.position_millis, 2_000);
        let mut paused = song(2_000, false);
        tracker.update(&mut paused, start + Duration::from_millis(1_000));
        let mut later = song(2_000, false);
        tracker.update(&mut later, start + Duration::from_millis(2_000));
        assert_eq!(later.position_millis, paused.position_millis);
    }
}
