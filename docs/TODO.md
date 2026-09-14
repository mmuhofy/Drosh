# Drosh — TODO
_Last updated: 2026-07-16_

> **Development Philosophy:** Terminal first. Agent Intelligence last.
> Build the best terminal on Android. Then make it intelligent.

---

## Phase 1 — Terminal Core
*Goal: A working terminal. Type a command, get output.*

### Project Setup
- [ ] Gradle KTS + Version Catalog + Hilt
- [ ] Package: `dev.drosh`
- [ ] Min SDK 26, Target SDK 36
- [ ] Kotlin 2.3.20
- [ ] Compose BOM 2026.04.01
- [ ] Base architecture — MVVM + Clean Architecture layers
- [ ] Visual identity — colors, typography, design system

### Terminal Engine (Port from Iris Code)
- [ ] Port termux-view JNI (`libtermux.so`)
- [ ] Port termux terminal emulator (vendored)
- [ ] Port `TerminalManager.kt`
- [ ] Port `ProotRunner.kt`
- [ ] Port `UbuntuBootstrap.kt`
- [ ] Basic terminal screen — full screen, raw PTY output
- [ ] ANSI color rendering
- [ ] Unicode + emoji support
- [x] Persistent session — Foreground Service, phantom killer proof
- [x] Command completion: shell hooks (preexec/precmd) → file → service reads → notification + toast

### Linux Environment
- [x] PRoot v5.2.0 static binary download
- [x] Ubuntu 24.04 rootfs download + extraction
- [x] Bootstrap progress UI — step-by-step stepper (5 rows + live log card)
- [x] Onboarding wizard (3 sahne: Welcome / Architecture / Ready; Pager yok, Compose-canvas fake terminal backdrop, gerçek Termux-mount HAYIR) — DataStore firstLaunch flag
- [x] Setup recovery UI — Retry / Re-download / Reset / Report
- [x] Zsh as default shell
- [x] Oh My Zsh + zsh-autosuggestions + zsh-syntax-highlighting
- [x] Package install: zsh, git, curl, nano, vim, tree
- [x] Shell selector — zsh/bash in Settings
- [x] resolv.conf, apt sources, bashrc/zshrc setup
- [x] PRoot start command — customizable via Settings (experimental warning banner)

### Basic Input
- [ ] Text input field — basic, working
- [ ] Send command on Enter
- [ ] Hardware keyboard support

---

## Phase 2 — UI & Session System
*Goal: Beautiful, navigable session experience.*

### Phase 2 — UI & Session System
*Goal: Beautiful, navigable session experience.*

### Block-Based Output
- [x] `BlockEngine.kt` — every command = one block
- [x] Block structure: command line + output + footer (exit code, duration)
- [x] Short output (≤8 lines): fully expanded
- [x] Long output (>8 lines): collapsed + "Show X more ↓"
- [x] Very long (50+ lines): "Open fullscreen ↑"
- [x] Copy button per block
- [x] Long press block → copy, share, pin, search
- [x] Terminal link detection — `UrlDetector` in `domain/` shared by both block + classic paths
- [x] URL rendering with `DroshPrimary` color + `TextDecoration.Underline` in `BlockBody`
- [x] URL tap handling via `onUrlClick` callback → `WebViewSheet` in-app browser (bottom sheet)

### Semantic Output Highlighting
- [x] `SemanticParser.kt` — pattern detection
- [x] ERROR / FATAL → red
- [x] WARNING / WARN → gold
- [x] SUCCESS / DONE → green
- [x] BUILD / COMPILE → blue
- [x] Support: Gradle, npm, cargo, git, apt, docker, pip, adb, logcat

### Session System
_Scope (revised 2026-07-28): TopBar modal session switcher only — no Home screen, no groups, no search, no navigator. Session naming kept._

- [ ] Session list screen — real terminal snapshot per card (DEFERRED — out of Phase 2 scope)
- [ ] Session preview swipe — Shared Element Transition (DEFERRED)
- [ ] Card → terminal morph animation (DEFERRED)
- [ ] Session naming (e.g. `prod-server`, `dev-local`) — kept
- [ ] Session groups + favorites + recents (DEFERRED)
- [ ] New session sheet — kept (TopBar `[+]` action)
- [ ] Session search + filter (DEFERRED)
- [ ] Session Navigator — trigger TBD (DEFERRED)
- [ ] TopBar modal session switcher — yatay pager, kart tap, last-session default

### Screens
- [ ] Sessions (Home) screen — full implementation (DEFERRED — modal switcher replaces it)
- [ ] Terminal screen — full implementation
- [ ] Tab bar: Terminal / Files / Agent (REMOVED — per Muhofy 2026-07-28)
- [ ] Overflow menu: rename, export, share, settings
- [ ] Toolbar: session name, branch, status

### HUD
- [ ] HUD strip at top of terminal
- [ ] Widgets: CPU, RAM, Network, SSH status, Clock, Battery
- [ ] Widget selector in Settings
- [ ] Drag to reorder widgets
- [ ] Toggle HUD visibility

### Workspace
- [ ] Workspace / project system
- [ ] Project: name, path, linked sessions
- [ ] Project-aware shortcuts
- [ ] Project-aware agent context (v later)

---

## Phase 3 — Input System
*Goal: The best terminal input experience on Android.*

### Keyboard Handle & Extra Keys (Sprint 1 — COMPLETED 2026-08-10)
- [x] `KeyboardHandle.kt` — thin handle above keyboard
- [x] Tap → toggle extra key bar
- [x] Extra key bar — ESC, TAB, CTRL, ALT, ←, ↓, ↑, →, HOME, END, PGUP, PGDN, -, |
- [x] Second tap → hide
- [x] Hidden by default (DataStore persisted)
- [x] Hardware keyboard detection → auto-hide bar
- [x] Modifier sticky state: tap=sticky (one-shot), long-press=popup with combos
- [x] Works in both Classic (TerminalView) and Block (Compose) modes
- [x] Classic: TerminalViewClientImpl.readControlKey/readAltKey wired to ExtraKeyState
- [x] Block: Ctrl+C/Z/D/X flush raw bytes to PTY via SubmitRawByteUseCase
- [x] Settings toggle: "Extra Keys Bar" in SettingsScreen

### Flat Key Bar (Sprint 2 — COMPLETED 2026-09-13)
- [x] `FlatKeyBar.kt` — text-only keys, no surfaces, blur behind (API 31+ RenderEffect)
- [x] Key groups: System (Esc, Ctrl, Alt, Tab), Arrows, Navigation (Home/End/PgUp/PgDn), Editing (BkSpc/Enter/Space), F-keys (F1-F12)
- [x] Gradient vertical dividers between groups (DroshBorderSubtle)
- [x] Hover: border + subtle background overlay
- [x] Pressed: stronger background overlay
- [x] Primary keys (Ctrl/Alt/Tab): DroshPrimary color, hover → DroshPrimary lighter
- [x] Active/stuck keys: full DroshPrimary background
- [x] F-keys: smaller 11sp font
- [x] Horizontal scroll (single row)
- [x] Default visible: true (was false)
- [x] Integrated into TerminalScreen — replaces InputBarHost usage
- [x] HTML mockup: `html/irisshell_keybar_flat_mockup.html`
- [ ] Blur level configurable (TODO: parameterized via settings)

### Ghost Text Autocomplete
- [ ] `GhostTextEngine.kt` — inline ghost text
- [ ] Suggestion pill above keyboard
- [ ] Tap pill → accept completion
- [ ] Shell TAB completion integration
- [ ] History-based suggestions

### IDE-Style Input
- [ ] Multi-line editing
- [ ] Cursor movement
- [ ] Text selection
- [ ] Undo / Redo

### Shortcut System
- [ ] Shortcut Overlay UI — trigger TBD
- [ ] Left side: Command Shortcuts
- [ ] Right side: Keyboard Shortcuts
- [ ] Command shortcut builder — name, icon, color, command, execute mode
- [ ] Keyboard shortcut builder — modifier + key via PC layout UI
- [ ] Shortcut manager in Settings
- [ ] Export / import JSON
- [ ] Community Shortcut Store — browse packs, install

### Voice Input
- [ ] Mic button in input bar
- [ ] Push-to-talk recording
- [ ] Whisper API → transcript
- [ ] Auto-send option in Settings

---

## Phase 4 — SSH & Remote
*Goal: Best-in-class mobile SSH client.*

- [ ] `SshjManager.kt` — SSHJ 0.38.x
- [ ] SSH host list — add, edit, delete
- [ ] `SshKeyVault.kt` — encrypted storage
- [ ] BiometricPrompt unlock
- [ ] Built-in key generator — RSA, ED25519
- [ ] Known hosts management
- [ ] Fingerprint verification UI
- [ ] SSH session in terminal screen
- [ ] Port forwarding — local + remote
- [ ] Keep-alive ping
- [ ] Session timeout + auto-lock
- [ ] Production Tag — red banner, confirm every command
- [ ] Multi-Exec — broadcast command to multiple hosts
- [ ] SSH Constellation — visual server map (v1.1)
- [ ] Mosh support (v1.1)
- [ ] SFTP browser (v1.1)
- [ ] Jump host / bastion support (v2.0)

---

## Phase 5 — Safety, Polish & Distribution
*Goal: Stable, safe, shippable.*

### Safety
- [ ] Dangerous Command Warn — rm -rf, chmod 777, dd, fork bombs
- [ ] Smart Sudo — explain before execute
- [ ] Secret Redaction — mask API keys/passwords in output
- [ ] Incognito Session — no history, no DNA, no logs
- [ ] Clipboard auto-clear

### Visual & Theme
- [ ] Theme Store — browse, preview, install
- [ ] Community theme JSON format
- [ ] OLED mode toggle
- [ ] Terminal background style — TBD (solid/glass)

### Alias Manager
- [ ] GUI alias management
- [ ] Global + per-workspace scope
- [ ] Auto-sync to `.zshrc` / `.bashrc`

### Notifications
- [ ] Command finished
- [ ] SSH disconnected
- [ ] Long task completed
- [ ] Notification actions — View, Dismiss

### Distribution
- [ ] F-Droid metadata — fastlane, screenshots, description
- [ ] GitHub Releases — APK per ABI
- [ ] README + contributing guide
- [ ] v1.0 changelog

---

## Phase 6 — Agent Intelligence
*Goal: Terminal becomes intelligent. The Warp moment.*

### Core Agent (Port from Iris Code)
- [ ] Port `AgentLoop.kt`
- [ ] Port `MultiStepStreamer.kt`
- [ ] Port `OpenAiProviderAdapter.kt`
- [ ] Port API Vault — per provider key management
- [ ] Port `WebSearchTool.kt`
- [ ] Port `BashTool.kt`
- [ ] Agent tab in terminal screen
- [ ] Work mode: PLAN / BUILD / AUTO

### Tools
- [ ] Tool: `bash` — PRoot subprocess
- [ ] Tool: `read_file`
- [ ] Tool: `write_file` — diff + approve
- [ ] Tool: `ask_user`
- [ ] Tool: `update_todo`
- [ ] Tool: `web_search`

### Natural Language → Shell
- [ ] Command generation from natural language
- [ ] Show command before execution
- [ ] User approval flow
- [ ] Error explanation + fix suggestion

### Command DNA
- [ ] `CommandDnaDao.kt` — Room FTS5
- [ ] Auto-index every command
- [ ] Natural language query across history
- [ ] Session Intelligence

### Error DNA
- [ ] Failure detection — exit code ≠ 0
- [ ] Agent diagnosis
- [ ] Fix suggestion
- [ ] Learned fixes in Room
- [ ] Proven fix on repeat error

### Output Intelligence
- [ ] npm, pip, gradle, cargo, apt, docker, git parsing
- [ ] Actionable cards from raw output
- [ ] Fix / Details action buttons

### Advanced Agent Features
- [ ] Drosh Autopilot — multi-step task execution
- [ ] Agent Watch — WorkManager background conditions
- [ ] Natural Language Cron — WorkManager scheduler
- [ ] Terminal Lens — OCR → command
- [ ] Session Replay — record + playback
- [ ] Live Share — read-only / suggest / execute / full control

---

## v1.1

- [ ] Rich Terminal Rendering — table, JSON, markdown, image
- [ ] Anthropic + OpenAI + OpenRouter providers
- [ ] Workspace workflow builder
- [ ] Live Share relay server
- [ ] Session export — markdown
- [ ] Light theme

---

## v2.0

- [ ] Process Cinema — visual process manager
- [ ] Local model — Ollama, llama.cpp
- [ ] X11 forwarding
- [ ] WearOS companion
- [ ] DeX / tablet layout
- [ ] Plugin system

---

## 🔲 Backlog

Input: Typo Fixer, Command Template, Pipe Suggestions, Sudo Remember, History Dedup

Output: Output Pin, Output Diff, Progress Detector, Output Filter, Line Numbering

Files: Drag & Drop Upload, Quick Edit, File Size Warning, Trash, Recents

SSH: Auto Reconnect, Connection Health, Offline Queue, SSH Config Import

Safety: Dry Run, Command Lock

Discovery: Man Page Viewer, Cheat Sheet, Package Search, Port Scanner Mini, Env Viewer

Productivity: Command Timer, Repeat Command, Parallel Run, Command Chain Builder, Expected Output

Quick Access: Quick Note, Screenshot to Command, Widget, Notification Actions

Accessibility: Large Font Mode, High Contrast Theme, Single Hand Mode

---

## 🔓 Open Decisions

| # | Decision | Status |
|---|----------|--------|
| 1 | Command Shortcuts trigger | TBD |
| 2 | Shortcut Overlay trigger | TBD |
| 3 | Session Navigator trigger | TBD |
| 4 | Ghost text confirmation | Pill — leading candidate |
| 5 | App icon & splash | TBD |
| 6 | Onboarding flow | TBD |
| 7 | Terminal background style | TBD |
| 8 | Live Share relay infrastructure | TBD |
