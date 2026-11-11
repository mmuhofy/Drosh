package dev.drosh.domain.terminal

/**
 * Shell preference threaded through the bootstrap pipeline.
 *
 * Moved from `ui/setup/onboarding/components/` so it can be referenced by the
 * bootstrap pipeline (TriggerBootstrapUseCase → BootstrapStatePort →
 * UbuntuBootstrap) without creating a circular dependency.
 *
 * **No longer an onboarding choice.** The wizard does not ask which shell the
 * guest should run; it always sends [Zsh] in [SetupPreferences.shellChoice],
 * and the pipeline's Oh My Zsh branch runs for every fresh rootfs. The enum
 * stays because the pipeline still branches on it, and a future settings
 * screen can write it without the bootstrap changing.
 */
enum class ShellChoice { Zsh, Bash }
