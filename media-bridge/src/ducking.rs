use serde::Deserialize;
use std::sync::mpsc;

#[derive(Debug, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct Request {
    pub target: String,
    pub factor: f32,
    pub game_pid: u32,
    // Some only for music-triggered ducking. Protect the foreground player.
    pub foreground: Option<String>,
}

pub enum Command {
    Update(Option<Request>),
    Shutdown,
}

pub struct Controller {
    pub sender: mpsc::Sender<Command>,
    worker: std::thread::JoinHandle<()>,
}

impl Controller {
    pub fn start() -> Self {
        let (sender, receiver) = mpsc::channel();
        #[cfg(target_os = "windows")]
        let worker = std::thread::spawn(move || wasapi::run(receiver));
        #[cfg(not(target_os = "windows"))]
        let worker = std::thread::spawn(
            move || {
                while let Ok(Command::Update(_)) = receiver.recv() {}
            },
        );
        Self { sender, worker }
    }

    pub fn stop(self) {
        let _ = self.sender.send(Command::Shutdown);
        let _ = self.worker.join();
    }
}

#[cfg(target_os = "windows")]
mod wasapi;
