package dev.drosh.agent.provider

/**
 * Decides whether a failed request is worth sending again.
 *
 * Getting this wrong in either direction is expensive. Retry everything and a
 * malformed tool schema — which will never succeed — burns three attempts and
 * fifteen seconds every single turn. Retry nothing and a transient 429 kills a
 * run that was one second from succeeding.
 *
 * The split is by status code, because that is the only signal available before
 * the provider has parsed the request, and by exception type, because a dropped
 * connection mid-stream is unambiguously transient.
 */
internal object ProviderErrorClassifier {

    enum class Decision {
        /** Send the same request again after a backoff. */
        RETRY,

        /** Do not resend; the request itself is wrong. */
        FATAL,

        /** Credentials are missing or rejected — retrying cannot help. */
        NEEDS_CREDENTIALS,
    }

    fun classify(statusCode: Int): Decision = when {
        statusCode == 401 || statusCode == 403 -> Decision.NEEDS_CREDENTIALS

        // Rate limiting and server-side faults clear on their own.
        statusCode == 408 || statusCode == 429 -> Decision.RETRY
        statusCode in 500..599 -> Decision.RETRY

        // 402 payment required is a credential problem the user must resolve.
        statusCode == 402 -> Decision.NEEDS_CREDENTIALS

        // 400 and 404 are mistakes in the request; 409/422 are rejected content.
        else -> Decision.FATAL
    }

    fun classify(error: Throwable): Decision = when (error) {
        // Cancellation is not an error to classify — the caller checks for it
        // before ever getting here.
        is kotlinx.coroutines.CancellationException -> Decision.FATAL

        // The body ended early or the connection dropped. Resending is safe
        // because the request is idempotent from the provider's perspective.
        is java.io.IOException -> Decision.RETRY

        // A JSON parse failure on our side of a 200 response means we cannot
        // read the answer; the next attempt may parse.
        else -> Decision.FATAL
    }

    /**
     * Backoff for attempt [attempt], counting from 1.
     *
     * Doubling from a 1s base with a 15s ceiling: long enough that a provider
     * enforcing a cooldown has actually reset, short enough that a user watching
     * a spinner does not assume the app has hung.
     */
    fun backoffMs(attempt: Int, baseMs: Long, maxMs: Long): Long {
        var delay = baseMs
        repeat((attempt - 1).coerceAtLeast(0)) {
            delay = (delay * 2).coerceAtMost(maxMs)
        }
        return delay.coerceAtMost(maxMs)
    }
}
