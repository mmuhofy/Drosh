// ssh/ — SSHJ manager, SSH key vault, host management, multi-exec, constellation.
//
// Per AGENT.md §129-131: "SSHJ manager, SSH key vault, host management..."
// Per AGENT.md §144: "ssh/ is isolated — UI accesses only through domain/ use cases"
// Per MEMORYBANK.md §669-707 (§12 SSH System): SSHJ 0.38.x.

plugins {
    alias(libs.plugins.iris.android.library)
    alias(libs.plugins.iris.android.hilt)
    alias(libs.plugins.iris.kotlin.serialization)
}

android {
    namespace = "dev.drosh.ssh"
}

dependencies {
    api(project(":domain"))
    implementation(project(":core"))

    // SSH client — MEMORYBANK.md §671: SSHJ 0.38.x (libs.versions.toml pinned 0.39.0
    // as most current 0.38.x-line release).
    implementation(libs.sshj)

    // security — encrypted SSH key storage in Key Vault
    implementation(libs.androidx.security.crypto)
    implementation(libs.androidx.biometric)

    // networking bounce path (not used directly — SSHJ handles transports)
    implementation(libs.okhttp)

    // coroutines
    implementation(libs.kotlinx.coroutines.android)

    // logging
    implementation(libs.timber)

    // serialization — host config import/export (MEMORYBANK.md §702)
    implementation(libs.kotlinx.serialization.json)

    // unit tests
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.mockk)
}
