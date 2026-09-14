package com.iris.irisshell.domain.terminal

/**
 * Shell preference chosen by the user during onboarding.
 *
 * Moved from `ui/setup/onboarding/components/` so it can be referenced by the
 * bootstrap pipeline (TriggerBootstrapUseCase → BootstrapStatePort →
 * UbuntuBootstrap) without creating a circular dependency.
 */
enum class ShellChoice { Zsh, Bash }
