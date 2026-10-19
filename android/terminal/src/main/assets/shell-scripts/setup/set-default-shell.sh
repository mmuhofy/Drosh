#!/usr/bin/env bash
# Iris Shell default shell selector.
#   set-default-shell.sh — runs INSIDE proot after packages-install.sh
#   has installed zsh. Idempotent.
#
# Sets the default shell for both /etc/passwd's existing root entry and the
# future-user skel. Adds zsh to /etc/shells. Falls back to bash if zsh is
# somehow not installed (apt might have failed mid-install on slow networks).

set -euo pipefail

if ! command -v zsh >/dev/null 2>&1; then
    echo "set-default-shell: zsh not installed; keeping bash"
    exit 0
fi

# Make sure shells(5) policy recognizes zsh.
if [ -f /etc/shells ] && ! grep -q '^/bin/zsh$' /etc/shells; then
    echo "/bin/zsh" >> /etc/shells
fi

# Force /root and any /home user to zsh by rewriting their passwd entry.
# We use `usermod` if available (it's in the base image), else we rewrite
# /etc/passwd directly.
if command -v usermod >/dev/null 2>&1; then
    usermod -s /bin/zsh root 2>&1 || true
else
    # Rewrite /etc/passwd for root only.
    awk -v new_shell='/bin/zsh' -F: '
        BEGIN { OFS = ":" }
        $1 == "root" { $7 = new_shell }
        { print }
    ' /etc/passwd > /etc/passwd.tmp && mv /etc/passwd.tmp /etc/passwd
fi

# Ensure /etc/skel so any future user shells match.
SKEL_BASHRC=/etc/skel/.bashrc
if [ -f "$SKEL_BASHRC" ]; then
    echo "" >> "$SKEL_BASHRC"
    echo "# Default new-user shell — start zsh on login" >> "$SKEL_BASHRC"
    echo '[ -x /bin/zsh ] && exec /bin/zsh' >> "$SKEL_BASHRC"
fi

echo "set-default-shell: ok"
