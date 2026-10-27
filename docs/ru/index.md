---
layout: default
title: Haven
---

<p align="center" style="font-size:.9em">
  <a href="../">English</a> · <a href="../zh/">简体中文</a> · <a href="../es/">Español</a> · <a href="../fr/">Français</a> · <a href="../de/">Deutsch</a> · <a href="../pt/">Português</a> · <b>Русский</b> · <a href="../ja/">日本語</a> · <a href="../ko/">한국어</a> · <a href="../ar/">العربية</a> · <a href="../hi/">हिन्दी</a> · <a href="../bn/">বাংলা</a>
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
  Открытый исходный код под лицензией AGPLv3. Без телеметрии. Без рекламы. Без аккаунта.
</p>

<p align="center">
  <video autoplay loop muted playsinline width="720" style="max-width:100%;border-radius:8px">
    <source src="https://github.com/GlassHaven/Haven/releases/download/v5.60.4/haven-transparency.webm" type="video/webm">
    <source src="https://github.com/GlassHaven/Haven/releases/download/v5.60.4/haven-transparency.mp4" type="video/mp4">
  </video>
</p>

<p align="center" style="font-size:.85em">
  Прозрачность терминала поверх живых обоев устройства (<a href="https://magicfluids.com/">MagicFluids</a>), работающих на телефоне.
</p>

## Загрузка

<table>
<thead><tr><th>Канал</th><th>Примечания</th></tr></thead>
<tbody>
<tr><td><a href="https://f-droid.org/en/packages/sh.haven.app"><b>F-Droid</b></a></td><td>Собирается из исходного кода, обновляется автоматически, рекомендуется для большинства пользователей.</td></tr>
<tr><td><a href="https://github.com/GlassHaven/Haven/releases/latest"><b>Релизы GitHub</b></a></td><td>Подписанные APK (arm64, armv7 &amp; x86_64), выходят первыми, те же возможности, что и у F-Droid.</td></tr>
</tbody>
</table>

Возможности одинаковы в обоих случаях. Минимальная версия Android 8.0 (API 26).

**Выберите один канал и оставайтесь на нём.** Релизы GitHub и F-Droid подписаны
**разными ключами** (GitHub использует собственный релизный ключ Haven; F-Droid подписывает
своим попакетным ключом), поэтому Android рассматривает их как разные приложения — обновить на месте
с одного на другое нельзя. Смена канала требует удаления + повторной установки, что
стирает данные приложения, поэтому сначала сделайте резервную копию через **Настройки → Резервная копия**. Obtainium и прямая
установка APK отслеживают ключ релизов GitHub.

## Снимки экрана

<p align="center">
  <img src="https://raw.githubusercontent.com/GlassHaven/Haven/main/fastlane/metadata/android/en-US/images/phoneScreenshots/1_terminal.png" width="160" alt="Terminal" />
  <img src="https://raw.githubusercontent.com/GlassHaven/Haven/main/fastlane/metadata/android/en-US/images/phoneScreenshots/2_connections.png" width="160" alt="Connections" />
  <img src="https://raw.githubusercontent.com/GlassHaven/Haven/main/fastlane/metadata/android/en-US/images/phoneScreenshots/3_wayland_desktop.png" width="160" alt="Wayland desktop" />
  <img src="https://raw.githubusercontent.com/GlassHaven/Haven/main/fastlane/metadata/android/en-US/images/phoneScreenshots/4_cloud_storage.png" width="160" alt="Cloud storage" />
  <img src="https://raw.githubusercontent.com/GlassHaven/Haven/main/fastlane/metadata/android/en-US/images/phoneScreenshots/6_vnc_desktop.png" width="160" alt="VNC desktop" />
  <img src="https://raw.githubusercontent.com/GlassHaven/Haven/main/fastlane/metadata/android/en-US/images/phoneScreenshots/7_keys.png" width="160" alt="Keys" />
</p>

## Обзор

- **[Терминал](../features/terminal.md)** — Mosh / Eternal Terminal / SSH, последовательные консоли USB / Bluetooth / BLE с мостом последовательный порт↔TCP, восстановление сессий tmux, настраиваемая панель инструментов клавиатуры, интеграция OSC 7/8/9/52/133/777.
- **[Рабочие столы](../features/desktops.md)** — VNC (RFB 3.8 / VeNCrypt), RDP (IronRDP + EGFX), SPICE (QEMU/KVM, GLZ/QUIC), нативный композитор Wayland с GPU-ускорением и менеджер локальных рабочих столов для нескольких дистрибутивов.
- **[Соединения](../features/connections.md)** — проброс портов (-L/-R/-D/-J), прокси SOCKS/HTTP/Tor, попакетные туннели WireGuard и Tailscale, port knocking + fwknop SPA, ключи SSH и FIDO2.
- **[Безопасность](../features/security.md)** — биометрическая блокировка, без телеметрии, зашифрованное резервное копирование/восстановление (AES-256-GCM).

Откройте [полный список возможностей](../FEATURES.md).

## Почему одно приложение?

Список выше — это детали; смысл в том, как они сочетаются. Каждый из этих сценариев — единый поток внутри Haven, без второго приложения, без заклинаний вроде `curl | ssh`:

- Коснитесь 4K-файла MKV в Google Drive → FFmpeg перекодирует его по HTTP, и результат возвращается в ту же папку Drive, ни разу не касаясь локального диска.
- Подключитесь по SSH к машине, пробросьте её порт, коснитесь профиля VNC, указывающего на `localhost` — рабочий стол откроется в том же приложении, с общими клавиатурой и буфером обмена.
- Вырежьте каталог логов из бакета S3, переключите вкладку и вставьте его на SFTP-сервер — rclone сделает копирование на стороне сервера, когда это возможно, иначе Haven передаст данные через себя.
- Запустите CLI своего агента в Linux-оболочке на устройстве; он выполняет `push` через SSH-агент, проброшенный с вашего ноутбука, пока вы наблюдаете на том же экране.
- Столкнулись с тем, что не по силам PRoot — трассировщиком, монтированием, скриптом, которому вы не доверяете — загрузите вместо этого гостевую систему UML: настоящее ядро как процесс приложения, которым агент может управлять через те же инструменты MCP.
- Транслируйте облачное видео на телевизор в другом конце комнаты по HLS, скопируйте URL локальной сети из снэкбара и отправьте другу, чтобы он тоже мог смотреть.

Телефон — это тонкий клиент, Haven — операционная система тонкого клиента, а облако, ваши серверы и ваши агенты — это компьютер. Ширины достаточно; всё дело в композиции. ([Видение](https://github.com/GlassHaven/Haven/blob/main/VISION.md).)

## Языки

Доступно на 12 языках: английском, китайском (упрощённом), испанском, хинди, арабском (с поддержкой RTL), португальском, бенгальском, русском, японском, корейском, французском и немецком. Интерфейс следует языку устройства.

**[🌍 Помогите перевести Haven →](../translate.html)** — просматривайте каждую строку приложения с контекстом, смотрите, чего не хватает в вашем языке, и предлагайте перевод через pull request на GitHub.

## Почему Haven?

- **Одно приложение охватывает весь цикл.** SSH + Mosh + ET + VNC + RDP + SFTP + облачное хранилище + Linux на устройстве (PRoot и полноценное ядро UML) + перекодирование медиа — из одной панели вкладок.
- **Без телеметрии, без рекламы, без аккаунта.** Ничего не отправляется на сервер. См. [политику конфиденциальности](../privacy-policy.html).
- **Попакетные туннели.** Направляйте отдельные профили SSH через WireGuard или Tailscale *без* захвата единственного VPN-слота Android — другие приложения продолжают использовать прямую сеть.
- **Всё нативное.** FFmpeg, labwc, IronRDP, rclone, гостевое ядро Linux UML и транспорт Reticulum на Kotlin — всё скомпилировано из исходного кода: ни среды выполнения Python, ни Chaquopy.
- **Частые выпуски.** Релизы попадают в F-Droid через автоматический MR. См. [историю релизов](https://github.com/GlassHaven/Haven/releases).

## Сборка из исходного кода

Требуется [Rust](https://rustup.rs/) с целевыми платформами Android, `cargo-ndk`, [Go](https://go.dev/dl/) 1.26+ и `gomobile`:

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

Результат появляется в `app/build/outputs/apk/debug/haven-*-debug.apk`.

## Проблемы, запросы, отзывы

- [GitHub issues](https://github.com/GlassHaven/Haven/issues) — ошибки и запросы возможностей.
- [Ko-fi](https://ko-fi.com/glassontin) — если хотите сказать спасибо.

## Документация

- [Возможности](../FEATURES.md) — подробный справочник по возможностям.
- [Политика конфиденциальности](../privacy-policy.html).
- [Исходный код](https://github.com/GlassHaven/Haven).

---

<p align="center">
  <sub>
    Haven — открытый исходный код под лицензией <a href="https://github.com/GlassHaven/Haven/blob/main/LICENSE">AGPLv3</a>.
    &middot; Android является товарным знаком Google LLC.
  </sub>
</p>
