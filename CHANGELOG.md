# Changelog

All notable changes to KD Pager. Each version's APK is on the [Releases](../../releases) page.

## [0.6.5] — 2026-10-08

**Install over 0.6.0 — no new login.** Also on Google Play (closed test).

### Added
- **Google login works.** Google blocks its login inside apps, so KD Pager now opens it in a Chrome Custom Tab. KD sends the result back to the app, which finishes the login in its own window. Needs one approval on Android 12+: **Set up** → **Add link** → tick `krackeddevs.com` → **Add** (asked on first launch; GitHub/email users can skip).
- **KD links open in the app.** With that approval, krackeddevs.com links from Discord, WhatsApp or email open straight in KD Pager.

### Changed
- Account in no guild → plain message ("join a guild on KD first") instead of a raw hub error.

### Removed
- Discord from the login hints (KD no longer offers Discord login).
- Unused permissions: foreground service and battery-optimisation exemption (the hub + push do the work since 0.6.0).

## [0.6.0] — 2026-10-06

**Install over 0.5.1 — no reinstall, no new login.** Your existing pager session moves to the new setup by itself.

### Changed
- **One login.** No more second "Connect Pager" login. Log in to KD once and the pager is on. The hub now holds the only refresh token and hands the app fresh access tokens, so app and pager can't log each other out.
- **Pager state is visible.** The pager button next to `<KD/>` has a dot: green = on, grey = off. After the switch you see a one-time "Pager is on" note.
- **Settings is one control:** Pager on / off, and **Log out**.
- **Log out in the app logs out this phone only** and switches its pager off. (Logging out of KD on the web still signs you out everywhere — that's KD.)

### Removed
- The Connect screen and the on-phone listener fallback at boot.

### Hub
- New `POST /token`; sessions renew 20 min before expiry. Already live.

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
