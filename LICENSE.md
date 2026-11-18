# Drosh — the license map

**Drosh is Apache-2.0.** The full text is in [`LICENSE`](LICENSE) at the root
and in [`LICENSES/Apache-2.0.txt`](LICENSES/Apache-2.0.txt). This file is the
map: it says which parts are Drosh's, which parts come from somewhere else,
and where the upstream license texts live.

There is no GPL copyleft in the APK. The terminal is Drosh's own Kotlin plus
the Rust half in `native/`, driving Ghostty's VT core (MIT). The Linux guest
runs under chroot-ng (Apache-2.0), not PRoot. Neither the old Termux engine
nor the old PRoot binary is in the tree or in the APK.

## The map

| Component | Location | License | Why |
|---|---|---|---|
| All Drosh-authored source — app, UI, domain, data, agent, SSH, theme, core, terminal, editor wrapper, Linux guest, Rust half | `android/`, `native/` | **Apache-2.0** | written by the project; reuse without copyleft |
| VT engine | `libghostty-vt.so`, fetched by `tools/fetch-ghostty-vt.sh` | **MIT** | Ghostty v1.4.0, checksum-verified, not committed — [`LICENSES/MIT-Ghostty.txt`](LICENSES/MIT-Ghostty.txt) |
| Guest runtime | `android/linux/src/main/assets/libchrootng.so` | **Apache-2.0** | pinned release of `mmuhofy/chroot-ng` (a fork of [`sylirre/fake-chroot-ng`](https://github.com/sylirre/fake-chroot-ng)); copy in `android/linux/src/main/assets/licenses/chroot-ng-LICENSE.txt` |
| Editor component | `io.github.rosemoe:editor`, `language-textmate` | **LGPL-2.1-or-later** | third-party dependency, used unmodified — [`android/features/editor/NOTICE.md`](android/features/editor/NOTICE.md) |
| TextMate grammars | `android/features/editor/src/main/assets/textmate/` | **MIT** | upstream grammars, unmodified — `PROVENANCE.md` beside them |
| Fonts | `android/theme/src/main/res/font/` | **OFL-1.1** | Geist, Inter, JetBrains Mono — [`LICENSES/fonts/`](LICENSES/fonts/), shipped under their reserved names |
| Ubuntu 24.04 userspace | downloaded on first launch, never in this repo | various (Ubuntu community licenses) | packages keep their own licenses; none are vendored here |

## Notes

**The guest runtime.** Drosh moved off PRoot because its ptrace-based syscall
interception cost too much on a phone. chroot-ng is ptrace-free: seccomp traps
only path-bearing syscalls and its own ELF loader runs static, Go and Rust
guest binaries. Upstream is `sylirre/fake-chroot-ng`; Drosh consumes its own
fork, `mmuhofy/chroot-ng`, as a pinned release asset with a verified SHA-256.
Local patches to that fork are planned. If a patched binary is ever shipped,
its source stays public and the Apache-2.0 NOTICE requirements follow it —
see [`CREDITS.md`](CREDITS.md).

**The fonts.** The OFL is a font license, not a software license: it does not
mix into the code's terms. Each copy ships with its copyright notice and
license in `LICENSES/fonts/`, and Drosh does not rename the reserved families.

**No license headers in every source file.** `LICENSE.md` is the single source
of truth: a file is Apache-2.0 unless it is a third-party asset named above.
New files use the short notice from the appendix of `LICENSES/Apache-2.0.txt`;
upstream files keep the headers their authors gave them.

**Not legal advice.** This map is written to make the terms findable. Where it
and a full license text disagree, the full text in `LICENSES/` or the upstream
project governs.
