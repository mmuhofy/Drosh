# Changelog

All notable changes to Drosh are documented in this file. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and versions follow
[Semantic Versioning](https://semver.org/).

## [Unreleased]

Everything since the `v0.1.0` tag, collected. This becomes the first versioned
release when it ships.

### Added

**Build — architecture guardrails**

- `docs/ARCHITECTURE.md`: the module graph, the enforced layer rules, the
  naming contract, the Rust/Kotlin boundary and the migration phases, in one
  normative place
- `checkModuleBoundaries` fails `check` when a module imports a package its
  layer forbids; the two known inversions (`data → terminal`, `ssh → terminal`)
  carry a ratcheted baseline so the count can only go down

**Agent — the shell that talks back**

- Four provider protocols: OpenAI-compatible (OpenRouter and friends),
  Anthropic Messages, Gemini, and OpenAI Responses — choose per model
- A 225-provider catalog from models.dev, fetched once and cached, with
  per-model reasoning effort that persists
- Eight tools the model can call: `shell`, `read_file`, `write_file`, `grep`,
  `move_file`, `ask_user`, `update_todo` and `web_search`
- Live shell output streams into the chat as the command runs — clipped, never
  silently
- `write_file` shows a diff and waits for your approval before touching a file
- A bounded multi-step loop with retries, approval parking, and
  repetitive-loop detection
- Durable chat history that survives a restart, with automatic compaction of
  old turns, and per-chat working directories
- Assistant messages render as markdown with syntax-highlighted code blocks
- API keys live in an encrypted, per-provider vault

**SSH — the network is just another session**

- Hosts and keys managed in-app, with a trust-on-first-use host key verifier
- SSHJ sessions bridged straight into the real terminal: an SSH connection is a
  tab, not a bolt-on
- Credentials in the encrypted vault, with connection keep-alive

**Editor**

- `editor <path>` opens a real code editor inside the app (`dedit` does the
  same from the shell)
- Syntax highlighting through bundled TextMate grammars, with Drosh's own
  colour scheme
- Discard-changes confirmation when leaving a dirty document

**Workspace**

- Projects group sessions under a name, colour and note; archiving moves
  sessions out of the way without losing them
- Create, assign, archive and restore from the sidebar or Settings
- The Projects screen filters projects and the sessions inside them, and a
  failed write is reported with the action that failed rather than swallowed

**Split panes**

- Drop a session onto another to split; the divider snaps to five positions
- A floating window you can drag over any app, resize from a corner and dock

**Terminal**

- Smooth pinch-to-zoom: continuous 0.1sp, anchored on the row under your
  fingers, with a size chip; double-tap returns to the default size
- Sub-line pixel scrolling with momentum, in both directions
- OSC 133 shell integration, installed per shell — no more file polling
- Space-bar drag moves the text cursor, and IME composing regions are respected
- Every URL detected and highlighted, wrapped segments included; a tap opens
  the in-app browser
- Two chrome states: fullscreen up in the scrollback, normal at the live edge
- Ambient tint derived from the screen's own colours, and an interactive MOTD
  with three display modes

**Input**

- FlatKeyBar with backdrop blur, grouped keys, sticky modifiers and key repeat
- The bar hides itself while Drosh Keyboard is the active IME
- Command state and snippets shared with the keyboard through a provider

**Settings & appearance**

- Settings rebuilt by category, with `settings.toml` in the rootfs as the
  single source of truth
- Selectable font packs — Geist and Inter, with real Medium and SemiBold
  weights instead of synthetic ones
- A theme store with built-in and installed themes, plus background image
  behaviours
- Light theme on measured neutral surfaces; device identity from Wikidata
- 4-digit PIN app lock backed by EncryptedSharedPreferences
- Eleven languages with instant switching, no restart

**Sessions**

- Ended sessions are marked and can be cleared; no more silent replacement
- The sidebar is a flat list with rename, recency ordering and search

**Links**

- The in-app browser gained navigation, a progress bar, a URL dropdown and a
  half-expanded default

### Changed

- The engine and the sessions are one module now. `:terminal` holds the Rust
  core's Kotlin side and the sessions that drive it, in two packages
  (`dev.drosh.terminal.engine` and `dev.drosh.terminal.session`), and the rule
  that kept them apart — the engine does not know about sessions — moved from a
  module boundary to a package one, so the checker still enforces it
- The product's optional surfaces moved into a `features` group: the agent, the
  SSH transport and the editor are now `:features:agent`, `:features:ssh` and
  `:features:editor`. They are things a user reaches, not steps in the dependency
  chain, and the module list mixed them with the layers
- The design system module is now `:theme` and holds what a theme is — colours
  and typography. The dropdown component moved to `:ui`, where the screens that
  use it live, and the package moved with it (`dev.drosh.design.system` →
  `dev.drosh.theme`)
- The Linux guest is its own module. The rootfs, the bootstrap, the rootfs
  archive, the guest launchers and the guest's own assets moved out of
  `:terminal` into `:guest`, which knows nothing about sessions, panes or the
  UI — and the `data → terminal` debt fell from 22 to 13 as a side effect
- The guest has two backends behind one interface: proot, and chroot-ng. The
  chroot-ng path is wired — the argv its CLI expects, the environment it
  builds for the guest, and the device paths it binds — and can be switched on
  a device without a UI
- The guest runtime, chroot-ng, is no longer vendored in this repository. It is
  built and published by its own project, and Drosh consumes a pinned release
  asset — a tag and a SHA-256 it verifies before the file goes into the APK.
  That takes 68k lines of C out of the tree, and the C toolchain out of CI
- The repository is split by build system: the Gradle build — modules, wrapper,
  convention plugins, keystore — lives under `android/`, the Rust engine
  workspace under `rust/`, and the vendored chroot-ng under `third_party/`.
  Module names, Kotlin packages and the cdylib name are unchanged, so nothing
  about the app itself moved with them
- The Rust engine is five crates instead of one: `drosh-core` (the JNI
  boundary), `drosh-session`, `drosh-pty`, `drosh-bridge` and `drosh-font`. The
  library the app loads is still `libdrosh_core.so` with the same symbols
- The Projects screen is one flat list in the settings language — projects,
  their sessions, the sessions in no project and the archive as rows of the
  same run — with loading, first-run, unfiled and no-results states, and the
  working directory moved to the agent sheet where the session is made
- Split panes run top-to-bottom, not side by side — two 180dp columns were
  about ten characters wide
- Zoom limits are shared between the pinch gesture and the Settings slider, so
  a size set one way can be reproduced the other

### Fixed

- The module boundary checker no longer passes silently when a rule names a
  module that is not in the tree. `:guest`'s five rules were decorative until
  the module exists; the checker now says which module is missing, and fails
  when a planned module shows up without its rules being moved over
- Crashes: the stale Room identity hash on launch, the missing v3 migration
  path, an infinite redraw loop on heavy output, the URL overlay's row range,
  the blank WebView screen, R8 stripping Hilt bindings, and the splash and
  process-exit dialog loops
- Block mode no longer produces one block per character, and classic-mode ESC
  parameter bytes are stripped
- The top bar's blur finally spreads over the content, and the glass edge is
  visible again
- Session lifecycle: no zero-session state while the app runs, a relaunch
  creates a session again, the last session closes for real

### Known issues

- Block mode reports exit code 0 for every command — `BlockEngineWire`
  hardcodes it, so a failing command renders as successful
- Block mode does not pinch-zoom; it renders in Compose with no `TerminalView`
  behind it
- Agent chats run in the guest home directory; per-chat terminals are not
  wired yet
- Command DNA (history search), ghost-text autocomplete and semantic output
  classification are not built
- `targetSdk` is deliberately 28 on a compileSdk 36 project; release builds are
  signed with the committed debug key and run without R8
- Nothing has been verified on a physical device yet

[Unreleased]: https://github.com/mmuhofy/Drosh/compare/v0.1.0...HEAD
