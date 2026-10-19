#!/usr/bin/env bash
# Iris Shell base-package install.
#   packages-install.sh — runs INSIDE proot. Installs the curated base-pack:
#   zsh, git, curl, ca-certificates, nano, vim, tree.
#
# Additionally installs any packages listed in $IRIS_CUSTOM_PACKAGES
# (comma-separated) — used when the user picks PackageProfile.Custom.
#
# Idempotent: apt-get skips already-installed packages.

set -euo pipefail

export DEBIAN_FRONTEND=noninteractive

echo "packages-install: updating apt lists..."
apt-get update -qq
echo "packages-install: installing base packages..."
apt-get install -y --no-install-recommends \
    zsh \
    git \
    curl \
    ca-certificates \
    nano \
    vim \
    tree

# Install user-custom packages if provided (PackageProfile.Custom).
if [ -n "${IRIS_CUSTOM_PACKAGES:-}" ]; then
    echo "packages-install: installing custom packages: $IRIS_CUSTOM_PACKAGES"
    # shellcheck disable=SC2086  # we want word-splitting on the comma list
    IFS=',' read -ra CUSTOM_PKGS <<< "$IRIS_CUSTOM_PACKAGES"
    apt-get install -y --no-install-recommends "${CUSTOM_PKGS[@]}"
fi

echo "packages-install: ok"
