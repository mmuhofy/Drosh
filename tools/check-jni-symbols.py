#!/usr/bin/env python3
"""Checks that every JNI entry point answers to the name Kotlin will call.

The JNI symbol for a Kotlin `external fun` is derived from the *fully
qualified* class it lives in: the package with dots turned into underscores,
then the class, then the function. Move a Kotlin class and every native
function it calls stops resolving — with an `UnsatisfiedLinkError` at the
first call, on a device, and with nothing in the build to say so.

That is not hypothetical: the engine's `NativeCore` moved from
`dev.drosh.engine.core` to `dev.drosh.terminal.engine.core` when the engine
and the sessions became one module, and the Rust side kept the old name. The
whole JNI surface was dead from that moment, and the build was green
throughout — the Rust compiles, the Kotlin compiles, and the two are wired
together by a name that no compiler checks.

So this reads both sides and compares them:

  - the Kotlin side: every `external fun` of the classes that declare one,
    with the symbol name Kotlin will look for;
  - the native side: every `Java_...` entry point, whether it is written in
    Rust (`drosh-jni`) or in C (the vendored Termux JNI).

Every Kotlin function must have a native function of the same name: a missing
one is an `UnsatisfiedLinkError` the first time it is called, on a device, and
nothing in the build says so.

A native function with no Kotlin caller is the other way round — dead code,
which costs a little binary and nothing else. It is reported, not failed on:
the vendored Termux JNI carries one such function today, and Termux is on its
way out rather than a thing to tidy up in passing.

Usage: python3 tools/check-jni-symbols.py
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
ANDROID_DIR = REPO_ROOT / "android"
RUST_DIR = REPO_ROOT / "rust"

# `external fun name(...)` — the declaration's name is what matters.
EXTERNAL_FUN = re.compile(r"^\s*external\s+fun\s+([A-Za-z_][A-Za-z0-9_]*)", re.MULTILINE)
PACKAGE = re.compile(r"^\s*package\s+([A-Za-z0-9_.]+)", re.MULTILINE)
# A Kotlin `object` or `class` header; the class name is what JNI mangles.
CLASS = re.compile(r"^\s*(?:internal\s+|public\s+|private\s+|abstract\s+|open\s+)*"
                   r"(?:object|class)\s+([A-Za-z_][A-Za-z0-9_]*)", re.MULTILINE)
# The Rust entry points.
RUST_FN = re.compile(r"pub\s+extern\s+\"system\"\s+fn\s+(Java_[A-Za-z0-9_]+)")
# The C entry points: `JNIEXPORT ... JNICALL Java_...`.
C_FN = re.compile(r"Java_[A-Za-z0-9_]+\s*\(")


def kotlin_symbols() -> dict[str, str]:
    """Every Kotlin `external fun`, mapped to the file it is declared in."""
    found: dict[str, str] = {}
    for path in sorted(ANDROID_DIR.rglob("*.kt")):
        if "/build/" in str(path):
            continue
        text = path.read_text(encoding="utf-8")
        if "external fun" not in text:
            continue

        package = PACKAGE.search(text)
        if package is None:
            continue
        package_name = package.group(1)

        # The class the functions live in. A file may declare more than one,
        # and the functions belong to the class they are written in — the one
        # whose header precedes them.
        current_class = None
        class_spans: list[tuple[int, str]] = []
        for match in CLASS.finditer(text):
            class_spans.append((match.start(), match.group(1)))

        for match in EXTERNAL_FUN.finditer(text):
            owner = None
            for start, name in class_spans:
                if start < match.start():
                    owner = name
            if owner is None:
                continue
            symbol = f"Java_{package_name.replace('.', '_')}_{owner}_{match.group(1)}"
            found[symbol] = str(path.relative_to(REPO_ROOT))
    return found


def rust_symbols() -> dict[str, str]:
    """Every Rust JNI entry point, mapped to the file it is declared in."""
    found: dict[str, str] = {}
    for path in sorted(RUST_DIR.rglob("*.rs")):
        if "/target/" in str(path):
            continue
        text = path.read_text(encoding="utf-8")
        for match in RUST_FN.finditer(text):
            found[match.group(1)] = str(path.relative_to(REPO_ROOT))
    return found


def c_symbols() -> dict[str, str]:
    """Every C JNI entry point, mapped to the file it is declared in.

    The vendored Termux JNI is C, and it is a separate native library — so
    the two sides of the boundary are not only Rust and Kotlin.
    """
    found: dict[str, str] = {}
    for path in sorted(ANDROID_DIR.rglob("*.c")):
        if "/build/" in str(path):
            continue
        text = path.read_text(encoding="utf-8")
        for match in C_FN.finditer(text):
            symbol = match.group(0)[:-1]
            found[symbol] = str(path.relative_to(REPO_ROOT))
    return found


def main() -> int:
    kotlin = kotlin_symbols()
    rust = rust_symbols()
    native = {**rust, **c_symbols()}

    if not kotlin:
        print("no Kotlin `external fun` found — has the engine's JNI surface moved?")
        return 1
    if not native:
        print("no native entry point found — has the engine's JNI surface moved?")
        return 1

    missing_in_native = sorted(set(kotlin) - set(native))
    missing_in_kotlin = sorted(set(native) - set(kotlin))

    failures = 0

    for symbol in missing_in_native:
        print(f"  MISSING in native code: {symbol}")
        print(f"      declared in {kotlin[symbol]}")
        failures += 1

    if failures:
        print()
        print(f"{failures} JNI symbol(s) do not line up.")
        print("A Kotlin `external fun` resolves by its fully qualified name, so a")
        print("moved class or a renamed function is a link error on a device and")
        print("nothing in the build.")
        return 1

    for symbol in missing_in_kotlin:
        print(f"  no Kotlin caller (dead native code): {symbol}")
        print(f"      declared in {native[symbol]}")

    print(f"{len(native)} JNI symbol(s) line up with their Kotlin declarations.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
