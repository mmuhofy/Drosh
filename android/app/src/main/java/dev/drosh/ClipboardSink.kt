package dev.drosh

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * The terminal's clipboard sink, reached from the native engine.
 *
 * ## Who calls it
 *
 * The Rust engine invokes [write] — through JNI, as a static method — when a
 * program running in a terminal asks to write the clipboard with `OSC 52`,
 * iTerm2's `OSC 1337` Copy, or the kitty clipboard protocol (`OSC 5522`). The
 * call arrives on the session's PTY reader thread, which is why nothing here
 * touches the clipboard directly: the write is posted to the main thread.
 *
 * ## Why the write is refused by default
 *
 * A clipboard write is the one escape sequence that reaches *outside* the
 * terminal: a program behind an `ssh` connection, or a `cat` of a file
 * somebody else wrote, could otherwise replace what the user next pastes. So
 * the default is to refuse, and [allowWrites] is the gate the app would open
 * if — and only if — the user asked for it. There is no setting for it yet, so
 * it stays closed; the mechanism is here so the day the setting exists, nothing
 * in the native half has to change.
 *
 * The engine's own reply is synchronous, so [write] answers before the posted
 * write lands. That is the design the engine's callback expects — the VT stream
 * is paused while the callback runs — and a refused program is told so through
 * the matching protocol status.
 */
object ClipboardSink {

    private const val TAG = "ClipboardSink"

    /** The label the clipboard shows for the paste it holds. */
    private const val LABEL = "terminal"

    private val main = Handler(Looper.getMainLooper())

    /** The application context, set once at startup. */
    @Volatile
    private var applicationContext: Context? = null

    /**
     * Whether a program may write the clipboard.
     *
     * Off, and off is the answer this build gives: there is no OSC 52 setting
     * to read, and writing a program's choice over the user's clipboard without
     * one is the wrong default. A future settings surface turns this on.
     */
    @Volatile
    var allowWrites: Boolean = false

    /** Hand the sink the context it writes through. Called from `Application`. */
    fun attach(context: Context) {
        applicationContext = context.applicationContext
    }

    /**
     * Accept or refuse a clipboard write from the engine.
     *
     * Runs on the session's reader thread. Returns true only when the write was
     * accepted *and* posted — false covers both "not allowed" and "nowhere to
     * write", and the native side turns either into a deny.
     *
     * `@JvmStatic` is what makes the method reachable as
     * `dev.drosh.ClipboardSink.write(String): Boolean`, the signature the Rust
     * bridge calls.
     */
    @JvmStatic
    fun write(text: String): Boolean {
        if (text.isEmpty() || !allowWrites) return false
        val context = applicationContext ?: return false

        // The clipboard is the platform's, and the platform wants the UI
        // thread. A blocking write here would stall the VT stream the engine
        // has paused for this callback, so the write is posted and the reply is
        // an optimistic accept.
        main.post {
            runCatching {
                val clipboard =
                    context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                clipboard?.setPrimaryClip(ClipData.newPlainText(LABEL, text))
            }.onFailure { Log.w(TAG, "the clipboard write failed", it) }
        }
        return true
    }
}
