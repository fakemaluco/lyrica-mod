use crate::platform::IntensityCapture;
use serde_json::json;
use std::sync::{Arc, RwLock, atomic::{AtomicBool, Ordering}};
use std::thread;
use std::time::Duration;

/// Publishes five existing meter values; this never opens a device or performs an FFT.
pub struct ReactiveAudioStream {
    source: RwLock<IntensityCapture>,
    enabled: AtomicBool,
    running: AtomicBool,
}

impl ReactiveAudioStream {
    pub fn start() -> Arc<Self> {
        let stream = Arc::new(Self {
            source: RwLock::new(IntensityCapture::zeroed()),
            enabled: AtomicBool::new(false),
            running: AtomicBool::new(true),
        });
        let worker = stream.clone();
        thread::spawn(move || {
            while worker.running.load(Ordering::Relaxed) {
                if worker.enabled.load(Ordering::Relaxed) {
                    let levels = worker.source.read().ok().map(|source| pooled_levels(&source));
                    if let Some(levels) = levels { crate::output(json!({"type": "audio_levels", "levels": levels})); }
                }
                thread::sleep(Duration::from_millis(50));
            }
        });
        stream
    }

    pub fn replace_source(&self, source: IntensityCapture) {
        if let Ok(mut current) = self.source.write() { *current = source; }
    }
    pub fn set_enabled(&self, enabled: bool) { self.enabled.store(enabled, Ordering::Relaxed); }
    pub fn stop(&self) { self.running.store(false, Ordering::Relaxed); }
}

/// Read the unsmoothed calibrated bands; visual attack/release happens only once in Minecraft.
fn pooled_levels(source: &IntensityCapture) -> [u32; 5] {
    std::array::from_fn(|bar| source.island_bands[bar].load(Ordering::Relaxed).min(10_000))
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn stream_uses_calibrated_meter_and_clamps_invalid_values() {
        let source=IntensityCapture::zeroed();
        for band in source.bands.iter() { band.store(10_000,Ordering::Relaxed); }
        assert_eq!(pooled_levels(&source),[0;5]);
        source.island_bands[2].store(3_200,Ordering::Relaxed);
        source.island_bands[4].store(u32::MAX,Ordering::Relaxed);
        assert_eq!(pooled_levels(&source),[0,0,3_200,0,10_000]);
    }
}
