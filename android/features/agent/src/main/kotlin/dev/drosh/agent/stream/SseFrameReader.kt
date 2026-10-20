package dev.drosh.agent.stream

/**
 * Server-sent-event framing, reduced to what a token stream actually needs.
 *
 * SSE is a text format with a lot of syntax a chat stream never uses: `event:`
 * names, `id:`, `retry:`, multi-line data continuation. Chat Completions sends
 * only unnamed `data:` frames and terminates with a literal `[DONE]`.
 *
 * Pulling this out as its own object rather than using OkHttp's `EventSource`
 * is deliberate. `EventSource` is a callback API, so using it from a `Flow`
 * means bridging callbacks back into a coroutine and giving up the ability to
 * drive the parser from a plain string in a unit test. The whole state machine
 * here is 20 lines and is fully covered by tests that feed it literal wire text.
 *
 * Comment lines (`:` keep-alive) and unknown fields are ignored rather than
 * treated as errors — providers send them as keep-alives during long tool runs.
 */
internal class SseFrameReader {

    private val data = StringBuilder()
    private val eventName = StringBuilder()

    /**
     * Feed one raw line.
     *
     * @return the assembled payload when this line completed a frame, otherwise
     *         null
     */
    fun accept(line: String): String? {
        when {
            // Blank line dispatches the accumulated frame.
            line.isEmpty() -> {
                if (data.isEmpty() && eventName.isEmpty()) return null
                val payload = data.toString()
                data.setLength(0)
                eventName.setLength(0)
                return payload
            }

            // Comment / keep-alive.
            line.startsWith(":") -> return null

            line.startsWith("event:") -> eventName.append(line.removePrefix("event:").trim())

            line.startsWith("data:") -> {
                // Exactly one optional space after the colon is stripped, per spec.
                val value = line.removePrefix("data:").removePrefix(" ")
                if (data.isNotEmpty()) data.append('\n')
                data.append(value)
            }

            // `id:` and `retry:` carry no meaning for a chat stream.
            else -> return null
        }
        return null
    }

    /**
     * Flush a frame that arrived without a trailing blank line.
     *
     * Some proxies close the connection mid-frame. Without this the last tool
     * call would be silently lost, which is exactly the kind of failure that
     * looks like the model "forgetting" what it was doing.
     */
    fun flush(): String? {
        if (data.isEmpty()) {
            eventName.setLength(0)
            return null
        }
        val payload = data.toString()
        data.setLength(0)
        eventName.setLength(0)
        return payload
    }

    /** The `event:` name of the last dispatched frame, for callers that care. */
    val lastEventName: String
        get() = eventName.toString()
}
