#!/usr/bin/env bash
# Iris Shell asset-extract step.
#   asset-extract.sh — runs INSIDE the host (not proot) to untar the rootfs into
#   $ROOTFS_DIR. Stage 2 of the bootstrap pipeline.
#
# This script is intentionally minimal — it does not depend on the rootfs
# being valid (that check is bootstrap-time).

set -euo pipefail

ROOTFS_TARBALL="${1:-}"
ROOTFS_DIR="${2:-}"

if [ -z "$ROOTFS_TARBALL" ] || [ -z "$ROOTFS_DIR" ]; then
    echo "asset-extract.sh: usage: $0 <tarball> <dest dir>" >&2
    exit 64
fi

mkdir -p "$ROOTFS_DIR"

# Note: not running under proot yet — this is the host Android side.
# Bootstrap code streams the tarball through us, so we read stdin.
tar -xzf "$ROOTFS_TARBALL" -C "$ROOTFS_DIR"
