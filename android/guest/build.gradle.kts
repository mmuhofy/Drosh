// guest/ — the Linux guest: its rootfs, its bootstrap, its archive, and the
// launcher that execs a shell inside it.
//
// Per docs/ARCHITECTURE.md §1: `:guest` owns rootfs, bootstrap, archive and
// chroot exec, and must not know about the terminal, the engine, the data
// layer or the UI. The boundary checker enforces that.
//
// Layout:
//   UbuntuBootstrap.kt     installs the rootfs and prepares it for use
//   BootstrapStatePort.kt  the setup pipeline's state, as a port
//   GuestLauncher.kt       the seam a guest backend implements
//   ProotRunner.kt         the proot backend
//   ChrootNgRunner.kt      the chroot-ng backend
//   backup/                the rootfs archive format, for backup and restore
//
// The guest is a Linux environment, not a terminal: it knows nothing about
// sessions, panes or blocks, which is why it can be lifted out of `:terminal`
// without dragging the session model with it.

plugins {
    alias(libs.plugins.dev.drosh.android.library)
}

android {
    namespace = "dev.drosh.guest"

    packaging {
        jniLibs {
            // libchrootng.so is the freestanding, statically-linked chroot-ng
            // monitor, fetched from its own release (see the CI step in
            // .github/workflows/debug.yml). It has a custom linker script and
            // no standard sections, so AGP's strip pass would corrupt it —
            // keep it byte-for-byte.
            keepDebugSymbols += "**/libchrootng.so"
        }
    }
}

dependencies {
    // The setup preferences and the shell choice are domain types, and the
    // bootstrap pipeline is driven through them.
    api(project(":domain"))

    // The bootstrap runs its work off the main thread.
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.timber)

    testImplementation(libs.junit)
}
