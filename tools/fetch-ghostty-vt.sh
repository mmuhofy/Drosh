#!/usr/bin/env bash
# Fetch libghostty-vt for the Android ABIs we ship, verify the checksums, and
# lay the headers and libraries out where the native build expects them.
#
# The library is not committed: it is 2.1 MB per ABI and it is somebody else's
# build. It is downloaded, verified against the release's own SHA256SUMS, and
# cached by CI. A checksum that does not match aborts the build — an unchecked
# binary is a binary nobody can account for.
#
# Usage:
#   tools/fetch-ghostty-vt.sh [--abi arm64-v8a] [--version vt-v1.0.0]
#
# Environment:
#   GHOSTTY_VT_REPO     owner/name of the release repository
#   GHOSTTY_VT_VERSION  release tag (overridden by --version)
#   GHOSTTY_VT_ABIS     space-separated ABIs (overridden by --abi)

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEST="$REPO_ROOT/native/ghostty"

GHOSTTY_VT_REPO="${GHOSTTY_VT_REPO:-ancientcatz/libghostty-android}"
GHOSTTY_VT_VERSION="${GHOSTTY_VT_VERSION:-v1.4.0}"
GHOSTTY_VT_ABIS="${GHOSTTY_VT_ABIS:-arm64-v8a}"

while [ $# -gt 0 ]; do
  case "$1" in
    --abi) GHOSTTY_VT_ABIS="$2"; shift 2 ;;
    --version) GHOSTTY_VT_VERSION="$2"; shift 2 ;;
    --repo) GHOSTTY_VT_REPO="$2"; shift 2 ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done

command -v curl >/dev/null || { echo "curl is required" >&2; exit 1; }

# GitHub's release asset CDN occasionally answers 502/503/504 and closes the
# connection without warning. `--retry-all-errors` covers HTTP 5xx, 408 and
# 429, with a growing delay between attempts.
fetch() {
  curl -fsSL --retry 6 --retry-all-errors --retry-delay 10 --retry-max-time 600 "$@"
}

# The `releases/download` browser URL that serves asset bytes has been
# answering 504 for every GET — HEAD still returns 200, which is what fools a
# naive "is it up?" probe. The API asset endpoint (`api.github.com/.../assets`)
# keeps working, so when the GitHub CLI is present it does the fetching and
# curl is the fallback. The bytes are verified against SHA256SUMS either way;
# only the transport changes.
download() {
  local name="$1" dest="$2"
  # gh authenticates either through the runner's GITHUB_TOKEN env var or through
  # its config file (the usual dev-box case); `gh auth status` covers both. The
  # API asset endpoint is the one that answers 200 while the browser download
  # URL answers 504.
  if command -v gh >/dev/null && gh auth status >/dev/null 2>&1; then
    # Always land in a scratch dir so a same-name destination does not make
    # the `mv` a no-op (which still fails under `set -e`).
    local dir="$DEST/.download"
    mkdir -p "$dir"
    gh release download "$GHOSTTY_VT_VERSION" --repo "$GHOSTTY_VT_REPO" \
      --pattern "$name" --dir "$dir" --clobber
    mkdir -p "$(dirname "$dest")"
    mv "$dir/$name" "$dest"
    return 0
  fi
  fetch "$BASE/$name" -o "$dest"
}
command -v sha256sum >/dev/null || { echo "sha256sum is required" >&2; exit 1; }

info() { printf '  %s\n' "$*"; }

mkdir -p "$DEST/include" "$DEST/lib"

BASE="https://github.com/$GHOSTTY_VT_REPO/releases/download/$GHOSTTY_VT_VERSION"

echo "libghostty-vt $GHOSTTY_VT_VERSION from $GHOSTTY_VT_REPO"
info "ABIs: $GHOSTTY_VT_ABIS"

# The checksum list is the authority for every file below it.
echo "  fetching SHA256SUMS"
download "SHA256SUMS" "$DEST/SHA256SUMS"

verify() {
  local file="$1" name="$2"
  local expected
  expected="$(awk -v n="$name" '$2 == n { print $1 }' "$DEST/SHA256SUMS")"
  if [ -z "$expected" ]; then
    echo "no checksum for $name in SHA256SUMS" >&2
    exit 1
  fi
  local actual
  actual="$(sha256sum "$file" | awk '{ print $1 }')"
  if [ "$actual" != "$expected" ]; then
    echo "checksum mismatch for $name" >&2
    echo "  expected $expected" >&2
    echo "  actual   $actual" >&2
    exit 1
  fi
  info "verified $name"
}

# --- headers ----------------------------------------------------------------
HEADERS="$DEST/libghostty-vt-headers.tar.gz"
echo "  fetching headers"
download "libghostty-vt-headers.tar.gz" "$HEADERS"
verify "$HEADERS" "libghostty-vt-headers.tar.gz"
rm -rf "$DEST/include"
tar -xzf "$HEADERS" -C "$DEST"
rm -f "$HEADERS"
info "headers in native/ghostty/include"

# --- libraries --------------------------------------------------------------
for abi in $GHOSTTY_VT_ABIS; do
  name="libghostty-vt-$abi.so"
  out="$DEST/lib/$abi/libghostty-vt.so"
  mkdir -p "$DEST/lib/$abi"
  echo "  fetching $name"
  download "$name" "$out"
  verify "$out" "$name"

  # The release's SONAME is `libghostty-vt.so.0`, but an APK ships the file as
  # `libghostty-vt.so` and Android's linker resolves DT_NEEDED by file name.
  # Left alone, `libdrosh.so` would ask for a file the APK does not contain and
  # the app would fail to start with a link error naming a library that is
  # visibly present.
  #
  # The fix is to rewrite both sides to the name the APK uses: the engine's own
  # SONAME, and the DT_NEEDED entry in everything that links it. The bytes are
  # checked again afterwards, because the checksum above only covers what the
  # release published.
  if command -v patchelf >/dev/null; then
    patchelf --set-soname libghostty-vt.so "$out"
    info "soname set to libghostty-vt.so"
  else
    echo "patchelf is required to set the soname" >&2
    exit 1
  fi
done

# --- provenance -------------------------------------------------------------
# Which ghostty commit these bytes were built from. The library is somebody
# else's build; the commit is the only thing that says what is inside it.
download "ghostty-commit.txt" "$DEST/ghostty-commit.txt" || true
echo "$GHOSTTY_VT_VERSION" > "$DEST/VERSION"

rm -rf "$DEST/.download"
echo "libghostty-vt ready: $DEST"
