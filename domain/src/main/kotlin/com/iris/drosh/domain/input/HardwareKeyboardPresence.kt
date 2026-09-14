package com.iris.irisshell.domain.input

import kotlinx.coroutines.flow.Flow

/**
 * Detects whether a hardware `KEYBOARD_TYPE_ALPHABETIC` input device is
 * currently connected to the device. When true, the on-screen extra-keys
 * bar hides automatically (Termux convention — see
 * `docs/MEMORYBANK.md` §8).
 *
 * Implementation note: emits the latest known state on collect, then
 * updates whenever the configuration changes (e.g. user plugs in a USB
 * keyboard).
 */
interface HardwareKeyboardPresence {

    /** Hot stream of the hardware-keyboard presence flag. */
    val isPresent: Flow<Boolean>
}
