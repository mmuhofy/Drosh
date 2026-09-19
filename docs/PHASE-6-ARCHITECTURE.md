# Phase 6 Architecture — Agent Intelligence

_Reference analysis of 10 agentic coding tools (OpenCode, Cline SDK, Codex, Aider, DeepSeek Harness, Qwen Code, Harness CLI, Claude Code, Kilo Code) conducted 2026-09-19 in `/root/projects/Drosh-sources-of-inspiration/`._

---

## 1. Architectural Overview

```
agent/                          ← Agent Intelligence module (Phase 6)
  ├── runtime/
  │   ├── AgentRuntime.kt          ← Core loop orchestrator (naming approved)
  │   └── AgentOrchestrator.kt     ← Coordinates runtime, tools, UI streaming events
  ├── provider/
  │   ├── ProviderAdapter.kt       ← Interface: normalize SSE → StreamEvent
  │   ├── GeminiAdapter.kt         ← google-genai SDK (v1.0 default)
  │   ├── OpenAiAdapter.kt         ← OpenAI-compatible /chat/completions
  │   └── AnthropicAdapter.kt      ← Anthropic /messages
  ├── tool/
  │   ├── Tool.kt                  ← Interface (name, schema, execute)
  │   ├── ToolRegistry.kt          ← Provider tools + local tools
  │   └── impl/
  │       ├── BashTool.kt          ← PRoot subprocess (uses TerminalManager.executeCommand)
  │       ├── ReadFileTool.kt
  │   │   ├── WriteFileTool.kt      ← diff + approve flow (diff-utils dep)
  │       ├── AskUserTool.kt
  │       ├── UpdateTodoTool.kt
  │       └── WebSearchTool.kt     ← Tavily API (okhttp dep)
  └── stream/
      ├── StreamEvent.kt           ← Sealed: TextDelta, ToolCallStart, ToolInputDelta,
      │                              ToolCallEnd, ToolResult, Finish, Error
      └── MultiStepStreamer.kt     ← Normalizes adapter SSE → StreamEvent, feeds AgentRuntime

domain/agent/                      ← Pure interfaces (no deps)
  ├── ToolResult.kt                ← EXISTS (Success, Error, Cancelled, AwaitingApproval)
  ├── Tool.kt                      ← NEW — sealed interface for tool definitions
  ├── ProviderAdapter.kt           ← NEW — interface
  └── AgentEvent.kt                ← NEW — UI-facing sealed event types

data/                              ← Existing di/ + data layer
```

**Module dependency rule (AGENT.md §5):** `domain/` = pure Kotlin interfaces only; `agent/` implements `domain` interfaces; wired via Hilt `@Binds` in `data/src/main/kotlin/dev/drosh/data/di/BindingsModule.kt`.

---

## 2. Reference Analysis

### 2.1 OpenCode (Effect-TS, TypeScript) — Best reference for provider adapters + streaming

**Source:** `packages/llm/src/`

| Component | File | Key insight |
|---|---|---|
| **Provider adapters** | `protocols/openai-chat.ts`, `protocols/gemini.ts`, `protocols/anthropic-messages.ts` | Each adapter converts provider-native SSE/JSON to normalized `LLMEvent`. **No proxy** — adapter owns the translation. |
| **Normalized events** | `schema/events.ts` (618 lines, 14 event types) | `StepStart`, `TextStart`, `TextDelta`, `TextEnd`, `ReasoningDelta`, `ToolInputStart`, `ToolInputDelta`, `ToolInputEnd`, `ToolCall`, `ToolResult`, `ToolError`, `Finish`, `ProviderError` — this is the gold standard to port. |
| **Provider routing** | `route/client.ts` (line 396: `stream()`) | `compile(request)` resolves provider → calls `compiled.route.streamPrepared(...)` → adapter streams events. |
| **Agent loop** | `session/runner/llm.ts` (439 lines) | `while (shouldRun) { while (needsContinuation) { runTurn() } }`. Each turn: `llm.stream(request)` → `Stream.runForEach` → process events → dispatch tools via `FiberSet` → reload history → next turn. |
| **Tool system** | `llm/src/tool.ts` (253 lines) | `Tool<Parameters, Success>` — bundles description, JSON Schema, `execute()` handler. `tool-runtime.ts:78` has `dispatch()` that decodes params, executes, catches errors. |
| **Stream accumulation** | `protocols/utils/tool-stream.ts` | `ToolStream` accumulator — provider sends partial JSON across delta chunks; accumulator assembles input + finalizes. |

**Key pattern — streaming normalization:** Each provider adapter produces raw SSE `data:` lines. The adapter decodes them into `ProtocolEvent` (provider-native), then maps to `LLMEvent` (normalized). The `runForEach` in the agent loop consumes `LLMEvent` — provider-agnostic.

```typescript
// OpenCode pattern — adapter produces normalized stream
const providerStream = llm.stream(request)  // → Stream<LLMEvent, LLMError>
Stream.runForEach(providerStream, (event) => {
  if (event.type === "tool-call") {
    toolMaterialization.settle({ ... })  // dispatch tool
  }
  if (event.type === "text-delta") {
    publish(event)  // to UI
  }
})
```

**Ktlization for Drosh:**
- `ProviderAdapter` interface: `suspend fun stream(request: LLMRequest): Flow<StreamEvent>`
- `GeminiAdapter` uses `google-genai` lib → maps `AsyncGenerateContentResponse` → `StreamEvent`
- `StreamEvent` = sealed interface mirroring OpenCode's 14 event types (subset for v1)

### 2.2 Cline SDK (TypeScript) — Best reference for agent loop + error handling

**Source:** `sdk/packages/agents/src/agent-runtime.ts` (2454 lines)

| Component | File | Key insight |
|---|---|---|
| **Agent loop** | `agent-runtime.ts:724` `execute()` | `while (this.config.maxIterations === undefined || this.state.iteration < this.config.maxIterations)` — bounded loop with `iteration` counter. |
| **Turn lifecycle** | `agent-runtime.ts:724-770` | Per iteration: emit `turn-started` → `generateAssistantMessage()` → emit `assistant-message` → `executeToolCalls()` → emit `message-added`. |
| **Provider retry** | `agent-runtime.ts:1027` `generateAssistantMessageWithProviderRetry()` | Retries on transient errors (PROVIDER_ERROR_MAX_RETRIES = 3), backoff 1s→2s→4s (max 15s). |
| **Context overflow** | `agent-runtime.ts:1146` `generateAssistantMessageWithOverflowRecovery()` | Detects context overflow → triggers compaction → retries with compacted history. |
| **Tool interface** | `sdk/packages/shared/src/agent.ts:202` `AgentTool<TInput, TOutput>` | `execute(input, context)` returns `Promise<TOutput>`. Context includes `sessionId`, `agentId`, `iteration`, `signal` (AbortSignal), `abort` callback. |
| **AgentToolContext** | `agent.ts:189` | Carries runtime state into tool execution — abort signal, metadata, snapshot, `emitUpdate` callback. |
| **Event emission** | `agent-runtime.ts` throughout | `emit({ type: "turn-started", ... })`, `emit({ type: "run-finished", ... })`, `emit({ type: "assistant-message", ... })`. |

**Key pattern — provider error recovery:**
```typescript
// Cline: retry with backoff + overflow recovery
const { message, finishReason, interrupted } =
  await this.generateAssistantMessageWithProviderRetry();
if (finishReason === "max-tokens" && toolCalls.length === 0) {
  throw new Error(MAX_TOKENS_INCOMPLETE_TURN_MESSAGE);
}
```

**Ktlization for Drosh:** Wrap the loop in a coroutine with `repeatWhen` / `ensureActive` for abort. Use `kotlinx.coroutines.flow.retryWhen` for transient error handling.

### 2.3 Codex (Rust) — Multi-agent + persistent state

**Source:** `codex-rs/core/src/`

| Component | File | Key insight |
|---|---|---|
| **Agent turn control** | `agent/control.rs` (966 lines) | `LocalAgentControl` — manages agent tree, sub-agent spawning, inter-agent communication. |
| **SSE streaming** | `client.rs` (1260 lines, lines 1627-1809) | `loop {` reads SSE events from OpenAI Responses API, emits `Op`/items to thread store. |
| **Thread persistence** | `codex-thread.rs` (1075 lines) | `start_turn_if_idle`, `continue_turn_if_idle`, `submit_with_trace` — SQLite-backed thread state. |
| **Model provider** | `model-provider/src/` | `ModelProvider` trait, `ProviderCapabilities`, auth management. |
| **Sub-agent monitoring** | `control.rs:637` | `while !is_final(&status)` — polls sub-agent status until Completed/Failed. |

**Key pattern — durable session state:** Codex persists every tool call + result in SQLite before execution begins. This enables crash recovery. For Drosh v1, we start with in-memory state; add SQLite persistence later (Phase 6 scope: "Agent Session visible in Session Switcher" implies in-memory lifecycle).

### 2.4 DeepSeek Harness (TypeScript/Cordis) — State machine agent

**Source:** `packages/core/agent-loop/src/agent.ts` (620 lines)

| Component | File | Key insight |
|---|---|---|
| **State machine** | `agent.ts:66` `Phase` type | `idle | maintenance | running { turn, step }` — explicit phase transitions. |
| **Main loop** | `agent.ts:210` `kick()` | `while (await this.turn()) {}` — calls `turn()` until it returns false. |
| **Tool scheduling** | `tool-calls.ts` (290 lines) | `executeToolCalls` — handles parallel vs. sequential (barrier) execution, abort propagation, drain. |
| **Plugin framework** | `packages/core/agent-loop/src/index.ts` | Cordis `Context`/`Service` system — agents published as services, lifecycle managed. |

**Key pattern — phase-based state:** `wakeDriver()` transitions `idle → running`, `kick()` runs the loop, `finally` transitions back to `idle`. This is cleaner than bare `while(true)` but adds complexity. For Drosh v1, a simple bounded loop in a coroutine is sufficient.

### 2.5 Qwen Code (TypeScript) — Loop detection + subagents

**Source:** `packages/core/src/agents/runtime/agent-core.ts` (2815 lines)

| Component | File | Key insight |
|---|---|---|
| **Reasoning loop** | `agent-core.ts:1007` `_runReasoningLoopInner()` | `while (true)` with multiple termination conditions. |
| **Termination conditions** | `agent-core.ts:1020-1030` | `abortController.signal.aborted`, `maxTurns`, `maxTimeMinutes`, loop detection. |
| **Loop detection** | `agent-core.ts:1038` `LoopDetectionService` | Heuristic + safety checks — detects repeated tool calls / responses. |
| **Streaming** | `agent-core.ts:1048` `for await (const streamEvent of responseStream)` | Async iterator over provider stream, case-switched on `streamEvent.type`. |
| **Multi-agent (fork)** | `packages/core/src/agents/` | `forkedAgent.ts`, `arena/`, `team/` — subagent spawning with resource limits. |

**Key pattern — loop detection:** Qwen's `LoopDetectionService` checks for repeated identical tool calls. For Drosh v1, `MAX_STEPS` bound + `ToolResult.AwaitingApproval`/`Cancelled` handling is sufficient; add heuristic loop detection in v2.

### 2.6 Harness CLI (TypeScript, 137 lines) — Minimal reference

**Source:** `harness-cli/src/agent/agent.ts` (137 lines)

| Component | Key insight |
|---|---|
| **Loop** | `while (steps < MAX_STEPS)` where `MAX_STEPS = 50` — single function, linear flow. |
| **Streaming** | `llm.generate({ messages, tools })` — non-streaming by default, but events are emitted: `text`, `tool_call`, `tool_result`, `approval_request`, `done`. |
| **Tool dispatch** | `runTool(call.name, call.args)` — simple dispatch table. |
| **Safety** | `isDangerousCommand()` — regex-based command safety check before execution. |
| **Context mgmt** | `manageContext()` — token counting + summarization. |

**Key pattern — minimal loop:**
```typescript
while (steps < MAX_STEPS) {
  const { text, toolCalls } = await llm.generate({ model, apiKey, system, messages, tools });
  if (toolCalls.length === 0) { onEvent({ type: "done", text }); return text; }
  for (const call of toolCalls) { onEvent({ type: "tool_call", ... }); const result = await runTool(...); }
}
```
This is the simplest correct agent loop and serves as a minimal validation target.

### 2.7 Aider (Python) — Universal provider abstraction

**Source:** `aider/aider/coders/base_coder.py` (2485 lines)

| Component | Key insight |
|---|---|
| **Provider abstraction** | `litellm` — universal LLM library supporting 100+ providers via a single interface. `LlmCallConfig` normalizes all providers. |
| **Agent loop** | `Coder.run()` method — processes one full conversation turn. |
| **Editing strategies** | Different `Coder` subclasses: `EditBlockCoder` (edit block), `WholeFileCoder` (full file rewrite), `SingleWholeFileCoder`. |
| **Repo context** | `RepoMap` — builds a tree of the repo and uses fuzzy matching to find relevant code sections. |
| **Git integration** | `GitRepo` — tracks file changes, diffs, commits. |

**Key pattern — single-provider API:** Aider uses litellm's unified interface rather than per-provider adapters. This simplifies the code but loses provider-specific optimizations (e.g., Claude's prompt caching). For Drosh, the multi-adapter pattern (OpenCode) is preferred because it preserves provider-specific features and avoids an external dependency like litellm.

### 2.8 Claude Code

The `@anthropic-ai/claude-code` NPM package is distributed as a **compiled/bundled binary** — the source code is not available in the GitHub repo (`claude-code/`). What we can observe:

| Component | File | Key insight |
|---|---|---|
| **Plugin system** | `mods/` directory | Contains TypeScript plugin code (agents-md, diff, sec-default, telemetry). These are user-installed extensions, not core source. |
| **Type definitions** | `mods/types/claude-code.d.ts` | TypeScript declarations describing the Claude Code extension API — shows the surface area plugins interact with. |
| **Commands** | `.claude/commands/*.md` | Command definition format (markdown with YAML frontmatter). |
| **Scripts** | `scripts/*.ts` | Utility scripts for repo management (issue lifecycle, duplicate handling, badge management). |

**Key insight:** Claude Code's internal architecture is opaque. The plugin API surface (from `.d.ts`) suggests it uses an event-driven model with hooks. For Drosh, we model the agent loop after OpenCode/Cline patterns, not Claude Code's internals.

### 2.9 Kilo Code

**Source:** `kilo-code/packages/`

Kilo Code is an **Effect-TS project that forks OpenCode** — `packages/core/src/agent.ts` imports from `@opencode-ai/schema/agent`. Its `agent.ts` (111 lines) is just **agent config/registration**, not the runtime. The actual runtime follows OpenCode's Effect-TS `Layer`/`Service` DI pattern.

**Key insight:** Kilo demonstrates the **Effect-TS Layer pattern** at scale — services like `Agent`, `AISDK`, `BackgroundJob`, `Catalog` are all `Context.Service<Service, Interface>()` with `Layer.effect()` factories. For Drosh (Kotlin), the equivalent is Hilt `@Module` + `@InstallIn(SingletonComponent::class)`.

---

## 3. Agent Loop Design (Decided)

### 3.1 Loop Structure

**Pattern:** Bounded iterative loop with step counter, combining Cline's event emission with OpenCode's streaming approach.

```kotlin
// AgentRuntime.kt
class AgentRuntime @Inject constructor(
    private val provider: ProviderAdapter,
    private val toolRegistry: ToolRegistry,
    private val eventSink: (AgentEvent) -> Unit,
) {
    suspend fun run(prompt: String, maxSteps: Int = 50): AgentResult = coroutineScope {
        var step = 0
        var history = buildList { add(UserMessage(prompt)) }

        while (step < maxSteps) {
            val stream = provider.stream(buildRequest(history, step, maxSteps))
            val turnResult = collectTurn(stream)  // Text + tool calls from StreamEvent

            if (turnResult.toolCalls.isEmpty()) {
                return@coroutineScope AgentResult.Completed(turnResult.text)
            }

            // Execute tools, get results
            val toolResults = turnResult.toolCalls.map { call ->
                executeTool(call)  // → ToolResult (Success/Error/Cancelled/AwaitingApproval)
            }

            history.addAll(toolResults.toHistoryMessages())
            step++
        }

        AgentResult.Completed("Max steps reached")
    }
}
```

**Key parameters (from TODO.md):**
- `MAX_STEPS` = 50 (matching Harness CLI's default)
- Bounded loop prevents infinite tool-call cycles
- `step` counter for diagnostics and future loop detection

### 3.2 Turn Lifecycle Events (from Cline pattern)

Each iteration emits events to the UI layer (from `agent-runtime.ts:753-770`):

| Event | When | UI action |
|---|---|---|
| `turn-started` | Before LLM call | Show spinner, iteration counter |
| `assistant-message` | After LLM stream completes | Render assistant response in chat |
| `message-added` | After tool result | Append tool result to history |
| `tool-call` | Before tool execution | Show tool being called |
| `tool-result` | After tool execution | Show result + diff preview (write_file) |
| `turn-finished` | After all tools resolved | Hide spinner |
| `run-finished` | When no more tool calls | Mark session complete |

### 3.3 Abort / Cancellation

- `AgentToolContext` carries `AbortSignal` (from Cline pattern) — tools can check `signal.aborted` and cancel PRoot subprocesses
- `ToolResult.Cancelled` already exists in the sealed type — use for user-initiated abort
- `kotlinx.coroutines.Job.cancel()` propagates to all child coroutines (tools executing concurrently)

---

## 4. Provider Adapter Design (Decided: Multi-Adapter, NOT proxy)

### 4.1 Decision Rationale

**The question (MEMORYBANK.md §42–44):** Gemini doesn't speak OpenAI-compatible format. Options were:
1. **OpenAI proxy** — route all providers through an OpenAI-format proxy
2. **Per-provider adapters** — each provider has its own adapter that normalizes to `StreamEvent`

**Decision: Per-provider adapters.** Rationale:
- OpenCode's `protocols/` pattern (7 adapters: openai-chat, openai-responses, openai-compatible-chat, gemini, anthropic-messages, bedrock-converse, bedrock-event-stream) proves this scales
- `google-genai` lib is already in version catalog (verified: `libs.versions.toml`) — direct integration, no proxy dependency
- Provider-specific features (Claude prompt caching, Gemini function calling) preserved
- Error handling is provider-specific (Cline's `classifyProviderError()`) — proxy would obscure this

### 4.2 Interface

```kotlin
// domain/agent/ProviderAdapter.kt
interface ProviderAdapter {
    suspend fun stream(request: LLMRequest): Flow<StreamEvent>
    fun formatTools(tools: List<Tool>): ProviderTools
    fun countTokens(messages: List<Message>): Int
}

// agent/provider/GeminiAdapter.kt
class GeminiAdapter @Inject constructor(
    private val client: GenerativeClient,  // google-genai
) : ProviderAdapter {
    override suspend fun stream(request: LLMRequest): Flow<StreamEvent> =
        flow {
            val response = client.generateContentStream(...)
            response.collect { chunk ->
                emit(mapGeminiChunk(chunk))  // → StreamEvent
            }
        }
}
```

### 4.3 Provider Resolution

**Pattern:** Registry + factory (from OpenCode's `route/client.ts` + Codex's `ModelProvider` trait):

```kotlin
// agent/provider/ProviderRegistry.kt
@Singleton
class ProviderRegistry @Inject constructor(
    private val geminiAdapter: GeminiAdapter,
    private val openAiAdapter: OpenAiAdapter,
) {
    fun resolve(providerId: ProviderId): ProviderAdapter = when (providerId) {
        ProviderId.GEMINI -> geminiAdapter
        ProviderId.OPENAI -> openAiAdapter
        ProviderId.ANTHROPIC -> AnthropicAdapter
        else -> throw IllegalArgumentException("Unknown provider: $providerId")
    }
}
```

### 4.4 Streaming Normalization (OpenCode schema ported)

`StreamEvent` sealed interface — mirrors OpenCode's `LLMEvent` schema (14 types → subset for v1):

```kotlin
// domain/agent/StreamEvent.kt
sealed interface StreamEvent {
    data class TextDelta(val id: String, val text: String) : StreamEvent
    data class ReasoningDelta(val id: String, val text: String) : StreamEvent
    data class ToolCallStart(val id: String, val name: String) : StreamEvent
    data class ToolInputDelta(val id: String, val delta: String) : StreamEvent   // partial JSON
    data class ToolCallEnd(val id: String) : StreamEvent
    data class ToolResult(val id: String, val result: String, val isError: Boolean) : StreamEvent
    data class Finish(
        val reason: FinishReason,  // STOP | MAX_TOKENS | ERROR | TOOL_CALLS
        val usage: TokenUsage
    ) : StreamEvent
    data class Error(val message: String, val isRetryable: Boolean) : StreamEvent
}
```

**Tool input accumulation** (from OpenCode's `ToolStream`/`tool-stream.ts`): When a provider sends partial tool input across delta chunks, the `MultiStepStreamer` collects them into a `ToolAccumulator` buffer and emits a complete `ToolCall` only when `ToolCallEnd` arrives.

---

## 5. Tool System Design (Decided)

### 5.1 Tool Interface

```kotlin
// domain/agent/Tool.kt
interface Tool {
    val name: String
    val description: String
    val parameters: JsonObject  // JSON Schema
    val requiresApproval: Boolean
    suspend fun execute(input: JsonObject, context: ToolContext): ToolResult
}

data class ToolContext(
    val sessionId: String,
    val agentId: String,
    val step: Int,           // current step (from Cline's iteration)
    val abortSignal: AbortSignal?,
    val emitUpdate: (ToolEvent) -> Unit,  // incremental output to UI
)
```

### 5.2 Existing Foundation Leveraged

`TerminalManager.executeCommand()` (terminal/src/main/kotlin/dev/drosh/terminal/TerminalManager.kt:557) returns `ToolResult` — **this is the bash tool foundation.** It:
- Checks `ubuntuBootstrap.isInstalled`
- Builds a PRoot bash command
- Executes via `ProcessBuilder` with cleaned environment
- Captures stdout/stderr via `inputStream.bufferedReader()`
- Returns `ToolResult.Success(output)` or `ToolResult.Error(message)`

The `BashTool` wraps this: maps LLM tool-call JSON args (`{ command: "...", timeout: ... }`) → `executeCommand()` call.

### 5.3 Tool Visibility — Mutation vs. Read-Only (MEMORYBANK.md §127)

| Tool | Visible in terminal? | Reason |
|---|---|---|
| `bash` | Yes (mutation heuristic) | Commands like `write`, `install`, `commit`, `push`, `rm`, `mv` → visible |
| `read_file` | No | Read-only inspection |
| `write_file` | Yes | File mutation |
| `web_search` | No | No terminal side effects |
| `update_todo` | No | Internal state |

**Implementation:** `Tool.requiresTerminalVisibility: Boolean` flag. Simple keyword heuristic (`bash` tool checks command against `write|install|commit|push|rm|mv|mkdir|touch|chmod|chown`) OR explicit flag per tool — not LLM-decided (MEMORYBANK.md §127).

### 5.4 Approval Flow (MEMORYBANK.md §89, §127)

```
LLM emits tool_call → MultiStepStreamer dispatches → ToolRegistry.execute() →
  Tool.requiresApproval?
    YES → ToolResult.AwaitingApproval(eventId) → event flows to UI → User approves/denies →
      Approved: execute tool → ToolResult.Success/Error
      Denied:  ToolResult.Cancelled("User declined")
    NO  → execute tool → ToolResult.Success/Error
→ ToolResult pushed into history → next LLM turn
```

`ToolResult.AwaitingApproval` already exists in the sealed type. The UI layer handles the approval UI; the agent loop pauses until approval.

---

## 6. Module Boundary Map

### 6.1 domain/agent/ — Pure interfaces (Phase 6 v1 additions)

| File | Status | Contents |
|---|---|---|
| `ToolResult.kt` | EXISTS | Sealed: Success, Error, Cancelled, AwaitingApproval |
| `Tool.kt` | NEW | Interface: name, description, parameters, requiresApproval, execute() |
| `ProviderAdapter.kt` | NEW | Interface: stream(), formatTools(), countTokens() |
| `StreamEvent.kt` | NEW | Sealed interface for normalized streaming events |
| `AgentEvent.kt` | NEW | Sealed: TurnStarted, AssistantMessage, ToolCall, ToolResult, TurnFinished, RunFinished, Error |
| `AgentRuntime.kt` | NEW | Interface: run(prompt, maxSteps), abort() |
| `AgentConfig.kt` | NEW | Data class: provider, model, maxSteps, systemPrompt, apiKey |

### 6.2 agent/ — Implementations

| File | Status | Contents |
|---|---|---|
| `runtime/AgentRuntime.kt` | NEW | Core loop (coroutines, step counter, event emission) |
| `runtime/AgentOrchestrator.kt` | NEW | Coordinates Runtime + ToolRegistry + UI events; bridges to PTY session |
| `provider/ProviderAdapter.kt` | NEW | Interface (or reuse domain version) |
| `provider/GeminiAdapter.kt` | NEW | google-genai SDK → StreamEvent |
| `provider/OpenAiAdapter.kt` | NEW | OkHttp SSE → StreamEvent |
| `provider/AnthropicAdapter.kt` | NEW | OkHttp SSE → StreamEvent |
| `provider/ProviderRegistry.kt` | NEW | Provider resolution |
| `stream/MultiStepStreamer.kt` | NEW | Collects StreamEvents → tool calls, manages ToolStream accumulator |
| `tool/Tool.kt` | NEW | (reuse domain interface) |
| `tool/ToolRegistry.kt` | NEW | Registered tools, dispatch |
| `tool/impl/BashTool.kt` | NEW | Wraps TerminalManager.executeCommand() |
| `tool/impl/ReadFileTool.kt` | NEW | Read from PRoot filesystem |
| `tool/impl/WriteFileTool.kt` | NEW | Write with diff + approve (diff-utils) |
| `tool/impl/AskUserTool.kt` | NEW | Asks user via AgentEvent → UI |
| `tool/impl/UpdateTodoTool.kt` | NEW | Creates TodoCard |
| `tool/impl/WebSearchTool.kt` | NEW | Tavily API via OkHttp |

### 6.3 DI Wiring

Existing `data/src/main/kotlin/dev/drosh/data/di/BindingsModule.kt` uses Hilt `@Binds`:
```kotlin
@Module
@InstallIn(SingletonComponent::class)
abstract class BindingsModule {
    @Binds fun bindSessionRepository(impl: SessionRepositoryImpl): SessionRepository
    // ... existing bindings
}
```

**Add to `BindingsModule.kt`:**
```kotlin
@Binds fun bindAgentRuntime(impl: AgentRuntime): AgentRuntime
@Binds fun bindProviderAdapter(impl: GeminiAdapter): ProviderAdapter  // default
@Binds fun bindToolRegistry(impl: ToolRegistry): ToolRegistry
```

---

## 7. Cross-Session & Session Lifecycle Decisions (from user UX questions)

| Question | Decision | Reference |
|---|---|---|
| Mod selection timing | Panel-open dropdown: "Bu Session" / "Agent Session" tab | TODO.md §32, MEMORYBANK §77 |
| Default mode | **Agent Session** (isolated PTY — safer, more predictable on mobile) | Resolved by user |
| Agent Session in Session Switcher | **Visible** — auto-named e.g. `🤖 dracula-theme fix` | Resolved by user |
| Parallel tasks | **No** — single agent, sequential queueing only | Resolved by user |
| Session lifecycle | Agent Session deleted when app fully dies, but **metadata persists** (runtime killed, metadata kept) | Resolved by user |

---

## 8. Implementation Sequence (Phase 6)

```
Step 1: Domain interfaces     → Tool.kt, ProviderAdapter.kt, StreamEvent.kt, AgentEvent.kt
Step 2: Streaming + Adapter     → StreamEvent schema, GeminiAdapter (google-genai)
Step 3: AgentRuntime loop       → Bounded loop, step counter, event emission
Step 4: ToolRegistry + Tools    → BashTool (wrap TerminalManager), ReadFileTool, WriteFileTool
Step 5: DI + Orchestration      → Hilt @Binds in BindingsModule, AgentOrchestrator
Step 6: UI wiring (separate)    → Agent panel (bottom sheet) in Phase 5 UI work
```

**v1 scope (per TODO.md §17–26):**
- Port: `AgentLoop.kt` (→ `AgentRuntime.kt`), `MultiStepStreamer.kt`, `OpenAiProviderAdapter.kt` (→ `ProviderAdapter.kt` + `GeminiAdapter.kt`)
- Tools: `bash`, `read_file`, `write_file`, `web_search`, `ask_user`, `update_todo`
- Work modes: PLAN / BUILD / AUTO (authority axis, independent of task-assignment axis)

**v2 (TODO.md §57–63, AFTER v1):**
- Proaktif tetikleme (Error DNA / Output Intelligence banners)
- Command DNA (Room FTS5 indexing of every command)
- Ghost Text (separate from agent)

**NOT in scope for v1:**
- Multiple parallel agent sessions
- Loop detection heuristics (simple MAX_STEPS bound instead)
- Sub-agent spawning (multi-agent)
- Provider-specific optimizations (Claude prompt caching, etc.)

---

## 9. Codex-Specific Notes (from `backend-client/src/client.rs`)

The Codex `client.rs` streaming loop has a relevant pattern for retry/recovery:

```rust
// Lines 1627-1809 — streaming loop with auth recovery
loop {
    let client_setup = self.client.current_client_setup(ClientRouting::Workspace).await?;
    let transport = self.client.build_api_transport(...).await?;
    let request = self.build_streaming_request(...).await?;
    let response_stream = transport.execute(request).await?;

    // process SSE events...
    // on auth failure: retry from the top of the loop
}
```

**Ktlization:** Wrap the `GeminiAdapter.stream()` in a retry loop that re-resolves auth on `401` → re-creates the `GenerativeClient` → resumes streaming. Use `kotlinx.coroutines.flow.retryWhen` with exponential backoff.

---

## 10. References

| Tool | Repo path | Key files |
|---|---|---|
| OpenCode | `opencode/packages/llm/src/` `packages/core/src/session/runner/` | `protocols/{openai-chat,gemini,anthropic-messages}.ts`, `schema/events.ts`, `tool.ts`, `tool-runtime.ts`, `session/runner/llm.ts:439` |
| Cline SDK | `cline/sdk/packages/` | `agents/src/agent-runtime.ts:2454`, `shared/src/agent.ts`, `shared/src/llms/` |
| Codex | `codex/codex-rs/core/src/` | `client.rs:1260`, `agent/control.rs:966`, `codex_thread.rs:1075`, `agent/control/execution.rs` |
| DeepSeek Harness | `deepseek-harness/packages/core/agent-loop/` | `src/agent.ts:620`, `src/tool-calls.ts:290` |
| Qwen Code | `qwen-code/packages/core/src/agents/` | `runtime/agent-core.ts:2815`, `runtime/agent-core.ts:1007` (runReasoningLoop) |
| Harness CLI | `harness-cli/src/agent/` | `agent.ts:137` |
| Claude Code | `claude-code/` | `mods/` (plugins), `.claude/commands/` |
| Kilo Code | `kilo-code/packages/` | `core/src/agent.ts:111` (config), forks OpenCode |
| Aider | `aider/aider/` | `coders/base_coder.py:2485`, `llm.py`, `models.py` |

_Analysis conducted 2026-09-19 by reading source files in `/root/projects/Drosh-sources-of-inspiration/`._
