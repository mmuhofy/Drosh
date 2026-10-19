#!/usr/bin/env bash
# Iris Shell Oh My Zsh + plugins install.
#   omz-install.sh — runs INSIDE proot.
#
# Idempotent: each step skips if already present.
# Plugin list mirrors MEMORYBANK.md §7 — Terminal Core (zsh-autosuggestions,
# zsh-syntax-highlighting).
#
# If git clone fails for any reason, the script falls back to writing a basic
# .zshrc via the configure step's template (caller decides policy).

set -euo pipefail

OMZ_DIR=/home/.oh-my-zsh
PLUGIN_DIR="$OMZ_DIR/custom/plugins"

mkdir -p "$OMZ_DIR"
mkdir -p "$PLUGIN_DIR"

# Omz itself.
if [ ! -d "$OMZ_DIR/.git" ]; then
    echo "omz-install: cloning ohmyzsh..."
    git clone --depth=1 https://github.com/ohmyzsh/ohmyzsh.git "$OMZ_DIR"
fi

# Plugins — independent clones; tolerate failure.
clone_plugin() {
    local name="$1"
    local url="$2"
    if [ -d "$PLUGIN_DIR/$name/.git" ]; then
        echo "omz-install: $name already installed, skipping"
        return 0
    fi
    echo "omz-install: cloning $name..."
    if ! git clone --depth=1 "$url" "$PLUGIN_DIR/$name" 2>&1; then
        echo "omz-install: WARN $name clone failed; continuing" >&2
        return 0
    fi
}

clone_plugin zsh-autosuggestions https://github.com/zsh-users/zsh-autosuggestions
clone_plugin zsh-syntax-highlighting https://github.com/zsh-users/zsh-syntax-highlighting.git

# Verify at minimum that Omz exists — caller may take over .zshrc writing.
if [ -d "$OMZ_DIR/.git" ]; then
    echo "omz-install: ok"
else
    echo "omz-install: ERROR primary clones did not succeed" >&2
    exit 1
fi
