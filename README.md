# Drosh

> Your phone is a Unix machine. Finally.

Drosh is a ground-up reimagination of what a mobile terminal should be:
agent-native, semantically aware, and built for the way people actually use
their phones. Not a Termux fork — a modern terminal environment where the
agent and the shell are the same thing.

A real Ubuntu 24.04 userspace runs on-device under PRoot, driven by the
vendored termux terminal emulator, with block-based output and an in-app
browser for links.

## Status

🚧 **Active development.** Phases 1, 2, 3 and 5 are substantially complete.
Phase 6 (agent) is started; Phase 4 (SSH) has not begun.

| Phase | Status |
|-------|--------|
| 1. Terminal Core | ✅ Done — PRoot + Ubuntu 24.04, PTY, block engine, ANSI pipeline |
| 2. UI & Session System | ✅ Done — session sidebar, Room persistence, settings, in-app browser |
| 3. Input System | ✅ Done — extra-key bar, sticky modifiers, keyboard handle, search |
| 4. SSH & Remote | ❌ Not started — module is a stub |
| 5. Safety, Polish & Distribution | 🟡 Partial — PIN lock and notification service done, CI/release set up |
| 6. Agent Intelligence | 🟡 Partial — loop and SSE streaming done, 1 of 6 tools |

## Feature Matrix

| Feature | State |
|---------|-------|
| **Terminal core** | |
| PRoot 5.2.0 + Ubuntu 24.04 rootfs | ✅ Working — asset extract, HTTP fallback, tar.gz unpacker |
| 9 setup shell scripts (shell, packages, oh-my-zsh, rootfs) | ✅ Working |
| PTY session via vendored `libtermux.so` | ✅ Working |
| Multi-session lifecycle + Room reconciliation | ✅ Working |
| ANSI strip / SGR handling | ✅ Working (`AnsiStripper`) |
| **Block output** | |
| Block repository + engine state (200-block ring) | ✅ Working |
| PromptBlock rendering + per-block menu | ✅ Working |
| Long-output collapse / "show N more" | ❌ Missing — lived in the deleted `BlockBody` |
| Jump-to-bottom affordance | ❌ Missing — lived in the deleted `JumpToBottom` |
| Real exit codes in block mode | ❌ **Broken** — always `0`, see Known Issues |
| Semantic output classification (ERROR/WARN/OK) | ❌ Missing |
| **Input** | |
| FlatKeyBar + sticky CTRL/ALT, modifier popup | ✅ Working |
| Hardware keyboard detection / auto-hide | ✅ Working |
| Block-mode raw byte injection (Ctrl+C, ESC) | ✅ Working |
| Ghost-text autocomplete | ❌ Missing |
| Shortcut overlay, voice input | ❌ Not started |
| **Session** | |
| Room-backed sessions + active-session state | ✅ Working |
| Session sidebar | ✅ Working |
| Session switcher view model | ✅ Working (sidebar is the rendered surface) |
| Grouping, favourites, drag-reorder | ❌ Not started |
| **UI** | |
| Setup wizard (4 scenes) + bootstrap stage view | ✅ Working |
| Setup recovery (retry / re-download / reset / report) | ✅ Working |
| Settings (16 persisted keys) + theme | ✅ Working |
| In-app browser (WebViewSheet) | ✅ Working |
| Draggable search bar + match highlighting | ✅ Working |
| PIN lock (SHA-256, EncryptedSharedPreferences) | ✅ Working |
| Foreground service + notifications | ✅ Working |
| DocumentsProvider | ✅ Working |
| **Agent** | |
| Bounded multi-step loop + tool-call accumulation | ✅ Working |
| OpenAI-compatible SSE adapter (tool calls, reasoning) | ✅ Working |
| `shell` tool via PRoot | ✅ Working |
| `read_file` / `write_file` / `ask_user` / `update_todo` / `web_search` | ❌ Missing |
| Diff + approve flow before write | ❌ Missing |
| Shell output streaming to the chat pane | ❌ Broken — listener never wired |
| API key persistence | ❌ In-memory only |
| **Data** | |
| Room DB v1 + session DAO | ✅ Working |
| Room migrations | ❌ None, and no destructive fallback |
| Command DNA (FTS5) | ❌ Missing |
| **SSH** | |
| SshjManager, key vault, constellation | ❌ Module is empty (0 source files) |
| **Background** | |
| WorkManager cron / agent watch | ❌ Dependencies declared, no workers |

## Stack

Versions below are the values actually pinned in `gradle/libs.versions.toml`,
which is the single source of truth.

| Component | Technology |
|-----------|------------|
| Language | Kotlin 2.2.0 |
| KSP | 2.2.0-2.0.2 |
| UI | Jetpack Compose BOM 2026.04.01 + Material 3 |
| Architecture | MVVM + Clean Architecture |
| DI | Hilt 2.57 |
| Local DB | Room 2.8.4 |
| Async | Kotlin Coroutines 1.10.2 + Flow |
| HTTP / streaming | OkHttp 4.12.0 + SSE |
| Serialization | Kotlinx Serialization 1.7.3 |
| SSH | SSHJ 0.39.0 (declared, not yet used) |
| Terminal engine | termux-view + termux-terminal-emulator, vendored |
| Linux env | PRoot 5.2.0 + Ubuntu 24.04 rootfs |
| Min SDK | 26 (Android 8.0+) |
| Target SDK | 28 — see Known Issues |
| Compile SDK | 36 |
| Java / JVM target | 17 |
| ABI | arm64-v8a only |

> `targetSdk` is deliberately still 28 so the app keeps broad OEM
> compatibility on Android 8+. It is not 36; if you are syncing from
> `docs/MEMORYBANK.md`, trust this table and `libs.versions.toml`.

## Architecture

```
app/            → MainActivity + NavHost, DI seam, TerminalService
ui/             → Compose screens, ViewModels (never imports data/agent/terminal/ssh)
domain/         → Models, use cases, repository interfaces (pure Kotlin, no Android)
data/           → Repository implementations, Room, DataStore, DI bindings
agent/          → AgentRuntime, ToolRegistry, ProviderAdapter, ShellTool
terminal/       → TerminalManager, UbuntuBootstrap, ProotRunner, vendored termux
ssh/            → Empty. Reserved for SSHJ manager and key vault
core/           → Locale, constants, extensions
design-system/  → DroshColors, typography, dropdown
build-logic/    → Gradle convention plugins
```

Strict layering: `ui/` consumes only `domain/` types. `domain/` is pure
Kotlin. See `docs/AGENT.md` for the full rules.

### Rendering pipeline

```
PTY output → AnsiStripper → block boundary detection → BlockRepository → Compose BlockList
```

## Known Issues

- **Block mode always reports exit code 0.** `terminal/BlockEngineWire.kt`
  hardcodes `onCommandCompleted(exitCode = 0)`, so a failing command renders
  as successful and `BlockState.Error` is unreachable.
- **targetSdk 28** on a compileSdk 36 project. AndroidX APIs gated behind
  SDK 29+ are unavailable, and `ExpiredTargetSdkVersion` is suppressed in
  `app/build.gradle.kts` to keep lint quiet.
- **Room has no migration path.** `DroshDatabase` is v1 with neither
  migrations nor `fallbackToDestructiveMigration()`; a schema bump without
  adding them will crash on upgrade.
- **Agent shell output never reaches the chat pane.** `ShellTool.onOutputLine`
  is never wired, so `AgentEvent.BashStarted/BashOutput/BashCompleted` are
  consumed by the ViewModel but never emitted.
- **Agent `fetchModels()` sends no Authorization header** and will 401 against
  OpenRouter.
- **PIN lock hashes a 4-digit code without a salt.** Only 10,000 candidates;
  acceptable as a UI lock, not as authentication.
- **`ssh/` is an empty module** that `app` still depends on, pulling in SSHJ,
  biometric and security-crypto for nothing.
- **Release builds are signed with the debug keystore** and run with
  `isMinifyEnabled = false`.
- **`docs/MEMORYBANK.md` §1 lists the license as MIT.** It is GPLv3; see below.

## Build

```bash
./gradlew assembleDebug        # arm64-v8a APK
./gradlew testDebugUnitTest    # 38 unit tests across domain + terminal
./gradlew spotlessCheck
./gradlew detekt
```

No local build is required to validate a change — CI runs
`Build Debug`, `Tests` and `Release` on every push.

## Tests

38 unit tests, all in pure-JVM modules:

| Suite | Tests | Coverage |
|-------|-------|----------|
| `CommandBoundaryDetectorTest` | 10 | prompt detection across shell and zsh patterns |
| `BlockTest` | 9 | elapsed time, network delta, exit code per block state |
| `InputDispatcherTest` | 7 | sticky modifiers, control-byte translation, ordering |
| `AnsiStripperTest` | 5 | CSI SGR, OSC, 2-byte escape, zsh prompt codes |
| `BlockEngineScenarioTest` | 4 | transcript-shaped scenarios |
| `NetworkDeltaTest` | 3 | traffic detection |

No tests yet for the agent, data, ui or app modules.

## License

GPLv3 — see [LICENSE](LICENSE).

This project uses code from [Termux](https://github.com/termux), which is
licensed under the GNU General Public License v3.0. As required by the terms
of that license, Drosh is also licensed under GPLv3 to ensure license
compatibility and to preserve the same freedoms for all users.
