---
name: Changelog
description: Write or update Drosh's CHANGELOG.md — curate the git range since the last changelog touch into user-facing release notes, or cut a release. Use when the user says "changelog", "release notes", "release hazırla", or when a feature milestone lands.
---

Curate Drosh's CHANGELOG.md. The file is user-facing: a reader should learn
what changed *for them*, not read a commit list.

## Source of truth — never write from memory

The working context may be fresh or days old; git is always current. Start by
finding what happened since the last changelog touch:

```bash
LAST=$(git log -1 --format=%H -- CHANGELOG.md)
git log --format='%h %s%n%b' "$LAST"..HEAD
```

Read **bodies**, not just subjects — this project's commit bodies carry the
reasoning and the "why", which is exactly the changelog fuel. If the range is
empty, say so and stop; do not invent entries.

## What goes in

- Features a user can see or use
- Behaviour changes a user would notice
- Bug fixes a user could have hit
- Risky internal changes worth knowing (a Room migration, a versioning rule)

## What stays out

- Pure refactors, comment/typo fixes, dependency bumps, CI and build plumbing
- Work that was explored and abandoned — only shipped behaviour is news
- Anything you cannot point to a commit for

## Format

`## [Unreleased]` at the top, then `Added` / `Changed` / `Fixed`, optionally
`Removed`, `Security`, `Known issues`. Inside Added, group by area with a bold
one-line lead (`**Agent**`, `**Terminal**`, …). Bullets start with the change,
not the commit: "Live shell output streams into the chat", never
"fix(agent): stream output". No hashes, no authors, no PR numbers — git has
those. Keep `Known issues` honest and current; it is the section that makes the
whole file trustworthy.

## Update workflow

1. Run the git range above.
2. Curate: drop the noise, group the rest, write user-facing bullets.
3. **Show the draft in the conversation and wait for approval.** The file is
   only written after the user says yes. If a commit is ambiguous, ask rather
   than guess.
4. On approval, edit `CHANGELOG.md`, commit it as `docs(changelog): …`.

Do not update after every commit — too noisy and the curation is the value.
Update at feature milestones, at the end of a session, or when the user asks.

## Release workflow

1. Review `## [Unreleased]` with the user; fill gaps from the full git range.
2. Agree the version (semver; this project is pre-1.0, see the release policy
   in `docs/`).
3. Rename the section to `## [X.Y.Z] — YYYY-MM-DD`, set the compare link at
   the bottom to the previous version.
4. Commit, then `git tag vX.Y.Z` and push both.
5. The GitHub Release notes are the **same text** as the version section —
   `release.yml` builds the APK from the tag; pass the version through
   `-PversionName=${TAG#v}` so the APK matches the tag.

## Hard rules

- Never invent a feature or a fix that is not in the git range.
- Never leave a hash, author or PR number in the published file.
- Never write the file without showing the draft first.
- One voice: user-facing, present tense, no commit-message phrasing.
