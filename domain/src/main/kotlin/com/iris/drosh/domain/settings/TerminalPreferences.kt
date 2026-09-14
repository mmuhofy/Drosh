package com.iris.irisshell.domain.settings

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
