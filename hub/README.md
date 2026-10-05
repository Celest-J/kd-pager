# kd-pager-hub

Rust relay. Each user hands their own KD session to the hub. The hub listens to KD Realtime as that user
and pages every other registered member of the guild through FCM (data-only, priority HIGH).

Endpoints (everything else is a bare 404):
- `POST /register` connect: `{"fcm_token", "kd_session": {access_token, refresh_token, expires_at, user_id}}`; rotation: `{"fcm_token", "old_fcm_token"}` (no session; 409 if old token unknown = re-run Connect). Both or neither: 400.
  `kd_session` also accepts the cookie form `"base64-<base64url JSON>"`. Unknown fields are rejected. Body cap 32 KB.
- `POST /unregister` `{"fcm_token": "..."}`
- `GET /health` counts only (sessions, guilds, sockets, registrations, last_row_at).

## Build and test
```
cargo build --release          # ~2.6 MB, no unsafe
cargo test                     # crypto, session parsing, member-name scan, cookie chunking
```

## Run locally against the mock
`dev/mock.py` fakes Supabase (auth, REST, realtime websocket), the GCP metadata server, the KD members page and FCM on one port.
Two users: bearer `tok-alice` and `tok-bob`. A joined socket gets one chat row from bob after 2 s. FCM sends are printed.
```
python3 dev/mock.py 9400 &
export KDPAGER_SB_URL=http://127.0.0.1:9400 KDPAGER_ANON_KEY=anon-test KDPAGER_FCM_PROJECT=proj-test \
  KDPAGER_LISTEN=127.0.0.1:9401 KDPAGER_DATA_DIR=$(mktemp -d) \
  KDPAGER_KEY_SOURCE=env-dev KDPAGER_DEV_KEY_HEX=$(printf 'ab%.0s' $(seq 32)) \
  KDPAGER_SITE_URL=http://127.0.0.1:9400 KDPAGER_FCM_URL=http://127.0.0.1:9400 KDPAGER_METADATA_URL=http://127.0.0.1:9400
./target/release/kd-pager-hub &
curl -X POST localhost:9401/register -d '{"fcm_token":"alice-device-token-0123456789","kd_session":{"access_token":"tok-alice","refresh_token":"refresh-1","expires_at":'$(( $(date +%s)+3600 ))'}}'
```
Expected: mock prints `FCM send ... type=chat ... mention=1 user='bob'`.
Restarting the hub with the same `KDPAGER_DATA_DIR` and key restores the session from the encrypted state file.

## Point it at the real thing
1. `KDPAGER_SB_URL`, `KDPAGER_ANON_KEY`, `KDPAGER_FCM_PROJECT` as in `deploy/hub.env.example`; leave the endpoint overrides unset.
2. Key: create a Secret Manager secret holding 64 hex chars (`openssl rand -hex 32`), grant the VM service account `roles/secretmanager.secretAccessor`, set `KDPAGER_KEY_SOURCE=gcp-secret` and `KDPAGER_KEY_SECRET`.
3. FCM: the VM service account needs `roles/firebasecloudmessaging.admin` (already granted) and the `cloud-platform` scope.
4. A real session: the app sends the user's KD session to `/register`. For a manual test, read the `sb-nxukkhyjasusqbzhkqdv-auth-token` cookie, join `.0`/`.1` chunks and send the string as `kd_session`.
5. After registering, the client must delete its local KD cookie. Never call signOut: that revokes the hub's refresh token.

## Install (prod)
```
useradd --system --home /var/lib/kd-pager --shell /usr/sbin/nologin kdpager
install -m 0755 target/release/kd-pager-hub /usr/local/bin/
install -d -m 0750 -o root -g kdpager /etc/kd-pager && install -m 0640 -g kdpager deploy/hub.env.example /etc/kd-pager/hub.env   # then edit
install -m 0644 deploy/kd-pager-hub.service /etc/systemd/system/ && systemctl enable --now kd-pager-hub
```
Add `deploy/Caddyfile.snippet` to the Caddyfile. Binary is glibc-dynamic: build on the target OS or the same distro release.

## Behaviour notes
- Realtime: `vsn=2.0.0`, topic `realtime:guild_chat:<guild_id>`, INSERT on `public.guild_messages`. One live socket per guild; other registered sessions are spares. No working listener for 90 s: every member of that guild gets an `alert` push.
- Refresh: 5 min before expiry. Rejected refresh token: session dropped, owner gets an `alert` push. Network errors retry every 20 s until the access token expires.
- Push data: `type, guild_id, guild_name, guild_slug, msg_id, user_id, username, content, created_at, mention`. Alerts: `type=alert, message[, guild_id]`.
- Sender name unresolved: `username` is `unknown member`.
- State file `state.kdp`: XChaCha20-Poly1305, holds refresh tokens, registrations, last-seen timestamps. Access tokens stay in memory. Tokens and message text are never logged.
- First subscribe to a guild skips catch-up (no `last_seen` yet). Later subscribes catch up from `last_seen`.
