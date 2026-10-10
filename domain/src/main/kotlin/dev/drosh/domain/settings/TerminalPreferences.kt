package dev.drosh.domain.settings

enum class CursorStyle {
    Block,
    Beam,
    Underline,
    ;

    companion object {
        fun fromString(value: String): CursorStyle = entries.find {
            it.name.equals(value, ignoreCase = true)
        } ?: Block
    }
}

enum class AutoLockTimeout {
    Immediately,
    OneMinute,
    FiveMinutes,
    FifteenMinutes,
    ThirtyMinutes,
    Never,
    ;

    companion object {
        fun fromString(value: String): AutoLockTimeout = entries.find {
            it.name.equals(value, ignoreCase = true)
        } ?: Immediately
    }
}

/**
 * How long away counts as "away" for this timeout.
 *
 * [Never] answers a duration no elapsed clock can reach rather than a
 * sentinel the caller has to remember to check — the comparison in the
 * activity is one line, and a special case there is one line that can be
 * forgotten.
 */
fun AutoLockTimeout.toMillis(): Long = when (this) {
    AutoLockTimeout.Immediately -> 0L
    AutoLockTimeout.OneMinute -> 60_000L
    AutoLockTimeout.FiveMinutes -> 300_000L
    AutoLockTimeout.FifteenMinutes -> 900_000L
    AutoLockTimeout.ThirtyMinutes -> 1_800_000L
    AutoLockTimeout.Never -> Long.MAX_VALUE
}
