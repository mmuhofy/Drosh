/*
 * Copyright (C) 2026 Drosh contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 */

package dev.drosh.terminal

import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Measures how much a running command is actually doing.
 *
 * Distinguishes `gradle build` from `sleep 100` without asking the shell
 * anything: both report the same state, and only the rate of output tells them
 * apart. A consumer can therefore tell "working" from "stuck" without looking
 * away from the keyboard.
 *
 * The signal is the arrival rate of screen updates, so no polling and no extra
 * processes. Updates arrive in bursts, which is exactly what is being measured.
 *
 * Exponentially smoothed so the value does not flicker between frames. Raw
 * counts are too spiky to draw from.
 */
class CommandActivityTracker {

    private companion object {
        /** Rate that counts as fully busy. Calibrated by feel, not measured. */
        const val BUSY_UPDATES_PER_SECOND = 40.0

        /**
         * Smoothing factor per update. Higher reacts faster and flickers more.
         * Roughly a 300 ms time constant at typical update rates.
         */
        const val SMOOTHING = 0.28

        /** Below this the command is treated as idle no matter its state. */
        const val QUIET_FLOOR = 0.02
    }

    private val _level = MutableStateFlow(0f)
    val level: StateFlow<Float> = _level.asStateFlow()

    private var smoothed = 0.0
    private var lastUpdateAt = 0L
    private var running = false

    /** Call when a command starts, so the first outputs are measured. */
    fun onCommandStarted() {
        running = true
        smoothed = 0.0
        lastUpdateAt = SystemClock.elapsedRealtime()
        _level.value = 0f
    }

    /** Call on every screen update while a command runs. */
    fun onOutput() {
        val now = SystemClock.elapsedRealtime()
        if (lastUpdateAt != 0L) {
            val seconds = (now - lastUpdateAt) / 1000.0
            if (seconds > 0.0) {
                // Instantaneous rate, then smoothed. The first update after a
                // start has an artificially tiny interval, so it is skipped
                // rather than allowed to spike the average.
                val instant = (1.0 / seconds).coerceAtMost(BUSY_UPDATES_PER_SECOND * 2.0)
                val normalised = instant / BUSY_UPDATES_PER_SECOND
                smoothed += (normalised - smoothed) * SMOOTHING
                _level.value = smoothed.coerceIn(0.0, 1.0).toFloat()
            }
        }
        lastUpdateAt = now
    }

    /**
     * Call on command end and on a tick, so the value decays to zero when the
     * output stops rather than freezing at whatever it last reached.
     */
    fun onSettled() {
        running = false
        lastUpdateAt = 0L
        // One more smoothing step rather than a hard reset: the sheen should
        // fade out over a few frames, matching how the edge settles.
        smoothed *= 0.55
        if (smoothed < QUIET_FLOOR) smoothed = 0.0
        _level.value = smoothed.toFloat()
    }

    fun reset() {
        running = false
        smoothed = 0.0
        lastUpdateAt = 0L
        _level.value = 0f
    }

    /** True while the tracker believes a command is executing. */
    val isRunning: Boolean get() = running
}
