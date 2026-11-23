#!/usr/bin/env python3
"""Check the Kotlin layout rules that do not need a compiler.

- a file's package must match its directory under src/main|test|androidTest/kotlin
- file names must not use the banned role suffixes
- two source files must not share a basename

This deliberately does not enforce "one public type per file": that needs a
Kotlin parser, and a regex that guesses at declarations would fail on nested
types, companion objects and function-only files. The naming contract lives in
docs/ARCHITECTURE.md; this script checks the mechanical half of it.
"""

from __future__ import annotations

import os
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
ANDROID = ROOT / "android"
BANNED = ("Manager", "Impl", "Helper", "Util", "Wire", "Handler")
PACKAGE = re.compile(r"^package\s+([\w.]+)", re.MULTILINE)


def source_files() -> list[Path]:
    return sorted(
        path
        for path in ANDROID.rglob("*.kt")
        if "build" not in path.parts and ".gradle" not in path.parts
    )


def package_of(path: Path) -> str | None:
    match = PACKAGE.search(path.read_text(encoding="utf-8"))
    return match.group(1) if match else None


def directory_package(path: Path) -> str | None:
    parts = path.parts
    for marker in ("main", "test", "androidTest"):
        if marker not in parts:
            continue
        index = parts.index(marker)
        if index + 2 >= len(parts) or parts[index + 1] not in ("kotlin", "java"):
            continue
        package_parts = parts[index + 2 : -1]
        return ".".join(package_parts) if package_parts else None
    return None


def main() -> int:
    failures: list[str] = []
    seen: dict[str, Path] = {}
    files = source_files()

    for path in files:
        relative = path.relative_to(ROOT).as_posix()
        basename = path.stem
        for suffix in BANNED:
            if basename.endswith(suffix):
                failures.append(f"{relative}: banned file suffix {suffix!r}")
                break

        package = package_of(path)
        if package is None:
            failures.append(f"{relative}: no package declaration")
            continue
        expected = directory_package(path)
        if expected is not None and package != expected:
            failures.append(f"{relative}: package {package!r}, directory expects {expected!r}")

        if basename in seen:
            failures.append(
                f"{relative}: duplicate file name, also used by "
                f"{seen[basename].relative_to(ROOT).as_posix()}"
            )
        else:
            seen[basename] = path

    if failures:
        print("Kotlin layout check failed:", file=sys.stderr)
        for failure in failures:
            print(f"  {failure}", file=sys.stderr)
        return 1

    print(f"Kotlin layout OK ({len(files)} files)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
