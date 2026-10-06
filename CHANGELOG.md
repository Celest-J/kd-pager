# Changelog

All notable changes to KD Pager. Each version's APK is on the [Releases](../../releases) page.

## [0.5.1] — 2026-10-06

**Install over 0.5.0 — no reinstall needed, your login and settings stay.**

### Fixed
- **App crashed on launch after logging out of KD on the web.** KD's logout signs you out everywhere, which left an empty login cookie in the app; the app tried to read it and crashed every time it opened. It now treats that as "logged out" and shows the login page.
- **Hub:** reconnecting while the hub was renewing your old session could cut off your new one and send a false "Pager session expired" alert. Fixed on the hub (already live, no app update needed for this part).

### Good to know
- Logging out of KD anywhere (web or phone) also ends your pager session — that's how KD's logout works. You'll get a "Pager session expired" notification; open KD Pager, log in, and tap **Connect Pager** again.

## [0.5.0] — 2026-10-06

First public release.
- KD as an app: login → your dashboard, pager button next to `<KD/>`
- Guild chat notifications with sender name + KD avatar, @mention channel
- Raid alerts: sign-up open + 5 min before T-0, boss portrait, Join raid / Not this time
- Raid sounds: Old Vibe (default), Moonlight Raid, Night Rider, your phone's tones, or your own mp3 (first 25 s)
