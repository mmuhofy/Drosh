#!/usr/bin/env bash
# Iris Shell reset shell to bash.
#   reset-shell-to-bash.sh — runs INSIDE proot. Called when Oh My Zsh install
#   failed and we need to revert /etc/passwd SHELL from /bin/zsh back to /bin/bash.
#
# Idempotent.

set -euo pipefail

if command -v usermod >/dev/null 2>&1; then
    usermod -s /bin/bash root 2>&1 || true
else
    awk -v new_shell='/bin/bash' -F: '
        BEGIN { OFS = ":" }
        $1 == "root" { $7 = new_shell }
        { print }
    ' /etc/passwd > /etc/passwd.tmp && mv /etc/passwd.tmp /etc/passwd
fi

# Remove zsh from /etc/skel .bashrc auto-exec line so login shells get bash.
SKEL_BASHRC=/etc/skel/.bashrc
if [ -f "$SKEL_BASHRC" ]; then
    sed -i '/exec \/bin\/zsh/d' "$SKEL_BASHRC"
fi

echo "reset-shell-to-bash: ok"
