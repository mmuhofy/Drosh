# Drosh Render Benchmark

The renderer's numbers, how they are taken, and what each phase of the
rendering work has to beat.

The point of this document is that **"it got faster" is not a claim without a
number**, and that a number without a scenario is decoration. Every figure
below has a scenario, a command, and a way to reproduce it.

---

## 1. Where the numbers come from

The app measures its own render loop. The loop times four phases per frame
and writes one line every 120 frames (about two seconds) under the log tag
`DroshBench`:

```
adb logcat -s DroshBench
```

```
frames=241 cells=1920 sprites=812 images=0.0 calls=3.0 bytes=38448 atlas=0.02 \
clean=180 partial=58 full=3 slow8=2 (0.83%) max=11.4 \
pump=1.82 build=0.94 upload=0.31 draw=0.22 jank=0 interval_max=48.1
```

The phases:

| Phase | What it covers | Whose cost it is |
|---|---|---|
| `pump` | the snapshot: the JNI calls that take the terminal's lock | the terminal's IO, blocked while it runs |
| `build` | the cells turned into vertices | the render thread's CPU |
| `upload` | the vertices and the atlas handed to the GPU | the bus and the driver |
| `draw` | the draw calls and the present | the GPU and the display |

`cells` is how many cells the frame carried, `sprites` how many glyph
instances were drawn, `calls` how many GL draw calls were issued, `bytes` how
many bytes crossed the boundary, `atlas` how many atlas regions went up.

`clean` / `partial` / `full` are the engine's own dirty states: the frames
where nothing changed, where only some of the screen changed, and where all
of it did. **The ratio is the headroom the dirty-row work has to claim.**

The same numbers are on the diagnostics screen (the engine's own report) and
in `RenderBench`, which the diagnostics read.

---

## 2. Running the benchmark

```bash
# Build, install, run every scenario, write the report.
tools/bench-render.sh

# One scenario, a different build, a different device.
tools/bench-render.sh --scenario flood --apk android/app/build/outputs/apk/debug/app-debug.apk --device SERIAL
```

The report lands in `bench-out/render-<timestamp>.txt` and is meant to be
attached to the change it measures.

The scenarios and what each one is for:

| Scenario | What it types | What it measures |
|---|---|---|
| `idle` | nothing | the cost of a frame with nothing to draw; the floor |
| `flood` | `timeout 8 yes` | a frame per line at full speed, and the lock under pressure |
| `scroll` | `seq 1 20000`, then swipes | a scrolled viewport: the overscan rows and the re-layout |
| `tui` | `vim` | a full-screen program: every cell changed, every frame |

**A benchmark needs a device.** The CI builds and tests; it cannot measure a
GPU. Every performance claim in a change to the renderer has to come from a
run of this script on real hardware, and the report has to say which device
it was taken on.

---

## 3. The baseline (measured from the code, 2026-11-16)

These are the facts the first phase works against. They are read off the code,
not measured on a device, so they are exact and reproducible.

### 3.1 The frame path

```
PTY → Rust: ghostty_terminal_vt_write            (the session lock)
         → ghostty_render_state_begin_update      (the session lock)
         → the terminal's lock is released here
         → ghostty_render_state_end_update        (no lock)
         → the row iterator + 9 FFI calls per cell (only the rows that changed)
         → the frame is written into the caller's buffer
Kotlin→ the frame is read from that buffer        (no copy, no allocation)
         → buildBackgrounds + buildForegrounds      (only the rows that changed)
         → glBufferSubData, only the rows that changed
         → 2 instanced draws + the cursor + one draw per run of pictures
```

### 3.2 The numbers

| Measure | Before | Now | Where |
|---|---|---|---|
| FFI calls per cell | 9, every cell | 9, **only the rows that changed** | `native/src/frame.rs` (`write_cell`) |
| Frame size | 48 B header + 20 B/cell | 80 B header + 20 B/cell | `FrameFormat.kt` |
| Copies per frame | 2 (a Java array per frame) | **0** (the caller's own buffer) | `native/src/jni/session.rs` (`nativeFrameInto`) |
| Java allocations per frame | one 40 KB array | none | `RenderThread.fetchFrame` |
| Cells walked per frame | all of them | **only the rows that changed** | `FrameRenderer.buildBackgrounds` |
| Bytes uploaded per frame | the whole background buffer + the whole glyph buffer | **only the rows that changed** | `FrameRenderer.uploadBackgrounds` |
| Draw calls per frame | 2 + the cursor + one per picture | 2 + the cursor + **one per run of pictures** | `FrameRenderer.draw` |
| Terminal lock held | the whole frame's production | **the snapshot only** | `native/src/session.rs` (`with_frame`) |
| Styles drawn | bold, underline | bold, underline | `FrameRenderer` |
| Styles carried but not drawn | italic, blink, strikethrough, overline | the same | the frame's attributes |
| Atlas | one `ALPHA_8` texture, 2048², shelf-packed, no fallback | the same | `font/TextAtlas.kt` |
| Font | `Typeface.MONOSPACE`, hard-coded | the same | `font/TextAtlas.kt` |
| EGL context loss | not detected | not detected | `EglCore.swap` |

### 3.3 What the phases cost, on a device

*(to be filled in from `tools/bench-render.sh`; the table is the shape the
numbers have to take.)*

| Scenario | pump p50 | pump p99 | build p50 | upload p50 | draw p50 | frames | jank |
|---|---|---|---|---|---|---|---|
| idle | | | | | | | |
| flood | | | | | | | |
| scroll | | | | | | | |
| tui | | | | | | | |

---

## 4. The acceptance criteria, per phase

Each phase has to beat the numbers of the phase before it, on a device, with
the report attached.

| Phase | What it changes | The criterion | State |
|---|---|---|---|
| 0 — measurement | nothing that draws | the numbers exist and are reproducible | done |
| 1 — the lock | the snapshot is taken under the lock, the rest is not | `pump` p99 < 4 ms while `flood` runs, and the keyboard stays responsive | done, to be measured |
| 2 — zero copy | the frame is written into the renderer's own buffer | the copies per frame: 2 → 0; `pump` p50 halves | done, to be measured |
| 3 — dirty rows | only the changed rows are extracted, built and uploaded | a frame that changes one row costs one row: `build` on `idle` ≈ 0 and `partial` frames cost a fraction of `full` ones | done, to be measured |
| 4 — sprites | the pictures are drawn in runs of the same texture | the draw calls per frame: 3 + runs of pictures | done, to be measured |

**What Phase 4 deliberately did not do.** The background could be drawn as
one full-screen triangle with the cell colours in a texture, the way Ghostty
draws it. It was left alone: the fill cost is the same either way (both
versions paint the whole grid), and the triangle version costs a texture
fetch per fragment where the instanced version costs one vertex read per
cell. On a tiled mobile GPU that is not obviously the cheaper of the two, and
it cannot be measured without a device — so the change that could not be
measured was not made.

The criteria are deliberately in the phases' own units: a renderer that got
faster by doing the same work in a different place has not got faster, and
the numbers say which it was.
