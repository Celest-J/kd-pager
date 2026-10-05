//! Sender names. KD RLS hides other profiles over REST, so names come from the guild
//! Members page (Next.js RSC payload), fetched with a member's own session cookie.

use base64::Engine;
use std::collections::HashMap;

/// One guild member as the Members page shows them. `avatar` is None when the profile has no picture.
#[derive(Clone, Debug, PartialEq)]
pub struct Member {
    pub name: String,
    pub avatar: Option<String>,
}

/// Scan an RSC payload for `"profile":{"username":"X",...,"avatar_url":"A"},"user_id":"Y"` blocks.
pub fn parse_members(rsc: &str) -> HashMap<String, Member> {
    let mut out = HashMap::new();
    let key = "\"profile\":{\"username\":\"";
    let uid_key = "\"user_id\":\"";
    let mut rest = rsc;
    while let Some(i) = rest.find(key) {
        rest = &rest[i + key.len()..];
        let Some(end) = rest.find('"') else { break };
        let name = &rest[..end];
        let after = &rest[end..];
        // the user_id must follow before the next profile block
        let next_profile = after.find(key).unwrap_or(after.len());
        if let Some(j) = after[..next_profile].find(uid_key) {
            let tail = &after[j + uid_key.len()..];
            if let Some(e) = tail.find('"') {
                // avatar_url sits inside the profile block, before the user_id; `null` or absent = no picture
                let avatar_key = "\"avatar_url\":\"";
                let avatar = after[..j].find(avatar_key).and_then(|a| {
                    let v = &after[a + avatar_key.len()..j];
                    v.find('"').map(|q| v[..q].to_string())
                }).filter(|u| u.starts_with("https://"));
                out.insert(tail[..e].to_string(), Member { name: name.to_string(), avatar });
            }
        }
    }
    out
}

/// Supabase SSR cookie string: `base64-` + base64url(JSON), split in 3180-char chunks.
pub fn session_cookie(cookie_name: &str, raw: &serde_json::Value) -> String {
    let enc = format!(
        "base64-{}",
        base64::engine::general_purpose::URL_SAFE_NO_PAD.encode(raw.to_string())
    );
    if enc.len() <= 3180 {
        return format!("{cookie_name}={enc}");
    }
    enc.as_bytes()
        .chunks(3180)
        .enumerate()
        .map(|(i, c)| format!("{cookie_name}.{i}={}", std::str::from_utf8(c).unwrap()))
        .collect::<Vec<_>>()
        .join("; ")
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn members_scan() {
        let rsc = r#"x"profile":{"username":"alice","full_name":"A","avatar_url":"https://x/a.png"},"user_id":"u-1","role":"scout"},{"profile":{"username":"bob","avatar_url":null},"user_id":"u-2"}"#;
        let m = parse_members(rsc);
        assert_eq!(m["u-1"], Member { name: "alice".into(), avatar: Some("https://x/a.png".into()) });
        assert_eq!(m["u-2"], Member { name: "bob".into(), avatar: None });
    }

    #[test]
    fn cookie_chunks() {
        let big = serde_json::json!({"x": "a".repeat(5000)});
        let c = session_cookie("sb-p-auth-token", &big);
        assert!(c.contains("sb-p-auth-token.0=") && c.contains("sb-p-auth-token.1="));
        let small = session_cookie("sb-p-auth-token", &serde_json::json!({"a":1}));
        assert!(small.starts_with("sb-p-auth-token=base64-"));
    }
}
