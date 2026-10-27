---
layout: default
title: Haven
---

<p align="center" style="font-size:.9em">
  <b>English</b> · <a href="zh/">简体中文</a> · <a href="es/">Español</a> · <a href="fr/">Français</a> · <a href="de/">Deutsch</a> · <a href="pt/">Português</a> · <a href="ru/">Русский</a> · <a href="ja/">日本語</a> · <a href="ko/">한국어</a> · <a href="ar/">العربية</a> · <a href="hi/">हिन्दी</a> · <a href="bn/">বাংলা</a>
</p>

<p align="center">
  <a href="https://github.com/GlassHaven/Haven/releases/latest">
    <img src="https://img.shields.io/github/v/release/GlassHaven/Haven?style=flat-square" alt="Release" />
  </a>
  <a href="https://f-droid.org/en/packages/sh.haven.app">
    <img src="https://img.shields.io/f-droid/v/sh.haven.app?style=flat-square" alt="F-Droid" />
  </a>
  <a href="https://github.com/GlassHaven/Haven/actions/workflows/ci.yml?query=branch%3Amain">
    <img src="https://img.shields.io/github/check-runs/GlassHaven/Haven/main?style=flat-square&label=build" alt="Build" />
  </a>
  <a href="https://github.com/GlassHaven/Haven/blob/main/LICENSE">
    <img src="https://img.shields.io/github/license/GlassHaven/Haven?style=flat-square" alt="License" />
  </a>
  <img src="https://img.shields.io/badge/Android-8.0%2B-3ddc84?style=flat-square&logo=android&logoColor=white" alt="Android 8.0+" />
</p>

<p align="center">
  Open source under AGPLv3. No telemetry. No ads. No account.
</p>

<p align="center">
  <video autoplay loop muted playsinline width="720" style="max-width:100%;border-radius:8px">
    <source src="https://github.com/GlassHaven/Haven/releases/download/v5.60.4/haven-transparency.webm" type="video/webm">
    <source src="https://github.com/GlassHaven/Haven/releases/download/v5.60.4/haven-transparency.mp4" type="video/mp4">
  </video>
</p>

<p align="center" style="font-size:.85em">
  Terminal transparency over a live device wallpaper (<a href="https://magicfluids.com/">MagicFluids</a>), running on the phone.
</p>

## Download

<table>
<thead><tr><th>Channel</th><th>Notes</th></tr></thead>
<tbody>
<tr><td><a href="https://f-droid.org/en/packages/sh.haven.app"><b>F-Droid</b></a></td><td>Built from source, auto-updated, recommended for most users.</td></tr>
<tr><td><a href="https://github.com/GlassHaven/Haven/releases/latest"><b>GitHub Releases</b></a></td><td>Signed APKs (arm64, armv7 &amp; x86_64), released first, same features as F-Droid.</td></tr>
</tbody>
</table>

Same features either way. Minimum Android 8.0 (API 26).

**Pick one channel and stay on it.** GitHub Releases and F-Droid are signed with
**different keys** (GitHub uses Haven's own release key; F-Droid signs with its
per-app key), so Android treats them as separate apps — you can't update in place
from one to the other. Switching channels needs an uninstall + reinstall, which
clears app data, so back up first via **Settings → Backup**. Obtainium and direct
sideloads track the GitHub Releases key.

## Screenshots

<p align="center">
  <img src="https://raw.githubusercontent.com/GlassHaven/Haven/main/fastlane/metadata/android/en-US/images/phoneScreenshots/1_terminal.png" width="160" alt="Terminal" />
  <img src="https://raw.githubusercontent.com/GlassHaven/Haven/main/fastlane/metadata/android/en-US/images/phoneScreenshots/2_connections.png" width="160" alt="Connections" />
  <img src="https://raw.githubusercontent.com/GlassHaven/Haven/main/fastlane/metadata/android/en-US/images/phoneScreenshots/3_wayland_desktop.png" width="160" alt="Wayland desktop" />
  <img src="https://raw.githubusercontent.com/GlassHaven/Haven/main/fastlane/metadata/android/en-US/images/phoneScreenshots/4_cloud_storage.png" width="160" alt="Cloud storage" />
  <img src="https://raw.githubusercontent.com/GlassHaven/Haven/main/fastlane/metadata/android/en-US/images/phoneScreenshots/6_vnc_desktop.png" width="160" alt="VNC desktop" />
  <img src="https://raw.githubusercontent.com/GlassHaven/Haven/main/fastlane/metadata/android/en-US/images/phoneScreenshots/7_keys.png" width="160" alt="Keys" />
</p>

## At a glance

- **[Terminal](features/terminal.md)** — SSH with a configurable keyboard toolbar, per-tab session handling, OSC 7/8/9/52/133/777 integration, and a paperclip attach flow.
- **[Desktops](features/desktops.md)** — VNC (RFB 3.8 / VeNCrypt) and RDP (IronRDP + EGFX) viewers in a multi-session tabbed workspace, both able to ride an SSH tunnel.
- **[Connections](features/connections.md)** — port forwarding (-L/-R/-D/-J), SOCKS/HTTP/Tor proxies, per-app WireGuard & Tailscale tunnels, SSH keys & FIDO2.
- **[Security](features/security.md)** — biometric lock, no telemetry, encrypted backup/restore (AES-256-GCM).

Browse the [full feature index](FEATURES.md).

## Why one app

The list above is the parts; the point is how they compose. Each of these is one flow inside Haven — no second app, no `curl | ssh` incantation:

- SSH to a box, forward its port, tap the VNC profile that targets `localhost` — the desktop opens in the same app, keyboard and clipboard shared.

The phone is the thin client, Haven is the thin-client OS, and the cloud, your servers, and your agents are the computer. Width is sufficient; composition is the point. ([Vision](https://github.com/GlassHaven/Haven/blob/main/VISION.md).)

## Languages

Available in 12 languages: English, Chinese (simplified), Spanish, Hindi, Arabic (with RTL support), Portuguese, Bengali, Russian, Japanese, Korean, French, and German. The UI follows the device language.

**[🌍 Help translate Haven →](translate.html)** — browse every in-app string with context, see what's missing in your language, and propose a translation as a GitHub pull request.

## Why Haven?

- **One app covers the whole loop.** SSH terminal, VNC and RDP desktops, SFTP files and SSH keys, from a single tab bar.
- **No telemetry, no ads, no account.** Nothing is phoned home. See the [privacy policy](privacy-policy.html).
- **Per-app tunnels.** Route individual SSH profiles through WireGuard or Tailscale *without* taking Android's one VPN slot — other apps keep using the direct network.
- **Native everything.** IronRDP is compiled from source; the tunnel bridges are Go compiled with gomobile. No Python runtime, no Chaquopy.
- **Ships often.** Releases reach F-Droid via an automated MR. See the [release history](https://github.com/GlassHaven/Haven/releases).

## Build from source

Requires [Rust](https://rustup.rs/) with Android targets, `cargo-ndk`, [Go](https://go.dev/dl/) 1.26+, and `gomobile`:

```bash
# Rust (for RDP)
rustup target add aarch64-linux-android x86_64-linux-android
cargo install cargo-ndk

# Go (for the tunnel bridges)
go install golang.org/x/mobile/cmd/gomobile@latest
go install golang.org/x/mobile/cmd/gobind@latest

git clone --recurse-submodules https://github.com/GlassHaven/Haven.git
cd Haven
./gradlew assembleDebug
```

Output lands in `app/build/outputs/apk/debug/haven-*-debug.apk`.

## Issues, requests, feedback

- [GitHub issues](https://github.com/GlassHaven/Haven/issues) — bugs and feature requests.
- [Ko-fi](https://ko-fi.com/glassontin) — if you want to say thanks.

## Documentation

- [Features](FEATURES.md) — detailed feature reference.
- [Privacy policy](privacy-policy.html).
- [Source code](https://github.com/GlassHaven/Haven).

---

<p align="center">
  <sub>
    Haven is open source under <a href="https://github.com/GlassHaven/Haven/blob/main/LICENSE">AGPLv3</a>.
    &middot; Android is a trademark of Google LLC.
  </sub>
</p>
