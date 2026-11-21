# CREDITS.md

Drosh stands on a few big shoulders. This file says where the borrowed work
came from; [`LICENSE.md`](LICENSE.md) says what the terms are.

| What | Where it lives in this project |
|---|---|
| **Ghostty** — the VT core (`libghostty-vt` v1.4.0) that parses the terminal's output and owns the grid, modes and scrollback | downloaded and checksum-verified by `tools/fetch-ghostty-vt.sh`, shipped as `libghostty-vt.so`; license in `LICENSES/MIT-Ghostty.txt` |
| **chroot-ng** — the ptrace-free, seccomp-based guest runtime; upstream [`sylirre/fake-chroot-ng`](https://github.com/sylirre/fake-chroot-ng), built here as the fork [`mmuhofy/chroot-ng`](https://github.com/dexbycode/chroot-ng) | pinned release asset staged from `android/linux/src/main/assets/libchrootng.so`; license beside it |
| **Ubuntu 24.04** — the Linux userspace | downloaded on first launch, never vendored |
| **sora-editor** (Rosemoe) — the native code editor and its TextMate module | `:features:editor` dependency, used unmodified — `android/features/editor/NOTICE.md` |
| **TextMate grammars** — syntax highlighting for the editor | `android/features/editor/src/main/assets/textmate/`, provenance beside them |
| **Geist** (Vercel × basement.studio), **Inter** (Rasmus Andersson), **JetBrains Mono** (JetBrains) — the typefaces | `android/theme/src/main/res/font/`, licenses in `LICENSES/fonts/` |

Drosh does not ship the former Termux terminal engine or the former PRoot
binary. Both were replaced: the engine by Ghostty plus Drosh's Rust/OpenGL
core, the guest runtime by chroot-ng.

## The credit line

All Drosh-authored code is Apache-2.0, so no visible credit is required in an
app that ships it — the license is the whole deal. Apache-2.0 does have a hat
on top of its requirements: the NOTICE file. If you redistribute a Derivative
Work, keep `android/features/editor/NOTICE.md` and the third-party notices
with it.

And if you built something with Drosh's pieces — the design system, the block
engine contracts, the agent adapters — a line in your README is the whole
ritual:

> Built with [Drosh](https://github.com/dexbycode/Drosh).

No obligation. Just how the name travels.
