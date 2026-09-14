# Drosh — Memory Bank
_Last updated: 2026-09-13_

Last commit: `3efe15e` — fix: remove isMinifyEnabled=true from library convention plugin + add hilt_aggregated_deps keep rule in ui/proguard

### Release Build Crash Fix (2026-09-13)

**Root cause:** `build-logic/.../AndroidLibraryConventionPlugin.kt` set `isMinifyEnabled = true` for release build type. The `ui` module's `proguard-rules.pro` only had `-keep class com.iris.drosh.ui.** { *; }` which does NOT cover `hilt_aggregated_deps.*` classes. R8 stripped these Hilt-generated Dagger aggregation modules, leaving the `ViewModelC` Dagger component with an empty `hiltViewModelMap`. When `HiltViewModelFactory.create()` looked for `OnboardingViewModel` in the map, it found nothing and fell through to `NewInstanceFactory.create()` (reflection), which failed with `NoSuchMethodException` since `OnboardingViewModel` has no no-arg constructor.

**Fix:**
1. Removed `isMinifyEnabled = true` + `isShrinkResources = true` + `proguardFiles(...)` from `AndroidApplicationConventionPlugin` and `AndroidLibraryConventionPlugin` — release builds now use `isMinifyEnabled = false` by default (matching Phase 1's simplicity)
2. Added `-keep class hilt_aggregated_deps.** { *; }` to `ui/proguard-rules.pro` as a safety net for future R8 enablement
3. Added `--refresh-dependencies --no-configuration-cache` to release CI workflow to bypass all Gradle caches

### Icon System — Final Architecture (2026-09-11)
- ✅ **Library**: `io.github.ardasoyturk.compose.icons:lucide-android:2.0.7` from Maven Central (replaces local AAR + thelacspace library)
- ✅ **API**: `compose.icons.LucideIcons` object with extension properties in `compose.icons.lucideicons` package (e.g. `LucideIcons.PanelLeft`)
- ✅ `DroshIcons.kt` — thin wrapper: 37 `ImageVector` constants delegating to `LucideIcons.*` extension properties
- ✅ Only name difference: thelacspace `XCircle` → ardasoyturk `CircleX` (aliased in `DroshIcons.kt`)
- ✅ `ui/libs/` deleted (no more local AAR files)
- ✅ All 50 call sites across `app/` + `ui/` use `DroshIcons.*` properties (no changes needed)
- ✅ CI build passes — no more crash from missing drawable resources in app module
- ✅ New icons added: `ArrowRight`, `Gauge`, `Info`, `Timer`, `Type` (for settings screen), `Minus`, `Plus`, `Shield`, `CircleUser`


---

## 1. Project Identity

| Field | Value |
|-------|-------|
| App name | Drosh |
| Package | `com.iris.drosh` |
| Tagline | "Your phone is a Unix machine. Finally." |
| License | MIT |
| Distribution | F-Droid first, GitHub Releases |
| Repo | github.com/mmuhofy/Drosh |
| Ecosystem | Drosh — by Muhofy |

---

## 2. Confirmed Stack

| Component | Decision | Notes |
|-----------|----------|-------|
| Language | Kotlin 2.1.0 | Iris Code parity |
| UI | Jetpack Compose BOM 2026.04.01 | Proven working combo |
| Architecture | MVVM + Clean Architecture | Strict layering |
| DI | Hilt 2.57 | Iris Code parity |
| Min SDK | 26 | Android 8.0+ |
| Target SDK | 28 | Android 14 |
| Compile SDK | 36 | — |
| Terminal Engine | termux-view + termux-terminal-emulator | Vendored |
| PTY | libtermux.so (JNI) | Prebuilt, port from Iris Code |
| Linux Env | PRoot v5.2.0 + Ubuntu 24.04 rootfs | Port from Iris Code |
| Storage | Room 2.8.4 + FTS5 | Session history |
| Preferences | DataStore 1.1.x | Settings, active session id |
| Async | Kotlin Coroutines + Flow | Reactive streams |
| Build | Gradle KTS + Version Catalog | Iris Code parity |

---

## 3. Architecture Layers

```
ui/           → Compose screens, ViewModels
domain/       → Pure Kotlin interfaces, use cases
data/         → Repository impls, Room DAOs, SessionManagerAdapter
terminal/     → PTY session management, TerminalManager, DroshSession
di/           → Hilt modules
util/         → Constants, helpers
```

### Session Data Flow

```
Compose Screen (SessionSwitcherSheet / ReadyScreen)
      ↓ UI events
SessionSwitcherViewModel (exposes allSessions, activeId, shouldExit)
      ↓ calls
ObserveActiveSessionUseCase / SessionRepository
      ↓ calls
SessionRepositoryImpl (Room + DataStore, _shouldExit MutableStateFlow)
      ↓ bridges
SessionManagerAdapter (implements SessionLifecycleCallbacks)
      ↓ calls
TerminalManager (DroshSession list, PTY lifecycle)
      ↓ owns
TerminalSession (PTY emulator) + TerminalSessionClientImpl
```

### Exit Signal Flow

```
User deletes last session OR last session exits naturally:
  TerminalManager.closeTab() or onSessionFinished()
      → irisSessions empty
      → onSessionsEmpty() / onLastSessionExited()
      → SessionManagerAdapter
      → sessionRepository.setShouldExit(true)
      → SessionRepositoryImpl._shouldExit.value = true
      → SessionSwitcherViewModel.shouldExit (StateFlow)
      → ReadyScreen LaunchedEffect { onExit() }
      → MainActivity: LocalContext.current.finish()
```

---

## 4. Session System — Current Implementation

### Key Files

| File | Role |
|------|------|
| `domain/session/SessionSnapshot.kt` | Session state model (id, name, state, timestamps, live lines) |
| `domain/session/SessionRepository.kt` | Repository interface |
| `data/session/SessionEntity.kt` | Room entity |
| `data/session/SessionDao.kt` | Room DAO |
| `data/session/SessionRepositoryImpl.kt` | Room + DataStore impl, _livePreviews StateFlow |
| `data/session/SessionManagerAdapter.kt` | Bridges Room ↔ TerminalManager; implements SessionLifecycleCallbacks |
| `terminal/DroshSession.kt` | Wrapper: TerminalSession + persistentId + name + pid |
| `terminal/SessionLifecycleCallbacks.kt` | Callback interface for session lifecycle events |
| `terminal/TerminalManager.kt` | PTY session lifecycle; single irisSessions list + idToIndex map |
| `terminal/TerminalSessionClientImpl.kt` | TerminalSessionClient impl; forwards onSessionFinished + onPidChanged |

### Key Improvements (2026-09-06)

**Bug fixes:**
- `TerminalManager.onSessionFinished()` now cleans up `idToIndex` mappings (previously leaked stale entries)
- `onSessionFinished()` now calls `SessionLifecycleCallbacks.onSessionFinished` so Room state updates to Closed when PTY exits
- PID tracking wired: `TerminalSessionClientImpl.setTerminalShellPid` → `TerminalManager.onSessionPidChanged` → stores pid on `DroshSession`

**Architecture:**
- Replaced 4 parallel structures (`_sessions`, `_tabNames`, `_idToIndex`, `_indexToId`) with single `MutableList<DroshSession>` + `idToIndex`
- `SessionManagerAdapter.reconcile()` now compares Room state against `TerminalManager.liveSessionIds()` instead of stale `lastIds` delta — ensures Closed sessions aren't re-spawned, restored Idle sessions ARE spawned
- `SessionManagerAdapter` implements `SessionLifecycleCallbacks`, wires itself via `terminalManager.lifecycleCallbacks = this` in `start()`

### Inspiration

- Termux session lifecycle: `TermuxService` → `TermuxShellManager` → `TermuxSession` → `TerminalSession` → `TerminalSessionClient`
- ReTerminal id-keyed session map: `HashMap<String, TerminalSession>` in SessionService
- Iris Code: `TerminalManager.kt` port baseline

---

## 5. Session State Machine

```
Idle (Room only, not yet spawned)
  ↓ addTabWithId / reconcile spawns PTY
Running (in Room + in irisSessions)
  ↓ process exits naturally → onSessionFinished → Room → Closed
  ↓ user deletes from Room → reconcile → closeTab → PTY killed
    → if last session → onLastSessionExited → shouldExit=true → Activity.finish()
Closed (Room only, removed from irisSessions)
  ↓ user restores → restoreSession → Idle → reconcile spawns PTY
    → shouldExit reset to false on create()
```

### Bug Fixes (2026-09-06 — closeTab + app exit)

- **BUG**: `closeTab` had `if (irisSessions.size <= 1) return` guard preventing
  the last session from ever being closed. User deletes session → Room row
  removed → `reconcile()` calls `closeTab` → guard blocks it → terminal
  never closes, session stuck "Running" in Room forever.
- **FIX**: Removed guard; `closeTab` now allows closing last session.
  When `irisSessions` becomes empty, immediately calls
  `onSessionFinished(persistentId, -1)` + `onLastSessionExited()`.
- **FIX**: `onLastSessionExited` now sets `shouldExit=true` (signals app exit)
  instead of creating a replacement session. Mirrors Termux's
  `TermuxService.updateNotification() → requestStopService()` pattern.
- **FIX**: Default session creation moved from `TerminalViewHost.LaunchedEffect`
  (UI layer) to `SessionManagerAdapter.reconcile()` (running on appScope,
  survives Activity recreation). `create()` resets `shouldExit=false`.
- **FIX**: `SessionRepository.create()` resets `_shouldExit` to false when a
  new session is created (so session switcher Create button cancels exit).
- **FIX**: `start()` resets `shouldExit=false` on fresh process launch;
  `ReadyScreen` uses `yield()` guard before onExit to let `reconcile()`
  create default on process-reuse relaunch.
- **ADDED**: `SessionRepository.shouldExit` + `setShouldExit` (domain interface).
  `SessionSwitcherViewModel.shouldExit` (StateFlow). `TerminalScreen.onExit`
  callback → `MainActivity` uses `LocalContext.finish()`.

### Termux Patterns Studied (2026-09-06)

- **TermuxShellManager** (termux-shared/shell/TermuxShellManager.java): simple
  `List<TermuxSession>` + static ID counter. No parallel arrays. Drosh
  mirrors with single `MutableList<DroshSession>`.
- **TermuxService** (app/TermuxService.java): `mShellManager.mTermuxSessions`
  is the single source of truth. `onTermuxSessionExited` removes from list.
  `updateNotification()` calls `requestStopService()` when sessions empty.
- **TermuxActivity** (app/TermuxActivity.java): `onServiceConnected` checks
  `isTermuxSessionsEmpty()` → creates new session if visible, or
  `finishActivityIfNotFinishing()` if not. Drosh mirrors:
  startup creates default session, user deletion triggers exit.

---

## 6. Visual Identity

| Element | Value |
|---------|-------|
| Background | `#000000` (OLED) |
| Surface | `#0A0A0A` |
| Surface 2 | `#121212` |
| Primary accent | `#3B82F6` (terminal blue) |
| Text primary | `#E8E8E8` |
| Text secondary | `#A0A0A0` |

---

## 7. Current Status

### Completed (Visual Identity & Top Bar — 2026-09-07)
- ✅ Color palette: gold `#E8C547` → blue `#3B82F6`, surfaces darkened with 10-nit separation
- ✅ Status bar `#000000` → `#0A0A0A`
- ✅ Install screen modernized (StepStateIcon, StepRow, LiveLogCard, BootstrapStepperScreen)
- ✅ Onboarding wizard kept in original state
- ✅ DocumentsProvider compile errors resolved + declared in AndroidManifest
- ✅ Deleted `SessionSwitcherTopBar.kt`
- ✅ Created `TerminalTopBar.kt` — modern minimalist: left pill button + divider + session name (not clickable), right merged pills (keyboard + more actions)
- ✅ Created `SessionSidebar.kt` — slide-in overlay replacing ModalBottomSheet, session management (list, new, rename, delete)
- ✅ Updated `TerminalScreen.kt` — replaced SessionSwitcherTopBar/SessionSwitcherSheet with TerminalTopBar/SessionSidebar, added BackHandler
- ✅ Added `lucide_panel_left.xml` drawable
- ✅ Build passes on CI (compileDebugKotlin succeeds)
- ✅ All import/path compilation errors resolved (fillMaxWidth, Text, statusBars, rememberRipple, DpOffset, DropdownMenuItem API)
- ✅ Runtime crash fix: 3 vector drawables missing `android:width`/`android:height` → added 24dp (lucide_keyboard, lucide_panel_left, lucide_square_plus)
- ✅ Terminal visibility fix: replaced `Animatable`+`LaunchedEffect`+`coroutineScope` with static 1f values (race condition when `activeId` transitioned `null`→value at startup left `appearAlpha` stuck at 0)
- ✅ Top bar redesign: floating pills (no surface/background surface, only subtle 8% press alpha), session name gets own `DroshSurfaceVariant` surface with 12dp rounded corners
- ✅ Left sidebar button is pill-shaped (CircleShape 36dp), session name NOT clickable — only the pill button opens sidebar
- ✅ Top bar redesign: pills float directly on terminal (transparent container), no border on pills, larger (38dp), merged pill group with connected corners, divider between sidebar button and session name

### Completed (Settings Screen — 2026-09-11)
- ✅ Color palette: added `DroshSurfaceLow` (#191C20), `DroshSurfaceHigh` (#272A2E), `DroshSurfaceContainerLowest` (#0B0E12) to DroshColors.kt
- ✅ Domain enums: `CursorStyle` (Block/Beam/Underline), `AutoLockTimeout` (Immediately/OneMinute/FiveMinutes/FifteenMinutes/ThirtyMinutes/Never) in `domain/settings/TerminalPreferences.kt`
- ✅ SettingsRepository: added `cursorStyle`, `cursorBlinkRateMs`, `autoLockTimeout` flows + setters
- ✅ SettingsViewModel: added `cursorStyle`, `cursorBlinkRateMs`, `autoLockTimeout` StateFlows + `setCursorStyle`, `setCursorBlinkRateMs`, `setAutoLockTimeout` functions
- ✅ SettingsScreen rewritten: iOS-style top bar (icon-only back button, centered title), grouped section containers with 16dp horizontal padding, preview terminal card with blinking cursor, segment controls, iOS-style toggle switch
- ✅ SettingsComponents.kt: all composables for settings rows, toggle, custom thin slider, preview card, segmented controls, editable PRoot command field
- ✅ PinEntryScreen embedded as modal overlay for PIN setup flow
  - ✅ "Made by Muhofy" footer row with `CircleUser` icon
  - ✅ Design reference: `html/irisshell_settings_pure.html` (Tailwind iOS-style design)
  - ✅ CI build passes — no more `rememberRipple`, `MutableInteractionSource`, `normalizeHex`, `statusBars`, or `launch` compilation errors
  - ✅ Removed all custom ripple usage (plain `Modifier.clickable { }` with default Material 3 ripple)
  - ✅ Fixed pre-existing `normalizeHex` undefined reference (simplified color setters)
  - ✅ Cleaned up duplicate imports in DroshIcons.kt (Copy, SquareTerminal, Terminal, Trash2, Undo appeared twice)

### Fixes (Settings Screen — 2026-09-12)
- ✅ Cards now have 16dp horizontal padding (Column padding, not full-bleed containers)
- ✅ SettingsTopBar: back button is icon-only IconButton, title centered with weight(1f), empty 40dp spacer balances layout
- ✅ Text stretching fixed: removed `fill = false` from `weight(1f)` on Column/Text in SettingsSubRow and SettingsNavigationRow
- ✅ Custom ThinSlider: Material 3 Slider with white thumb, DroshPrimary active track, DroshSurfaceHigh inactive track, 20dp height
- ✅ FontSizeStepper → FontSizeSlider: stepper buttons (icon-only, transparent) + thin slider + value badge
- ✅ TerminalPreviewCard: accepts cursorStyle, cursorBlinkRateMs, fontSizeSp, useBlockEngine params — cursor shape changes in real-time
- ✅ BlinkingCursor: matches HTML — Block (8x1.15em), Beam (2x1.15em), Underline (9x2.5px), positioned at prompt end
- ✅ Block mode: thin 1dp Divider lines between commands (not glow/border), matching HTML's block separation
- ✅ Font size applied to all preview text + cursor sizing dynamically
- ✅ PRoot Start Command made editable (OutlinedTextField with DroshPrimary text, DroshPrimary focus border)
- ✅ Font size +/- buttons: transparent background, icon-only (removed DroshSurfaceHigh background)
- ✅ Terminal mode toggle: segment control updates preview appearance (divider lines in block mode, none in classic)
- ✅ CI build passes

### Fixes (Settings Screen — 2026-09-12, afternoon)
- ✅ Two-row layout for sliders: `SettingsSliderRow` (label row + slider row) replaces `SettingsSubRow` for Blink Rate and Font Size
- ✅ Two-row layout for PRoot command: `SettingsCommandFieldRow` (label row + editable field) replaces `SettingsSubRow`
- ✅ Text stretching fixed: removed `fill = false` from `weight(1f)` on Column/Text in SettingsSubRow and SettingsNavigationRow
- ✅ TerminalPreviewCard uses `FontFamily.Monospace` (terminal-like) instead of `OutfitFontFamily`
- ✅ TerminalPreviewCard updated to match HTML: "Shell: zsh 5.9 • Term: xterm-256color" (combined line)
- ✅ BlinkingCursor height fixed: `fontSizeSp * 1.15` (was incorrectly `fontSizeSp * 4.6` which made cursor huge)
- ✅ Font size +/- buttons: transparent background (removed DroshSurfaceHigh), icon-only
- ✅ Block mode: thin 1dp Divider between commands (not glow/border)
- ✅ Terminal mode segment control updates preview (dividers appear/disappear)
- ✅ SegmentControl width capped (`widthIn(max=160dp)`, `widthIn(max=220dp)`) to prevent label text wrapping
- ✅ Auto-Lock Timeout row removed from Settings screen
- ✅ Cursor style setting wired to actual terminal emulator (`TerminalSessionClientImpl.cursorStyle` → `TerminalEmulator.setCursorStyle()` → `TerminalRenderer` renders Block/Underline/Beam)
- ✅ Cursor blink rate wired to `TerminalView.setTerminalCursorBlinkerRate()` from settings
- ✅ Cursor style setting wired to actual terminal emulator (`TerminalSessionClientImpl.cursorStyle` → `TerminalEmulator.setCursorStyle()` → `TerminalRenderer` renders Block/Underline/Beam)
- ✅ Cursor blink rate wired to `TerminalView.setTerminalCursorBlinkerRate()` from settings

### To Build
- Same as docs/TODO.md (full feature backlog)

### Completed (Onboarding Wizard — 2026-09-07)
- ✅ Design system components: `DroshLogo`, `SetupButton`, `DeviceCheckItem`, `ShellSelector`, `PackageProfileSelector`
- ✅ New 4-scene flow: Welcome → DeviceCheck → Preferences → ShellSetup (Zsh conditional)
- ✅ `WelcomeScene` replaced TerminalBackdrop with DroshLogo + SetupButton
- ✅ `OnboardingScreen` hoists preference state; conditional ShellSetup skip for Bash
- ✅ Old `ArchitectureScene`/`ReadyScene` left as dead code (no longer referenced)
- ✅ GitHub Actions build passing (`2209f58` — fix Kotlin compose API mismatches)
- ✅ Runtime crash fix: `weight(0f)` in ShellSelector Canvas `Box` → 0-width bitmap; removed
- ✅ Vector drawable: `lucide_square_terminal.xml` missing width/height → added 24dp
- ✅ Preferences connected to real bootstrap: `OnboardingViewModel.start(preferences)` → `TriggerBootstrap` → `BootstrapStatePort` → `UbuntuBootstrap.install(preferences)`
- ✅ `set-default-shell.sh` only for Zsh (Bash users got `/bin/zsh not found` proot error)
- ✅ `isInstalled` check no longer requires `bin/zsh` (needed for Bash)
- ✅ `bashrc-write.sh` created for Bash path
- ✅ `packages-install.sh` reads `IRIS_CUSTOM_PACKAGES` env var for Custom profile

### Domain Types (2026-09-07)
- `ShellChoice` enum: moved from `ui/components/` → `domain/terminal/`
- `PackageProfile` enum: moved from `ui/components/` → `domain/terminal/`
- `SetupPreferences` data class: `(userName, shellChoice, packageProfile, customPackages)`

### Compose API Issues Resolved
- `if (selected) X : Y` → `if (selected) X else Y` (Kotlin requires `else`, not `:`)
- `KeyboardOptions()`: `keyboardCapitalization` → `capitalization`, `autoCorrect` required
- `painterResource()` must be called at Composable body, not inside `LaunchedEffect`
- `drawArc()`: `startAngleDegrees`/`sweepAngleDegrees` → `startAngle`/`sweepAngle`
- `Path.arcTo()`: `forceNewSubgroup` → `forceMoveTo`
- `Checkbox`/`CheckboxDefaults`: import from `material3`, not `foundation`
- `Surface(...) { }` trailing lambda: close with `}` not `)`
- `ImageVector.Builder.build()` takes **zero arguments** — paths added via `addPath()` before `build()`
- `ImageVector.Builder.addPath(pathData: List<PathNode>, stroke: Brush?, ...)` for adding parsed SVG paths
- `PathParser().parsePathString(svgData).toNodes()` to convert SVG path strings to `PathNode` lists
- `rememberVectorPainter(image = ...)` — parameter is `image`, not `imageVector` in Compose 1.4.0-alpha02

---

## 8. Terminal Output Search (2026-09-09)

- New `DraggableSearchBar` composable in `ui/search/` module — draggable overlay with search input field, match count display (`N/M`), up/down arrow navigation buttons, and X close button
- Accessible from the 3-dot top bar dropdown menu ("Find in output")
- Works in both block mode (searches through Block.prompt + Block.command + Block.outputLines) and classic mode (searches TerminalEmulator.getScreen().getTranscriptText())
- Draggable via `detectDragGestures` — starts at top-center with 64dp top padding, user can drag anywhere
- Back button closes search; matches update live as user types
- Uses `Modifier.border()` (not `BorderStroke`) per architecture rule for `ui/` module
- ✅ CI build passes (`2209f58` + `06a02fb`): resolved all compilation issues
  (dragAmount vs change.delta, buildAnnotatedString withStyle vs setStyle)
- Smooth dragging via `Modifier.graphicsLayer { translationX/Y }` avoids recomposition per drag pixel
- Per-block vs per-global search scope toggle (All/Card)
- Search match highlighting with URL underline + primary background overlay
  (combined style where search matches overlap URLs)
- `currentMatchBlockId` tracked to highlight the block containing the current match
- **Classic terminal (2026-09-09):**
  - URL click fix: `UrlDetector.matches()` (full-string `Regex.matches`) → `UrlDetector.findUrls()` (substring `findAll`) — URLs with trailing punctuation now correctly detected on tap
  - Search highlight overlay: `SearchHighlightOverlay` View (terminal module) draws semi-transparent rectangles on top of `TerminalView` using `Renderer.mFontWidth`/`mFontLineSpacing`/`mFontLineSpacingAndAscent` + `TerminalView.mTopRow` for pixel-perfect cell alignment
  - Overlay wired via `FrameLayout` container in `TerminalViewHost` (classic path only)
  - `SearchHighlightOverlay` also draws URL underlines (blue stroke) — URL click
    detection + visual underlines now work in classic mode, matching block mode
  - Smooth dragging via `graphicsLayer` for search bar
  - Overlay synced with TerminalView redraws via `ViewTreeObserver.OnDrawListener`
   - Focus fix: TerminalView only calls `requestFocus()` once in `OnGlobalLayoutListener`
     (not in AndroidView update block), preventing search bar focus theft
  - URL tap fix: `viewClient.terminalView = this` now set in TerminalViewHost factory
    (was never set — `TerminalViewClientImpl.onSingleTapUp` returned early on null check)

## 9. Release Build (2026-09-09)

- Release build config in `app/build.gradle.kts`: `targetSdk` 28 → 36, enabled `isMinifyEnabled`, `isShrinkResources`, proguard files
- R8 stripping fix: Hilt-injected + Kotlin file classes (`XXXKt`) stripped by each module's own R8 pass before reaching app module — added `-keep { class com.iris.drosh.**; }` + `-dontwarn` to every module's `proguard-rules.pro` (not just consumer-rules.pro)
- Release workflow: `.github/workflows/release.yml` triggers on `v*` tag push, builds `app-arm64-v8a-release.apk`
- ✅ CI release build passes (v0.1.0), APK artifact uploaded (5.7MB)

## 10. App Lock — PIN (2026-09-09)

- 4-digit PIN lock with modern minimalist Compose UI
- Architecture: `domain/settings/PinLockRepository` interface → `data/settings/PinLockRepositoryImpl` (EncryptedSharedPreferences + SHA-256)
- DI: `data/di/SecurityModule.kt` provides `@PinPref`-qualified EncryptedSharedPreferences (MasterKey AES-256)
- Hilt binding: `BindingsModule.bindPinLockRepository`
- UI: `ui/pin/PinEntryScreen.kt` — dot-style filled boxes, hidden numeric input, focus auto-request, onPinReady callback
- PIN setup flow in onboarding: new `OnboardingSceneKind.Security` scene (Welcome → DeviceCheck → Preferences → ShellSetup → Security)
- PIN toggle in SettingsScreen: enable shows inline PinEntryScreen overlay, disable clears PIN
- MainActivity PIN gate: when `pinLock.isEnabled == true`, shows PinEntryScreen at `terminal` route; correct PIN navigates to `terminalHome`
- GitHub Release creation fails with 403 (token lacks `generate_release_notes` permission) — non-blocking, APK available as CI artifact

---

## Block Mode — PromptBlock Rendering (2026-09-12)

- Block mode renders **styled text blocks** (not card-based) — matches `html/block_mode_reference-1.html`
- `PromptBlock.kt` — renders full prompt text from `block.prompt` (blue DroshPrimary) + command (blue), then output (DroshText / DroshTextMuted for errors)
- PromptBlock: two-line layout — directory path (DroshTextSecondary, 12sp) on top, prompt text (DroshPrimary, 13sp) + command below
- PromptBlock: tap block → 3-dot `IconButton` (EllipsisVertical) appears; click it → `DroshDropdownMenu` with: Komutu kopyala, Tekrar çalıştır, Komutu düzenle, Output'u kopyala, Dışa aktar, Block'u sil
- `PromptDivider` — thin 0.5dp horizontal line (DroshBorderSubtle) between blocks, 8dp block spacing (Warp-style breathing room)
- `BlockInputField` — simplified to match HTML `input-row` (plain row, no bordered box, no vertical bar), accepts `promptSuffix` param
- `BlockEngineViewModel.onCommandSubmitted` — checks for `clear`/`ctrl+l` and calls `blockRepository.clear()` immediately before submitting
- `BlockEngineWire` — `lastPrompt` stores full prompt including suffix; `pendingEchoWasClear` handles `clear` echo detection
- `TerminalScreen` — block mode: `Column { LazyColumn<PromptBlock> + PromptDivider, weight=1f }` then fixed `BlockInputField` (separate cell, not scrolling with content); classic mode: `TerminalViewHost + InputBarHost`
- Mode switch: `LaunchedEffect(useBlockEngine)` calls `terminalManager.addTab()` for new session (skips first emission)
- Removed TUI auto-fullscreen — TUI apps render as block output normally, user toggles fullscreen manually
- TUI apps (nano, vim, htop, etc.) in block mode: `altBufferActive` becomes true → TerminalViewHost rendered fullscreen in the block area (terminal's native fullscreen). **No app-level fullscreen** (top bar, input bar remain visible). Manual exit via `CompactFullscreenExit` button.
- Text selection in blocks: `SelectionContainer` wraps prompt, directory, and output Text composables — long-press to select, copy text
- PromptBlock: tap → 3-dot `IconButton` (EllipsisVertical) appears; click it → `DroshDropdownMenu` with: Komutu kopyala, Tekrar çalıştır, Komutu düzenle, Output'u kopyala, Dışa aktar, Block'u sil
- Input field: `Column { LazyColumn(weight=1f), PromptDivider, BlockInputField }` — input is in a separate cell, not scrolling with content
- Block mode LazyColumn has `statusBars` top padding to avoid drawing under status bar icons
- `CommandSeparatorOverlay.kt` removed (PromptBlock rendering replaces overlay approach)
- Runtime fix: `FOREGROUND_SERVICE_DATA_SYNC` permission added to `AndroidManifest.xml` — required since `targetSdk=36` for `dataSync` foreground service type
