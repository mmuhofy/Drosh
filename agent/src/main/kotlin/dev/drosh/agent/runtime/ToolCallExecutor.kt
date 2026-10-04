package dev.drosh.agent.runtime

import dev.drosh.agent.tool.ToolRegistry
import dev.drosh.domain.agent.AgentApproval
import dev.drosh.domain.agent.AgentEvent
import dev.drosh.domain.agent.ApprovalDecision
import dev.drosh.domain.agent.ApprovalRequest
import dev.drosh.domain.agent.LlmToolCall
import dev.drosh.domain.agent.ToolContext
import dev.drosh.domain.agent.ToolOutputTrimmer
import dev.drosh.domain.agent.ToolResult
import dev.drosh.domain.agent.ToolUpdate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.job
import kotlinx.coroutines.selects.select
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
        emit: FlowCollector<AgentEvent>,
    ): String {
        val startedAt = System.currentTimeMillis()
        val tool = registry.find(call.name)

        if (tool == null) {
            // Not fatal. The model is told what it could have called and gets
            // another turn; ending the run over a hallucinated name discards
            // everything that led up to it.
            return unknownTool(call, emit)
        }

        emit(
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
                    emit = { update -> emit(update.eventFor(call.id)) },
                    awaitApproval = { request ->
                        awaitApproval(tool, call, request, chatId, emit)
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

        emit(
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
        emit: FlowCollector<AgentEvent>,
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

        emit(
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
        emit: FlowCollector<AgentEvent>,
    ): ApprovalDecision {
        val approvalId = "ap_${approvalIds.incrementAndGet()}"
        val waiter: CompletableDeferred<ApprovalDecision> = pending.register(approvalId, chatId)

        emit(
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

        val decision = select {
            waiter.onAwait { it }
            currentCoroutineContext().job.onJoin {
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
