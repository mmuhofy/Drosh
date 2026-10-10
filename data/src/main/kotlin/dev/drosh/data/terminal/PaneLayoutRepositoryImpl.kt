package dev.drosh.data.terminal

import dev.drosh.domain.settings.SessionSettings
import dev.drosh.domain.settings.SettingsStore
import dev.drosh.domain.terminal.NormalizedRect
import dev.drosh.domain.terminal.PaneLayout
import dev.drosh.domain.terminal.PaneLayoutRepository
import dev.drosh.domain.terminal.PanePresentation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * TOML-backed [PaneLayoutRepository].
 *
 * Reads never throw: a value the file cannot offer yields [PaneLayout.EMPTY] —
 * a stored value that is out of range is clamped through the model's own
 * mutators, exactly as before, because a zero-width pane is worse than a lost
 * arrangement.
 *
 * The pane is runtime state, but it is the user's arrangement, so it lives
 * with the rest of their settings rather than in a store of its own.
 */
@Singleton
class PaneLayoutRepositoryImpl @Inject constructor(
    private val store: SettingsStore,
) : PaneLayoutRepository {

    override val layout: Flow<PaneLayout> =
        store.settings.map { it.session.toLayout() }

    override suspend fun setLayout(layout: PaneLayout) {
        store.update { current ->
            current.copy(session = current.session.from(layout))
        }
    }

    override suspend fun setSplitFraction(fraction: Float) {
        store.update { current ->
            current.copy(session = current.session.copy(splitFraction = fraction))
        }
    }

    override suspend fun clear() {
        store.update { current ->
            current.copy(session = SessionSettings.DEFAULT)
        }
    }

    private fun SessionSettings.toLayout(): PaneLayout {
        if (secondarySessionId.isBlank()) return PaneLayout.EMPTY
        var layout = PaneLayout(secondarySessionId = secondarySessionId, presentation = presentation)
        layout = layout.withSplitFraction(splitFraction)
        if (presentation == PanePresentation.FLOATING) {
            val bounds = NormalizedRect(
                left = floatLeft,
                top = floatTop,
                width = floatWidth,
                height = floatHeight,
            )
            layout = layout.withFloatingBounds(bounds)
            if (maximized) layout = layout.toggleMaximized()
        }
        return layout
    }

    private fun SessionSettings.from(layout: PaneLayout): SessionSettings =
        copy(
            secondarySessionId = layout.secondarySessionId ?: "",
            presentation = layout.presentation,
            splitFraction = layout.splitFraction,
            floatLeft = layout.floatingBounds.left,
            floatTop = layout.floatingBounds.top,
            floatWidth = layout.floatingBounds.width,
            floatHeight = layout.floatingBounds.height,
            maximized = layout.maximized,
        )
}
