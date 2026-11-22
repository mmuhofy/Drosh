#!/usr/bin/env bash
# Summarise a Drosh benchmark.log file into a readable report.
#
# Usage:
#   tools/benchmark-report.sh /path/to/benchmark.log
set -euo pipefail

if [[ $# -ne 1 || ! -f "$1" ]]; then
    echo "usage: $0 /path/to/benchmark.log" >&2
    exit 2
fi

file="$1"
sample=$(grep '^frames=' "$file" | tail -n 1 || true)

if [[ -z "$sample" ]]; then
    echo "no benchmark samples in $file" >&2
    exit 1
fi

echo "Drosh benchmark report"
echo "file: $file"
echo "bytes: $(wc -c < "$file")"
echo
echo "latest cumulative sample:"
echo "$sample" | tr ' ' '\n' | sed 's/^/  /'
echo
echo "header lines:"
grep '^#' "$file" | sed 's/^/  /'
