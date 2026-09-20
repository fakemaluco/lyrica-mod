use crate::{
    Event, PlaybackSnapshot, SpicetifyClientHello, SpicetifyLyricsPayload, SpicetifyLyricsStatus,
};
use anyhow::{Context, Result};
use rand::Rng;
use serde_json::{Value, json};
use std::fs;
use std::net::{IpAddr, Ipv4Addr, SocketAddr, TcpListener};
use std::path::PathBuf;
use std::sync::{
    Arc, Mutex,
    atomic::{AtomicUsize, Ordering},
    mpsc,
};
use std::thread;
use tungstenite::{Message, accept};

pub struct Server {
    port: u16,
    pairing_code: String,
    clients: Arc<Mutex<Vec<mpsc::Sender<String>>>>,
}

impl Server {
    pub fn start(events: mpsc::Sender<Event>) -> Result<Self> {
        let requested = std::env::var("FANDROID_SPICETIFY_PORT")
            .ok()
            .and_then(|value| value.parse().ok())
            .unwrap_or(24_873);
        let listener =
            TcpListener::bind(SocketAddr::new(IpAddr::V4(Ipv4Addr::LOCALHOST), requested))?;
        let port = listener.local_addr()?.port();
        let pairing_code = format!("{:06}", rand::rng().random_range(0..1_000_000));
        let token = load_or_create_token()?;
        let clients = Arc::new(Mutex::new(Vec::new()));
        let active_clients = Arc::new(AtomicUsize::new(0));
        let accept_clients = clients.clone();
        let accept_code = pairing_code.clone();
        thread::spawn(move || {
            for stream in listener.incoming().flatten() {
                let events = events.clone();
                let code = accept_code.clone();
                let token = token.clone();
                let clients = accept_clients.clone();
                let active_clients = active_clients.clone();
                thread::spawn(move || handle(stream, events, code, token, clients, active_clients));
            }
        });
        Ok(Self {
            port,
            pairing_code,
            clients,
        })
    }

    pub fn port(&self) -> u16 {
        self.port
    }
    pub fn pairing_code(&self) -> &str {
        &self.pairing_code
    }

    pub fn broadcast(&self, message: &str) {
        if let Ok(mut clients) = self.clients.lock() {
            clients.retain(|client| client.send(message.to_owned()).is_ok());
        }
    }
}

fn load_or_create_token() -> Result<String> {
    let path = token_path()?;
    if let Ok(existing) = fs::read_to_string(&path) {
        let candidate = existing.trim();
        if candidate.len() == 32 && candidate.bytes().all(|value| value.is_ascii_hexdigit()) {
            return Ok(candidate.to_owned());
        }
    }

    let token = format!("{:032x}", rand::rng().random::<u128>());
    fs::write(&path, format!("{token}\n"))
        .with_context(|| format!("could not persist Spicetify token at {}", path.display()))?;
    Ok(token)
}

fn token_path() -> Result<PathBuf> {
    if let Some(configured) = std::env::var_os("FANDROID_SPICETIFY_TOKEN_FILE") {
        return Ok(PathBuf::from(configured));
    }
    let executable = std::env::current_exe().context("could not locate media bridge executable")?;
    Ok(executable.with_file_name("fandroid-spicetify.token"))
}

fn handle(
    stream: std::net::TcpStream,
    events: mpsc::Sender<Event>,
    pairing_code: String,
    token: String,
    clients: Arc<Mutex<Vec<mpsc::Sender<String>>>>,
    active_clients: Arc<AtomicUsize>,
) {
    let Ok(mut socket) = accept(stream) else {
        return;
    };
    // Announce the current pairing code so a trusted localhost extension can auto-pair
    // the first time, without the user typing a code into Minecraft. Clients that already
    // hold a token simply ignore this greeting and proceed to authenticate.
    let _ = socket.send(Message::Text(
        json!({"type": "pairing_code", "code": pairing_code.clone()}).to_string().into(),
    ));
    let Ok(Message::Text(first)) = socket.read() else {
        return;
    };
    let Ok(auth) = serde_json::from_str::<Value>(&first) else {
        return;
    };
    let request_type = auth.get("type").and_then(Value::as_str);
    let authenticated = request_type == Some("authenticate")
        && auth.get("token").and_then(Value::as_str) == Some(token.as_str());
    let paired = request_type == Some("pair")
        && auth.get("code").and_then(Value::as_str) == Some(pairing_code.as_str());
    if !authenticated && !paired {
        let error = if request_type == Some("pair") {
            "invalid_code"
        } else {
            "invalid_token"
        };
        let _ = socket.send(Message::Text(
            json!({"type": "error", "error": error}).to_string().into(),
        ));
        return;
    }
    if paired {
        let _ = socket.send(Message::Text(
            json!({"type": "paired", "token": token}).to_string().into(),
        ));
    } else {
        let _ = socket.send(Message::Text(json!({"type": "ready"}).to_string().into()));
    }
    let was_empty = active_clients.fetch_add(1, Ordering::SeqCst) == 0;
    if was_empty {
        let _ = events.send(Event::SpicetifyStatus(true));
    }
    let _connection = ConnectionGuard {
        events: events.clone(),
        active_clients,
    };
    let (outgoing, receiver) = mpsc::channel();
    if let Ok(mut all) = clients.lock() {
        all.push(outgoing);
    }
    let _ = socket.get_mut().set_nonblocking(true);
    loop {
        while let Ok(message) = receiver.try_recv() {
            if socket.send(Message::Text(message.into())).is_err() {
                return;
            }
        }
        match socket.read() {
            Ok(Message::Text(text)) => {
                if text.len() > 10 * 1024 * 1024 {
                    return;
                }
                if let Ok(value) = serde_json::from_str::<Value>(&text) {
                    if value.get("type").and_then(Value::as_str) == Some("snapshot") {
                        if let Ok(snapshot) =
                            serde_json::from_value::<PlaybackSnapshot>(value["snapshot"].clone())
                        {
                            let _ = events.send(Event::SpicetifySnapshot(snapshot));
                        }
                    } else if value.get("type").and_then(Value::as_str) == Some("client_hello") {
                        if let Ok(hello) =
                            serde_json::from_value::<SpicetifyClientHello>(value.clone())
                        {
                            if valid_hello(&hello) {
                                let _ = events.send(Event::SpicetifyClientHello(hello));
                            }
                        }
                    } else if value.get("type").and_then(Value::as_str) == Some("lyrics_status") {
                        if let Ok(status) =
                            serde_json::from_value::<SpicetifyLyricsStatus>(value.clone())
                        {
                            if valid_lyrics_status(&status) {
                                let _ = events.send(Event::SpicetifyLyricsStatus(status));
                            }
                        }
                    } else if value.get("type").and_then(Value::as_str) == Some("lyrics_payload") {
                        let uri = value.get("uri").and_then(Value::as_str).unwrap_or("");
                        let format = value.get("format").and_then(Value::as_str).unwrap_or("");
                        let payload = value.get("payload").and_then(Value::as_str).unwrap_or("");
                        let source = value
                            .get("source")
                            .and_then(Value::as_str)
                            .unwrap_or("Spicy Lyrics");
                        let songwriters = value
                            .get("songwriters")
                            .and_then(Value::as_array)
                            .map(|names| {
                                names
                                    .iter()
                                    .filter_map(Value::as_str)
                                    .filter(|name| !name.trim().is_empty() && name.len() <= 160)
                                    .take(32)
                                    .map(|name| name.trim().to_owned())
                                    .collect::<Vec<_>>()
                            })
                            .unwrap_or_default();
                        let short_text = |field: &str, maximum: usize| {
                            value
                                .get(field)
                                .and_then(Value::as_str)
                                .unwrap_or("")
                                .trim()
                                .chars()
                                .take(maximum)
                                .collect::<String>()
                        };
                        let provider_code = short_text("providerCode", 24);
                        let community_provided = value
                            .get("communityProvided")
                            .and_then(Value::as_bool)
                            .unwrap_or(false);
                        let maker_username = short_text("makerUsername", 160);
                        let maker_avatar = short_text("makerAvatar", 2_048);
                        let uploader_username = short_text("uploaderUsername", 160);
                        let uploader_avatar = short_text("uploaderAvatar", 2_048);
                        let max_payload = if format == "ttml" {
                            4 * 1024 * 1024
                        } else {
                            2 * 1024 * 1024
                        };
                        if uri.starts_with("spotify:track:")
                            && matches!(format, "ttml" | "spicy-json")
                            && payload.len() <= max_payload
                            && source.len() <= 80
                        {
                            let _ = events.send(Event::SpicetifyLyrics(SpicetifyLyricsPayload {
                                uri: uri.to_owned(),
                                format: format.to_owned(),
                                payload: payload.to_owned(),
                                source: source.to_owned(),
                                songwriters,
                                provider_code,
                                community_provided,
                                maker_username,
                                maker_avatar,
                                uploader_username,
                                uploader_avatar,
                            }));
                        }
                    }
                }
            }
            Ok(Message::Close(_)) => return,
            Err(tungstenite::Error::Io(error))
                if error.kind() == std::io::ErrorKind::WouldBlock =>
            {
                thread::sleep(std::time::Duration::from_millis(25));
            }
            Err(_) => return,
            _ => {}
        }
    }
}

fn valid_hello(hello: &SpicetifyClientHello) -> bool {
    hello.protocol_version >= 2
        && hello.protocol_version <= 16
        && !hello.extension_version.is_empty()
        && hello.extension_version.len() <= 40
        && hello.capabilities.is_object()
}

fn valid_lyrics_status(status: &SpicetifyLyricsStatus) -> bool {
    let known = matches!(
        status.status.as_str(),
        "HIT_TTML" | "HIT_CACHE" | "MISS" | "ERROR"
    );
    status.uri.starts_with("spotify:track:")
        && status.uri.len() <= 96
        && known
        && status
            .detail
            .as_ref()
            .is_none_or(|detail| detail.len() <= 240)
}

struct ConnectionGuard {
    events: mpsc::Sender<Event>,
    active_clients: Arc<AtomicUsize>,
}

impl Drop for ConnectionGuard {
    fn drop(&mut self) {
        if self.active_clients.fetch_sub(1, Ordering::SeqCst) == 1 {
            let _ = self.events.send(Event::SpicetifyStatus(false));
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn validates_protocol_v2_hello_and_lyrics_status() {
        let hello = SpicetifyClientHello {
            protocol_version: 2,
            extension_version: "0.2.0".into(),
            capabilities: json!({"artwork": true, "spicyCache": true, "rawTtml": true}),
        };
        assert!(valid_hello(&hello));
        assert!(valid_lyrics_status(&SpicetifyLyricsStatus {
            uri: "spotify:track:6wOYnPq1hPihRepcDClPUw".into(),
            status: "HIT_CACHE".into(),
            detail: Some("SpicyLyrics_LyricsStore_g1".into()),
        }));
        assert!(!valid_lyrics_status(&SpicetifyLyricsStatus {
            uri: "https://example.invalid".into(),
            status: "HIT_CACHE".into(),
            detail: None,
        }));
    }
}
