# Drosh — benchmark, telemetry and diagnostics

This document proposes how Drosh should measure itself. It is a design, not a
description of everything already built: where a piece exists it says so, and
where it does not it says what to add and why.

## The rule

**Measure locally, report only when asked.** A terminal is a place people type
passwords and read private files. Nothing in this system may send the contents of
a session, a command, a file name, a host name or a path anywhere. What leaves
the device is counters, timings, device class and crash stacks — and only after
the user says yes.

## Three layers, three questions

| Layer | Question | Where it runs |
| --- | --- | --- |
| Micro | Is this function still as fast? | Host (JVM / `cargo test`), CI |
| Session | Is the app smooth on *this* device, with *this* session? | Device, continuously |
| Field | What are real users experiencing? | Device, opt-in, batched |

Each layer answers a question the others cannot, and none of them is a substitute
for the others. A microbenchmark cannot tell you that the preview loop starves
the render thread; a field counter cannot tell you which of forty commits made a
frame 2 ms slower.

## 1. Micro benchmarks — a budget per hot path

Run on the host, in CI, against a fixed corpus. Fail the build on a regression
larger than a stated tolerance, not on absolute times: CI machines vary more than
the code does.

Already present:

- `native/src/font` and `renderer/snapshot` tests prove correctness, not cost.
- `tools/drosh-bench` and `tools/bench-render.sh` drive the native renderer.
- `tools/benchmark-report.sh` summarises a device `benchmark.log`.

To add, in this order:

1. **`snapshot_bench`** — `SessionHandle::snapshot_png` at 480 px for a 80×24
   and a 200×60 grid, unchanged and dirty frames. Budget: unchanged frame
   ≤ 0.2 ms (cache hit), dirty frame ≤ 8 ms on the reference device. This is the
   number the session grid pays once per card per second while it is open.
2. **`wire_bench`** — frame capture with a full-dirty and a partial-dirty grid.
3. **`glyph_cache_bench`** — mask rasterisation hit rate, so a font change does
   not silently turn into per-frame outlines.
4. **`input_bench`** — one key event end to end through `NativePty`.

Corpus: ASCII, a box-drawing TUI frame, a CJK line, an emoji line. Each case is
a checked-in frame buffer, so the benchmark measures drawing rather than the
engine's parser.

## 2. Session benchmarks — the numbers the renderer already has

`RenderBench` records, per frame, the time split into pump / build / upload /
draw, plus cells, sprites, draw calls, atlas uploads and frame bytes. It writes
one `frames=…` line per second to `benchmark.log`. That is the right shape:
cheap enough to leave on, complete enough to explain a stall.

Extend it rather than replace it:

- **Preview cost.** `TerminalSessionCoordinator.sessionPreview` is called once
  per live session per second while the grid is open. Record `preview_ms` and
  `preview_bytes` for the last call and a rolling p95, keyed by card count. A
  grid of twenty sessions is twenty rasterisations; if that is 200 ms a tick it
  is visible as a stutter in the grid's scroll, not in the terminal.
- **Frame-time histogram, not just an average.** Averages hide jank. Keep a
  64-bucket histogram of frame work and report p50/p95/p99 with the summary.
- **Dropped frames** — frames whose interval exceeded 1.5 × the display's
  refresh period — counted against drawn frames.
- **Startup**: time from process start to first drawn frame, and to first PTY
  output. Split by cold and warm start.
- **Memory**: `Runtime.totalMemory - freeMemory`, plus the engine's own frame
  buffer and atlas sizes, sampled once a second. A terminal's memory is mostly
  scrollback: report `scrollbackLines` alongside, because the number is
  meaningless without it.

Budgets to hold the product to:

| Metric | Target (mid-range phone) |
| --- | --- |
| Steady-state frame work | ≤ 8 ms p95 at 60 Hz |
| Dropped frames while scrolling | < 1 % |
| Preview tick, 10 cards | ≤ 60 ms off the main thread |
| First frame after tap on a session | ≤ 120 ms |
| Cold start to first frame | ≤ 2 s |

## 3. Diagnostics — the screen a user can paste into an issue

`EngineDiagnostics.report()` already answers the three questions that can fail
first: did the libraries load, does the engine parse, does a frame come out. It
also reports the renderer's live counters, input statistics and block-mark
count. Two things are missing:

1. **A reachable surface.** Today it is a log line and a hidden path. Add
   *Settings → About → Diagnostics* with the same report rendered as selectable
   text, a "Copy" button, and a "Share file" button that writes the report plus
   the last N lines of `benchmark.log` to a cache file. No session content is in
   it by construction: the report is counters and self-tests only.
2. **A preview section.** Report whether a font is installed in the native
   renderer (`font::has_font`), the last preview's width/height/bytes and its
   duration. "Preview is a blank rectangle" must be answerable from the report
   alone, without logcat.

The panel is local and always available. It is not telemetry; it is the user's
own evidence.

## 4. Field telemetry — opt-in, aggregate, batched

Off by default. Turning it on is a switch in the same Diagnostics screen that
says exactly what is sent. Consent is stored, revocable, and takes effect
immediately.

### What may be sent

- App version, build type, ABI, Android API level, device model *class* (a
  coarse bucket, not the exact marketing name).
- Renderer counters: fps p50/p95, dropped-frame ratio, frame-work p95, atlas
  fill, session count bucket (0, 1–2, 3–5, 6–10, 11+), scrollback size bucket,
  split/block-mode on/off, font pack id (bundled/Geist/Inter/downloaded hash),
  theme id.
- Error events: sanitised stack traces, engine self-test results, native link
  failures, PTY spawn failures.
- Feature counters: previews rendered, preview cache hits, prediction accepted
  vs. shown, session groups used.

### What may never be sent

Command lines, output, session names, file paths, host names, URLs, clipboard
contents, typed text, predicted lines, the list of installed apps, or anything
derived from them. Enforce this at the type level: the telemetry payload is a
`@Serializable` data class of numbers, enums and fixed-size strings, built by a
mapper that never receives a session's text. If a field is a `String` and did not
come from a constant, it does not belong.

### How

- A ring buffer in memory, flushed at most once per hour, and at most once per
  `onStop` if the last flush was over ten minutes ago.
- One HTTP POST per batch, gzip, no retries on 4xx, exponential backoff with
  jitter on 5xx. Never send during a session's first minute — that is when the
  user is working and when measurement would perturb what it measures.
- An installation id: a random UUID in DataStore, not derived from anything.
  Reset in the Diagnostics screen ("Reset telemetry id") and on uninstall.
- Server side keeps aggregates keyed by version and device class. No per-install
  timeline beyond 30 days.

Recommended service: a crash/analytics backend that supports custom events and
native symbolication (Sentry, or Firebase Crashlytics if the project wants to
stay inside Google's stack). The native engine means symbol upload for
`libdrosh.so` and `libghostty-vt.so` is a hard requirement, not a nice-to-have.

## 5. What to build first

In order, because each step makes the next one cheap:

1. `snapshot_bench` + preview timing counters (the grid's cost is currently
   unmeasured, and it is the newest hot path).
2. A Diagnostics screen that shows and copies `EngineDiagnostics.report()`.
3. Frame-time histogram and dropped-frame count in `RenderBench`.
4. Startup and memory samples in the same report.
5. Opt-in telemetry, with the payload type above and a consent screen.

Steps 1–4 are useful on their own and need no network, no account and no
agreement about privacy. Step 5 should wait until the local numbers are stable:
shipping field telemetry before the local report is trustworthy only produces
confident nonsense at scale.
