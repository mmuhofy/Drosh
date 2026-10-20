// theme/ — Compose theme, components, typography.
//
// Per MEMORYBANK.md §124-163 (§5 Visual Identity): the gold accent (#E8C547),
// dark-only theme, JetBrains Mono terminal font, corner radii (14/12/8dp),
// color tokens for surfaces / borders / text.
//
// Per architecture rules: theme is independent — no dep on domain,
// data, agent, terminal, ssh. ui/ and any module rendering Compose may depend
// on theme.

plugins {
    alias(libs.plugins.dev.drosh.android.library)
    alias(libs.plugins.dev.drosh.android.compose)
}

android {
    namespace = "dev.drosh.theme"
}

dependencies {
    // theme is pure presentation — It does NOT depend on domain/,
    // data/, agent/, terminal/, ssh/, or ui/.
    //
    // :core holds the palette as plain ARGB ints so :terminal can read the
    // same numbers without gaining a Compose dependency.
    implementation(project(":core"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)

    // Unit tests
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
