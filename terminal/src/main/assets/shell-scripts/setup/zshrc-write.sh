#!/usr/bin/env bash
# Iris Shell final zshrc writer.
#   zshrc-write.sh — runs INSIDE proot. Writes the full Oh My Zsh config to
#   /root/.zshrc (and /home/.zshrc if /home exists).
#
# Idempotent: overwrites the file every time, but no destructive ops.

set -euo pipefail

d='$'

write_zshrc() {
    local target="$1"
    cat > "$target" <<ZSHRC
export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
export HOME=/home
export TERM=xterm-256color
export LANG=C.UTF-8
export TMPDIR=/tmp

# ─── Oh My Zsh ──────────────────────────────────────────
export ZSH="${d}HOME/.oh-my-zsh"
ZSH_THEME="agnoster"

# ─── Plugins ────────────────────────────────────────────
plugins=(
    git
    zsh-autosuggestions
    zsh-syntax-highlighting
    history
    aliases
)

source ${d}ZSH/oh-my-zsh.sh

# ─── History ────────────────────────────────────────────
HISTSIZE=5000
HISTFILESIZE=10000
HISTTIMEFORMAT="%F %T "
setopt SHARE_HISTORY HIST_IGNORE_DUPS HIST_IGNORE_SPACE

# ─── Aliases ────────────────────────────────────────────
alias ll='ls -la'
alias la='ls -A'
alias l='ls -CF'
alias ..='cd ..'
alias ...='cd ../..'
alias grep='grep --color=auto'
alias df='df -h'
alias du='du -h'

# ─── Welcome ────────────────────────────────────────────
if [[ -z "${d}IRIS_WELCOME_SHOWN" ]]; then
    export IRIS_WELCOME_SHOWN=1
    echo ""
    echo "  ╔══════════════════════════════════════════╗"
    echo "  ║        Welcome to Drosh v1.0           ║"
    echo "  ║     Your phone is a Unix machine.        ║"
    echo "  ╚══════════════════════════════════════════╝"
    echo ""
fi
ZSHRC
}

# Write to both /root and /home — covers interactive non-login login shells.
if [ -d /root ]; then
    write_zshrc /root/.zshrc
fi
if [ -d /home ]; then
    write_zshrc /home/.zshrc
fi
if [ -d /etc/skel ]; then
    write_zshrc /etc/skel/.zshrc
fi

echo "zshrc-write: ok"
