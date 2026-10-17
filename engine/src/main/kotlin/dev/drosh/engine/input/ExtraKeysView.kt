package dev.drosh.engine.input

import android.content.Context
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The extra-keys row (decision D6): the keys a soft keyboard cannot send.
 *
 * Phase B ships Ctrl, Esc, Tab and the four arrows. Ctrl is *sticky* — it arms
 * the next key and disarms itself, which is how a phone sends Ctrl+C: arm Ctrl,
 * then type `c` (or press it twice, which is why it stays visible as armed).
 *
 * Input methods commit text instead of key events, so the row exposes its armed
 * state ([ctrlArmed] / [clearCtrl]) for the activity to apply to whatever the
 * keyboard sends next.
 */
internal class ExtraKeysView(context: Context) : LinearLayout(context) {

    /** Called for a row key: which key, and whether Ctrl was armed for it. */
    var onKey: ((Encoder.Named, Boolean) -> Unit)? = null

    /** Copy the current selection to the clipboard. */
    var onCopy: (() -> Unit)? = null

    /** Paste the clipboard into the session. */
    var onPaste: (() -> Unit)? = null

    /** Run the Phase C gate probe (`chroot-ng --probe`) and show its output. */
    var onProbe: (() -> Unit)? = null

    /** Install (if needed) and enter the Ubuntu guest. */
    var onGuest: (() -> Unit)? = null

    /** Called when the armed state changes, so the activity can show it. */
    var onCtrlChanged: ((Boolean) -> Unit)? = null

    private val ctrlButton: TextView

    var ctrlArmed = false
        private set

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setBackgroundColor(BACKGROUND)
        setPadding(PADDING, PADDING / 2, PADDING, PADDING / 2)

        ctrlButton = addButton("Ctrl") {
            setCtrlArmed(!ctrlArmed)
        }
        addButton("Esc") { emit(Encoder.Named.Escape) }
        addButton("Tab") { emit(Encoder.Named.Tab) }
        // The arrows report as a group so a future momentum scroll can reuse the
        // same layout.
        addButton("\u2190") { emit(Encoder.Named.Left) }
        addButton("\u2191") { emit(Encoder.Named.Up) }
        addButton("\u2193") { emit(Encoder.Named.Down) }
        addButton("\u2192") { emit(Encoder.Named.Right) }
        // Long-press selects a word, this copies it; Paste is also the only way
        // to get text *into* the session without typing it.
        addButton("Copy") { onCopy?.invoke() }
        addButton("Paste") { onPaste?.invoke() }
        // Phase C's gate is a device measurement, so it needs a button: the
        // probe prints the kernel facts the guest runtime depends on.
        addButton("Probe") { onProbe?.invoke() }
        // Phase C's payoff: the host shell is one process, the guest is another.
        addButton("Ubuntu") { onGuest?.invoke() }
    }

    fun clearCtrl() = setCtrlArmed(false)

    private fun emit(named: Encoder.Named) {
        onKey?.invoke(named, ctrlArmed)
        clearCtrl()
    }

    private fun setCtrlArmed(armed: Boolean) {
        ctrlArmed = armed
        ctrlButton.setBackgroundColor(if (armed) ACCENT else KEY_BACKGROUND)
        ctrlButton.setTextColor(if (armed) BACKGROUND else FOREGROUND)
        onCtrlChanged?.invoke(armed)
    }

    private fun addButton(label: String, onClick: () -> Unit): TextView {
        val button = TextView(context)
        button.text = label
        button.gravity = Gravity.CENTER
        button.typeface = Typeface.MONOSPACE
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        button.setTextColor(FOREGROUND)
        button.setBackgroundColor(KEY_BACKGROUND)
        button.setPadding(PADDING, PADDING / 2, PADDING, PADDING / 2)
        button.isClickable = true
        button.setOnClickListener { onClick() }

        val params = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
        params.marginEnd = PADDING / 2
        addView(button, params)
        return button
    }

    private companion object {
        const val PADDING = 12
        const val BACKGROUND = 0xff101010.toInt()
        const val KEY_BACKGROUND = 0xff2a2a2a.toInt()
        const val FOREGROUND = 0xffd8d8d8.toInt()
        const val ACCENT = 0xfff4bf75.toInt()
    }
}
