package dev.drosh.agent.runtime

import dev.drosh.agent.tool.ToolRegistry
import dev.drosh.domain.agent.AgentApproval
import dev.drosh.domain.agent.AgentEvent
import dev.drosh.domain.agent.ApprovalDecision
import dev.drosh.domain.agent.ApprovalRequest
import dev.drosh.domain.agent.LlmToolCall
import dev.drosh.domain.agent.ToolContext
import dev.drosh.domain.agent.Tool
import dev.drosh.domain.agent.ToolOutputTrimmer
import dev.drosh.domain.agent.ToolResult
import dev.drosh.domain.agent.ToolUpdate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.job
import kotlinx.coroutines.selects.select
import kotlin.coroutines.ContinuationInterceptor
import java.util.concurrent.atomic.AtomicLong

/**
 * Runs one tool call and reports it to the UI.
 *
 * Everything between "the model asked for this" and "the model knows what
 * happened" lives here: resolving the name, running the tool with a context that
 * can stream output and ask for consent, timing it, and clipping what goes back
 * to the model.
 *
 * The return value is deliberately just the text for history. What the UI renders
 * is a separate concern and gets its own event — the model wants a clipped
 * version of the result plus a marker saying it was clipped, while the UI wants
 * the tool's own message and a flag saying it was truncated.
 *
 * ## Why [confined] wraps every emit
 *
 * A `FlowCollector` is not thread-safe and must only be emitted from the
 * coroutine that collected it — the loop's flow is collected on the ViewModel's
 * dispatcher (Main), while tools do their work on IO.
 *
 * `ShellTool` is the case that proves it: it passes its `onOutput` lambda into
 * `TerminalManager.executeCommand`, which reads the process inside
 * `withContext(Dispatchers.IO)` and calls that lambda from the IO thread. So a
 * plain `collector.emit(...)` from inside a tool arrives from IO at a collector
 * living on Main, and kotlinx.coroutines throws
 * *"Flow invariant is violated"* — taking the whole run down on the first shell
 * command.
 *
 * The alternative was `flowOn(Dispatchers.IO)` on `AgentSession.send`, which
 * moves the entire loop off the collector's context and out of `runTest`'s
 * virtual clock. Capturing the interceptor where the tool call *starts* and
 * hopping back for each emit keeps the loop where it was collected and leaves
 * the tests deterministic.
 */
internal class ToolCallExecutor(
    private val registry: ToolRegistry,
    private val pending: PendingRequests,
) {

    private val approvalIds = AtomicLong(0)

    /** Execute [call] and return the text to append to conversation history. */
    suspend fun execute(
        call: LlmToolCall,
        chatId: String,
        workingDirectory: String,
        step: Int,
        collector: FlowCollector<AgentEvent>,
    ): String {
        val startedAt = System.currentTimeMillis()
        // Captured once, before any tool runs: the whole point is the context this
        // call *started* on, and reading it inside the emit lambda would report the
        // tool's context instead — the one that is wrong.
        val events = confined(collector)
        val tool = registry.find(call.name)

        if (tool == null) {
            // Not fatal. The model is told what it could have called and gets
            // another turn; ending the run over a hallucinated name discards
            // everything that led up to it.
            return unknownTool(call, events)
        }

        collector.emit(
            AgentEvent.ToolCallStarted(
                callId = call.id,
                name = tool.name,
                summary = tool.summarize(call.arguments),
            ),
        )

        val result = try {
            tool.execute(
                call.arguments,
                ToolContext(
                    chatId = chatId,
                    workingDirectory = workingDirectory,
                    step = step,
                    emit = { update -> events.emit(update.eventFor(call.id)) },
                    awaitApproval = { request ->
                        awaitApproval(tool, call, request, chatId, events)
                    },
                ),
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            // A tool that throws is a bug in the tool, but it must not take the
            // run down with it: the model can route around a broken tool.
            ToolResult.Error(
                message = error.message ?: error::class.java.simpleName,
                cause = error,
            )
        }

        val trimmed = ToolOutputTrimmer.trim(result.toResponseString())

        events.emit(
            AgentEvent.ToolCompleted(
                callId = call.id,
                name = tool.name,
                result = result,
                truncated = trimmed.truncated,
                durationMs = System.currentTimeMillis() - startedAt,
            ),
        )

        return trimmed.text
    }

    private suspend fun unknownTool(
        call: LlmToolCall,
        collector: FlowCollector<AgentEvent>,
    ): String {
        val available = registry.names()
        val message = buildString {
            append("No tool named '").append(call.name).append("'.")
            if (available.isEmpty()) {
                append(" No tools are registered for this run.")
            } else {
                append(" Available tools: ").append(available.joinToString(", "))
            }
        }

        confined(collector).emit(
            AgentEvent.ToolCompleted(
                callId = call.id,
                name = call.name,
                result = ToolResult.Error(message),
                truncated = false,
                durationMs = 0,
            ),
        )
        return message
    }

    /**
     * Park the tool until the user answers.
     *
     * The second `select` branch is what keeps a cancelled run from leaving the
     * tool suspended on a deferred nobody will ever resume — which would hold its
     * coroutine for the life of the process. Cancellation wins over a pending
     * answer; the abandoned deferred is simply never completed.
     */
    private suspend fun awaitApproval(
        tool: Tool,
        call: LlmToolCall,
        request: ApprovalRequest,
        chatId: String,
        collector: FlowCollector<AgentEvent>,
    ): ApprovalDecision {
        val events = confined(collector)

        val approvalId = "ap_${approvalIds.incrementAndGet()}"
        val waiter: CompletableDeferred<ApprovalDecision> = pending.register(approvalId, chatId)

        events.emit(
            AgentEvent.ApprovalRequired(
                AgentApproval(
                    id = approvalId,
                    chatId = chatId,
                    callId = call.id,
                    toolName = tool.name,
                    title = request.title,
                    body = request.body,
                    diff = request.diff,
                    options = request.options,
                ),
            ),
        )

        // Hoisted: a `select` clause block is not a coroutine body, so
        // currentCoroutineContext() cannot be called inside it.
        val runJob = currentCoroutineContext().job

        val decision = select {
            waiter.onAwait { it }
            runJob.onJoin {
                throw CancellationException("run cancelled while awaiting approval")
            }
        }

        currentCoroutineContext().ensureActive()
        return decision
    }
}

/** Tag a tool's incremental output with the call it belongs to. */
private fun ToolUpdate.eventFor(callId: String): AgentEvent = when (this) {
    is ToolUpdate.Output -> AgentEvent.ToolOutput(callId, line)
    is ToolUpdate.Progress -> AgentEvent.ToolProgress(callId, text)
}

/**
 * Capture the context this tool call was entered on, and return a collector that
 * hops back to it for every emit.
 *
 * The interceptor is read once, at the call site, rather than per emit: reading
 * `currentCoroutineContext()` inside the emit lambda would report the *tool's*
 * context — which is the one that is wrong.
 *
 * `ContinuationInterceptor` is captured on its own rather than the whole
 * [kotlin.coroutines.CoroutineContext], because a context carries its Job and
 * re-parenting every emit under it would detach the emission from the run's
 * cancellation.
 */
private suspend fun confined(collector: FlowCollector<AgentEvent>): ConfinedCollector =
    ConfinedCollector(collector, currentCoroutineContext()[ContinuationInterceptor])

private class ConfinedCollector(
    private val delegate: FlowCollector<AgentEvent>,
    /** The collector's dispatcher, captured where the tool call started. */
    private val interceptor: ContinuationInterceptor?,
) : FlowCollector<AgentEvent> {

    override suspend fun emit(value: AgentEvent) {
        val target = interceptor ?: return delegate.emit(value)

        // Hop only when the emit is actually arriving from somewhere else. The
        // *current* context here is the tool's — IO — which is precisely why the
        // collector's dispatcher had to be captured earlier; comparing against
        // the current one would always say "different" and always hop.
        //
        // Under a test dispatcher with no real threads the two are the same
        // instance, and withContext on the same dispatcher is a no-op, so this
        // stays a plain emit and the deterministic tests keep working.
        if (target === currentCoroutineContext()[ContinuationInterceptor]) {
            delegate.emit(value)
        } else {
            withContext(target) { delegate.emit(value) }
        }
    }
}
