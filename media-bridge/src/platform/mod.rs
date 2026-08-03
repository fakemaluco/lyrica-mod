use std::sync::atomic::{AtomicU32, Ordering};
use std::sync::Arc;

#[cfg(target_os = "linux")]
mod linux;
#[cfg(target_os = "macos")]
mod macos;
#[cfg(target_os = "windows")]
mod windows;

#[cfg(target_os = "linux")]
pub use linux::SystemMedia;
#[cfg(target_os = "macos")]
pub use macos::SystemMedia;
#[cfg(target_os = "windows")]
pub use windows::SystemMedia;

pub const BAND_COUNT: usize = 16;

/// Shared reactive-audio state produced by the platform capture thread.
/// All values are stored as atomics so the media bridge can read them from
/// its own thread without locks:
/// - intensity / beat_phase / centroid: scaled by 10_000 (0..1)
/// - bpm: stored as-is (60..200)
/// - bands: 16 log-spaced spectrum bands, each scaled by 10_000 (0..1)
#[derive(Clone)]
pub struct IntensityCapture {
    pub intensity: Arc<AtomicU32>,
    pub beat_phase: Arc<AtomicU32>,
    pub bpm: Arc<AtomicU32>,
    pub centroid: Arc<AtomicU32>,
    pub bands: Arc<[AtomicU32; BAND_COUNT]>,
}

impl IntensityCapture {
    /// Creates a capture state that is always zero (used when the platform
    /// has no audio capture, or before the capture thread reports anything).
    pub fn zeroed() -> Self {
        Self {
            intensity: Arc::new(AtomicU32::new(0)),
            beat_phase: Arc::new(AtomicU32::new(0)),
            bpm: Arc::new(AtomicU32::new(120)),
            centroid: Arc::new(AtomicU32::new(5000)),
            bands: Arc::new([const { AtomicU32::new(0) }; BAND_COUNT]),
        }
    }

    pub fn intensity(&self) -> f64 {
        self.intensity.load(Ordering::Relaxed) as f64 / 10_000.0
    }

    pub fn beat_phase(&self) -> f64 {
        self.beat_phase.load(Ordering::Relaxed) as f64 / 10_000.0
    }

    pub fn bpm(&self) -> f64 {
        self.bpm.load(Ordering::Relaxed) as f64
    }

    pub fn centroid(&self) -> f64 {
        self.centroid.load(Ordering::Relaxed) as f64 / 10_000.0
    }

    pub fn bands(&self) -> Vec<f64> {
        self.bands
            .iter()
            .map(|band| band.load(Ordering::Relaxed) as f64 / 10_000.0)
            .collect()
    }
}

/// Starts a background reactive-audio capture (intensity, beat, spectrum).
/// Windows uses WASAPI loopback on the default render endpoint; other platforms
/// currently have no capture and keep every value at zero.
pub fn start_intensity_capture() -> IntensityCapture {
    #[cfg(target_os = "windows")]
    {
        if let Some(capture) = windows::start_intensity_capture() {
            return capture;
        }
    }
    IntensityCapture::zeroed()
}
