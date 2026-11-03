use std::time::Instant;

use chrono::Utc;

use crate::PlaybackSnapshot;

/// Positions in Windows media sessions are anchored to LastUpdatedTime, not
/// necessarily to the instant the bridge reads them. Account for that initial
/// age before the monotonic tracker handles repeated reports.
pub fn project_position(position: i64, duration: i64, playing: bool, rate: f64, age_ms: i64) -> i64 {
    let projected = if playing && (0..=300_000).contains(&age_ms) && rate.is_finite() && rate > 0.0 {
        position.saturating_add((age_ms as f64 * rate) as i64)
    } else { position };
    if duration > 0 { projected.clamp(0, duration) } else { projected.max(0) }
}

/** Keeps lyric time moving when Windows or a background browser repeats a stale position. */
#[derive(Default)]
pub struct TimelineTracker {
    track: String,
    raw_position: i64,
    position: f64,
    observed: Option<Instant>,
    playing: bool,
    sample_time: Option<chrono::DateTime<Utc>>,
}

impl TimelineTracker {
    pub fn update(&mut self, snapshot: &mut PlaybackSnapshot, now: Instant) {
        self.update_at(snapshot, now, Utc::now());
    }

    fn update_at(&mut self, snapshot: &mut PlaybackSnapshot, now: Instant, wall_now: chrono::DateTime<Utc>) {
        if !snapshot.is_meaningful() {
            *self = Self::default();
            return;
        }
        let track = format!("{}\n{}\n{}\n{}", snapshot.spotify_uri.as_deref().unwrap_or(""),
            snapshot.title, snapshot.artist, snapshot.duration_millis);
        let raw = snapshot.position_millis.max(0);
        let sample_time = snapshot.observed_at;
        let age_ms = (wall_now - sample_time).num_milliseconds().clamp(0, 2_000);
        let observed = raw as f64 + if snapshot.playing { age_ms as f64 } else { 0.0 };
        if self.observed.is_none() || self.track != track {
            self.track = track;
            self.raw_position = raw;
            self.position = observed;
            self.observed = Some(now);
            self.playing = snapshot.playing;
        } else {
            let elapsed = now.duration_since(self.observed.unwrap()).as_secs_f64();
            let predicted = self.position + if self.playing { elapsed * 1_000.0 } else { 0.0 };
            let fresh = self.sample_time.is_none_or(|previous| sample_time >= previous);
            let state_changed = snapshot.playing != self.playing;
            if fresh && (state_changed || raw != self.raw_position) {
                let error = observed - predicted;
                // Correct in BOTH directions. Previously a new sample over a second
                // behind our prediction was ignored forever, leaving lyrics ahead.
                self.position = if state_changed || !snapshot.playing || error.abs() >= 750.0 {
                    observed
                } else {
                    predicted + error * (1.0 - (-elapsed / 0.12).exp())
                };
            } else {
                self.position = predicted;
            }
            self.raw_position = raw;
            self.observed = Some(now);
            self.playing = snapshot.playing;
        }
        self.sample_time = Some(self.sample_time.map_or(sample_time, |previous| previous.max(sample_time)));
        if snapshot.duration_millis > 0 {
            self.position = self.position.min(snapshot.duration_millis as f64);
        }
        snapshot.position_millis = self.position.round().max(0.0) as i64;
        snapshot.observed_at = wall_now;
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::time::Duration;

    #[test]
    fn projects_initial_os_position_from_report_timestamp_only_when_playing() {
        assert_eq!(project_position(10_000, 120_000, true, 1.0, 3_000), 13_000);
        assert_eq!(project_position(10_000, 120_000, false, 1.0, 3_000), 10_000);
        assert_eq!(project_position(119_000, 120_000, true, 1.0, 3_000), 120_000);
        assert_eq!(project_position(10_000, 120_000, true, 1.0, -1), 10_000);
        assert_eq!(project_position(10_000, 120_000, true, 1.0, 86_400_000), 10_000);
    }

    fn song(raw: i64, playing: bool) -> PlaybackSnapshot {
        let mut value = PlaybackSnapshot::unavailable();
        value.title = "Track".into();
        value.position_millis = raw;
        value.duration_millis = 30_000;
        value.playing = playing;
        value
    }

    #[test]
    fn catches_up_to_new_reports_that_are_over_a_second_behind() {
        let mut tracker = TimelineTracker::default();
        let start = Instant::now();
        let wall = Utc::now();
        let mut first = song(10_000, true);
        first.observed_at = wall;
        tracker.update_at(&mut first, start, wall);
        let mut corrected = song(10_100, true);
        corrected.observed_at = wall + chrono::Duration::milliseconds(2_000);
        tracker.update_at(&mut corrected, start + Duration::from_secs(2), wall + chrono::Duration::milliseconds(2_000));
        assert_eq!(corrected.position_millis, 10_100);
    }

    #[test]
    fn pause_uses_the_reported_position_even_when_the_last_raw_value_repeats() {
        let mut tracker = TimelineTracker::default();
        let start = Instant::now();
        tracker.update(&mut song(8_000, true), start);
        let mut paused = song(8_000, false);
        tracker.update(&mut paused, start + Duration::from_secs(2));
        assert_eq!(paused.position_millis, 8_000);
    }

    #[test]
    fn elapsed_time_is_not_lost_during_a_bridge_stall() {
        let mut tracker = TimelineTracker::default();
        let start = Instant::now();
        let mut first = song(1_000, true);
        tracker.update(&mut first, start);
        let mut repeated = song(1_000, true);
        tracker.update(&mut repeated, start + Duration::from_secs(8));
        assert!((8_990..=9_010).contains(&repeated.position_millis));
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
