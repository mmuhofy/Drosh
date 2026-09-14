# Drosh

> Your phone is a Unix machine. Finally.

Drosh is a ground-up reimagination of what a mobile terminal should be:
agent-native, semantically aware, and built for the way people actually use
their phones. Not a Termux fork — a modern terminal environment where the
agent and the shell are the same thing.

## Status

🚧 **Phase 1 — Terminal Core** (in progress)

| Phase | Status |
|-------|--------|
| 1. Terminal Core | 🚧 Project setup |
| 2. UI & Session System | ⬜ |
| 3. Input System | ⬜ |
| 4. SSH & Remote | ⬜ |
| 5. Safety, Polish & Distribution | ⬜ |
| 6. Agent Intelligence | ⬜ |

## Stack

| Component | Technology |
|-----------|------------|
| Language | Kotlin 2.3.20 |
| UI | Jetpack Compose BOM 2026.04.01 |
| Architecture | MVVM + Clean Architecture |
| DI | Hilt 2.57 |
| Local DB | Room 2.8.4 + FTS5 |
| SSH | SSHJ 0.39.x |
| Terminal Engine | termux-view + termux-terminal-emulator (vendored from Termux) |
| Linux Env | PRoot 5.2.0 + Ubuntu 24.04 rootfs |
| Min SDK | 26 (Android 8.0+) |
| Target SDK | 36 (Android 16) |

## Architecture

```
ui/            → Compose screens, ViewModels
domain/        → Use cases, repository interfaces (pure Kotlin)
data/          → Repository implementations, Room, LLM, SSHJ client
agent/         → AgentLoop, ToolRegistry, MultiStepStreamer
terminal/      → PTY engine, block renderer, semantic parser
ssh/           → SSHJ manager, Key Vault
core/          → Shared utilities, extensions, constants
design-system/ → Compose theme, components, typography
build-logic/   → Gradle convention plugins
```

Strict layering — see `docs/AGENT.md` for the rules.

## Build

```bash
./gradlew assembleDebug
./gradlew spotlessCheck
./gradlew testDebugUnitTest
```

## License

GPLv3 — see [LICENSE](LICENSE).

This project uses code from [Termux](https://github.com/termux), which is
licensed under the GNU General Public License v3.0. As required by the terms
of that license, Drosh is also licensed under GPLv3 to ensure license
compatibility and to preserve the same freedoms for all users.
