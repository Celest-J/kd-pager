//! Shared state + the business logic: sessions, registrations, token refresh, push.

use crate::config::Config;
use crate::fcm::{Fcm, Outcome};
use crate::names;
use crate::sb::{now, parse_session, RefreshErr, Sb, SbSession};
use crate::store::{GuildInfo, Reg, Store};
use serde_json::Value;
use std::collections::{HashMap, HashSet, VecDeque};
use std::sync::atomic::{AtomicI64, AtomicU8, Ordering};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};
use tokio::sync::{watch, Semaphore};

pub const MAX_REGS: usize = 1000;
/// One KD account can keep at most this many phones; a new Connect past it drops the oldest.
pub const MAX_PER_USER: usize = 3;
const REFRESH_LEAD_SECS: i64 = 300;
const NAME_RELOAD_MIN: Duration = Duration::from_secs(30);
pub const UNKNOWN_MEMBER: &str = "unknown member";

pub struct ApiErr(pub u16, pub String);

pub const SOCK_DOWN: u8 = 0;
pub const SOCK_CONNECTING: u8 = 1;
pub const SOCK_UP: u8 = 2;

pub struct WorkerStat {
    pub sock: AtomicU8,
}

struct SInner {
    access: Option<String>,
    refresh: String,
    expires_at: i64,
    raw: Option<Value>,
}

/// One user's KD session, held by the hub. Access token lives only here, in memory.
pub struct Session {
    pub user_id: String,
    inner: Mutex<SInner>,
    pub tok_tx: watch::Sender<Option<String>>,
    pub kill_tx: watch::Sender<bool>,
}

impl Session {
    fn new(user_id: String, refresh: String, live: Option<SbSession>) -> Arc<Session> {
        let (access, expires_at, raw, refresh) = match live {
            Some(s) => (Some(s.access_token), s.expires_at, Some(s.raw), s.refresh_token),
            None => (None, 0, None, refresh),
        };
        let (tok_tx, _) = watch::channel(access.clone());
        let (kill_tx, _) = watch::channel(false);
        Arc::new(Session { user_id, inner: Mutex::new(SInner { access, refresh, expires_at, raw }), tok_tx, kill_tx })
    }
    pub fn access(&self) -> Option<String> {
        self.inner.lock().unwrap().access.clone()
    }
    pub fn killed(&self) -> bool {
        *self.kill_tx.borrow()
    }
    pub fn kill(&self) {
        self.kill_tx.send_replace(true);
    }
    fn raw(&self) -> Option<Value> {
        self.inner.lock().unwrap().raw.clone()
    }
}

struct Seen {
    set: HashSet<String>,
    order: VecDeque<String>,
}
impl Seen {
    fn first_time(&mut self, id: &str) -> bool {
        if !self.set.insert(id.to_string()) {
            return false;
        }
        self.order.push_back(id.to_string());
        if self.order.len() > 4000 {
            if let Some(old) = self.order.pop_front() {
                self.set.remove(&old);
            }
        }
        true
    }
}

struct GuildNames {
    map: HashMap<String, names::Member>,
    loaded: Option<Instant>,
}

pub struct Hub {
    pub cfg: Config,
    pub http: reqwest::Client,
    pub sb: Sb,
    pub store: Store,
    pub fcm: Fcm,
    sessions: Mutex<HashMap<String, Arc<Session>>>,
    pub workers: Mutex<HashMap<String, Arc<WorkerStat>>>,
    seen: Mutex<Seen>,
    names: Mutex<HashMap<String, GuildNames>>,
    pub last_row: AtomicI64,
    pub reg_gate: Semaphore,
    hits: Mutex<HashMap<String, VecDeque<i64>>>,
}

fn new_device_key() -> String {
    use chacha20poly1305::aead::rand_core::RngCore;
    let mut b = [0u8; 32];
    chacha20poly1305::aead::OsRng.fill_bytes(&mut b);
    b.iter().map(|x| format!("{x:02x}")).collect()
}

/// Same time whatever the input: no early exit on the first wrong byte.
fn key_eq(a: &str, b: &str) -> bool {
    !a.is_empty() && a.len() == b.len() && a.bytes().zip(b.bytes()).fold(0u8, |acc, (x, y)| acc | (x ^ y)) == 0
}

fn val_str(v: &Value) -> Option<String> {
    match v {
        Value::String(s) if !s.is_empty() => Some(s.clone()),
        Value::Number(n) => Some(n.to_string()),
        _ => None,
    }
}

impl Hub {
    pub fn new(cfg: Config, http: reqwest::Client, store: Store) -> Arc<Hub> {
        let sb = Sb::new(http.clone(), cfg.sb_url.clone(), cfg.anon_key.clone());
        let fcm = Fcm::new(http.clone(), cfg.fcm_project.clone(), cfg.fcm_url.clone(), cfg.metadata_url.clone());
        Arc::new(Hub {
            cfg,
            http,
            sb,
            store,
            fcm,
            sessions: Mutex::new(HashMap::new()),
            workers: Mutex::new(HashMap::new()),
            seen: Mutex::new(Seen { set: HashSet::new(), order: VecDeque::new() }),
            names: Mutex::new(HashMap::new()),
            last_row: AtomicI64::new(0),
            reg_gate: Semaphore::new(4),
            hits: Mutex::new(HashMap::new()),
        })
    }

    /// Boot: restart a refresher for every stored refresh token, then the guild workers.
    pub fn restore(self: &Arc<Self>) {
        let stored: Vec<(String, String)> = self.store.with(|st| st.refresh.iter().map(|(k, v)| (k.clone(), v.clone())).collect());
        for (uid, refresh) in stored {
            let s = Session::new(uid.clone(), refresh, None);
            self.sessions.lock().unwrap().insert(uid, s.clone());
            tokio::spawn(run_session(self.clone(), s));
        }
        self.ensure_workers();
    }

    pub fn session_count(&self) -> usize {
        self.sessions.lock().unwrap().len()
    }

    /// Live sessions whose owner is registered in this guild, stable order.
    pub fn candidates(&self, guild_id: &str) -> Vec<Arc<Session>> {
        let uids: HashSet<String> = self.store.with(|st| {
            st.regs.iter().filter(|r| r.guilds.iter().any(|g| g.id == guild_id)).map(|r| r.user_id.clone()).collect()
        });
        let mut v: Vec<Arc<Session>> = self
            .sessions
            .lock()
            .unwrap()
            .values()
            .filter(|s| uids.contains(&s.user_id) && !s.killed() && s.access().is_some())
            .cloned()
            .collect();
        v.sort_by(|a, b| a.user_id.cmp(&b.user_id));
        v
    }

    pub fn guild_has_regs(&self, guild_id: &str) -> bool {
        self.store.with(|st| st.regs.iter().any(|r| r.guilds.iter().any(|g| g.id == guild_id)))
    }

    pub fn ensure_workers(self: &Arc<Self>) {
        let guilds: HashSet<String> =
            self.store.with(|st| st.regs.iter().flat_map(|r| r.guilds.iter().map(|g| g.id.clone())).collect());
        let mut w = self.workers.lock().unwrap();
        for g in guilds {
            if !w.contains_key(&g) {
                let stat = Arc::new(WorkerStat { sock: AtomicU8::new(SOCK_DOWN) });
                w.insert(g.clone(), stat.clone());
                tokio::spawn(crate::listener::worker(self.clone(), g, stat));
            }
        }
    }

    /// Kill sessions that no registration owns any more.
    fn gc_sessions(&self) {
        let owners: HashSet<String> = self.store.with(|st| st.regs.iter().map(|r| r.user_id.clone()).collect());
        let mut dropped = vec![];
        self.sessions.lock().unwrap().retain(|uid, s| {
            let keep = owners.contains(uid);
            if !keep {
                s.kill();
                dropped.push(uid.clone());
            }
            keep
        });
        if !dropped.is_empty() {
            self.store.with(|st| dropped.iter().for_each(|u| drop(st.refresh.remove(u))));
            if let Err(e) = self.store.save() {
                error!("save after gc: {e}");
            }
        }
    }

    /// Sliding-window limit per (client ip, endpoint). Client ip comes from Caddy's X-Forwarded-For:
    /// the hub listens on loopback only, so nothing but Caddy can set that header.
    pub fn allow(&self, ip: &str, what: &str, max: usize, window_secs: i64) -> bool {
        let t = now();
        let mut m = self.hits.lock().unwrap();
        if m.len() > 20_000 {
            m.retain(|_, q| q.back().is_some_and(|last| t - last < 3600));
        }
        let q = m.entry(format!("{what} {ip}")).or_default();
        while q.front().is_some_and(|first| t - first >= window_secs) {
            q.pop_front();
        }
        if q.len() >= max {
            return false;
        }
        q.push_back(t);
        true
    }

    pub async fn register(self: &Arc<Self>, fcm_token: String, raw: &Value) -> Result<(usize, String), ApiErr> {
        let mut sess = parse_session(raw).map_err(|e| ApiErr(400, e))?;
        if sess.expires_at - now() < 120 {
            sess = self.sb.refresh(&sess.refresh_token).await.map_err(|e| match e {
                RefreshErr::Rejected(m) => ApiErr(401, m),
                RefreshErr::Transient(m) => ApiErr(502, m),
            })?;
        }
        let uid = self.sb.user_id(&sess.access_token).await.map_err(|e| ApiErr(401, e))?;
        let guilds = self.sb.guilds(&sess.access_token, &uid).await.map_err(|e| ApiErr(502, e))?;
        if guilds.is_empty() {
            return Err(ApiErr(422, "this account is in no guilds".into()));
        }
        let username = self.sb.username(&sess.access_token, &uid).await.map_err(|e| ApiErr(502, e))?;

        let n_guilds = guilds.len();
        let refresh_tok = sess.refresh_token.clone();
        let device_key = new_device_key();
        let full = self.store.with(|st| {
            st.regs.retain(|r| r.fcm_token != fcm_token);
            // oldest phones of this account go first once it is at the cap
            while st.regs.iter().filter(|r| r.user_id == uid).count() >= MAX_PER_USER {
                let i = st.regs.iter().position(|r| r.user_id == uid).unwrap();
                info!("register: account at {MAX_PER_USER} phones, dropping its oldest");
                st.regs.remove(i);
            }
            if st.regs.len() >= MAX_REGS {
                return true;
            }
            st.regs.push(Reg { fcm_token, user_id: uid.clone(), username, guilds, device_key: device_key.clone() });
            st.refresh.insert(uid.clone(), refresh_tok);
            false
        });
        if full {
            return Err(ApiErr(503, "registration limit reached".into()));
        }
        self.store.save().map_err(|e| {
            error!("register: {e}");
            ApiErr(500, "could not persist registration".into())
        })?;

        let s = Session::new(uid.clone(), String::new(), Some(sess));
        if let Some(old) = self.sessions.lock().unwrap().insert(uid, s.clone()) {
            old.kill();
        }
        tokio::spawn(run_session(self.clone(), s));
        self.gc_sessions();
        self.ensure_workers();
        Ok((n_guilds, device_key))
    }

    /// FCM token rotation: swap the token on an existing registration, keep session + guilds.
    /// Unknown old token -> 409 so the app knows it must re-run Connect.
    pub fn rotate(&self, old: &str, new: String, key: &str) -> Result<(), ApiErr> {
        let found = self.store.with(|st| {
            if !st.regs.iter().any(|r| r.fcm_token == old && key_eq(&r.device_key, key)) {
                return false;
            }
            if old != new {
                st.regs.retain(|r| r.fcm_token != new);
                if let Some(r) = st.regs.iter_mut().find(|r| r.fcm_token == old) {
                    r.fcm_token = new;
                }
            }
            true
        });
        if !found {
            return Err(ApiErr(409, "old_fcm_token + device_key not registered: run Connect again".into()));
        }
        self.store.save().map_err(|e| {
            error!("rotate: {e}");
            ApiErr(500, "could not persist rotation".into())
        })
    }

    /// Phone-initiated: only the phone holding the device key may remove its registration.
    pub fn unregister_with_key(&self, fcm_token: &str, key: &str) -> bool {
        let ok = self.store.with(|st| st.regs.iter().any(|r| r.fcm_token == fcm_token && key_eq(&r.device_key, key)));
        ok && self.unregister(fcm_token)
    }

    /// Hub-initiated (FCM said the token is dead): no key needed.
    pub fn unregister(&self, fcm_token: &str) -> bool {
        let removed = self.store.with(|st| {
            let before = st.regs.len();
            st.regs.retain(|r| r.fcm_token != fcm_token);
            before != st.regs.len()
        });
        if removed {
            if let Err(e) = self.store.save() {
                error!("unregister save: {e}");
            }
            self.gc_sessions();
        }
        removed
    }

    // ---- push -----------------------------------------------------------------------

    pub async fn deliver(self: &Arc<Self>, fcm_token: String, data: HashMap<String, String>) {
        match self.fcm.send(&fcm_token, &data).await {
            Ok(Outcome::Sent) => {}
            Ok(Outcome::Unregistered) => {
                info!("fcm: token unregistered, removing registration");
                self.unregister(&fcm_token);
            }
            Err(e) => error!("fcm send: {e}"),
        }
    }

    pub fn alert_user(self: &Arc<Self>, user_id: &str, message: &str) {
        let tokens: Vec<String> =
            self.store.with(|st| st.regs.iter().filter(|r| r.user_id == user_id).map(|r| r.fcm_token.clone()).collect());
        for t in tokens {
            self.spawn_alert(t, message, None);
        }
    }

    pub fn alert_guild(self: &Arc<Self>, guild_id: &str, message: &str) {
        let tokens: Vec<String> = self.store.with(|st| {
            st.regs.iter().filter(|r| r.guilds.iter().any(|g| g.id == guild_id)).map(|r| r.fcm_token.clone()).collect()
        });
        for t in tokens {
            self.spawn_alert(t, message, Some(guild_id));
        }
    }

    fn spawn_alert(self: &Arc<Self>, token: String, message: &str, guild: Option<&str>) {
        let mut d = HashMap::new();
        d.insert("type".to_string(), "alert".to_string());
        d.insert("message".to_string(), message.to_string());
        if let Some(g) = guild {
            d.insert("guild_id".to_string(), g.to_string());
        }
        let hub = self.clone();
        tokio::spawn(async move { hub.deliver(token, d).await });
    }

    /// A new guild_messages row, from the live socket or from catch-up.
    pub async fn handle_row(self: &Arc<Self>, guild_id: &str, rec: &Value, via: &Arc<Session>) {
        let (Some(id), Some(content), Some(sender), Some(created)) =
            (val_str(&rec["id"]), rec["content"].as_str(), val_str(&rec["user_id"]), val_str(&rec["created_at"]))
        else {
            error!("row in guild {guild_id} lacks id/content/user_id/created_at, dropped");
            return;
        };
        if !self.seen.lock().unwrap().first_time(&id) {
            return;
        }
        self.last_row.store(now(), Ordering::Relaxed);
        self.store.with(|st| {
            let e = st.last_seen.entry(guild_id.to_string()).or_default();
            if created > *e {
                *e = created.clone();
            }
        });
        if let Err(e) = self.store.save() {
            error!("save last_seen: {e}");
        }

        let regs: Vec<Reg> = self.store.with(|st| {
            st.regs
                .iter()
                .filter(|r| r.user_id != sender && r.guilds.iter().any(|g| g.id == guild_id))
                .cloned()
                .collect()
        });
        let Some(ginfo) = regs.first().and_then(|r| r.guilds.iter().find(|g| g.id == guild_id)).cloned() else {
            return;
        };
        let who = self.name_for(&ginfo, &sender, via).await;
        let lower = content.to_lowercase();
        let shown: String = content.chars().take(1500).collect();
        for reg in regs {
            let mention = lower.contains(&format!("@{}", reg.username.to_lowercase()));
            let mut d = HashMap::new();
            d.insert("type".into(), "chat".into());
            d.insert("guild_id".into(), ginfo.id.clone());
            d.insert("guild_name".into(), ginfo.name.clone());
            d.insert("guild_slug".into(), ginfo.slug.clone());
            d.insert("msg_id".into(), id.clone());
            d.insert("user_id".into(), sender.clone());
            d.insert("username".into(), who.name.clone());
            // optional: absent when the sender has no picture or is unknown; the app then shows a plain icon
            if let Some(a) = &who.avatar {
                d.insert("avatar_url".into(), a.clone());
            }
            d.insert("content".into(), shown.clone());
            d.insert("created_at".into(), created.clone());
            d.insert("mention".into(), if mention { "1" } else { "0" }.into());
            let hub = self.clone();
            tokio::spawn(async move { hub.deliver(reg.fcm_token, d).await });
        }
    }

    async fn name_for(&self, g: &GuildInfo, uid: &str, via: &Arc<Session>) -> names::Member {
        let reload = {
            let m = self.names.lock().unwrap();
            match m.get(&g.id) {
                Some(gn) if gn.map.contains_key(uid) => return gn.map[uid].clone(),
                Some(gn) => gn.loaded.map_or(true, |t| t.elapsed() > NAME_RELOAD_MIN),
                None => true,
            }
        };
        if reload {
            match self.fetch_members(g, via).await {
                Ok(map) => {
                    let found = map.get(uid).cloned();
                    self.names.lock().unwrap().insert(g.id.clone(), GuildNames { map, loaded: Some(Instant::now()) });
                    if let Some(n) = found {
                        return n;
                    }
                }
                Err(e) => {
                    error!("member names for guild {}: {e}", g.slug);
                    self.names
                        .lock()
                        .unwrap()
                        .entry(g.id.clone())
                        .or_insert(GuildNames { map: HashMap::new(), loaded: None })
                        .loaded = Some(Instant::now());
                }
            }
        }
        warn!("no name for user {uid} in guild {}, sending \"{UNKNOWN_MEMBER}\"", g.slug);
        names::Member { name: UNKNOWN_MEMBER.to_string(), avatar: None }
    }

    async fn fetch_members(&self, g: &GuildInfo, via: &Arc<Session>) -> Result<HashMap<String, names::Member>, String> {
        let raw = via.raw().ok_or("listening session has no raw session yet")?;
        let cookie = names::session_cookie(&self.sb.cookie_name(), &raw);
        let resp = self
            .http
            .get(format!("{}/guilds/{}/members", self.cfg.site_url, g.slug))
            .header("RSC", "1")
            .header("Cookie", cookie)
            .send()
            .await
            .map_err(|e| format!("members request failed: {e}"))?;
        if !resp.status().is_success() {
            return Err(format!("members page returned {}", resp.status()));
        }
        let body = resp.text().await.map_err(|e| format!("members body: {e}"))?;
        let map = names::parse_members(&body);
        if map.is_empty() {
            return Err("members page parsed to zero usernames (page format changed?)".into());
        }
        Ok(map)
    }

    pub async fn catch_up(self: &Arc<Self>, guild_id: &str, via: &Arc<Session>) {
        let Some(since) = self.store.with(|st| st.last_seen.get(guild_id).cloned()) else {
            return; // first ever subscribe for this guild: nothing to catch up from
        };
        let Some(access) = via.access() else { return };
        match self.sb.messages_since(&access, guild_id, &since).await {
            Ok(rows) => {
                for r in &rows {
                    self.handle_row(guild_id, r, via).await;
                }
            }
            Err(e) => error!("catch-up guild {guild_id}: {e}"),
        }
    }

    /// Refresh succeeded or failed for good: the owner of a dead session gets a loud push.
    /// A replaced session (owner reconnected mid-refresh) ends quietly: it must not evict its successor.
    fn session_failed(self: &Arc<Self>, s: &Arc<Session>, why: &str) {
        s.kill();
        let current = {
            let mut m = self.sessions.lock().unwrap();
            let cur = m.get(&s.user_id).is_some_and(|c| Arc::ptr_eq(c, s));
            if cur {
                m.remove(&s.user_id);
            }
            cur
        };
        if !current {
            info!("replaced session for user {} ended: {why}", s.user_id);
            return;
        }
        error!("session for user {} stopped: {why}", s.user_id);
        let held = s.inner.lock().unwrap().refresh.clone();
        self.store.with(|st| {
            if st.refresh.get(&s.user_id) == Some(&held) {
                st.refresh.remove(&s.user_id);
            }
        });
        if let Err(e) = self.store.save() {
            error!("save after session failure: {e}");
        }
        self.alert_user(&s.user_id, "Pager session expired. Open KD Pager and reconnect.");
    }
}

/// Per-session refresher. Owns the refresh token; keeps the access token fresh.
async fn run_session(hub: Arc<Hub>, s: Arc<Session>) {
    let mut kill = s.kill_tx.subscribe();
    loop {
        let (wait, refresh, expires_at) = {
            let i = s.inner.lock().unwrap();
            let wait = if i.access.is_none() { 0 } else { (i.expires_at - REFRESH_LEAD_SECS - now()).max(30) };
            (wait as u64, i.refresh.clone(), i.expires_at)
        };
        tokio::select! {
            _ = tokio::time::sleep(Duration::from_secs(wait)) => {}
            _ = async { let _ = kill.wait_for(|k| *k).await; } => return,
        }
        if refresh.is_empty() {
            hub.session_failed(&s, "no refresh token held");
            return;
        }
        match hub.sb.refresh(&refresh).await {
            Ok(ns) => {
                info!("session {} refreshed, next in ~{}s", s.user_id, (ns.expires_at - now() - REFRESH_LEAD_SECS).max(30));
                // only rotate the stored token if it is still ours; a reconnect may have replaced it
                let ours = hub.store.with(|st| match st.refresh.get_mut(&s.user_id) {
                    Some(t) if *t == refresh => {
                        *t = ns.refresh_token.clone();
                        true
                    }
                    _ => false,
                });
                if ours {
                    if let Err(e) = hub.store.save() {
                        error!("persist rotated refresh token: {e}");
                    }
                } else {
                    info!("session {} replaced during refresh, not persisting its token", s.user_id);
                }
                {
                    let mut i = s.inner.lock().unwrap();
                    i.access = Some(ns.access_token.clone());
                    i.refresh = ns.refresh_token.clone();
                    i.expires_at = ns.expires_at;
                    i.raw = Some(ns.raw.clone());
                }
                s.tok_tx.send_replace(Some(ns.access_token.clone()));
                hub.reread_guilds(&s.user_id, &ns.access_token).await;
            }
            Err(RefreshErr::Rejected(m)) => {
                hub.session_failed(&s, &m);
                return;
            }
            Err(RefreshErr::Transient(m)) => {
                if now() >= expires_at {
                    hub.session_failed(&s, &format!("access token expired and refresh keeps failing: {m}"));
                    return;
                }
                warn!("session {} refresh transient error: {m}; retry in 20s", s.user_id);
                tokio::select! {
                    _ = tokio::time::sleep(Duration::from_secs(20)) => {}
                    _ = async { let _ = kill.wait_for(|k| *k).await; } => return,
                }
            }
        }
    }
}

impl Hub {
    /// After each token refresh, pick up joins/leaves of guilds.
    async fn reread_guilds(self: &Arc<Self>, uid: &str, access: &str) {
        match self.sb.guilds(access, uid).await {
            Ok(g) if !g.is_empty() => {
                self.store.with(|st| st.regs.iter_mut().filter(|r| r.user_id == uid).for_each(|r| r.guilds = g.clone()));
                if let Err(e) = self.store.save() {
                    error!("save guild list: {e}");
                }
                self.ensure_workers();
            }
            Ok(_) => warn!("user {uid} now reports zero guilds, keeping the stored list"),
            Err(e) => error!("re-read guilds for {uid}: {e}"),
        }
    }
}

#[cfg(test)]
mod key_tests {
    use super::*;

    #[test]
    fn device_keys() {
        let k = new_device_key();
        assert_eq!(k.len(), 64);
        assert_ne!(k, new_device_key());
        assert!(key_eq(&k, &k.clone()));
        assert!(!key_eq(&k, &new_device_key()));
        assert!(!key_eq("", "")); // legacy registrations without a key never match
    }
}
