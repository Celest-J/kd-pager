//! Supabase client: auth refresh, who-am-i, membership, profile, message catch-up.
//! Error strings never contain tokens.

use crate::store::GuildInfo;
use base64::Engine;
use serde_json::Value;

pub struct SbSession {
    pub access_token: String,
    pub refresh_token: String,
    pub expires_at: i64,
    /// Full session JSON (provider tokens stripped), kept in memory for the site cookie.
    pub raw: Value,
}

pub enum RefreshErr {
    /// Supabase rejected the refresh token. Session is dead.
    Rejected(String),
    /// Network or 5xx. Retry.
    Transient(String),
}

pub struct Sb {
    http: reqwest::Client,
    pub base: String,
    anon: String,
}

pub fn now() -> i64 {
    std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).unwrap().as_secs() as i64
}

/// Accepts the session as a JSON object, a JSON string, or the cookie form `base64-<base64url(JSON)>`.
pub fn parse_session(v: &Value) -> Result<SbSession, String> {
    let obj: Value = match v {
        Value::Object(_) => v.clone(),
        Value::String(s) => {
            let s = s.trim();
            if let Some(b) = s.strip_prefix("base64-") {
                let bytes = base64::engine::general_purpose::URL_SAFE_NO_PAD
                    .decode(b.trim_end_matches('='))
                    .map_err(|_| "kd_session: bad base64url after base64- prefix".to_string())?;
                serde_json::from_slice(&bytes).map_err(|_| "kd_session: base64 payload is not JSON".to_string())?
            } else {
                serde_json::from_str(s).map_err(|_| "kd_session: string is neither base64-... nor JSON".to_string())?
            }
        }
        _ => return Err("kd_session must be an object or string".into()),
    };
    session_from_obj(obj, "kd_session")
}

fn session_from_obj(mut obj: Value, what: &str) -> Result<SbSession, String> {
    let m = obj.as_object_mut().ok_or_else(|| format!("{what}: not an object"))?;
    m.remove("provider_token");
    m.remove("provider_refresh_token");
    let access = m.get("access_token").and_then(Value::as_str).filter(|s| !s.is_empty());
    let refresh = m.get("refresh_token").and_then(Value::as_str).filter(|s| !s.is_empty());
    let exp = m.get("expires_at").and_then(Value::as_i64);
    match (access, refresh, exp) {
        (Some(a), Some(r), Some(e)) => {
            let (a, r) = (a.to_string(), r.to_string());
            Ok(SbSession { access_token: a, refresh_token: r, expires_at: e, raw: obj })
        }
        _ => Err(format!("{what}: needs access_token, refresh_token and numeric expires_at")),
    }
}

fn short(s: &str) -> String {
    s.chars().take(120).collect()
}

impl Sb {
    pub fn new(http: reqwest::Client, base: String, anon: String) -> Sb {
        Sb { http, base, anon }
    }

    pub fn ws_url(&self) -> String {
        let host = self.base.replacen("https://", "wss://", 1).replacen("http://", "ws://", 1);
        format!("{host}/realtime/v1/websocket?apikey={}&vsn=2.0.0", self.anon)
    }

    /// Cookie base name used by Supabase SSR: sb-<project-ref>-auth-token
    pub fn cookie_name(&self) -> String {
        let host = self.base.split("://").nth(1).unwrap_or(&self.base);
        let host = host.split(['/', ':']).next().unwrap_or(host);
        format!("sb-{}-auth-token", host.split('.').next().unwrap_or(host))
    }

    fn get(&self, path: &str, access: &str) -> reqwest::RequestBuilder {
        self.http
            .get(format!("{}{path}", self.base))
            .header("apikey", &self.anon)
            .bearer_auth(access)
    }

    pub async fn refresh(&self, refresh_token: &str) -> Result<SbSession, RefreshErr> {
        let resp = self
            .http
            .post(format!("{}/auth/v1/token?grant_type=refresh_token", self.base))
            .header("apikey", &self.anon)
            .json(&serde_json::json!({"refresh_token": refresh_token}))
            .send()
            .await
            .map_err(|e| RefreshErr::Transient(format!("refresh request failed: {e}")))?;
        let status = resp.status();
        let body: Value = resp.json().await.unwrap_or(Value::Null);
        if status.is_success() {
            return session_from_obj(body, "refresh reply").map_err(RefreshErr::Rejected);
        }
        let why = body["error_description"].as_str().or(body["msg"].as_str()).unwrap_or("no reason given");
        let msg = format!("refresh returned {status}: {}", short(why));
        if status.is_server_error() || status.as_u16() == 429 {
            Err(RefreshErr::Transient(msg))
        } else {
            Err(RefreshErr::Rejected(msg))
        }
    }

    pub async fn user_id(&self, access: &str) -> Result<String, String> {
        let resp = self.get("/auth/v1/user", access).send().await.map_err(|e| format!("auth/user request failed: {e}"))?;
        if !resp.status().is_success() {
            return Err(format!("auth/user returned {}: session not accepted", resp.status()));
        }
        let v: Value = resp.json().await.map_err(|e| format!("auth/user body: {e}"))?;
        v["id"].as_str().map(str::to_string).ok_or_else(|| "auth/user reply has no id".to_string())
    }

    pub async fn guilds(&self, access: &str, user_id: &str) -> Result<Vec<GuildInfo>, String> {
        let resp = self
            .get("/rest/v1/guild_members", access)
            .query(&[("select", "guild_id,guilds(name,slug)"), ("user_id", &format!("eq.{user_id}"))])
            .send()
            .await
            .map_err(|e| format!("guild_members request failed: {e}"))?;
        if !resp.status().is_success() {
            return Err(format!("guild_members returned {}", resp.status()));
        }
        let rows: Vec<Value> = resp.json().await.map_err(|e| format!("guild_members body: {e}"))?;
        rows.iter()
            .map(|r| {
                let f = |v: &Value| v.as_str().map(str::to_string);
                match (f(&r["guild_id"]), f(&r["guilds"]["name"]), f(&r["guilds"]["slug"])) {
                    (Some(id), Some(name), Some(slug)) => Ok(GuildInfo { id, name, slug }),
                    _ => Err("guild_members row lacks guild_id / guilds.name / guilds.slug".to_string()),
                }
            })
            .collect()
    }

    pub async fn username(&self, access: &str, user_id: &str) -> Result<String, String> {
        let resp = self
            .get("/rest/v1/profiles", access)
            .query(&[("select", "username"), ("id", &format!("eq.{user_id}"))])
            .send()
            .await
            .map_err(|e| format!("profiles request failed: {e}"))?;
        if !resp.status().is_success() {
            return Err(format!("profiles returned {}", resp.status()));
        }
        let rows: Vec<Value> = resp.json().await.map_err(|e| format!("profiles body: {e}"))?;
        rows.first()
            .and_then(|r| r["username"].as_str())
            .map(str::to_string)
            .ok_or_else(|| "no profile row / username for this user".to_string())
    }

    pub async fn messages_since(&self, access: &str, guild_id: &str, since: &str) -> Result<Vec<Value>, String> {
        let resp = self
            .get("/rest/v1/guild_messages", access)
            .query(&[
                ("select", "id,content,user_id,guild_id,created_at"),
                ("guild_id", &format!("eq.{guild_id}")),
                ("created_at", &format!("gt.{since}")),
                ("order", "created_at.asc"),
                ("limit", "100"),
            ])
            .send()
            .await
            .map_err(|e| format!("catch-up request failed: {e}"))?;
        if !resp.status().is_success() {
            return Err(format!("catch-up returned {}", resp.status()));
        }
        resp.json().await.map_err(|e| format!("catch-up body: {e}"))
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    #[test]
    fn session_forms() {
        let o = json!({"access_token":"a","refresh_token":"r","expires_at":5,"provider_token":"x","user":{"id":"u"}});
        let s = parse_session(&o).unwrap();
        assert_eq!((s.access_token.as_str(), s.expires_at), ("a", 5));
        assert!(s.raw.get("provider_token").is_none());
        let enc = format!("base64-{}", base64::engine::general_purpose::URL_SAFE_NO_PAD.encode(o.to_string()));
        assert_eq!(parse_session(&json!(enc)).unwrap().refresh_token, "r");
        assert!(parse_session(&json!({"access_token":"a"})).is_err());
        assert!(parse_session(&json!(5)).is_err());
    }

    #[test]
    fn cookie_and_ws() {
        let sb = Sb::new(reqwest::Client::new(), "https://abc123.supabase.co".into(), "K".into());
        assert_eq!(sb.cookie_name(), "sb-abc123-auth-token");
        assert_eq!(sb.ws_url(), "wss://abc123.supabase.co/realtime/v1/websocket?apikey=K&vsn=2.0.0");
    }
}
