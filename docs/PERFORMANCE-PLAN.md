# Drosh — Performance & Optimization Plan

> **Status: PLAN ONLY.** No code changes have been made based on this document.
> Implementation is not started. Every "today" claim below is cited as
> `path:line` against commit `885ae39e` unless marked `[verified]`, which means
> it was read directly from the working tree during planning.
>
> Section 1 is a source-verified survey of how the reference terminals solve
> the same problems, produced by cloning and reading their code (section 7
> says how to reproduce it). Sections 2–6 turn that into an ordered plan for
> Drosh.

---

## 0. Reading guide — the three baselines

When this plan says "Drosh vs Termux vs desktop", it means:

| Baseline | Meaning |
|---|---|
| **Termux** | the upstream engine we vendor: `github.com/termux/termux-app` (`terminal-emulator`, `terminal-view`) driving `libtermux.so` |
| **Drosh** | our app as it is today: that engine, plus the Compose UI, block engine, agent, and every effect added on top |
| **Desktop** | kitty, WezTerm, Ghostty, Alacritty (Windows Terminal's Atlas engine is cited from its public design posts; st/urxvt/iTerm2 from general knowledge, marked) |

The important structural fact: **the engine is shared, so every Drosh-specific
problem below is in the "on top" layer.** That is where the wins are, and it is
why the desktop comparison matters — the desktop terminals do almost nothing
per emitted chunk, and we do a lot.

---

## 1. Reference terminals — what each one actually does (source-verified)

All file references in this section are to shallow clones read during planning
(`/tmp/opencode/perf-refs/*`, `git clone --depth 1`).

### 1.1 termux-app — the upstream engine

| Concern | Technique | Evidence |
|---|---|---|
| PTY read | 4096-byte read from a `FileInputStream` on a dedicated `TermSessionInputReader` thread, into a 64 KiB `ByteQueue` | `terminal-emulator/.../TerminalSession.java:133-148`, `:44` |
| Main-thread wake | `sendEmptyMessage(MSG_NEW_INPUT)` **on every read** — upstream does not coalesce | `TerminalSession.java:142` |
| Parse | on the **main thread**, inside the handler, queue drained in one message | handler in `TerminalSession.java` |
| Threads per session | 3: input reader, output writer, waiter | `TerminalSession.java:133`, `:150`, `:166` |
| Screen buffer | `TerminalRow` = `char[]` with `1.5×` spare + `long[]` styles, allocated lazily; ring buffer over transcript+screen rows | `TerminalRow.java:12`, `:58` |
| Scrollback | `DEFAULT_TERMINAL_TRANSCRIPT_ROWS = 2000` | `TerminalEmulator.java:230` |
| Text rendering | one `canvas.drawTextRun` per equal-style run; ASCII widths measured once per renderer (127 `measureText` calls at construction) | `TerminalRenderer.java:51-56`, `:260` |
| Glyph cache | widths only — no bitmap atlas; HWUI draws the glyphs each frame | `TerminalRenderer.java:29`, `:51` |
| Cursor blink | View-level blinker with a rate setter (100–2000 ms) | `TerminalView.java:62-67`, `:1215` |
| Extra | Sixel/bitmap image support exists upstream (`TerminalSixel.java`, `TerminalBitmap.java`) | module file list |

**What we already do better than upstream:** the empty→non-empty wake policy
(`terminal/.../TerminalSession.kt:189-200`) — upstream posts a message for every
4 KB read, we post one per transition. **What we do identically (and it is the
ceiling):** parse on the main thread, full redraw per frame, no glyph atlas.
Android's View model does not get "leave the unchanged rows alone" for free —
our own `terminal/.../TerminalBuffer.kt:514-527` documents that damage tracking
was tried and removed, because the display list is rebuilt from `onDraw` output
each frame anyway. The transferable lesson from desktop is therefore **not**
"add damage tracking to the Canvas path" — it is "cut how often frames happen,
and do no non-render work per chunk".

### 1.2 kitty — the latency-first reference

| Concern | Technique | Evidence |
|---|---|---|
| Architecture | child-programs IO in a **thread separate from rendering**; parser uses vector CPU instructions; updates to the screen typically require sending only a few bytes to the GPU | `docs/performance.rst:4-13` |
| IO thread | `child-monitor.c` creates `io_thread` running `io_loop`, parked on a poll set with a wakeup fd | `kitty/child-monitor.c:180`, `:244`, `:312` |
| Parser | `parse_worker` in `vt-parser.c`, invoked as the child's `parse_func` from the monitor; screen access cross-thread is mutex-guarded | `kitty/vt-parser.c:1684`, `kitty/child-monitor.c:196-197`, `screen_mutex` usage in `child-monitor.c:380-406` |
| Render condition | screen-level `is_dirty` flag; re-render when `reload_all_gpu_data \|\| scroll_changed \|\| is_dirty \|\| screen_resized \|\| (disable_ligatures && cursor_pos_changed)` | `kitty/shaders.c:1153`, `kitty/screen.c` (many `is_dirty = true` sites) |
| Glyph cache | each rendered glyph cached **in video RAM** — font rendering is explicitly stated as "not a bottleneck" | `docs/performance.rst:6-7` |
| Frame pacing | user-tunable artificial render delay (`repaint_delay`) and input delay (`input_delay`); "these control the artificial delays introduced into the render loop to reduce CPU usage" | `kitty/state.h:81`, `kitty/child-monitor.c:1137-1159`, `docs/performance.rst:15-20` |
| vsync trade | `sync_to_monitor` no → less latency, some tearing | `docs/performance.rst:18-20`, `state.h:126` |
| Idle cost | main loop is a tick callback over an event wait; nothing runs when nothing happens | `kitty/glfw.c:3611` `run_main_loop(tick_callback_fun cb, …)` |

kitty also publishes its measurement methodology and results (see §1.6), and a
profiling recipe (`docs/performance.rst:142-156`).

### 1.3 WezTerm — the process-split reference

| Concern | Technique | Evidence |
|---|---|---|
| Architecture | terminal state lives in a **separate mux-server process**; each GUI window is its own process rendering it | crates `wezterm-mux-server-impl`, `wezterm-mux-server`, `wezterm-gui`, `window` in the workspace |
| Rendering | wgpu front end; whole-window `invalidate()` (no tile damage rects) with batched quad drawing | `wezterm-gui/src/termwindow/render/paint.rs`, `screen_line.rs`, `quad.rs`; `window/src/lib.rs` invalidate path used from `termwindow/*` |
| Glyph cache | dedicated `glyphcache.rs`; lookup is designed to be **allocation-free** via a borrowed cache key | `wezterm-gui/src/glyphcache.rs:85-89` |
| Shape cache | shaped glyph runs cached in `shapecache.rs`, again with a borrowed key to avoid allocation on cache hits | `wezterm-gui/src/shapecache.rs:56-60` |
| Consequence | the GUI never parses; parsing cost sits in another process on its own schedule | crate structure above |

WezTerm shows the extreme of "get the work off the frame path". The mobile
transferable part is the **cache design** (borrowed keys, no allocation on hit)
and the discipline of never rendering from freshly-shaped state — not the
process split itself.

### 1.4 Ghostty — the modern-IO reference

| Concern | Technique | Evidence |
|---|---|---|
| Async IO | everything on **libxev** loops, including the renderer | `src/global.zig:30` (`pub const xev = @import("xev").Dynamic`), `src/renderer/Thread.zig:124` (`xev.Loop.init`), `src/cli/version.zig:48` |
| Render thread | a dedicated renderer **thread** with a mailbox: main thread posts, render thread `drainMailbox()` under `wakeup.notify()` | `src/renderer/Thread.zig:192`, `:237`, `:290`, `:489` |
| Parser | SIMD UTF-8 decoding until control sequence, C++ Fast Util (simdutf/Highway) with Zig intrinsic fallback | `src/simd/vt.zig:7-27`, `src/simd/vt.cpp:1-7` |
| Terminal state | page-list model (`terminal/PageList.zig`) with a serializable `terminal/snapshot/` (kaitai schema) for handing state across threads/processes | `src/terminal/snapshot/snapshot.ksy`, `grid.zig`, `screen.zig` |
| GPU | Metal / OpenGL backends with a generic renderer core | `src/renderer/Metal.zig`, `OpenGL.zig`, `backend.zig` |

Ghostty's message-passing render thread is the cleanest statement of the
architecture our Phase 1B targets: state thread produces, render thread
consumes, they share nothing but a queue.

### 1.5 Alacritty — the damage-tracking reference

| Concern | Technique | Evidence |
|---|---|---|
| Damage tracking | the terminal crate yields **per-line damage bounds**; the display keeps a `DamageTracker` with two double-buffered frames, and asks the terminal for a `TermDamageIterator` → `Vec<Rect>` | `alacritty/src/display/damage.rs:8`, `:16`, `:25`, `:52`, `:97-101` |
| Forced full damage | explicit `mark_fully_damaged()` for resize/bell/search-exit cases | `alacritty/src/event.rs:979`, `:1421`, `:1612` |
| Debug affordance | `debug.highlight_damage` config option to visualize damage | `alacritty/src/config/debug.rs:19-20` |
| Glyph atlas | glyph textures in an `Atlas` (multiple atlasses, `ATLAS_SIZE`), populated by the text renderer | `alacritty/src/renderer/text/atlas.rs`, `text/gles2.rs:16-34` |
| Event loop | single-threaded (mio) event loop — it does not need a parse thread because the terminal state update *is* cheap and damage-gated | `alacritty/src/event.rs`, `scheduler.rs` |
| Caveat | even Alacritty can be hit by an OS redraw that "bypasses frame throttling" | `alacritty/src/window_context.rs:380` |

Alacritty is the proof that damage tracking is a *renderer* concern, and that
the win comes from the terminal state being queryable for "what changed" — our
block engine tries to reconstruct that after the fact from a whole-transcript
string diff, which is exactly the expensive way to obtain it.

### 1.6 Published, comparable numbers (kitty, 2023)

kitty's own `docs/performance.rst` measures three axes — energy (CPU),
keyboard-to-screen latency, and throughput — across terminals on the same
machine. Treat as directional: single author, 2023 hardware, kitty's choice of
scenarios.

Throughput (`kitty __benchmark__`, MB/s of data parsed):

| Terminal | ASCII | Unicode | CSI | Images | Average |
|---|---|---|---|---|---|
| kitty 0.33 | 121.8 | 105.0 | 59.8 | 251.6 | **134.55** |
| xterm 389 | 47.7 | 18.3 | 0.6 | 56.3 | 30.72 |
| alacritty 0.13.1 | 43.1 | 46.5 | 32.5 | 94.1 | 54.05 |
| wezterm 20230712 | 16.4 | 26.0 | 11.1 | 140.5 | 48.5 |
| gnome-terminal | 33.4 | 55.0 | 16.1 | 142.8 | 61.83 |
| konsole | 25.2 | 37.7 | 23.6 | 23.4 | 27.48 |
| alacritty+tmux | 30.3 | 7.8 | 14.7 | 46.1 | 24.73 |

CPU while continuously scrolling a file in `less` (terminal + X):

| Terminal | CPU |
|---|---|
| kitty | 6–8% |
| xterm | 5–7% (but "extremely janky") |
| urxvt | 12–14% |
| gnome-terminal | 15–17% |
| konsole | 29–31% |

Latency: kitty cites Typometer-based measurements and a hardware latency tester
(`thume.ca`) putting kitty and Terminal.app at the top, with defaults; minimum
latency is obtained with `input_delay 0`, `repaint_delay 2`, `sync_to_monitor
no`, `wayland_enable_ime no` (`docs/performance.rst:37-49`). The relevant
transferable fact for Drosh: **the default posture is "render as soon as
possible, but never poll", and every knob is about frames, not timers.**

(For context, not source-verified here: Windows Terminal's Atlas engine
documents the same three ideas — glyph atlas, tiled double buffering with
damage, and input→present latency measurement; `st`/`urxvt` are CPU-rendered X
terminals whose cost is tiny precisely because they do almost nothing per
frame; iTerm2 moved to a Metal renderer for the same reason Ghostty/Alacritty
did.)

### 1.7 Cross-reference matrix — "for which problem, who uses which method"

| Problem | termux-app | kitty | WezTerm | Ghostty | Alacritty | **Drosh today** |
|---|---|---|---|---|---|---|
| PTY read chunk | 4 KB read → 64 KiB queue | async poll loop | mux process, async | libxev loop | 4 KB read → 64 KiB queue | **same as termux** (`TerminalSession.kt:72`, `:185`) |
| Parse off the frame path | ❌ main thread | ✅ io thread | ✅ other process | ✅ terminal thread | ✅ cheap+damage-gated | ❌ main thread |
| Redraw frequency | per chunk (`invalidate`) | `repaint_delay`-paced, `is_dirty`-gated | per event | render thread mailbox | damage-gated | ❌ per chunk, no coalescing |
| Work per chunk beyond render | nothing | nothing | nothing | nothing | nothing | ❌ transcript serialize + 3 regex + anchor scan + URL scan + tint scan + list scans (see §2) |
| Glyph/text caching | widths once per renderer | VRAM glyph cache | glyphcache + shapecache, alloc-free | render thread cache | glyph atlas | ⚠️ widths, but pinch path re-measures per event (`[verified]`) |
| Idle timers | blink only | none | none | none | none | ❌ 10+ (500 ms polls ×4, network ticker, 500 ms + 90 ms capture loops) |
| Scrollback memory | 2000-row ring | pages | mux pages | PageList | rendered lines | 3000 rows + 200 blocks in RAM, per session |
| Startup cost | process spawn | instant | server+window spawn | fast Zig | fast | ❌ runBlocking store read + 5.3 MB catalog in DI graph + 1.5 s fixed splash |
| Latency knobs | none | `input_delay`, `repaint_delay`, `sync_to_monitor` | — | — | cursor throttle | none |

### 1.8 What we take, what we don't

| Technique | Decision | Why |
|---|---|---|
| Frame coalescing (render at most once per frame; WT/kitty `repaint_delay` mindset) | **Adopt** (Phase 1A) | Biggest, safest win; Android's display-list rebuild makes frequency the lever |
| Zero non-render work per chunk (Alacritty/kitty posture) | **Adopt** (Phases 1C, 4) | Kills the transcript poller and the overlay URL scan |
| Event-driven state instead of 500 ms polls | **Adopt** (Phase 2, 5) | Direct battery + recomposition win |
| In-place glyph re-measure on size change (kitty-style) | **Adopt** (Phase 3) | docs claim it exists; it doesn't `[verified]` |
| Damage rects for the Compose block path | **Adopt via Compose idioms** (Phase 2) | LazyColumn keys + stable data *is* the Android damage tracking |
| Damage rects for the classic `TerminalView` Canvas path | **Defer, with a note** | Our own `TerminalBuffer.kt:514-527` records it was removed after trial; HWUI redraws the view's display list regardless. Frequency, not intra-frame work, is the lever there |
| Separate parse thread (kitty io thread / Ghostty render thread) | **Defer to Phase 1B** | Correctness risk: the vendored emulator is single-thread-safe by convention, and the renderer reads rows from the main thread. Only after 1A proves insufficient |
| Separate mux process (WezTerm) | **Don't** | Process cost and battery on mobile; states must live in one process for the overlay/service |
| GPU glyph atlas (Alacritty/kitty VRAM cache) | **Don't for now** | Compose + HWUI already rasterizes text on the GPU; a custom atlas would be a rewrite of the renderer for ~50 rows |
| SIMD parsing (kitty/Ghostty) | **Don't** | Kotlin has no intrinsics story of this kind; the emulator is already allocation-free per byte except `Character.getType`/`WcWidth` |
| `input_delay`/`repaint_delay` knobs | **Adopt as one internal knob** | Phase 1A needs a tunable batch size; expose it as a hidden debug setting, not a user option |

---

## 2. Drosh today — ranked inventory (from full code-path trace)

Commit `885ae39e`. Two independent traces were produced (render path; data/agent/async path); this is their merge.

### P0 — main-thread cost per PTY chunk (classic mode pays for block mode too)

1. **`BlockEngineWire` transcript polling** — every `onSessionTextChanged` call serializes the entire transcript (`getTranscriptTextWithoutJoinedLines`, up to 3000 rows), runs `AnsiStripper` (3 full-string regex passes), searches a rolling anchor (up to 8176 substring allocations + `lastIndexOf` over the whole transcript), then runs prompt regexes. It runs **whenever a session updates text, in both UI modes** — `useBlockEngine` is not consulted at the call site (`terminal/.../BlockEngineWire.kt:91-209`, `:254-264`; `terminal/.../TerminalManager.kt:682-692`).
2. **`SearchHighlightOverlay`** — invalidated per chunk; its `onDraw` allocates a `String` per visible row and runs `UrlDetector.findUrls` per logical line; enabled by default even when no search is active (`terminal/.../TerminalView.kt:767`; `SearchHighlightOverlay.kt:103-128`, `:200-255`).
3. **Unbounded main-thread drain** — the handler re-arms while the queue has bytes; under `yes`-class output the main thread never returns to the looper (`terminal/.../TerminalSession.kt:371-373`).
4. **`scrollTopRow` at the screen root** — recomposes the whole terminal screen (both panes, key bar, top bar) per scroll row, to derive a boolean that `isAtLiveEdge`'s hysteresis already computed (`app/.../TerminalScreen.kt:310-318` vs `terminal/.../TerminalManager.kt:564-577`).
5. **Pinch zoom on the old path** — `setTextSize` allocates a new `TerminalRenderer` (127 glyph measurements) per scale event; `updateTextSize()`, documented as done, does not exist `[verified]` (`terminal/.../TerminalView.kt:787-790`; `docs/MEMORYBANK.md:921`).

### P1 — unnecessary emissions and amortization failures

6. **Block repository** — every mutation copies **every session's** block map and re-publishes two `StateFlow`s; `onOutputChunk` appends by copying the output-line list (`data/.../BlockRepositoryImpl.kt:63-69`, `:104-122`).
7. **Running block rendering** — `joinToString` of the whole output body plus a URL regex pass inside composition on every recomposition (`ui/.../PromptBlock.kt:154-162`, `:187`); no collapse for long outputs (README: "show N more ❌ Missing").
8. **Four 500 ms polling loops + forced network tick** in one view model — one deliberately force-re-emits the block list every 500 ms while a command runs (`ui/.../BlockEngineViewModel.kt:66-139`).
9. **Two full-terminal `view.draw()` captures on timers** — top bar every 500 ms while resumed (`app/.../TerminalBackdrop.kt:79-105`), selection menu every 90 ms while selecting (`app/.../SelectionMenu.kt:114-117`). Each is a second complete rasterization of the terminal on the main thread.
10. **Per-chunk peripheral work** — linear `getIndexOfSession` scan, ambient tint scan (6×48 cells), activity-tracker StateFlow write, accessibility full-screen text build when TalkBack is on (`TerminalManager.kt:682-692`, `:688`, `:140-150`; `TerminalView.kt:769`).
11. **Space-key latency** — a space is held up to 500 ms for space-drag disambiguation (`TerminalView.kt:638-648`, `:1926`).
12. **Cursor blink** — upstream's blinker exists in the vendored view but is never enabled from anywhere (no callers of `setTerminalCursorBlinkerState`); it is dead weight either way.

### P2 — battery, startup, memory, data path

13. **Startup** — `MainActivity.attachBaseContext` blocks on a DataStore first read (`app/.../MainActivity.kt:84-89` `[verified]`); a 5.3 MB provider catalog downloads from inside the Hilt graph at first launch (`data/.../CatalogModule.kt:43-48`); splash is a fixed ≥1.5 s (`ui/.../SplashScreen.kt:63-80`, `MainActivity.kt:276-283`).
14. **Agent streaming** — full transcript list copy per SSE token and per shell line (`domain/.../TranscriptBuilder.kt:129`, `AgentChatViewModel.kt:287-289`); per-line `withContext(Main)` hop (`ToolCallExecutor.kt:239-255`); `ToolCallBuffer` re-allocates the args `StringBuilder` per fragment (`agent/.../ToolCallBuffer.kt:70-76`).
15. **SSH** — 50 ms busy-wait poll while the emulator is being built (`ssh/.../SshSessionFactoryImpl.kt:81-83`); 30 s keepalive per session.
16. **Room** — `SessionManagerAdapter`'s reconcile loop writes what its own `observeAll()` emission triggers (feedback loop), plus an unconditional 500 ms ticker (`data/.../SessionManagerAdapter.kt:87-117`, `:291`); per agent turn reloads and re-upserts the whole transcript.
17. **Memory** — 3000 transcript rows per session (~3 MB at 80 columns; upstream default is 2000) + 200 blocks per session in RAM; N sessions multiply all of it (`TerminalManager.kt:1267`, `:1277`).
18. **Build/runtime packaging** — release has `isMinifyEnabled = false`, `isShrinkResources = false`; no baseline profile anywhere; `app/build.gradle.kts` overrides `targetSdk = 28` while the convention plugin and `tools:targetApi="36"` say 36 `[verified]`.
19. **Docs/code drift** — `updateTextSize` documented, absent; `fontSizeSp` still `Flow<Int>` where docs describe `Flow<Float>`; `docs/block-engine/PLAN.md` referenced from code, missing.

---

## 3. Phase 0 — measurement first (nothing ships before this)

`docs/PLAN.md:512` says it plainly: *"CI yalnızca derleme doğruluyor. Hiçbiri cihazda çalıştırılmadı."* Until this phase exists, "faster" is a feeling.

**Harness (new `:benchmark` module, Macrobenchmark):**

| Scenario | Measures |
|---|---|
| Startup (cold/warm) | `StartupTimingMetric`, time-to-first-frame, time-to-attached-session |
| `yes > /dev/null` burst | `FrameTimingMetric` (janky frames %), sustained bytes/s, main-thread message backlog |
| `git log \| less`, `find /` | scroll smoothness, scrollback memory (`dumpsys meminfo`), p50/p99 frame ms |
| Type→echo | input-to-pixel latency (trace deltas between IME commit and first pixel of the echoed char) |
| Tab/pane switch | frames per switch, transcript re-serialize count |
| Pinch zoom | frames per gesture, glyph re-measure count |
| Selection menu open | capture count, `view.draw()` cost |

**Instrumentation (drop-in, no behavior change):** `androidx.tracing` trace
sections around `emulator.append`, `BlockEngineWire.onSessionTextChanged`,
`TerminalRenderer.render`, `SearchHighlightOverlay.onDraw`, backdrop `capture`,
`BlockRepositoryImpl.mutate` — so a Perfetto trace names the root cause.

**Device-side checks (scripted):** `adb shell dumpsys gfxinfo dev.drosh framestats`
(jank %, frame percentiles); `dumpsys meminfo` per session count; battery
stats wakeups/minute while idle.

**Micro-benchmarks (plain JVM unit tests):** `TerminalEmulator.append`
throughput MB/s; `AnsiStripper.strip` ns/op; `findRollingAnchor` ns/op;
`BlockRepositoryImpl.mutate` ns/op at 200 blocks — these become regression
gates for later phases.

**Targets (mid-range 2022+ device; replace estimates with Phase 0 numbers):**

| Metric | Today (est.) | Target |
|---|---|---|
| type→echo pixel | ~80–150 ms | ≤ 40 ms |
| janky frames during sustained output | high | < 2% |
| frames per tab/pane switch | many | ≤ 1–2 |
| cold start to first frame | ~2.5–3 s | ≤ 1.2 s (of which artificial splash ≤ 0.4 s) |
| idle CPU / wakeups | 10+ timers | < 1% CPU, nothing on a 500 ms timer |
| memory per idle session | ~3 MB | ≤ ~1.5–2 MB (configurable rows) |

---

## 4. The phases

Ordering rationale: Phase 1A and Phase 3 are the cheapest high-value changes;
the block-engine surgery (1C/2) is the biggest behavioral risk and comes after
its tests exist.

### Phase 1 — get the work off the main thread (~1 week)

- **1A. Frame-paced drain (do first).** In `TerminalSession`'s handler, process
  at most a bounded slice per frame via `Choreographer` post-frame callbacks,
  keeping the remainder for the next frame (Windows Terminal's coalescing
  posture; kitty's `repaint_delay` is the same idea with a user knob — we keep
  ours internal and debug-only). `yes`-class output stops starving the looper;
  input latency during output collapses because the UI thread still frames.
  _Risk:_ low. The emulator is still single-threaded; only the pacing changes.
- **1B. Dedicated session parse thread (after 1A, only if measurements demand).**
  kitty (`child-monitor.c:312`) and Ghostty (`renderer/Thread.zig:192`) models:
  one thread per session owning the emulator, publishing a small immutable
  snapshot the renderer reads. Requires a locking scheme around `TerminalBuffer`
  rows (kitty solves this with a screen mutex). _Risk:_ the vendored engine
  assumes single-thread access; the classic `TerminalView` reads rows on the
  main thread today. Only worth it if 1A leaves jank above target.
- **1C. Kill the transcript poller for classic mode; make it incremental.**
  Gate `blockEngineWire` on `useBlockEngine` at the `TerminalManager.kt:691`
  call site; when it does run, run it **per frame, off the decode path**: track
  rows/line indices incrementally instead of rebuilding the whole transcript
  string, and replace the `MAX_ANCHOR_BYTES` search with "append since last
  seen row". The wire's own KDoc says it is a per-frame design — make it true.
  _Risk:_ block boundary/echo-suppression semantics need the acceptance tests
  from Phase 0 before this lands. Write `docs/block-engine/PLAN.md` (referenced
  but missing).

### Phase 2 — block engine and repository (~3–4 days)

- `mutate()` copies only the active session's list, not the map of all
  sessions; publish with `distinctUntilChanged`; drop the forced re-emit.
- `PromptBlock`: `remember(text)` around `joinToString` and the URL pass; mark
  `Block` `@Immutable` so LazyColumn can skip unchanged items; restore
  long-output collapse so a 20k-line command renders a window, not all of it.
- Replace the four 500 ms polls with pushes from the wire (it already knows
  when the prompt/dir/awaiting state changes); single 1 s elapsed ticker, only
  while a command runs and the pane is visible.

### Phase 3 — Compose recomposition surgery (~2–3 days)

- Remove the `scrollTopRow` root read; derive chrome state from the already
  hysteretic `isAtLiveEdge` only.
- Implement the documented `TerminalRenderer.updateTextSize()` — in-place
  re-measure behind the existing setters — and an equality guard in
  `TerminalView.setTextSize`, ending per-event renderer allocation.
- Cache `sessionForSlot`/`getIndexOfSession` lookups so the per-chunk path is
  not a linear scan.

### Phase 4 — inside the vendored view (~3–4 days)

- `SearchHighlightOverlay`: gate URL highlighting behind a setting (default
  off), cache row strings behind a dirty flag, and only scan when the pane is
  visible — this removes the second full-text scan per chunk.
- Resolve the cursor blinker: wire `setTerminalCursorBlinkerState` properly or
  delete the dead path.
- Space-drag: commit a space immediately unless a drag gesture has actually
  begun; keep the 500 ms ambiguity only for gestures.
- Accessibility: build the screen description lazily and throttled, not per
  chunk.
- Backdrop captures: top bar re-captures only when output goes quiet (plus a
  longer cap), and samples a downscaled bitmap; selection menu samples the
  selection region, not the whole view, with a 200 ms ceiling.

### Phase 5 — battery: delete the timers (~2 days)

Everything on this list becomes event-driven: the four block-VM polls, the
network ticker, `SessionManagerAdapter`'s 500 ms `ensureSessionExists`, the SSH
50 ms busy-wait (replaced by a proper ready signal), and a review of the SSH
keepalive interval. Reference posture: kitty's loop does nothing when nothing
happens (`glfw.c:3611`), and Alacritty renders only on damage.

### Phase 6 — agent and SSH data path (~2 days)

- `TranscriptBuilder`: stop copying the whole list per token — accumulate and
  publish coalesced (frame-paced or ≥50 ms), then finalize exactly.
- `ToolCallBuffer`: mutate the existing args builder in place.
- Room: debounce the reconcile feedback loop; write only changed messages per
  turn; delete the dead `_livePreviews` combine source; add the missing
  `agent_messages` index only if a query pattern needs it.

### Phase 7 — startup, memory, packaging (~2–3 days)

- Move locale out of `attachBaseContext` (a synchronous mirror read, ~µs, with
  DataStore remaining the source of truth).
- Move the catalog load off the Hilt graph to after first frame / first use;
  parse it incrementally instead of a 5.3 MB DOM parse.
- Splash: gate on readiness, max(min-hold 400 ms, session-ready) instead of a
  fixed 1.5 s.
- Transcript rows: setting, default 2000 (upstream value), cap 5000; blocks
  cap with cheap eviction.
- Release: enable R8 + resource shrinking (verify rules for JNI/reflection
  users: OkHttp, kotlinx.serialization, Hilt, sora-editor), generate a
  **baseline profile** via the Phase 0 macrobenchmark and ship it; APK shrinks,
  class loading and first-use cost drop.

### Phase 8 — docs↔code reconciliation (~0.5 day)

Make `updateTextSize`, `fontSizeSp`'s type, and `docs/block-engine/PLAN.md`
match reality, in whichever direction is cheaper. The memory bank currently
describes a pinch implementation that does not exist `[verified]`; while wrong
it will keep getting cited as done.

---

## 5. Sequencing and risk register

**Recommended order:** 0 → 1A → 3 → 1C (+ its tests) → 2 → 4 → 5 → 6 → 7 → 8.

| Risk | Where | Mitigation |
|---|---|---|
| Emulator thread-safety | Phase 1B | Keep 1A as the fallback; if 1A meets targets, 1B never ships |
| Block boundary regressions | Phases 1C, 2 | Phase 0 acceptance tests (echo suppression, `clear`, prompt detection, exit codes) before touching the wire; alsoREADME's "block mode always reports exit code 0" bug gets fixed here or stays loudly documented |
| R8 strips something | Phase 7 | Debug build with minify on as a smoke test; keep rules local to modules |
| Android makes damage tracking pointless on the classic path | Phase 4 | Accepted — `TerminalBuffer.kt:514-527` already records the experiment; we optimize frequency instead |
| Blur/glass effects are the actual frame cost on some devices | Phase 4 | Phase 0 traces will show `capture`/blur share; if blur dominates, the capture redesign above is the lever, not the renderer |

## 6. Definition of done

- A phase is done when: (a) the Phase 0 scenario for it shows the target
  metric, (b) the scenario has not regressed the others, (c) a note lands in
  `docs/MEMORYBANK.md` with the before/after numbers.
- The repo ends this effort with: no 500 ms timers in the output path, classic
  mode paying nothing for the block engine, no per-chunk full-text scans,
  pinch measured in-place, release built with R8 + baseline profile, and the
  benchmark harness runnable from CI (at least nightly) so "it felt slow" is
  never the only signal again.

---

## 7. Reproducing section 1

```bash
mkdir -p /tmp/opencode/perf-refs && cd /tmp/opencode/perf-refs
git clone --depth 1 https://github.com/termux/termux-app
git clone --depth 1 https://github.com/kovidgoyal/kitty
git clone --depth 1 https://github.com/wezterm/wezterm
git clone --depth 1 https://github.com/ghostty-org/ghostty
git clone --depth 1 https://github.com/alacritty/alacritty
```

Every `path:line` in §1 was read from these clones at planning time
(October 2026). Benchmarks quoted in §1.6 are kitty's own published numbers
(2023, kitty 0.33 era) and are directional only. Re-clone and re-verify before
citing a line number in a code review.

---

*End of plan. No implementation has begun.*
