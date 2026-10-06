#!/usr/bin/env python3
"""
Find Kotlin symbols that are used but neither imported nor declared locally.

## Why this exists

It exists because it was needed four times in one afternoon. Every one of them
was a *missing* import — `DroshPrimary`, `IconAction`, `DroshSurfaceHigh`,
`Spacer` — and every one of them reached CI as a one-line compile error after a
full round trip of push, queue, build, log-read, fix.

The check that was being run instead does not catch them, and cannot:

    # finds UNUSED imports
    for each import: is its name used in the body?

A missing import is not an unused import. There is nothing to find, so the file
reports clean — and, crucially, it reports clean on a file that does not compile.
That is the trap: a check that says "this is fine" about broken code is worse
than no check, because it is believed.

So this walks the other way: every capitalised identifier used in a file, minus
what is imported, minus what is declared in the file or anywhere else in the
repository. What is left is a symbol nobody can resolve.

## What it is not

Not a compiler. It cannot see generics, scoped receivers, `when` branches,
extension properties, or anything behind a lambda receiver, so it will report
false positives for those. It is scoped deliberately to what actually bit us —
a `Drosh*` token or a Compose layout primitive used without an import — and
prints "no suspicion" rather than pretending to be exhaustive.

## Usage

    python3 tools/find-missing-imports.py            # the branch's changed files
    python3 tools/find-missing-imports.py ui/src/... # specific files
"""

from __future__ import annotations

import os
import re
import subprocess
import sys

# Compose primitives that appear across the codebase. Deliberately not exhaustive:
# a partial list that catches the real cases beats an aspirational one that needs
# maintaining to stay correct.
COMPOSE = set(
    """
    Box Column Row Spacer Text Icon Button Canvas Divider
    LazyColumn LazyRow Field TextField OutlinedTextField IconButton
    Switch Slider Arrangement Alignment PaddingValues IntOffset IntSize
    Offset Dp DpOffset Size Rect animateDpAsState animateColorAsState
    animateFloatAsState remember rememberSaveable mutableStateOf
    mutableIntStateOf mutableFloatStateOf derivedStateOf LaunchedEffect
    DisposableEffect SideEffect Modifier fillMaxSize fillMaxWidth
    fillMaxHeight wrapContentSize size width height padding offset align
    center background clickable combinedClickable pointerInput semantics
    contentDescription clearAndSetSemantics testTag clipToBounds graphicsLayer
    alpha clip shadow border drawBehind zIndex heightIn widthIn defaultMinSize
    aspectRatio matchParentSize weight toPx toDp LocalDensity LocalContext
    LocalConfiguration LocalLifecycleOwner AndroidView Surface Scaffold
    HorizontalDivider VerticalDivider IconAction ActionButton FlatButton
    SectionHeader GlassIconButton DroshDropdownMenu DroshMenuItem
    Drosh Surface MaterialTheme ModalBottomSheet DroshTextCursor
    """.split()
)

# The design-system palette, from design-system/.../DroshColors.kt. Every one is a
# @Composable @ReadOnlyComposable getter, so a use site needs the import even
# though the token reads like a constant.
DROSH = set(
    """
    DroshBackground DroshSurface DroshSurfaceVariant DroshSurfaceLow
    DroshSurfaceHigh DroshSurfaceContainerLowest DroshOutline DroshBorderSubtle
    DroshPrimary DroshOnPrimary DroshText DroshTextSecondary DroshTextMuted
    DroshTextDisabled DroshSuccess DroshError DroshWarning DroshBuild DroshTile
    DroshTileSelected DroshTilePressed DroshTrack DroshIcons DroshTheme
    LocalDroshColors DroshTextMuted
    """.split()
)

DECL_PATTERNS = (
    r"^(?:@\w+(?:\([^)]*\))?\s+)*(?:internal |private |public |expect |actual )*"
    r"(?:suspend |inline |operator |infix )*"
    r"(?:fun|val|var|class|object|enum class|interface|typealias)\s+(\w+)",
    r"^\s*(?:@\w+\s+)*(?:internal |private |public )*(?:const )?val\s+(\w+)\s*[:=]",
    r"^\s*(?:internal |private )?const val\s+(\w+)",
)

SKIP_DIRS = ("/build/", "/.git/", "/.gradle/", "/.recycle/")


def declarations_by_package(root: str = ".") -> dict[str, set[str]]:
    """Declaration names, keyed by the file's own package.

    Keyed by package rather than merged into one set, because the question is
    not "does this name exist anywhere" — every palette token exists in
    DroshColors.kt and would then always pass. It is "can this file resolve it
    *without* an import", and that is true only for the same file or the same
    package. A name from another package needs the import whatever else
    declares it.
    """
    by_package: dict[str, set[str]] = {}
    for dirpath, dirnames, filenames in os.walk(root):
        if any(skip in dirpath for skip in SKIP_DIRS):
            continue
        for name in filenames:
            if not name.endswith(".kt"):
                continue
            path = os.path.join(dirpath, name)
            try:
                with open(path, encoding="utf-8", errors="ignore") as handle:
                    source = handle.read()
            except OSError:
                continue
            match = re.search(r"^package\s+([\w.]+)", source, re.M)
            package = match.group(1) if match else ""
            names = by_package.setdefault(package, set())
            for pattern in DECL_PATTERNS:
                names |= set(re.findall(pattern, source, re.M))
    return by_package


def check(path: str, by_package: dict[str, set[str]]) -> list[str]:
    with open(path, encoding="utf-8", errors="ignore") as handle:
        source = handle.read()

    imported = set(re.findall(r"^import\s+[\w.]+\.(\w+)\s*$", source, re.M))

    package_match = re.search(r"^package\s+([\w.]+)", source, re.M)
    package = package_match.group(1) if package_match else ""

    # Same file, plus same package, plus same module path (an Android library's
    # own generated R and BuildConfig).
    resolvable = set(by_package.get(package, set()))
    resolvable |= set(re.findall(r"^(?:\w+\s+)*R\b", source, re.M))
    for pattern in DECL_PATTERNS:
        resolvable |= set(re.findall(pattern, source, re.M))

    # Block comments are stripped, not just line comments: a symbol named in a
    # KDoc ("the panes are Views inside AndroidView") is not a use, and treating
    # it as one produced a false positive on a file that compiled.
    without_blocks = re.sub(r"/\*.*?\*/", "", source, flags=re.S)
    body = "\n".join(
        line
        for line in without_blocks.split("\n")
        if not line.startswith("import ") and not line.strip().startswith("//")
    )
    used = set(re.findall(r"\b([A-Z][A-Za-z0-9_]{2,})\b", body))

    # Scoped on purpose: every real failure was a `Drosh*` token or a Compose
    # primitive. Reporting every unresolved capital would drown them in noise and
    # the whole thing would get ignored.
    def is_interesting(name: str) -> bool:
        # `Drosh` on its own is not a symbol — it is the first segment of names
        # like `DroshIcons`, and the length guard keeps the package fragment out.
        if name.startswith("Drosh"):
            return len(name) > 5
        # Compose names only, and only when they are actually Compose's: the
        # module has its own `Text` (an ExtraKey data class), its own `Divider`
        # and its own `Surface` tokens, and a bare name cannot tell those apart.
        # Every real failure this tool exists for was a `Drosh*` token, so that
        # is the whole of the interesting set — anything broader drowns it.
        return name in COMPOSE and name not in {"Text", "Divider", "Surface", "Size", "Rect"}

    # One predicate rather than a chained condition: Python binds `and` tighter
    # than `or`, so `A or B and C and D` parses as `(A or B) and C and D` and the
    # import check gets skipped for every name that matched A or B — which made
    # 86 correctly-imported files report as broken.
    suspects = sorted(
        name
        for name in used
        if is_interesting(name) and name not in resolvable and name not in imported
    )
    return suspects


def files_to_check(argv: list[str]) -> list[str]:
    """Explicit paths, or the branch's changed files.

    Changed files rather than the whole tree on purpose: the false positives
    documented above are all pre-existing, so a repo-wide run trains you to
    ignore the output, which is worse than not having the tool.
    """
    if len(argv) > 1:
        return [path for path in argv[1:] if path.endswith(".kt")]

    changed: set[str] = set()
    for ref in ("HEAD", "origin/main...HEAD"):
        try:
            result = subprocess.run(
                ["git", "diff", "--name-only", ref],
                capture_output=True,
                text=True,
                check=True,
            )
            changed |= set(result.stdout.split())
        except subprocess.CalledProcessError:
            continue

    return sorted(path for path in changed if path.endswith(".kt"))


def main() -> int:
    targets = files_to_check(sys.argv)
    if not targets:
        print("no kotlin files to check")
        return 0

    by_package = declarations_by_package()
    problems = 0

    for path in targets:
        suspects = check(path, by_package)
        if suspects:
            problems += 1
            print(f"{path}: {', '.join(suspects)}")

    if problems:
        print(f"\n{problems} file(s) use a symbol with no import and no declaration")
        return 1

    print(f"no missing imports in {len(targets)} file(s)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
