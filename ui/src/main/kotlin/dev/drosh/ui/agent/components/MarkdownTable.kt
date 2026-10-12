package dev.drosh.ui.agent.components

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mikepenz.markdown.compose.components.MarkdownComponentModel
import dev.drosh.design.system.DroshOutline
import dev.drosh.design.system.DroshSurfaceLow
import dev.drosh.design.system.DroshSurfaceVariant
import dev.drosh.design.system.DroshText
import dev.drosh.design.system.DroshTextSecondary
import dev.drosh.design.system.LocalFontSet
import dev.drosh.ui.R
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.ast.getTextInNode

/**
 * A GFM table.
 *
 * ## Why it is drawn here
 *
 * The renderer parses GFM tables — the parser is `org.jetbrains:markdown` and it
 * has always handled the pipe form — but a table rendered as its literal source
 * is not a table. It is three lines of punctuation with the answer somewhere
 * inside them, and anyone reading an answer on a phone scrolls straight past it.
 * So the AST is read here and the rows and cells are laid out as what they are.
 *
 * ## Why it scrolls sideways rather than wrapping
 *
 * A table whose columns wrap stops having columns. Two rows of the same table
 * would then show different cells under different headers, which is a table
 * that lies. Horizontal scroll keeps the alignment honest and costs a finger
 * swipe on a table too wide for the screen — which is a table that was too wide
 * for the screen.
 */
@Composable
fun DroshMarkdownTable(model: MarkdownComponentModel) {
    val table = remember(model.content, model.node) { model.node.parseTable(model.content) }
    if (table.rows.isEmpty()) return

    val mono = LocalFontSet.current.mono
    val scroll = rememberScrollState()
    val tableCd = stringResource(R.string.agent_table)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(CODE_BLOCK_CORNER))
            .background(DroshSurfaceLow)
            .horizontalScroll(scroll)
            .semantics { contentDescription = tableCd },
    ) {
        Column(modifier = Modifier.padding(vertical = 8.dp)) {
            table.rows.forEachIndexed { index, row ->
                val isHeader = index == 0 && table.hasHeader
                Row(
                    modifier = Modifier
                        .then(
                            if (isHeader) {
                                Modifier.background(
                                    DroshSurfaceVariant,
                                )
                            } else {
                                Modifier
                            },
                        )
                        .padding(vertical = 5.dp),
                ) {
                    row.cells.forEach { cell ->
                        Text(
                            text = cell,
                            fontSize = 12.sp,
                            lineHeight = 17.sp,
                            fontFamily = mono,
                            fontWeight = if (isHeader) FontWeight.SemiBold else null,
                            color = if (isHeader) {
                                DroshText
                            } else {
                                DroshTextSecondary
                            },
                            modifier = Modifier
                                .width(TABLE_CELL_WIDTH)
                                .padding(horizontal = 10.dp),
                        )
                    }
                }
                // A rule under the header, and nowhere else: it separates the
                // column names from the values without turning every pair of
                // rows into a separate table.
                if (isHeader) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 1.dp)
                            .background(DroshOutline),
                    )
                }
            }
        }
    }
}

/** Fixed cell width, so every column of every row lines up. */
private val TABLE_CELL_WIDTH = 112.dp

/**
 * One table's rows, as text.
 *
 * The AST shape is `TABLE > TABLE_HEADER > TABLE_CELL`, then `TABLE_ROW >
 * TABLE_CELL`. The separator row — the `---` line — is dropped rather than
 * drawn: a rule under the header is one line, and a second would be the same
 * line twice.
 */
private data class MarkdownTable(
    val rows: List<MarkdownTableRow>,
    val hasHeader: Boolean,
)

private data class MarkdownTableRow(val cells: List<String>)

private fun ASTNode.parseTable(content: CharSequence): MarkdownTable {
    val rows = mutableListOf<MarkdownTableRow>()
    var hasHeader = false

    children.forEach { child ->
        when (child.type) {
            MarkdownElementTypes.TABLE_HEADER -> {
                hasHeader = true
                rows += MarkdownTableRow(child.cells(content))
            }
            MarkdownElementTypes.TABLE_ROW -> {
                // The separator row is `TABLE_ROW` with only `---|` cells; one
                // that has a table-cell child is a real row.
                if (child.children.any { it.type == MarkdownElementTypes.TABLE_CELL }) {
                    rows += MarkdownTableRow(child.cells(content))
                }
            }
        }
    }

    return MarkdownTable(rows, hasHeader)
}

/** The text of every `TABLE_CELL` under this node. */
private fun ASTNode.cells(content: CharSequence): List<String> {
    val cells = mutableListOf<String>()
    forEachChildOfType(MarkdownElementTypes.TABLE_CELL) { cell ->
        // A cell's own children are the inline content; its raw span includes the
        // surrounding pipes, which are not part of the value.
        val value = cell.children.joinToString("") { it.getTextInNode(content).toString().trim() }
        cells += value.ifBlank { "" }
    }
    return cells
}

/** Walks the direct children of [type] without an extension import. */
private inline fun ASTNode.forEachChildOfType(
    type: org.intellij.markdown.IElementType,
    block: (ASTNode) -> Unit,
) {
    for (child in children) {
        if (child.type == type) block(child)
    }
}
