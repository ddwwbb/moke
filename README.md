<div align="center">

# Moke · 墨客

**Native SSH / mosh terminal for Android** (Kotlin + Jetpack Compose)

[![CI](https://github.com/ddwwbb/moke/actions/workflows/ci.yml/badge.svg)](https://github.com/ddwwbb/moke/actions/workflows/ci.yml)
[![Release](https://img.shields.io/github/v/release/ddwwbb/moke?sort=semver)](https://github.com/ddwwbb/moke/releases)
[![minSdk](https://img.shields.io/badge/minSdk-24-blue)](#)

</div>

<p align="center"><b>English</b> · <a href="README.zh-CN.md">简体中文</a></p>

## What is this

Moke is a native SSH / mosh terminal for Android. It connects to remote servers directly inside the app — run a shell, tmux, vim, or a full-screen TUI such as Claude Code — and reuses termux's `terminal-view` / `terminal-emulator` (Apache-2.0) for terminal rendering instead of writing its own ANSI parser.

## Features

- **SSH**: password / private-key (PEM) auth; TOFU host-key verification with fingerprint confirmation on first connection; jump host (ProxyJump); per-host startup command (e.g. pick cmd, PowerShell or WSL on a Windows host); run-on-login command; keep-alive heartbeat.
- **mosh**: bundled native `mosh-client` running as a separate subprocess over a PTY; UDP roaming (survives screen-off and network switches); closing a session also shuts down the remote `mosh-server`.
- **tmux**: a panel to attach / create / rename / detach / kill remote sessions; optionally attach automatically on connect. After the app process ends, selecting a saved tmux host attaches again by its remembered session name (not the old terminal scrollback). A saved remote project directory has a separate, explicit workspace entry; the workspace name includes a path-derived suffix to avoid confusing same-named directories.
- **Files**: browse remote folders over SFTP (SSH and mosh hosts); upload / download with resume; transfers keep running in the background. Files opened from a host with a saved project directory start there. When entered from a terminal, a completed upload adds its shell-quoted remote path to that session’s editable text-block draft; it never sends or presses Enter automatically. Overwriting an existing remote file still requires confirmation. Existing remote file paths can be sent to the terminal manually.
- **Sessions & hosts**: multiple sessions stay resident, with a foreground service keeping connections alive in the background; group / sort (including manual drag-to-reorder); duplicate; per-session titles; clear ended sessions; copy connect command. Settings → Import / export hosts creates a versioned, non-secret JSON file; import previews every host and requires an Add / Replace / Skip decision per entry. Passwords, private keys, and passphrases are not exported and must be re-entered on the new device.
- **Terminal**: two-row extra keys with five selectable layouts (default, editing, function, control, custom). The custom layout lets you replace twelve keys; the two rightmost actions (more keys and text-block input) stay fixed. The full keyboard remains expandable, with lockable modifiers; three keyboard modes (character / standard / IME first) support CJK input; copy & paste (including remote OSC 52), pinch-to-zoom, full-screen TUI scrolling modes, jump-to-latest, and a status bar (protocol / host / latency).
- **Appearance**: live preview; light / dark / follow-system theme with separate terminal color schemes for each; bundled Maple Mono NF CN unhinted (Latin, CJK and Nerd glyphs), font size / line spacing / letter spacing; cursor style.
- **Security, languages & updates**: credentials are encrypted with the Android Keystore (AES-GCM) before being stored and are excluded from system backups; bilingual English / 中文, following the system language by default and switchable in Settings. About supports manual update checks and an optional pre-release channel, exclusively for `ddwwbb/moke`; successful startup checks are cached for six hours, with a dot on Settings / About when a newer release is found. Update actions open that release page, never download or install automatically. An empty release feed is reported explicitly.

## Screenshots

<div align="center">
<img src="docs/screenshot-connections.png" alt="Hosts" width="160"/>&nbsp;<img src="docs/screenshot-terminal.png" alt="Terminal running Claude Code over SSH" width="160"/>&nbsp;<img src="docs/screenshot-tmux.png" alt="tmux panel" width="160"/>&nbsp;<img src="docs/screenshot-files.png" alt="Remote files" width="160"/>&nbsp;<img src="docs/screenshot-appearance.png" alt="Appearance (light theme)" width="160"/>
<br/><sub>Hosts · Terminal (Claude Code over SSH) · tmux panel · Remote files · Appearance (light theme)</sub>
</div>

## Install

From [Releases](https://github.com/ddwwbb/moke/releases), choose the Standard or Maple APK. Both now bundle the same Maple Mono NF CN unhinted font; the existing release variants remain for upgrade compatibility.

Allow "install from unknown sources" and install. Release builds use a stable signature, so upgrades install over the top.

## Modules

| Module | Description | License |
|---|---|---|
| `app` | Product layer (Compose UI / session orchestration / transport implementations) | GPL-3.0-or-later |
| `terminal-emulator` | Terminal parsing / state core (vendored; `TerminalSession` made transport-agnostic; the PTY JNI is kept for the mosh subprocess) | Apache-2.0 |
| `terminal-view` | Terminal rendering View (vendored; additive changes for line / letter spacing and swipe scrolling) | Apache-2.0 |

## Build

Requires JDK 17 + Android SDK (compileSdk 35 / build-tools 35). Create `local.properties` at the project root pointing to the SDK: `sdk.dir=/path/to/Android/sdk`.

```sh
./gradlew assembleStandardDebug
./gradlew testDebugUnitTest testStandardDebugUnitTest
```

The mosh native artifacts are reproducibly built by [`scripts/build-mosh-native.sh`](scripts/build-mosh-native.sh) from public sources (mosh 1.4.0 + rjyo/mosh-android prebuilt libs); NDK r29 is required, and the GPLv3 binaries are not checked in.

## Feedback

Only [issue](https://github.com/ddwwbb/moke/issues)-based reports are accepted; pull requests are not accepted for now (see [CONTRIBUTING](CONTRIBUTING.md)).

Community discussion: [学AI，linuxdo](https://linux.do/)

## Acknowledgements & third-party

The terminal core reuses `terminal-emulator` / `terminal-view` from [termux/termux-app](https://github.com/termux/termux-app) (Apache-2.0, originally from [Android Terminal Emulator](https://github.com/jackpal/Android-Terminal-Emulator)). SSH transport uses [sshj](https://github.com/hierynomus/sshj). The mosh native component is based on [mobile-shell/mosh](https://github.com/mobile-shell/mosh) (GPLv3) and [rjyo/mosh-android](https://github.com/rjyo/mosh-android). The sole bundled font is [Maple Mono NF CN](https://github.com/subframe7536/maple-font) (OFL). See [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) for the full list.

## License

The moke product layer (`app/`) is released under the **GNU General Public License v3.0 or later**; see [LICENSE](LICENSE) and [COPYRIGHT.md](COPYRIGHT.md). The vendored `terminal-*` modules are Apache-2.0 (GPLv3-compatible); the mosh native component is GPLv3 and ships as a standalone executable.
