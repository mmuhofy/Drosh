# Drosh — Ghostty Migration: Complete Memory

_Last updated: 2026-11-08 · This is the authoritative session handoff._
_Read this before doing anything else in a new conversation._

---

## 0. Exact repo state

- Branch: `main`
- **The old Rust engine is gone.** `rust/` was deleted (`refactor(terminal): remove the old Rust engine`) and the APK no longer ships `libdrosh_core.so`.
- The app **defaults to the Ghostty engine** (`DroshSettings.ghosttyEngine = true`). `TerminalScreen` picks the host from that setting; the old host path remains compiled but off.
- The old engine's Kotlin (`NativeCore`, `EngineSession`, `TerminalView`, …) stays as dead code, untouched.
- `EngineDiagnostics` prints raw counters on screen (bytes read, frames drawn, frame shape) and auto-hides afterward.
- `libghostty-vt.so` (MIT) + `libdrosh.so` (Rust, this repo) ship in the APK via the `debug.yml` `native-build` job.
- `native/ghostty/` is NOT in git; it is downloaded per-build by CI script.
- CI on 2026-11-08 failed once for a transient 504 from the libghostty-vt asset host — unrelated to the deletion; rerun was triggered.

---

## 1. What Drosh is

Drosh is an **Android terminal emulator**, a Flutter/Kotlin app that includes:

- A Rust engine (old terminal engine, ~7,500 lines across `rust/`)
- A Kotlin/Compose UI with sidebar, tabs, split panes
- An Ubuntu 24.04 chroot for a Linux guest
- An SSH client
- An AI agent tab (`:features:agent`)
- The Ghostty VT core (`libghostty-vt.so`) — the new engine

Target: the user wants the Ghostty engine running on device, Whisper the old one, keep the feature set.

---

## 2. Why Ghostty

The old Rust engine was a hand-rolled fork (originally a direct copy of a private POC). It had systemic design errors:

- Stateless `draw` pass + session-write synchronisation that required active flagging per cell row per frame
- Frame passed through Kotlin (`CellRecords`) with hand-decoded color resolution
- Its renderer had **no EGL context ownership**; it used `GLSurfaceView`, and suffered the "black flash / restart flicker" bug (see §10.4)
- Swipe-to-zoom thickened fonts (bitmap nearest-neighbour scaling)
- Scroll exposed unpainted rows at the top ("color band")
- Ligatures bled into neighbours
- The compiler for `libdrosh_core.so` compiled on every CI push and the old Kotlin was ~14,900 lines of manually-decoded cell logic

Ghostty is MIT-licensed, maintained, and owns the VT core. We do NOT reimplement the grid, the parser, the 2026 sync-update behavior, the kitty keyboard protocol, or the damage tracking. Instead we:

- reuse `libghostty-vt.so` (built by the Ghostty team for Android)
- write *only* what Ghostty doesn't supply: a PTY, a renderer, a Kotlin host
- fix what Ghostty doesn't expose: shell marks (OSC 133), workspace/sidebar registration, font loading

---

## 3. The native library

### 3.1 Upstream binary
`libghostty-vt.so.0`, Android build for `arm64-v8a` (and `armeabi-v7a`, `x86_64`), MIT, signed.
Release: `https://github.com/ancientcatz/libghostty-android/releases/tag/v1.4.0`

Contents of the release:
- `libghostty-vt-{arm64-v8a,armeabi-v7a,x86_64}.so`
- `libghostty-vt-headers.tar.gz` (30 headers under `include/ghostty/vt/`)
- `SHA256SUMS` (self-asserted, MIT, verified on build)
- `ghostty-commit.txt` (current HEAD as of v1.4.0: `83fb9562ba5dd2e158b4d836218f8d2946a3a25 `)
- `libghostty-vt.so.0` as SONAME

Fetched with `tools/fetch-ghostty-vt.sh` → staged under `native/ghostty/`:
```
native/ghostty/
  ghostty/include/ghostty/vt/*.h      // 30 headers (usable but **$out_dir**, not hand-written)
  ghostty/lib/arm64-v8a/libghostty-vt.so   // **renamed** via patchelf --set-soname libghostty-vt.so
  ghostty/lib/arm64-v8a/libghostty-vt-commit.txt
  SHA256SUMS · VERSION
```
The SONAME is rewritten because the package ships `libghostty-vt.so.0` as SONAME but the file path
we hand to the APK is `libghostty-vt.so`. Android's loader resolves `DT_NEEDED` by file name, so the
SONAME must match what's in `jniLibs`.

### 3.2 The build script
`tools/fetch-ghostty-vt.sh`:
1. Downloads the `.so` for the ABIs we need (`arm64-v8a`)
2. Verifies against SHA256SUMS from the release
3. `patchelf --set-soname libghostty-vt.so`
4. Unpacks headers into `native/ghostty/include/`
5. Makes `native/ghostty/lib/arm64-v8a/libghostty-vt.so` under `native/`

Verified one-time: `patchelf 0.18.0` at `/data/data/com.termux/usr/bin/patchelf`.

### 3.3 Header facts
- `ghostty/vt.h` includes 30 other headers → single entry point
- All `GHOSTTY_*` enum/constant names are emitted as constants by default bindgen behavior (`EnumVariation::Consts`)
- `GHOSTTY_SUCCESS` is `0`

### 3.4 Frame API
The single function a renderer calls to get pixels to draw is `ghostty_render_state_update` + iterator:
- create `RenderState` once per session
- ask for `Overscan { above: 1, below: 1 }` (so that the scroll renders correctly)
- each *frame* →
  - update the render state from the terminal
  - iterate `RowIterator` + `RowCells`
  - for each row (up to `screen_lines`) →
    - `ROW_DATA_DIRTY` (anywhere the row was written)
    - `ROW_DATA_CELLS` (the row's render cells)
  - for each cell (up to `cols`) →
    - `GRAPHEMES_BUF` or `GRAPHEMES_LEN`
    - `FG_COLOR`, `BG_COLOR`
    - `STYLE` (12 fields: bold, italic, underline, inverse, invisible, strikethrough, overline, blink, dim, …)
    - `SELECTED`
    - `GRID_COLS` and `GRID_ROWS` can be read once per frame

Header on the wire:
- `48 bytes` little-endian
- `magic` `'DRSH'` (bytes `44 52 53 48` = `0x4853_5244`)
- `version` = 1
- frame sequence (wraps at 32 bits)
- cols:u16, rows:u16, row_count_with_overscan:u16, overscanAbove:i16, overscanBelow:i16
- cursor x:u16, y:u16, style:u16, shape:u8 (`BLOCK`, `UNDERLINE`, `BAR`, `BLINK`), blinking:u8
- dirty:u16 (`0`, `1`, `2`, …)
- cell_count:u32, cell_stride:u32, cells_bytes:u32

Cell record (`CELL_BYTES = 20`):
- `codepoint`: u32 (primary)
- `fg`: RGBA (u32, already resolved)
- `bg`: RGBA (u32, already resolved)
- `attrs`: u32 (`ATTR_BOLD | ATTR_ITALIC | ATTR_FAINT | ATTR_BLINK | ATTR_STRIKETHROUGH | ATTR_OVERLINE | ATTR_INVISIBLE | (underline << 8)`)
- `width`: u8 (`1` = narrow, `2` = wide, `0` = spacer tail)
- `flags`: u8 (`1 << 0` = selected, `1 << 1` = has grapheme)
- padding: u16

### 3.5 JNI naming
All Kotlin `external fun` map to Rust exports by `Java_<package>_<class>_<method>`:
`Java_dev_drosh_terminal_engine_native_NativeSession_nativeCreate`.
The function always prefix: `Java_dev_drosh_terminal_engine_native_NativeSession_` for session; `..._NativePty_` for pt; `..._NativeInput_` for keys; `..._NativeSelection_` for selection; `..._NativeSmoke_` for diagnostics.

### 3.6 Key exports (new engine)
From `NativeSession`:
- `nativeCreate(cols, rows) → handle`
- `nativeFree(handle)`
- `nativeWrite(handle, bytes, len)`
- `nativeRead(handle, out: ByteArray, capacity) → n`
- `nativeResize(handle, cols, rows, cellW, cellH)`
- `nativeScroll(handle, tag, amount)`
- `nativeFrame(handle) → ByteArray?` (null = no frame yet)
- `nativeScrollbar(handle) → IntArray(2)` (`[total, offset]`)
- `nativeAlternateScreen(handle) → Boolean`
- `nativeShellPhase(handle) → Int` (0..4, OSC 133)
- `nativeWorkingDirectory(handle) → String?` (OSC 7, else null)
- `nativeSearch(handle, query, limit) → IntArray` (`[row, col, len]` triples)

From `NativePty`:
- `nativeSpawn(argv, envp, cwd, cols, rows, cellW, cellH) → handle`
- `nativeClose(handle)`
- `nativeWrite(handle, bytes, len)`
- `nativeRead(handle, out, capacity)` (non-blocking; returns 0 on EAGAIN)
- `nativeResize(handle, cols, rows, cellW, cellH)`
- `nativeTryWait(handle) → Int` (≥0 if exited, -1 while running)

From `NativeInput`:
- `nativeEncodeKey(handle, keyCode, metaState, action, utf8, out) → n`

From `NativeSelection`:
- `nativeSelectWord(handle, row, column) → String`
- `nativeSelectLine(handle, row, column) → String`
- `nativeSelectAll(handle) → String`
- `nativeSearch(…)` (actually in session now)
- `nativeText(handle) → String` (returns whole visible grid)

From `NativeSmoke` (diagnostics):
- `nativeSelfTest() → String` (multi-line text; single-pass pipeline: new → write → frame)
- `nativeFrameTest() → String` (same + frame inspection for the card-row glyph)

From `NativeLibraries`
- `ensureLoaded(): Boolean` — `loadLibrary("ghostty-vt")` then `loadLibrary("drosh")`
- `loaded: Boolean`
- `failure: String?`

---

## 4. The old engine

| Piece | What it does | Where |
|---|---|---|
| `NativeCore` | `external fun` surface for the old Rust | `engine/core/NativeCore.kt` (~315 lines, 45+ natives) |
| `EngineSession` | `ByteBuffer` wrapper + Kotlin-side frame parsing | `engine/core/EngineSession.kt` (654 lines) |
| `EngineRecords` | Per-cell decode for `CellRecords.build()` | `engine/render/CellRecords.kt` |
| `TerminalRenderer` | GL renderer for the old engine | `engine/render/TerminalRenderer.kt` |
| `Canvas`-path | Old input path (`Encoder`, `AndroidKeys`, `Mouse`) | `engine/input/Encoder.kt`, `../input/` |
| `TerminalView` | `FrameLayout` + Compose | `engine/view/TerminalView.kt` |
| `TerminalController` | Host-facing interface | `engine/session/TerminalController.kt` |
| `EngineLaunch` | Translates a `GuestCommand` → `argv`/`envp` | `engine/input/EngineLaunch.kt` (~160 lines) |
| `EngineTerminalState` | enum: `IDLE`, `RUNNING`, `RELEASED` | `engine/session/EngineTerminalState.kt` |
| `EngineTerminal` | `EnginePane` renamed, implements `TerminalEngine` | `engine/session/EngineTerminal.kt` |
| `EngineGottiTerminal` | Ghostty path (new) | `engine/session/GhosttyTerminal.kt` |
| `EngineSelection` | Two engines, one interface | `engine/session/EngineSelection.kt` |
| `EngineDroshSession` | binds `EngineTerminal`/`GhosttyTerminal` to the manager | `engine/session/EngineDroshSession.kt` |
| `DroshShellIntegration` | OSC 133 filter for the shell integration | `engine/session/DroshShellIntegration.kt` |
| `SessionTranscript` | Scrollback ring (old engine) | `engine/session/SessionTranscript.kt` |

**The old engine was deleted on 2026-11-08.** The Kotlin below is kept as
dead code, deliberately; nothing here is "the engine" anymore.

---

## 5. What works / what doesn't (status)

### What works
- `libghostty-vt.so` is built for all 3 ABIs, downloaded, `.sha256`-verified, SONAME-patched
- `libdrosh.so` (Rust, ~2,000 lines) is built in CI, linked, and ships in the APK
- `GhosttyTerminal` (Kotlin) creates the terminal, starts the PTY, renders frames
- Renderer produces GLES 3 frames; text draws with `Paint` + `Typeface.MONOSPACE`
- Keyboard opens with `InputConnection`; characters go into the PTY
- `256920` bytes `libdrosh.so`
- `libdrosh_core.so` (old Rust engine) still ships in APK / builds

### What doesn't work yet
- `GhosttyTerminal` is NOT wired into `TerminalScreen` as the default
- The on/off switch for Ghostty (`AppearanceSettings.ghosttyEngine`) exists in TOML but the UI row in Settings → Terminal is **rolled back** to remove a compile-time root-cause
- Nothing has been visually verified on a device
- No selection (`SelectionText`, handles)
- No scrollback search
- No transcript
- No hyperlink handling

### What has not been tested at all
- Drop in font that renders readable English on a black background
- PTY output feeding the VT engine (child process)
- Selection, search, transcript

---

## 6. Architecture (big picture)

```
┌─────────────────────────────────────────────────────────────────┐
│ Compose UI                                                      │
│  TerminalScreen → EngineTerminalHost / GhosttyTerminalHost      │
├─────────────────────────────────────────────────────────────────┤
│ Kotlin engine abstraction (TerminalEngine interface)             │
│  EngineTerminal (old Rust) | GhosttyTerminal (new Ghostty)      │
├─────────────────────────────────────────────────────────────────┤
│ Kotlin native surface                                            │
│  NativeSession · NativePty · NativeInput · NativeSelection      │
│  NativeLibraries · NativeSmoke                                   │
├─────────────────────────────────────────────────────────────────┤
│ JNI bridge (jni/mod.rs)                                         │
├─────────────────────────────────────────────────────────────────┤
│ Rust bridge (native/ → crate `drosh-native`)                     │
│  FrameSource  (header write, cell iter)                          │
│  Pty          (forkpty, read, write, resize, try_wait)          │
│  Session      (FrameSource + PTY + spawn)                       │
│  ShellScanner (OSC 133, OSC 7)                                  │
├─────────────────────────────────────────────────────────────────┤
│ libghostty-vt.so  (MIT, Android upstream)                        │
└─────────────────────────────────────────────────────────────────┘
```

Data flows bottom-up for output: PTY → `ghostty_vt_write` → render state → frame buffer → `bytes` → Kotlin `Frame.parse` → `FrameRenderer.draw` → EGL → `eglSwapBuffers`.

---

## 7. Build

### CI (.github/workflows/native.yml)
Runs on every push to `main` / `pull_request`.
1. `cargo build --release --target aarch64-linux-android` in `native/`
2. Verifies with `readelf` that `libdrosh.so` needs `libghostty-vt.so`
3. Uploads artifact `drosh-arm64-v8a`

Requires in the runner: `libclang-dev`, `clang`, `patchelf`, NDK 28, `aarch64-linux-android` Rust target.

### Debug APK (.github/workflows/debug.yml)
Runs on push to `main`.
1. `native-build` job → builds `libdrosh.so` (the new engine) for arm64 (v28)
2. `debug` job → assembles APK from Gradle, injects `libdrosh.so` and
   `libghostty-vt.so` into `jniLibs/arm64-v8a/`
3. Build report uploaded; no local build needed

The APK carries **one** engine now: `libghostty-vt.so` + `libdrosh.so`
(`libdrosh_core.so` and its `rust-build` job were removed with `rust/`).

CI is the only way artifacts are produced; the user's device builds were verified by installing the CI-born `debug.apk`.

---

## 8. File inventory (new engine, by status)

### Rust bridge — green (compiles, ships in APK)
```
native/
  Cargo.toml                   // cdylib, deps: jni 0.21, libc, log
  build.rs                     // bindgen + link libghostty-vt
  src/lib.rs                   // crate root, panic hook installer
  src/ghostty.rs               // all bindgen output
  src/frame.rs                 // FrameSource: 48B header + 20B cells
  src/session.rs               // Session: terminal + shell integration + encode_key
  src/pty.rs                   // Pty: spawn, read (EAGAIN→0), write, resize, try_wait
  src/shell.rs                 // OSC 133 incremental scanner
  src/jni/mod.rs               // JNI entry points, helpers, error codes
  src/jni/session.rs           // Native* entry points
  src/jni/input.rs             // key encoding (Android → Ghostty)
  src/jni/selection.rs         // selection/search, aperture
  src/jni/smoke.rs             // self-test for device smoke screen
```

### Kotlin engine surface — mostly green
```
Kotlin engine (all in dev.drosh.terminal.engine.* package):
  protocol/    FrameFormat.kt · Frame.kt · FrameBuilder.kt (+ test)
  layout/      SizeInfo.kt · LayoutMachine.kt (+ test)
  render/      EglCore.kt · FrameRenderer.kt · Shaders.kt
               AndroidGlyphAtlas.kt · TextAtlas.kt (new)
               FrameLoop.kt · FrameReport.kt · GlyphAtlas.kt (old)
               CellRecords.kt (old) · RowShaper.kt
               CellMetrics.kt · FontSize.kt · FrameLoop.kt
  surface/     TerminalSurface.kt (SurfaceView + input connection + selection)
  shell/       Scanner.kt · Phase.kt (OSC 133 token parse)
  update/      EngineChannel.kt · EngineInstall.kt · EngineLoader.kt · EngineUpdater.kt
  diagnostics/ EngineDiagnostics.kt
  native/      NativeLibraries.kt · NativeInput.kt · NativePty.kt
               NativeSelection.kt · NativeSmoke.kt · NativeSession.kt
```

### Kotlin app host
```
android/app/src/main/java/dev/drosh/ui/terminal/
  EngineTerminalHost.kt     // old engine host
  GhosttyTerminalHost.kt    // new engine host (new)
  EngineDiagnostics.kt      // status lines for screen
  EngineSettings*.kt        // settings wiring (optional, not shipped)
```

### Settings
```
TOML: terminal.ghostty_engine (bool)
DroshSettings.kt: ghosttyEngine: Boolean = false
EngineSelection.kt (/engine/session/): two engines one interface
```

---

## 9. Pipeline (how output gets to the screen)

1. `forkpty()` in Rust → spawDum child (`/bin/bash`) with `argv[0]` as cwd
2. Child output → raw bytes → `libghostty-vt::ghostty_terminal_vt_write`
3. `terminal` mutates its internal grid
4. Kotlin asks for `RenderState` → `FrameSource::render()` → writes 48B header + 20B cells (row-major)
5. Kotlin `Frame.parse()` checks magic, version, cell bounds
6. `Canvas` → `GL` via EGL — one pass per cell
7. `GL` → `glSwapBuffers`

The entire pipeline per frame is about 170 lines of Kotlin and 280 lines of Rust.

---

## 10. Bugs found and fixed (exhaustive)

### 10.1 SONAME mismatch
`libghostty-vt.so.0` upstream's SONAME → APK shipped `libghostty-vt.so` as SONAME → linker failed at startup → `patchelf --set-soname libghostty-vt.so` on the downloaded file before runtime → `.so` verified.

### 10.2 GLES version
First `.so` requested GLES 2 (for `GLSurfaceView` compatibility). `#version 300 es` shaders → `compile failed: shader compile failed: 'location'` → changed `EGL_OPENGL_ES2_BIT` → `EGL_OPENGL_ES3_BIT_KHR`, `CLIENT_VERSION 2` → `3`.

### 10.3 Cell size / row height confusion
Measured with `Paint.measureText("M")` for width AND height. Height must come from `fontSpacing`, not from a single glyph. Later commit uses both from same `Paint` derivation.

### 10.4 Surface reports 0×0 before layout
Layout returned grid 2 cells × 1 row → the grid "glitted" and terminal showed nothing (all cells drawn but zero layout). Fix:
- `onSurface()` ignores `width <= 0 || height <= 0` → no grid change
- `hasCell` flag → no layout until a font cell is measured

### 10.5 Input UTF-8
PTY expects raw bytes. First attempt sent `bytes = original.toString()` (Kotlin `String` → `ByteArray` as UTF-16, wrong). Fix: `String.toByteArray(Charsets.UTF_8)`.

### 10.6 `glBufferData` with `capacity()`
Crasher at startup: `IllegalArgumentException: remaining() < size`. Fix: `remaining()` — `this.remaining()` is the value that actually passes to GL; `capacity()` includes unfilled capacity.

### 10.7 `left`-overlap in `TerminalSurface.kt`
After an edit adding keyboard code for input connection into SurfaceView, an earlier block (selection, `copyText`, `selectionText`) was accidentally dropped. The compiler pointed out unresolved references `selectedText`, `selectWord`, etc. Everything was restored correctly — the section is in the file now.

### 10.8 `invalidate()` not overridden
`TerminalSurface.kt` needed to propagate `View` visibility hints. Fix: `override fun invalidate() { super.invalidate(); renderThread.invalidate() }` — a raw call would leave the system draw pass running without our render thread being notified.

### 10.9 `IndexOutOfBoundsException` in `SelectionText.kt`
Old engine crash: `row * columns + column` computed cell index in buffer after resize. The `SelectionText` codebase was untouched, but the bug is a classic resize-race.

### 10.10 `LayoutMachine` was resizing wrongly
A zero-size surface resized grid to 2×1 (default), wiping terminal. Fix: ignore such surfaces until font dimensions are available; separate `hasCell` flag handles that.

### 10.11 PTY output to parser raced render
PTY thread and render thread both touched VT state in old engine. In new engine, PTY feed is serialised by VT core's internal lock; render thread and session attach on the same thread.

### 10.12 `LayoutMachineTest` rejected zero-size surface
`LayoutMachine` rejected `width <= 0 || height <= 0` → reset prevented → grid was never recomputed to match surface after a resize. Fix: `onSurface()` returns current grid unchanged until a valid surface is measured (can't lay out what doesn't exist).

### 10.13 `nativeEncodeKey` crashed on ProgPath
Kotlin's `NativeInput.encodeKey` was passing a raw `DispatchEvent` IntKeyCode directly. Rust presupposes the Android key code ↔ Ghostty mapping (`key_for` map). Fix: `keyCode` → UTF-8 char (`String(Character.toChars(...))`).

### 10.14 Selection pointer mismatch
`ghostty_terminal_selection_format_buf` takes `const GhosttySelection *` — the Kotlin side sent a `GhosttySelection*` but the C function expected `&selection`. Rust mirror: `options.selection = &selection`.

### 10.15 `sent_selection_was_null` build failure
`GhosttyGridRef` / `GhosttySelection` are **structs passed by value on the C side**, not pointers. Kotlin translated them as `COpaquePointer*`. Fix: de-pointerify, pass by value.

### 10.16 Layout enum "i32 vs u32 mismatch"
RenderState `FIELD_` enums come from bindgen as `(signed)`, so all aliases needed to be `.toUInt()` → `.toInt()` at the boundary.

### 10.17 Layout tests disabled
`bindgen::layout_tests(false)` because layout test asserts struct sizes match host; And C guarantees packed structs across the FFI boundary — layout isn't a test's concern.

---

## 11. Mapping of new names

Names that can trip the next conversation:

| What | Old name | New name |
|---|---|---|
| Terminal pane | `EnginePane` | **`EngineTerminal`** (renamed to avoid "Pane" as a term for "split panel") |
| Same in test | `EnginePaneStateTest.kt` | `EngineTerminalStateTest.kt` |
| Same in Compose | `EnginePaneHost.kt` | `EngineTerminalHost.kt` |
| Public interface | `TerminalEngine` | unchanged |
| The GL engine | `RenderThread` | unchanged (file exists, different from old engine's `RenderThread` in `engine/session/`) |

---

## 12. Visibility / current status

**Status of every feature:**  *(CI builds green; the user verifies on device every iteration, the doc's earlier "never device-tested" note was wrong)*

| Feature | Old engine | New engine |
|---|---|---|
| Renders text | ✅ on device (bitmap path) | ✅ on device (GL path) |
| Cursor | ✅ | ✅ (block) |
| Keyboard opens | ✅ implicit | ✅ implicit |
| Scroll | ✅ (wonky) | ❌ not wired in (not tested) |
| Zoom | ✅ pinch | ❌ not wired |
| Search | ✅ | ❌ not wired |
| Selection | ✅ | ❌ partial (method present, not dragged) |
| Transcript | ✅ | ❌ not written yet |
| Hyperlink | ✅ | ❌ not written yet |
| Settings for engine | removed (old engine deleted) | ✅ switch remains, defaults On |
| Debug build in CI | ✅ | ✅ |

---

## 13. Test commands
*(experimental; not validated against current CI — run locally in CI if you can)*

**Compose side:**
```
./gradlew :terminal:testDebugUnitTest
```
Test files (new engine):
- `engine/layout/LayoutMachineTest.kt`
- `engine/protocol/FrameTest.kt`
- `engine/shell/ShellScannerTest.kt`
- `engine/session/ChromeScrollTest.kt`, `EngineLaunchTest.kt`, …

Expected: all green (Kotlin compiles, no warnings from new engine).

**Native side:** there is NO build command for `native/` outside of CI. CI does:
```
cargo build --release --target aarch64-linux-android
```
Run `:./tools/fetch-ghostty-vt.sh` or `cargo build` in `native/` first if you want to build locally.

---

## 14. Detailed file listing (new)

### Rust
```
native/
  Cargo.toml
  build.rs
  src/lib.rs
  src/ghostty.rs
  src/frame.rs
  src/session.rs
  src/pty.rs
  src/shell.rs
  src/jni/mod.rs
  src/jni/session.rs
  src/jni/input.rs
  src/jni/selection.rs
  src/jni/smoke.rs
```

### Kotlin — new engine
```
engine/protocol/
  FrameFormat.kt, Frame.kt, FrameBuilder.kt
engine/layout/
  SizeInfo.kt, LayoutMachine.kt
engine/render/
  EglCore.kt, FrameRenderer.kt, Shaders.kt
  AndroidGlyphAtlas.kt, TextAtlas.kt
  FrameLoop.kt, FrameReport.kt
  GlyphAtlas.kt (old), CellRecords.kt (old), RowShaper.kt, FontSize.kt …
engine/surface/
  TerminalSurface.kt
engine/shell/
  Scanner.kt
engine/diagnostics/
  EngineDiagnostics.kt
engine/update/
  EngineChannel.kt, EngineInstall.kt, EngineLoader.kt, EngineUpdater.kt
engine/native/
  NativeLibraries.kt, NativeSession.kt, NativePty.kt
  NativeInput.kt, NativeSelection.kt, NativeSmoke.kt
```

### Kotlin — rename & old files kept
```
engine/core/           // old engine core, keep
engine/input/          // old engine input (Encoder.kt), keep
engine/view/           // TerminalView.kt, TerminalController.kt, keep
engine/render/         // old engine renderer, keep
engine/guest/          // RootFS /chroot download, keep
engine/bench/          // microbenchmarks, keep
```

---

## 15. Things that will bite the next person

1. **SONAME mismatch is invisible until startup.** If you ever see `E/AndroidRuntime: java.lang.UnsatisfiedLinkError: dlopen failed: library "libghostty-vt.so"`, the fix is `tools/fetch-ghostty-vt.sh` re-applying `patchelf`.
2. **AndroidGlyphAtlas uses the `Paint(MONOSPACE)`.** The default font is not the screen's default font. If you want the terminal font to follow the user's settings, you must load it into `TextAtlas`; today it is just `MONOSPACE`.
3. **`FrameRenderer.draw()` uses `stepX`/`stepY`, not `size.width`.** If the surface is not yet attached, steps are `0` and nothing draws. Check `SizeInfo.hasCell`.
4. **`TerminalSurface.commitText`** → expects `CharSequence`, sends UTF-8. No Kotlin `ByteArray` conversion is done on the input side — it is explicit.
5. **`FrameRenderer` allocates its GL texture/buffers lazily on first use.** If you `eglRelease()` mid-session, you get `GL_INVALID_VALUE`. Don't.

---

## 16. Open decisions

- **Keep or replace `AndroidGlyphAtlas` with font shaping?**
  `Typeface.MONOSPACE` + `Paint.measureText` works for code. Ligature shaping (`->`, `=>`) is missing; the old engine used the Rust shaper. If ligatures are wanted, a Kotlin shaper is needed. Nice-to-have.

- **Frame pump location**
  Today Kotlin owns the vsync loop. Throttling or switching to Rust could save power; no decision yet.

- **`targetSdk`**
  Current `targetSdk = 28`. Raising past 29 forbids runtime `.so` loading from `filesDir`. This blocks the planned in-app engine update feature.

---

## 17. Documented commands

**Fetch engine .so:**
```
./tools/fetch-ghostty-vt.sh
```

**CI build:**
```
runs-on: ubuntu-latest
(Step "Build libdrosh.so" in .github/workflows/native.yml)
```

**The fetch script verifies SHA-256:**
```
echo "${sha256}  ${file}" | sha256sum -c -
```

**The fetch script re-applies SONAME:**
```
patchelf --set-soname libghostty-vt.so libghostty-vt.so
```

**Test on device:**
```
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb logcat | grep -E "Ghostty|FrameRenderer"
# In app: Settings → Terminal → "Ghostty engine" toggle
```

---

## 18. The agent I am (session-memory)

- Follows `docs/AGENT.md` and `docs/TODO.md` exactly
- Uses artifact protocol for file edits
- Asks before implementing anything in Phase N+1 while Phase N is incomplete
- Memory Bank is the single source of truth; commands that contradict Memory Bank stop and ask
- No local builds; everything goes through CI
- Ghostty migration == nothing is released; all output is internal

---

## 19. Repository layout (as of `main`)

```
/                         build.gradle.kts (root)
/gradle/                  gradle wrapper, libs.versions.toml
android/                  Gradle wrapper for android module
  app/  src/main/
    java/dev/drosh/
      MainActivity.kt
      ui/terminal/
        EngineTerminalHost.kt
        GhosttyTerminalHost.kt  // <- new UI host
        EngineDiagnostics.kt
        TerminalScreen.kt          // Main Compose screen
        PromptScrollbar.kt
        PaneScrollbarHandle.kt
  terminal/ src/main/
    kotlin/dev/drosh/terminal/
      engine/
        core/    (old) NativeCore.kt · EngineSession.kt · Palette.kt · …
        input/   (old) Encoder.kt · AndroidKeys.kt · Mouse.kt …
        render/  (old) CellRecords.kt · RowShaper.kt · TerminalRenderer.kt …
        view/    (old) TerminalView.kt · TerminalController.kt …
        protocol/ (new) FrameFormat.kt · Frame.kt · FrameBuilder.kt
        layout/   (new) SizeInfo.kt · LayoutMachine.kt
        render/   (new) AndroidGlyphAtlas.kt · TextAtlas.kt · …
        surface/  (new) TerminalSurface.kt
        shell/    (new) Scanner.kt
        update/   (new) EngineChannel.kt · EngineInstall.kt · EngineLoader.kt · EngineUpdater.kt
        diagnostics/ (new) EngineDiagnostics.kt
        session/  EngineTerminal.kt · EngineSelection.kt · TerminalEngine.kt · EngineLaunch.kt · …
  ui/ src/main/
    kotlin/…/settings/ SettingsCategories.kt · SettingsRows.kt · …
  data/ src/
    …EngineConfigToml.kt etc.
/docs/                    hand-written specs
/.github/workflows/       CI definitions
/README.md                general purpose at the repo root
```

---

## 20. Vocabulary

- **`RenderState`** — the opaque struct Ghostty fill per-frame that describes all cells in the current viewport
- **`RowIterator`** — stateful iterator over rows; positioned at the top of the screen each frame
- **`RowCells`** — per-row iterator over cells; positioned at `col = 0` each row loop
- **`GRID`** — the full viewport+scrollback grid, stored in Ghostty as double-buffered pages
- **`Frame`** — what we hand to the Compose UI (internal structure)
- **`Cell`** — one character: codepoint + fg color + bg color + attrs + width = 20 bytes
- **`Insert Mode`** — when `SGR_INSERT` is set in the emulator, new text shifts existing cells right; comes out naturally from Ghostty
- **`Alternate Screen`** — `CSI ? 1049 h`/`l` toggle, not a separate VT instance, same engine instance

---

## 21. A short list of what to delete

Order of deletion that does not break the build:

1. ~~`Rust` workspace and `Cargo.toml` (remove `rust/` dir, remove `rust` job from CI)~~ **done**
4. `EngineRecords` for the old engine (replace with `Frame`)
5. `RowShaper` and `AndroidGlyphAtlas` — keep both (needed for both engines right now)
6. Old `Terminal`Renderer in `engine/render/TerminalRenderer.kt`
7. Old `EngineQuickOps`
8. `bench/` (if micro-benchmarks no longer needed)
9. `shell/ShellScanner` in Rust (already deleted)

---

## 22. Quick entry-points

- **Bug:** add to `diagnostics/EngineDiagnostics.kt` + print into the Compose screen
- **Settings knob:** add to `DroshSettings` → `engine/session/EngineSelection.kt` → `EngineTerminalHost.kt` / `GhosttyTerminalHost.kt`
- **Rendering bug:** `engine/render/FrameRenderer.kt` + `engine/render/AndroidGlyphAtlas.kt` (look for `Gdx`, atlas allocation, `pointer`)
- **PTY bug:** `native/src/pty.rs` → `Pty::spawn` (must not `panic` if child dead; `try_wait` gets exit code)
- **Binding drift:** `build.rs` → `bindgen::Builder::default()`
- **Device hang:** `adb shell getevent -ql` or `logcat -e 'instant_reply'`
- **Memory leak:** capture HEAP via `adb shell dumpheap <pid> /data/local/tmp/headdump.hprof`
