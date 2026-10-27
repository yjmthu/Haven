---
layout: default
title: Haven
---

<p align="center" style="font-size:.9em">
  <a href="../">English</a> · <a href="../zh/">简体中文</a> · <a href="../es/">Español</a> · <a href="../fr/">Français</a> · <a href="../de/">Deutsch</a> · <a href="../pt/">Português</a> · <a href="../ru/">Русский</a> · <b>日本語</b> · <a href="../ko/">한국어</a> · <a href="../ar/">العربية</a> · <a href="../hi/">हिन्दी</a> · <a href="../bn/">বাংলা</a>
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
  AGPLv3 のもとでオープンソース。テレメトリなし。広告なし。アカウント不要。
</p>

<p align="center">
  <video autoplay loop muted playsinline width="720" style="max-width:100%;border-radius:8px">
    <source src="https://github.com/GlassHaven/Haven/releases/download/v5.60.4/haven-transparency.webm" type="video/webm">
    <source src="https://github.com/GlassHaven/Haven/releases/download/v5.60.4/haven-transparency.mp4" type="video/mp4">
  </video>
</p>

<p align="center" style="font-size:.85em">
  スマートフォン上で動作するライブ壁紙（<a href="https://magicfluids.com/">MagicFluids</a>）の上に重ねたターミナルの透過表示。
</p>

## ダウンロード

<table>
<thead><tr><th>チャネル</th><th>備考</th></tr></thead>
<tbody>
<tr><td><a href="https://f-droid.org/en/packages/sh.haven.app"><b>F-Droid</b></a></td><td>ソースからビルドされ、自動更新されます。ほとんどのユーザーにおすすめです。</td></tr>
<tr><td><a href="https://github.com/GlassHaven/Haven/releases/latest"><b>GitHub Releases</b></a></td><td>署名済み APK（arm64、armv7 と x86_64）。先行してリリースされ、機能は F-Droid と同じです。</td></tr>
</tbody>
</table>

どちらでも機能は同じです。最低要件は Android 8.0（API 26）。

**チャネルは 1 つを選び、そのまま使い続けてください。** GitHub Releases と F-Droid は
**別々の鍵**で署名されており（GitHub は Haven 独自のリリース鍵を、F-Droid は
アプリごとの鍵を使用します）、Android はそれらを別々のアプリとして扱います。そのため
一方からもう一方へその場で更新することはできません。チャネルを切り替えるにはアンインストール
＋再インストールが必要で、アプリのデータが消えるため、まず **設定 → バックアップ** から
バックアップを取ってください。Obtainium や直接のサイドロードは GitHub Releases の鍵を使います。

## スクリーンショット

<p align="center">
  <img src="https://raw.githubusercontent.com/GlassHaven/Haven/main/fastlane/metadata/android/en-US/images/phoneScreenshots/1_terminal.png" width="160" alt="Terminal" />
  <img src="https://raw.githubusercontent.com/GlassHaven/Haven/main/fastlane/metadata/android/en-US/images/phoneScreenshots/2_connections.png" width="160" alt="Connections" />
  <img src="https://raw.githubusercontent.com/GlassHaven/Haven/main/fastlane/metadata/android/en-US/images/phoneScreenshots/3_wayland_desktop.png" width="160" alt="Wayland desktop" />
  <img src="https://raw.githubusercontent.com/GlassHaven/Haven/main/fastlane/metadata/android/en-US/images/phoneScreenshots/4_cloud_storage.png" width="160" alt="Cloud storage" />
  <img src="https://raw.githubusercontent.com/GlassHaven/Haven/main/fastlane/metadata/android/en-US/images/phoneScreenshots/6_vnc_desktop.png" width="160" alt="VNC desktop" />
  <img src="https://raw.githubusercontent.com/GlassHaven/Haven/main/fastlane/metadata/android/en-US/images/phoneScreenshots/7_keys.png" width="160" alt="Keys" />
</p>

## ひと目で分かる機能

- **[ターミナル](../features/terminal.md)** — Mosh / Eternal Terminal / SSH、シリアル↔TCP ブリッジ付きの USB / Bluetooth / BLE シリアルコンソール、tmux セッションの復元、カスタマイズ可能なキーボードツールバー、OSC 7/8/9/52/133/777 連携。
- **[デスクトップ](../features/desktops.md)** — VNC（RFB 3.8 / VeNCrypt）、RDP（IronRDP + EGFX）、SPICE（QEMU/KVM、GLZ/QUIC）、GPU アクセラレーション対応のネイティブ Wayland コンポジタ、複数ディストリビューション対応のローカルデスクトップマネージャ。
- **[接続](../features/connections.md)** — ポートフォワーディング（-L/-R/-D/-J）、SOCKS/HTTP/Tor プロキシ、アプリごとの WireGuard と Tailscale トンネル、ポートノッキング + fwknop SPA、SSH 鍵と FIDO2。
- **[セキュリティ](../features/security.md)** — 生体認証ロック、テレメトリなし、暗号化されたバックアップ／復元（AES-256-GCM）。

[全機能一覧](../FEATURES.md)を見る。

## なぜ一つのアプリなのか？

上のリストは部品であり、重要なのはそれらをどう組み合わせるかです。次のどれもが Haven 内の一つの流れで完結します — 2 つ目のアプリも、`curl | ssh` のような呪文も不要です：

- Google Drive 上の 4K MKV をタップ → FFmpeg が HTTP 経由でトランスコードし、結果はローカルディスクに一切触れずに同じ Drive フォルダへ戻ります。
- マシンに SSH 接続し、そのポートを転送し、`localhost` を指す VNC プロファイルをタップ — デスクトップが同じアプリ内で開き、キーボードとクリップボードを共有します。
- S3 バケットからログディレクトリを切り取り、タブを切り替えて SFTP サーバーに貼り付け — rclone は可能ならサーバー側でコピーし、できなければ Haven が中継します。
- デバイス上の Linux シェルでエージェント CLI を実行 — ノートパソコンから転送した SSH エージェント経由で `push` し、その様子を同じ画面で見られます。
- PRoot ではできないこと（トレーサー、マウント、信頼できないスクリプトなど）にぶつかったら、代わりに UML ゲストを起動 — アプリプロセスとして動く本物のカーネルで、エージェントは同じ MCP ツールから操作できます。
- クラウドの動画を HLS で部屋の向こうのテレビにキャストし、スナックバーから LAN の URL をコピーして友人に送れば、その人も一緒に視聴できます。

電話機がシンクライアントであり、Haven がシンクライアント OS であり、クラウド・あなたのサーバー・あなたのエージェントがコンピューターです。幅は十分にある。要は組み合わせ（コンポジション）です。（[ビジョン](https://github.com/GlassHaven/Haven/blob/main/VISION.md)）

## 言語

12 の言語に対応：英語、中国語（簡体字）、スペイン語、ヒンディー語、アラビア語（RTL 対応）、ポルトガル語、ベンガル語、ロシア語、日本語、韓国語、フランス語、ドイツ語。UI はデバイスの言語に従います。

**[🌍 Haven の翻訳を手伝う →](../translate.html)** — アプリ内のすべての文字列を文脈付きで参照し、あなたの言語で何が不足しているかを確認し、GitHub のプルリクエストとして翻訳を提案できます。

## なぜ Haven なのか？

- **1 つのアプリでループ全体をカバー。** SSH + Mosh + ET + VNC + RDP + SFTP + クラウドストレージ + オンデバイス Linux（PRoot と完全な UML カーネル）+ メディアのトランスコードを、単一のタブバーから。
- **テレメトリなし、広告なし、アカウント不要。** 何も外部に送信されません。[プライバシーポリシー](../privacy-policy.html)をご覧ください。
- **アプリごとのトンネル。** 個々の SSH プロファイルを WireGuard や Tailscale 経由でルーティングできます。しかも Android の唯一の VPN 枠を占有*せず*に — 他のアプリは直接のネットワークを使い続けられます。
- **すべてがネイティブ。** FFmpeg、labwc、IronRDP、rclone、Linux UML ゲストカーネル、そして Kotlin 製 Reticulum トランスポートはすべてソースからコンパイルされています。Python ランタイムも Chaquopy もありません。
- **頻繁にリリース。** リリースは自動 MR により F-Droid に届きます。[リリース履歴](https://github.com/GlassHaven/Haven/releases)をご覧ください。

## ソースからのビルド

Android ターゲットを備えた [Rust](https://rustup.rs/)、`cargo-ndk`、[Go](https://go.dev/dl/) 1.26 以上、`gomobile` が必要です：

```bash
# Rust (for RDP)
rustup target add aarch64-linux-android x86_64-linux-android
cargo install cargo-ndk

# Go (for rclone cloud storage)
go install golang.org/x/mobile/cmd/gomobile@latest
go install golang.org/x/mobile/cmd/gobind@latest

git clone --recurse-submodules https://github.com/GlassHaven/Haven.git
cd Haven
./gradlew assembleDebug
```

出力は `app/build/outputs/apk/debug/haven-*-debug.apk` に生成されます。

## 不具合、要望、フィードバック

- [GitHub issues](https://github.com/GlassHaven/Haven/issues) — バグと機能リクエスト。
- [Ko-fi](https://ko-fi.com/glassontin) — 感謝を伝えたい方へ。

## ドキュメント

- [機能](../FEATURES.md) — 詳細な機能リファレンス。
- [プライバシーポリシー](../privacy-policy.html)。
- [ソースコード](https://github.com/GlassHaven/Haven)。

---

<p align="center">
  <sub>
    Haven は <a href="https://github.com/GlassHaven/Haven/blob/main/LICENSE">AGPLv3</a> のもとでオープンソースです。
    &middot; Android は Google LLC の商標です。
  </sub>
</p>
