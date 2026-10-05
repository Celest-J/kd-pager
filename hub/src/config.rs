use std::net::SocketAddr;
use std::path::PathBuf;

pub enum KeySourceCfg {
    /// Production: 64-hex-char secret in GCP Secret Manager. Value is the full resource name.
    GcpSecret { secret: String },
    /// LOCAL DEV STUB: 64-hex-char key straight from the environment. Never use in prod.
    EnvDev { hex: String },
}

pub struct Config {
    pub sb_url: String,
    pub anon_key: String,
    pub fcm_project: String,
    pub listen: SocketAddr,
    pub data_dir: PathBuf,
    pub key_source: KeySourceCfg,
    // Fixed production endpoints. Env override exists only so a local mock can stand in.
    pub site_url: String,
    pub fcm_url: String,
    pub metadata_url: String,
    pub secretmanager_url: String,
}

fn req(name: &str) -> Result<String, String> {
    match std::env::var(name) {
        Ok(v) if !v.trim().is_empty() => Ok(v.trim().to_string()),
        _ => Err(format!("missing required env {name}")),
    }
}

/// Optional override of a fixed production endpoint (documented in README).
fn endpoint(name: &str, prod: &str) -> String {
    match std::env::var(name) {
        Ok(v) if !v.trim().is_empty() => v.trim().trim_end_matches('/').to_string(),
        _ => prod.to_string(),
    }
}

impl Config {
    pub fn from_env() -> Result<Config, String> {
        let sb_url = req("KDPAGER_SB_URL")?.trim_end_matches('/').to_string();
        if !(sb_url.starts_with("https://") || sb_url.starts_with("http://")) {
            return Err(format!("KDPAGER_SB_URL must start with https:// or http://, got {sb_url}"));
        }
        let listen_s = req("KDPAGER_LISTEN")?;
        let listen: SocketAddr = listen_s
            .parse()
            .map_err(|e| format!("KDPAGER_LISTEN={listen_s} is not host:port: {e}"))?;
        let key_source = match req("KDPAGER_KEY_SOURCE")?.as_str() {
            "gcp-secret" => KeySourceCfg::GcpSecret { secret: req("KDPAGER_KEY_SECRET")? },
            "env-dev" => KeySourceCfg::EnvDev { hex: req("KDPAGER_DEV_KEY_HEX")? },
            other => return Err(format!("KDPAGER_KEY_SOURCE must be gcp-secret or env-dev, got {other}")),
        };
        Ok(Config {
            sb_url,
            anon_key: req("KDPAGER_ANON_KEY")?,
            fcm_project: req("KDPAGER_FCM_PROJECT")?,
            listen,
            data_dir: PathBuf::from(req("KDPAGER_DATA_DIR")?),
            key_source,
            site_url: endpoint("KDPAGER_SITE_URL", "https://krackeddevs.com"),
            fcm_url: endpoint("KDPAGER_FCM_URL", "https://fcm.googleapis.com"),
            metadata_url: endpoint("KDPAGER_METADATA_URL", "http://metadata.google.internal"),
            secretmanager_url: endpoint("KDPAGER_SECRETMANAGER_URL", "https://secretmanager.googleapis.com"),
        })
    }
}
