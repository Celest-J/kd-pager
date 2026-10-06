//! The only HTTP surface: POST /register, POST /unregister, POST /token, GET /health. Everything else: bare 404.

use crate::hub::{ApiErr, Hub, SOCK_CONNECTING, SOCK_DOWN, SOCK_UP};
use axum::http::HeaderMap;
use axum::body::Bytes;
use axum::extract::{DefaultBodyLimit, State};
use axum::http::{Method, StatusCode, Uri};
use axum::response::{IntoResponse, Response};
use axum::Router;
use serde::Deserialize;
use serde_json::{json, Value};
use std::sync::atomic::Ordering;
use std::sync::Arc;

const BODY_CAP: usize = 32 * 1024;

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct RegisterReq {
    fcm_token: String,
    kd_session: Option<Value>,
    old_fcm_token: Option<String>,
    device_key: Option<String>,
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct UnregisterReq {
    fcm_token: String,
    device_key: String,
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct TokenReq {
    fcm_token: String,
    device_key: String,
}

pub fn router(hub: Arc<Hub>) -> Router {
    Router::new().fallback(dispatch).layer(DefaultBodyLimit::max(BODY_CAP)).with_state(hub)
}

fn json_resp(status: u16, v: Value) -> Response {
    (StatusCode::from_u16(status).unwrap(), [("content-type", "application/json")], v.to_string()).into_response()
}

fn err(e: ApiErr) -> Response {
    json_resp(e.0, json!({"error": e.1}))
}

fn valid_fcm(t: &str) -> bool {
    (20..=4096).contains(&t.len()) && t.chars().all(|c| c.is_ascii_graphic())
}

// Caddy appends the real client to X-Forwarded-For; no header = a local call (health checks on the box).
fn client_ip(h: &HeaderMap) -> String {
    h.get("x-forwarded-for")
        .and_then(|v| v.to_str().ok())
        .and_then(|v| v.split(',').next_back())
        .map(|v| v.trim().to_string())
        .unwrap_or_else(|| "local".into())
}

async fn dispatch(State(hub): State<Arc<Hub>>, method: Method, uri: Uri, headers: HeaderMap, body: Bytes) -> Response {
    let ip = client_ip(&headers);
    match (method, uri.path()) {
        // counts + socket states are for the operator only: Caddy also 404s /health from outside
        (Method::GET, "/health") if ip == "local" => health(&hub),
        (Method::POST, "/register") => {
            if !hub.allow(&ip, "register", 10, 3600) {
                warn!("rate limit: /register from {ip}");
                return err(ApiErr(429, "too many connects from this address, try in an hour".into()));
            }
            register(&hub, &body).await
        }
        (Method::POST, "/unregister") => {
            if !hub.allow(&ip, "unregister", 20, 3600) {
                warn!("rate limit: /unregister from {ip}");
                return err(ApiErr(429, "too many requests from this address".into()));
            }
            unregister(&hub, &body)
        }
        (Method::POST, "/token") => {
            // the app asks on open/resume and when KD's page wants a refresh: a few per hour per phone
            if !hub.allow(&ip, "token", 240, 3600) {
                warn!("rate limit: /token from {ip}");
                return err(ApiErr(429, "too many requests from this address".into()));
            }
            token(&hub, &body)
        }
        _ => StatusCode::NOT_FOUND.into_response(),
    }
}

async fn register(hub: &Arc<Hub>, body: &[u8]) -> Response {
    let Ok(_permit) = hub.reg_gate.try_acquire() else {
        return err(ApiErr(429, "busy, retry shortly".into()));
    };
    let req: RegisterReq = match serde_json::from_slice(body) {
        Ok(r) => r,
        Err(e) => return err(ApiErr(400, format!("bad json: {}", e.classify_name()))),
    };
    if !valid_fcm(&req.fcm_token) {
        return err(ApiErr(400, "fcm_token is not a plausible token".into()));
    }
    match (req.kd_session, req.old_fcm_token) {
        (Some(sess), None) => match hub.register(req.fcm_token, &sess).await {
            Ok((n, key)) => json_resp(200, json!({"ok": true, "guilds": n, "device_key": key})),
            Err(e) => err(e),
        },
        (None, Some(old)) => {
            if !valid_fcm(&old) {
                return err(ApiErr(400, "old_fcm_token is not a plausible token".into()));
            }
            let Some(key) = req.device_key else {
                return err(ApiErr(400, "rotation needs device_key".into()));
            };
            match hub.rotate(&old, req.fcm_token, &key) {
                Ok(()) => json_resp(200, json!({"ok": true, "rotated": true})),
                Err(e) => err(e),
            }
        }
        (None, None) => err(ApiErr(400, "need kd_session (connect) or old_fcm_token (rotation)".into())),
        (Some(_), Some(_)) => err(ApiErr(400, "send kd_session or old_fcm_token, not both".into())),
    }
}

fn unregister(hub: &Arc<Hub>, body: &[u8]) -> Response {
    let req: UnregisterReq = match serde_json::from_slice(body) {
        Ok(r) => r,
        Err(e) => return err(ApiErr(400, format!("bad json: {}", e.classify_name()))),
    };
    if !valid_fcm(&req.fcm_token) {
        return err(ApiErr(400, "fcm_token is not a plausible token".into()));
    }
    if hub.unregister_with_key(&req.fcm_token, &req.device_key) {
        json_resp(200, json!({"ok": true, "removed": true}))
    } else {
        err(ApiErr(403, "fcm_token + device_key not registered".into()))
    }
}

fn token(hub: &Arc<Hub>, body: &[u8]) -> Response {
    let req: TokenReq = match serde_json::from_slice(body) {
        Ok(r) => r,
        Err(e) => return err(ApiErr(400, format!("bad json: {}", e.classify_name()))),
    };
    if !valid_fcm(&req.fcm_token) {
        return err(ApiErr(400, "fcm_token is not a plausible token".into()));
    }
    match hub.session_for(&req.fcm_token, &req.device_key) {
        Ok(s) => json_resp(200, json!({"session": s})),
        Err(e) => err(e),
    }
}

fn health(hub: &Arc<Hub>) -> Response {
    let (mut up, mut connecting, mut down) = (0, 0, 0);
    let guilds = {
        let w = hub.workers.lock().unwrap();
        for st in w.values() {
            match st.sock.load(Ordering::Relaxed) {
                SOCK_UP => up += 1,
                SOCK_CONNECTING => connecting += 1,
                SOCK_DOWN => down += 1,
                _ => {}
            }
        }
        w.len()
    };
    let regs = hub.store.with(|st| st.regs.len());
    let last = hub.last_row.load(Ordering::Relaxed);
    json_resp(
        200,
        json!({
            "sessions": hub.session_count(),
            "guilds": guilds,
            "sockets": {"connected": up, "connecting": connecting, "down": down},
            "registrations": regs,
            "last_row_at": if last == 0 { Value::Null } else { json!(last) },
        }),
    )
}

/// serde_json errors can echo input fragments; expose only the category.
trait ClassifyName {
    fn classify_name(&self) -> &'static str;
}
impl ClassifyName for serde_json::Error {
    fn classify_name(&self) -> &'static str {
        use serde_json::error::Category::*;
        match self.classify() {
            Io => "io",
            Syntax => "syntax",
            Data => "wrong or missing fields",
            Eof => "truncated",
        }
    }
}
