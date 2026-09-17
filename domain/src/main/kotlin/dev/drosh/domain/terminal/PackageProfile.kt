package dev.drosh.domain.terminal

enum class PackageProfile(
    val displayName: String,
    val description: String,
    val estimatedTime: String,
) {
    Minimal(
        displayName = "Minimal",
        description = "Core utilities only",
        estimatedTime = "~3 min",
    ),
    Standard(
        displayName = "Standard",
        description = "vim, python3, nodejs, htop, tree, wget",
        estimatedTime = "~8 min",
    ),
    Full(
        displayName = "Full",
        description = "Standard + git, ffmpeg, build tools, and more",
        estimatedTime = "~15 min",
    ),
    Custom(
        displayName = "Custom",
        description = "Select individual packages",
        estimatedTime = "~varies",
    );

    val isCustom: Boolean
        get() = this == Custom
}
