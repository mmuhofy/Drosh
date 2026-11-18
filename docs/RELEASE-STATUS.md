# Drosh — Release Status

_Snapshot: 2026-11-18, GitHub `main` @ `23d1d547` (source of truth for this file)._

This is a **single, code-verified inventory**: what is in the tree, what is
still missing, and what stands between the current code and a public release.
Every mark is backed by a path; `[~]` means present but incomplete or not
proven end-to-end. Where a document disagrees with the code, **the code wins**;
the stale documents are listed in §7 rather than trusted.

Legend: `[x]` implemented · `[~]` partial / unverified · `[ ]` absent or broken.

---

## 1. Release blockers — must be cleared before shipping

| | Blocker | Evidence / why it blocks |
|---|---|---|
| [ ] | **Production app id is `dev.drosh.my` on `main`** | `android/app/build.gradle.kts:36`. The side-by-side dev identity was merged into the release branch. A tag build would ship the dev app, not `dev.drosh`; authority and launcher name also follow it. Revert to `dev.drosh` (or decide the dev build lives only on its branch) before any release. |
| [ ] | **i18n coverage collapsed** | `tools/i18n-coverage.sh`: 754 base keys; `tr` 736/754, the other eight locales **418/754 (336 missing)**. `android/ui/src/main/res/values/drosh_strings.xml` has 667 keys; `values-de/drosh_strings.xml` 331; `values-tr` 649. Missing examples: `about_*`, `agent_attach_*`, `settings_font_custom_*`. |
| [ ] | **`i18n.mjs check` crashes** | `tools/i18n.mjs:182` → `Error: base missing: editor/values/strings.xml`. The editor module moved to `android/features/editor/`; the tool still looks at the old path. The placeholder/escaping gate therefore does not run at all. |
| [ ] | **No i18n gate in CI** | `grep -ri i18n .github/workflows/` → no matches. Nothing prevents the next PR from adding more untranslated strings. |
| [ ] | **Tagging cannot produce release notes** | `.github/workflows/release.yml:118-127` requires a `## [vX.Y.Z]` section in `CHANGELOG.md`; the file currently has only `## [Unreleased]` (`CHANGELOG.md:7`). The first tag push would fail. |
| [ ] | **License decision left to the owner** | `LICENSE.md:8-16` says the APK is no longer GPL-derived and that the choice is Muhofy's; root `LICENSE` still says GPL-3.0; README's badge says GPL-3.0; `LICENSES/` has Apache + fonts but **no MIT text** for Ghostty. `CREDITS.md:8` still credits Termux as the terminal engine. Choose and reconcile before an F-Droid or Play submission. |
| [~] | **Device-verification claim is wrong in the README** | `README.md:22-24` says only the terminal, gestures and sessions were run on a physical device, not the agent/SSH. Per Muhofy (2026-11-18) **everything has been device-verified**. The README needs the correction; the code cannot prove the claim either way. |
| [ ] | **No release marketing assets** | No screenshot or GIF in the repo (`README.md` has badges only, no `![]` preview). Three real device JPEGs exist only as untracked files in the old `termux-to-rust` worktree and show raw test states (chroot-ng warnings, font test), not a hero shot. |
| [ ] | **No distribution metadata** | No F-Droid `.fdroid.yml`/metadata, no fastlane/Play listing, no privacy policy or data-safety form in the repo. Description and 10 topics are set (`gh api`), so repo-level marketing is partly done. |
| [~] | **Release identity / signing** | `.github/workflows/release.yml:70-72` signs with the committed debug key on purpose. Fine for GitHub/F-Droid; not acceptable for Play. `targetSdk = 28` (`android/app/build.gradle.kts:38`) likewise blocks Play. Decide the channel first. |

---

## 2. Terminal core — Ghostty VT + Rust + OpenGL

The engine is **Ghostty v1.4.0 `libghostty-vt`** (MIT, C ABI), fetched from
`ancientcatz/libghostty-android` and verified against the release's
`SHA256SUMS` (`tools/fetch-ghostty-vt.sh:23-25`, `docs/ARCHITECTURE.md:8`).
The pixels are ours: OpenGL ES 3.0 (`EglCore.kt:83-87`, `#version 300 es`
shaders, `GLES30.glDrawArraysInstanced` in `FrameRenderer.kt:1020-1060`).
The Rust half is `native/` (`libdrosh.so`, GPL-3.0 crate metadata) and the
Kotlin half is `android/terminal/.../engine/`.

| | Feature | Evidence / state |
|---|---|---|
| [x] | Ghostty VT v1.4.0, checksum-verified fetch, not committed binary | `tools/fetch-ghostty-vt.sh:23-25,70-78`; CI: `.github/workflows/native.yml:106-128` |
| [x] | JNI boundary: PTY, session, input, search, selection, smoke | `native/src/jni/{pty,session,input,search,selection,smoke}.rs` (`Java_dev_drosh_...` exports); parity checked by `tools/check-jni-symbols.py` in `tests.yml:67-68` |
| [x] | PTY: spawn/read/write/resize/try-wait/pid | `native/src/jni/pty.rs:24-209`; `native/src/pty.rs` |
| [x] | Grid, scrollback, modes, damage — engine-owned | `native/src/renderer/{frame,row,cell}.rs`; `docs/ARCHITECTURE.md:63` |
| [x] | Wide chars (CJK/emoji) use the engine's cell width | `CHANGELOG.md` "Cells carry the engine's own width" |
| [x] | Instanced GL cell/glyph rendering, R8 glyph atlas | `engine/render/FrameRenderer.kt:187,1020-1060`; `engine/gl/GlTexture.kt:113` |
| [x] | Italic / bold / strikethrough / overline / underline | Recent commits `f190cf30` (italic), `4a652f7f` (strike/overline); atlas `engine/font/ColorAtlas.kt` |
| [x] | Background images | `native/src/renderer/image.rs`; `FrameRenderer.kt` image pass |
| [x] | Kitty graphics protocol | `native/src/png.rs`, `native/src/renderer/image.rs:3-27`; `CHANGELOG.md` |
| [x] | Cursor styles + blink | `native/src/jni/session.rs:230`; blink commit `e712ace9` |
| [x] | Bell / OSC 52 clipboard, clipboard opt-in | `native/src/bell.rs:48`; `native/src/clipboard.rs:1,81,180-238` |
| [x] | Mouse reporting (click/hold/drag/wheel) | `native/src/jni/input.rs:287,353`; `CHANGELOG.md` |
| [~] | Kitty keyboard protocol | Flags exposed (`native/src/ghostty.rs:164-165`, `jni/session.rs:429`, `jni/input.rs:8`); full protocol behavior not verified |
| [ ] | **Ligatures at runtime** | `harfrust = "=0.16.0"` and a shaped-glyph module with 5 tests exist (`native/src/font/mod.rs:3-45,330-365`, exported at `native/src/lib.rs:35`), but **no caller outside that module** (grep for `shape_run`/`Font::` → nothing), and no ligature path in the Kotlin glyph pipeline. The font assets even ship an `NL` (no-ligature) variant (`assets/fonts/README.md:20-24`). Shaper: ready; rendering: not wired. |
| [x] | OSC 133 prompt/command/exit marks, OSC 7 cwd, OSC 1339 editor | `native/src/shell.rs:3-13`; `android/terminal/.../DroshShellIntegration.kt:159-194,236` |
| [x] | Real exit codes in blocks (old "always 0" bug gone) | `domain/block/ShellState.kt:18` (`exitStatus`) ← OSC 133 `D` in `native/src/shell.rs:42`; `CommandStatePublisher.kt:123-149` maps success/failure and publishes `exitCode` |
| [x] | Block engine / prompt cards from shell marks | `BlockEngineWire.kt` uses `ShellStateSource` for phase/cwd/editor target; transcript diff fallback retained |
| [x] | Search (viewport matches, next/prev, match count) | `native/src/jni/search.rs:12-156`; `engine/native/NativeSearch.kt` |
| [x] | URL detection | `engine/shell/Scanner.kt`; engine URL API in session |
| [x] | Text selection: word, line, all, drag handles | `native/src/jni/selection.rs:28-241`; `engine/input/Selection.kt` |
| [x] | Pinch-to-zoom (focal anchor, 0.1sp steps, chip, fling gear) | `engine/input/PinchMachine.kt:29-92`; recent commits `f6b47075`, `23d1d547` |
| [x] | Smooth scroll / fling with friction | `engine/input/ScrollMachine.kt:50`; `fix(scroll): a fling ends where friction says…` |
| [x] | Diagnostics eye with numbers + copy button | commit `8add2eba`; `engine/diagnostics/EngineDiagnostics.kt` |
| [x] | Render benchmark log | `docs/RENDER-BENCHMARK.md`; `engine/bench/{BenchmarkLog,RenderBench}.kt` |
| [x] | Termux engine removed from code | `grep '^import com.termux' android/` → none; remaining `termux/` paths are backup-migration code (`data/backup/termux/`) |
| [ ] | Semantic output classification (ERROR/WARN/OK colors) | no classifier class found; palette tokens exist in `core/DroshPalette.kt` |
| [ ] | Ghost-text autocomplete | no matching class in `android/` |
| [ ] | Command DNA / history search | no `CommandDna` type in `android/` |

---

## 3. Features outside the terminal

| | Feature | Evidence |
|---|---|---|
| [x] | 8 agent tools: `shell`, `read_file`, `write_file`, `grep`, `move_file`, `ask_user`, `update_todo`, `web_search` | `android/features/agent/.../tool/impl/*Tool.kt`, `NAME` constants |
| [x] | 4 provider protocols (OpenAI-compatible, Anthropic, Gemini, OpenAI Responses) | `agent/provider/{OpenAiCompat,Anthropic,Gemini,OpenAiResponses}Adapter.kt`; 225-provider catalog (`EffortMapper.kt:19`) |
| [x] | Bounded agent loop, tool registry, streaming, API vault, per-model reasoning effort | `android/features/agent/`, `android/domain/.../agent/Safety.kt:13-28` (PLAN/BUILD/AUTO + AUTO_SAFE) |
| [x] | Diff + approval before write, ask-user cards, todo cards | agent tool implementations + UI cards |
| [x] | Image input, session logs to agent, web-result cards, chat compaction | agent feature tree + recent commits |
| [x] | SSH: hosts, keys, encrypted credential vault, TOFU, sessions bridged to terminal | `android/data/.../ssh/Ssh{Host,Key}RepositoryImpl.kt`, `SshCredentialVaultImpl.kt`, `SshSessionLauncherImpl.kt`, `features/ssh/SshSessionFactoryImpl.kt` |
| [ ] | SSH SFTP / jump hosts / port forwarding / Mosh | not found in the tree |
| [x] | Editor `editor <path>` with syntax highlighting (bundled TextMate grammars) | `android/features/editor/src/main/assets/textmate/*.tmLanguage.json`, `EditorLanguage.kt`, `TextMateSetup.kt`; LGPL sign-off still open |
| [x] | Backup/restore: app data, rootfs archive, Termux→glibc migration importer | `android/data/.../backup/*`, `android/ui/.../BackupRestoreScreen.kt`, tests |
| [x] | Session sidebar, projects/workspaces, split panes, floating/overlay window | `android/ui/.../session/`, `WorkspaceScreen.kt`, `SplitPaneHost.kt`, `pane/PaneLayoutViewModel.kt`, `ui/keyboard/KeyboardWindowMode.kt` |
| [x] | Onboarding + recovery, settings categories, theme store, fonts (Geist/Inter/JetBrains Mono), background images | `SetupFlowScreen.kt`, `SetupRecoveryScreen.kt`, `SettingsHomeScreen.kt`, `ThemeStoreScreen.kt`, `theme/` |
| [x] | 4-digit PIN lock, biometric gating, foreground service, DocumentsProvider, in-app browser | `ui/pin/PinEntryScreen.kt`, `MainActivity.kt`, `DocumentsProvider` (1 file), `WebViewSheet.kt` (2 files) |
| [x] | FlatKeyBar input, modifier state, keyboard bridge, snippets | `ui/input/FlatKeyBar.kt`, `domain/input/StickyModifierState.kt`, `session/SnippetsStore.kt` |
| [x] | 765 Kotlin unit tests + 12 Rust tests; CI green on latest `main` | `grep -rh '@Test' android/` = 765; `grep -rh '#\[test\]' native/src` = 12; `gh run list`: Build Debug ✅, Unit Tests ✅ (2026-11-18) |

---

## 4. i18n — needs a repair pass, not a translation pass only

- **Coverage**: 754 base keys; `tr` 736/754; `de/es/pt/ru/zh/ja/ar/fil` 418/754.
  The 336-key gap is mostly newer app/about/agent/settings surfaces, not a
  single screen. `docs/TRANSLATION-MANIFEST.md:18` still claims 413/413 — stale.
- **Tooling**: `tools/i18n-coverage.sh` runs (slowly, >2 min), `tools/i18n.mjs`
  crashes on `editor/values/strings.xml` (`tools/i18n.mjs:182`), and
  `tools/i18n-merge.mjs` exists for staged batches.
- **Hardcoded UI strings remain**, including Turkish in provider picker
  (`android/ui/.../agent/components/ProviderPicker.kt:185,194`) and English in
  search/rename/PIN screens (`DraggableSearchBar.kt:188`,
  `RenameSessionDialog.kt:81,123`, `PinEntryScreen.kt:181`).
- **CI does not check any of it** — no i18n step in `.github/workflows/`.
- `fr` is listed in a picker but has no `values-fr/` (known, pre-existing).

**Repair order**: fix `i18n.mjs` path → re-run the check and fix escaping →
translate the 336-key gap × 8 + 18 remaining `tr` → regenerate the manifest →
add the coverage+placeholder gate to `tests.yml`.

---

## 5. CI / build / distribution

| | Item | State |
|---|---|---|
| [x] | `debug.yml` — native build + debug APK on push/PR/dispatch; checksum-verified Ghostty fetch; JNI libs staged | `.github/workflows/debug.yml:20-134` |
| [x] | `tests.yml` — module-boundary check, import resolver, JNI symbol parity, all module tests, Rust tests | `.github/workflows/tests.yml:52-98` |
| [x] | `native.yml` — pinned NDK, Ghostty fetch, `libdrosh.so` for ABIs, artifact upload | `.github/workflows/native.yml:38-152` |
| [x] | `release.yml` — tag → versionName, changelog section required, checksum, GitHub Release artifact | `.github/workflows/release.yml:80,105,118-138` |
| [x] | Release is minified/shrunk; debug is not | `android/app/build.gradle.kts:89-104` (`release: true`) |
| [~] | Signing with the committed debug key | documented trade in `release.yml:70-72`; fine for GitHub, not Play |
| [ ] | No publish automation beyond GitHub Release artifact; no F-Droid/Play pipeline | workflows list |
| [ ] | No i18n gate in CI | §4 |
| [ ] | README says `isMinifyEnabled = false` as a known issue | `README.md:229` — stale; release is now `true` |

---

## 6. Marketing / docs done vs missing

**Done:** repo description + 10 topics; CI badges; Keep-a-Changelog file with
an `Unreleased` section; `CONTRIBUTING.md`, `SECURITY.md`, issue/PR templates;
`LICENSE.md` map and `LICENSES/` texts; docs index and architecture/session
reference layer.

**Missing / stale:**

| | Item | Note |
|---|---|---|
| [ ] | Hero screenshot + feature GIFs in README | none; decide the set (terminal, agent, split panes, SSH, editor, sidebar; GIF: pinch-zoom, agent streaming) |
| [ ] | F-Droid metadata / Play listing / privacy policy | channel decision first |
| [ ] | Release notes for the first real version | `CHANGELOG.md` has only `[Unreleased]`; tag workflow requires `[vX.Y.Z]` |
| [~] | README device-verification claim | §1 — needs the corrected sentence |
| [~] | README minify claim | §5 |
| [~] | `CREDITS.md:8` credits the Termux engine | engine is Ghostty + ours now |
| [~] | `LICENSE.md:30` points at `native/ghostty/SHA256SUMS` | that directory is created by the fetch script, not committed |
| [~] | `LICENSES/` has no MIT license text for Ghostty | add before distribution |
| [~] | `docs/TRANSLATION-MANIFEST.md:18` says 413/413 | actual base is 754 |
| [~] | Root `LICENSE` (GPL-3.0) vs `LICENSE.md` (Apache-2.0 / open choice) | owner decision, §1 |

---

## 7. Document trust table

| Document | Trust | Why |
|---|---|---|
| `docs/ARCHITECTURE.md` | high | matches the Ghostty/Rust/OpenGL code and enforces module boundaries in CI |
| `docs/SESSION-SYSTEM.md` | high | session invariants referenced from code |
| `CHANGELOG.md` | high for shipped work | only `[Unreleased]` — no released version yet |
| `README.md` | medium | engine/stack current; device claim and minify claim stale; no visuals |
| `LICENSE.md` | medium | map is current in spirit; `native/ghostty/` path is not; license choice open |
| `CREDITS.md` | low | still describes the pre-Ghostty Termux engine |
| `docs/TRANSLATION-MANIFEST.md` | low | 413/413 is three generations behind 754 keys |

Safer plan for release day: fix blockers in §1 in this order —
**app id → license/channel decision → i18n tooling+coverage+CI gate →
CHANGELOG version section → README/CREDITS corrections → marketing assets →
tag**. CI builds are green today, so none of this requires new engine work.
