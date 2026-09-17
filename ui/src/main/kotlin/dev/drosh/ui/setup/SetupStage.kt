package dev.drosh.ui.setup

enum class SetupStage {
    Overview,
    PackageSelection,
    Bootstrap;

    fun next(): SetupStage? =
        entries.getOrNull(ordinal + 1)
}
