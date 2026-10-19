#!/usr/bin/env bash
# Iris Shell rootfs optimize.
#   rootfs-optimize.sh — runs INSIDE proot as the FINAL bootstrap step.
#
# Differences from a naive `apt-get clean` purge:
#   * keeps /var/lib/apt/lists/* so subsequent `apt install` is fast
#   * only removes /var/cache/apt/archives/*.deb (deb download cache)
#   * purges /tmp content
#   * writes the setup-completion marker — bootstrap is done.
#
# Marker + cache policy mirrors MEMORYBANK.md "Caching strategy".

set -euo pipefail

MARKER_DIR=/var/lib/drosh
MARKER_FILE="$MARKER_DIR/.setup_complete"

echo "rootfs-optimize: cleaning deb cache..."
apt-get clean -qq

echo "rootfs-optimize: purging /tmp..."
rm -rf /tmp/*

echo "rootfs-optimize: writing setup-completion marker..."
mkdir -p "$MARKER_DIR"
# Capture install signature so re-runs are cheap.
{
    echo "drosh_setup_version=1"
    echo "completed_at=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
    echo "abi=${ABI:-unknown}"
} > "$MARKER_FILE"

echo "rootfs-optimize: ok"
