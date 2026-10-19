# Drosh — Backup & Restore Plan

> **Status: implementation in progress** (branch `feat/backup-restore`).
> Reference research was source-verified: proot-distro (`termux/proot-distro`,
> Python implementation) and desktop terminals (kitty, WezTerm, Ghostty,
> Alacritty, termux-app — read from source).

---

## 0. What this is

Drosh owns two separate state worlds that no single mechanism covers:

1. **App-private (Android sandbox):** Room DB (sessions, workspaces, agent
   chats, ssh hosts/keys), TOML settings (`<rootfs>/home/.drosh/settings.toml`
   with an app-private mirror at `filesDir/.drosh/settings.toml` per
   `SettingsPaths`), DataStore, theme store, background images, snippets.
2. **The rootfs (Linux environment):** `filesDir/ubuntu` — apt state, `/home`,
   `/etc`, `/opt`, `/usr/local`, plus app-generated files (`.zshrc`,
   `.drosh_motd` from `ensureShellRc`).

Desktop terminals have no backup feature at all (verified): kitty ships
*session files* (recreate the layout — not the bytes), Alacritty/Ghostty/WezTerm
ship nothing, Termux ships nothing (only crash-log rotation). The desktop
consensus is **restore the arrangement, never the scrollback**. proot-distro
ships a real rootfs backup — we mirror its format and harden it.

## 1. Scope tiers (user-visible)

| Tier | Contents | Default |
|---|---|---|
| T0 Settings | `settings.toml` (app mirror), themes, background images, snippets, input prefs | on |
| T1 App data | T0 + Room DB snapshot (sessions/workspaces/chats/ssh hosts) + DataStore | on |
| T2 Secrets | SSH private keys, agent API keys, PIN | **off** (v2, encrypted) |
| T3 Rootfs | `<rootfs>/home`, `/etc`, `/opt`, `/usr/local`, `/root` + `dpkg -l` package list | on |
| T4 Rootfs full | the whole rootfs tree | off (manual) |

T3 restores by *reinstalling from the package list* — never copy binaries.
Naming note: the pd-aligned archive format below covers T3/T4 with one
mechanism (`rootfs/` member is either the full tree or the selected subtrees).

## 2. Archive format (mirrors proot-distro)

```
<name>/manifest.json          (JSON: formatVersion, createdAt, appVersionCode,
                               rootfsIncluded, sections[{id, kind, archivePath,
                               byteSize, sha256}])
<name>/rootfs/...             (the rootfs member tree, or T3 subtrees)
<name>/app/settings.toml
<name>/app/datastore/...
<name>/app/database/drosh.db  (VACUUM INTO snapshot)
<name>/app/themes/..., snippets/...
```

- Container: `.tar.gz` in v1 (zero new dependencies — the version catalog has
  no compression library). **zstd level 10** is the documented later upgrade:
  proot-distro measured 101 MiB → 22 MiB in 3.8 s at level 10 vs gzip-9's
  27 MiB in 23 s (`compress.py:75`), so T4 will move to `zstd-jni` or a
  guest-side `tar | zstd` script when size/speed justifies it.
- `manifest.json` is written **last** and is the only file whose name is
  trusted; restore refuses an archive whose manifest is missing, unparseable,
  or a newer `formatVersion` than the app knows.

## 3. Security rules (learned from proot-distro's hardening)

1. **Permission-relax pass first.** A naive walk silently skips chmod-000
   directories — proot-distro lost subtree contents to exactly this before
   adding a relax pass. Our walker re-adds read/execute before archiving and
   restores modes afterwards.
2. **Descriptor-based walk, never by path.** Guest-writable directories can be
   swapped between check and pack. Entries are addressed as
   `(parentFd, name)` and opened `O_NOFOLLOW` (JVM: `FileChannel.open` with
   `NOFOLLOW_LINKS` where available; document the limitation on API < 26 —
   our minSdk is 26, so `StandardOpenOption.NOFOLLOW_LINKS` is free).
3. **Zip-slip guard.** Every member's destination is canonicalised and must
   stay under the staging root; symlinks in the archive are never followed.
4. **Atomic restore.** Extract to `rootfs.restore-staging/`, verify, then
   atomic directory replace; the previous tree is kept as
   `rootfs.pre-restore/` for rollback. Manifest is written after the swap
   succeeds, so a crash mid-restore leaves no half-truth.
5. **Never backed up (regenerated):** `ProotRunner`'s fake `/proc` files
   (`fake_proc_stat`, `fake_proc_vmstat`), `PROOT_TMP_DIR`, the staged proot
   binary/`libtalloc`, and the loader `.so` in `nativeLibDir`.

## 4. App-data snapshot rules

- **Room:** consistent copy without closing the DB — `VACUUM INTO` (SQLite
  3.27+) via `SupportSQLiteDatabase` exec, or a short close/copy window if
  unavailable. Never tar a live WAL.
- **DataStore:** copy the `datastore/` preferences files while no write is in
  flight (a short `withContext` barrier around the copy).
- **Settings:** write the **app mirror** (`filesDir/.drosh/settings.toml`) and
  re-write it into the restored rootfs on restore (primary + mirror, in that
  order).
- **Themes/backgrounds/snippets**: plain file copies of their store dirs.

## 5. Termux (bionic) → Drosh (glibc) migration

What survives: `~/` dotfiles and user data, public SSH keys, and the **package
list** (`dpkg -l` from `$PREFIX/var/lib/dpkg/status`). What does not: Termux's
bionic-linked binaries (`$PREFIX` tree) — they cannot run under glibc.

Mechanics:
- Package-name mapping table (Termux → Ubuntu): most names match; the known
  divergences are curated in `TermuxPackageMap` with an "unmapped" bucket that
  the user can edit before applying. Unmatched packages land in
  `~/imported-from-termux/packages-not-matched.txt` — nothing is dropped
  silently.
- Dotfile adapter: `.zshrc`/`.bashrc` are rewritten line-wise — strip `$PREFIX`
  paths, `pkg`/`termux-*` invocations, `LD_LIBRARY_PATH`; keep aliases, exports,
  prompt basics, history.
- Anything not automatically migratable is extracted to
  `~/imported-from-termux/` as-is.

## 6. Restore pipeline

1. Pre-checks: free space, no conflicting rootfs lock, manifest version.
2. Stage archive → verify checksums → restore app-data sections (atomic file
   replace, DB swap).
3. Rootfs: staging extract → atomic replace → configure via the existing
   bootstrap script pipeline (`runScriptInProot` — `UbuntuBootstrap` pattern).
4. Re-write settings into the new rootfs; recreate fake/proc and tmp paths.
5. Checkpoint after every section (`checkpoint.json` in the staging dir) so a
   killed process resumes instead of restarting; failure rolls back to
   `.pre-restore`.
6. v1 ends with an explicit "restart Drosh to apply" prompt — no process
   kill trickery.
7. **Arrangement restore** (the desktop consensus): Room restores workspaces,
   sessions, and each session's last known cwd (`lastDir`); no scrollback or
   live blocks are restored — no desktop terminal does either.

## 7. Deferred to v2

T2 encrypted secrets (PBKDF2 + AES-GCM), WorkManager rolling backups,
`.tar.zst` output, incremental rootfs backup, drop-in interop with
proot-distro's own manifest schema.

## 8. Work split (parallel agents, disjoint files)

| Agent | Files | Deliverable |
|---|---|---|
| **B** — `android/terminal/.../backup/**` | RootfsArchiveWriter (walk + relax pass + excludes + tar members), RootfsArchiveReader (staged extract + path guard), JVM tests | rootfs engine, leaf module |
| **A** — `android/data/.../backup/*.kt` + BindingsModule | BackupRepository impl orchestrating B + app-data sections + manifest read/write, `VACUUM INTO` snapshot, tests | end-to-end create/restore for app data |
| **C** — `android/data/.../backup/termux/**` | TermuxMigrationImporter + package map + dotfile adapter + tests | bionic→glibc migration planning |
| **D** — `android/ui/.../backup/**`, `MainActivity.kt` (route), Settings entry | BackupRestoreScreen + ViewModel + settings/nav entry + restart prompt | user-facing surface |

Integration order: **B → A → C → D** (dependency order), one commit per slice,
pushed after each slice so CI validates incrementally. **No local Gradle
builds** — verification is CI (`./gradlew test` + build) only.

## 9. Definition of done

- Backup produces an archive whose manifest lists real checksums; restore of
  every non-default tier round-trips on CI unit tests (temp dirs, temp DB).
- Archive refuses: missing/oversized manifest, checksum mismatch, member paths
  escaping the staging root, symlink members pointing outside.
- Migration never silently drops a package or a dotfile — the leftovers file
  and log say so.
- Every commit carries a before/after note in `docs/MEMORYBANK.md`.
