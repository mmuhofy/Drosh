package dev.drosh.ui.agent.components

import androidx.compose.runtime.Composable

/**
 * Kept as a no-op for source compatibility.
 *
 * The app is now globally full-screen: status, navigation and gesture bars are
 * hidden at the activity window and re-applied on every layout pass, including
 * dialog and bottom-sheet windows. An agent screen showing the status bar here
 * would fight that global decision and make the bar flash on navigation.
 *
 * Callers no longer need to change; this composable simply does nothing.
 */
@Composable
fun DroshStatusBarVisible() = Unit
