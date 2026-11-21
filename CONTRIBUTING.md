# Contributing to Drosh

Thank you for your interest in contributing to Drosh.

Drosh is an Android terminal environment that provides a persistent
Ubuntu userspace and an integrated terminal experience.

We welcome bug reports, technical discussions, documentation
improvements, and contributions that help improve the project.

## Current Contribution Status

Drosh is currently under active development and has not published its
first release yet.

Until the first public release, contribution opportunities may be
limited while the project undergoes architectural improvements,
stabilization, and documentation work.

External contributions are expected to become more broadly available
after the first release.

This does not prevent you from participating in discussions, reporting
bugs, or suggesting improvements.

## Before Contributing

Before starting work:

-   Check existing issues and pull requests.
-   Review relevant documentation.
-   Look for existing implementations of the functionality you intend to
    change.
-   Make sure your proposal aligns with Drosh's goals and current
    development direction.

For substantial changes, open an issue before submitting a pull request.

This is particularly important for:

-   New features.
-   Architectural changes.
-   New modules or major dependencies.
-   Significant refactoring.
-   Changes affecting runtime behavior or security boundaries.

Small fixes and documentation corrections do not require a preliminary
issue.

## Types of Contributions

Contributions may involve:

-   Android application development.
-   Kotlin and Rust development.
-   Terminal engine improvements.
-   Terminal rendering and performance.
-   PTY and process lifecycle management.
-   Ubuntu runtime and environment management.
-   Runtime isolation and security improvements.
-   User interface and user experience.
-   Internationalization and localization.
-   Documentation and developer tooling.
-   Bug fixes and testing.

## Development Guidelines

Please follow these principles when working on Drosh:

-   Keep changes focused and avoid unrelated modifications.
-   Follow existing architectural boundaries and module conventions.
-   Prefer maintainable and understandable implementations.
-   Avoid unnecessary dependencies.
-   Handle errors explicitly.
-   Consider Android lifecycle behavior and mobile resource constraints.
-   Avoid introducing hidden behavior or undocumented configuration.
-   Keep documentation consistent with the actual implementation.
-   Do not introduce security or performance claims that cannot be
    verified.

## Architecture and Design Changes

Drosh's architecture is actively evolving.

Before introducing significant architectural changes, discuss the
proposed design through a GitHub issue or discussion.

Avoid unnecessary module dependencies, duplicated responsibilities, and
changes that make existing components harder to maintain.

## Commit Convention

Drosh requires Conventional Commits for commit messages.

Use the following format:

`<type>(<scope>): <description>`

Common types include:

  -----------------------------------------------------------------------
  Type                                Purpose
  ----------------------------------- -----------------------------------
  `feat`                              Introduces a new feature

  `fix`                               Fixes a bug

  `refactor`                          Changes implementation without
                                      changing intended behavior

  `perf`                              Improves performance

  `docs`                              Changes documentation

  `test`                              Adds or updates tests

  `build`                             Changes build configuration or
                                      dependencies

  `ci`                                Changes CI workflows

  `chore`                             Performs maintenance tasks
  -----------------------------------------------------------------------

Examples:

``` text
feat(terminal): improve terminal input handling
fix(pty): resolve session cleanup issue
refactor(runtime): simplify environment initialization
perf(renderer): optimize glyph lookup
docs(security): update security policy
test(terminal): add parser tests
build(android): update Gradle dependencies
ci: improve verification workflow
chore(repo): remove obsolete files
```

Commit messages should be concise, descriptive, and written in English.

## Code Quality and Testing

Before submitting changes:

-   Review the complete diff.
-   Remove unused code and temporary debugging output.
-   Check for hardcoded credentials, tokens, or sensitive information.
-   Follow established project conventions.
-   Run relevant tests and verification tasks.
-   Document any tests that could not be executed.
-   Update documentation when implementation changes affect documented
    behavior.

Test requirements depend on the scope and impact of the change. Bug
fixes and new functionality should include relevant tests where
practical.

Do not claim that a change has been tested if it has not been tested.

## Pull Requests

Before submitting a pull request:

1.  Make sure your changes are focused and complete.
2.  Follow the Conventional Commits convention.
3.  Run the relevant tests and verification tasks.
4.  Review your changes for unintended modifications.
5.  Update affected documentation.

A pull request should include:

-   A clear description of the changes.
-   The reason for the changes.
-   Relevant implementation details.
-   Testing and verification results.
-   References to related issues, when applicable.

Keep pull requests focused and reasonably sized.

### Continuous Integration

Required CI checks must pass before a pull request can be merged.

If a check fails, investigate and resolve the failure before requesting
a merge.

Exceptions should only be handled by project maintainers when there is a
justified reason.

## Bug Reports

When reporting a bug, provide as much relevant information as possible:

-   A clear description of the problem.
-   Steps to reproduce it.
-   Expected behavior.
-   Actual behavior.
-   Android version and relevant device information.
-   Relevant logs or screenshots, with sensitive information removed.

Do not include passwords, API keys, access tokens, or other private
information in reports.

## Feature Requests

Feature requests should explain:

-   The problem or need being addressed.
-   The proposed behavior.
-   Why the feature would benefit Drosh.
-   Any relevant technical considerations.

Significant proposals should be discussed before implementation.

## Security Issues

Do not disclose security vulnerabilities through public GitHub issues or
discussions.

Follow the reporting process described in [SECURITY.md](SECURITY.md).

## Documentation

Documentation contributions should:

-   Reflect the current implementation.
-   Distinguish implemented functionality from planned features.
-   Use consistent terminology.
-   Avoid unsupported technical claims.
-   Remain clear and maintainable.

## License

Drosh is distributed under the Apache License 2.0.

By submitting a contribution, you agree that your contributions will be
licensed under the same license.

You must have the legal right to submit the code or other materials you
contribute.

See [LICENSE](LICENSE) for the complete license text.

## Community

Please follow the project's [Code of Conduct](CODE_OF_CONDUCT.md) when
participating in Drosh community spaces.

GitHub Discussions is available for questions, ideas, technical
discussions, and general project conversations.

Thank you for helping improve Drosh.
