//! Persistent state, encrypted at rest. Access tokens are NEVER in here.

use crate::crypto::Vault;
use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use std::io::Write;
use std::path::PathBuf;
use std::sync::Mutex;

#[derive(Serialize, Deserialize, Clone, Debug, PartialEq)]
pub struct GuildInfo {
    pub id: String,
    pub name: String,
    pub slug: String,
}

#[derive(Serialize, Deserialize, Clone, Debug)]
pub struct Reg {
    pub fcm_token: String,
    pub user_id: String,
    pub username: String,
    pub guilds: Vec<GuildInfo>,
    /// Secret handed to the phone at Connect; rotation and unregister must present it.
    /// Empty = registered before device keys existed: that phone must run Connect again.
    #[serde(default)]
    pub device_key: String,
}

/// The next raid as /raid shows it, plus which pushes already went out for it.
#[derive(Serialize, Deserialize, Clone, Debug)]
pub struct RaidRec {
    pub id: String,
    pub title: String,
    pub starts_at: i64,
    pub signup_at: i64,
    pub sent_signup: bool,
    pub sent_ready: bool,
    /// absent when /raid shows no boss art (or in records from before boss faces)
    #[serde(default)]
    pub boss: Option<Boss>,
}

/// The boss portrait as KD draws it: one frame of a sprite sheet, shifted and hue-rotated by CSS.
#[derive(Serialize, Deserialize, Clone, Debug, PartialEq)]
pub struct Boss {
    pub name: String,
    pub note: String,
    pub sprite: String,
    pub sheet_w: u32,
    pub frame: u32,
    pub x: u32,
    pub hue: i32,
}

#[derive(Serialize, Deserialize, Default, Clone)]
pub struct State {
    pub regs: Vec<Reg>,
    /// user_id -> refresh_token
    pub refresh: HashMap<String, String>,
    /// guild_id -> created_at of the newest row handled
    pub last_seen: HashMap<String, String>,
    /// absent in state files from before raid alerts
    #[serde(default)]
    pub raid: Option<RaidRec>,
}

pub struct Store {
    vault: Vault,
    path: PathBuf,
    state: Mutex<State>,
    write_lock: Mutex<()>,
}

impl Store {
    pub fn open(dir: &PathBuf, vault: Vault) -> Result<Store, String> {
        let path = dir.join("state.kdp");
        let state = match std::fs::read(&path) {
            Ok(blob) => {
                let plain = vault.open(&blob).map_err(|e| format!("{}: {e}", path.display()))?;
                serde_json::from_slice(&plain).map_err(|e| format!("{}: bad state json: {e}", path.display()))?
            }
            Err(e) if e.kind() == std::io::ErrorKind::NotFound => {
                info!("no state file at {}, first boot, starting empty", path.display());
                State::default()
            }
            Err(e) => return Err(format!("read {}: {e}", path.display())),
        };
        Ok(Store { vault, path, state: Mutex::new(state), write_lock: Mutex::new(()) })
    }

    pub fn with<R>(&self, f: impl FnOnce(&mut State) -> R) -> R {
        f(&mut self.state.lock().unwrap())
    }

    /// Atomic: write temp (0600) in the same dir, fsync, rename over.
    pub fn save(&self) -> Result<(), String> {
        let _w = self.write_lock.lock().unwrap();
        let snapshot = self.state.lock().unwrap().clone();
        let plain = serde_json::to_vec(&snapshot).map_err(|e| format!("serialize state: {e}"))?;
        let blob = self.vault.seal(&plain)?;
        let tmp = self.path.with_extension("kdp.tmp");
        let write = || -> std::io::Result<()> {
            use std::os::unix::fs::OpenOptionsExt;
            let mut f = std::fs::OpenOptions::new().write(true).create(true).truncate(true).mode(0o600).open(&tmp)?;
            f.write_all(&blob)?;
            f.sync_all()?;
            std::fs::rename(&tmp, &self.path)
        };
        write().map_err(|e| format!("write {}: {e}", self.path.display()))
    }
}
