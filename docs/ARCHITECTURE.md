# Drosh Architecture

The layer rules every module obeys, the naming contract every file follows, and
where the Rust/Kotlin boundary sits. This document is normative: the
`checkModuleBoundaries` task fails the build when §3 is broken, so a rule here
that nobody enforces is a bug in this file.

The terminal is **Ghostty's VT core** (v1.4.0, MIT) driven from `native/`.
It replaced a vendored Termux engine, and the rules below were written for
that one — what is here is the same shape, one engine further along.

---

## 1. Module graph

Every module directory below lives under `android/`, the Gradle build root;
`gradlew`, `settings.gradle.kts` and `build-logic/` live there too. The
native half is the separate `native/` Cargo crate (§2). Module *names* are
unchanged by that split: `android/data/` is still `project(":data")`.

```
:app  ──► everything (composition root only: Activity, NavHost, Hilt wiring)

 ├── :ui --------------► :domain, :theme
 ├── :terminal --------► :linux, :domain
 ├── :features:agent --► :terminal, :domain
 ├── :features:ssh ----► :domain, :terminal  (implements TransportPort)
 ├── :linux -----------► :domain
 ├── :data ------------► :domain
 ├── :features:editor -► :domain, :theme
 └── :domain ----------► (nothing — pure Kotlin)
```

| Module | Owns | Must not know about |
|---|---|---|
| `:domain` | Entities, ports, use cases | Android, any Drosh module |
| `:terminal` | the engine's Kotlin side (JNI, the sessions, the surface's layout and input) and the sessions that drive it (sessions, panes, tabs, blocks, shell launch) | Compose, `:ui`, `:app`; the engine half does not know the session half |
| `:linux` | rootfs, bootstrap, archive, chroot exec | terminal sessions, UI |
| `:features:ssh` | SSH transport implementing the domain port | `:terminal` |
| `:data` | Room, DataStore, TOML, vault | `:terminal`, Compose |
| `:features:agent` | LLM providers, tools, chat | Compose (screen lives in `:ui`) |
| `:ui` | Screens and chrome over domain | `:terminal` |
| `:theme` | Tokens, typography | any feature module |

`:app` is the only module allowed to know everything; it wires ports to
implementations and nothing else.

### 1.1 The inversion that matters

`SshSessionFactory` is a `:terminal` type today, and `:ssh` implements it — so
`:ssh → :terminal`. In the target graph the transport port lives in `:domain`,
`:ssh` implements it there, and `:terminal` never sees an SSH type. Same for the
bootstrap ports currently imported by `:data`.

---

## 2. Rust / Kotlin boundary

**Data is processed where the grid lives; pixels and gestures stay in Kotlin.**

| Concern | Owner | Why |
|---|---|---|
| Grid, scrollback, modes, damage | the engine (Ghostty), read through the C ABI | single source of truth |
| Cell widths, wide characters, images | the engine | it knows what a cell is |
| Selection text, word/line boundaries | the engine | needs the cell widths and the grid geometry |
| Scrollback search | the engine | avoids copying the transcript across JNI |
| URL detection per cell | the engine | same reason |
| The frame: cells, colours, images | Rust (`native/src/frame.rs`) | one snapshot for one crossing |
| The bell, the notifications, the prompt marks | Rust (`native/src/bell.rs`) | the engine's callbacks |
| ANSI stripping, the shell tap | Kotlin (`native/src/shell.rs`) | the bytes arrive here |
| Pixels, the glyph atlas, the cursor | Kotlin (`engine/render/`) | OpenGL ES |
| Gesture recognition, IME, insets | Kotlin | platform API |
| Selection handles, find bar, link chip | Kotlin | drawing |
| Session/panes/blocks orchestration | Kotlin (`:terminal`) | product policy, not terminal semantics |
| Persistence, networking | Kotlin | platform API |

The `native/src/jni/` files contain `extern "C"` entry points and handle
validation only; the behaviour lives in `native/src/`. A `jni/*.rs` over 120
lines means logic leaked into the boundary.

### 2.1 JNI surface layout

One file per concern; the surface is `engine/native/`, and the session and the
surface's own halves live beside it.

```
android/terminal/src/main/kotlin/dev/drosh/terminal/engine/native/
  NativeSession.kt    create/feed/resize/frame/scroll/modes/title/images
  NativeSelection.kt  selectWord/selectLine/selectWordBetween/text
  NativeSearch.kt     search(query, limit)
  NativeInput.kt      key and mouse encoders
  NativePty.kt        spawn/read/write/close/pid
```

Handles are opaque positive `jlong` values, never raw pointers. No `unwrap()`
or panic may cross the boundary; the panic hook is installed at load.

---

## 3. Layer rules (enforced)

1. `:domain` imports no Android type and no Drosh module.
2. `:terminal` imports nothing above it (`:terminal`, `:linux`, `:data`, `:agent`, `:ssh`, `:ui`).
3. `:linux` imports no UI, no `:terminal`, no `:terminal`.
4. `:terminal` imports no Compose, no `:ui`, no `:app`.
5. `:ui` imports neither `:terminal` nor `:terminal`.
6. Only `android/terminal/engine/.../native/` (today `android/terminal/engine/.../core/`) declares `external fun`.
7. No module imports `com.termux.*` once Termux is removed (P3).

Run `./gradlew checkModuleBoundaries` (it is also part of every `check`).

### 3.1 Debt baseline

The rule table lives in `build.gradle.kts` as `boundaryRules`
(`module|prefix|allowed`) and records the exact number of known violations, so
debt can only go down. Lowering a number is part of the phase that removes the
imports; raising one is a review failure.

It is a task in the root build script, not a build-logic plugin: applying one to
the root project puts its jar on the build classpath with an unknown version,
which then collides with the versioned aliases the modules use.

### 3.2 Modules that do not exist yet

A rule may name a module the plan expects but that is not in the tree yet
(`:linux`, in P4). Such a module is listed in `plannedModules`, and the checker
asserts that it is *still* absent — so its rules cannot pass unnoticed, and
when the module appears the checker fails until the rules move out of that list
and start being enforced.

A rule that names a module which is neither in the tree nor planned is a build
failure. A rule nobody can check is a comment, not a rule.

| Rule | Baseline | Cleared by |
|---|---:|---|
| `data → terminal` | 22 | P3 |
| `ssh → terminal` | 1 | P5 |

---

## 4. Naming contract

### 4.1 Kotlin

| Rule | Example |
|---|---|
| File name = the single public type | `SessionCoordinator.kt` |
| Package = `dev.drosh.<module>.<area>` | `dev.drosh.terminal.session` |
| Port (contract) ends in `*Port` | `TransportPort` |
| Persistence ends in `*Store` | `SnippetStore` |
| Lifecycle owner ends in `*Coordinator` | `SessionCoordinator` |
| State producer ends in `*Tracker` | `CommandActivityTracker` |
| View command surface ends in `*Controller` | `TerminalController` |
| Android View `*View`, Compose `*Overlay`, GL `*Renderer` | |
| **Banned suffixes** | `*Manager`, `*Impl`, `*Helper`, `*Util`, `*Wire`, `*Handler` |

The banned list is not taste. Each of those names currently marks a class whose
responsibility is unclear: `TerminalManager` (1808 lines), `BlockEngineWire`,
`MouseInputHandler`, `TerminalViewClientImpl`, `TerminalSessionClientImpl`.

### 4.2 Rust

| Crate | Responsibility | Rule |
|---|---|---|
| `drosh-session` | behaviour | no Android, no JNI, unit-tested with `cargo test` |
| `drosh-pty` | PTY + the child process (D8) | must not know about the grid |
| `drosh-bridge` | struct/byte marshalling | no JNI, plain Rust |
| `drosh-font` | raster, shaping | must not pull the VT engine in |
| `drosh-jni` | `extern "C"` + handle validation, `JNI_OnLoad`, logging | no business logic; `ffi/*.rs` ≤ 120 lines |

The table is enforced by Cargo, not by review: a crate can only call what its
manifest declares, so "the bridge must not see JNI" is a missing dependency
rather than a comment. `ffi/*.rs` stays boring by keeping the behaviour in
`drosh-session`.

---

## 5. Size budget

| Limit | Value | Rationale |
|---|---|---|
| Kotlin file | ≤ 700 lines | beyond this, a file is a subsystem pretending to be a class |
| `ffi/*.rs` | ≤ 120 lines | boundary files should be boring |
| Public types per file | 1 | keeps the file name honest |

Current overruns, all scheduled: `TerminalScreen.kt` 2238 (P6), `TerminalManager.kt` 1808 (P3),
`RootfsArchiveWriter.kt` 860 (P4), `RootfsArchiveReader.kt` 614 (P4).

---

## 6. Migration phases

| Phase | Scope | Gate |
|---|---|---|
| P0 | this document, `checkModuleBoundaries`, baseline | `check` green, boundary breach proven red |
| P1 | Rust: selection, search, URL, block, tint, text | `cargo test` + clippy + cross-check |
| P2 | `:terminal` view becomes the product view | device checklist |
| P3 | `:terminal` rewritten, Termux deleted | both paths build; device checklist |
| P4 | `:linux` extracted, chroot-ng replaces PRoot | clean install, `uname -a`, backup round-trip |
| P5 | `:ssh` over bridged sessions | SSH session checklist |
| P6 | `:app` terminal feature split | tabs/panes/zoom/find/links |
| P7 | docs, CHANGELOG, dead adapter removal | `check` + full device pass |

Rules for every phase: one branch, one PR, CI green before merge, `main` never red,
no force-push.

The repository layout itself was split between P0 and P1 (2026-10-20): the Gradle
build moved under `android/` and the Cargo crate under `rust/` (now `native/`).
It was a pure move — module names, Kotlin packages and the cdylib name did not
change — so no import, Hilt binding or `System.loadLibrary` call was affected.

What changed since: the terminal engine itself. The vendored Termux engine and
its Kotlin view are gone; Ghostty's VT core is driven from `native/` and the
pixels are drawn by our own OpenGL ES renderer. The cdylib is `libdrosh.so`
alongside `libghostty-vt.so`.

The guest runtime left the repository with P4 (2026-10-20): chroot-ng is built
and published by its own project, and Drosh consumes a pinned release asset
(tag + SHA-256) instead of vendoring the source.
