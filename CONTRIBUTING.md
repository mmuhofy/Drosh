# Contributing to Drosh

Drosh is a single-developer project with an agent doing much of the typing, so
this file exists mainly to make the *rules* legible — to a future collaborator,
and to an agent that just joined the session. Read `LICENSE.md` first: the
project is split-licensed, and what you may do with a file depends on which
directory it is in.

- The APK and everything under `terminal/` are **GPL-3.0** (Termux-derived).
- The Drosh-authored modules (`app/`, `ui/`, `data/`, `domain/`, `agent/`,
  `ssh/`, `core/`, `design-system/`, `editor/`) are **Apache-2.0**.
- Contributions to the Apache-2.0 modules are accepted under Apache-2.0 (see
  §5 of that license).

## Building

You need JDK 17 and the Android SDK. The app is arm64-only and needs a device
or emulator with that ABI — most of Drosh (PRoot, PTY) does not work on x86_64
emulators.

```bash
./gradlew assembleDebug        # debug APK, arm64-v8a
./gradlew testDebugUnitTest    # the unit test suite
```

CI (`Build Debug`, `Unit Tests`) runs these on every push and PR; the release
workflow runs on tags. No local build is required for a change to be validated.

## Commits

Conventional commits: `type(scope): subject`, where type is one of
`feat|fix|docs|refactor|perf|test|build|ci|chore|revert`.

The subject says what changed; the **body says why**. This is not decoration —
the changelog is curated from git (`@changelog`), and a commit whose body
carries no reasoning is an entry that has to be guessed at later. The good
commits in this repository's history read like short essays; copy them.

A commit that fixes a bug should name the symptom a user would see
(`fix(crash): a reopened chat now shows its transcript`), not the mechanism.

## The changelog

`CHANGELOG.md` is user-facing. It is maintained with the `@changelog` skill,
either at the end of a milestone or before a release. The rules in short:

- Draft from the git range since the last changelog touch — **not from memory**
- Include what a user can see or hit; exclude refactors, dependency bumps and
  abandoned work
- No commit hashes, authors or PR numbers in the file
- The draft is shown for review before it is written; nothing lands unapproved

## Versioning & releases

Semantic versioning, currently on the pre-1.0 `0.x` line: a `0.x.0` minor bump
for anything a user would call a milestone, patch for fixes.

`versionCode` is derived from the commit count by
`AndroidApplicationConventionPlugin` (10,000 + commits) and cannot be set by
hand per release — it exists to keep an old build from installing over a new
one, which is how the Room database got downgraded out from under a running
app once. Do not bypass it.

To cut a release:

1. Update `CHANGELOG.md`: move `## [Unreleased]` to `## [X.Y.Z] — YYYY-MM-DD`
   and set the compare link to the previous version.
2. Commit, review, then `git tag vX.Y.Z && git push origin main --tags`.
3. CI builds the release APK with the tag's version (`-PversionName`) and
   creates the GitHub Release from the CHANGELOG section for that version —
   the release notes and the changelog are the same text, by design.
4. Install the release APK on a device and check the version in Settings
   before announcing it. **Nothing in this project is considered verified
   until it has run on a real device.**

Release builds are signed with the debug key committed to the repository (a
deliberate single-identity choice, see README). That is recorded as a known
issue, not a secret.

## Hygiene

- No build output, screenshots, screen recordings or design junk in commits.
  Build artifacts are ignored; prototypes live in the design vault, not here.
- New files need no license header — `LICENSE.md` is the single source of
  truth. A new third-party dependency needs a row in `LICENSE.md` (and a
  `NOTICE.md` next to the module, if it is a library).

## Documentation

`docs/` is the reference layer: `MEMORYBANK.md` holds decisions and invariants,
`docs/AGENT.md` holds the rules for working in the codebase. If a change
contradicts a documented decision, the documentation moves with the code —
same commit, not later.
