//! Encryption-at-rest layer. XChaCha20-Poly1305, random 24-byte nonce per write.
//! File layout: b"KDP1" || nonce(24) || ciphertext+tag. The magic doubles as AAD.

use crate::config::{Config, KeySourceCfg};
use crate::fcm::metadata_token;
use chacha20poly1305::aead::{Aead, AeadCore, KeyInit, OsRng, Payload};
use chacha20poly1305::{XChaCha20Poly1305, XNonce};
use serde_json::Value;
use std::future::Future;
use std::pin::Pin;

const MAGIC: &[u8; 4] = b"KDP1";

pub struct Vault {
    cipher: XChaCha20Poly1305,
}

impl Vault {
    pub fn new(key: [u8; 32]) -> Vault {
        Vault { cipher: XChaCha20Poly1305::new((&key).into()) }
    }

    pub fn seal(&self, plain: &[u8]) -> Result<Vec<u8>, String> {
        let nonce = XChaCha20Poly1305::generate_nonce(&mut OsRng);
        let ct = self
            .cipher
            .encrypt(&nonce, Payload { msg: plain, aad: MAGIC })
            .map_err(|_| "encrypt failed".to_string())?;
        let mut out = Vec::with_capacity(4 + 24 + ct.len());
        out.extend_from_slice(MAGIC);
        out.extend_from_slice(&nonce);
        out.extend_from_slice(&ct);
        Ok(out)
    }

    pub fn open(&self, blob: &[u8]) -> Result<Vec<u8>, String> {
        if blob.len() < 4 + 24 + 16 || &blob[..4] != MAGIC {
            return Err("state file is not a KDP1 blob".into());
        }
        let nonce = XNonce::from_slice(&blob[4..28]);
        self.cipher
            .decrypt(nonce, Payload { msg: &blob[28..], aad: MAGIC })
            .map_err(|_| "decrypt failed: wrong key or corrupted state file".to_string())
    }
}

pub fn parse_hex_key(s: &str) -> Result<[u8; 32], String> {
    let s = s.trim();
    if s.len() != 64 || !s.is_ascii() {
        return Err(format!("key must be 64 hex chars, got {} chars", s.len()));
    }
    let mut out = [0u8; 32];
    for (i, b) in out.iter_mut().enumerate() {
        *b = u8::from_str_radix(&s[i * 2..i * 2 + 2], 16).map_err(|_| "key is not valid hex".to_string())?;
    }
    Ok(out)
}

type KeyFut<'a> = Pin<Box<dyn Future<Output = Result<[u8; 32], String>> + Send + 'a>>;

pub trait KeySource: Send + Sync {
    fn fetch(&self) -> KeyFut<'_>;
}

/// LOCAL DEV STUB. Reads the key from KDPAGER_DEV_KEY_HEX. Not for production.
pub struct DevEnvKey(pub String);

impl KeySource for DevEnvKey {
    fn fetch(&self) -> KeyFut<'_> {
        Box::pin(async move { parse_hex_key(&self.0) })
    }
}

/// PRODUCTION. GCP Secret Manager REST, authenticated with the VM service account
/// (metadata server). The SA needs roles/secretmanager.secretAccessor on the secret.
pub struct GcpSecretKey {
    pub http: reqwest::Client,
    pub secret: String, // projects/P/secrets/S/versions/latest
    pub metadata_url: String,
    pub sm_url: String,
}

impl KeySource for GcpSecretKey {
    fn fetch(&self) -> KeyFut<'_> {
        Box::pin(async move {
            let (tok, _) = metadata_token(&self.http, &self.metadata_url).await?;
            let url = format!("{}/v1/{}:access", self.sm_url, self.secret);
            let resp = self
                .http
                .get(&url)
                .bearer_auth(tok)
                .send()
                .await
                .map_err(|e| format!("secret manager request failed: {e}"))?;
            let status = resp.status();
            if !status.is_success() {
                return Err(format!("secret manager {url} returned {status}"));
            }
            let v: Value = resp.json().await.map_err(|e| format!("secret manager body: {e}"))?;
            let b64 = v["payload"]["data"].as_str().ok_or("secret manager reply has no payload.data")?;
            use base64::Engine;
            let raw = base64::engine::general_purpose::STANDARD
                .decode(b64)
                .map_err(|e| format!("secret payload base64: {e}"))?;
            let text = String::from_utf8(raw).map_err(|_| "secret payload is not utf-8 hex text".to_string())?;
            parse_hex_key(&text)
        })
    }
}

pub fn key_source(cfg: &Config, http: &reqwest::Client) -> Box<dyn KeySource> {
    match &cfg.key_source {
        KeySourceCfg::EnvDev { hex } => {
            warn!("KEY SOURCE = env-dev (LOCAL STUB). Never run this in production.");
            Box::new(DevEnvKey(hex.clone()))
        }
        KeySourceCfg::GcpSecret { secret } => Box::new(GcpSecretKey {
            http: http.clone(),
            secret: secret.clone(),
            metadata_url: cfg.metadata_url.clone(),
            sm_url: cfg.secretmanager_url.clone(),
        }),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn roundtrip_and_tamper() {
        let v = Vault::new([7u8; 32]);
        let blob = v.seal(b"hello state").unwrap();
        assert_eq!(v.open(&blob).unwrap(), b"hello state");
        let mut bad = blob.clone();
        let last = bad.len() - 1;
        bad[last] ^= 1;
        assert!(v.open(&bad).is_err());
        assert!(Vault::new([8u8; 32]).open(&blob).is_err());
        assert!(v.open(b"nope").is_err());
    }

    #[test]
    fn hex_key() {
        assert!(parse_hex_key(&"ab".repeat(32)).is_ok());
        assert!(parse_hex_key("abcd").is_err());
        assert!(parse_hex_key(&"zz".repeat(32)).is_err());
    }
}
