package dev.drosh.agent.provider

import dev.drosh.domain.agent.LlmStep
import dev.drosh.domain.agent.StreamEvent
import dev.drosh.domain.agent.StreamRequest
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.map
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class OpenAiSseAdapter @Inject constructor() : ProviderAdapter {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    override suspend fun stream(request: StreamRequest): Flow<StreamEvent> = callbackFlow {
        val body = buildRequestBody(request)
        val isProviderUrl = request.endpoint.contains("openrouter", ignoreCase = true)

        val httpRequest = Request.Builder()
            .url(request.endpoint)
            .post(body.toString().toRequestBody(jsonMediaType))
            .addHeader("Authorization", "Bearer ${request.apiKey}")
            .addHeader("Content-Type", "application/json")
            .apply {
                if (isProviderUrl) {
                    header("HTTP-Referer", "https://github.com/mmuhofy/IrisCode")
                    header("X-Title", "Drosh")
                }
            }
            .build()

        val listener = object : EventSourceListener() {
            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                if (data == "[DONE]" || data.isEmpty()) return
                trySend(data)
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: okhttp3.Response?) {
                val errorMsg = when {
                    t != null -> t.message ?: "Unknown error"
                    response != null -> {
                        val body = response.body?.string()
                        "HTTP ${response.code}: $body"
                    }
                    else -> "Unknown SSE failure"
                }
                val escaped = JSONObject.quote(errorMsg)
                trySend("{\"error\":$escaped}")
                close()
            }

            override fun onClosed(eventSource: EventSource) {
                close()
            }
        }

        val eventSource = EventSources.createFactory(httpClient)
            .newEventSource(httpRequest, listener)

        awaitClose { eventSource.cancel() }
    }.buffer(Channel.UNLIMITED)
        .map { jsonLine -> parseSseLine(jsonLine) }

    private fun parseSseLine(jsonLine: String): StreamEvent {
        return try {
            val obj = JSONObject(jsonLine)
            if (obj.has("error")) {
                val errorVal = obj.opt("error")
                val errorMsg = when (errorVal) {
                    is JSONObject -> errorVal.optString("message", errorVal.toString())
                    is String -> errorVal
                    else -> errorVal.toString()
                }
                return StreamEvent.Error(errorMsg)
            }

            val choices = obj.optJSONArray("choices") ?: return StreamEvent.StreamEnd
            if (choices.length() == 0) return StreamEvent.StreamEnd

            val choice = choices.getJSONObject(0)

            choice.optJSONObject("delta")?.let { delta ->
                delta.optString("content")?.takeIf { it.isNotBlank() }?.let {
                    return StreamEvent.TextDelta(it)
                }

                if (delta.has("tool_calls")) {
                    val toolCalls = delta.getJSONArray("tool_calls")
                    for (i in 0 until toolCalls.length()) {
                        val tc = toolCalls.getJSONObject(i)
                        val index = tc.getInt("index")
                        val fn = tc.getJSONObject("function")

                        fn.optString("name")?.takeIf { it.isNotBlank() }?.let { name ->
                            return StreamEvent.ToolCallStart(
                                index = index,
                                id = fn.optString("id", ""),
                                name = name
                            )
                        }

                        fn.optString("arguments")?.takeIf { it.isNotBlank() }?.let { args ->
                            return StreamEvent.ToolCallDelta(index = index, argsDelta = args)
                        }
                    }
                }

                delta.optString("reasoning_content")?.takeIf { it.isNotBlank() }?.let {
                    return StreamEvent.ReasoningDelta(it)
                }
            }

            choice.optString("finish_reason")?.takeIf { it.isNotBlank() }?.let {
                return StreamEvent.ReasoningComplete
            }

            StreamEvent.StreamEnd
        } catch (e: Exception) {
            StreamEvent.Error("Parse error: ${e.message}")
        }
    }

    private fun buildRequestBody(request: StreamRequest): JSONObject {
        val contents = JSONArray()

        if (!request.systemPrompt.isNullOrBlank()) {
            val systemMsg = JSONObject()
            systemMsg.put("role", "system")
            systemMsg.put("content", request.systemPrompt)
            contents.put(systemMsg)
        }

        request.messages.forEach { step ->
            val msg = JSONObject()
            when (step) {
                is LlmStep.User -> {
                    msg.put("role", "user")
                    msg.put("content", step.text)
                    contents.put(msg)
                }
                is LlmStep.Assistant -> {
                    msg.put("role", "assistant")
                    msg.put("content", step.text.ifEmpty { null })
                    if (step.toolCalls.isNotEmpty()) {
                        val tcArray = JSONArray()
                        step.toolCalls.forEach { tc ->
                            val tcObj = JSONObject()
                            tcObj.put("id", tc.id)
                            tcObj.put("type", "function")
                            val fn = JSONObject()
                            fn.put("name", tc.name)
                            fn.put("arguments", JSONObject(tc.arguments).toString())
                            tcObj.put("function", fn)
                            tcArray.put(tcObj)
                        }
                        msg.put("tool_calls", tcArray)
                    }
                    contents.put(msg)
                }
                is LlmStep.ToolCall -> {
                    msg.put("role", "assistant")
                    msg.put("content", null)
                    val tcObj = JSONObject()
                    tcObj.put("id", step.id)
                    tcObj.put("type", "function")
                    val fn = JSONObject()
                    fn.put("name", step.name)
                    fn.put("arguments", JSONObject(step.arguments).toString())
                    tcObj.put("function", fn)
                    val tcArray = JSONArray()
                    tcArray.put(tcObj)
                    msg.put("tool_calls", tcArray)
                    contents.put(msg)
                }
                is LlmStep.ToolResult -> {
                    msg.put("role", "tool")
                    msg.put("tool_call_id", step.callId)
                    msg.put("content", step.result)
                    contents.put(msg)
                }
            }
        }

        val toolsArray = JSONArray()
        request.tools.forEach { tool ->
            val toolObj = JSONObject()
            val fn = JSONObject()
            fn.put("name", tool.name)
            fn.put("description", tool.description)
            fn.put("parameters", tool.parameters)
            toolObj.put("type", "function")
            toolObj.put("function", fn)
            toolsArray.put(toolObj)
        }

        return JSONObject().apply {
            put("model", request.model)
            put("messages", contents)
            put("stream", true)
            put("tools", toolsArray)
        }
    }
}