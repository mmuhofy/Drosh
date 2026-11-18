#!/usr/bin/env bash
# Drosh rootfs configure.
#   rootfs-configure.sh — runs INSIDE the chroot against Ubuntu 24.04 rootfs.
#
# Writes:
#   /etc/resolv.conf, /etc/hostname, /etc/hosts
#   /etc/apt/sources.list (ports.ubuntu.com for arm64/armhf)
#   /home stubs + minimal .bashrc / .bash_profile
#
# Bash is the only guest shell. The final prompt is written by bashrc-write.sh
# later in the same pipeline; this only makes the rootfs usable.
#
# Idempotent: safe to re-run.

set -euo pipefail

cat > /etc/resolv.conf <<'RESOLV'
nameserver 8.8.8.8
nameserver 8.8.4.4
RESOLV

echo "iris-shell" > /etc/hostname

cat > /etc/hosts <<'HOSTS'
127.0.0.1 localhost iris-shell
::1 localhost ip6-localhost ip6-loopback
HOSTS

# Drop base-image-specific sources file written by newer Ubuntu images
# (noble+ uses *.sources files; we ship classic .list for arm64 ports).
rm -f /etc/apt/sources.list.d/ubuntu.sources
cat > /etc/apt/sources.list <<'SOURCES'
deb http://ports.ubuntu.com/ubuntu-ports noble main restricted universe multiverse
deb http://ports.ubuntu.com/ubuntu-ports noble-updates main restricted universe multiverse
deb http://ports.ubuntu.com/ubuntu-ports noble-security main restricted universe multiverse
SOURCES

mkdir -p /home /root /tmp

# Minimal bashrc — script compatibility. Will be created in /etc/skel so any
# later user copies inherit it.
cat > /etc/skel/.bashrc <<'BASHRC'
export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
export HOME=/home
export TERM=xterm-256color
export LANG=C.UTF-8
export TMPDIR=/tmp
alias ll='ls -la'
alias la='ls -A'
alias l='ls -CF'

# Minimal bash prompt — bashrc-write.sh replaces this after the rootfs is
# configured, but the shell must already be usable if setup stops here.
PS1='\[\033[1;33m\]drosh\[\033[0m\]:\[\033[1;34m\]\w\[\033[0m\]\$ '
BASHRC

cat > /etc/skel/.bash_profile <<'BASHP'
if [ -f ~/.bashrc ]; then
    . ~/.bashrc
fi
BASHP

# Add host (Android) group IDs to /etc/group so the shell doesn't print
# "groups: Cannot find name for group ID XXXX" warnings at startup.
# The guest inherits Android GIDs (e.g. 3003=inet, 9997=net_bt_admin) which
# have no entry in the Ubuntu rootfs.
for gid in $(id -G); do
    if ! getent group "$gid" >/dev/null 2>&1; then
        echo "android_gid_${gid}:x:${gid}:" >> /etc/group
    fi
done

echo "rootfs-configure: ok"
