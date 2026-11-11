use crate::{Event, PlaybackSnapshot};
use anyhow::{Context, Result};
use rand::Rng;
use serde::Deserialize;
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
        let requested = std::env::var("LYRICA_YOUTUBE_MUSIC_PORT")
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
    fs::write(&path, format!("{token}\n")).with_context(|| {
        format!(
            "could not persist YouTube Music token at {}",
            path.display()
        )
    })?;
    Ok(token)
}

fn token_path() -> Result<PathBuf> {
    if let Some(configured) = std::env::var_os("LYRICA_YOUTUBE_MUSIC_TOKEN_FILE") {
        return Ok(PathBuf::from(configured));
    }
    let executable = std::env::current_exe().context("could not locate media bridge executable")?;
    Ok(executable.with_file_name("lyrica-youtube-music.token"))
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
        json!({"type": "pairing_code", "code": pairing_code.clone()})
            .to_string()
            .into(),
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
    let mut connection = ConnectionGuard {
        events: events.clone(),
        active_clients: None,
    };
    let (outgoing, receiver) = mpsc::channel::<String>();
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
                    match value.get("type").and_then(Value::as_str) {
                        Some("client_hello") => {
                            if !serde_json::from_value::<ClientHello>(value)
                                .is_ok_and(|hello| hello.valid())
                            {
                                return;
                            }
                            if connection.active_clients.is_none() {
                                if let Ok(mut all) = clients.lock() {
                                    all.push(outgoing.clone());
                                }
                                if active_clients.fetch_add(1, Ordering::SeqCst) == 0 {
                                    let _ = events.send(Event::YoutubeStatus(true));
                                }
                                connection.active_clients = Some(active_clients.clone());
                            }
                        }
                        Some("snapshot") => {
                            if connection.active_clients.is_none() {
                                return;
                            }
                            if let Ok(snapshot) = serde_json::from_value::<PlaybackSnapshot>(
                                value["snapshot"].clone(),
                            ) {
                                let _ = events.send(Event::YoutubeSnapshot(snapshot));
                            }
                        }
                        _ => {}
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

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct ClientHello {
    client_kind: String,
    protocol_version: u32,
    extension_version: String,
    capabilities: Value,
}

impl ClientHello {
    fn valid(&self) -> bool {
        self.client_kind == "youtube_music"
            && (2..=16).contains(&self.protocol_version)
            && !self.extension_version.is_empty()
            && self.extension_version.len() <= 40
            && self.capabilities.is_object()
    }
}

struct ConnectionGuard {
    events: mpsc::Sender<Event>,
    active_clients: Option<Arc<AtomicUsize>>,
}

impl Drop for ConnectionGuard {
    fn drop(&mut self) {
        if let Some(clients) = &self.active_clients {
            if clients.fetch_sub(1, Ordering::SeqCst) == 1 {
                let _ = self.events.send(Event::YoutubeStatus(false));
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn only_youtube_music_can_announce_playback() {
        let mut hello = ClientHello {
            client_kind: "youtube_music".into(),
            protocol_version: 2,
            extension_version: "0.2.0".into(),
            capabilities: json!({}),
        };
        assert!(hello.valid());
        hello.client_kind.clear();
        assert!(!hello.valid());
        hello.client_kind = "spotify".into();
        assert!(!hello.valid());
    }
}
