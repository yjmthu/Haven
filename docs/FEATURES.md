---
layout: default
title: Features
---

# Features

Haven is one app for SSH terminals, remote desktops, files and SSH keys. This
build keeps six surfaces: connections, terminal, desktop, keys, files and
settings. The [landing page](index.md) has a short summary.

- **Terminal** — SSH with a configurable keyboard toolbar, per-tab tmux-aware
  session handling, OSC 7/8/9/52/133/777 integration, and a paperclip attach
  flow that uploads a file (or a photo) and pastes the remote path at the
  cursor.
- **Desktops** — VNC (RFB 3.8 / VeNCrypt) and RDP (IronRDP + EGFX) viewers in a
  multi-session tabbed workspace, both able to ride an SSH tunnel.
- **Files** — a unified browser for SFTP/SCP and the device's own storage, with
  cross-profile copy/move, permissions and ownership editing, folder upload,
  and age encryption/decryption.
- **Keys** — SSH key management (import, generate, export, hardware/security-key
  and FIDO2 authenticators), TOTP secrets, and OpenSSH certificate handling.
- **[Connections & networking](features/connections.md)** — host-key TOFU, port
  forwarding (-L/-R/-D/-J), SOCKS/HTTP/Tor proxies, per-app WireGuard and
  Tailscale tunnels, and `haven://connect` deep links.
- **[Security & privacy](features/security.md)** — biometric lock, no telemetry,
  encrypted backup/restore (AES-256-GCM).
- **[Automation (Tasker / MacroDroid)](features/automation.md)** — a native
  Tasker/Locale/MacroDroid plugin action to run a command on a saved SSH
  server, with host-variable substitution and output returned as
  `%hstdout`/`%hstderr`/`%hexit`.
- **[Languages & translation](features/i18n.md)** — 12 languages, follows the
  device locale, in-app language picker; contribute corrections or whole new
  languages from the web translation page, no setup required.

**Languages** — the UI is translated into 12 languages (English, Chinese, Spanish, Hindi, Arabic with RTL, Portuguese, Bengali, Russian, Japanese, Korean, French, German) and follows the device language. See [Languages & translation](features/i18n.md), or jump straight to the [translation page](translate.html).

---

[Vision](https://github.com/GlassHaven/Haven/blob/main/VISION.md) · [Translate](translate.html) · [Backup file format](backup-format.md) · [Privacy policy](privacy-policy.html)
