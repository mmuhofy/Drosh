#!/usr/bin/env bash
# Drosh guest setup — the one script streamed into the Ubuntu rootfs.
#   setup.sh — runs INSIDE the guest via `bash -s` (see UbuntuBootstrap).
#
# Everything the terminal needs, in order: DNS and hosts, apt sources, the
# bash prompt, a deb-cache purge and the completion marker. No package
# installs, no extra shells — bash is the only guest shell.
#
# Idempotent: every file is rewritten with a fixed value and cleaning an
# already-empty cache is harmless, so re-running is safe.

set -euo pipefail

IRIS_USERNAME="${IRIS_USERNAME:-user}"

echo "setup: writing network configuration..."
mkdir -p /etc/apt /root /tmp

# The cloud rootfs ships a dangling resolv.conf symlink; replace it outright
# so a real regular file exists and the write below can never fail on it.
rm -f /etc/resolv.conf
cat > /etc/resolv.conf <<'RESOLV'
nameserver 8.8.8.8
nameserver 8.8.4.4
RESOLV

echo "iris-shell" > /etc/hostname

cat > /etc/hosts <<'HOSTS'
127.0.0.1 localhost iris-shell
::1 localhost ip6-localhost ip6-loopback
HOSTS

# Newer Ubuntu images ship a deb822 sources file; remove it so the classic
# .list below is the only source apt sees on the ports architectures.
rm -f /etc/apt/sources.list.d/ubuntu.sources
cat > /etc/apt/sources.list <<'SOURCES'
deb http://ports.ubuntu.com/ubuntu-ports noble main restricted universe multiverse
deb http://ports.ubuntu.com/ubuntu-ports noble-updates main restricted universe multiverse
deb http://ports.ubuntu.com/ubuntu-ports noble-security main restricted universe multiverse
SOURCES

echo "setup: writing bash configuration for ${IRIS_USERNAME}..."
write_bashrc() {
    local target="$1"
    cat > "$target" <<BASHRC
# ─── Drosh .bashrc ───────────────────────────────────────────
export PS1="${IRIS_USERNAME}@drosh:\\w\\$ "
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
BASHRC
}

# /root always exists in the rootfs; /home may not. Only .bashrc is written —
# no profile or install scripts are part of terminal setup.
write_bashrc /root/.bashrc
if [ -d /home ]; then
    write_bashrc /home/.bashrc
fi

echo "setup: writing completion marker..."
MARKER_DIR=/var/lib/drosh
MARKER_FILE="$MARKER_DIR/.setup_complete"
mkdir -p "$MARKER_DIR"
# Record the install signature so the gate can tell a finished guest from
# one whose flag was written early.
{
    echo "drosh_setup_version=1"
    echo "completed_at=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
    echo "abi=${ABI:-unknown}"
} > "$MARKER_FILE"

echo "setup: ok"
