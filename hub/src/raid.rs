//! Raid alerts. KD's /raid page (public, no login) names the next raid and its times:
//! title `"rf-title","children":"Raid 04: Melissa"`, `"raidId":"<uuid>"`, and a hint
//! `"rf-hint","children":["Sun 11 Oct, 21:00 MYT"," · sign-up opens Sat 10 Oct, 21:00 MYT"`.
//! Two pushes per raid, each sent once (flags persist in the store): sign-up open, and T-5 min.

use crate::hub::Hub;
use crate::sb::now;
use crate::store::{Boss, RaidRec};
use std::collections::{HashMap, HashSet};
use std::sync::Arc;
use std::time::Duration;

const TICK: Duration = Duration::from_secs(30);
const REFRESH_SECS: i64 = 30 * 60;
const READY_LEAD_SECS: i64 = 5 * 60;
const SIGNUP_LEAD_SECS: i64 = 24 * 3600; // KD: "Sign-up opens 24 hours before the raid"
const MYT_OFFSET_SECS: i64 = 8 * 3600;

#[derive(Debug, PartialEq)]
pub struct Parsed {
    pub id: String,
    pub title: String,
    pub starts_at: i64,
    pub signup_at: i64,
    pub boss: Option<Boss>,
}

fn num_after(s: &str, key: &str) -> Option<i64> {
    let i = s.find(key)? + key.len();
    let digits: String = s[i..].chars().take_while(|c| c.is_ascii_digit() || *c == '-').collect();
    digits.parse().ok()
}

/// `"rl-boss-art"` block: `"title":"Melissa"`, width 128, `url(/raid/boss-ring.png)`, `backgroundSize":"512px 128px"`,
/// `backgroundPosition":"-0px 0"`, `hue-rotate(40deg)`; tagline in the next `"rd-note","children":[...]`.
/// Any piece missing -> None (the push then goes out without a face, and the log says why).
fn parse_boss(rsc: &str, site: &str) -> Option<Boss> {
    let i = rsc.find("\"rl-boss-art\"")?;
    let art = &rsc[i..(i + 900).min(rsc.len())];
    let name = str_after(art, "\"title\":\"")?.to_string();
    let path = str_after(art, "url(").map(|u| u.split(')').next().unwrap_or(u).to_string())?;
    let frame = num_after(art, "\"width\":")? as u32;
    let sheet_w = num_after(art, "\"backgroundSize\":\"")? as u32;
    let x = num_after(art, "\"backgroundPosition\":\"")?.unsigned_abs() as u32;
    let hue = num_after(art, "hue-rotate(").unwrap_or(0) as i32; // no filter = original colours
    let note = rsc[i..].find("\"rd-note\",\"children\":[").map(|j| {
        let rest = &rsc[i + j + "\"rd-note\",\"children\":[".len()..];
        let list = &rest[..rest.find(']').unwrap_or(0)];
        list.split("\",\"").map(|p| p.trim_matches('"')).collect::<String>()
    }).unwrap_or_default();
    let sprite = if path.starts_with("http") { path } else { format!("{site}{path}") };
    Some(Boss { name, note, sprite, sheet_w, frame, x, hue })
}

fn str_after<'a>(s: &'a str, key: &str) -> Option<&'a str> {
    let i = s.find(key)? + key.len();
    let rest = &s[i..];
    Some(&rest[..rest.find('"')?])
}

/// Days since 1970-01-01 for a civil date (Howard Hinnant's algorithm).
fn days_from_civil(y: i64, m: i64, d: i64) -> i64 {
    let y = if m <= 2 { y - 1 } else { y };
    let era = if y >= 0 { y } else { y - 399 } / 400;
    let yoe = y - era * 400;
    let mp = (m + 9) % 12;
    let doy = (153 * mp + 2) / 5 + d - 1;
    let doe = yoe * 365 + yoe / 4 - yoe / 100 + doy;
    era * 146097 + doe - 719468
}

fn year_of(unix: i64) -> i64 {
    // good enough to pick a year: walk from the 1970 estimate
    let mut y = 1970 + unix / 31_556_952;
    while days_from_civil(y, 1, 1) * 86400 > unix {
        y -= 1;
    }
    while days_from_civil(y + 1, 1, 1) * 86400 <= unix {
        y += 1;
    }
    y
}

/// "Sun 11 Oct, 21:00 MYT" -> unix seconds. The page has no year: take the one that lands
/// closest to `now` (a raid is never more than half a year away).
fn parse_myt(s: &str, now: i64) -> Result<i64, String> {
    let bad = || format!("unreadable raid time {s:?}");
    let p: Vec<&str> = s.split_whitespace().collect();
    if p.len() != 5 || p[4] != "MYT" {
        return Err(bad());
    }
    let day: i64 = p[1].parse().map_err(|_| bad())?;
    const MONTHS: [&str; 12] = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"];
    let mon = MONTHS.iter().position(|m| *m == p[2].trim_end_matches(',')).ok_or_else(bad)? as i64 + 1;
    let (h, mi) = p[3].split_once(':').ok_or_else(bad)?;
    let (h, mi): (i64, i64) = (h.parse().map_err(|_| bad())?, mi.parse().map_err(|_| bad())?);
    let y0 = year_of(now);
    (y0 - 1..=y0 + 1)
        .map(|y| days_from_civil(y, mon, day) * 86400 + h * 3600 + mi * 60 - MYT_OFFSET_SECS)
        .min_by_key(|t| (t - now).abs())
        .ok_or_else(bad)
}

pub fn parse(rsc: &str, now: i64, site: &str) -> Result<Parsed, String> {
    let title = str_after(rsc, "\"rf-title\",\"children\":\"").ok_or("no rf-title on /raid (page format changed?)")?;
    let id = str_after(rsc, "\"raidId\":\"").ok_or("no raidId on /raid (page format changed?)")?;
    // first hint whose first child is a "... MYT" time is the raid start
    let key = "\"rf-hint\",\"children\":[\"";
    let mut rest = rsc;
    while let Some(i) = rest.find(key) {
        rest = &rest[i + key.len()..];
        let Some(end) = rest.find('"') else { break };
        let Ok(starts_at) = parse_myt(&rest[..end], now) else { continue };
        let tail = &rest[end..rest.find(']').unwrap_or(rest.len())];
        let signup_at = match tail.find("sign-up opens ") {
            Some(j) => {
                let t = &tail[j + "sign-up opens ".len()..];
                parse_myt(&t[..t.find('"').unwrap_or(t.len())], now)?
            }
            None => starts_at - SIGNUP_LEAD_SECS,
        };
        let boss = parse_boss(rsc, site);
        if boss.is_none() {
            warn!("raid: no boss art parsed on /raid, pushes go out without a face");
        }
        return Ok(Parsed { id: id.to_string(), title: title.to_string(), starts_at, signup_at, boss });
    }
    Err("no \"<day> <date> <mon>, HH:MM MYT\" hint on /raid (page format changed?)".into())
}

async fn fetch(hub: &Hub) -> Result<Parsed, String> {
    let resp = hub
        .http
        .get(format!("{}/raid", hub.cfg.site_url))
        .header("RSC", "1")
        .send()
        .await
        .map_err(|e| format!("/raid request failed: {e}"))?;
    if !resp.status().is_success() {
        return Err(format!("/raid returned {}", resp.status()));
    }
    parse(&resp.text().await.map_err(|e| format!("/raid body: {e}"))?, now(), &hub.cfg.site_url)
}

fn push_all(hub: &Arc<Hub>, kind: &str, r: &RaidRec) {
    let tokens: HashSet<String> = hub.store.with(|st| st.regs.iter().map(|x| x.fcm_token.clone()).collect());
    info!("raid {}: sending {kind} to {} phone(s)", r.title, tokens.len());
    for t in tokens {
        let mut d = HashMap::new();
        d.insert("type".to_string(), "raid".to_string());
        d.insert("kind".to_string(), kind.to_string());
        d.insert("raid_id".to_string(), r.id.clone());
        d.insert("title".to_string(), r.title.clone());
        d.insert("starts_at".to_string(), r.starts_at.to_string());
        if let Some(b) = &r.boss {
            d.insert("boss_name".to_string(), b.name.clone());
            d.insert("boss_note".to_string(), b.note.clone());
            d.insert("boss_sprite".to_string(), b.sprite.clone());
            d.insert("boss_sheet_w".to_string(), b.sheet_w.to_string());
            d.insert("boss_frame".to_string(), b.frame.to_string());
            d.insert("boss_x".to_string(), b.x.to_string());
            d.insert("boss_hue".to_string(), b.hue.to_string());
        }
        let hub = hub.clone();
        tokio::spawn(async move { hub.deliver(t, d).await });
    }
}

pub fn spawn(hub: Arc<Hub>) {
    tokio::spawn(async move {
        let mut fetched_at = 0i64;
        loop {
            let t = now();
            if t - fetched_at >= REFRESH_SECS {
                fetched_at = t;
                match fetch(&hub).await {
                    Ok(p) => {
                        let changed = hub.store.with(|st| {
                            let same = st.raid.as_ref().is_some_and(|r| r.id == p.id);
                            match st.raid.as_mut() {
                                Some(r) if same => {
                                    let moved = r.starts_at != p.starts_at || r.signup_at != p.signup_at || r.title != p.title || r.boss != p.boss;
                                    (r.title, r.starts_at, r.signup_at, r.boss) = (p.title.clone(), p.starts_at, p.signup_at, p.boss.clone());
                                    moved
                                }
                                _ => {
                                    st.raid = Some(RaidRec {
                                        id: p.id.clone(),
                                        title: p.title.clone(),
                                        starts_at: p.starts_at,
                                        signup_at: p.signup_at,
                                        sent_signup: false,
                                        sent_ready: false,
                                        boss: p.boss.clone(),
                                    });
                                    true
                                }
                            }
                        });
                        if changed {
                            info!("raid: {} · sign-up {} · start {} (unix)", p.title, p.signup_at, p.starts_at);
                            if let Err(e) = hub.store.save() {
                                error!("save raid: {e}");
                            }
                        }
                    }
                    Err(e) => error!("raid: {e}"),
                }
            }

            let due = hub.store.with(|st| {
                let r = st.raid.as_mut()?;
                if !r.sent_signup && t >= r.signup_at && t < r.starts_at {
                    r.sent_signup = true;
                    return Some(("signup", r.clone()));
                }
                // a hub that wakes up well past T-0 stays quiet: "get ready" after the start is noise
                if !r.sent_ready && t >= r.starts_at - READY_LEAD_SECS && t < r.starts_at + 60 {
                    r.sent_ready = true;
                    return Some(("ready", r.clone()));
                }
                None
            });
            if let Some((kind, r)) = due {
                if let Err(e) = hub.store.save() {
                    error!("save raid flags: {e}");
                }
                push_all(&hub, kind, &r);
            }
            tokio::time::sleep(TICK).await;
        }
    });
}

#[cfg(test)]
mod tests {
    use super::*;

    // 2026-10-06 04:00 MYT
    const NOW: i64 = 1_791_230_400;

    #[test]
    fn myt_times() {
        // Sun 11 Oct 2026 21:00 MYT = 13:00 UTC
        assert_eq!(parse_myt("Sun 11 Oct, 21:00 MYT", NOW).unwrap(), days_from_civil(2026, 10, 11) * 86400 + 13 * 3600);
        assert!(parse_myt("TONIGHT'S RAID", NOW).is_err());
    }

    #[test]
    fn raid_page() {
        let rsc = r#"x{"className":"rf-title","children":"Raid 04: Melissa"}..{"className":"rl-boss-art","children":["$","i",null,{"title":"Melissa","role":"img","style":{"width":128,"height":128,"backgroundImage":"url(/raid/boss-ring.png)","backgroundSize":"512px 128px","backgroundPosition":"-0px 0","filter":"hue-rotate(40deg)"}}]}],["$","div",null,{"className":"rd-note","children":["1999 · ","Here is that document you asked for."]}]..{"className":"rf-hint","children":["Sun 11 Oct, 21:00 MYT"," · sign-up opens Sat 10 Oct, 21:00 MYT","",""]}..{"raidId":"939d8ee8-6c50-4fbf-812c-60f3cc7349bd","joined":false}"#;
        let p = parse(rsc, NOW, "https://kd").unwrap();
        assert_eq!(p.boss, Some(Boss { name: "Melissa".into(), note: "1999 · Here is that document you asked for.".into(),
            sprite: "https://kd/raid/boss-ring.png".into(), sheet_w: 512, frame: 128, x: 0, hue: 40 }));
        assert_eq!(p.title, "Raid 04: Melissa");
        assert_eq!(p.id, "939d8ee8-6c50-4fbf-812c-60f3cc7349bd");
        assert_eq!(p.starts_at, days_from_civil(2026, 10, 11) * 86400 + 13 * 3600);
        assert_eq!(p.signup_at, p.starts_at - 24 * 3600);
    }
}
