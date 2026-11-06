#!/usr/bin/env bash
# Run the native self-test on the CI runner, against the aarch64 library.
#
# The device is the real gate, but a device run is minutes away and this is
# seconds: qemu-user executes the aarch64 library on the x86 runner, and the
# smoke test is a pure function of the library — create a terminal, write "hi",
# read two cells. If that fails here, it fails on the device for the same
# reason, and the reason is in the log instead of in a screenshot.
#
# What this does NOT prove: anything about the JVM, the APK, the linker's view
# of the app's jniLibs, or the surface. Those are the device's job.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
NATIVE="$REPO_ROOT/native"

ABI="${1:-arm64-v8a}"
case "$ABI" in
  arm64-v8a) QEMU=qemu-aarch64; TRIPLE=aarch64-linux-gnu ;;
  *) echo "unsupported abi for the host smoke test: $ABI" >&2; exit 2 ;;
esac

command -v "$QEMU" >/dev/null || { echo "$QEMU is required" >&2; exit 1; }

ENGINE="$NATIVE/ghostty/lib/$ABI/libghostty-vt.so"
test -f "$ENGINE" || { echo "missing $ENGINE — run tools/fetch-ghostty-vt.sh" >&2; exit 1; }

# Where qemu-user finds the aarch64 interpreter and libc. The cross compiler
# already knows its own sysroot for linking; this is only for running.
QEMU_PREFIX="${SMOKE_SYSROOT:-/usr/aarch64-linux-gnu}"
test -d "$QEMU_PREFIX" || {
  echo "no aarch64 sysroot at $QEMU_PREFIX (install libc6-dev-arm64-cross)" >&2
  exit 1
}

# A tiny C driver: the same call sequence the JNI entry point makes, without a
# JVM in the way. It is generated here rather than committed because it is not
# part of the product — it is the smallest thing that can ask the question.
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

cat > "$WORK/smoke.c" <<'EOF'
#include <stdio.h>
#include <stdint.h>
#include <string.h>
#include <ghostty/vt.h>

static int fail(const char *step, int code) {
    printf("FAIL: %s -> %d\n", step, code);
    return 1;
}

int main(void) {
    GhosttyString version = {0};
    if (ghostty_build_info(GHOSTTY_BUILD_INFO_VERSION_STRING, &version) != GHOSTTY_SUCCESS)
        return fail("build_info", -1);
    printf("ghostty-vt: %.*s\n", (int)version.len, (const char *)version.ptr);

    GhosttyTerminal term = NULL;
    if (ghostty_terminal_new(NULL, &term, 20, 4) != GHOSTTY_SUCCESS)
        return fail("terminal_new", -1);

    const char *text = "hi";
    ghostty_terminal_vt_write(term, (const uint8_t *)text, strlen(text));

    GhosttyRenderState state = NULL;
    if (ghostty_render_state_new(NULL, &state) != GHOSTTY_SUCCESS)
        return fail("render_state_new", -1);
    if (ghostty_render_state_update(state, term) != GHOSTTY_SUCCESS)
        return fail("render_state_update", -1);

    uint16_t cols = 0, rows = 0;
    ghostty_render_state_get(state, GHOSTTY_RENDER_STATE_DATA_COLS, &cols);
    ghostty_render_state_get(state, GHOSTTY_RENDER_STATE_DATA_ROWS, &rows);
    printf("grid: %ux%u\n", cols, rows);

    GhosttyRenderStateRowIterator it = NULL;
    if (ghostty_render_state_row_iterator_new(NULL, &it) != GHOSTTY_SUCCESS)
        return fail("row_iterator_new", -1);

    GhosttyRenderStateRowCells cells = NULL;
    if (ghostty_render_state_row_cells_new(NULL, &cells) != GHOSTTY_SUCCESS)
        return fail("row_cells_new", -1);

    if (ghostty_render_state_get(state, GHOSTTY_RENDER_STATE_DATA_ROW_ITERATOR, &it) != GHOSTTY_SUCCESS)
        return fail("bind iterator", -1);
    if (!ghostty_render_state_row_iterator_next(it))
        return fail("row_iterator_next", -1);
    if (ghostty_render_state_row_get(it, GHOSTTY_RENDER_STATE_ROW_DATA_CELLS, &cells) != GHOSTTY_SUCCESS)
        return fail("row_get", -1);

    uint32_t h = 0, i = 0;
    if (ghostty_render_state_row_cells_select(cells, 0) != GHOSTTY_SUCCESS)
        return fail("select 0", -1);
    ghostty_render_state_row_cells_get(cells, GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_GRAPHEMES_BUF, &h);
    if (ghostty_render_state_row_cells_select(cells, 1) != GHOSTTY_SUCCESS)
        return fail("select 1", -1);
    ghostty_render_state_row_cells_get(cells, GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_GRAPHEMES_BUF, &i);

    printf("cells: '%c' '%c'\n", (char)h, (char)i);

    ghostty_render_state_row_cells_free(cells);
    ghostty_render_state_row_iterator_free(it);
    ghostty_render_state_free(state);
    ghostty_terminal_free(term);

    if (h == 'h' && i == 'i') {
        printf("PASS\n");
        return 0;
    }
    printf("FAIL: expected 'h','i'\n");
    return 1;
}
EOF

CC="${CC:-aarch64-linux-gnu-gcc}"
command -v "$CC" >/dev/null || { echo "$CC is required" >&2; exit 1; }

# No --sysroot: the cross compiler's own sysroot is correct for linking, and
# pointing at the host directory breaks the absolute paths ld records.
"$CC" \
  -O1 \
  -I "$NATIVE/ghostty/include" \
  "$WORK/smoke.c" \
  -L "$NATIVE/ghostty/lib/$ABI" \
  -lghostty-vt \
  -o "$WORK/smoke"

echo "--- running under $QEMU"
# LD_LIBRARY_PATH inside qemu is the *guest* path; -L supplies the host
# sysroot for the interpreter and libc.
"$QEMU" \
  -L "$QEMU_PREFIX" \
  -E LD_LIBRARY_PATH="$NATIVE/ghostty/lib/$ABI" \
  "$WORK/smoke"
