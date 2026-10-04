#!/usr/bin/env bash
# dedit-install.sh — builds the `dedit` package, publishes it to a local apt
# repository inside the rootfs, and installs it.
#
# Runs INSIDE proot. The staging tree has already been written by
# DeditPackage.stage() (DEBIAN/control, usr/bin/dedit, …); this script's job is
# everything that needs real Debian tooling.
#
# dpkg-deb and apt do the archive and index formats here rather than in Kotlin.
# A hand-rolled ar/tar writer would be ~150 lines to reproduce what
# dpkg-deb already does correctly, and any mistake in the tar padding or the
# Packages checksum field would surface as a subtly corrupt package.
#
# Not fatal on failure. If apt cannot be made to work, the command is still
# installed by copying it into place, because a missing `dedit` is a worse
# outcome than a `dedit` that dpkg does not know about.

set -uo pipefail

export DEBIAN_FRONTEND=noninteractive

SRC="${DEDIT_SRC:?DEDIT_SRC not set}"
REPO="${DEDIT_REPO:?DEDIT_REPO not set}"
SOURCES_DIR=/etc/apt/sources.list.d

echo "dedit-install: staging tree at $SRC"

if [ ! -f "$SRC/DEBIAN/control" ] || [ ! -f "$SRC/usr/bin/dedit" ]; then
    echo "dedit-install: staging tree incomplete; skipping package install"
    exit 0
fi

# ── Repository layout ───────────────────────────────────────────────────────
#
# flat repository: pool/ + a Packages index at the top level, referenced by
# `deb [trusted=yes] file:<repo> ./`. `trusted=yes` because there is no keyring
# for a repo that ships with the app — there is nothing to trust on first boot,
# and the .deb is not downloaded, it was just written to this directory.

mkdir -p "$REPO/pool"
rm -f "$REPO"/*.deb "$REPO"/Packages "$REPO"/Packages.gz 2>/dev/null

DEB="$REPO/pool/dedit.deb"

if ! dpkg-deb --build "$SRC" "$DEB" >/dev/null 2>&1; then
    echo "dedit-install: dpkg-deb failed; falling back to a plain install"
    if cp "$SRC/usr/bin/dedit" /usr/local/bin/dedit 2>/dev/null; then
        chmod 0755 /usr/local/bin/dedit
        echo "dedit-install: installed dedit to /usr/local/bin (not a dpkg package)"
        exit 0
    fi
    echo "dedit-install: could not install dedit at all"
    exit 0
fi

# ── Packages index ──────────────────────────────────────────────────────────
#
# Written by hand because dpkg-scanpackages lives in dpkg-dev, which the base
# rootfs does not install. apt needs exactly these fields for a file:// repo.

VERSION=$(dpkg-deb -f "$DEB" Version)
ARCH=$(dpkg-deb -f "$DEB" Architecture)
SIZE=$(wc -c < "$DEB" | tr -d ' ')
SHA=$(sha256sum "$DEB" | cut -d' ' -f1)

{
    echo "Package: dedit"
    echo "Version: $VERSION"
    echo "Architecture: $ARCH"
    echo "Maintainer: Drosh <drosh@localhost>"
    echo "Installed-Size: 4"
    echo "Depends: coreutils"
    echo "Section: utils"
    echo "Priority: optional"
    echo "Description: Open a file in Drosh's native code editor"
    echo " Terminal client for the Drosh built-in editor."
    echo "Filename: pool/dedit.deb"
    echo "Size: $SIZE"
    echo "SHA256: $SHA"
    echo
} > "$REPO/Packages"

gzip -9 -f "$REPO/Packages"

mkdir -p "$SOURCES_DIR"
echo "deb [trusted=yes] file:$REPO ./" > "$SOURCES_DIR/drosh-local.list"

# ── Install ─────────────────────────────────────────────────────────────────

echo "dedit-install: updating apt lists for the local repo..."
if ! apt-get update -qq -o Dir::Etc::sourcelist="$SOURCES_DIR/drosh-local.list" \
    -o Dir::Etc::sourceparts="-" -o APT::Get::List-Cleanup="0" 2>&1 | tail -3; then
    echo "dedit-install: apt-get update failed"
fi

if apt-get install -y --no-install-recommends dedit 2>&1 | tail -5; then
    echo "dedit-install: dedit installed as a package"
else
    echo "dedit-install: apt-get install failed; installing the command directly"
    cp "$SRC/usr/bin/dedit" /usr/local/bin/dedit 2>/dev/null \
        && chmod 0755 /usr/local/bin/dedit \
        && echo "dedit-install: installed dedit to /usr/local/bin (not a dpkg package)"
fi

# Leaving the repo's own apt list behind would make every later `apt-get update`
# fail with "not signed", which is a far more confusing failure than not having
# the repository at all. The command is installed either way.
apt-get update -qq 2>/dev/null || true
rm -f "$SOURCES_DIR/drosh-local.list"
apt-get update -qq 2>/dev/null || true

echo "dedit-install: ok"
exit 0