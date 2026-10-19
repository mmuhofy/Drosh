# Rust core (`libdrosh_core.so`)

The Rust half of the terminal core — built as a cdylib; the Android app talks to
it over JNI. See [`../../PLAN.md`](../../PLAN.md) §5 for the design and
[`../../TODO.md`](../../TODO.md) for the Phase A status.

**Layout**

| Path | Contents |
|---|---|
| `src/lib.rs` | cdylib entry: `JNI_OnLoad`, panic hook, `nativeVersion` |
| `src/ffi/` | the JNI boundary: `mod.rs` (error codes + helpers), `session.rs`, `snapshot.rs`, `events.rs` |
| `src/session/` | `mod.rs` (Session lifecycle), `manager.rs` (handle table), `pty_factory.rs` (PTY + child) |
| `src/bridge/` | `cell.rs` (16-byte cell record), `snapshot.rs` (grid → buffer + damage), `events.rs` (EventProxy) |
| `src/util/` | logging |
| `tests/` | integration tests: engine/snapshot correctness and a real PTY session |

**Wire formats** (frozen for the prototype; the Kotlin mirror lives next to the
renderer): the cell record in `bridge/cell.rs` (16 bytes little-endian), the
damage list and state array in `bridge/snapshot.rs`, the event records in
`ffi/events.rs`, the error codes in `ffi/mod.rs`.

**Dependencies:** `alacritty_terminal =0.26.0` (VT engine, D2), `jni =0.21`,
`libc`, `log`, plus `polling` and `signal-hook` — the same versions the engine
itself uses, so the tree keeps one copy of each. Phase B adds `rustybuzz` +
`swash` (Gate 2).

**No local builds** (AGENTS.md §10): `cargo fmt`, `clippy`, `test` and the
Android cross-check all run in GitHub Actions.
