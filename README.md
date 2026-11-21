# Drosh

<p align="center">
  <strong>The Terminal, Rebuilt for Android.</strong>
  <br>
  A modern, GPU-accelerated terminal environment built specifically for Android.
</p>

<p align="center">
  <a href="https://github.com/mmuhofy/Drosh/actions/workflows/debug.yml">
    <img src="https://github.com/mmuhofy/Drosh/actions/workflows/debug.yml/badge.svg" alt="Build">
  </a>
  <a href="https://github.com/mmuhofy/Drosh/actions/workflows/tests.yml">
    <img src="https://github.com/mmuhofy/Drosh/actions/workflows/tests.yml/badge.svg" alt="Tests">
  </a>
  <img src="https://img.shields.io/badge/License-Apache%202.0-blue.svg" alt="Apache 2.0">
  <img src="https://img.shields.io/badge/Android-8.0%2B-green.svg" alt="Android 8.0+">
</p>

---

Drosh is a native Android terminal environment built around a modern terminal engine, a custom GPU-accelerated renderer, and an on-device Linux userspace.

Powered by Ghostty's VT core, Rust, OpenGL ES and chroot-ng, Drosh brings a full Ubuntu environment and advanced terminal capabilities to Android.

## Features

### Terminal

- Ghostty VT core with a dedicated Rust backend.
- Custom OpenGL ES 3.0 renderer.
- Block-based command output with exit status tracking.
- Persistent terminal sessions.
- Advanced text selection and search.
- Mouse reporting and Kitty graphics support.
- Pinch-to-zoom and smooth scrolling.
- Split panes and floating terminal windows.

### Linux environment

- Ubuntu 24.04 userspace running on-device.
- chroot-ng runtime.
- Native shell and PTY integration.
- Linux filesystem and command-line tools.
- Backup and restore support.

### Customization

- TOML-based theme files.
- TOML-based application settings.
- Installable themes and background images.
- Customizable terminal fonts and appearance.
- 12 language options.
- PIN-based application lock.

### Integrated tools

- Built-in code editor with syntax highlighting.
- SSH connections with encrypted credential storage.
- Session and project organization.
- In-app browser for terminal links.
- Backup and restore.

### DroshKeyboard

Drosh also has its own companion keyboard, developed specifically to improve terminal interaction on Android.

[mmuhofy/DroshKeyboard](https://github.com/mmuhofy/DroshKeyboard)

Its terminal-focused features include:

- Code snippets for frequently used commands.
- Virtual mouse for terminal interaction.
- Spacebar-based cursor navigation.
- Keyboard controls designed for command-line workflows.

## Technology

| Component | Technology |
|---|---|
| Platform | Android |
| Languages | Kotlin, Rust |
| UI | Jetpack Compose |
| Terminal core | Ghostty VT |
| Terminal renderer | OpenGL ES 3.0 |
| Linux runtime | chroot-ng |
| Linux distribution | Ubuntu 24.04 |
| Architecture | MVVM + Clean Architecture |

## Roadmap

Drosh is continuously evolving. The following are planned directions for future development and are not currently available features.

- **Multiple Linux distributions:** Support for distributions beyond Ubuntu, with the ability to choose and manage different Linux environments.
- **Shizuku integration:** Optional access to Android system capabilities through Shizuku.
- **Built-in terminal assistant:** An integrated assistant capable of understanding terminal context, interacting with the terminal and executing user-approved commands.
- **Deeper Android integration:** More tools and workflows that make use of Android-specific capabilities.
- **Further terminal improvements:** Additional rendering, interaction and productivity features.

The roadmap may change as development progresses.

## Development status

Drosh is currently in active development and has not reached its first stable release.

Features and internal APIs may change between development versions.

## License

Drosh is licensed under the [Apache License 2.0](LICENSE).

Third-party components retain their respective licenses. See [LICENSE.md](LICENSE.md) and [CREDITS.md](CREDITS.md) for details.

---

<p align="center">
  <strong>Drosh</strong>
  <br>
  The Terminal, Rebuilt for Android.
</p>