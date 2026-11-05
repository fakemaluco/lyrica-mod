use super::{Command, Request};
use std::collections::{HashMap, HashSet};
use std::path::Path;
use std::sync::mpsc::{Receiver, RecvTimeoutError};
use std::time::{Duration, Instant};
use windows::Win32::Foundation::CloseHandle;
use windows::Win32::Media::Audio::{
    DEVICE_STATE_ACTIVE, IAudioSessionControl2, IAudioSessionManager2, IMMDeviceEnumerator,
    ISimpleAudioVolume, MMDeviceEnumerator, eRender,
};
use windows::Win32::System::Com::{
    CLSCTX_ALL, COINIT_MULTITHREADED, CoCreateInstance, CoInitializeEx, CoTaskMemFree,
    CoUninitialize,
};
use windows::Win32::System::Threading::{
    OpenProcess, PROCESS_NAME_WIN32, PROCESS_QUERY_LIMITED_INFORMATION, QueryFullProcessImageNameW,
};
use windows::core::{Interface, PWSTR};

const STEP: Duration = Duration::from_millis(45);
const SCAN: Duration = Duration::from_millis(180);
const WATCHDOG: Duration = Duration::from_secs(2);

pub(super) fn run(receiver: Receiver<Command>) {
    let mut mixer = None;
    let mut request = None;
    let mut renewed = Instant::now();
    loop {
        let wait =
            if request.is_some() || mixer.as_ref().is_some_and(|m: &Mixer| !m.leases.is_empty()) {
                STEP
            } else {
                Duration::from_secs(60)
            };
        match receiver.recv_timeout(wait) {
            Ok(Command::Update(next)) => {
                if next.is_some() && mixer.is_none() {
                    mixer = Mixer::open();
                }
                if request != next {
                    if let Some(mixer) = &mut mixer {
                        mixer.next_scan = Instant::now();
                    }
                    request = next;
                }
                renewed = Instant::now();
            }
            Ok(Command::Shutdown) | Err(RecvTimeoutError::Disconnected) => break,
            Err(RecvTimeoutError::Timeout) => {}
        }
        if renewed.elapsed() >= WATCHDOG {
            request = None;
        }
        if let Some(mixer) = &mut mixer {
            mixer.update(request.as_ref());
        }
    }
}

struct Mixer {
    devices: Option<IMMDeviceEnumerator>,
    leases: HashMap<String, Lease>,
    wanted: HashSet<String>,
    next_scan: Instant,
}

impl Mixer {
    fn open() -> Option<Self> {
        unsafe {
            CoInitializeEx(None, COINIT_MULTITHREADED).ok().ok()?;
            let devices = CoCreateInstance(&MMDeviceEnumerator, None, CLSCTX_ALL).ok();
            Some(Self {
                devices,
                leases: HashMap::new(),
                wanted: HashSet::new(),
                next_scan: Instant::now(),
            })
        }
    }

    fn update(&mut self, request: Option<&Request>) {
        if let Some(request) = request {
            if Instant::now() >= self.next_scan {
                self.scan(request);
                self.next_scan = Instant::now() + SCAN;
            }
        } else {
            self.wanted.clear();
        }
        self.leases.retain(|id, lease| {
            let target = request
                .filter(|_| self.wanted.contains(id))
                .map_or(1.0, |r| r.factor.clamp(0.0, 1.0));
            lease.step(target).unwrap_or(false)
        });
    }

    fn scan(&mut self, request: &Request) {
        self.wanted.clear();
        let Some(devices) = &self.devices else { return };
        let Ok(endpoints) = (unsafe { devices.EnumAudioEndpoints(eRender, DEVICE_STATE_ACTIVE) })
        else {
            return;
        };
        let mut candidates = Vec::new();
        let mut process_names = HashMap::new();
        for index in 0..unsafe { endpoints.GetCount() }.unwrap_or(0) {
            let endpoint = unsafe { endpoints.Item(index) };
            let Ok(manager) = endpoint
                .and_then(|e| unsafe { e.Activate::<IAudioSessionManager2>(CLSCTX_ALL, None) })
            else {
                continue;
            };
            let Ok(sessions) = (unsafe { manager.GetSessionEnumerator() }) else {
                continue;
            };
            for index in 0..unsafe { sessions.GetCount() }.unwrap_or(0) {
                let Ok(session) = (unsafe { sessions.GetSession(index) }) else {
                    continue;
                };
                let Ok(control) = session.cast::<IAudioSessionControl2>() else {
                    continue;
                };
                let Ok(pid) = (unsafe { control.GetProcessId() }) else {
                    continue;
                };
                if pid == 0 || pid == request.game_pid || pid == std::process::id() {
                    continue;
                }
                let Some(app) = process_names
                    .entry(pid)
                    .or_insert_with(|| process_name(pid))
                else {
                    continue;
                };
                if let (Some(id), Ok(volume)) =
                    (session_id(&control), session.cast::<ISimpleAudioVolume>())
                {
                    candidates.push((id, app.clone(), volume));
                }
            }
        }
        let foreground = request.foreground.as_deref().map(canonical_app);
        // Unknown player identities never cause their own audio to be reduced.
        if let Some(foreground) = &foreground {
            if !candidates.iter().any(|(_, app, _)| app == foreground) {
                return;
            }
        }
        let target = canonical_app(&request.target);
        for (id, app, volume) in candidates {
            if foreground.as_ref() == Some(&app) || (target != "*" && target != app) {
                continue;
            }
            if !self.leases.contains_key(&id) {
                if let Ok(base) = unsafe { volume.GetMasterVolume() } {
                    self.leases.insert(
                        id.clone(),
                        Lease {
                            volume,
                            base,
                            written: base,
                            gain: 1.0,
                        },
                    );
                }
            }
            self.wanted.insert(id);
        }
    }
}

impl Drop for Mixer {
    fn drop(&mut self) {
        self.leases.clear();
        self.devices.take();
        unsafe { CoUninitialize() };
    }
}

struct Lease {
    volume: ISimpleAudioVolume,
    base: f32,
    written: f32,
    gain: f32,
}

impl Lease {
    fn step(&mut self, target: f32) -> windows::core::Result<bool> {
        let observed = unsafe { self.volume.GetMasterVolume()? };
        if (observed - self.written).abs() > 0.001 {
            // Another mixer or the user changed the volume since our last write.
            if target == 1.0 {
                return Ok(false);
            }
            self.base = observed;
            self.written = observed;
        }
        self.gain += (target - self.gain) * if target < self.gain { 0.60 } else { 0.38 };
        if (target - self.gain).abs() < 0.008 {
            self.gain = target;
        }
        let next = self.base * self.gain;
        if (next - self.written).abs() > 0.001 || (self.gain == 1.0 && next != self.written) {
            unsafe { self.volume.SetMasterVolume(next, std::ptr::null())? };
            self.written = next;
        }
        Ok(target < 1.0 || self.gain < 1.0)
    }
}

impl Drop for Lease {
    fn drop(&mut self) {
        if self.base == self.written {
            return;
        }
        if let Ok(current) = unsafe { self.volume.GetMasterVolume() } {
            if (current - self.written).abs() <= 0.001 {
                let _ = unsafe { self.volume.SetMasterVolume(self.base, std::ptr::null()) };
            }
        }
    }
}

fn session_id(control: &IAudioSessionControl2) -> Option<String> {
    unsafe {
        let raw = control.GetSessionInstanceIdentifier().ok()?;
        let id = raw.to_string().ok();
        CoTaskMemFree(Some(raw.0.cast()));
        id
    }
}

fn process_name(pid: u32) -> Option<String> {
    unsafe {
        let handle = OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, false, pid).ok()?;
        let mut path = vec![0u16; 32768];
        let mut length = path.len() as u32;
        let result = QueryFullProcessImageNameW(
            handle,
            PROCESS_NAME_WIN32,
            PWSTR(path.as_mut_ptr()),
            &mut length,
        );
        let _ = CloseHandle(handle);
        result.ok()?;
        let path = String::from_utf16_lossy(&path[..length as usize]);
        Some(canonical_app(Path::new(&path).file_name()?.to_str()?))
    }
}

fn canonical_app(name: &str) -> String {
    let lower = name.trim().to_ascii_lowercase();
    if lower.starts_with("spotifyab.spotify") || lower == "spotify music" {
        return "spotify".into();
    }
    let name = lower.rsplit(['/', '\\']).next().unwrap_or(&lower);
    name.strip_suffix(".exe").unwrap_or(name).to_owned()
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::Write;
    use std::process::{Command as ProcessCommand, Stdio};
    use windows::Win32::Media::Audio::{AUDCLNT_SHAREMODE_SHARED, IAudioClient, eConsole};
    use windows::core::GUID;

    #[test]
    fn normalizes_spotify_and_executable_names() {
        assert_eq!(
            canonical_app("SpotifyAB.SpotifyMusic_zpdnekdrzrea0!Spotify"),
            "spotify"
        );
        assert_eq!(
            canonical_app(r"C:\Program Files\Browser\CHROME.EXE"),
            "chrome"
        );
        assert_eq!(canonical_app("*"), "*");
    }

    #[test]
    #[ignore = "requires a Windows audio endpoint and a built release bridge"]
    fn bridge_reduces_and_restores_an_isolated_wasapi_session() -> anyhow::Result<()> {
        let mixer = Mixer::open().ok_or_else(|| anyhow::anyhow!("COM unavailable"))?;
        let devices = mixer
            .devices
            .as_ref()
            .ok_or_else(|| anyhow::anyhow!("No audio mixer"))?;
        let endpoint = unsafe { devices.GetDefaultAudioEndpoint(eRender, eConsole)? };
        let client = unsafe { endpoint.Activate::<IAudioClient>(CLSCTX_ALL, None)? };
        let format = unsafe { client.GetMixFormat()? };
        let session =
            GUID::from_u128(0xa1dd_1454_1234_4321_8000_0000_0000_0000 | std::process::id() as u128);
        let initialized =
            unsafe { client.Initialize(AUDCLNT_SHAREMODE_SHARED, 0, 0, 0, format, Some(&session)) };
        unsafe { CoTaskMemFree(Some(format.cast())) };
        initialized?;
        let volume = unsafe { client.GetService::<ISimpleAudioVolume>()? };
        unsafe { volume.SetMasterVolume(0.8, std::ptr::null())? };
        let executable = std::env::current_exe()?;
        let target = executable.file_name().unwrap().to_string_lossy();
        let bridge =
            Path::new(env!("CARGO_MANIFEST_DIR")).join("target/release/fandroid-media-bridge.exe");
        let mut process = ProcessCommand::new(bridge)
            .env("FANDROID_SPICETIFY_PORT", "0")
            .stdin(Stdio::piped())
            .stdout(Stdio::null())
            .stderr(Stdio::inherit())
            .spawn()?;
        let mut input = process.stdin.take().unwrap();
        let result = (|| -> anyhow::Result<()> {
            let request = serde_json::json!({"type": "ducking", "request": {
                "target": target, "factor": 0.25, "gamePid": 0, "foreground": null
            }});
            writeln!(input, "{request}")?;
            wait_for_volume(&volume, 0.2)?;
            writeln!(
                input,
                "{}",
                serde_json::json!({"type": "ducking", "request": null})
            )?;
            wait_for_volume(&volume, 0.8)?;

            writeln!(input, "{request}")?;
            wait_for_volume(&volume, 0.2)?;
            wait_for_volume(&volume, 0.8)?; // Request expires without a heartbeat.
            writeln!(input, "{request}")?;
            wait_for_volume(&volume, 0.2)
        })();
        drop(input);
        assert!(process.wait()?.success());
        result?;
        wait_for_volume(&volume, 0.8)
    }

    fn wait_for_volume(volume: &ISimpleAudioVolume, target: f32) -> anyhow::Result<()> {
        let start = Instant::now();
        loop {
            let current = unsafe { volume.GetMasterVolume()? };
            if (current - target).abs() < 0.008 {
                return Ok(());
            }
            if start.elapsed() >= Duration::from_secs(4) {
                anyhow::bail!("Expected {target}, got {current}");
            }
            std::thread::sleep(Duration::from_millis(30));
        }
    }
}
