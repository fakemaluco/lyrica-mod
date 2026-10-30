use crate::{MusicCapabilities, MusicCommand, PlaybackSnapshot};
use anyhow::Result;
use base64::{Engine as _, engine::general_purpose::STANDARD};
use chrono::Utc;
use std::sync::atomic::Ordering;
use std::thread;
use std::time::{Duration, Instant};

use super::IntensityCapture;
use crate::session_selection::{SessionCandidate, select_session};

use windows::Media::Control::{
    GlobalSystemMediaTransportControlsSession, GlobalSystemMediaTransportControlsSessionManager,
    GlobalSystemMediaTransportControlsSessionMediaProperties,
    GlobalSystemMediaTransportControlsSessionPlaybackStatus,
};
use windows::Storage::Streams::DataReader;
use windows::Win32::Media::Audio::{
    eConsole, eRender, AUDCLNT_BUFFERFLAGS_SILENT, AUDCLNT_SHAREMODE_SHARED,
    AUDCLNT_STREAMFLAGS_LOOPBACK, IAudioCaptureClient, IAudioClient, IMMDeviceEnumerator,
    MMDeviceEnumerator,
};

use windows::Win32::System::Com::{
    CLSCTX_ALL, COINIT_MULTITHREADED, CoCreateInstance, CoInitializeEx, CoTaskMemFree,
};

// Direct COM interface pointers obtained on an MTA are safe to call from the
// dedicated capture thread; the wrapper only restores `Send`.
struct SendCaptureClient(IAudioCaptureClient);
unsafe impl Send for SendCaptureClient {}

const FFT_SIZE: usize = 512;
const BAND_COUNT: usize = 16;
const POLL_MS: u64 = 50;

/// Spawns a WASAPI loopback capture thread that continuously measures the
/// loudness of everything playing on the default render endpoint (0..1).
/// Returns `None` when loopback capture cannot start.
pub fn start_intensity_capture() -> Option<IntensityCapture> {
    let shared = IntensityCapture::zeroed();
    spawn_loopback_capture(shared.clone())?;
    Some(shared)
}

fn spawn_loopback_capture(shared: IntensityCapture) -> Option<()> {
    unsafe {
        if CoInitializeEx(None, COINIT_MULTITHREADED).is_err() {
            return None;
        }
        let enumerator: IMMDeviceEnumerator =
            CoCreateInstance(&MMDeviceEnumerator, None, CLSCTX_ALL).ok()?;
        let device = enumerator.GetDefaultAudioEndpoint(eRender, eConsole).ok()?;
        let client: IAudioClient = device.Activate(CLSCTX_ALL, None).ok()?;
        let format = client.GetMixFormat().ok()?;
        let channels = (*format).nChannels.max(1) as usize;
        let block_align = (*format).nBlockAlign.max(1) as usize;
        client
            .Initialize(
                AUDCLNT_SHAREMODE_SHARED,
                AUDCLNT_STREAMFLAGS_LOOPBACK,
                200_000,
                0,
                format,
                None,
            )
            .ok()?;
        CoTaskMemFree(Some(format as *const core::ffi::c_void));
        let capture: IAudioCaptureClient = client.GetService().ok()?;
        client.Start().ok()?;
        let capture = SendCaptureClient(capture);
        thread::spawn(move || capture_loop(capture, shared, channels, block_align));
    }
    Some(())
}

/// In-place radix-2 iterative FFT (decimation in time).
fn fft(re: &mut [f32], im: &mut [f32]) {
    let n = re.len();
    let mut j = 0usize;
    for i in 1..n {
        let mut bit = n >> 1;
        while j & bit != 0 {
            j ^= bit;
            bit >>= 1;
        }
        j ^= bit;
        if i < j {
            re.swap(i, j);
            im.swap(i, j);
        }
    }
    let mut len = 2usize;
    while len <= n {
        let ang = -core::f32::consts::TAU / len as f32;
        let wlen_re = ang.cos();
        let wlen_im = ang.sin();
        let half = len >> 1;
        let mut i = 0usize;
        while i < n {
            let mut w_re = 1.0f32;
            let mut w_im = 0.0f32;
            for k in 0..half {
                let u_re = re[i + k];
                let u_im = im[i + k];
                let v_re = re[i + k + half] * w_re - im[i + k + half] * w_im;
                let v_im = re[i + k + half] * w_im + im[i + k + half] * w_re;
                re[i + k] = u_re + v_re;
                im[i + k] = u_im + v_im;
                re[i + k + half] = u_re - v_re;
                im[i + k + half] = u_im - v_im;
                let nw_re = w_re * wlen_re - w_im * wlen_im;
                let nw_im = w_re * wlen_im + w_im * wlen_re;
                w_re = nw_re;
                w_im = nw_im;
            }
            i += len;
        }
        len <<= 1;
    }
}

fn capture_loop(
    capture: SendCaptureClient,
    shared: IntensityCapture,
    channels: usize,
    block_align: usize,
) {
    let capture = capture.0;
    let silent_flag = AUDCLNT_BUFFERFLAGS_SILENT.0 as u32;
    let mut envelope = 0.0f64;
    let mut ring = vec![0.0f32; FFT_SIZE];
    let mut ring_head = 0usize;
    let mut energy_history: Vec<f64> = Vec::with_capacity(24);
    let mut onset_intervals: Vec<f64> = Vec::with_capacity(8);
    let mut last_onset = Instant::now() - Duration::from_millis(600);
    let mut prev_onset: Option<Instant> = None;
    let mut bpm_estimate = 120.0f64;
    let mut beat_phase = 1.0f64;
    let mut bands_smooth = [0.0f64; BAND_COUNT];
    let mut centroid_smooth = 0.5f64;
    while shared.is_active() {
        let mut sum = 0.0f64;
        let mut samples = 0usize;
        unsafe {
            loop {
                let Ok(packet) = capture.GetNextPacketSize() else { break };
                if packet == 0 {
                    break;
                }
                let mut data: *mut u8 = core::ptr::null_mut();
                let mut frames: u32 = 0;
                let mut flags: u32 = 0;
                if capture
                    .GetBuffer(&mut data, &mut frames, &mut flags, None, None)
                    .is_err()
                {
                    break;
                }
                if flags & silent_flag == 0 && !data.is_null() && frames > 0 {
                    let bytes_per_sample = block_align / channels;
                    for frame in 0..frames as usize {
                        let mut mono = 0.0f64;
                        for channel in 0..channels {
                            let offset = frame * block_align + channel * bytes_per_sample;
                            if bytes_per_sample >= 4 {
                                let bytes = [*data.add(offset), *data.add(offset + 1),
                                    *data.add(offset + 2), *data.add(offset + 3)];
                                mono += f32::from_bits(u32::from_le_bytes(bytes)) as f64;
                            } else if bytes_per_sample == 2 {
                                let bytes = [*data.add(offset), *data.add(offset + 1)];
                                mono += i16::from_le_bytes(bytes) as f64 / 32768.0;
                            }
                        }
                        mono /= channels as f64;
                        sum += mono * mono;
                        samples += 1;
                        ring[ring_head] = mono as f32;
                        ring_head = (ring_head + 1) % FFT_SIZE;
                    }
                }
                if capture.ReleaseBuffer(frames).is_err() {
                    break;
                }
            }
        }
        let rms = if samples > 0 { (sum / samples as f64).sqrt() } else { 0.0 };
        // Envelope follower: instant attack, exponential release, so the value
        // jumps on each beat and decays between them.
        if rms > envelope {
            envelope = rms;
        } else {
            envelope *= 0.82;
            if envelope < rms {
                envelope = rms;
            }
        }
        // Map the envelope (0..1 amplitude) to intensity, reserving the low
        // region for silence so quiet passages read near 0.
        let intensity = if envelope > 0.012 {
            ((envelope - 0.012) / 0.38).clamp(0.0, 1.0)
        } else {
            0.0
        };
        shared
            .intensity
            .store((intensity * 10_000.0) as u32, Ordering::Relaxed);

        // Beat onset detection on the short-term energy of this poll window.
        let energy = if samples > 0 { sum / samples as f64 } else { 0.0 };
        energy_history.push(energy);
        if energy_history.len() > 24 {
            energy_history.remove(0);
        }
        let avg_energy =
            energy_history.iter().sum::<f64>() / energy_history.len() as f64;
        let now = Instant::now();
        if energy > avg_energy * 1.4
            && energy > 4e-5
            && now.duration_since(last_onset).as_millis() > 200
        {
            last_onset = now;
            beat_phase = 0.0;
            if let Some(prev) = prev_onset.take() {
                let interval = now.duration_since(prev).as_secs_f64();
                if (0.28..1.5).contains(&interval) {
                    onset_intervals.push(60.0 / interval);
                    if onset_intervals.len() > 8 {
                        onset_intervals.remove(0);
                    }
                    let bpm =
                        onset_intervals.iter().sum::<f64>() / onset_intervals.len() as f64;
                    bpm_estimate = bpm.clamp(60.0, 200.0);
                }
            }
            prev_onset = Some(now);
        }
        // Beat phase ramps 0..1 between onsets, using the current BPM period.
        beat_phase += POLL_MS as f64 / (60_000.0 / bpm_estimate);
        if beat_phase > 1.0 {
            beat_phase = 1.0;
        }
        shared
            .beat_phase
            .store((beat_phase * 10_000.0) as u32, Ordering::Relaxed);
        shared
            .bpm
            .store(bpm_estimate.round() as u32, Ordering::Relaxed);

        // Spectral analysis on the last FFT_SIZE mono samples.
        if samples > 0 {
            let mut re: Vec<f32> = (0..FFT_SIZE)
                .map(|i| ring[(ring_head + i) % FFT_SIZE])
                .collect();
            let mut im = vec![0.0f32; FFT_SIZE];
            fft(&mut re, &mut im);
            let half = FFT_SIZE / 2;
            let mut mags = vec![0.0f32; half];
            for k in 0..half {
                mags[k] = (re[k] * re[k] + im[k] * im[k]).sqrt();
            }
            // 16 log-spaced bands over bins 1..half. Map bin magnitudes through
            // a -70..-10 dB scale so they read as 0..1.
            let mut sum_mag = 0.0f64;
            let mut sum_freq = 0.0f64;
            for band in 0..BAND_COUNT {
                let low = (((half as f64).powf(band as f64 / BAND_COUNT as f64))
                    .floor() as usize)
                    .clamp(1, half - 1);
                let high = (((half as f64).powf((band + 1) as f64 / BAND_COUNT as f64))
                    .ceil() as usize)
                    .clamp(low + 1, half);
                let mut mag_total = 0.0f64;
                for k in low..high {
                    let m = mags[k] as f64;
                    mag_total += m;
                    sum_mag += m;
                    sum_freq += k as f64 * m;
                }
                let db = 20.0 * (mag_total / (high - low) as f64).log10().max(-90.0);
                let band_01 = ((db + 70.0) / 60.0).clamp(0.0, 1.0);
                bands_smooth[band] += (band_01 - bands_smooth[band]) * 0.25;
                shared.bands[band].store(
                    (bands_smooth[band] * 10_000.0) as u32,
                    Ordering::Relaxed,
                );
            }
            let centroid_raw = if sum_mag > 1e-6 {
                (sum_freq / sum_mag) / half as f64
            } else {
                0.5
            };
            centroid_smooth += (centroid_raw - centroid_smooth) * 0.3;
            shared
                .centroid
                .store((centroid_smooth * 10_000.0) as u32, Ordering::Relaxed);
        } else {
            for band in 0..BAND_COUNT {
                shared.bands[band].store(0, Ordering::Relaxed);
            }
        }
        thread::sleep(Duration::from_millis(POLL_MS));
    }
}

const MAX_ARTWORK_BYTES: u64 = 8 * 1024 * 1024;

pub struct SystemMedia {
    manager: GlobalSystemMediaTransportControlsSessionManager,
    selected: Option<GlobalSystemMediaTransportControlsSession>,
    selected_source: String,
    artwork_key: String,
    artwork: Option<String>,
}

impl SystemMedia {
    pub fn new() -> Result<Self> {
        Ok(Self {
            manager: GlobalSystemMediaTransportControlsSessionManager::RequestAsync()?.get()?,
            selected: None,
            selected_source: String::new(),
            artwork_key: String::new(),
            artwork: None,
        })
    }

    fn session_for(&mut self, source: &str) -> Result<GlobalSystemMediaTransportControlsSession> {
        let current = self.manager.GetCurrentSession().ok();
        let mut available = Vec::new();
        let mut candidates = Vec::new();
        let sessions = self.manager.GetSessions()?;
        for index in 0..sessions.Size()? {
            let session = sessions.GetAt(index)?;
            let id = session.SourceAppUserModelId()?.to_string().to_lowercase();
            if source == "YOUTUBE_MUSIC" && !(id.contains("youtube") || id.contains("music.youtube")
                || id.contains("cinhimbnkkaeohfgghhklpknlkffjgod")) {
                continue;
            }
            let Ok(properties) = session.TryGetMediaPropertiesAsync().and_then(|value| value.get()) else { continue };
            let title = properties.Title().map(|value| value.to_string()).unwrap_or_default();
            let artist = properties.Artist().map(|value| value.to_string()).unwrap_or_default();
            let playing = session.GetPlaybackInfo().and_then(|value| value.PlaybackStatus())
                .is_ok_and(|status| status == GlobalSystemMediaTransportControlsSessionPlaybackStatus::Playing);
            candidates.push(SessionCandidate {
                playing,
                meaningful: !title.trim().is_empty(),
                music_metadata: !artist.trim().is_empty(),
                previous: self.selected.as_ref() == Some(&session),
                current: current.as_ref() == Some(&session),
            });
            available.push(session);
        }
        let index = select_session(&candidates).ok_or_else(|| anyhow::anyhow!("No active media session"))?;
        let session = available.swap_remove(index);
        self.selected = Some(session.clone());
        self.selected_source = source.to_owned();
        Ok(session)
    }

    fn artwork_data(
        media: &GlobalSystemMediaTransportControlsSessionMediaProperties,
    ) -> Option<String> {
        let thumbnail = media.Thumbnail().ok()?;
        let stream = thumbnail.OpenReadAsync().ok()?.get().ok()?;
        let size = stream.Size().ok()?;
        if size == 0 || size > MAX_ARTWORK_BYTES || size > u32::MAX as u64 {
            return None;
        }
        let input = stream.GetInputStreamAt(0).ok()?;
        let reader = DataReader::CreateDataReader(&input).ok()?;
        let loaded = reader.LoadAsync(size as u32).ok()?.get().ok()?;
        if loaded == 0 || loaded as u64 > MAX_ARTWORK_BYTES {
            let _ = reader.Close();
            return None;
        }
        let mut bytes = vec![0; loaded as usize];
        if reader.ReadBytes(&mut bytes).is_err() {
            let _ = reader.Close();
            return None;
        }
        let _ = reader.Close();
        let content_type = stream.ContentType().ok()?.to_string().to_lowercase();
        let mime = match content_type.as_str() {
            "image/png" => "image/png",
            "image/webp" => "image/webp",
            "image/gif" => "image/gif",
            _ => "image/jpeg",
        };
        Some(format!("data:{mime};base64,{}", STANDARD.encode(bytes)))
    }

    pub fn snapshot(&mut self) -> Result<PlaybackSnapshot> {
        self.snapshot_for("WINDOWS_MEDIA")
    }

    pub fn snapshot_for(&mut self, source: &str) -> Result<PlaybackSnapshot> {
        let session = self.session_for(source)?;
        let media = session.TryGetMediaPropertiesAsync()?.get()?;
        let timeline = session.GetTimelineProperties()?;
        let playback = session.GetPlaybackInfo()?;
        let controls = playback.Controls()?;
        let title = media.Title()?.to_string();
        let artist = media.Artist()?.to_string();
        let album = media.AlbumTitle()?.to_string();
        let app = session.SourceAppUserModelId()?.to_string();
        let playing = playback.PlaybackStatus()?
            == GlobalSystemMediaTransportControlsSessionPlaybackStatus::Playing;
        let now = Utc::now();
        let report_age_ms = timeline.LastUpdatedTime().map(|updated|
            now.timestamp_millis() - (updated.UniversalTime / 10_000 - 11_644_473_600_000))
            .unwrap_or(0);
        let rate = playback.PlaybackRate().ok().and_then(|value| value.Value().ok()).unwrap_or(1.0);
        let duration_millis = timeline.EndTime()?.Duration / 10_000;
        let position_millis = crate::timeline::project_position(timeline.Position()?.Duration / 10_000,
            duration_millis, playing, rate, report_age_ms);
        let artwork_key = format!("{app}\n{title}\n{artist}\n{album}");
        if self.artwork_key != artwork_key || self.artwork.is_none() {
            self.artwork = Self::artwork_data(&media);
            self.artwork_key = artwork_key;
        }
        Ok(PlaybackSnapshot {
            title,
            artist,
            album,
            artwork_url: self.artwork.clone(),
            spotify_uri: None,
            device_name: Some(app),
            position_millis,
            duration_millis,
            playing,
            volume: 0.0,
            shuffled: false,
            repeat_mode: "off".into(),
            liked: false,
            intensity: 0.0,
            beat_phase: 0.0,
            bpm: 0.0,
            spectral_centroid: 0.5,
            intensity_bands: Vec::new(),
            observed_at: now,
            capabilities: MusicCapabilities {
                play_pause: controls.IsPlayEnabled()? || controls.IsPauseEnabled()?,
                previous: controls.IsPreviousEnabled()?,
                next: controls.IsNextEnabled()?,
                seek: controls.IsPlaybackPositionEnabled()?,
                volume: false,
                shuffle: false,
                repeat: false,
                spotify_uri: false,
                device_name: true,
            },
        })
    }

    pub fn command(&mut self, command: &MusicCommand) -> Result<()> {
        self.command_for(command, "WINDOWS_MEDIA")
    }

    pub fn command_for(&mut self, command: &MusicCommand, source: &str) -> Result<()> {
        // A command controls the player whose snapshot the user last saw,
        // even if Windows moves its global transport focus in the meantime.
        let session = match &self.selected {
            Some(session) if self.selected_source == source => session.clone(),
            _ => self.session_for(source)?,
        };
        match command.action.as_str() {
            "PLAY_PAUSE" => {
                session.TryTogglePlayPauseAsync()?.get()?;
            }
            "PREVIOUS" => {
                session.TrySkipPreviousAsync()?.get()?;
            }
            "NEXT" => {
                session.TrySkipNextAsync()?.get()?;
            }
            "SEEK" => {
                session
                    .TryChangePlaybackPositionAsync(
                        (command.value.unwrap_or(0.0) * 10_000.0) as i64,
                    )?
                    .get()?;
            }
            _ => {}
        }
        Ok(())
    }
}
