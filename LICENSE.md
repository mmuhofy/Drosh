# Drosh — the license map

Drosh is not one license, because it is not one kind of code. Some of it is
Termux's, some of it is the Linux environment's, and some of it is Drosh's
own. This file is the map; each row says who owns what and under which terms.
The full texts live in `LICENSES/`.

## The one thing to know first

**The APK you install is GPL-3.0.** `:terminal` contains code modified from
Termux, and Termux is GPLv3 — the app that ships that code ships as a combined
work under the GPL. That is not a choice anyone gets to make, and it is why the
root `LICENSE` file (the one GitHub and F-Droid read) is GPLv3: for the app as
a whole, that is the truth.

**The Drosh-authored source is Apache-2.0.** Everything the project wrote
itself — the app, the UI, the domain models, the data layer, the agent, the
design system — is Apache-2.0. That means the reusable parts (the block engine
contracts, the agent adapters, the Compose design system) can be lifted into
another project without dragging copyleft along, while the APK they happen to
be compiled into stays GPLv3 because of `:terminal`.

## The map

| Component | Location | License | Why |
|---|---|---|---|
| Terminal engine + shell integration | `android/terminal/` (both `com.termux.*` and `dev.drosh.terminal.*`) | **GPL-3.0** | modified Termux code, vendored in-tree |
| Terminal native library | `android/terminal/src/main/jniLibs/` (`libtermux.so`) | **GPL-3.0** | Termux's JNI library |
| chroot-ng guest runtime | fetched from its own release into `android/terminal/src/main/jniLibs/` (`libchrootng.so`) | **Apache-2.0** | third-party binary, unmodified — see `android/terminal/src/main/assets/licenses/` |
| Drosh source | `android/app/` `android/ui/` `android/data/` `android/domain/` `android/agent/` `android/ssh/` `android/core/` `android/theme/` | **Apache-2.0** | Drosh-authored |
| Linux guest | `android/guest/` | **Apache-2.0**, except the rootfs archive format | Drosh-authored; `backup/RootfsArchive{Reader,Writer}.kt` are GPL-3.0 |
| Editor wrapper | `android/editor/` (Drosh code) | **Apache-2.0** | Drosh-authored; the editor it wraps is LGPL — see `android/editor/NOTICE.md` |
| Editor component | sora-editor (`io.github.rosemoe:editor`, `language-textmate`) | **LGPL-2.1-or-later** | third-party dependency, unmodified |
| TextMate grammars | `android/editor/src/main/assets/textmate/` | **MIT** | microsoft/vscode + fwcd/vscode-kotlin — see `android/editor/src/main/assets/textmate/PROVENANCE.md` |
| Fonts | `android/theme/src/main/res/font/` | **OFL-1.1** | Geist, Inter, JetBrains Mono — see `LICENSES/fonts/` |
| PRoot binary | `android/app/src/main/assets/proot` | **GPL-2.0** | third-party binary, unmodified |
| talloc library | `android/app/src/main/assets/libtalloc.so.2` | **LGPL** | Samba talloc, unmodified |
| Ubuntu 24.04 userspace | downloaded on first launch, never in this repo | various (Ubuntu community licenses) | see the note below |

## Notes on specific rows

**Ubuntu userspace.** The rootfs is downloaded at runtime rather than shipped,
so its packages (each under its own license — GPL, LGPL, Apache, MIT and
others) never enter this repository. The download source is recorded in
`android/guest/.../UbuntuBootstrap`.

**Fonts.** The OFL is not a software license and does not mix into the GPL
question: fonts are data. What the OFL does require is that each copy carry the
copyright notice and the license, which is what `LICENSES/fonts/` is for, and
that reserved font names are not reused by derivatives — Drosh ships them
under their own names (Geist, Inter, JetBrains Mono) and never renames them.

**The vendored binaries.** `proot` and `libtalloc.so.2` ship unmodified. If
either is ever patched in-tree, its source must be offered alongside; that is
what GPL-2.0 and the LGPL require, and it is easier to keep them pristine.

## How this shows up in code

There are no license headers in the Kotlin sources, on purpose: 335 files
carrying a 13-line block each is 4,000 lines that can drift from this file and
be wrong. `LICENSE.md` is the single source of truth — a file is under the
license of the directory it lives in, as the map above says. New files added by
the project use the short Apache notice from the appendix of
`LICENSES/Apache-2.0.txt`; vendored files keep whatever headers upstream
shipped them with.

## What this license map is not

This is a summary written so a reader can find the right license fast, not
legal advice, and in any conflict the full text in `LICENSES/` and the upstream
licenses of the third-party components govern.
