# CREDITS.md

Drosh stands on a few big shoulders, and the APK carries their code. This file
is where they are named — the licenses in `LICENSE.md` say what the terms are,
this file says where the work came from.

| What | Where it lives in this project |
|---|---|
| **Termux** — the terminal engine, the PTY stack, the shell tooling this app was rebuilt from | `android/terminal/` (vendored, modified) |
| **chroot-ng** — the ptrace-free chroot/bind emulation that runs the guest, upstreamed from `sylirre/fake-chroot-ng` | fetched from its own release into `android/terminal/src/main/jniLibs/` |
| **PRoot** — the userspace that lets Ubuntu run without root | `android/app/src/main/assets/proot` |
| **Ubuntu 24.04** — the Linux environment itself | downloaded on first launch |
| **sora-editor** (Rosemoe) — the native code editor | `:editor` dependency |
| **TextMate grammars** — syntax highlighting for the editor | `android/editor/src/main/assets/textmate/` |
| **Geist** (Vercel × basement.studio), **Inter** (Rasmus Andersson), **JetBrains Mono** (JetBrains) — the typefaces | `android/theme/src/main/res/font/` |

Full licenses: `LICENSE.md`, `LICENSES/`, `android/editor/NOTICE.md`,
`android/editor/src/main/assets/textmate/PROVENANCE.md`.

## The credit line

The Drosh-authored modules are Apache-2.0, so nobody owes a visible credit in
an app that ships them — the license is the whole deal. But Apache-2.0 has a
hat on top of its requirements: the NOTICE file. If you redistribute a
Derivative Work of this code, keep `android/editor/NOTICE.md` and the third-party
notices with it.

And if you built something with Drosh's pieces — the design system, the block
engine contracts, the agent adapters — a line in your README is the whole
ritual:

> Built with [Drosh](https://github.com/mmuhofy/Drosh).

No obligation. Just how the name travels.
