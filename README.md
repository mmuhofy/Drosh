# Drosh

[![Build Debug](https://github.com/mmuhofy/Drosh/actions/workflows/debug.yml/badge.svg)](https://github.com/mmuhofy/Drosh/actions/workflows/debug.yml)
[![Unit Tests](https://github.com/mmuhofy/Drosh/actions/workflows/tests.yml/badge.svg)](https://github.com/mmuhofy/Drosh/actions/workflows/tests.yml)
[![License: GPL-3.0](https://img.shields.io/badge/License-GPL%20v3-blue.svg)](LICENSE.md)
[![Min SDK: 26](https://img.shields.io/badge/Min%20SDK-26%20%28Android%208.0%29-green.svg)](https://developer.android.com/about/versions/oreo)
[![Strings: 10 languages](https://img.shields.io/badge/Strings-10%20languages-orange.svg)](docs/TRANSLATION-MANIFEST.md)

> Your phone is a Unix machine. Finally.

Drosh is a ground-up reimagination of what a mobile terminal should be:
agent-native, semantically aware, and built for the way people actually use
their phones. Not a Termux fork — a modern terminal environment where the
agent and the shell are the same thing.

A real Ubuntu 24.04 userspace runs on-device under PRoot, driven by the
vendored termux terminal emulator, with block-based output and an in-app
browser for links.

## Status

🚧 **Active development, pre-release.** Everything below is in the tree and
compiles; nothing has been verified on a physical device yet. The changelog
in [`CHANGELOG.md`](CHANGELOG.md) is the per-release record, and the design
decisions live in [`docs/MEMORYBANK.md`](docs/MEMORYBANK.md).

## What's in it

**The terminal.** A full Ubuntu 24.04 userspace under PRoot, a vendored termux
engine, PTY sessions that survive rotation, OSC 133 shell integration per
shell, sub-line scrolling with momentum, a block mode with per-command
prompt cards, and text selection that works the way it does on a desktop.

**The agent.** Eight tools (`shell`, `read_file`, `write_file`, `grep`,
`move_file`, `ask_user`, `update_todo`, `web_search`), four provider
protocols (OpenAI-compatible, Anthropic, Gemini, OpenAI Responses), a
225-provider catalog, per-model reasoning effort, API keys in an encrypted
vault. Shell output streams into the chat live, writes show a diff and wait
for approval, and the chat history is durable with automatic compaction.

**SSH.** Hosts and keys managed in-app, trust-on-first-use host key
verification, an encrypted credential vault, and SSHJ sessions bridged into
the real terminal — an SSH connection is a tab, not a bolt-on.

**Everything else.** A native code editor (`editor <path>`) with syntax
highlighting, split panes with a floating window you can drag over other
apps, smooth pinch-to-zoom anchored on the row under your fingers, split
panes, a project system that groups sessions, a theme store with installable
themes and background images, a 4-digit PIN lock, and a UI that speaks ten
languages completely.

## Feature matrix

| Area | State |
|---|---|
| **Terminal core** | |
| PRoot 5.2.0 + Ubuntu 24.04 rootfs | ✅ asset extract, HTTP fallback, tar.gz unpacker |
| PTY sessions, multi-session lifecycle, Room reconciliation | ✅ |
| ANSI/SGR pipeline, OSC 133 + OSC 7 parsing | ✅ |
| Sub-line scrolling with momentum | ✅ |
| **Block output** | |
| Block repository + engine, prompt cards, per-block menu | ✅ |
| Long-output collapse / "show N more" | ❌ not built |
| Real exit codes in block mode | ❌ **broken** — always `0`, see Known Issues |
| Semantic classification (ERROR/WARN/OK) | ❌ not built |
| **Agent** | |
| Bounded multi-step loop, retries, approval parking | ✅ |
| Four provider protocols, 225-provider catalog, per-model effort | ✅ |
| Eight tools, live shell streaming, diff + approve before writes | ✅ |
| Durable transcripts with compaction | ✅ |
| Agent chats share the guest home directory | ❌ per-chat terminals not wired |
| **SSH** | |
| Hosts, keys, TOFU verification, credential vault, interactive sessions | ✅ |
| Mosh, SFTP, jump hosts, port forwarding | ❌ not built |
| **Editor** | ✅ `editor <path>`, syntax highlighting, discard confirmation |
| **Input** | ✅ FlatKeyBar with blur, sticky modifiers, key repeat, IME-aware |
| **UI** | |
| Session sidebar, search, ended-session handling | ✅ |
| Split panes (top-to-bottom, five snaps) + floating window | ✅ |
| Pinch-to-zoom (0.1sp, focal anchoring, size chip) | ✅ |
| In-app browser (WebViewSheet) with nav, progress, dropdown | ✅ |
| Settings by category, theme store, backgrounds, font packs | ✅ |
| 4-digit PIN lock, foreground service, DocumentsProvider | ✅ |
| **Localization** | ✅ **ten languages, 413 strings, zero missing** |
| **Not built** | Command DNA (history search), ghost-text autocomplete |

## Stack

The values in [`android/gradle/libs.versions.toml`](android/gradle/libs.versions.toml) are the source of truth; this table
is a summary of it.

| Component | Technology |
|-----------|------------|
| Language | Kotlin 2.2.0, Java 17 |
| UI | Jetpack Compose BOM 2026.04.01 + Material 3 |
| Architecture | MVVM + Clean Architecture (10 Gradle modules) |
| DI | Hilt 2.58 |
| Local DB | Room 2.8.5 (schema v6, migrations 1→6) |
| Async | Coroutines 1.11.0 + Flow |
| HTTP / streaming | OkHttp 5.4.0 + SSE |
| SSH | SSHJ 0.41.1 |
| Terminal engine | termux-view + termux-terminal-emulator, vendored in-tree |
| Linux env | PRoot 5.2.0 + Ubuntu 24.04 rootfs |
| Min SDK / Target SDK / Compile SDK | 26 / 28 / 36 |
| ABI | arm64-v8a only |

> `targetSdk` is deliberately 28 to keep broad OEM compatibility on Android 8+.
> The lint suppression for it is in `android/app/build.gradle.kts`.

## Architecture

The tree is split by build system: everything the APK ships is under `android/`,
the Rust core under `rust/`.

```
android/                → the Gradle build; `settings.gradle.kts` and `gradlew` live here
  app/                  → MainActivity + NavHost, DI seam, TerminalService
  ui/                   → Compose screens, ViewModels (never imports data/agent/terminal/ssh)
  domain/               → Models, use cases, repository interfaces (pure Kotlin, no Android)
  data/                 → Repository implementations, Room, DataStore, DI bindings
  terminal/             → the terminal: the Rust core's Kotlin side (JNI, GL
                          renderer, input, view) and the sessions that drive it
  linux/                → the Linux userland: rootfs, bootstrap, archive, guest launchers
  features/
    agent/              → AgentRuntime, ToolRegistry, provider adapters, eight tools
    ssh/                → SSHJ session bridge
    editor/             → Sora editor wrapper, TextMate grammars
  core/                 → Locale, constants, extensions
  theme/                → DroshColors, typography — the Compose theme
  build-logic/          → Gradle convention plugins

rust/                   → the Cargo workspace; `cargo test` runs from here
  crates/drosh-jni/     → the JNI boundary: `JNI_OnLoad`, `ffi/`, error codes → `libdrosh_core.so`
  crates/drosh-session/ → the engine: alacritty's Term, its event loop, selection/search/url/tint
  crates/drosh-pty/     → raw libc PTY and the child process (D8)
  crates/drosh-bridge/  → the Rust → Kotlin wire formats; no JNI, by manifest
  crates/drosh-font/    → rustybuzz shaping + swash rasterisation
```

The guest runtime — [chroot-ng](https://github.com/mmuhofy/chroot-ng) — is not
in this repository. It is fetched from its own release, pinned by tag and
SHA-256, and packaged into the APK by CI. A local build has to fetch it too:

```bash
tools/fetch-chroot-ng.sh
```

Without it the APK has no chroot-ng, and choosing it in the setup wizard gives
a session that dies with `exec(".../libchrootng.so"): No such file or
directory` — the release build fetches it, so this only bites a local build
that skipped the step.

Project names are unchanged by the split — `:app`, `:data`, … — so a module is
`android/data/` and is still depended on as `project(":data")`.

Strict layering: `ui/` consumes only `domain/` types; `domain/` is pure
Kotlin. The rules are in [`docs/AGENT.md`](docs/AGENT.md) and
[`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md).

```
PTY output → AnsiStripper → block boundary detection → BlockRepository → Compose BlockList
```

## Install

Release APKs are attached to
[GitHub Releases](https://github.com/mmuhofy/Drosh/releases) alongside a
`.sha256` checksum:

```bash
sha256sum -c drosh-release-arm64-v8a-release.apk.sha256
adb install drosh-release-arm64-v8a-release.apk
```

All builds — CI and local — are signed with the debug key committed to the
repository, so an official APK installs over your own build without an
uninstall. That is a deliberate single-identity choice; it is recorded as a
known issue, not a secret.

## Build

```bash
cd android
./gradlew assembleDebug        # debug APK, arm64-v8a
./gradlew testDebugUnitTest    # the unit test suite
```

```bash
cd rust
cargo test                     # the engine crate, on the host
```

The Rust core is built for arm64 only by CI, which drops `libdrosh_core.so`
into `android/terminal/engine/src/main/jniLibs/arm64-v8a/` before Gradle runs.

CI runs `Build Debug` and `Unit Tests` on every push and pull request, and
`Build Release` on tags.

## Tests

464 unit tests across the pure-JVM modules — domain, data, terminal, agent
and ui — covering the block engine's boundary detection, the input
dispatcher, the ANSI pipeline, the agent loop and its four provider
adapters, the zoom gesture maths, and the room migrations.

## Known issues

- **Block mode always reports exit code 0.** `android/terminal/BlockEngineWire.kt`
  hardcodes `onCommandCompleted(exitCode = 0)`, so a failing command renders
  as successful.
- **`targetSdk` 28** on a compileSdk 36 project; AndroidX APIs gated behind
  SDK 29+ are unavailable.
- **Release builds are signed with the committed debug key** and run with
  `isMinifyEnabled = false`.
- **Nothing has been verified on a physical device.** CI builds and
  unit-tests; a real device has not confirmed the terminal, the agent, or
  any gesture yet.
- **Agent chats share one guest home directory** — per-chat terminals are
  not wired.

## License

Drosh is split-licensed, and the map is in
[`LICENSE.md`](LICENSE.md):

- The APK and `:terminal` are **GPL-3.0** — the terminal module contains
  modified Termux code, and Termux is GPLv3.
- The Drosh-authored modules are **Apache-2.0**, so the design system and the
  agent adapters can be reused without copyleft.

Third-party components — Termux, PRoot, sora-editor (LGPL), the TextMate
grammars (MIT), and the Geist, Inter and JetBrains Mono fonts (OFL) — are
named with their licenses in `LICENSE.md`, `editor/NOTICE.md` and
`CREDITS.md`.

## Documentation

- [`docs/README.md`](docs/README.md) — the living documents
- [`CHANGELOG.md`](CHANGELOG.md) — release record
- [`CONTRIBUTING.md`](CONTRIBUTING.md) — build, commit, changelog and release rules
- [`docs/TRANSLATION-MANIFEST.md`](docs/TRANSLATION-MANIFEST.md) — the string inventory, per language
