# Drosh — Status & Roadmap

_Snapshot: 2026-11-22. Base: GitHub `main` @ `06ff29fe`; this file also records
the release-cleanup work on branch `docs/release-status`. Code is the reference;
where a document disagrees with it, the document is a bug._

Legend: `[x]` done · `[~]` partial / in progress / needs a decision · `[ ]` absent.

---

## 1. Release readiness — what is fixed, what is still open

**Fixed in this cleanup pass**

| | Item | Evidence |
|---|---|---|
| [x] | Production identity restored to `dev.drosh` | `android/app/build.gradle.kts` (`applicationId`), `AndroidManifest.xml`, `CommandStateProvider.AUTHORITY`, `KeyboardWindowMode.PERMISSION_SEND_KEYBOARD_MODE`, `tools/bench-render.sh` |
| [x] | Drosh is Apache-2.0 everywhere it is ours | root `LICENSE` = Apache-2.0; `LICENSE.md` rewritten; `LICENSES/MIT-Ghostty.txt` added; `CONTRIBUTING.md`, `README.md`, `docs/AGENT.md`, `android/features/editor/NOTICE.md` updated |
| [x] | No Termux/PRoot as active dependencies in the docs | `CREDITS.md`, `LICENSE.md`, `README.md`, `SECURITY.md`, `docs/ARCHITECTURE.md`, `docs/SESSION-SYSTEM.md`, `docs/AGENT.md` — remaining mentions are historical (`CHANGELOG.md`, `docs/archive/`) or explicit "we no longer use" statements |
| [x] | Dead i18n tooling removed | `tools/i18n-{coverage,mjs,merge}` and `docs/TRANSLATION-*` deleted; references in `README.md` and `.github/PULL_REQUEST_TEMPLATE.md` cleaned |
| [x] | README corrected | device verification: everything verified on device; release minify is ON; 765 tests; no false exit-code bug; short what-is/what-isn't table added |
| [x] | Historical memory-bank/TODO docs absent in this tree | `docs/MEMORYBANK.md` and `docs/TODO.md` do not exist on current `main` |

**Still open before a tag**

| | Item | Why |
|---|---|---|
| [ ] | **CHANGELOG needs a version section** | `.github/workflows/release.yml:118-127` requires `## [vX.Y.Z]`; only `[Unreleased]` exists today |
| [~] | **Translations incomplete** | 754 base keys; `tr` 736/754, eight other locales 418/754 (336 missing). The old tooling was deleted by decision; the gap stays in the resource files until translated by hand |
| [ ] | **No launch assets** | no hero screenshot or GIF in the repo; repo description and 10 topics are set |
| [ ] | **No distribution metadata** | no F-Droid metadata, Play listing or privacy policy |
| [~] | **Channel decision** | GitHub/F-Droid works with debug-key signing and `targetSdk` 28; Play would need a real key, targetSdk 34+, privacy policy and data-safety form |

---

## 2. Terminal — Ghostty VT + Rust + OpenGL

Engine: **Ghostty v1.4.0 `libghostty-vt`** (MIT, C ABI), fetched from
`ancientcatz/libghostty-android` and verified against the release's
`SHA256SUMS` (`tools/fetch-ghostty-vt.sh:23-25,70-78`).
Pixels: our OpenGL ES 3.0 renderer (`engine/render/EglCore.kt:83-87`,
`FrameRenderer.kt:1020-1060`, `#version 300 es` shaders).
Rust half: `native/` (`libdrosh.so`); Kotlin half: `android/terminal/.../engine/`.

| | Feature | Evidence |
|---|---|---|
| [x] | Ghostty VT v1.4.0, checksum-verified fetch; not committed | fetch script above; `native.yml:106-128` |
| [x] | JNI boundary: PTY, session, input, search, selection | `native/src/jni/{pty,session,input,search,selection}.rs`; parity gate `tests.yml:67-68` |
| [x] | PTY spawn/read/write/resize/try-wait/pid | `native/src/jni/pty.rs:24-209` |
| [x] | Grid, scrollback, modes, damage owned by the engine | `native/src/renderer/{frame,row,cell}.rs` |
| [x] | Wide chars (CJK/emoji) use engine cell widths | `CHANGELOG.md` Added |
| [x] | Instanced GL cells/glyphs, R8 atlas, cursor, blink | `FrameRenderer.kt:187,1020-1060`; `GlTexture.kt:113` |
| [x] | Italic / bold / strikethrough / overline / underline | recent commits `f190cf30`, `4a652f7f` |
| [x] | Kitty graphics + background images | `native/src/png.rs`, `renderer/image.rs:3-27` |
| [x] | Mouse reporting, bell, OSC 52 clipboard (opt-in) | `jni/input.rs:287,353`; `bell.rs:48`; `clipboard.rs:81,180-238` |
| [~] | Kitty keyboard protocol | flags in `ghostty.rs:164-165`, `jni/session.rs:429`; full behavior unverified |
| [x] | OSC 133 prompt/command/**exit status**, OSC 7 cwd, OSC 1339 editor | `native/src/shell.rs:3-13`; `DroshShellIntegration.kt:159-194` |
| [x] | Real exit codes in block mode (old hardcoded-0 bug gone) | `domain/block/ShellState.kt:18` ← OSC 133 `D`; `CommandStatePublisher.kt:123-149` |
| [x] | Block engine, prompt cards, per-block menu | `BlockEventBridge.kt` + `ShellStateSource` |
| [x] | Search (viewport matches, next/prev, count), URL detection | `jni/search.rs:12-156`; `engine/shell/Scanner.kt` |
| [x] | Selection: word, line, all, drag handles | `jni/selection.rs:28-241` |
| [x] | Pinch-to-zoom (0.1sp, focal anchor, chip, double-tap reset) | `engine/input/PinchMachine.kt:29-92` |
| [x] | Sub-line scroll with friction-based fling | `engine/input/ScrollMachine.kt:50` |
| [x] | Diagnostics eye + render benchmark log | `EngineDiagnostics.kt`, `engine/bench/`, `docs/RENDER-BENCHMARK.md` |
| [ ] | **Ligatures at runtime** | `harfrust 0.16.0` + 5 tests in `native/src/font/mod.rs:3-45,330-365`, but **no JNI export and no caller**: all `Java_dev_drosh_*` exports are in `jni/` (none for font), and `engine/font/TextAtlas.kt:15-20` says the atlas switched from the Rust shaper to Android `Paint`. The `CHANGELOG` "ligatures" entry above describes an intent that is not in the current code |
| [ ] | Semantic output colouring (ERROR/WARN/OK) | no classifier class; palette tokens only |
| [ ] | Ghost-text autocomplete, Command DNA/history search | no such types in `android/` |
| [x] | Old terminal engine code gone | `grep '^import com.termux' android/` → none; only backup-migration code under `data/backup/termux/` |

---

## 3. The rest of the app

| | Feature | Evidence |
|---|---|---|
| [x] | 8 agent tools: `shell`, `read_file`, `write_file`, `grep`, `move_file`, `ask_user`, `update_todo`, `web_search` | `features/agent/.../tool/impl/*Tool.kt` |
| [x] | 4 provider protocols, 225-provider catalog, per-model effort, encrypted keys | `agent/provider/*Adapter.kt`, `EffortMapper.kt:19`, `Safety.kt:13-28` (PLAN/BUILD/AUTO) |
| [x] | Bounded loop, streaming, diff+approval, ask cards, todos, image input, session logs, compaction | `features/agent/`, `ui/agent/` |
| [x] | SSH hosts/keys/vault/TOFU, bridged sessions; editor with TextMate grammars | `data/ssh/*`, `features/ssh/SshjSessionFactory.kt`, `features/editor/src/main/assets/textmate/` |
| [x] | Backup/restore incl. rootfs archive and Termux→chroot migration | `data/backup/*`, `ui/.../BackupRestoreScreen.kt` |
| [x] | Sessions/projects, split panes, floating overlay, theme store, fonts, backgrounds, PIN/biometric, service, DocumentsProvider, in-app browser, onboarding/recovery | `ui/` screens, `SplitPaneHost.kt`, `PinEntryScreen.kt` |
| [x] | 765 Kotlin tests + 12 Rust tests; CI green on `main` | `grep @Test` / `grep '#[test]'`; `gh run list` 2026-11-18 |
| [ ] | SSH SFTP/jump hosts/port forwarding/Mosh | not in the tree |

---

## 4. Roadmap — ne var, ne yok, ne devam ediyor

| Durum | Alan |
|---|---|
| ✅ **Var** | Ghostty VT + Rust core + OpenGL renderer; blok modu ve gerçek exit code; pinch-zoom, fling, selection, search, URL, mouse, kitty graphics; 8 agent tool + 4 protokol; SSH vault; editör + syntax highlighting; yedekleme/restore; split pane + floating window; tema store; PIN; 10 dilde kaynaklar |
| 🔄 **Devam ediyor** | Çeviri açığı (8 dilde 336 anahtar); ligature (shaper hazır, renderer bağlantısı yok); `mmuhofy/chroot-ng` fork'una yerel patch planı; dağıtım kanalı kararı |
| ⚠️ **Sorunlu / karar bekliyor** | `targetSdk` 28; debug anahtarıyla imza; ilk sürüm notları (CHANGELOG'da sürüm bölümü yok); README'de görsel yok; Play için privacy policy/data-safety; F-Droid metadata |
| ❌ **Yok** | Ghost text, semantic output colouring, Command DNA, SSH SFTP/jump host/port forwarding, kendi imzalı release anahtarımız, F-Droid/Play varlığı, i18n doğrulama aracı (bilerek silindi) |

---

## 5. CI ve dağıtım

| | Item | State |
|---|---|---|
| [x] | `debug.yml`, `tests.yml`, `native.yml`, `release.yml` | push/PR/tag/dispatch; native `.so` build + Ghostty fetch; JNI parity; module boundaries; checksum + GitHub Release artifact |
| [x] | Minify/shrink | release açık, debug kapalı (`app/build.gradle.kts:103-104`) |
| [~] | Debug-key signing | GitHub/F-Droid için yeterli; Play için değil |
| [ ] | i18n CI gate | araçlar silindi; yerine kural: yeni string tüm locale'lere eklenir (PR template) |

---

## 6. Sürüm planı — hangi commit, hangi sürüm?

Commit başına sürüm **yok**. `v0.1.0` (`8f5b9330`, 2026-09-09) Ghostty öncesi
kodun etiketi ve aradan **1349 commit** geçti — bu yüzden eski etiketi
taşımak ya da ona ekleme yapmak yanlış olur.

Öneri: **tek bir etiket**, temizlik işi bittikten sonra:

1. Bu dalda kalan maddeleri bitir (CHANGELOG sürüm bölümü, kanal kararı,
   istenirse bir tur daha cihaz/görsel çalışması).
2. `CHANGELOG.md`'de `## [0.2.0]` bölümünü `[Unreleased]` içeriğinden oluştur.
3. `v0.2.0` etiketini **bir kez** at → `release.yml` sürüm adını tag'den alır,
   notları CHANGELOG'dan okur, checksum + artifact üretir.
4. Sonraki işler yine `[Unreleased]` altında birikir; bir sonraki etiket
   `v0.3.0` (yeni özellik) ya da `v0.2.1` (sadece düzeltme) olur.

`v1.0.0` için ölçüt önerisi: dağıtım kanalı netleşip ilk gerçek sürüm
yayınlandıktan ve geri bildirim turu geçtikten sonra — şu an "public release"
daha önce gelmeli.

---

## 7. Doküman güveni

| Doküman | Güven | Not |
|---|---|---|
| `docs/ARCHITECTURE.md`, `docs/SESSION-SYSTEM.md` | yüksek | kodla uyumlu, CI'da doğrulanan kurallar |
| `CHANGELOG.md` | yüksek (geçmiş) / dikkat (şimdi) | geçmiş girdiler tarihsel; `Known issues` ve ligature girdisi güncel kodla çelişiyordu, düzeltildi |
| `README.md`, `CONTRIBUTING.md`, `docs/AGENT.md` | yüksek | bu turda güncellendi |
| `LICENSE.md`, `CREDITS.md` | yüksek | Apache-2.0 + upstream atıfları güncel |
| `docs/archive/*` | düşük (tarihsel) | eski tasarım notları; referans alınmaz |
