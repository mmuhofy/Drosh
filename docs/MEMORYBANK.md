# Drosh — Memory Bank
_Last updated: 2026-10-08_

---

## 1. Project Identity

| Field | Value |
|-------|-------|
| App name | Drosh |
| Package | `dev.drosh` |
| Tagline | "Your phone is a Unix machine. Finally." |
| License | MIT |
| Distribution | F-Droid first, GitHub Releases |
| Repo | github.com/mmuhofy/Drosh |
| Ecosystem | Drosh — by Muhofy |

---

## 2. Vision

Termux brought the terminal to Android in 2012. Drosh reinvents it for 2026. Not a Termux fork — a ground-up reimagination of what a mobile terminal should be: agent-native, semantically aware, and built for the way people actually use their phones. The goal is a single application that combines desktop terminal power, mobile-first UX, semantic UI, and AI workflow — making it the first terminal environment where the agent and the shell are the same thing.

**Target users:** Termux power users, mobile developers, DevOps engineers, CTF players, students learning Linux.

**Why it's different:**
- Every other Android terminal is a shell with a keyboard. Drosh is an intelligent environment.
- Warp did this for desktop. Nobody did it for Android.
- Open-source, free, bring-your-own-model — no cloud lock-in.

---

## 3. Confirmed Stack

| Component       | Decision                               | Notes                              |
| --------------- | -------------------------------------- | ---------------------------------- |
| Language        | Kotlin 2.1.0                           | Iris Code parity                   |
| UI              | Jetpack Compose BOM 2026.04.01         | Proven working combo               |
| Architecture    | MVVM + Clean Architecture              | Strict layering                    |
| DI              | Hilt 2.57                              | Iris Code parity                   |
| Min SDK         | 26                                     | Android 8.0+                       |
| Target SDK      | 36                                     | Android 16                         |
| Terminal Engine | termux-view + termux-terminal-emulator | Vendored from Iris Code            |
| Font size       | Fractional sp, 0.1 step                 | Pinch follows the fingers; limits in `TerminalZoom` (§7C) |
| Editor Engine | **sora-editor** (`io.github.rosemoe:editor`) | Native Android widget, LGPL-2.1-or-later |
| PTY             | libtermux.so (JNI)                     | Prebuilt, port from Iris Code      |
| Linux Env       | PRoot v5.2.0 + Ubuntu 24.04 rootfs     | Port from Iris Code                |
| Agent Loop      | MultiStepStreamer + AgentLoop          | Port from Iris Code                |
| LLM (v1.0)      | Gemini 3.5 Flash (Google GenAI SDK)    | Cloud-first                        |
| LLM (v1.1+)     | + Anthropic, OpenAI, OpenRouter        | Multi-provider                     |
| LLM (v2.0+)     | + Ollama, llama.cpp                    | Local optional                     |
| HTTP/Stream     | OkHttp 4.12.x + SSE                    | Agent streaming                    |
| SSH             | SSHJ 0.38.x                            | Modern, actively maintained        |
| Storage         | Room 2.8.5                            | Command DNA, session history       |
| Preferences     | DataStore 1.1.x                        | Settings, shortcuts                |
| Security        | AndroidX Security Crypto 1.1.x         | API keys, SSH Key Vault            |
| Serialization   | Kotlinx Serialization 1.7.x            | Shortcut export/import, themes     |
| Background      | WorkManager 2.10.x                     | Natural Language Cron, Agent Watch |
| Biometric       | BiometricPrompt 1.2.x                  | SSH Key Vault unlock               |
| Image Loading   | Coil Compose 3.x                       | Theme Store previews               |
| Animation       | Lottie Compose 6.x                     | Onboarding, loading                |
| Async           | Kotlin Coroutines 1.10.x + Flow        | Reactive streams                   |
| Build           | Gradle KTS + Version Catalog           | Iris Code parity                   |

---

## 4. Architecture Layers

```
ui/
  setup/            → Onboarding + BootstrapStepper + SetupRecovery + LiveLogCard
  terminal/         → Terminal screen, session UI, block renderer
  sessions/         → Session list, preview, navigator
  ssh/              → SSH manager, constellation
  shortcuts/        → Shortcut overlay, keyboard panel
  settings/         → Settings, theme store, API vault
  hud/              → HUD widgets, status panel
  workspace/        → Project workspace (grouping + metadata, §7D)

domain/
  terminal/         → TerminalSession, Block, SemanticToken,
                      BootstrapStep, BootstrapProgress,
                      ObserveBootstrapUseCase, TriggerBootstrapUseCase,
                      ObserveFirstLaunchUseCase, TerminalZoom
  agent/            → AgentLoop, Tool interfaces, StreamEvent
  session/          → SessionEntity, CommandDNA, Replay
  ssh/              → SshHost, SshKey, SshConnection
  shortcut/         → ShortcutEntity, KeyBinding, CommandShortcut
  workspace/        → Workspace, WorkspaceEdit, WorkspaceRepository,
                      WorkspacePath, WorkspaceGrouping

data/
  terminal/         → TerminalManager, ProotRunner, UbuntuBootstrap
  agent/            → MultiStepStreamer, ProviderAdapter, ToolRegistry
  local/            → Room DAOs, FTS5, DataStore
  remote/           → LLM clients, SSH client, Theme Store API
  ssh/              → SshjManager, SshKeyVault

agent/
  tools/            → BashTool, ReadFileTool, WebSearchTool, CronTool
  loop/             → AgentLoop, MultiStepStreamer
  semantic/         → SemanticParser, OutputClassifier

terminal/
  engine/           → PTY bridge, ANSI parser
  renderer/         → BlockRenderer, SemanticHighlighter, RichRenderer
input/           → GhostTextEngine, InputQueue
  zoom/            → TerminalRenderer.updateTextSize, zoomTo + focal anchoring

di/                 → Hilt modules
util/               → Constants, extensions
```

### Rendering Pipeline

```
PTY output
    ↓
ANSI Parser        → strips escape codes, applies colors
    ↓
Semantic Parser    → detects ERROR/WARNING/SUCCESS/BUILD patterns
    ↓
Rich Renderer      → tables, JSON, markdown (v1.1+)
    ↓
Block Engine       → wraps each command+output as a Block
    ↓
Compose UI         → renders BlockList with animations
```

---

## 5. Visual Identity

| Element | Value |
|---------|-------|
| Background | `#0E0E0E` |
| Surface | `#1A1A1A` |
| SurfaceLow | `#151515` |
| SurfaceVariant | `#242424` |
| SurfaceHigh | `#2E2E2E` |
| SurfaceContainerLowest | `#080808` |
| Border / Outline | `#3A3A3A` |
| Primary accent | `#4C9EFF` |
| OnPrimary | `#0E0E0E` |
| Text primary | `#F2F2F2` |
| Text secondary | `#B4B4B4` |
| Text muted | `#7A7A7A` |
| Text disabled | `#4D4D4D` |
| Success | `#3DD68C` |
| Error | `#F2555A` |
| Warning | `#F0B429` |
| Build | `#4C9EFF` |
| Terminal font | JetBrains Mono |
| UI font | Outfit (bundled) |
| Corner radius | 14dp cards, 12dp buttons, 8dp chips |
| Theme | Dark only (v1.0) |

**Source of truth:** the values live in `core/.../DroshPalette.kt` as plain
ARGB ints; `design-system/.../DroshColors.kt` wraps them as Compose
`Color`s. They are plain ints in `:core` because `:terminal` renders through
Views and has no Compose dependency, and pulling Compose in to read a
constant is the wrong trade. Change the value in `:core`.

**Why the surfaces are neutral.** Every neutral is R=G=B. The previous set
mixed a cool tint (`#252A30`, `#343A43`) with a neutral one (`#272A2E`),
and a palette whose hue drifts between steps reads as dirty rather than
designed — the eye cannot settle on one colour temperature. Steps run
evenly: 8, 14, 21, 26, 36, 46, 58. The earlier set also had
`SurfaceVariant` and `SurfaceHigh` at the same lightness, so layers meant to
separate did not.

**Accent.** One saturated blue, with a green and a red that stay
distinguishable from it and from each other. This replaces the gold
(#E8C547) this section previously specified, which had already drifted in
code before it was written down.

### Semantic Highlight Colors
| Token | Color |
|-------|-------|
| ERROR / FATAL | `#F2555A` red |
| WARNING / WARN | `#F0B429` gold |
| SUCCESS / DONE | `#3DD68C` green |
| BUILD / COMPILE | `#4C9EFF` blue |
| INFO | `#7A7A7A` muted |

Not implemented yet — the semantic parser that would colour terminal
output by these is still missing (§7). These are the intended values.

### Terminal Themes (v1.1+)
- **Default** — Drosh dark, warm gold accents
- **Stealth** — Pure black, minimal color
- **Material You** — Dynamic color from wallpaper
- **Glass** — Subtle blur, translucent surfaces

### OLED Mode
Toggle in Settings. Forces `#000000` background. Saves battery on OLED displays.

---

## 6. Navigation & Screen Inventory

### Navigation Hierarchy
```
Sessions (Home)
  → [session card]     → Terminal Screen
                           tabs: Terminal / Files / Agent
  → [+ new]            → New Session Sheet
  → [workspace]        → Workspace Screen
  → [⚙️]              → Settings

Settings
  → API Vault
  → SSH Manager
  → Theme Store
  → Shortcuts
  → HUD Config
  → Workspace
```

### Screens

**Sessions (Home)**
- Session cards with real terminal snapshot preview
- Session name, last command, uptime, SSH host if remote
- Long press → rename, delete, duplicate, export
- Swipe between sessions → preview card grows into terminal
- `[+]` top right → New Session Sheet
- Search + filter bar

**Terminal Screen**
- Full-screen terminal
- Block-based output (each command = one block)
- HUD strip at top (optional, configurable)
- Keyboard handle at bottom → tap = extra key bar toggle
- Ghost text inline autocomplete
- Tab bar: `[💻 Terminal]` `[📁 Files]` `[🤖 Agent]`
- Overflow `[⋮]`: rename session, export, share, settings

**Session Navigator**
- Triggered by long swipe or dedicated gesture (TBD)
- Full-screen list of all sessions
- Real terminal snapshot per card
- Drag to reorder, swipe to close

**SSH Manager**
- Host list with connection status
- Add host: name, IP/hostname, port, user, auth method
- SSH Constellation view (v1.1)
- Key Vault: stored keys, biometric unlock

**Theme Store**
- Browse community themes
- Preview before applying
- One-tap install
- Upload own theme

**Workspace**
- Project list
- Each project: name, path, linked sessions, workflows
- Workflow builder (v1.1)

**Settings**
- API Vault (per provider)
- Default model
- SSH Manager
- Theme Store
- Shortcuts Manager
- HUD Config
- Shell: zsh/bash toggle
- Auto-install packages toggle
- Auto-optimize rootfs toggle
- OLED mode toggle
- About, license, GitHub

### Session Preview Swipe

```
User swipes right →
  Current session scales down + moves left
  Next session preview scales up from right
  Preview shows real terminal snapshot
  User sees: session name + last command + real output
  Release → transition completes (Shared Element)
  Cancel (swipe back) → return to current
```

Shared Element Transition: session card thumbnail → full terminal screen.

---

## 7. Terminal Core

### Two chrome states, and there is no band

*Decided 2026-10-09. Replaces the always-fullscreen attempt of 2026-10-08, and
the three band/colours that preceded both. Rebuilt from scratch the same day.*

**Immersive** — at the prompt, on a session that has just opened, or with a TUI
in control. The app goes fullscreen (`hide(systemBars())`), the pill row's offset
is **0** — flush with the top of the screen, in the space the status bar vacated —
and the grid's top padding is the status bar's height. **Normal** — anywhere in
the scrollback. Status bar back, pills at `statusBarH + 4dp`, grid padded clear
of them.

```
chromeCollapsed = tuiActive || mTopRow == 0
```

That is the whole rule, and it lives in the terminal module as a plain function
so the tests can walk it. `mTopRow` is an integer, so `0` and `< 0` already draw
a sharp line. The optional dead zone (0 / 24 / 80px) is **off**: hysteresis here
means two answers to "which side of the line is the viewport on", which is how the
previous version managed to disagree with itself mid-gesture.

**The direction is the point.** You are not looking for the clock while you are
typing; you are definitely looking for it while reading something that scrolled
off. The bar disappears exactly when it is not needed, which is what the first
attempt got backwards — it left a clock permanently on screen over the prompt.

**There is no band.** Nothing is painted over anything: no scrim, no gradient,
no dimming, no separate surface. The pills are translucent and what is behind them
is the pane, painted in `terminalBgColor`. Three earlier attempts each failed the
same way — by treating the top of the terminal as something that needed painting:

| Attempt | What it looked like |
|---|---|
| `DroshBackground` fill | A separate surface with an edge |
| A near-black scrim | A black bar |
| Row shrunk to the bar's height | A 30dp squashed control caught mid-transition |

The fix was to stop painting and start moving things. The row is **44dp in both
states**; only its offset moves. It used to shrink to the status bar's height
while collapsed, on the theory that the pills *were* the band — caught in the
middle of that transition it read as a control that could not decide where it
belonged, and it was the thing being complained about.

**`BEHAVIOR_DEFAULT`, kept.** The pills live at the very top of the screen, which
is exactly where Android's edge gesture lives.
`BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE` reveals the bars **transiently** on such a
gesture — *"overlay your app's content … and are automatically hidden after a
short timeout."* A slow upward drag therefore had the system drawing a status bar
across the row the user was reaching for, repeatedly. That was the flapping. A
deliberate top-edge swipe still brings the bars back for good.

**The grid is padded, and the padding follows the chrome.** This is the part that
was wrong for a long time and is right now. A *constant* clearance either wastes
a status bar's height at the prompt, where nothing is showing, or lets the first
line of output go under the pill row in the scrollback, where the status bar is
back — which is exactly the bug the prototype had. So:

```
immersive → padding-top = max(statusBarH, PILL_ROW_H + CHROME_CLEARANCE)
normal    → padding-top = statusBarH + PILL_TOP_GAP + PILL_ROW_H + CHROME_CLEARANCE
```

`CHROME_CLEARANCE` stays a constant and is read by the row and the grid alike.
What changes between states is the row's **offset**, not the clearance.

**A terminal grid is not a web page, so this costs a resize.** A scrolling
document can be padded for free; a fixed grid cannot — there is only so much room
above and below. Every crossing of the live edge therefore changes the number of
rows, sends a SIGWINCH, and the shell answers by redrawing its prompt. Two things
make that survivable, both in `TerminalView`:

- `updateSize` **clamps** `mTopRow` instead of zeroing it. Zeroing meant the
  chrome's own transition threw the user back to the newest output, which is the
  one thing a scrollback is not for.
- `onScreenUpdated` treats output arriving within `RESIZE_GRACE_MILLIS` (400ms)
  of a resize as the shell answering us rather than as new output, and does not
  pull the viewport to the live edge for it. Without this the repaint that closes
  the transition is mistaken for new output and the transition undoes itself.

**`autoHideStatusBar`, a setting.** Off: the bar stays where it is and the pills
never travel. Default on.

### Block-Based Output
Every command execution produces a Block:

```
┌──────────────────────────────────────┐
│ $ git status                    [📋] │  ← command line, gold
├──────────────────────────────────────┤
│ On branch main                       │  ← output
│ nothing to commit                    │
│ ✓ exit 0  •  12ms               [↕] │  ← footer: status, duration
└──────────────────────────────────────┘
```

- Short output (≤8 lines): fully expanded
- Long output (>8 lines): collapsed + "Show X more ↓"
- Very long (50+ lines): "Open fullscreen ↑"
- Copy button top right
- Long press block → copy, share, pin, search

### IDE-Style Input
- Multi-line editing
- Cursor movement (arrow keys via keyboard panel)
- Text selection
- Undo / Redo
- Not line-based — real text editor behavior

### Ghost Text Autocomplete
```
git pu
      sh origin main
```
Gray ghost text inline. Confirmed by tapping the suggestion pill that appears above keyboard. Falls back to TAB for shell completion.

```
┌─────────────────────────┐
│  [git push origin main] │  ← suggestion pill
└─────────────────────────┘
   git pu█
```

### Semantic Output Highlighting
Second rendering layer on top of ANSI colors:

| Pattern | Style |
|---------|-------|
| `ERROR`, `FATAL`, `Exception` | Red background tint |
| `WARNING`, `WARN` | Gold left border |
| `BUILD SUCCESSFUL`, `✓`, `done` | Green accent |
| `Task :app:compile*` | Blue, monospace |
| `[1/4]`, `[2/4]` | Progress indicator |

Supported tools: Gradle, npm, pnpm, cargo, git, adb, logcat, docker, pip.

### Rich Terminal Rendering (v1.1+)
PTY → ANSI → Semantic → Rich Renderer → Compose UI

| Content | Rendered As |
|---------|-------------|
| Markdown `# Title` | Large bold heading |
| `\| A \| B \|` table | Compose Table component |
| `- [ ] task` | Interactive checklist |
| `![img](url)` | Inline image thumbnail |
| JSON blob | Collapsible formatted JSON |

---

## 7A. Native Editor

*Merged 2026-10-05. PR #15, branch `feat/editor-clean`.*

### Shape

```
$ dedit notes.md        → opens in Drosh's native editor
$ dedit --help
$ dpkg -l | grep dedit  → it is a real package
```

One command, one file, one screen. No file tree, no tabs.

### `dedit` is a package, not a shell function

The first attempt injected an `editor` **shell function** from the shell-integration
script. It never ran, so `editor foo.txt` fell through to Ubuntu's
`/usr/bin/editor` — which is nano. Measured, not assumed:

| invocation | reads `$ENV`? |
|---|---|
| `ENV=f zsh -i -l` — what Drosh does, `--login` | **no** |
| `ENV=f zsh -i` | **no** |
| `ZDOTDIR/.zshenv` | yes |

**zsh does not read `$ENV` in native mode.** Since Drosh prefers zsh whenever
Oh My Zsh is installed, `DroshShellIntegration.install()`'s zsh plan has never
worked. See §16 — this also means **OSC 133 does not fire for zsh**, which is
why the block engine's command lifecycle has been degraded on the default shell.
Still unfixed; fixing it means injecting into `~/.zshenv`, which touches a user
file.

A packaged executable has none of that fragility: it does not care which shell
runs, whether it is interactive, or whether any startup file was sourced. So
`dedit` works from a script, a Makefile or a bare `sh -c` — none of which a shell
function can. It draws nothing itself, so it composes with pipes.

Built for real: `dpkg-deb --build` over a staging tree, a hand-written
`Packages` index (`dpkg-scanpackages` is in dpkg-dev, absent from the base
rootfs), `apt-get install dedit`. Any failure falls back to copying the command
into `/usr/local/bin` — a missing `dedit` is worse than one dpkg does not know
about, and failing the install step would send a working terminal back to the
recovery screen.

**Shebang is host-absolute**, built from the real `filesDir` at runtime: the
kernel resolves a shebang before PRoot is involved and `/bin/sh` does not exist
on the Android host. Termux does this in 40k+ files. An NDK binary was
rejected — this program's whole job is to validate one argument and write forty
bytes to a file descriptor.

**Validation, with messages:** no argument, more than one, unknown option, a
directory, an unreadable file, a read-only file (warns, still opens), over
2 MiB, a missing parent directory, and the bind-mounted paths the app cannot
open (`/sdcard`, `/data`, `/proc`, …). BEL and ESC are stripped from the path
rather than refused: they would end the escape sequence early and silently open
the wrong file. Verified in dash.

### Signal path

```
dedit <path>                         a packaged POSIX sh script
    ↓ ESC ] 1339 ; <guest path> BEL  Drosh-private OSC
TerminalEmulator.handleOscDroshOpenEditor
    ↓
ShellIntegrationState.onOpenEditor   per-session event, NOT CommandSnapshot
    ↓
EditorRequestPublisher               Channel(BUFFERED) → Flow<String>
    ↓
TerminalScreen → onOpenEditor(path)   callback, holds no NavController
    ↓ navigate "editor?guestPath=…"   URI-encoded: a guest path has slashes
EditorScreen (`:editor`) → SoraCodeEditor → sora CodeEditor (AndroidView)
    ↓
EditorViewModel → GuestFileRepository (`:domain`) → RootfsGuestFileRepository (`:data`)
```

OSC 1339 is Drosh-private: 133 is FinalTerm, 1337/1338 are iTerm2's. Relative
paths resolve against `$PWD` in the script, because that is the only authority
on where the user is; OSC 7 reports the same thing but only after the prompt
redraws, which has not happened when a command runs.

`ShellIntegrationState.onOpenEditor` is deliberately **not** folded into
`CommandSnapshot`: an editor request has no lasting state, and putting it in the
snapshot would make the block engine read every file open as command activity.

### Editor engine

`Rosemoe/sora-editor` — the only serious open-source Android code editor
*widget*. None of the editing is reimplemented; buffer, cursor, selection,
undo/redo, auto-indent, word wrap and rendering are all sora's.

- **Licence: LGPL-2.1-or-later.** Drosh is GPL-3.0. Compatible in principle
  (LGPL "or later" permits relicensing under GPL-3) but **Muhofy has not signed
  off**, and it needs checking before any F-Droid submission.
- Upstream marks every release `prerelease` and its README says "developing
  slowly". Pinned deliberately for that reason.

**Version pin — measured, not guessed.** `editor` is pure Java (verified:
`editor-0.24.6.aar` contains zero `.kotlin_metadata`). `language-textmate` is
Kotlin, and its metadata version is the constraint:

| Artifact | metadata (`mv`) | readable by KGP 2.2.0 |
|---|---|---|
| `editor:0.24.6` | *(no Kotlin)* | yes |
| `language-textmate:0.24.3` | `[2,2,0]` | yes |
| `language-textmate:0.24.6` | `[2,3,0]` | **no — compile error** |

Hence the two are pinned apart and **`editor-bom` is deliberately not used** —
it would force one version and reintroduce the 2.3 metadata. Same class of trap
as the `kotlinx-serialization` pin already in the catalog.

### Syntax highlighting

`language-textmate:0.24.3`. Core library desugaring is on in **both** `:editor`
and `:app`: tm4e uses `java.time`, which does not exist below API 33, and this
app supports API 26.

Eleven grammars — bash, json, yaml, python, kotlin, java, javascript, typescript,
cpp, csharp, markdown — vendored from MIT-licensed microsoft/vscode at a pinned
commit, plus Kotlin from MIT-licensed fwcd/vscode-kotlin. Per-file provenance in
`editor/src/main/assets/textmate/PROVENANCE.md`. The theme is Drosh's own,
written against the palette in `:core`, so highlighted files match the terminal.

Loading is once per process and total: a grammar that fails to parse costs one
file its colours, not the user their editor. An unknown extension opens as plain
text rather than being guessed at.

### File access is rootfs-only

`RootfsGuestFileRepository` maps `/home/x` ↔ `<filesDir>/ubuntu/rootfs/home/x` by
concatenation — true only under the rootfs. PRoot bind-mounts `/sdcard`,
`/storage`, `/data`, `/proc`, `/sys` and `/system*`, and `rootfsDir + "/sdcard"`
names a real but *unrelated* directory, so those are **refused** with
`FileFailure.OutsideRootfs` rather than silently reading the wrong file. Both
sides are canonicalised, which covers `..` and symlinks too.

`GuestFileLimits.MAX_EDIT_BYTES = 2 MiB`.

### Still not built

- No file tree, no tabs, no LSP, no split view inside the editor.
- `FlatKeyBar`'s Backspace is still wired to `HOME`
  (`ui/.../input/FlatKeyBar.kt:170`) and `ExtraKey.Navigation` has no
  `BACKSPACE` member — unrelated to the editor but still wrong.

---

## 7B. Split Panes & Floating Window

*Added 2026-10-05. Branch `feat/split-pane-panes`, PR #19.*

### Shape

Two terminal panes, and never more. **Docked top-to-bottom** with a draggable
divider, or the second one floating over the first as a movable, resizable,
expandable window. *(Vertical, stepped divider, sidebar banner — PR #27.)*

Two ways in:
- Drag the grip on a session row in the sidebar past half its width.
- "Split below" or "Open in window" in the same row's long-press menu.

The overflow menu gains "Float window"/"Dock pane", "Move divider" and
"Close second pane", and all appear only once a second pane exists.

### Why top-to-bottom, not side by side

Side by side was the first cut and it was wrong on a phone. Two 180dp columns
of terminal are about ten characters wide — narrower than most paths — and
wrapped output is unreadable in a way that a short-but-full-width pane is
not. `splitFraction` is a **height** fraction.

### Why the divider snaps to five positions

20% apart. A divider that stops anywhere produces pane heights nobody would
have chosen, and the user has to hold it there with a finger. Five positions
cover every useful arrangement and each is one drag away, which is what makes
it a control rather than a slider.

Clamped *before* snapped, because snapping first can round a value past the
limit up to a legal step — a hard drag to the bottom would then land
somewhere other than the bottom. "Move divider" in the overflow walks the
same five positions, since a finger hunting a 4dp seam is a worse way to
move it than a menu entry.

### What the panes are allowed to overlap

Nothing. `SplitPaneHost` takes `weight(1f)`, not `fillMaxSize()`: each pane
draws its own extra-key bar, so a host that fills the Column pushes that bar
past the bottom edge — the lower terminal is drawn under the bar and under
the keyboard, which reads as "half the screen is empty" rather than as a bug
in the layout.

### What the drawer says about a split

Both session names, with the split glyph between them. A second terminal on
screen that nothing in the drawer explains reads as the app having rendered
twice. Both names ellipsise from the **middle** — sessions are named for what
they are for, and the distinguishing end is the end a line ending would cut
off. It names the *focused* pane, since it describes what is on screen.

Float/dock lives in the top bar only. The row menu has "Open in window",
which starts a float directly rather than making the user split and then find
a second toggle; having both would give one action two doors.

### Why exactly two

A tree of slots would allow arbitrary nesting, which on a phone means panes
too narrow to read a line of output in — and every nested split is another
divider the user has to discover and another thing that can collapse to zero.
`PaneSlot` refuses to describe a third pane, so the tree cannot be built even
by accident.

### Why the grip is its own target

The row's long press is already the context menu, and Compose gives no
reliable way to have one gesture mean two things: the tap and
drag-after-long-press detectors race on the same timeout, and whichever
consumes first wins — so the outcome depends on which one arrives. A
dedicated grip is unambiguous, and it doubles as the sign that a row can be
dragged at all.

It arms on a threshold rather than on any movement, and disarms if the drag
comes back, because brushing the grip while scrolling the list should not
leave the user with two panes they did not ask for.

### Geometry is fractions, not pixels

`PaneLayout` stores the divider position and the floating window's bounds as
fractions of the container. All of it is persisted, and a phone rotates: a
pane saved at 300dp tall is a different fraction of the screen after the
process restarts in the other orientation, and a different size again on a
tablet.

The clamps live in the model, not at the call sites. `MIN_SPLIT_FRACTION`
keeps the top pane tall enough to show a prompt and a few lines — the whole
use of the lower pane is watching something run while you work — and
`OVERSCAN` keeps a sliver of a floating pane reachable, because a pane pushed
entirely off screen has no visible edge to drag back in. Clamping rather than
rejecting means a drag past the end stops at the limit instead of snapping
back.

### The host must ask `isSplit` first

*Corrected 2026-10-08. This was the big one.*

`SplitPaneHost` had **no `isSplit` guard**, so the docked branch ran for every
screen in the app: the primary terminal at 50% height, a second
`TerminalPaneBody` underneath painting `canvas.drawColor(0xFF000000)` because its
slot had no session, a hairline seam, and a draggable pill. The app did not
"sometimes start split" — the bottom half was always black.

It explains most of what looked broken. The empty pane *registered a view* for
the second slot, so focus could land in it and `switchTab` writes to whichever
pane has focus — opening a session put it in the bottom half. Closing a split
could not unmount the pane, so it kept painting the session that had been in it.
And the divider was draggable over a layout the model refused to move, so it slid
and snapped back.

`if (!layout.isSplit) { primary(); return@BoxWithConstraints }` sits with the
other two early returns, **above** them in importance: not invoking `secondary` is
what keeps a second `AndroidView` from claiming a session the primary pane has.

### `pointerInput(Unit)` captures values forever

*Corrected 2026-10-08. Worth stating as a rule, not as a bug.*

`Modifier.pointerInput(Unit)` never restarts, so every value its lambdas close
over is the one from when the node was **first composed**. Three bugs were all
this:

- The divider's `onDragStart` seeded `dragTopPx` from `topPx` as it was at first
  composition, so the second drag and every one after it jumped on the first
  delta; and `onDrag` divided by that frame's `heightPx`, so opening the keyboard
  changed neither the fraction written nor how far the seam could travel.
- The floating window's move and resize origins were seeded from `bounds` the same
  way, so the second move snapped the window back to where it started.
- `FloatingPaneWindow` already had `rememberUpdatedState` for its callback, which
  is why the callback was fine and the origin was not.

Anything a gesture needs from the current frame goes through
`rememberUpdatedState`, read by `by` inside the detector. The file's own comment
about the first divider bug ("every event in the gesture computed from the same
starting height") was this, diagnosed one level too low.

### The part that was not cosmetic

`TerminalManager` had one `TerminalView`, one active index, and three
single-valued flows that quietly assumed there was only ever one terminal. A
View holds a single `TerminalSession`, so two sessions cannot be drawn by one
of them — the manager now holds a view per `PaneSlot` plus a focused pane.

Each pane keeps its **own positional index**. Both panes resolving through
one "current" index is what made the single-view design work, and it is
exactly what breaks with two: moving focus would drag the other pane's
session along with it.

Three flows were genuinely per-pane and are now resolved as such:

| Flow | What was wrong |
|------|----------------|
| Alt buffer | One boolean for every session, so a `vim` opening in the background pane was deduplicated against whatever the foreground pane's emulator last reported — the transition was dropped, or delivered with the wrong value, swapping the foreground pane's renderer for no reason |
| Selection | Drawn once, in view pixels, so the unfocused pane's rectangle anchored the menu to a different terminal |
| Scroll position | Scrolling the background pane collapsed the top bar the user was not reading |

Cursor style and blink rate apply to every pane, and the blink rate is held
rather than only pushed on change — a pane opened after the user last touched
the setting would otherwise keep the emulator default for good, since the
settings flow does not re-emit.

`switchTab` moves focus rather than opening a session twice when it is already
on screen in the other pane. Two views driven by one session is not something
the emulator can serve: each attach resets the other's scroll position.

`closeTab` and `onSessionFinished` hand their index arithmetic to
`resyncPanes()`, which remaps every pane and moves focus to one that still
has a session — so the persisted active id never names a session that is not
on screen.

### A pane outliving its session

Reconciled away, not left as an empty rectangle. `PaneLayout.reconciledAgainst`
and a `liveSessionIdsFlow` check in the screen both cover it, and the check
is guarded on the live set being non-empty so the first composition — where
no session has spawned yet — is not read as "the session you split into
died".

### Block mode

Split panes are a **classic terminal** feature. Block mode still renders one
session's blocks through a single `BlockEngineWire` and a single
`BlockRepository`, and giving each pane its own block history means the wire
and the repository become per-pane — a larger change, deliberately not smuggled
into this one.

### Known rough edges

- `FlatKeyBar` renders in both panes when no hardware keyboard is attached.
  Correct — each pane needs its own keys — but it means two identical key bars
  on one screen.
- The second pane does not repeat the MOTD widget, deliberately.
- Floating-window position is remembered, but there is no "reset layout".

### The divider resizes live, and a drag past the end leaves split view

*Corrected 2026-10-08. Both halves of this were broken, and in opposite ways.*

The panes used to be **removed from composition for the whole drag** and put
back on release, on the theory that two `TerminalView`s re-measuring per frame
juddered. The model was still written every frame and nothing consumed it
visually, so the seam moved and nothing else did — a divider that cannot do
the one thing a divider is for. Both panes now stay composed and take their
heights from the seam's live pixel position.

The second half was a clamp that disagreed with the model. The seam was
floored at 96dp at both ends, while the collapse threshold is
`COLLAPSE_FRACTION` = 0.1 — and 96dp is more than 10% of the host on every
phone that exists, so `commitDraggedFraction`'s `<=` test could never fire.
`collapsed()` was unreachable from the gesture, which is what "split viewden
çıkılmıyor" was. The seam is now clamped to `[0, height - 1]` and the model
does the real clamping.

Which pane survives the collapse is **the pane the user grew**, not the one
that happened to be primary. Dragging the seam down leaves the top pane full
height, and dropping the bottom session there would throw away the terminal
they had just made room for. `PaneLayout.collapsedSurvivingSlot()` decides;
`PaneSessionBinder.promoteSecondaryToPrimary()` carries it out, and the
promotion re-publishes the active session, the alt-buffer state and the block
engine's binding, because the primary pane's *contents* changed even though
its view and its focus did not.

`collapsed()` also resets `splitFraction` to `DEFAULT_SPLIT_FRACTION`. It used
to keep the collapse-zone value, and nothing else resets geometry, so the next
split opened with one pane a tenth of the screen tall.

---

## 7C. Smooth Pinch-to-Zoom

**Status:** `feature/pinch-zoom`, PR #38 — merged into `feature-ssh` as
`feat(terminal): smooth pinch-to-zoom`. **Not yet run on a device**; see
*Verification still owed*.

### What it does

Pinch the terminal and the font follows the fingers continuously, in **0.1sp
steps**, up to 9sp–48sp. A **size chip** follows the pinch (above the fingers,
clamped inside the pane) and lingers ~900ms after they lift. **Double-tap**
returns to the app default, 14sp. The size is written to DataStore when the
fingers lift, not per frame. The Settings slider uses the same limits and the
same 0.1sp detent, so a size set by dragging is one the pinch can reach.

### Why it is smooth

Three costs landed in the same frame, and only one of them was visible:

| Cost | Removed by |
|---|---|
| A new renderer per event, each re-measuring 127 glyph widths | `TerminalRenderer.updateTextSize()` re-measures in place |
| Whole-sp rounding, so the size could only move in 1sp steps however little the fingers moved | the 0.1sp grid (`TerminalZoom.STEP_SP`) |
| A StateFlow emit per event, recomposing the whole screen because the size lived in a ViewModel the screen read at its root | the view owns the live size; the flow is told once, at the end |

The 0.1sp steps that do **not** change the column count cost only a repaint —
`updateSize()` reflows when the column count moves, not when the font size does.
That is the rest of it.

### How it is built now

| Piece | Where | Why there |
|---|---|---|
| `TerminalZoom` (limits, 0.1 step, 4% dead zone, per-event clamp) | `:domain` | `TerminalView` is in `:terminal`, the ViewModel in `:ui`, and `:ui` may not import `:terminal`. `:domain` is the one module both depend on. |
| `TerminalRenderer.updateTextSize()` | `:terminal` | Re-measures in place behind private setters. No `@JvmField` on them — a private setter is a custom accessor and the two cannot be combined. |
| `TerminalView.zoomTo()` + focal anchoring | `:terminal` | The row under the fingers is pinned by absolute row index, which survives the reflow that a viewport-relative one does not. |
| `TerminalViewClient.defaultFontSizeSp()` | `:terminal` | Double-tap's reset target, asked of the client so the vendored view holds no constant. |
| `onScaleBegin` / `onScale` / `onScaleEnd` in the recognizer | `:terminal` | `onScaleBegin` records the gesture's origin, `onScaleEnd` says the fingers are gone — neither is reachable from `ScaleGestureDetector`'s scale callback alone. |
| `onZoom` / `onZoomEnd` on the client | `:terminal` | Notifications, not requests: the view has already applied the size. Replaced the old `onScale(scale): Float`. |
| `ZoomChipState` + `TerminalZoomChip` | `:app` (ui/terminal) | Local state, **not** in the ViewModel — a flow read at the screen root would recompose both panes at 60Hz for a number only the chip shows. |
| `TerminalViewModel.onZoomCommitted()` | `:ui` | Publishes and persists once, on release. |
| `FONT_SIZE_DETENTS` in Settings | `:ui` | Derived from `TerminalZoom`, so the slider cannot offer a size the pinch cannot produce. |

### Gesture decisions worth keeping

- **The size is recomputed from the gesture's origin** every frame, never
  accumulated. Accumulating multiplies one rounding error per frame, and cannot
  be undone: shrinking back leaves a different size than it found.
- **The dead zone is latched**, not re-tested per frame. Testing it per frame
  froze a pinch at its peak once the fingers came back — a zoom the same
  gesture could not undo. `TerminalZoomTest` covers exactly that.
- **Per-event step clamp** (`MAX_STEP_FACTOR = 1.35`): a third finger landing
  reads as a large ratio, which would otherwise be worth several steps in one
  frame.
- **Double-tap → app default (14sp).** Deferred one frame: `onDoubleTap` arrives
  on the second ACTION_DOWN, before the gesture is known, so resetting there
  would zoom and un-zoom a double-tap that turned into a pinch. The posted block
  re-checks whether a scale is in progress — see *Double-tap reset* below.
- **0.1sp quantisation** is below what the eye resolves, and it keeps a long
  float tail (14.300000000000001) out of storage.

### Double-tap reset, and what it resets to

**It resets to the app default (14sp), not to the current size.** The current
size is what the last pinch produced, so a reset to that would do nothing. The
reset target comes from `TerminalViewClient.defaultFontSizeSp()` rather than a
constant baked into the vendored view, but it is a *default*, not a "settings
value": after a pinch there is no way to tell a size the user chose from one
the pinch produced, and treating the pinch result as the default would make
double-tap a no-op the moment anyone pinched.

The reset is **deferred by one frame** (`post {}`). `onDoubleTap` fires on the
second ACTION_DOWN, before the gesture is known — a double-tap that becomes a
pinch would zoom and then un-zoom, which is the flicker the deferral avoids.
The posted block re-checks `isInProgress()`, so a pinch that starts on the same
frame wins.

### Storage

The DataStore key behind `SettingsRepository.fontSizeSp` became
`floatPreferencesKey("font_size_sp")`. DataStore keys are typed, so an install
that upgrades cannot read the Int a previous build wrote and **falls back to
the default once**; it keeps fractional sizes from then on. The key name was
deliberately left alone so there is one key to reason about.

`TerminalZoom.MIN_SP/MAX_SP` (9/48) are wider than the slider's old 8..24, and
the Settings slider now uses the same limits *and* the same 0.1sp detent — the
two used to disagree in both range and granularity.

### Changed elsewhere as a consequence

- `TerminalViewClient.onScale(scale): Float` — the old callback, which asked the
  client to *apply* a scale and returned a scale factor — is replaced by
  `onZoom` / `onZoomEnd` / `defaultFontSizeSp`. The view now applies the size
  itself and notifies; a client cannot decline or alter it.
- `TerminalViewClientImpl`'s constructor takes `onZoomChange` /
  `onZoomEndChange` callbacks instead of `onScaleChange`.
- `TerminalViewModel.bumpFontSize()` is gone; `onZoomCommitted()` replaces it,
  and `setFontSize` takes a `Float`.
- `SettingsRepository.fontSizeSp` is `Flow<Float>`, and its setter takes a
  `Float`.
- `settings_font_size_value` takes `%1$s`, not `%1$d` — a float into `%1$d`
  throws at format time. `values/` and `values-tr/` changed together.

### Deliberately not done

- **Block mode does not pinch-zoom.** It renders in Compose with no
  `TerminalView` behind it: no gesture to claim, nothing to anchor against. Its
  font size path is untouched. Whether it *should* zoom is a product call, not
  a port of this work.
- **A pinch does not reflow on every step.** `updateSize()` reflows when the
  column count changes, not when the font size does, so the 0.1sp steps between
  two grid changes cost a repaint. Reflowing per step would make the gesture
  smooth and the terminal unusable.

### Prototype

An HTML prototype compared four models — continuous 0.1sp, 0.5sp steps, 1sp
steps, and a reflow-free canvas scale — with a live fps/reflow counter.
0.1sp continuous was chosen from it (Muhofy, 2026-10-08). `mockups/` is
gitignored, so the prototype is not in the repository; rebuild it from this
description if the question comes up again.

### Tests

`domain/src/test/.../TerminalZoomTest.kt` covers the arithmetic: rounding onto
the 0.1sp grid and its absence of a float tail, the limits surviving rounding,
every slider detent being a representable pinch step, dead-zone symmetry and the
latch that stops it gating mid-gesture, the per-event step clamp, and a
symmetric 20-frames-out-and-back gesture ending at the size it started from.

The helpers in that test **re-implement** what `TerminalView` does rather than
calling it — the class is a View, and instantiating one in a JVM test needs a
Looper and a `Paint`. They are kept in step by hand, which is the cost of that
choice and the reason each helper is named after the decision it encodes rather
than after the method it mirrors.

### Verification still owed

Everything above is what the code and `:domain:test` say. None of it has been
run on a device: the CI here builds and tests, it does not measure whether a
pinch feels right. In particular worth checking on real hardware:

- that the font tracks the fingers without visible stepping at 0.1sp,
- that the anchored row does not drift over a long pinch,
- that the chip follows, clamps at the edges and does not flicker,
- that a two-finger scroll no longer nudges the size at all,
- that a double-tap really returns to 14sp, and that a double-tap which grows
  into a pinch does not flash.

### Open

- Block mode: see *Deliberately not done* above.
- 48sp is about ten rows on a phone. Whether the top of the range is worth
  having is untested on a device.
- The DataStore key type change costs an install its saved font size once.
  Unverified on a real upgrade.

---

## 8. Input System

### Keyboard Handle & Extra Keys Bar (Phase 3 Sprint 1 — scope confirmed 2026-08-08)
- **Layered architecture:** `domain/input/` (pure Kotlin models + use cases), `data/input/` (DataStore + PTY writer), `terminal/input/` (mode-aware dispatcher), `ui/input/` (Compose composables), `app` (integration seam).
- **Trigger:** A thin Compose `KeyboardHandle` (4dp height, Material 3 `drawerHandle` styling) sits above the system keyboard. Tap → toggles `ExtraKeyBar`.
- **Visibility:** Persisted in DataStore (`InputPreferencesRepository.extraKeysBarVisible`, default false). When a hardware `KEYBOARD_TYPE_ALPHABETIC` device is detected, bar is hidden automatically (Termux convention).
- **Layout:** 2-row `FlowRow` — `ESC TAB CTRL ALT ← ↓ ↑ →` / `HOME END PGUP PGDN - |`. Mirrors TODO §"Keyboard Handle & Extra Keys".
- **Modifier state machine:** `ExtraKeyState` (port already in tree, `terminal/ExtraKeyState.kt`) — single tap = sticky one-shot (next read consumes `isActive`), long-press = popup with preset combos (`CTRL+C/Z/X/V/L/A/E`, `ALT+B/F`). Reused as-is for CTRL/ALT; SHIFT and FN not exposed in v1.
- **Key dispatch split:**
  - **Classic mode:** `TerminalView.handleKeyCode()` / `inputCodePoint()` per the existing reference path. `TerminalViewClientImpl.extraKeyState` now wired to the shared `ExtraKeyState` (currently `null`), so `readControlKey()/readAltKey()` consult the sticky state.
  - **Block mode:** text input flows through `BlockInputField` (existing `BasicTextField`). Modifier-injected bytes (`\u0003` for Ctrl+C, `\u001B` for ESC, `\u001A` for Ctrl+Z, `\u0004` for Ctrl+D) flush to PTY via new `SubmitRawByteUseCase`. `TAB` and arrow keys go into the text field (cursor move); `PgUp/PgDn` scroll the block list.
- **Open follow-ups (tracked):** Ghost text autocomplete, Shortcut overlay, Voice input — deferred to Sprint 2+.

### Shortcut Overlay
Triggered by a dedicated gesture or button (TBD — open decision).
When opened:
- Background dims
- **Left side** → Command Shortcuts (user-defined commands)
- **Right side** → Keyboard Shortcuts (key combinations)
- iOS wheel-picker style, scroll to select
- Tap outside → dismiss

**Command Shortcuts:**
User-defined. Each shortcut has:
- Name (e.g. "Deploy")
- Command (e.g. `git add . && git commit -m "update" && git push`)
- Icon
- Color
- Execute mode: run immediately OR paste only

**Keyboard Shortcuts:**
User-defined. Each shortcut has:
- Modifier: CTRL / ALT / SHIFT / CTRL+ALT
- Key: selected from full PC keyboard layout UI
- Name (e.g. "Kill Process")

**Community Shortcut Store:**
- Browse shortcut packs (Python Pack, Docker Pack, Git Flow Pack)
- One-tap install
- Export/import as JSON
- Submit own packs

### Ghost Text Confirmation
Suggestion pill appears above keyboard when ghost text is active.
Tap pill → accepts completion.

### Voice Input
Microphone button in input bar. Hold to record. Whisper API → transcript. Auto-send option in settings.

### External Keyboard
Full key mapping support. Cmd+K → command palette. Escape → dismiss overlays.

---

## 9. Session System

### Session Preview Swipe
See §6 Navigation. Shared Element Transition — card thumbnail morphs into full terminal. Real snapshot shown in preview, not placeholder.

### Session Organization
- **Name** — user-defined (e.g. `prod-server`, `dev-local`, `docker-lab`)
- **Groups** — logical grouping of related sessions
- **Favorites** — pinned to top of session list
- **Recents** — auto-sorted by last used

### Workspace (Project-Based)
*Implemented 2026-10-05. Branch `feat/workspace` — see §7D.*

Each workspace is a project:
```
Workspace: MyApp
  ├── Path: /home/user/myapp
  ├── Sessions: dev-local, test-env
  ├── Shortcuts: project-specific commands          (deferred)
  └── Workflows: deploy, test, build                (deferred)
```
- Project-aware shortcuts — **deferred**, nothing in the schema anticipates it
- Project-aware agent context — **deferred**
- Workflow automation (v1.1+) — **deferred**
- Lightweight — terminal stays primary

---

## 7D. Workspace / Project System

*Added 2026-10-05. Branch `feat/workspace`.*

### Scope, as Muhofy specified it

**Grouping plus persistent metadata. Sessions and processes are not made
persistent by it.** That sentence is the whole design, and two things follow from
it that are decisions rather than omissions:

- A workspace owns **no** PTY, keeps **no** shell alive, and reopens nothing.
  The workspace row survives a process death; the shell behind a session does
  not, exactly as it does not for an ungrouped session.
- `rootPath` is a **label, not a working directory**. Nothing reads it to decide
  where to run a shell and nothing `cd`s on the user's behalf.

Had a workspace implied a process, a dead session inside one would read as a
failure rather than the normal state of a phone that closed an hour ago.

### What is stored

```
Workspace
  id, name
  rootPath     guest path, normalised on write
  description  optional, empty is normal
  colorSeed    index into a fixed 6-slot palette the UI owns
  createdAtMs, lastOpenedAtMs
  archived
```

`sessions.workspace_id` — nullable FK, `ON DELETE SET NULL`, indexed. The
grouping lives on the session row, not as a collection on the workspace: a
workspace can hold many sessions but is not *made of* them, and a column of ids
would need its own consistency rules on every write that touched either table.

### Layering

| Layer | Files |
|-------|-------|
| `domain/workspace/` | `Workspace.kt` (model + `WorkspaceRepository` + `WorkspaceEdit` + `forStorage`), `WorkspacePath.kt`, `WorkspaceGrouping.kt` |
| `data/workspace/` | `WorkspaceEntity`, `WorkspaceDao`, `WorkspaceRepositoryImpl` |
| `ui/workspace/` | `WorkspaceScreen`, `WorkspaceViewModel`, `components/` |

`ui/` sees only `:domain`. The board the screen renders is
`WorkspaceGrouping.board(workspaces, sessions)` — a **pure** function over two
independent Room streams, which is why its three decision rules are unit-tested
rather than inferred from the screen.

### Decisions worth keeping

- **The colour is a seed index, not a stored colour.** A hex value would survive a
  theme change that made it unreadable, and a project list is read at a glance
  where two projects looking alike is the failure that matters. Every seed
  resolves to a token that already has a dark and a light value.
- **The seed is clamped on write** (`WorkspaceEdit.forStorage`), so the palette
  can be indexed without a bounds check at every read.
- **The path is normalised on write**, because it is typed by hand on a phone
  keyboard and then compared: `myapp`, `/myapp/`, `//myapp//` and
  `/home/x/./myapp` must not become four projects. `..` is *kept* — resolving it
  needs a filesystem, and a workspace path is not required to exist.
- **Deleting a workspace never deletes a session.** A grouping label is cheap to
  lose and a session's history is not. `WorkspaceRepositoryImpl.delete` also
  writes the nulls explicitly, because `ON DELETE SET NULL` only fires when the
  `foreign_keys` pragma is on and Room does not guarantee that it is.
- **Restoring a session keeps its grouping.** Dropping it would silently
  ungroup a session as a side effect of a restore the user thinks is harmless.
- **`assignToWorkspace` validates the id before the write**, not via the foreign
  key. A stale screen offering a since-deleted project is a UI mistake, and an
  `SQLiteConstraintException` at an arbitrary later point is a poor trade for it.
- **An archived workspace's sessions become ungrouped**, not lost. The group is
  gone as far as the user can see, so pretending the session still belongs to
  something would be worse than showing it loose.
- **A session naming a workspace that is not in the list is ungrouped, not
  dropped.** The FK should make this unreachable; a session silently missing from
  the screen is invisible breakage.
- **The screen claims only whether a record has *ended*.** A persisted
  `SessionState.Running` is not a liveness report: it is written when a PTY
  spawns and nothing rewrites it if the process dies with the app. It is the
  resume marker `SessionManagerAdapter.ensureSessionExists` looks for — the same
  distinction `docs/SESSION-SYSTEM.md` §2 already draws.
- **Opening an ended session restores it** rather than merely activating it.
  Activation of a `Closed` row is a no-op (the terminal switches by id and there
  is no process to switch to), so the user would land back where they were having
  been told nothing. Restoring resets it to `Idle`, which is the signal
  `reconcile` already watches for. Same rule as the sidebar, same reason.
- **Nothing about grouping touches `reconcile`.** It reads `state` only, so
  `ensureSessionExists` still resumes the most recently used session across *all*
  projects. That is correct and unchanged — a project is not a resume scope.

### Reached from

Sidebar → **Projects**, and Settings → Projects. Both routes land on
`"workspace"`; opening a session from there pops back to the named terminal
destination (`terminal_home`, else `terminal`) rather than one entry, because a
single pop would land on Settings.

### Migration

`DroshDatabase` **2 → 3**, `MIGRATION_2_3`. The `sessions` table is **rebuilt**,
not altered: `workspace_id` needs a foreign key and SQLite cannot add a
constraint to an existing table. Room validates the actual schema at open time,
so a plain `ADD COLUMN` would take every existing install down at launch. The
column is appended last so the `INSERT ... SELECT` can name the original six and
read NULL — the correct value, since every session predates grouping.

### Downgrades wipe; upgrades stay fail-loud

*Decided 2026-10-08.*

Room is asymmetric on purpose:

- **Up** — an unregistered migration **throws**. Shipping a schema change
  without the migration that carries it is a mistake, and it should be loud.
- **Down** — `fallbackToDestructiveMigrationOnDowngrade(dropAllTables = true)`
  recreates the tables. A downgrade is a developer action, not a shipping
  defect, and the alternative was an app that could not launch at all with no
  way out short of clearing app data.

The root cause was not Room. **Every build shipped `versionCode = 1`**, and
Android refuses a *lower* versionCode but accepts an *equal* one — so
checking out an older commit and installing it over a newer one succeeded
silently (same application id, same committed debug keystore, no uninstall) and
carried `databases/irisshell.db` over untouched at the newer schema version. The
older build then had no migration in either direction.

`versionCode` is now `10_000 + <commit count>`, read from git in
`AndroidApplicationConventionPlugin`, so the ordering of versions *is* the
ordering of history and an older commit cannot be installed at all.
`-PversionCode=` / `-PversionName=` override it.

Two consequences that are easy to get wrong:

- **CI needs `fetch-depth: 0`.** `actions/checkout` defaults to depth 1, and a
  shallow clone counts 1 — every CI APK would come out as 10001 and be
  interchangeable with every other, reintroducing the downgrade. All three
  workflows now fetch full history, and the plugin warns loudly if it detects
  a shallow checkout.
- **The downgrade fallback is the second line of defence, not the only one.**
  History can be rewritten and the commit count is not a guarantee.

`data/schemas/*.json` are committed and CI diffs them after `assembleDebug`,
which is what turned three stale-identity-hash crashes into red builds.

### Tests

`:domain:test` (runs in `tests.yml`) — `WorkspacePathTest`,
`WorkspaceEditTest`, `WorkspaceGroupingTest`, `PaneLayoutTest`.

### Not built

- **No project-scoped shortcuts**, no workflow builder — both are in §9 as
  deferred, and nothing here anticipates their schema.
- **Agent chats are not grouped.** A chat has a `working_directory` and would fit,
  but §9 lists sessions only and grouping it is a separate product call.
- **No file tree, no "open in editor", no git state** for a project.
- **No search on the project list.** The sidebar already searches every session
  and the project list is far shorter, so a second search field would be a
  duplicated one.
- **An archived project cannot gain sessions** — only be restored, edited or
  deleted. Restoring first is the obvious path and the sheet says so.
- **No drag-to-reorder.** Projects are ordered by `lastOpenedAtMs`, which is the
  right default until the user disagrees with it.

### Making it usable — `feat/workspace-ux`

The grouping worked. Nothing around it did.

- **Creating a session inside a project** was the missing primary action, and
  `SessionRepository.create(name, workspaceId)` had shipped with no caller. That
  made every new project a dead end: open a session in the sidebar, come back,
  long-press it, pick the project. Three screens to do what one tap should.
- **Two empty states, not one.** The empty state fired only when both the project
  list and the loose-session list were empty, so the state the app is actually in
  by default — a launch creates a `Default` session, you have not made a project
  — rendered as a bare list saying nothing. "Nothing here" is a first run; "you
  have sessions and none are filed" needs telling what to do about it.
- **Moving a session got a visible button.** Long press was the only way and
  nothing on the row hinted that pressing and holding did anything, so the feature
  existed only for people who happened to try it. The gesture stays; the button is
  why anyone finds it.
- **Archiving became reachable.** `archived`, `observeArchived()` and
  `setArchived()` had shipped with zero callers — a column nobody could set.
  Archive section at the bottom, restore from there, archive/unarchive in the
  edit sheet, placed **above** delete so the reversible option is not the one
  sitting next to the irreversible one.
- **The archive row states its own consequence** — "3 session projeler arasında".
  Archiving hides the project and stops the grouping being *displayed*; the
  grouping itself survives. Without the number, the ungrouped section reads as if
  archiving had destroyed it.
- The accent is a **bar down the left edge** rather than a border around the card.
  A border draws all four sides and reads as a selection state; a bar reads as
  "this thing has a colour", which is what it is. It also replaces a 10dp dot that
  disappeared on a dim screen at arm's length — the one job the colour was doing.
- Expansion **survives rotation and the trip to the terminal**, which `remember`
  alone did not: a list that folds itself up every time you come back from opening
  a session is a list you stop trusting.
- The top-bar `+` is gone. It duplicated the bottom button, which is the one a
  thumb can actually reach; two controls for one action reads as neither being
  sure which to press.

One trap worth remembering: the expanded-set state is a `List`, not a `Set`,
because it lives in `rememberSaveable` and a `Set` has no `Bundle`-compatible
`Saver`. It would throw at the first rotation rather than failing to compile.

---

## 9b. System-wide floating pane — **not built, and this is the reason why**

*Requested 2026-10-05. Not attempted, and the reason is structural rather than
a matter of time.*

### The request

The floating pane should stay on screen over other apps, with permission.

### Why it is not just a service and a window

The obvious implementation is a started service that adds a
`TYPE_APPLICATION_OVERLAY` window, and it is genuinely most of the work:

| Piece | Size |
|-------|------|
| `SYSTEM_ALERT_WINDOW` in the manifest | one line |
| `Settings.canDrawOverlays` check plus the Settings intent | ~10 lines |
| A started foreground service with its channel and a permanent notification | ~80 lines |
| `WindowManager.addView` / `updateViewLayout` from `PaneLayout`'s fractions | ~60 lines |
| Moving the pane's session between the two windows | **the problem** |

The last row is where it stops being a service.

`TerminalView` is created by an `AndroidView` inside the Activity's composition
(`TerminalScreen.kt`, `factory = { ctx -> TerminalView(ctx, null) }`). It is not
an ordinary View that can be reparented — it is the output of a composition, with
a `LifecycleOwner`, a `Context` and a place in the tree. Putting it in a window
owned by a service means:

1. **A second composition root.** A window added by a service has no
   `LifecycleOwner`, no `SavedStateRegistryOwner` and no `ViewModelStore`. The
   View cannot simply be handed over — something has to own a composition for it,
   and that is either a `Dialog` or `Popup` (which Compose ties back to the
   Activity anyway) or a hand-rolled `Recomposer` whose lifecycle, frame clock and
   callbacks have to be torn down correctly or the app leaks a running frame loop.
2. **One session, one view.** `TerminalManager.paneForSession` and
   `registerPaneView` already track which pane holds which session, precisely
   because a `TerminalSession` cannot be attached to two `TerminalView`s at once —
   `attachSession` resets the emulator, so two views on one session fight. Moving
   the pane to the overlay therefore means *removing* it from the Activity's tree
   first and creating it again in the new one. That is a handover, not a second
   window, and it has to be atomic or the user sees an empty rectangle for a frame
   — or forever, if it fails.
3. **Input routing.** `FLAG_NOT_FOCUSABLE` is mandatory, so the pane must not
   steal the keyboard from the app underneath. Without focus the pane cannot
   receive typing; with it, the app underneath stops working. A floating terminal
   you cannot type into is a wallpaper of a terminal, and a floating terminal that
   takes the keyboard stops being floating.

Point 3 decides it. Making it typable means taking the IME away from whatever the
user was actually doing — a behaviour to decide on purpose, not a bug to fix later.

### What a real version needs, in order

1. **Decide the input model.** Read-only (honest, useless), tap-to-focus (usable,
   steals the keyboard), or a separate IME flag (needs `EditorInfo` work). Every
   other piece depends on this, and it is a product question.
2. **Hand the session over atomically.** One `TerminalView` at a time, created by
   whichever root currently owns the pane. `TerminalManager` needs a "this pane has
   no view right now" state — `registerPaneView` assumes the pane is on screen.
3. **Then** the service, the permission, and the window.

### What is built instead

The in-app floating pane — move, resize, drag off screen, dock, park against an
edge — all works and is tested. That is the same feature minus the part that needs
an answer to question 1.

---

## 10. Agent Core

### Architecture (Phase 6 — implemented)
Single-provider model (per Muhofy instruction: "endpoint girme, isim girme").
Port of Iris Code's `OpenAiChatClient` + `OpenAiProviderAdapter`.

```
AgentSession (domain interface)
  └── AgentRuntime (agent/ module impl)
      ├── ProviderAdapter → OpenAiSseAdapter
      ├── ToolRegistry → ShellTool
      └── MAX_STEPS = 20 (mobile battery/CPU constraint)
```

### Provider Configuration
- `ProviderConfig.baseUrl` (base URL, e.g. `https://openrouter.ai/api/v1`)
- Chat URL: `"${baseUrl.trimEnd('/')}/chat/completions"`
- Models URL: `"${baseUrl.trimEnd('/')}/models"` (no auth required on OpenRouter)
- `isOpenRouter` detected via `baseUrl.contains("openrouter")`

### OpenRouter Headers
- `Authorization: Bearer <API_KEY>` (required)
- `HTTP-Referer: https://github.com/mmuhofy/IrisCode` (recommended for attribution)
- `X-OpenRouter-Title: Drosh` (app name for rankings)
- `Content-Type: application/json`

### Request Format
- System prompt as `role: "system"` message in messages array (NOT `system` field)
- Body: `{ model, messages, stream: true, tools: [...] }`
- SSE: `[DONE]` → `finish_reason:"stop"` → `StreamEnd`; `:ping` comments ignored
- `finish_reason:"error"` → `StreamEvent.Error` (not StreamEnd)

### Error Handling (per OpenRouter docs)
- Pre-stream: HTTP 4xx/5xx, body `{"error":{"code":401,"message":"User not found."}}`
- Mid-stream: 200 OK, SSE `{"error":{...},"choices":[{"finish_reason":"error"}]}`
- `onFailure`: raw response body passed through; JSON `error` extracted by `parseSseLine`

### Default Provider
- OpenRouter only: `https://openrouter.ai/api/v1`, model `meta-llama/llama-3-8b-instruct`
- Plus "Custom" for arbitrary OpenAI-compatible endpoints
- User enters base URL + API key + fetches models from `/models`

### Tool Set
| Tool | Description | Mode |
|------|-------------|------|
| `shell` | Execute shell command via PRoot | BUILD |
| `read_file` | Read file into agent context | PLAN + BUILD |
| `write_file` | Write file, diff+approve flow | BUILD |
| `ask_user` | Ask user a question | PLAN + BUILD |
| `update_todo` | Create/update TodoCard | PLAN + BUILD |
| `web_search` | Tavily API search | PLAN + BUILD |

### Work Mode
| Mode | Behavior |
|------|----------|
| PLAN | Read-only. Agent suggests only. |
| BUILD | Full tool use. Diff/approve active. |
| AUTO | BUILD + autonomy toggles forced ON. |

### Agent in Terminal Context
Agent operates in the same shell environment as the user. Same PRoot Ubuntu session. Agent can:
- Read current directory
- Execute commands
- Observe output
- Chain commands across steps

User terminal and agent share the same filesystem. Agent bash output → Agent tab (not user terminal).

---

## 11. Features

### Detailed Features

#### Command DNA
Every command is automatically indexed:
- Timestamp
- Working directory
- Exit code
- Duration
- Output summary (first 3 lines)
- Session ID
- Tags (auto-detected: git, docker, ssh, python...)

Stored in Room FTS5. Queryable:
- "What did I do on the prod server last week?"
- "Show all failed commands today"
- "Find when I last ran npm install"

#### Session Intelligence
Drosh indexes all sessions. Natural language queries across session history. Uses FTS5 full-text search. Agent layer for complex queries.

#### Natural Language Cron
```
"Every morning at 8am run git pull"
"Every Monday clean server logs"
"Every 5 minutes ping health endpoint"
        ↓
Drosh parses → generates cron expression
        ↓
WorkManager schedules job
        ↓
Runs in background, reports results
```
No cron syntax required. Managed from Settings → Scheduled Tasks.

#### Agent Watch
Background monitoring with natural language conditions:
```
"Tell me when this build finishes"
"Alert me if CPU goes above 90%"
"Notify me if this endpoint goes down"
```
WorkManager + Foreground Service. Sends Android notification when condition met. Notification actions: View, Dismiss, Repeat.

#### Drosh Autopilot
Multi-step task execution:
```
User: "Deploy the app to production"
        ↓
Drosh: shows step plan
User: approves
        ↓
Drosh executes step by step:
  1. git add . && git commit -m "..."
  2. git push origin main
  3. ssh server "pm2 restart app"
  4. curl https://app.com/health
        ↓
Reports result, notifies on completion
```
Each step shown as a block. User can pause between steps. Agent handles errors and retries.

#### Live Share
Real-time terminal session sharing between Drosh instances:

**Initiator:** generates share link / QR code
**Guest:** opens link → joins session

Permission levels:
| Level | Can Do |
|-------|--------|
| Read Only | Watch terminal output |
| Suggest | Submit commands for approval |
| Execute | Run commands directly |
| Full Control | Full terminal access |

SSH-inspired but human-first. No external server required for LAN sharing. Relay server for internet sharing (v1.1+).

#### Terminal Lens
Point camera at any terminal, monitor, document, or paper:
- OCR reads the text
- Drosh parses it as a command or output
- Shows: "Run this command?" → user approves
- Eliminates manual retyping

Triggered from input bar camera icon.

#### HUD (Heads-Up Display)
Configurable status strip at top of terminal screen:

Available widgets:
- CPU usage %
- RAM usage %
- Network ↑↓ speed
- Active job count
- SSH connection status
- Clock
- Battery %
- Disk usage

User selects which widgets to show. Drag to reorder. Can be hidden entirely.

#### SSH Constellation (v1.1)
Visual map of all SSH hosts:
- Graph layout — nodes = servers, edges = jump host relationships
- Tap node → connect
- Color by status: green (connected), gray (idle), red (unreachable)
- Shows active sessions per host

#### Theme Store
- Browse community themes
- Preview with real terminal screenshot
- One-tap install
- OLED-optimized themes flagged
- Submit own theme (JSON format)
- Font packs included

#### Alias Manager
GUI for shell aliases:
- Name: `dc`
- Command: `docker-compose`
- Scope: global or per-workspace
- Sync to `.zshrc` / `.bashrc` automatically

#### Dangerous Command Warn
Intercepts and warns before:
- `rm -rf *` or `rm -rf /`
- `chmod 777` on system paths
- `dd if=`
- Fork bomb patterns
- Any command in a **Production-tagged** session

Warning card shows: what will happen, estimated impact, confirm / cancel.

#### Smart Sudo
Before any `sudo` command:
- Drosh explains what the command will do
- Shows affected files/paths
- Risk level: LOW / MEDIUM / HIGH
- User confirms → executes
- Even in AUTO mode

#### Production Tag
Mark any SSH host or session as "Production":
- Red banner at top of terminal screen: `⚠️ PRODUCTION`
- Every command shows confirm prompt
- Dangerous Command Warn always active
- Cannot be accidentally dismissed

#### Session Replay
Every session is recorded as a command sequence:
- Replay step by step
- Pause, rewind, fast-forward
- Copy any command from replay
- Export as shell script

#### Output Intelligence
Agent analyzes command output automatically:
```
$ npm install
  ↓
┌──────────────────────────────────┐
│ ⚠️  3 high severity vulns found  │
│ 📦  847 packages installed       │
│ ⏱️  23.4s                        │
│ [Fix Vulns]  [Details]           │
└──────────────────────────────────┘
```
Actionable cards from raw output. Supported: npm, pip, gradle, cargo, apt, docker, git.

#### Error DNA
When a command fails:
1. Drosh detects failure (exit code ≠ 0)
2. Agent diagnoses: what failed and why
3. Suggests fix
4. If user applies fix → outcome stored
5. Next time same error occurs → Drosh proposes proven fix immediately

Learned fixes stored in Room. Per-user, per-project.

#### Multi-Exec
Send same command to multiple SSH hosts simultaneously:
- Select hosts from SSH manager
- Enter command
- Outputs shown side by side per host
- Aggregate status: X/Y succeeded

#### Process Cinema (v2.0)
Visual process manager:
- Each running process shown as a card
- CPU, RAM, uptime per process
- [Stop] [Restart] [Logs] actions
- Agent: "Why is this process using so much RAM?"

---

### Planned Features (name only)

Input & Completion: Typo Fixer, Command Template, Pipe Suggestions, Sudo Remember, History Dedup

Output: Output Pin, Output Diff, Table Renderer, JSON Renderer, Progress Detector, Output Filter, Line Numbering

Files: Drag & Drop Upload, Quick Edit, File Size Warning, Trash, Recents

SSH: Auto Reconnect, Connection Health, Offline Queue, SSH Config Import

Safety: Dry Run, Command Lock

Discovery: Man Page Viewer, Cheat Sheet, Package Search, Port Scanner Mini, Env Viewer

Productivity: Command Timer, Repeat Command, Parallel Run, Command Chain Builder, Expected Output

Quick Access: Quick Note, Screenshot to Command, Widget, Notification Actions

Accessibility: Large Font Mode, High Contrast Theme, Single Hand Mode

---

## 12. SSH System

### Stack
SSHJ 0.38.x — modern, actively maintained, clean Kotlin-friendly API.

### SshHost Entity
```kotlin
data class SshHost(
    val id: String,
    val name: String,        // "prod-server"
    val hostname: String,
    val port: Int = 22,
    val username: String,
    val authMethod: AuthMethod, // PASSWORD | KEY | KEY_WITH_PASSPHRASE
    val keyId: String?,
    val jumpHostId: String?,
    val isProduction: Boolean,
    val tags: List<String>
)
```

### SSH Key Vault
- Keys stored encrypted via Security Crypto
- BiometricPrompt unlock before key use
- Supports RSA, ED25519, ECDSA
- Built-in key generator
- Import from file or paste

### Features
- Port forwarding (local + remote)
- Jump host (bastion) support
- Mosh support (v1.1)
- SFTP browser (v1.1)
- Known hosts management
- Fingerprint visual verification
- Session timeout + auto-lock
- Keep-alive ping

---

## 13. Security & Privacy

- API keys encrypted via AndroidX Security Crypto
- SSH keys encrypted, biometric unlock
- Smart Sudo — explain before execute
- Production Tag — visual warning, confirm every command
- Dangerous Command Warn — intercept destructive commands
- Secret Redaction — API keys/passwords masked in output
- Incognito Session — no history, no DNA, no logs
- Clipboard auto-clear after configurable timeout
- No telemetry, no analytics, no data sent to Drosh servers

### App Lock — 4-digit PIN

- **Phase**: v0.2 — Settings (post-onboarding toggle)
- **Module**: `domain/settings/PinLockRepository.kt`, `data/settings/PinLockRepositoryImpl.kt`, `data/di/SecurityModule.kt`
- **Storage**: SHA-256 hash in EncryptedSharedPreferences (hash only, never plaintext PIN)
- **Key scheme**: `MasterKey.KeyScheme.AES256_GCM` — required in AndroidX Security Crypto 1.1.0-alpha06 (runtime crash if omitted)
- **Prefs encryption**: `PrefKeyEncryptionScheme.AES256_SIV` (keys), `PrefValueEncryptionScheme.AES256_GCM` (values)
- **EncryptedSharedPreferences.create** signature: `create(context, fileName, masterKey, prefKeyScheme, prefValueScheme)` — 4-arg, no default scheme
- **PIN length**: 4 digits (hard-coded `4` in UI after KSP companion-object import issues)
- **Gate**: `MainActivity.collectAsStateWithLifecycle(initialValue=false)` at "terminal" route → if enabled, route to PIN screen, verify before "terminalHome"
- **Onboarding**: optional "Security" scene — `PinSetupScreen` (enter + confirm); skippable
- **Known build issues resolved**: KSP companion `PIN_LENGTH` import unsupported → inline literal; Compose 1.8 `border` = `foundation.border` + `BorderStroke`; `TextFieldDefaults.colors(focusedContainerColor=...)` (not `containerColor`)

---

## 14. Notifications

| Event | Notification | Actions |
|-------|-------------|---------|
| Long command finished | "✓ Command done — exit 0" | View Output |
| SSH connection dropped | "⚠️ prod-server disconnected" | Reconnect |
| Agent task completed | "Drosh finished — 3 files changed" | View |
| Cron job finished | "Scheduled task: git pull done" | View Log |
| Agent Watch trigger | "CPU hit 94% on prod-server" | Open Terminal |
| Autopilot paused | "Waiting for your approval" | Approve / Cancel |

---

## 15. Current Status

### From Iris Code — Direct Port
```
✅ ProotRunner.kt
✅ UbuntuBootstrap.kt (+ log emission + lastFailedStep tracking)
✅ TerminalManager.kt
✅ AgentLoop.kt → AgentRuntime.kt (simplified)
✅ MultiStepStreamer.kt → bounded loop in AgentRuntime
✅ OpenAiProviderAdapter.kt → OpenAiSseAdapter.kt (OpenRouter headers, system msg)
✅ WebSearchTool.kt (v2.0+)
✅ BashTool.kt → ShellTool.kt (name="shell")
✅ termux-view (vendored JNI)
✅ libtermux.so
✅ Visual Identity (colors, typography)
✅ Settings / API Vault architecture
✅ Hilt module structure

### Boot / Setup UX (this milestone)
```
✅ First-launch pipeline refactored to MVVM:
   - domain/terminal/BootstrapStep, BootstrapProgress, BootstrapError
   - domain/terminal/ObserveBootstrapUseCase, TriggerBootstrapUseCase, ObserveFirstLaunchUseCase
   - data/terminal/BootstrapObserver, TriggerBootstrap, FirstLaunchRepositoryImpl
   - data/di/BindingsModule, ApplicationScopeModule
   - terminal/BootstrapStatePort (logs as SharedFlow + state as StateFlow)
   - ui/setup/BootstrapViewModel, OnboardingViewModel
✅ BootstrapStepperScreen — 5-row Material 3 stepper with pulse-halo,
   determinate progress bar, ETA, animated current-message caption.
✅ LiveLogCard — collapsed default chip, expandable to 320dp scrollable
   drawer; auto-scrolls tail while bottom-pinned; 200-line ring buffer.
✅ SetupRecoveryScreen — 4 recovery buttons (Retry / Re-download / Reset / Report).
   Report path copies diagnostic bundle (error + last 50 logs + device info)
   then opens GitHub issues/new.
✅ OnboardingScreen — 4-page HorizontalPager (Welcome / Architecture /
   Pick Shell / Ready). Persists firstLaunchCompleted in DataStore.
✅ ui → terminal import seam removed; UI only consumes :domain types.
   Sole remaining direct import: UbuntuSetupState.Ready passed to the legacy
   TerminalScreen signature (tracked as follow-up).
⏳ Follow-up: refactor TerminalScreen to consume a :domain state type.
```

### To Build from Scratch
```
✅ Smooth Pinch-to-Zoom (feature/pinch-zoom, PR #38) — §7C.
       0.1sp continuous zoom, focal anchoring, size chip, double-tap reset,
       dead-zone latch, `TerminalRenderer.updateTextSize` in place.
       Block mode: no pinch (renders in Compose) — tracked in TODO.md.
⬜ BlockEngine.kt — block-based output
⬜ SemanticParser.kt — output intelligence
⬜ GhostTextEngine.kt — inline autocomplete
⬜ KeyboardHandle.kt — toggle UI
⬜ ShortcutOverlay.kt — left/right picker
⬜ SessionPreviewSwipe.kt — shared element
⬜ SshjManager.kt — SSH client
⬜ SshKeyVault.kt — encrypted key store
⬜ CommandDnaDao.kt — FTS5 indexing
⬜ SessionReplay.kt — recording/playback
⬜ NaturalLanguageCron.kt — WorkManager cron
⬜ AgentWatch.kt — background conditions
⬜ HudEngine.kt — status widgets
⬜ ThemeStore.kt — theme engine
⬜ AliasManager.kt — shell alias sync
⬜ MultiExec.kt — broadcast SSH commands
    ✅ Workspace.kt, WorkspaceRepository, WorkspacePath, WorkspaceGrouping (§7D)
    ✅ SSH end-to-end (feature-ssh, PR #36): SshHost/SshKey Room layer,
       bridged TerminalSession over SSHJ ShellChannel into the vendored
       termux TerminalEmulator, sidebar → SSH → host list → interactive tab,
       Trust-On-First-Use host key verifier, EncryptedSharedPreferences
       credential vault, keep-alive via KeepAliveProvider. Mosh/SFTP/
       jump-host/port-forwarding still pending.
    ✅ SSH data layer (feature-ssh, PR #36): SshHost/SshKey domain models +
       repositories, SshHostEntity/SshKeyEntity/DAOs, DroshDatabase 4→5 +
       MIGRATION_4_5, Hilt bindings. Interactive session wiring and SSHJ
       manager still pending; SSHJ ShellChannel will bridge into the vendored
       termux TerminalEmulator (no local openssh shell). SSH UX: sidebar entry
       → host list → tap opens an interactive SSH session as a regular
       terminal session.

### WebViewSheet (browser)
- back/forward/reload toolbar icons (enabled via `canGoBack`/`canGoForward`); `WebChromeClient.onProgressChanged` → Material3 progress bar; 3-dot dropdown (`DroshDropdownMenu`: Copy URL / Open in Browser / Reload).
- Bug fix: `shouldOverrideUrlLoading` must call `view.loadUrl(...)` — previously dropped, causing blank/black WebView on internal link clicks.
- Optimizations: `LAYER_TYPE_HARDWARE` + transparent bg (SO black-screen fix), `useWideViewPort`, `offscreenPreRaster`, `LOAD_DEFAULT` cache, `safeBrowsingEnabled`; `destroy()` in `DisposableEffect`.
- `ModalBottomSheetState(skipPartiallyExpanded = false)`; opens half-expanded, drag up to fill, down to dismiss; removed the WebView `detectDragGestures` consume so the handle thumb actually drags the sheet; larger circular tap targets (22dp/20dp icons).
```

### URL Detection (TUI + Block)
- `UrlDetector` regex ported from termux-app `TermuxUrlUtils.URL_MATCH_REGEX` (full scheme list + IPv4/host/port/path/query/fragment grammar), plus a bare `www.` pattern; matches sorted by position and normalised (`https://`).
- Block-mod links: `SpanStyle(DroshPrimary, TextDecoration.Underline)` via `AnnotatedString` + tap→offset→`onUrlClick` in `BlockBody`.
- Classic TUI links: `SearchHighlightOverlay` draws a **dashed** `DashPathEffect` underline (DroshPrimary) over URL cells; `TerminalViewClientImpl.onSingleTapUp` → `getWordAtLocation` → `findUrls` → `onUrlClick` → open in `WebViewSheet`.
- TUI link algılama: **evet**, zaten aktif — overlay + tap handler.
- Note: Compose `TextDecoration` only exposes a solid `Underline` constant (dashed ctor is internal), so dashed underlines are Canvas-only (TUI overlay); block-mod uses a solid primary underline.

---

## 16. Open Decisions

| # | Decision | Options | Notes |
|---|----------|---------|-------|
| 1 | Command Shortcuts trigger | Gesture? Button? Dedicated key? | TBD |
| 2 | Shortcut Overlay trigger | Same as above | Linked to #1 |
| 3 | Session Navigator trigger | Long swipe? Button? Bottom sheet? | TBD |
| 4 | Ghost text confirmation | Pill tap vs sağa swipe | Pill leading candidate |
| 5 | App icon & splash | Eye concept? Shell concept? | TBD |
| 6 | Onboarding | How many steps? What to show? | Resolved 2026-07-24: 4 pages — Welcome / Architecture / Pick Shell / Ready |
| 7 | Terminal background | Solid / blur / glassmorphism depth | TBD |
| 8 | General UI direction | Material You depth vs minimal | Resolved 2026-07-25: deliberately avoids Material 3 expressive animations. M3 baseline, monospace prompt, dark gold-on-black, near-zero animation." |
| 9 | Live Share relay | Self-hosted? Third-party? | v1.1 concern |
| 10 | Workspace depth v1.0 | Full workflow builder or just project tagging? | TBD |
| 11 | Split in Block mode | Per-pane block engine, or classic-only? | Resolved 2026-10-05: classic-only for now. Block mode has one wire and one repository; per-pane blocks is a bigger change than it looks. |
| 12 | Split gesture | Drag the row, or a dedicated grip? | Resolved 2026-10-05: dedicated grip. One gesture cannot reliably mean two things in Compose — the detectors race on the same timeout. |
| 13 | Split depth | Arbitrary nesting, or two panes? | Resolved 2026-10-05: two. Nested panes on a phone are too narrow to read, and each is another divider to discover. |
| 14 | sora-editor licence | Accept LGPL-2.1-or-later inside a GPL-3.0 app? | **OPEN — Muhofy must sign off.** Compatible in principle, but blocks F-Droid until confirmed. See §7A. |
| 19 | Pinch zoom step | Whole sp, 0.5sp, or continuous 0.1sp? | Resolved 2026-10-08: continuous 0.1sp, chosen from an HTML prototype. Block mode does not zoom. See §7C. |
| 20 | Double-tap zoom reset | Back to 14sp, or to the last size set in Settings? | Resolved 2026-10-08: back to the app default (14sp). After a pinch the persisted size *is* the pinched one, so "the settings value" is indistinguishable from "what the pinch just produced" — resetting to it would be a no-op the moment anyone pinched. |
| 15 | zsh `$ENV` breakage | Inject into `~/.zshenv`, or leave OSC 133 dead on zsh? | **OPEN.** Touches a user file, so not done unilaterally. Also fixes the block engine's command lifecycle on the default shell. See §7A. |
| 16 | Editor surface | Split view with the terminal, or full screen? | Resolved 2026-10-05: full screen, separate route. One document at a time, no tabs. |
| 17 | Split axis | Side by side, or top-to-bottom? | Resolved 2026-10-05: top-to-bottom (PR #27). Two 180dp columns are ~10 characters wide, narrower than most paths. |
| 18 | Divider | Free positioning, or snapped steps? | Resolved 2026-10-05: five positions, 20% apart. Free positioning means holding the divider with a finger to keep it where you put it. |

---

## 17. Reference Repositories

| System | Reference |
|--------|-----------|
| Terminal engine | `termux/termux-app` |
| Agent loop | `anomalyco/opencode` |
| SSH client | `hierynomus/sshj` |
| Diff | `java-diff-utils` |
| Iris Code | `mmuhofy/IrisCode` — primary reference |