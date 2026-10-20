#!/usr/bin/env bash
# Iris Shell final bashrc writer.
#   bashrc-write.sh — runs INSIDE proot. Writes the shell prompt config to
#   /root/.bashrc (and /home/.bashrc if /home exists).
#
# Idempotent: overwrites the file every time, but no destructive ops.

set -euo pipefail

IRIS_USERNAME="${IRIS_USERNAME:-user}"

write_bashrc() {
    local target="$1"
    cat > "$target" <<BASHRC
# ─── Iris Shell .bashrc ──────────────────────────────────────
export PS1="${IRIS_USERNAME}@iris:\\w\\$ "
export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
export HOME=/home
export TERM=xterm-256color
export LANG=C.UTF-8
export TMPDIR=/tmp

# ─── Aliases ──────────────────────────────────────────────
alias ll='ls -la'
alias la='ls -A'
alias l='ls -CF'
alias ..='cd ..'
alias ...='cd ../..'
alias grep='grep --color=auto'
alias df='df -h'
alias du='du -h'

# ─── History ──────────────────────────────────────────────
HISTSIZE=5000
HISTFILESIZE=10000
HISTCONTROL=ignoreboth
shopt -s histappend histreedit histverify

# ─── Welcome ──────────────────────────────────────────────
if [ -z "\${IRIS_WELCOME_SHOWN}" ]; then
    export IRIS_WELCOME_SHOWN=1
    echo ""
    echo "  ╔══════════════════════════════════════════╗"
    echo "  ║        Welcome to Drosh v1.0           ║"
    echo "  ║     Your phone is a Unix machine.        ║"
    echo "  ╚══════════════════════════════════════════╝"
    echo ""
fi
BASHRC
}

write_bash_profile() {
    local dir="$1"
    if [ -f "$dir/.bash_profile" ]; then return 0; fi
    cat > "$dir/.bash_profile" <<'PROFILE'
if [ -f ~/.bashrc ]; then
    . ~/.bashrc
fi
PROFILE
}

write_profile() {
    local dir="$1"
    if [ -f "$dir/.profile" ]; then return 0; fi
    cat > "$dir/.profile" <<'PROFILE'
if [ -f ~/.bashrc ]; then
    . ~/.bashrc
fi
PROFILE
}

if [ -d /root ]; then
    write_bashrc /root/.bashrc
    write_bash_profile /root
fi
if [ -d /home ]; then
    write_bashrc /home/.bashrc
    write_bash_profile /home
    write_profile /home
fi
if [ -d /etc/skel ]; then
    write_bashrc /etc/skel/.bashrc
    write_bash_profile /etc/skel
    write_profile /etc/skel
fi

echo "bashrc-write: ok"
