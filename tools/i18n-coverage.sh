#!/usr/bin/env bash
# i18n-coverage.sh — what is translated, what is not, and the full key manifest.
#
#   tools/i18n-coverage.sh              summary per language
#   tools/i18n-coverage.sh --manifest   every key, its English value, per-locale state
#   tools/i18n-coverage.sh --check      exit 1 if any key is missing in Turkish (CI gate)
#
# The manifest is a derived document — regenerate it, never hand-edit it.
set -euo pipefail
cd "$(git rev-parse --show-toplevel)"

# Modules that hold string resources, as "name:res dir". A module is here only
# if it has a values/ directory with string resources.
mods=()
for dir in app ui terminal design-system core data domain agent ssh editor; do
    res="$dir/src/main/res"
    [ -d "$res/values" ] && mods+=("$dir:$res")
done

# Files that carry strings, per module: everything in values/ that contains
# <string or <plurals. colors.xml and themes.xml are skipped by the same test.
files_for() { # $1 = values dir
    local d="$1" f base
    for f in "$d"/*.xml; do
        [ -e "$f" ] || continue
        base=$(basename "$f")
        grep -qE '<(string|plurals)[^>]*name=' "$f" && echo "$base"
    done
}

locales=(tr de es pt ru zh ja ar fil)
files=()
for entry in "${mods[@]}"; do
    res="${entry#*:}"
    while IFS= read -r f; do files+=("$res|$f"); done < <(files_for "$res/values")
done

keys_in() { grep -oP '<(string|plurals)[^>]*name="\K[^"]+' "$1" 2>/dev/null | sort -u; }

case "${1:-}" in
--manifest)
    echo '# Translation manifest'
    echo
    echo 'Regenerate with `tools/i18n-coverage.sh --manifest`. Do not hand-edit.'
    echo
    for entry in "${mods[@]}"; do
        name="${entry%%:*}"; res="${entry#*:}"
        for f in $(files_for "$res/values"); do
            section_keys=$(keys_in "$res/values/$f")
            [ -n "$section_keys" ] || continue
            echo "## $name — $f"
            echo
            header='| Key | English |'
            sep='|---|---|'
            for l in "${locales[@]}"; do header="$header $l |"; sep="$sep:-:|"; done
            echo "$header"
            echo "$sep"
            while IFS= read -r key; do
                en=$(grep -A1 "name=\"$key\"" "$res/values/$f" | grep -oP '(?<=>)[^<]+' | head -1 | sed 's/|/\\|/g; s/[[:space:]]*$//')
                row="| \`$key\` | $en |"
                for l in "${locales[@]}"; do
                    if grep -q "name=\"$key\"" "$res/values-$l/$f" 2>/dev/null; then
                        row="$row ✓ |"
                    else
                        row="$row **✗** |"
                    fi
                done
                echo "$row"
            done <<< "$section_keys"
            echo
        done
    done
    ;;
--check)
    fail=0
    for entry in "${mods[@]}"; do
        res="${entry#*:}"
        for f in $(files_for "$res/values"); do
            while IFS= read -r key; do
                grep -q "name=\"$key\"" "$res/values-tr/$f" 2>/dev/null || {
                    echo "missing in tr: $res $f $key"; fail=1; }
            done < <(keys_in "$res/values/$f")
        done
    done
    [ "$fail" = 0 ] && echo "OK — every key has a Turkish translation" || exit 1
    ;;
*)
    total=0
    for entry in "${mods[@]}"; do
        res="${entry#*:}"
        for f in $(files_for "$res/values"); do
            n=$(keys_in "$res/values/$f" | wc -l)
            total=$((total + n))
        done
    done
    printf 'Keys total: %d\n\n' "$total"
    printf '%-6s %10s %8s\n' LOCALE OK MISSING
    for l in "${locales[@]}"; do
        ok=0
        for entry in "${mods[@]}"; do
            res="${entry#*:}"
            for f in $(files_for "$res/values"); do
                while IFS= read -r key; do
                    grep -q "name=\"$key\"" "$res/values-$l/$f" 2>/dev/null && ok=$((ok + 1))
                done < <(keys_in "$res/values/$f")
            done
        done
        printf '%-6s %10d %8d\n' "$l" "$ok" "$((total - ok))"
    done
    echo
    echo "fr: no values-fr/ — the picker entry is a lie (see docs/TRANSLATION-MANIFEST.md)"
    ;;
esac
