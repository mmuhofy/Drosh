# Drosh Architecture

The layer rules every module obeys, the naming contract every file follows, and
where the Rust/Kotlin boundary sits. This document is normative: the
`checkModuleBoundaries` task fails the build when §3 is broken, so a rule here
that nobody enforces is a bug in this file.

---

## 1. Module graph

Every module directory below lives under `android/`, the Gradle build root;
`gradlew`, `settings.gradle.kts` and `build-logic/` live there too. The Rust
engine is the separate `rust/` Cargo workspace (§2). Module *names* are unchanged
by that split: `android/data/` is still `project(":data")`.

```
:app  ──► everything (composition root only: Activity, NavHost, Hilt wiring)

 ├── :ui ──────────► :domain, :design-system
 ├── :terminal ────► :engine, :guest, :domain
 ├── :agent ───────► :terminal, :domain
 ├── :ssh ─────────► :domain, :engine       (implements TransportPort)
 ├── :engine ──────► :domain
 ├── :guest ───────► :domain
 ├── :data ────────► :domain
 ├── :editor ──────► :domain, :design-system
 └── :domain ──────► (nothing — pure Kotlin)
```

| Module | Owns | Must not know about |
|---|---|---|
| `:domain` | Entities, ports, use cases | Android, any Drosh module |
| `:engine` | Rust core, JNI surface, GL renderer, input encoder, terminal view | sessions, panes, blocks, UI chrome |
| `:guest` | rootfs, bootstrap, archive, chroot exec | terminal sessions, UI |
| `:terminal` | sessions, panes, tabs, blocks, shell launch | Compose, `:ui`, `:app` |
| `:ssh` | SSH transport implementing the domain port | `:terminal` |
| `:data` | Room, DataStore, TOML, vault | `:terminal`, Compose |
| `:agent` | LLM providers, tools, chat | Compose (screen lives in `:ui`) |
| `:ui` | Screens and chrome over domain | `:terminal`, `:engine` |
| `:design-system` | Tokens, components | any feature module |

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
| Grid, scrollback, modes, damage | Rust (`alacritty_terminal`) | single source of truth |
| PTY, shell integration tap, OSC 133/7/1339 | Rust | bytes arrive there |
| Selection text, word/line boundaries | Rust | needs cell widths and grid geometry |
| Scrollback search | Rust | avoids copying the transcript across JNI |
| URL detection per cell | Rust | same reason |
| Block boundaries | Rust | OSC 133 markers already parsed there |
| Ambient tint | Rust | averages visible cells without a copy |
| ANSI stripping | Rust | parser already present; no regex |
| Gesture recognition, IME, insets | Kotlin | platform API |
| Selection handles, find bar, link chip | Kotlin | drawing |
| Session/panes/blocks orchestration | Kotlin (`:terminal`) | product policy, not terminal semantics |
| Persistence, networking | Kotlin | platform API |

`ffi/` files contain `extern "C"` entry points and handle validation only; the
behaviour lives in `session/`. A `ffi/*.rs` over 120 lines means logic leaked
into the boundary.

### 2.1 JNI surface layout

The target is one file per concern; today the surface is `core/NativeCore.kt`
(the `external fun` declarations) plus `core/EngineSession.kt` (the session
wrapper the view talks to).

```
android/engine/src/main/kotlin/dev/drosh/engine/native/
  NativeCore.kt       library load, handle lifetime
  NativeTerminal.kt   create/feed/resize/snapshot/state/modes
  NativeSelection.kt  select, extend, selectedText, wordAt, lineAt
  NativeSearch.kt     search(query, limit)
  NativeText.kt       urlAt, stripAnsi, ambientTint
  NativeEvents.kt     drainEvents
```

Handles are opaque positive `jlong` values, never raw pointers. No `unwrap()`
or panic may cross the boundary; the panic hook is installed at load.

---

## 3. Layer rules (enforced)

1. `:domain` imports no Android type and no Drosh module.
2. `:engine` imports nothing above it (`:terminal`, `:guest`, `:data`, `:agent`, `:ssh`, `:ui`).
3. `:guest` imports no UI, no `:terminal`, no `:engine`.
4. `:terminal` imports no Compose, no `:ui`, no `:app`.
5. `:ui` imports neither `:terminal` nor `:engine`.
6. Only `android/engine/.../native/` (today `android/engine/.../core/`) declares `external fun`.
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
| `drosh-core` | `extern "C"` + handle validation, `JNI_OnLoad`, logging | no business logic; `ffi/*.rs` ≤ 120 lines |

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
| P2 | `:engine` view becomes the product view | device checklist |
| P3 | `:terminal` rewritten, Termux deleted | both paths build; device checklist |
| P4 | `:guest` extracted, chroot-ng replaces PRoot | clean install, `uname -a`, backup round-trip |
| P5 | `:ssh` over bridged sessions | SSH session checklist |
| P6 | `:app` terminal feature split | tabs/panes/zoom/find/links |
| P7 | docs, CHANGELOG, dead adapter removal | `check` + full device pass |

Rules for every phase: one branch, one PR, CI green before merge, `main` never red,
no force-push.

The repository layout itself was split between P0 and P1 (2026-10-20): the Gradle
build moved under `android/` and the Cargo workspace under `rust/`, with
`third_party/chroot-ng` for the vendored C guest runtime. It was a pure move —
module names, Kotlin packages and the cdylib name did not change — so no import,
Hilt binding or `System.loadLibrary` call was affected.
