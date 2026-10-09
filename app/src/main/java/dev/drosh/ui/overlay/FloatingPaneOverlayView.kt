package dev.drosh.ui.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import dev.drosh.R
import dev.drosh.domain.terminal.PaneSlot
import dev.drosh.terminal.TerminalManager
import com.termux.view.TerminalView

/**
 * The overlay's view tree: a title bar, a [TerminalView], and nothing else.
 *
 * Built from plain Views rather than Compose on purpose — see
 * [FloatingPaneOverlayService] for why that matters. The tree is built once and
 * afterwards only repositioned, so nothing here ever needs to recompose.
 *
 * This is deliberately the smallest version that is useful: a pane over another
 * app, with a title and a way out. Moving and resizing it are the next layer, and
 * they are not here because a drag gesture and a terminal's own scroll gesture
 * have to be told apart before they can share a window — getting that wrong makes
 * the terminal unusable in the overlay, which is worse than a fixed-size pane.
 *
 * @param slot the pane this view stands in for. Passed in rather than guessed:
 * the overlay is handed whichever pane was detached, and the title bar's focus
 * behaviour has to refer to that same pane.
 */
@SuppressLint("ViewConstructor")
class FloatingPaneOverlayView(
    context: Context,
    private val terminalManager: TerminalManager,
    private val slot: PaneSlot,
    /**
     * The monospace face for the terminal, resolved from the stored font pack
     * by the service — a plain View tree has no composition to read the theme
     * from. Null falls back to the platform monospace.
     */
    private val terminalTypeface: Typeface? = null,
) : LinearLayout(context) {

    val terminalView: TerminalView = TerminalView(context, null).apply {
        setTypeface(terminalTypeface ?: Typeface.MONOSPACE)
    }

    /**
     * True while this pane holds the keyboard.
     *
     * Named for the keyboard rather than simply `isFocused` because `View` already
     * has an `isFocused()` getter with that exact JVM signature, and a property
     * named after it is an accidental override. The distinction is real too:
     * Android focus here means "takes key events", while what the user is asking
     * about is the IME, and a pane can hold one without the other.
     */
    var paneFocused: Boolean = false
        private set

    /** Invoked when the user closes the pane. */
    var onClose: (() -> Unit)? = null

    private val titleText: TextView

    init {
        orientation = VERTICAL

        background = GradientDrawable().apply {
            cornerRadius = dp(CORNER_DP).toFloat()
            setColor(COLOR_SURFACE)
        }
        elevation = dp(ELEVATION_DP).toFloat()

        val titleBar = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(TITLE_PADDING_DP), 0, dp(ICON_DP) / 2, 0)
            setBackgroundColor(COLOR_TITLE_BAR)
        }
        addView(titleBar, LayoutParams(LayoutParams.MATCH_PARENT, dp(TITLE_BAR_DP)))

        titleText = TextView(context).apply {
            setTextColor(COLOR_TITLE_TEXT)
            textSize = TITLE_TEXT_SP
            maxLines = 1
            // A floating pane is narrow by definition, so the title gives up space
            // before the close button is pushed off the edge — losing the title
            // is recoverable, losing the way out is not.
            layoutParams = LayoutParams(0, LayoutParams.MATCH_PARENT, 1f)
        }
        titleBar.addView(titleText)

        val closeButton = ImageButton(context).apply {
            setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
            colorFilter = android.graphics.PorterDuffColorFilter(Color.WHITE, android.graphics.PorterDuff.Mode.SRC_IN)
            setBackgroundColor(Color.TRANSPARENT)
            contentDescription = context.getString(R.string.overlay_pane_close)
            setOnClickListener {
                // Give the keyboard back before going, or the app underneath does
                // not get the IME back until it focuses something itself.
                setFocused(false)
                onClose?.invoke()
            }
        }
        titleBar.addView(closeButton, LayoutParams(dp(ICON_DP), LayoutParams.MATCH_PARENT))

        addView(terminalView, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
    }

    fun setTitle(text: String) {
        titleText.text = text
    }

    /**
     * Focuses the pane, or gives the focus back.
     *
     * Focusing the *terminal view* rather than the pane is what makes the keyboard
     * appear, and unfocusing calls `hideKeyboard` explicitly because a window that
     * simply loses focus does not necessarily dismiss the IME — leaving it up over
     * the app underneath would make this pane feel like it had not really let go.
     */
    fun setFocused(focused: Boolean) {
        if (paneFocused == focused) return
        paneFocused = focused
        if (focused) {
            terminalManager.focusPane(slot)
            terminalView.requestFocus()
            terminalView.showKeyboard()
        } else {
            terminalView.hideKeyboard()
        }
    }

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            resources.displayMetrics,
        ).toInt()

    companion object {
        const val EXTRA_SLOT = "dev.drosh.extra.OVERLAY_SLOT"

        const val DEFAULT_WIDTH_DP = 240
        const val DEFAULT_HEIGHT_DP = 320

        private const val TITLE_BAR_DP = 36
        private const val TITLE_PADDING_DP = 12
        private const val ICON_DP = 32
        private const val CORNER_DP = 12
        private const val ELEVATION_DP = 12
        private const val TITLE_TEXT_SP = 13f

        private const val COLOR_SURFACE = 0xFF111318.toInt()
        private const val COLOR_TITLE_BAR = 0xFF1A1D24.toInt()
        private const val COLOR_TITLE_TEXT = 0xFFE8EAF0.toInt()
    }
}