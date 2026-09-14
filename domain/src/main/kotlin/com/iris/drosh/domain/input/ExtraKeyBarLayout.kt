package com.iris.irisshell.domain.input

/**
 * Default 2-row layout for the on-screen extra-keys bar.
 *
 * Mirrors the spec in TODO.md §"Keyboard Handle & Extra Keys":
 *
 *   Row 1: [ESC] [TAB] [CTRL] [ALT]  [←] [↓] [↑] [→]
 *   Row 2: [HOME] [END] [PGUP] [PGDN]  [-] [|]
 *
 * Pure-Kotlin, no Android imports — UI layer renders this in a FlowRow.
 */
object ExtraKeyBarLayout {

    val rows: List<List<ExtraKey>> = listOf(
        listOf(
            ExtraKey.Navigation.ESC,
            ExtraKey.Navigation.TAB,
            ExtraKey.Special.CTRL,
            ExtraKey.Special.ALT,
            ExtraKey.Navigation.ARROW_LEFT,
            ExtraKey.Navigation.ARROW_DOWN,
            ExtraKey.Navigation.ARROW_UP,
            ExtraKey.Navigation.ARROW_RIGHT,
        ),
        listOf(
            ExtraKey.Navigation.HOME,
            ExtraKey.Navigation.END,
            ExtraKey.Navigation.PAGE_UP,
            ExtraKey.Navigation.PAGE_DOWN,
            ExtraKey.Text("-"),
            ExtraKey.Text("|"),
        ),
    )

    /**
     * Long-press popup targets — what shortcut combinations appear when
     * the user holds down CTRL or ALT. Each entry is sent as a single
     * synthesized "input" gesture (see `InputIntent`).
     */
    val ctrlPopupCombos: List<List<ExtraKey>> = listOf(
        listOf(ExtraKey.Special.CTRL, ExtraKey.Text("c")),
        listOf(ExtraKey.Special.CTRL, ExtraKey.Text("z")),
        listOf(ExtraKey.Special.CTRL, ExtraKey.Text("x")),
        listOf(ExtraKey.Special.CTRL, ExtraKey.Text("v")),
        listOf(ExtraKey.Special.CTRL, ExtraKey.Text("l")),
        listOf(ExtraKey.Special.CTRL, ExtraKey.Text("a")),
        listOf(ExtraKey.Special.CTRL, ExtraKey.Text("e")),
    )

    val altPopupCombos: List<List<ExtraKey>> = listOf(
        listOf(ExtraKey.Special.ALT, ExtraKey.Text("b")),
        listOf(ExtraKey.Special.ALT, ExtraKey.Text("f")),
    )
}
