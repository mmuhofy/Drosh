#!/usr/bin/env bash
# bench-render.sh — the render loop's numbers, from a real device.
#
#   tools/bench-render.sh                      the whole run, every scenario
#   tools/bench-render.sh --scenario flood     one scenario
#   tools/bench-render.sh --apk path.apk       a specific build
#   tools/bench-render.sh --seconds 10         how long each scenario runs
#
# What it does: installs the app, opens the terminal, runs a few commands that
# make the terminal work in different ways, and collects the render loop's own
# measurements out of the log.
#
# The measurements are the app's, not the script's: the app times its own
# phases and writes one line per two seconds under the tag `DroshBench`. The
# script only says when to start and when to stop, and pulls the lines out.
#
# The report goes to bench-out/ and is meant to be attached to a change, so
# that "it got faster" has a number behind it.
set -euo pipefail

cd "$(git rev-parse --show-toplevel)"

APK=""
DEVICE=""
OUT_DIR="bench-out"
SECONDS_PER_SCENARIO=8
ONLY=""
SCENARIOS=(idle flood scroll tui)

while [ $# -gt 0 ]; do
  case "$1" in
    --apk) APK="$2"; shift 2 ;;
    --device) DEVICE="$2"; shift 2 ;;
    --out) OUT_DIR="$2"; shift 2 ;;
    --seconds) SECONDS_PER_SCENARIO="$2"; shift 2 ;;
    --scenario) ONLY="$2"; shift 2 ;;
    -h|--help) sed -n '2,20p' "$0"; exit 0 ;;
    *) echo "unknown option: $1" >&2; exit 2 ;;
  esac
done

PACKAGE="dev.drosh"
ACTIVITY="dev.drosh.MainActivity"
BENCH_TAG="DroshBench"

adb_cmd() {
  if [ -n "$DEVICE" ]; then
    adb -s "$DEVICE" "$@"
  else
    adb "$@"
  fi
}

# --- the device --------------------------------------------------------------

if [ -z "$DEVICE" ]; then
  DEVICE="$(adb devices | awk 'NR>1 && $2=="device" {print $1; exit}')"
fi
if [ -z "$DEVICE" ]; then
  echo "no device: connect one or pass --device" >&2
  exit 1
fi

echo "device: $DEVICE"

# --- the build ---------------------------------------------------------------

if [ -z "$APK" ]; then
  APK="android/app/build/outputs/apk/debug/app-debug.apk"
fi
if [ ! -f "$APK" ]; then
  echo "no apk at $APK — build it first (./gradlew :app:assembleDebug)" >&2
  exit 1
fi

echo "installing $APK"
adb_cmd install -r "$APK" >/dev/null

# --- the run -----------------------------------------------------------------

mkdir -p "$OUT_DIR"
STAMP="$(date +%Y%m%d-%H%M%S)"
REPORT="$OUT_DIR/render-$STAMP.txt"

{
  echo "# Drosh render benchmark"
  echo
  echo "date:    $(date -Is)"
  echo "device:  $(adb_cmd shell getprop ro.product.model | tr -d '\r')"
  echo "android: $(adb_cmd shell getprop ro.build.version.release | tr -d '\r')"
  echo "apk:     $APK"
  echo
} > "$REPORT"

# A line of the terminal's own numbers, with the scenario it belongs to.
collect() {
  local name="$1"
  local lines
  lines="$(adb_cmd logcat -d -s "$BENCH_TAG" | grep -c 'frames=' || true)"
  echo
  echo "## $name  ($lines bench lines)"
  adb_cmd logcat -d -s "$BENCH_TAG" | sed 's/^.*frames=/frames=/' || true
  adb_cmd logcat -c
}

# The scenarios.
#
# Each one is a command typed into the terminal, and a wait. The commands are
# the ones that make the renderer do different work:
#
#   idle   nothing at all — the cost of a frame with nothing to draw
#   flood  output as fast as the terminal can write it — the cost of a frame
#          per line, and the cost of the terminal lock under pressure
#   scroll a long scrollback and then a swipe: the cost of a scrolled frame
#   tui    a full-screen program: the cost of a frame where every cell changed
run_scenario() {
  local name="$1"
  local command="$2"

  echo "scenario: $name"
  adb_cmd logcat -c

  if [ -n "$command" ]; then
    # `input text` needs the spaces escaped, and the newline is its own event.
    local escaped
    escaped="$(printf '%s' "$command" | sed 's/ /%s/g')"
    adb_cmd shell input text "$escaped"
    adb_cmd shell input keyevent 66
  fi

  sleep "$SECONDS_PER_SCENARIO"
  collect "$name"
}

adb_cmd logcat -c
adb_cmd shell am start -n "$PACKAGE/$ACTIVITY" >/dev/null
sleep 4

for scenario in "${SCENARIOS[@]}"; do
  if [ -n "$ONLY" ] && [ "$ONLY" != "$scenario" ]; then
    continue
  fi
  case "$scenario" in
    idle) run_scenario idle "" ;;
    flood) run_scenario flood "timeout $SECONDS_PER_SCENARIO yes" ;;
    scroll)
      run_scenario scroll "seq 1 20000"
      # A swipe is a scroll: the finger moves up, the content follows.
      for _ in 1 2 3 4 5 6; do
        adb_cmd shell input swipe 540 1600 540 400 300
        sleep 0.3
      done
      sleep 2
      collect scroll-swipe
      ;;
    tui) run_scenario tui "vim" ;;
  esac
done

echo
echo "report: $REPORT"
