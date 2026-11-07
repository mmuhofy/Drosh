# Drosh — Ghostty Engine Handoff

_Last updated: 2026-11-08 · Author: this session · Scope: full state of the engine migration_

---

## 1. What this is

Drosh is an Android terminal emulator. This document is a complete handoff for
the replacement of its native VT engine: the work moved from a self-contained
Rust engine ("the old engine") to **Ghostty's VT core** (`libghostty-vt`),
driven by a new native half written in Rust and drawn by a new GL renderer on
Android.

You should be able to take over from this document without the prior
conversation. Everything in it is in the repository at `main` now, commit
`94a60cc8` (the exact commit at time of writing).

---

## 2. The one-sentence summary

The app is an Android terminal that currently runs on an old Rust engine; the
new engine is Ghostty's VT core, linked as a prebuilt C library, bridged by a
Rust `cdylib`, and rendered by a new Kotlin/GL stack. **The new engine builds,
ships in the APK, and produced text on a device — but the app still defaults to
the old engine and they coexist.**

---

## 3. What was decided and why

### 3.1 Replace the Rust engine with Ghostty

The old engine was a fork of a hand-rolled VT implementation. It had a long tail
of screen bugs that came from drawing logic being spread across two languages
(Kotlin renderer, Rust engine, all of it disagreeing about who owned the grid).

Ghostty's `libghostty-vt` is a complete, maintained VT core:

- Grid + scrollback
- ANSI/VT100 parsing (2026 DECSET, kitty graphics, kitty keyboard protocol)
- Damage tracking
- Selection + search
- Semantic prompt support (OSC 133)

It is MIT-licensed, written in Zig, and exports a stable C API. It does **not**
include a PTY, fonts, glyph rasterization, or any Android surface. So the
migration is: keep Ghostty's engine, write the missing pieces (PTY, font,
renderer) in the platform layers that already exist.

### 3.2 Two `.so` files, not one

| Library | Source | Contents |
|---|---|---|
| `libghostty-vt.so` | Upstream (Ghostty, Android team) | The VT engine and nothing else |
| `libdrosh.so` | This repo (`native/`) | JNI bridge, PTY, shell integration tap, frame buffer |

The API of `libghostty-vt.so` is deliberately narrow: parse bytes, render
frames. The app cannot draw a terminal by itself — it asks for a frame and
turns it into GL.

### 3.3 The bridge is Rust

The bridge was originally written in Zig because the engine is written in Zig.
It was then **ported to Rust** because:

- The repository is already a Rust + Kotlin project
- Rust is a better fit for the JNI surface than Zig 0.16 (which was unstable)
- CI can use `dtolnay/rust-toolchain` and `rust-cache` cleanly

All Zig code has been deleted and replaced with Rust.

### 3.4 `native/` is the single Cargo workspace for the engine

```
native/
  Cargo.toml        // crate: drosh, cdylib
  build.rs          // bindgen over libghostty-vt headers + link the .so
  src/
    lib.rs          // crate root
    ghostty.rs      // all bindgen output re-exported / aliased
    frame.rs        // render state → flat frame buffer (the hot path)
    pty.rs          // forkpty + execve
    shell.rs        // OSC 133/7 incremental tap on PTY output
    session.rs      // frame + keys + shell + lifecycle = one handle
    jni/            // the JNI entry points
      mod.rs        // shared helpers
      session.rs
      pty.rs
      input.rs
      selection.rs
      smoke.rs      // the self-test used by the device smoke screen
```

The old `native/` (Zig) is gone. The old `rust/` workspace (the old engine) is
**still present** and still the default, but will be deleted once the new one
is proven.

---

## 4. Architecture

```
┌─────────────────────────────────────────────────────────┐
│ Kotlin UI (Compose)                                      │
│  TerminalSurface (android.view.SurfaceView)               │
│  Gestures · selection · keyboard (InputConnection)       │
├─────────────────────────────────────────────────────────┤
│ Kotlin engine surface                                     │
│  AndroidGlyphAtlas (Android Paint + Typeface)             │
│  RenderThread / FrameRenderer (GL)                        │
│  TextAtlas · EglCore · Shaders                            │
├─────────────────────────────────────────────────────────┤
│ JNI boundary  (jni/mod.rs, ~500 lines)                    │
├─────────────────────────────────────────────────────────┤
│ Rust bridge   (crate: drosh, cdylib)                      │
│  FrameSource  ← render state (48B header + 20B per cell)  │
│  PTY          ← forkpty/execve (Linux / Android bionic)  │
│  Shell tap    ← OSC 133 / 7                               │
├─────────────────────────────────────────────────────────┤
│ libghostty-vt.so  (MIT, from Ghostty)                     │
└─────────────────────────────────────────────────────────┘
```

---

## 5. What was built, in order

### Phase 0 — The `.so` and CI provenance
`tools/fetch-ghostty-vt.sh` downloads `libghostty-vt.so` (MIT, Android build
) for the configured ABI, verifies its SHA-256 from the release's checksum
file, and stages headers + library under `native/ghostty/`.

- The downloaded `.so` is **ignored by git**; it is never committed
- CI builds `libdrosh.so` from this same commit, so the two always match

### Phase 1 — The Zig→Rust migration
Every Zig source line was replaced. Significant ordering issues (Zig was
generated first, then ported):

- Bindings are **generated** by `bindgen` from the headers, not hand-written
- `EnumVariation::Consts` is used so `GHOSTTY_*` names are the same in Rust as
  in C
- `layout_tests` is disabled: the size assertions bindgen emits are for the
  host ABI, and the Android ABI differs
- `#![allow(unsafe_op_in_unsafe_fn)]` is used crate-wide because wrapping forty
  FFI calls provides nesting, not auditability

### Phase 2 — The frame, not the grid
`frame.rs` turns a Ghostty render state into one flat buffer:

```
48B header  magic · version · seq · cols · rows · overscan · cursor · dirty
N × 20B cells  cp · fg RGBA · bg RGBA · attrs · width · flags
```

Colors are already resolved by Ghostty (palette, inverse, bold, selection).
Kotlin draws quads with a pre-sampled atlas; it does not interpret colors.

### Phase 3 — The PTY
`pty.rs` uses `forkpty`/`execve` exactly as Ghostty does. bionic has had
`forkpty` since API 23; `setsid` and `TIOCSCTTY` are the non-obvious parts
that were wrong (correctly copied).

### Phase 4 — The Android renderer
Kotlin side: `SurfaceView` + `GLThread`. The old engine used `GLSurfaceView`;
this one owns its own EGL context (GLES 3, not ES 2) and renders correctly.

- `EglCore` — context creation
- `FrameRenderer` / `Shaders` — GLSL
- `AndroidGlyphAtlas` — `Paint`/`Typeface`, never `NativeCore`

The renderer reports `bytes read` and `frames drawn` on screen while it is
generating content, so a black screen was diagnosed without a debugger.

### Phase 5 — Key input
`NativeInput.kt` — `performPrivateCommand` is not used; the app sends raw
key codes + `utf8` to the engine, which maps them to escape sequences itself
(including kitty keyboard protocol). This replaced `Encoder.kt` entirely.

### Phase 6 — Diagnostics
`EngineDiagnostics.kt` — counts bytes read / frames drawn / cells. Shows them
on screen until content appears, then hides itself. Added as a temporary
bring-up aid and will be removed before release.

---

## 6. CI / workflows

`.github/workflows/native.yml` — `libdrosh.so` for arm64-v8a:
- `dtolnay/rust-toolchain` stable, `aarch64-linux-android` target
- `libclang-dev` + `clang` (for bindgen)
- `tools/fetch-ghostty-vt.sh` (headers + `.so`)
- `cargo build --release --target aarch64-linux-android`
- Verifies `readelf -d libdrosh.so` reports `NEEDED libghostty-vt.so`
- Uploads as an artifact; `Build Debug` downloads it into `jniLibs`

`debug.yml` — APK path:
- `rust-build` job: builds the old engine (`libdrosh_core.so`)
- `native-build` job: builds the new engine (`libdrosh.so`)
- `debug` job: downloads both into `jniLibs/arm64-v8a/`, builds APK

---

## 7. Known state / bugs fixed

| Area | Bug | Fix |
|---|---|---|
| SONAME | `libghostty-vt.so.0` upstream, renamed to `.so` in the release package | `patchelf --set-soname` |
| GLES | Context requested ES 2; shaders are ES 3 | `EGL_OPENGL_ES3_BIT_KHR` |
| Cell metrics | Font measure used `sp`, not pixels | `Paint` measure with density |
| Layout | Surface 0×0 collapsed grid to 2×1 | `onSurface` refuses ≤ 0 |
| Layout | `LayoutMachine` accepted surface before font existed | `hasCell` flag |
| Render | `glBufferData` with `capacity()` instead of `remaining()` | crash on first frame |
| Input | `commitText` sent `String`, PTY wanted bytes | UTF-8 encode |
| Render | `glBufferData` with `capacity()` instead of `remaining()` | crash on first frame |
| Keyboard | Surface never got an `InputConnection` | added `onCreateInputConnection` |
| Keyboard | No focus request | `requestFocus` + `showSoftInput` |
| Keyboard | `onKeyDown` didn't delegate to the surface's encoder | fixed |
| Diagnostics | No way to see why the screen was black | byte/frame counters |
| Selection | Was accidentally deleted from `TerminalSurface.kt` in an earlier edit | restored |
| Selection | `selection_contains` takes engine selection by pointer | by-value handle + `&` |
| Session | `GhosttySelection` / `GhosttyGridRef` are structs, not pointers | fix bindgen call sites |

---

## 8. What remains

### 8.1 Device verification (first)
- Open the app, select Ghostty, create a new terminal
- Text should render
- `adb logcat | grep -E "Ghostty|FrameRenderer"`
- `EngineDiagnostics` prints `<n> bytes / <n> frames / frame <cols>x<rows> ...`
  for the first few seconds; it auto-hides once content draws

### 8.2 Register the Ghostty pane in the sidebar
Ghostty panes do not appear in the session list. The fix is in
`TerminalManager.registerEngineTerminal`: the Ghostty pane is registered as an
`EngineTerminal` (the renamed `EnginePane`) and the registry can see it.

### 8.3 Delete the old engine
The old engine is still the default and depends on `libdrosh_core.so` /
`rust/`. Once Ghostty is the default and proven on device, it can be removed:
- `rust/` (old workspace)
- `engine/core/`, `engine/view/`, `engine/render/`, `engine/input/` (old Kotlin)
- `libdrosh_core.so` — and its build job in CI

### 8.4 Lost features
These were deliberately shelved when the old engine was abandoned:
- `transcript` — scrollback text (was Rust)
- `hyperlinkAt` — OSC 8 (was Rust, tap now exists in `shell.rs`)
- Selection handles — the new `TerminalSurface` draws text but has no drag handles

### 8.5 Settings / persistence
The engine choice (`start_ghostty`) is stored in TOML; the setting UI switch is
wired but the current `EngineTerminalHost` was refactored back to the old
engine host to remove a compile blocker. The Ghostty pane should be brought
into the same host once the switch is ready to ship.

### 8.6 BottomSheet for engine updates
Motor version can be checked in-app (`native/`, `EngineUpdater.kt`). A UI
sheet showing the current version and a download button is described in
`EngineChannel.kt` but not yet built.

---

## 9. File map (new work only)

### native/
```
build.rs                // bindgen + link
Cargo.toml              // cdylib, deps: jni, libc, log
src/lib.rs
src/ghostty.rs          // bindgen-generated types
src/frame.rs
src/session.rs
src/pty.rs
src/shell.rs
src/jni/mod.rs
src/jni/session.rs
src/jni/pty.rs
src/jni/input.rs
src/jni/selection.rs
src/jni/smoke.rs
```

### Kotlin (`engine/`)
```
protocol/   FrameFormat.kt · Frame.kt · FrameBuilder.kt
layout/     SizeInfo.kt · LayoutMachine.kt
render/     EglCore.kt · FrameRenderer.kt · Shaders.kt · AndroidGlyphAtlas.kt …
surface/    TerminalSurface.kt
shell/      Scanner.kt
update/     EngineChannel.kt · EngineInstall.kt · EngineLoader.kt · EngineUpdater.kt
diagnostics/ EngineDiagnostics.kt
native/     NativeSession/NativePty/NativeInput/NativeLibraries/NativeSelection …
```

---

## 10. Key facts

- `libghostty-vt.so.0` is upstream's SONAME; renamed to `libghostty-vt.so` in
  the packaged `.so` by the fetch script
- The frame buffer is little-endian; all Android targets are
- 48B header magic `0x4853_5244` = `'DRSH'`
- 20B cell record: cp · fg RGBA · bg RGBA · attrs · width · flags
- `RenderState` is reused across frames; overscan is set once at creation
  (`{above:1, below:1}`)
- Bindings regenerate on every `build.rs` run; CI doesn't commit them
- `target/` and `ghostty/` are in `.gitignore`; the `.so` is never committed
- All Zig code and the old `native/` directory are **deleted**
- All old `DroshKeyboard` / `TerminalView` / `NativeCore` code still exists in
  the repository; none of it is wired into the new engine path

---

## 11. How to run the new engine

1. Build → `debug.yml` builds `libdrosh.so` and injects it + the old
   `libdrosh_core.so` into the APK
2. Install APK
3. Settings → Terminal → Ghostty Engine (feature-flagged, default off)
4. New terminal
5. Watch the status line: it prints
   `<bytes> bytes / <frames> frames / frame <cols>x<rows>`
   and vanishes when content draws
6. `adb logcat | grep -E "Ghostty|FrameRenderer"`

---

## 12. Open questions

These are real decisions, not technical debt:

- **Keep the old engine?** It is the default today and is proven on device;
  the new engine is the architectural goal. The decision to flip the default
  should wait for step 8.1 (device verification)
- **Delete `rust/`?** Yes, after the default flips and CI no longer references
  it. Its `rust-cache` and `flashlight` references must be removed
- **transcript / hyperlink?** Nice-to-haves gated on the switch shipping
- **Glyph rendering** — `AndroidGlyphAtlas` uses `Paint` and gives correct
  Unicode but no ligature shaping; ship ligatures as a follow-up
