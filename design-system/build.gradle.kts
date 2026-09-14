// design-system/ — Compose theme, components, typography.
//
// Per MEMORYBANK.md §124-163 (§5 Visual Identity): the gold accent (#E8C547),
// dark-only theme, JetBrains Mono terminal font, corner radii (14/12/8dp),
// color tokens for surfaces / borders / text.
//
// Per architecture rules: design-system is independent — no dep on domain,
// data, agent, terminal, ssh. ui/ and any module rendering Compose may depend
// on design-system.

plugins {
    alias(libs.plugins.iris.android.library)
    alias(libs.plugins.iris.android.compose)
}

android {
    namespace = "dev.drosh.design.system"
}

dependencies {
    // design-system is pure presentation — It does NOT depend on domain/,
    // data/, agent/, terminal/, ssh/, or ui/.
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)

    // Unit tests
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
