package com.iris.irisshell.domain.terminal

/**
 * Package profile chosen by the user during onboarding.
 *
 * - [Minimal]  ~3 dk · core utilities only.
 * - [Developer] ~8 dk · + vim, python3, nodejs, htop, tree, wget.
 * - [Custom]  user selects individual packages via the checkbox grid.
 *
 * Moved from `ui/setup/onboarding/components/` so it can be referenced by the
 * bootstrap pipeline without a ui→terminal import cycle.
 */
enum class PackageProfile { Minimal, Developer, Custom }
