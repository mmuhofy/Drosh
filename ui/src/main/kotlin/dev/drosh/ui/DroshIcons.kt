package dev.drosh.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathData
import androidx.compose.ui.unit.dp
import compose.icons.lucideicons.ClipboardPaste
import compose.icons.lucideicons.ExternalLink
import compose.icons.lucideicons.ChevronRight
import compose.icons.lucideicons.Sun
import compose.icons.lucideicons.Moon
import compose.icons.lucideicons.Languages
import compose.icons.lucideicons.Palette
import compose.icons.lucideicons.Clock
import compose.icons.lucideicons.ShieldCheck
import compose.icons.LucideIcons
import compose.icons.lucideicons.ALargeSmall
import compose.icons.lucideicons.ArrowBigDown
import compose.icons.lucideicons.ArrowBigLeft
import compose.icons.lucideicons.ArrowBigRight
import compose.icons.lucideicons.ArrowBigUp
import compose.icons.lucideicons.ArrowDown
import compose.icons.lucideicons.ArrowLeft
import compose.icons.lucideicons.ArrowRight
import compose.icons.lucideicons.ArrowUp
import compose.icons.lucideicons.Check
import compose.icons.lucideicons.ChevronDown
import compose.icons.lucideicons.ChevronUp
import compose.icons.lucideicons.CircleUser
import compose.icons.lucideicons.CircleX
import compose.icons.lucideicons.Code
import compose.icons.lucideicons.Copy
import compose.icons.lucideicons.Download
import compose.icons.lucideicons.EllipsisVertical
import compose.icons.lucideicons.Gauge
import compose.icons.lucideicons.Globe
import compose.icons.lucideicons.Info
import compose.icons.lucideicons.Keyboard
import compose.icons.lucideicons.KeyboardOff
import compose.icons.lucideicons.Lock
import compose.icons.lucideicons.Monitor
import compose.icons.lucideicons.Maximize
import compose.icons.lucideicons.Minimize
import compose.icons.lucideicons.Minus
import compose.icons.lucideicons.PanelLeft
import compose.icons.lucideicons.Package
import compose.icons.lucideicons.Pencil
import compose.icons.lucideicons.Play
import compose.icons.lucideicons.Plus
import compose.icons.lucideicons.RotateCw
import compose.icons.lucideicons.Search
import compose.icons.lucideicons.Settings
import compose.icons.lucideicons.Share
import compose.icons.lucideicons.Shield
import compose.icons.lucideicons.Square
import compose.icons.lucideicons.SquarePlus
import compose.icons.lucideicons.Smartphone
import compose.icons.lucideicons.SquareTerminal
import compose.icons.lucideicons.Terminal
import compose.icons.lucideicons.Tablet
import compose.icons.lucideicons.Timer
import compose.icons.lucideicons.Trash2
import compose.icons.lucideicons.Type
import compose.icons.lucideicons.Undo
import compose.icons.lucideicons.X

object DroshIcons {
    val ALargeSmall: ImageVector get() = LucideIcons.ALargeSmall
    val ArrowBigDown: ImageVector get() = LucideIcons.ArrowBigDown
    val ArrowBigLeft: ImageVector get() = LucideIcons.ArrowBigLeft
    val ArrowBigRight: ImageVector get() = LucideIcons.ArrowBigRight
    val ArrowBigUp: ImageVector get() = LucideIcons.ArrowBigUp
    val ArrowDown: ImageVector get() = LucideIcons.ArrowDown
    val ArrowLeft: ImageVector get() = LucideIcons.ArrowLeft
    val ArrowRight: ImageVector get() = LucideIcons.ArrowRight
    val ArrowUp: ImageVector get() = LucideIcons.ArrowUp
    val ChevronRight: ImageVector get() = LucideIcons.ChevronRight
    val Sun: ImageVector get() = LucideIcons.Sun
    val Moon: ImageVector get() = LucideIcons.Moon
    val Languages: ImageVector get() = LucideIcons.Languages
    val Palette: ImageVector get() = LucideIcons.Palette
    val Resize: ImageVector get() = LucideIcons.Type
    val Clock: ImageVector get() = LucideIcons.Clock
    val Cursor: ImageVector get() = CursorVector
    val ShieldCheck: ImageVector get() = LucideIcons.ShieldCheck
    val Check: ImageVector get() = LucideIcons.Check
    val ClipboardPaste: ImageVector get() = LucideIcons.ClipboardPaste
    val ExternalLink: ImageVector get() = LucideIcons.ExternalLink
    val ChevronDown: ImageVector get() = LucideIcons.ChevronDown
    val ChevronUp: ImageVector get() = LucideIcons.ChevronUp
    val CircleUser: ImageVector get() = LucideIcons.CircleUser
    val Code: ImageVector get() = LucideIcons.Code
    val Copy: ImageVector get() = LucideIcons.Copy
    val Download: ImageVector get() = LucideIcons.Download
    val EllipsisVertical: ImageVector get() = LucideIcons.EllipsisVertical
    val Gauge: ImageVector get() = LucideIcons.Gauge
    val Globe: ImageVector get() = LucideIcons.Globe
    val Info: ImageVector get() = LucideIcons.Info
    val Keyboard: ImageVector get() = LucideIcons.Keyboard
    val KeyboardOff: ImageVector get() = LucideIcons.KeyboardOff
    val Lock: ImageVector get() = LucideIcons.Lock
    val Maximize: ImageVector get() = LucideIcons.Maximize
    val Minus: ImageVector get() = LucideIcons.Minus
    val Minimize: ImageVector get() = LucideIcons.Minimize
    val Monitor: ImageVector get() = LucideIcons.Monitor
    val Package: ImageVector get() = LucideIcons.Package
    val PanelLeft: ImageVector get() = LucideIcons.PanelLeft
    val Pencil: ImageVector get() = LucideIcons.Pencil
    val Play: ImageVector get() = LucideIcons.Play
    val Plus: ImageVector get() = LucideIcons.Plus
    val RotateCw: ImageVector get() = LucideIcons.RotateCw
    val Search: ImageVector get() = LucideIcons.Search
    val Settings: ImageVector get() = LucideIcons.Settings
    val Share: ImageVector get() = LucideIcons.Share
    val Shield: ImageVector get() = LucideIcons.Shield
    val Square: ImageVector get() = LucideIcons.Square
    val SquarePlus: ImageVector get() = LucideIcons.SquarePlus
    val Smartphone: ImageVector get() = LucideIcons.Smartphone
    val SquareTerminal: ImageVector get() = LucideIcons.SquareTerminal
    val Terminal: ImageVector get() = LucideIcons.Terminal
    val Tablet: ImageVector get() = LucideIcons.Tablet
    val Timer: ImageVector get() = LucideIcons.Timer
    val Trash2: ImageVector get() = LucideIcons.Trash2
    val Type: ImageVector get() = LucideIcons.Type
    val Undo: ImageVector get() = LucideIcons.Undo
    val X: ImageVector get() = LucideIcons.X
    val XCircle: ImageVector get() = LucideIcons.CircleX
}

/**
 * A text cursor, drawn here rather than pulled from the lucide set.
 *
 * This was aliased to `LucideIcons.Terminal`, which is a prompt chevron — a
 * `>` — so the cursor-style row was wearing the same glyph as the startup
 * command and build rows, and stacked on top of a chevron that was being
 * drawn at the wrong corner. Three identical prompt marks on one screen.
 *
 * Lucide has no I-beam at the weight the other toolbar glyphs use, so this is
 * a 24x24 path on the same grid: 12x2 serif bars top and bottom with a 3x14
 * stem. `Icon()` tints it like any other vector.
 */
private val CursorVector: ImageVector = ImageVector.Builder(
    name = "DroshTextCursor",
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f,
).addPath(
    pathData = PathData {
        moveTo(6f, 3f)
        horizontalLineTo(18f)
        verticalLineToRelative(2f)
        horizontalLineTo(13.5f)
        verticalLineTo(19f)
        horizontalLineTo(18f)
        verticalLineToRelative(2f)
        horizontalLineTo(6f)
        verticalLineTo(19f)
        horizontalLineTo(10.5f)
        verticalLineTo(5f)
        horizontalLineTo(6f)
        close()
    },
    fill = SolidColor(Color.Black),
).build()
