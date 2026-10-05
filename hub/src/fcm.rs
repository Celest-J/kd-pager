//! FCM HTTP v1 sender. Auth = VM service account token from the metadata server.

use serde_json::{json, Value};
use std::collections::HashMap;
use std::time::{Duration, Instant};
use tokio::sync::Mutex;

pub async fn metadata_token(http: &reqwest::Client, base: &str) -> Result<(String, u64), String> {
    let url = format!("{base}/computeMetadata/v1/instance/service-accounts/default/token");
    let resp = http
        .get(&url)
        .header("Metadata-Flavor", "Google")
        .send()
        .await
        .map_err(|e| format!("metadata server unreachable at {url}: {e}"))?;
    if !resp.status().is_success() {
        return Err(format!("metadata server {url} returned {}", resp.status()));
    }
    let v: Value = resp.json().await.map_err(|e| format!("metadata body: {e}"))?;
    let tok = v["access_token"].as_str().ok_or("metadata reply has no access_token")?.to_string();
    let ttl = v["expires_in"].as_u64().ok_or("metadata reply has no expires_in")?;
    Ok((tok, ttl))
}

pub enum Outcome {
    Sent,
    Unregistered,
}

pub struct Fcm {
    http: reqwest::Client,
    project: String,
    base: String,
    metadata_url: String,
    cached: Mutex<Option<(String, Instant)>>,
}

impl Fcm {
    pub fn new(http: reqwest::Client, project: String, base: String, metadata_url: String) -> Fcm {
        Fcm { http, project, base, metadata_url, cached: Mutex::new(None) }
    }

    async fn token(&self) -> Result<String, String> {
        let mut c = self.cached.lock().await;
        if let Some((t, exp)) = c.as_ref() {
            if Instant::now() + Duration::from_secs(60) < *exp {
                return Ok(t.clone());
            }
        }
        let (t, ttl) = metadata_token(&self.http, &self.metadata_url).await?;
        *c = Some((t.clone(), Instant::now() + Duration::from_secs(ttl)));
        Ok(t)
    }

    /// Data-only, priority HIGH. Never logs the token or the data values.
    pub async fn send(&self, fcm_token: &str, data: &HashMap<String, String>) -> Result<Outcome, String> {
        let bearer = self.token().await?;
        let url = format!("{}/v1/projects/{}/messages:send", self.base, self.project);
        let body = json!({"message": {"token": fcm_token, "data": data, "android": {"priority": "HIGH"}}});
        let resp = self
            .http
            .post(&url)
            .bearer_auth(bearer)
            .json(&body)
            .send()
            .await
            .map_err(|e| format!("fcm request failed: {e}"))?;
        let status = resp.status();
        if status.is_success() {
            return Ok(Outcome::Sent);
        }
        let text = resp.text().await.unwrap_or_default();
        if status.as_u16() == 404 || text.contains("UNREGISTERED") {
            return Ok(Outcome::Unregistered);
        }
        if status.as_u16() == 401 {
            *self.cached.lock().await = None;
        }
        let code = serde_json::from_str::<Value>(&text)
            .ok()
            .and_then(|v| v["error"]["status"].as_str().map(str::to_string))
            .unwrap_or_else(|| "no error.status".into());
        Err(format!("fcm returned {status} ({code})"))
    }
}
