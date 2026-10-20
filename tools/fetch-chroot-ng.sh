#!/usr/bin/env bash
# Fetches the chroot-ng guest runtime into the app's native library folder.
#
# The runtime is not in this repository: it is built and published by the
# chroot-ng project, and the APK carries the published asset. The tag pins the
# release; the hash pins the bytes, because the build is not byte-reproducible
# (the debug info differs between runs), so rebuilding it would not give the
# same file. The hash is the one in the release's notes.
#
# The CI does this in both build workflows. A local build that skips it has no
# chroot-ng in the APK, and choosing it in the setup wizard gives a session
# that dies with:
#
#     exec(".../lib/arm64/libchrootng.so"): No such file or directory
#
# — an ENOENT in a log, with nothing in the UI to say what was missing.
#
#   tools/fetch-chroot-ng.sh
#
set -euo pipefail

CHROOT_NG_TAG="v1.2.0"
CHROOT_NG_SHA256="29473e3609c9e7692a3b9ab3613129dbdb46f9a1fa393c52b49fc601d2c7a01e"

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
target_dir="${root}/android/linux/src/main/jniLibs/arm64-v8a"
target="${target_dir}/libchrootng.so"

if [ -f "${target}" ] && echo "${CHROOT_NG_SHA256}  ${target}" | sha256sum -c - >/dev/null 2>&1; then
  echo "libchrootng.so ${CHROOT_NG_TAG} is already in place"
  exit 0
fi

url="https://github.com/mmuhofy/chroot-ng/releases/download/${CHROOT_NG_TAG}/libchrootng.so"
tmp="$(mktemp)"
trap 'rm -f "${tmp}"' EXIT

curl -fsSL --retry 3 --retry-delay 2 -o "${tmp}" "${url}"
echo "${CHROOT_NG_SHA256}  ${tmp}" | sha256sum -c -

mkdir -p "${target_dir}"
cp "${tmp}" "${target}"
# The download is as private as a temporary file is; the APK does not carry
# modes, but the file in the tree should not be unreadable either.
chmod 0644 "${target}"
ls -l "${target_dir}/"
echo "libchrootng.so ${CHROOT_NG_TAG} is in place"
