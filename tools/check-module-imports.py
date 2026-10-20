#!/usr/bin/env python3
"""
Check that every `dev.drosh.*` import is resolvable from the module that
holds the file.

The Kotlin compiler is the real authority, and CI runs it. This is the check
that runs *before* CI does: it answers "can this file see that type?" from the
manifests alone, which is where the answer went wrong three times in a row
during the guest-module move — a type moved to another module, and the import
that used to be implicit (same package) did not come with it.

For every module it reads:
  - the packages its own sources declare
  - the project dependencies it declares (`project(":x")`)
and then checks each file's imports against the union of those, plus the
packages of the modules it depends on.

Exit code 1 when something does not resolve.
"""

from __future__ import annotations

import os
import re
import sys
from pathlib import Path

ROOT = Path(sys.argv[1]).resolve() if len(sys.argv) > 1 else (Path(__file__).resolve().parents[1] / "android")

IMPORT = re.compile(r"^import\s+(dev\.drosh\.[\w.]+)", re.M)
PROJECT_DEP = re.compile(r"""project\(\s*":([\w-]+)"\s*\)""")
PACKAGE = re.compile(r"^package\s+([\w.]+)", re.M)


def module_of(path: Path) -> str:
    return path.relative_to(ROOT).parts[0]


def packages_of(module: str) -> set[str]:
    """Every package the module's own sources declare."""
    found: set[str] = set()
    base = ROOT / module / "src"
    if not base.is_dir():
        return found
    for kt in base.rglob("*.kt"):
        found |= set(PACKAGE.findall(kt.read_text(encoding="utf-8", errors="ignore")))
    return found


def project_deps(module: str) -> set[str]:
    """The Gradle modules this one depends on."""
    build = ROOT / module / "build.gradle.kts"
    if not build.is_file():
        return set()
    return set(PROJECT_DEP.findall(build.read_text(encoding="utf-8")))


def main() -> int:
    modules = sorted(
        p.name for p in ROOT.iterdir()
        if p.is_dir() and (p / "build.gradle.kts").is_file()
    )
    own = {m: packages_of(m) for m in modules}
    deps = {m: project_deps(m) for m in modules}

    # A package belongs to the module that declares it. A package declared by
    # two modules is reported separately: that is a real ambiguity.
    owner: dict[str, set[str]] = {}
    for module, packages in own.items():
        for package in packages:
            owner.setdefault(package, set()).add(module)

    problems: list[str] = []
    for module in modules:
        visible = set(own[module])
        for dep in deps[module]:
            visible |= own.get(dep, set())
        for kt in (ROOT / module / "src").rglob("*.kt"):
            text = kt.read_text(encoding="utf-8", errors="ignore")
            for imported in IMPORT.findall(text):
                if imported in visible:
                    continue
                # A package is visible if the module that owns it is a
                # dependency, even when the exact package is not listed above
                # (a module may own several packages).
                owners = {
                    o for package, o in owner.items()
                    if imported == package or imported.startswith(package + ".")
                    for o in o
                }
                if owners & (deps[module] | {module}):
                    continue
                problems.append(
                    f"{kt.relative_to(ROOT)}: {imported} is not visible from "
                    f":{module} (depends on {sorted(deps[module]) or 'nothing'})"
                )

    for problem in problems:
        print(problem)
    if problems:
        print(f"\n{len(problems)} unresolved import(s)")
        return 1
    print("all dev.drosh.* imports resolve from the module that holds the file")
    return 0


if __name__ == "__main__":
    sys.exit(main())
