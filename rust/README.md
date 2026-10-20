# The Rust engine

The Rust half of the terminal: the VT engine, the PTY, the wire formats Kotlin
reads, and the font stack. It builds to a cdylib — `libdrosh_core.so` — that the
Android app loads over JNI.

See [`../docs/ARCHITECTURE.md`](../docs/ARCHITECTURE.md) §2 for the Rust/Kotlin
boundary and §4.2 for what each crate is allowed to know.

**Crates**

| Crate | Contents |
|---|---|
| `crates/drosh-jni` | `JNI_OnLoad`, the `ffi/` boundary, `util/` logging — the crate that builds `libdrosh_core.so` |
| `crates/drosh-session` | `Session` lifecycle and handle table, the event loop, and the grid capabilities (`grid`, `selection`, `search`, `url`, `block`, `tint`, `text`) |
| `crates/drosh-pty` | PTY creation and the child process on the other end (decision D8) |
| `crates/drosh-bridge` | cell records, damage list, state array, event queue |
| `crates/drosh-font` | `rustybuzz` shaping, `swash` rasterisation |

A crate can only call what its `Cargo.toml` declares, which is the point of the
split: "the bridge must not see JNI" and "the font stack must not pull the VT
engine in" are compile errors here rather than review notes.

**Wire formats** (frozen for the prototype; the Kotlin mirror lives next to the
renderer): the cell record in `drosh-bridge`'s `cell.rs` (16 bytes
little-endian), the damage list and state array in its `snapshot.rs`, the event
records in `drosh-jni`'s `ffi/events.rs`, the error codes in its `ffi/mod.rs`.

**Commands**, from this directory:

```
cargo test --workspace
cargo fmt --all -- --check
cargo clippy --workspace --all-targets -- -D warnings
cargo check --workspace --target aarch64-linux-android
```

The host commands need nothing but a Rust toolchain. The Android artifact is
cross-compiled with the pinned NDK by CI, which drops it into
`android/terminal/src/main/jniLibs/arm64-v8a/` before Gradle runs; the `.so` is
never committed.