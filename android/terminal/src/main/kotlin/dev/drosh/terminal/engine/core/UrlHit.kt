package dev.drosh.terminal.engine.core

/**
 * A URL the engine found on the grid.
 *
 * A URL the shell soft-wrapped across two rows is reported once per row: the
 * position parts say where that row's part is, and [text] is the whole link,
 * because a tap on any of its rows opens the whole thing.
 *
 * @property row the visible row the part is on
 * @property col the character column where the part starts
 * @property len the character length of the part
 * @property text the whole URL, across the rows it covers
 */
data class UrlHit(
    val row: Int,
    val col: Int,
    val len: Int,
    val text: String,
)
