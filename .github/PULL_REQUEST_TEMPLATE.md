<!--
Drosh pull requests are small, reviewable, and carry their reasoning in the
body. The commit convention and the changelog rules are in CONTRIBUTING.md.
-->

## What this changes

One paragraph: what the user gets, or what breaks less. If it fixes a crash,
name the symptom a user would see.

## Why

The reasoning, in a few sentences. If a documented decision in
`docs/MEMORYBANK.md` is being changed or overturned, say so — the docs move
with the code, in the same PR.

## Checklist

- [ ] `./gradlew assembleDebug` and `testDebugUnitTest` pass locally, or CI
      will say it for you
- [ ] New user-facing strings follow the localization rules in
      `CONTRIBUTING.md` — English and Turkish at minimum, and `tools/i18n.mjs
      check` passes
- [ ] No build output, screenshots, recordings or design mockups in the diff
- [ ] If this is a milestone, `CHANGELOG.md`'s `Unreleased` section is updated
      (user-facing bullets, no hashes, no PR numbers)

## Screenshots

If it is a UI change, a before/after pair. Phone screenshots, in the dark
theme, are the reference.
