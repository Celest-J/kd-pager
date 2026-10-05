//! Realtime listener: one worker per guild, one live socket at a time,
//! other registered sessions in that guild are spares.

use crate::hub::{Hub, Session, WorkerStat, SOCK_CONNECTING, SOCK_DOWN, SOCK_UP};
use futures_util::{SinkExt, StreamExt};
use serde_json::{json, Value};
use std::sync::atomic::Ordering;
use std::sync::Arc;
use std::time::{Duration, Instant};
use tokio_tungstenite::tungstenite::Message;

const HEARTBEAT: Duration = Duration::from_secs(25);
const SILENCE_LIMIT: Duration = Duration::from_secs(70);
const JOIN_LIMIT: Duration = Duration::from_secs(20);
const ALERT_AFTER: Duration = Duration::from_secs(90);

pub async fn worker(hub: Arc<Hub>, guild_id: String, stat: Arc<WorkerStat>) {
    info!("guild {guild_id}: worker started");
    let mut down_since: Option<Instant> = None;
    let mut alerted = false;
    let mut backoff = 5u64;
    loop {
        {
            let mut w = hub.workers.lock().unwrap();
            if !hub.guild_has_regs(&guild_id) {
                w.remove(&guild_id);
                info!("guild {guild_id}: no registrations left, worker exits");
                return;
            }
        }
        let mut subscribed_any = false;
        for s in hub.candidates(&guild_id) {
            stat.sock.store(SOCK_CONNECTING, Ordering::Relaxed);
            let mut subscribed = false;
            let res = socket(&hub, &guild_id, &s, &stat, &mut subscribed).await;
            stat.sock.store(SOCK_DOWN, Ordering::Relaxed);
            match res {
                Ok(()) => {}
                Err(e) => warn!("guild {guild_id}: socket via {} ended: {e}", s.user_id),
            }
            if subscribed {
                subscribed_any = true;
                break; // restart from the top candidate after a short pause
            }
        }
        if subscribed_any {
            down_since = None;
            alerted = false;
            backoff = 2;
        } else {
            let since = *down_since.get_or_insert_with(Instant::now);
            if !alerted && since.elapsed() > ALERT_AFTER {
                error!("guild {guild_id}: no working listener for {}s, alerting members", since.elapsed().as_secs());
                hub.alert_guild(&guild_id, "Pager disconnected. Open KD Pager and reconnect.");
                alerted = true;
            }
            backoff = (backoff * 2).min(30);
        }
        tokio::time::sleep(Duration::from_secs(backoff)).await;
    }
}

fn frame(join_ref: Option<&str>, r: &str, topic: &str, event: &str, payload: Value) -> Message {
    Message::Text(json!([join_ref, r, topic, event, payload]).to_string().into())
}

/// Returns when the socket ends. `subscribed` is set once the join was acknowledged.
async fn socket(hub: &Arc<Hub>, guild_id: &str, s: &Arc<Session>, stat: &WorkerStat, subscribed: &mut bool) -> Result<(), String> {
    let access = s.access().ok_or("session has no access token")?;
    let topic = format!("realtime:guild_chat:{guild_id}");
    let (ws, _) = tokio::time::timeout(Duration::from_secs(15), tokio_tungstenite::connect_async(hub.sb.ws_url()))
        .await
        .map_err(|_| "connect timed out".to_string())?
        .map_err(|e| format!("connect: {e}"))?;
    let (mut tx, mut rx) = ws.split();

    let join = json!({
        "config": {
            "broadcast": {"ack": false, "self": false},
            "presence": {"key": ""},
            "postgres_changes": [{"event": "INSERT", "schema": "public", "table": "guild_messages", "filter": format!("guild_id=eq.{guild_id}")}],
            "private": false
        },
        "access_token": access
    });
    tx.send(frame(Some("1"), "1", &topic, "phx_join", join)).await.map_err(|e| format!("send join: {e}"))?;

    let mut tok_rx = s.tok_tx.subscribe();
    let mut kill_rx = s.kill_tx.subscribe();
    let mut hb = tokio::time::interval_at(tokio::time::Instant::now() + HEARTBEAT, HEARTBEAT);
    let mut wd = tokio::time::interval(Duration::from_secs(5));
    let started = Instant::now();
    let mut last_frame = Instant::now();
    let mut n = 1u64;

    loop {
        tokio::select! {
            _ = async { let _ = kill_rx.wait_for(|k| *k).await; } => return Err("session stopped".into()),
            _ = tok_rx.changed() => {
                let tok = tok_rx.borrow_and_update().clone();
                if let Some(t) = tok {
                    n += 1;
                    tx.send(frame(Some("1"), &n.to_string(), &topic, "access_token", json!({"access_token": t})))
                        .await.map_err(|e| format!("send access_token: {e}"))?;
                }
            }
            _ = hb.tick() => {
                n += 1;
                tx.send(frame(None, &n.to_string(), "phoenix", "heartbeat", json!({})))
                    .await.map_err(|e| format!("send heartbeat: {e}"))?;
            }
            _ = wd.tick() => {
                if last_frame.elapsed() > SILENCE_LIMIT {
                    return Err(format!("watchdog: no frame for {}s", last_frame.elapsed().as_secs()));
                }
                if !*subscribed && started.elapsed() > JOIN_LIMIT {
                    return Err("join was not acknowledged in time".into());
                }
            }
            msg = rx.next() => {
                let Some(msg) = msg else { return Err("socket closed by peer".into()) };
                let msg = msg.map_err(|e| format!("read: {e}"))?;
                last_frame = Instant::now();
                let text = match msg {
                    Message::Text(t) => t.to_string(),
                    Message::Close(_) => return Err("close frame".into()),
                    _ => continue, // binary (typing broadcasts), ping, pong
                };
                match handle_frame(hub, guild_id, s, &topic, &text, subscribed, stat).await? {
                    Flow::Continue => {}
                }
            }
        }
    }
}

enum Flow {
    Continue,
}

async fn handle_frame(
    hub: &Arc<Hub>,
    guild_id: &str,
    s: &Arc<Session>,
    topic: &str,
    text: &str,
    subscribed: &mut bool,
    stat: &WorkerStat,
) -> Result<Flow, String> {
    if text.trim().is_empty() {
        return Ok(Flow::Continue);
    }
    let Ok(v) = serde_json::from_str::<Value>(text) else {
        warn!("guild {guild_id}: skipped a non-JSON frame ({} bytes)", text.len());
        return Ok(Flow::Continue);
    };
    let Some(a) = v.as_array().filter(|a| a.len() == 5) else {
        warn!("guild {guild_id}: skipped a frame that is not a 5-element array");
        return Ok(Flow::Continue);
    };
    let (ftopic, event, payload) = (a[2].as_str().unwrap_or(""), a[3].as_str().unwrap_or(""), &a[4]);
    match event {
        "phx_reply" if ftopic == topic => {
            if payload["status"] == "ok" {
                if a[1].as_str() == Some("1") && !*subscribed {
                    *subscribed = true;
                    stat.sock.store(SOCK_UP, Ordering::Relaxed);
                    info!("guild {guild_id}: subscribed via user {}", s.user_id);
                    hub.catch_up(guild_id, s).await;
                }
            } else {
                return Err(format!("phx_reply not ok: {}", payload["status"]));
            }
        }
        "postgres_changes" if ftopic == topic => {
            let rec = &payload["data"]["record"];
            if rec.is_object() {
                hub.handle_row(guild_id, rec, s).await;
            }
        }
        "phx_error" | "phx_close" if ftopic == topic => return Err(format!("channel sent {event}")),
        "system" if payload["status"] == "error" => {
            let m: String = payload["message"].as_str().unwrap_or("no message").chars().take(100).collect();
            return Err(format!("realtime system error: {m}"));
        }
        _ => {}
    }
    Ok(Flow::Continue)
}
