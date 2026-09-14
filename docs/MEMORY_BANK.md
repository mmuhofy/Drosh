# Drosh — Memory Bank
_Last updated: 2026-09-08_

## Project
Android terminal emulator with block-based output, PRoot Linux environment, and agent intelligence. Package: `com.iris.drosh`.

## Stack
Kotlin 2.2.0, Compose BOM 2026.04.01, Hilt 2.57, Room 2.8.4, Kotlinx Serialization 1.7.x, WorkManager 2.11.2, SSHJ 0.38.x, OkHttp 4.12.x.

## Architecture Rules
- `domain/` — pure Kotlin, no Android imports, repository interfaces, use cases, `UrlDetector`, `IrisTool`
- `data/` — implements `domain` interfaces (Room DAOs, DataStore, SSHJ adapters)
- `terminal/` — depends on `domain/` only; termux-view JNI bridge, block engine, semantic parser, `TerminalManager`, `ProotRunner`
- `agent/` — depends on `domain/` only (DI via Hilt)
- `ui/` — depends on `domain/` and `core/` only; never imports `terminal/`, `data/`, `agent/`, or `ssh/` directly
- `ssh/` — isolated module, accessed via `domain/` use cases
- `di/` — Hilt modules only, no logic
- `util/` — constants, extensions

## Completed Features

### Terminal Link Detection
- `UrlDetector.kt` in `domain/` with `findUrls(text)`, `matches(word)`, `normalizeUrlFromWord(word)`
- `BlockBody.kt` — URLs rendered with `IrisPrimary` color + `TextDecoration.Underline`, clickable
- `BlockCard.kt` — `onUrlClick: (String) -> Unit` parameter
- `BlockTerminalView.kt` — `onUrlClick` parameter passed through
- `TerminalViewClientImpl.kt` — `onSingleTapUp` uses `UrlDetector.matches()` + `onUrlClick` instead of `Intent.ACTION_VIEW`
- `TerminalScreen.kt` — `browserUrl` state + `WebViewSheet`
- `WebViewSheet.kt` in `ui/browser/` — `ModalBottomSheet` + `WebView` via `AndroidView`

### PRoot Start Command Setting
- `SettingsRepository` interface + `SettingsRepositoryImpl` — `prootStartCommand: StateFlow<String>` + `setProotStartCommand(command: String)`
- `ProotRunner.build(startCommand: String = "")` — splits start command into argv tokens
- `TerminalManager` — collects `prootStartCommand` Flow via `onEach` + `launchIn`
- `TerminalViewModel` — `prootStartCommand` StateFlow + `setProotStartCommand()`
- `SettingsViewModel` — `prootStartCommand` StateFlow + `setProotStartCommand()`
- `SettingsScreen.kt` — "Gelişmiş" section with `ProotStartCommandRow` (experimental/dangerous warning banner)

### Foreground Service (Termux pattern)
- `TerminalService.kt` (app module) — `LifecycleService`, `@AndroidEntryPoint`, injects `TerminalManager` + `SessionRepository`
- `TerminalManager.sessionCountFlow: StateFlow<Int>` — reactive session count for notification updates
- `startForeground(NOTIFICATION_ID, notification)` with `dataSync` type (API 34+)
- `START_NOT_STICKY` — no auto-recreate on system kill
- Notification: session count, clickable PendingIntent → MainActivity, Exit action button
- `observeSessionCount()` — updates notification on count change; auto-stops when sessions empty + shouldExit
- `AndroidManifest.xml` — `<service>` declaration + `FOREGROUND_SERVICE` permission
- `IrisApplication.onCreate()` — starts service via `ContextCompat.startForegroundService()`

### Command Completion Notification + Toast (ENV injection)
- `writeShellHooksFile()` creates hooks in app's `filesDir/iris_hooks.zsh`, returns `Map("ENV" to path)`
- `ProotRunner.build()` merges `environmentHooks` into PRoot env — `$ENV` sourced by zsh for interactive shells
- zsh sources `$ENV` after `.zshrc` — user's `.zshrc` never modified, `.zshrc` template has hooks removed
- `preexec` captures `$1` + start time; `precmd` captures `$?` (BEFORE `date`), computes elapsed, writes to completion file
- Completion file: `/data/data/com.iris.drosh/files/drosh_cmd_complete` (accessible from PRoot via `/data` bind mount)
- `TerminalService.startCompletionMonitor()` — 500ms polling, `RandomAccessFile` for incremental reads
- Completion notification: non-ongoing, `IMPORTANCE_HIGH` channel, shows command + status + duration
- Completion toast: `Toast.LENGTH_SHORT` at `Gravity.TOP or Gravity.END` (top-right corner)
- Works in both Classic and Block Engine modes (shell-based detection, not output parsing)

## Active
- ENV-injected shell hooks — verify `$ENV` is sourced by zsh under PRoot on first session
- Verify completion file receives `command|elapsed_sec|exit_code` lines after running commands in shell

## Terminal Color Scheme (experimental)
- `TerminalColorScheme.updateWith(props: Properties)` — keys: `foreground`, `background`, `color0`..`color15` (ANSI indices), `cursor`. Parses hex (#RRGGBB) or `rgb:...`.
- `color6` = ANSI cyan → maps to accent color in iris shell theme.
- `TerminalColors.COLOR_SCHEME.updateWith(props)` updates `mDefaultColors` (IntArray); `mColors.reset()` copies into `mCurrentColors`.
- `TerminalView.updateColors(props)` = `COLOR_SCHEME.updateWith(props)` + `mColors.reset()` + `invalidate()`.
- `TerminalViewModel.colorProps: StateFlow<Properties>` combines `SettingsRepository.terminalBgColor`→`background`, `terminalTextColor`→`foreground`, `accentColor`→`color6`.
- Live: `TerminalViewHost.LaunchedEffect(colorProps) { tv.updateColors(props) }`.

## Open Decisions
- (none currently)
