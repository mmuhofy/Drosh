package dev.drosh.terminal.engine.input

import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

/**
 * What the input path actually did.
 *
 * The renderer counts its own frames; input had no equivalent, so a keyboard
 * that felt slow could only be described, not measured. These are monotone
 * counters — same rule as the frame stats — so a report copied off the screen
 * is a snapshot that cannot change while it is being read.
 *
 * The hot path does one atomic add per event and a `nanoTime` pair. That is
 * deliberately cheaper than a lock: these numbers are read from the UI thread
 * while keys arrive on the IME thread.
 */
object InputStats {

    private val keys = AtomicLong()
    private val mouseEvents = AtomicLong()
    private val pastes = AtomicLong()
    private val bytesWritten = AtomicLong()
    private val encodeNanos = AtomicLong()
    private val maxEncodeNanos = AtomicLong()

    @Volatile
    private var lastEvent: String = "none"

    /** Record one encoded key. [written] is the byte count the native call returned. */
    fun key(keyCode: Int, written: Int, nanos: Long) {
        keys.incrementAndGet()
        record(written, nanos)
        lastEvent = "key $keyCode"
    }

    /** Record one encoded mouse event. */
    fun mouse(action: Int, written: Int, nanos: Long) {
        mouseEvents.incrementAndGet()
        record(written, nanos)
        lastEvent = "mouse $action"
    }

    /** Record a paste: a byte array made on this side and copied to the child. */
    fun paste(length: Int) {
        pastes.incrementAndGet()
        bytesWritten.addAndGet(length.toLong())
        lastEvent = "paste $length"
    }

    private fun record(written: Int, nanos: Long) {
        if (written > 0) bytesWritten.addAndGet(written.toLong())
        if (nanos > 0) {
            encodeNanos.addAndGet(nanos)
            maxEncodeNanos.accumulateAndGet(nanos) { a, b -> maxOf(a, b) }
        }
    }

    fun reset() {
        keys.set(0)
        mouseEvents.set(0)
        pastes.set(0)
        bytesWritten.set(0)
        encodeNanos.set(0)
        maxEncodeNanos.set(0)
        lastEvent = "none"
    }

    /** The multi-line block the diagnostics panel puts under `--- input ---`. */
    fun report(): String {
        val count = keys.get() + mouseEvents.get() + pastes.get()
        val average = if (count == 0L) 0f else encodeNanos.get() / count / 1_000f
        return buildString {
            appendLine("keys ${keys.get()}  mouse ${mouseEvents.get()}  pastes ${pastes.get()}")
            appendLine("bytes to child ${bytesWritten.get()}")
            appendLine(
                String.format(
                    Locale.ROOT,
                    "encode avg %.3f ms  max %.3f ms  last %s",
                    average,
                    maxEncodeNanos.get() / 1_000f,
                    lastEvent,
                ),
            )
        }
    }
}
