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

A real Ubuntu 24.04 userspace runs on-device under PRoot, driven by
[Ghostty](https://ghostty.org)'s VT core — the same engine the desktop
terminal uses — with block-based output and an in-app browser for links.

## Status

🚧 **Active development, pre-release.** Everything below is in the tree and
compiles. The terminal, the gestures and the session system have been run on a
physical device; the agent and the SSH surface have not. The changelog in
[`CHANGELOG.md`](CHANGELOG.md) is the per-release record, and the design
decisions live in [`docs/MEMORYBANK.md`](docs/MEMORYBANK.md).

## What's in it

**The terminal.** A full Ubuntu 24.04 userspace under PRoot, the Ghostty VT core
behind a purpose-built OpenGL ES 3.0 renderer, PTY sessions that survive
rotation, OSC 133 shell integration per shell (the prompt marks, the exit
status, the working directory), sub-line scrolling with momentum, mouse
reporting, kitty graphics, wide characters, a block mode with per-command
prompt cards, and text selection with a menu and draggable handles.

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
| Ghostty VT core (C ABI) + own GLES 3.0 renderer | ✅ cells, glyph atlas, cursor, overscan |
| Wide characters (CJK, emoji) | ✅ the engine's own cell widths |
| Kitty graphics protocol | ✅ placements, layers, textures |
| Mouse reporting (SGR 1006) | ✅ click, drag, wheel, with a setting |
| Terminal bell | ✅ a fading accent wash, a buzz, a beep, a notification |
| OSC 0/2 title, OSC 9/777 notifications | ✅ |
| Terminal selection | ✅ double tap for a word, a menu, draggable handles |
| Background image behind the terminal | ✅ |
| Terminal core | ✅ the renderer is ours; the engine is Ghostty |
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
| Terminal engine | Ghostty v1.4.0 `libghostty-vt` (C ABI), driven from `native/` |
| Terminal renderer | OpenGL ES 3.0, written for this app |
| Linux env | PRoot 5.2.0 + Ubuntu 24.04 rootfs |
| Min SDK / Target SDK / Compile SDK | 26 / 28 / 36 |
| ABI | arm64-v8a only |

> `targetSdk` is deliberately 28 to keep broad OEM compatibility on Android 8+.
> The lint suppression for it is in `android/app/build.gradle.kts`.

## Architecture

The tree is split by build system: everything the APK ships is under `android/`,
the Rust half under `native/`.

```
android/                → the Gradle build; `settings.gradle.kts` and `gradlew` live here
  app/                  → MainActivity + NavHost, DI seam, TerminalService
  ui/                   → Compose screens, ViewModels (never imports data/agent/terminal/ssh)
  domain/               → Models, use cases, repository interfaces (pure Kotlin, no Android)
  data/                 → Repository implementations, Room, DataStore, DI bindings
  terminal/             → the terminal: the engine's Kotlin side (JNI, sessions,
                          the layout and the input the surface owns)
  linux/                → the Linux userland: rootfs, bootstrap, archive, guest launchers
  features/
    agent/              → AgentRuntime, ToolRegistry, provider adapters, eight tools
    ssh/                → SSHJ session bridge
    editor/             → Sora editor wrapper, TextMate grammars
  core/                 → Locale, constants, extensions
  theme/                → DroshColors, typography — the Compose theme
  build-logic/          → Gradle convention plugins

native/                  → the Cargo crate; `cargo test` runs from here
  src/frame.rs           → the frame: the engine's cells and images in one buffer
  src/session.rs         → a session: the engine's terminal, the PTY, the shell
  src/pty.rs             → the PTY and the child process
  src/shell.rs           → the shell tap: the prompt, the command, the exit status
  src/bell.rs            → the engine's callbacks: the bell, the notifications, the prompts
  src/clipboard.rs       → the engine's clipboard callback
  src/jni/               → the JNI entry points, one per Kotlin declaration
  ghostty/               → the engine's headers and its shared library
```

The engine's library is fetched by `tools/fetch-ghostty-vt.sh` and verified
against the release's own SHA256SUMS; `native/ghostty/` holds what it
fetched. The build produces `libdrosh.so`, and the APK ships it next to
`libghostty-vt.so`.

The guest runtime — [chroot-ng](https://github.com/mmuhofy/chroot-ng) — is a
third-party binary, so it ships in the repository at
`android/linux/src/main/assets/libchrootng.so`, the build that published it
(`v1.2.0`, SHA-256 `29473e36…`). It does not change with our code, and a
build that has to fetch it is a build that can be made without one. CI checks
the hash rather than fetching it, so the bytes in an APK are always the ones
that release published.

It travels as an **asset** and the bootstrap stages it into the app's own
storage, the way proot is staged. A `lib/` entry that nothing loads is not
extracted by the installer, so a runtime that lives there is in the APK and
nowhere else — and the guest has nothing to run.

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
cd native
cargo test --no-default-features   # the engine-free half, on the host
```

The engine's library is not in the repository: `tools/fetch-ghostty-vt.sh`
downloads it and checks it against the release's SHA256SUMS. CI runs that
before it builds.

**A local build without the native half has no terminal engine at all.** The
engine's library is loaded when the first session starts, and a missing one is
an `UnsatisfiedLinkError` before the first frame — which shows up as a white
rectangle where the terminal should be. The fix is the library itself: build
the APK in CI, or cross-compile the native half for arm64 first:

```bash
# the same thing CI does, with the NDK's toolchain
cd native
./tools/fetch-ghostty-vt.sh        # or: ../tools/fetch-ghostty-vt.sh
export ANDROID_NDK_HOME=/path/to/android-ndk
export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android26-clang"
export CC_aarch64_linux_android="$CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER"
export AR_aarch64_linux_android="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-ar"
cargo build --release --target aarch64-linux-android
cp target/aarch64-linux-android/release/libdrosh.so \
   ../android/terminal/src/main/jniLibs/arm64-v8a/
```

CI runs `Build Debug` and `Unit Tests` on every push and pull request, and
`Build Release` on tags.

## Reading the engine's log on a device

The native half logs under `drosh-native`; the app logs under `DroshApp`.

```bash
adb logcat -s drosh-native DroshApp
```

The lines that matter when the terminal is blank:

| Line | What it means |
|------|---------------|
| `the engine's library could not be loaded` | `libdrosh.so` is not in the APK — the build skipped its cross-compile |
| `the guest is not installed` | there is no rootfs, so the terminal has nothing to run |
| `the shell could not be started` | the guest's shell failed; the reason is on the line after it |

## Tests

741 unit tests across the pure-JVM modules — domain, data, terminal, agent
and ui — and the Rust half's own tests, covering the block engine's boundary
detection, the input dispatcher, the ANSI pipeline, the agent loop and its
four provider adapters, the zoom and scroll gestures, the renderer's colour
rules, the room migrations, and the engine's own cell and image handling.

## Known issues

- **Block mode always reports exit code 0.** `android/terminal/BlockEngineWire.kt`
  hardcodes `onCommandCompleted(exitCode = 0)`, so a failing command renders
  as successful.
- **`targetSdk` 28** on a compileSdk 36 project; AndroidX APIs gated behind
  SDK 29+ are unavailable.
- **Release builds are signed with the committed debug key** and run with
  `isMinifyEnabled = false`.
- **The agent and the SSH surface have not been run on a device.** The
  terminal, its gestures and the session system have been; the agent's tool
  loop and an SSH session have not.
- **Agent chats share one guest home directory** — per-chat terminals are
  not wired.

## License

Drosh is split-licensed, and the map is in
[`LICENSE.md`](LICENSE.md):

- The terminal module is Drosh's own Kotlin plus the Rust half in `native/`,
  and the engine it drives (Ghostty) is MIT — so the reason the APK was GPL
  is gone, and the APK's own licence is now a decision for you. The map is in
  [`LICENSE.md`](LICENSE.md).
- The Drosh-authored modules are **Apache-2.0**, so the design system and the
  agent adapters can be reused without copyleft.

Third-party components — Ghostty (MIT), PRoot, sora-editor (LGPL), the
TextMate grammars (MIT), and the Geist, Inter and JetBrains Mono fonts (OFL) —
are named with their licenses in `LICENSE.md`, `editor/NOTICE.md` and
`CREDITS.md`.

## Documentation

- [`docs/README.md`](docs/README.md) — the living documents
- [`CHANGELOG.md`](CHANGELOG.md) — release record
- [`CONTRIBUTING.md`](CONTRIBUTING.md) — build, commit, changelog and release rules
- [`docs/TRANSLATION-MANIFEST.md`](docs/TRANSLATION-MANIFEST.md) — the string inventory, per language
