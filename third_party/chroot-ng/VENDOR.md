# Vendoring notes

This directory is a **git subtree** of
[sylirre/fake-chroot-ng](https://github.com/sylirre/fake-chroot-ng), not our
code. Everything below the repository root of that project is upstream's; the
only Drosh-specific files here are this note and whatever a patch branch adds.

| | |
|---|---|
| Upstream | `https://github.com/sylirre/fake-chroot-ng.git` |
| Pin | `12d07c1a4da8d4c159899633fbfab3bd8ceeeee8` (2026-10-14, `procfs: a mount table opened from a signal handler hung under -R`) |
| License | Apache-2.0 (`LICENSE` and `NOTICE` stay inside this subtree) |
| Language | C11, freestanding (`-nostdlib`, own `_start`), AArch64 only |

## Why a subtree and not a submodule

The build has to work from a plain `git clone` in CI, offline from any
submodule host, and the pin has to be visible in the diff when it moves. A
subtree gives both: `git log` shows the squashed import, and the files are
present in the checkout.

## Updating the pin

```sh
git subtree pull --prefix=rust/chroot-ng \
  https://github.com/sylirre/fake-chroot-ng.git <commit> --squash
```

Then: bump the pin in this table, run the `Gate 3 — chroot-ng cross-build` CI
job, and re-run the on-device probe (TODO.md, Phase C). A pin bump is a
deliberate change, not a merge train.

## Drosh patches

Drosh-specific changes do **not** go into the subtree. They live in a patch
branch (`feat/chroot-ng-patches`) applied on top, so a pin bump is a rebase of
a small, readable set instead of a fork that drifts. Generic fixes go upstream
first.

## Build

```sh
make -C rust/chroot-ng CROSS=aarch64-linux-gnu-
# → rust/chroot-ng/build/chroot-ng  (static, freestanding, no libc)
```

The CI job installs `gcc-aarch64-linux-gnu` and runs exactly that. The result
is a static ELF with no interpreter, which is what lets it run on Android where
a glibc dynamic binary would not.

## Packaging

The binary is copied into the APK as `lib/arm64-v8a/libchrootng.so`, because
`nativeLibraryDir` is the one place an app can `exec` from on Android 10+ — and
the `lib*.so` name is what makes the installer extract it there. It is not a
shared object; the name is a packaging requirement.
