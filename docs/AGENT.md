_Last updated: 2026-07-16_

---

## ⚠️ ANTI-HALLUCINATION PROTOCOL (HIGHEST PRIORITY)

These rules override everything else without exception.

- **NEVER invent API names, method signatures, class names, or library features.**
  If you are not certain a method exists in the exact version being used, say so explicitly.
- **NEVER assume a dependency version is compatible.**
  Always reference the exact version from `gradle/libs.versions.toml`.
- **If you don't know something, say "I don't know" or "I need to verify this."**
  Do not fill gaps with plausible-sounding fabrications.
- **When referencing Jetpack Compose or any Jetpack API:** always explicitly state the target version. If uncertain about a class or method existing in that version, flag it.
- **When referencing external APIs (Gemini, SSHJ, Tavily, etc.):** always explicitly state the API version/endpoint. If uncertain, flag it and ask Muhofy to confirm via official docs.
- **Code that has not been tested must be labeled:**
  `// UNTESTED — verify before use` on any non-trivial logic block.
- **Do not silently rename or refactor existing code** unless explicitly asked.
  Muhofy's existing code is canonical.
- **Never reconstruct a file from memory.** If the file has not been provided in this session, stop and request it.

---

## 🔍 INSPIRATION-FIRST PROTOCOL (CRITICAL SYSTEMS)

For any critical or complex system, **never write from scratch without first studying real-world reference implementations.**

### What counts as a critical system:
- PTY / terminal emulator implementation
- Agent tool use loop / streaming response handling
- SSH client integration (SSHJ)
- Block-based output engine
- Semantic parser / output intelligence
- Ghost text / autocomplete engine
- Session replay / recording system
- Live Share protocol
- Any IPC / socket mechanism
- WorkManager-based scheduler (cron, agent watch)

### Process:
1. **Identify the best open-source reference** for the system being built.

   Default references for this project:
   | System | Reference |
   |--------|-----------|
   | PTY / Terminal | `termux/termux-app` (GitHub) |
   | Agent loop / tool use | `anomalyco/opencode` — `packages/opencode/src/` |
   | SSH client | `hierynomus/sshj` — examples + integration tests |
   | Block output | `warp-terminal/warp` — conceptual reference |
   | Iris Code port | `mmuhofy/IrisCode` — primary reference for ported modules |

2. **Ask Muhofy for the specific raw file URL** from the reference repo, OR fetch it directly if the file path is known and publicly accessible.
3. **Read and study the reference** before writing any code.
4. **Adapt and translate** to Kotlin/Android — do not copy, translate with understanding.
5. **Document the inspiration source** in a comment at the top of the file:
   ```kotlin
   // Inspired by: github.com/termux/termux-app/...
   // Adapted for Drosh — dev.drosh
   ```

### What does NOT need a reference:
- Compose UI screens, layouts, animations
- ViewModels, Use Cases, Repository pattern boilerplate
- Room entities / DAOs
- Standard Android navigation, theming, settings screens
- HUD widgets, shortcut overlay UI
- Session list, session preview UI

---

## 🌿 GIT RULES

- **Never output a commit message for Muhofy to run manually.**
- **Always commit and push directly** using available git tools after every confirmed implementation.

### Commit Format
```
<type>(<scope>): <short description>
```

| Type | When |
|------|------|
| `feat` | New feature or capability |
| `fix` | Bug fix |
| `refactor` | Restructure without behavior change |
| `perf` | Performance improvement |
| `style` | Formatting only, no logic change |
| `docs` | Documentation only |
| `test` | Tests added or updated |
| `chore` | Tooling, config, build system |
| `port` | Code ported from Iris Code |

- Imperative mood, all lowercase, no period, max 72 chars.
- Scope = module: `ui`, `domain`, `data`, `agent`, `terminal`, `ssh`, `block`, `semantic`, `shortcut`, `session`, `workspace`, `hud`, `util`, `di`

**Examples:**
```
feat(terminal): implement block-based output engine
port(agent): migrate MultiStepStreamer from Iris Code
fix(ssh): resolve key vault biometric unlock race condition
refactor(semantic): extract pattern matcher to separate class
```

---

## 🏗️ ARCHITECTURE RULES

### Layer Responsibilities

```
ui/           → Compose screens, components, ViewModels.
                No direct data/agent/terminal access.

domain/       → Use cases, business logic, repository interfaces,
                DroshTool interface. Pure Kotlin only.
                Zero Android imports unless unavoidable — flag explicitly.

data/         → Repository implementations, Room DAOs, SSHJ client,
                LLM provider adapters, DataStore, theme store client.

agent/        → Agent loop, ToolRegistry, MultiStepStreamer,
                streaming handler, tool implementations.
                Depends on domain/ interfaces only.

terminal/     → PTY session management, terminal emulator bridge,
                block engine, semantic parser, ghost text engine.
                Isolated — only data/ and agent/ interact with it.

ssh/          → SSHJ manager, SSH key vault, host management,
                multi-exec, constellation data.

di/           → Hilt modules only. No logic whatsoever.

util/         → Constants, extension functions, shared helpers.
```

### Hard Rules
- `ui/` never imports from `data/`, `agent/`, `terminal/`, or `ssh/` directly
- `domain/` is pure Kotlin — zero Android imports, flag any exception
- `data/` implements interfaces from `domain/`
- `agent/` depends only on `domain/` interfaces, connected via Hilt
- `terminal/` is isolated — never imported directly by `ui/`
- `ssh/` is isolated — UI accesses only through `domain/` use cases
- ViewModels expose `StateFlow<UiState>` — never expose mutable state
- UI collects with `collectAsStateWithLifecycle()`
- Never access Room, network, PTY, or SSH APIs from a ViewModel directly

### Data Flow

```
Compose Screen
      ↓ UI events
ViewModel (ui/)
      ↓ calls
Use Case (domain/)
      ↓ calls
Repository Interface (domain/)
      ↓ implemented by
Repository Impl (data/) / Agent (agent/) / Terminal (terminal/) / SSH (ssh/)
      ↓
Room DAO / LLM API / PTY / SSHJ
```

### Agent Tool Flow

```
LLM function_call response
      ↓
ToolRegistry.execute(name, args, mode)
      ↓
DroshTool implementation
      ↓ (if write_file)
DiffApproveEvent → UI DiffCard → User Approve / Reject
      ↓ (if bash)
PRoot subprocess → output streamed → BashBlock in terminal
      ↓ (if ask_user)
AskCard in agent tab → blocks until answered
      ↓
ToolResult (Success | Error | Cancelled | AwaitingApproval)
```

---

## 💻 CODING STANDARDS

### General
- All code comments in **English**
- No magic numbers — use named constants in `util/Constants.kt`
- No hidden side effects — every function does exactly what its name says
- Explicit error handling — no silent failures, no empty catch blocks
- Use `sealed class` / `sealed interface` for UI state, tool results, events, stream events
- Prefer immutable data (`val` over `var`, immutable collections)
- Use Kotlin `data class` for all model/entity types
- All `suspend` functions called from appropriate coroutine scopes only
- All coroutines must be cancellable — check `isActive` in long-running loops

### Naming Conventions
| Element | Convention | Example |
|---------|-----------|---------|
| Files | PascalCase | `BlockEngine.kt` |
| Classes | PascalCase | `SemanticParser` |
| Interfaces | PascalCase | `DroshTool` |
| Functions | camelCase | `parseOutput()` |
| Constants | SCREAMING_SNAKE | `MAX_BLOCK_LINES` |
| Composables | PascalCase | `TerminalBlock()` |
| ViewModels | PascalCase + VM suffix | `TerminalViewModel` |
| DAOs | PascalCase + Dao suffix | `CommandDnaDao` |

### Compose Rules
- One composable per file for complex components
- Preview annotations on every public composable
- No business logic inside composables — only UI state rendering
- Use `remember` and `derivedStateOf` correctly — never recompute inside composition
- Animations: 200–300ms, no bounce/elastic unless explicitly designed
- Always use `Modifier` as first parameter after required params

### Coroutine Scopes
- ViewModels → `viewModelScope`
- Repositories → injected `CoroutineScope` or caller's scope
- Background work → `WorkManager` (cron, agent watch)
- Terminal PTY → dedicated `CoroutineScope` in `TerminalManager`
- Agent loop → dedicated scope, cancellable via `cancel()`

---

## 🔧 TOOL SYSTEM RULES

Every tool implements `DroshTool` interface:
```kotlin
interface DroshTool {
    val name: String
    val description: String
    val parameters: JsonObject
    suspend fun execute(args: Map<String, Any>): ToolResult
}
```

```kotlin
sealed class ToolResult {
    data class Success(val output: String) : ToolResult()
    data class Error(val message: String, val cause: Throwable? = null) : ToolResult()
    data class Cancelled(val reason: String) : ToolResult()
    data class AwaitingApproval(val eventId: String) : ToolResult()
}
```

### Tool Rules
- `write_file` → always triggers `DiffApproveEvent` before writing, no exceptions
- `bash` → PRoot subprocess execution, output streams to terminal block
- `ask_user` → AskCard in agent tab, agent coroutine suspends until answered
- New tools added one at a time per Muhofy's spec — no speculative tools

### Work Mode Enforcement
| Mode | Behavior |
|------|----------|
| PLAN | `ToolRegistry` rejects `write_file` and `bash` → `ToolResult.Cancelled(reason = "PLAN mode — read only")` |
| BUILD | Full tool use. Diff/approve active for write_file. |
| AUTO | BUILD + autonomy toggles forced ON. Session-scoped only, never persisted. |

### Core Tool Set (Phase 6 — Agent Intelligence)
```
bash         → PRoot subprocess, output to terminal block
read_file    → file content into agent context
write_file   → diff + approve flow, write only after approval
ask_user     → AskCard, suspends until answered
update_todo  → TodoCard in agent tab
web_search   → Tavily API
```

---

## 📦 PORT RULES (Iris Code → Drosh)

When porting a module from Iris Code:

1. **Always fetch the latest version** of the file from `mmuhofy/IrisCode` — never reconstruct from memory.
2. **Adapt package names:** `com.iris.iriscode` → `dev.drosh`
3. **Adapt color references:** Iris Code uses `#7C3AED` purple. Drosh uses `#E8C547` gold.
4. **Check dependency versions:** Drosh uses Kotlin `2.3.20`. Confirm compatibility before porting.
5. **Document the port** at the top of the file:
   ```kotlin
   // Ported from: mmuhofy/IrisCode — agent/AgentLoop.kt
   // Adapted for Drosh — dev.drosh
   // Changes: [list what changed]
   ```
6. **Do not port dead code.** Only port what Drosh actually needs.

### Portability Status
| Module | Status | Notes |
|--------|--------|-------|
| `ProotRunner.kt` | ✅ Direct port | Package rename only |
| `UbuntuBootstrap.kt` | ✅ Direct port | Package rename only |
| `TerminalManager.kt` | 🔧 Adapt | Multi-session support needed |
| `AgentLoop.kt` | ✅ Direct port | Phase 6 |
| `MultiStepStreamer.kt` | ✅ Direct port | Phase 6 |
| `OpenAiProviderAdapter.kt` | ✅ Direct port | Phase 6 |
| `WebSearchTool.kt` | ✅ Direct port | Phase 6 |
| `BashTool.kt` | 🔧 Adapt | Block output instead of BashCard |
| Visual Identity | ✅ Direct port | Colors differ — gold not purple |
| termux-view JNI | ✅ Direct port | Same binary |

---

## 📁 FILE & ARTIFACT RULES

- Always provide files as **artifacts** — never inline as plain text
- One artifact per file, **full content always** — never truncate
- If updating an existing file, use the artifact update mechanism
- Artifact title must match the actual filename (e.g., `BlockEngine.kt`)
- Always include the **full project path** in the artifact title:
  ```
  app/src/main/java/dev/drosh/terminal/engine/BlockEngine.kt
  ```
- Never refer to a file by name alone — always pair with full path
- If a file is too long for one artifact, split by logical section and label clearly

---

## 🌐 WEB RESEARCH PROTOCOL

- If a fetch or search returns empty or fails:
  1. Do not hallucinate the content
  2. Provide the exact URL to Muhofy
  3. Ask Muhofy to paste the relevant content
  4. Only proceed once real content is provided
- For external APIs (SSHJ, Tavily, Gemini, Whisper), if behavior or parameters are uncertain: fetch official docs before writing integration code — never guess.
- For Gemini model strings: always verify against `ai.google.dev/gemini-api/docs/models` — they change frequently.

---

## 📂 PROJECT FILE ACCESS RULES

- Do **NOT** assume project files exist locally or in context.
- Before modifying or referencing any project file, request the GitHub raw URL from Muhofy.
- **Never reuse previously fetched file contents** from a prior session — always re-fetch from a fresh raw URL.
- If a required file has not been provided in this session: **stop, request the URL, do not reconstruct from memory.**
- Treat every session as if no project files have been seen before.

---

## 🎯 DEVELOPMENT PHASE RULES

Development follows a strict phase order. **Do not jump ahead.**

```
Phase 1 — Terminal Core      ← current
Phase 2 — UI & Session System
Phase 3 — Input System
Phase 4 — SSH & Remote
Phase 5 — Safety, Polish & Distribution
Phase 6 — Agent Intelligence ← last
```

- Never implement Phase N+1 features while Phase N is incomplete.
- Agent Intelligence (Phase 6) is explicitly last — do not add agent features earlier.
- If a feature spans multiple phases, implement only the Phase N portion now.

---

## 🧠 MEMORY BANK RULES

- **Always read `MEMORY_BANK.md` at session start** — before writing any code.
- **Always update `MEMORY_BANK.md` after every confirmed change:**
  - New decisions
  - Completed features
  - Changed stack versions
  - Resolved open decisions
- **Never contradict Memory Bank** without explicit Muhofy approval.
- **Always update `TODO.md`** — check off completed items, add new ones discovered during implementation.
- If Memory Bank is missing or incomplete: stop and ask Muhofy before proceeding.
- Memory Bank is the single source of truth. If code and Memory Bank conflict: Memory Bank wins.

---

## 🖥️ TARGET STACK

| Component | Technology | Version |
|-----------|------------|---------|
| Package | `dev.drosh` | — |
| Language | Kotlin | `2.3.20` |
| UI | Jetpack Compose BOM | `2026.04.01` |
| Material | Material 3 | via BOM |
| Min SDK | 26 | Android 8.0+ |
| Target SDK | 36 | Android 16 |
| Compile SDK | 36 | — |
| Architecture | MVVM + Clean Architecture | — |
| DI | Hilt | `2.57` |
| Navigation | Navigation Compose | via BOM |
| Local DB | Room + FTS5 | `2.8.4` |
| Preferences | DataStore | `1.1.x` |
| Async | Kotlin Coroutines + Flow | `1.10.x` |
| SSH | SSHJ | `0.38.x` |
| HTTP/Stream | OkHttp + SSE | `4.12.x` |
| Security | AndroidX Security Crypto | `1.1.x` |
| Serialization | Kotlinx Serialization | `1.7.x` |
| Background | WorkManager | `2.11.2` |
| Biometric | BiometricPrompt | `1.2.x` |
| Image | Coil Compose | `3.x` |
| Animation | Lottie Compose | `6.x` |
| Build | Gradle KTS + Version Catalog | — |
| Annotation | KSP | match Kotlin `2.3.20` |
| Terminal | termux-view + termux-emulator | vendored |
| Linux | PRoot `5.2.0` + Ubuntu `24.04` | runtime download |
| LLM (Phase 6 v1.0) | Gemini 3.5 Flash | Google GenAI SDK |
| LLM (Phase 6 v1.1+) | + Anthropic, OpenAI, OpenRouter | — |
| LLM (Phase 6 v2.0+) | + Ollama, llama.cpp | local optional |
| License | MIT | — |

> ⚠️ Always confirm versions against `gradle/libs.versions.toml` before referencing any API.
> ⚠️ Gemini model strings change frequently — always verify before use.
> ⚠️ Kotlin 2.3.20 + KSP must match — use KSP `2.3.20-x.x.x`.
