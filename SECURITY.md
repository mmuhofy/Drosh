# Security Policy

Drosh takes the security of its users, their data, and the Android environment seriously. This document describes the currently implemented security-related behavior, the project's vulnerability reporting process, and the limitations of its current architecture.

## Supported Versions

Drosh's security update policy is currently being established.

Security fixes and supported release versions will be documented here as the project's release process matures.

## Reporting a Vulnerability

Please do not disclose security vulnerabilities through public GitHub issues.

Use GitHub's private vulnerability reporting feature:

[Report a vulnerability](https://github.com/dexbycode/Drosh/security/advisories/new)

When submitting a report, include as much of the following information as possible:

- A description of the vulnerability.
- The affected component or attack surface.
- Steps to reproduce the issue.
- The potential security impact.
- Relevant logs or other technical evidence, with sensitive information removed.

Reports will be reviewed and investigated. Please allow time for assessment and remediation before publicly disclosing a vulnerability.

## Current Security Architecture

### Ubuntu Runtime

Drosh provides a persistent, virtual Ubuntu environment powered by a modified fork of `fake-chroot-ng`.

The current implementation:

- Uses a modified upstream runtime.
- Does not rely on `ptrace`-based syscall tracing.
- Starts the Ubuntu environment with the `root` user by default.
- Maintains its filesystem state across restarts.
- Provides network access from the Ubuntu environment.

The Ubuntu environment's root user is separate from Android's system-level root privileges. Having root privileges inside Ubuntu does not automatically grant root access to the Android operating system.

The runtime and its isolation model are under active development. The current implementation should not be considered a formally audited security sandbox or a guaranteed boundary against malicious guest processes.

### Filesystem Access

The Ubuntu environment can access Android shared storage when the user grants Drosh the required storage permissions.

Current access boundaries:

- Access to shared storage depends on Android permissions granted to Drosh.
- The Ubuntu environment can access Drosh's own application data.
- It cannot access other applications' private data under the current permission model.
- Access to Android system-level resources requiring device root privileges is not available without those privileges.

### Terminal Engine and PTY

Terminal commands are executed exclusively inside the Ubuntu environment.

Drosh does not provide direct command execution in the Android application environment through its terminal interface.

PTY sessions are terminated when Drosh is completely closed.

### Clipboard

Clipboard functionality currently supports manual copy and paste operations between Android and the terminal.

Pasting transfers the current Android clipboard content into the Ubuntu terminal. Clipboard access is not currently used for automatic background data synchronization.

### PIN Lock

Drosh provides an application-level PIN lock.

Current behavior:

- Supports PINs between 4 and 8 digits.
- Requires PIN verification when the application is opened or resumed, according to the lock state.
- Stores PIN-related data using Android encrypted storage mechanisms.

Current limitations:

- There is no failed-attempt rate limiting.
- There is no temporary lockout after repeated incorrect attempts.
- There is currently no PIN recovery mechanism.

These limitations are known and are planned areas for improvement.

## Currently Disabled Components

The following functionality is temporarily disabled and is not part of the active security surface described above:

- SSH
- AI Agent
- API key management

These components may return in future releases and will require their security behavior to be reviewed before being included in the active security scope.

## Network Communication

Current external network communication is used by Drosh's update system to retrieve release information and update-related resources from Drosh's GitHub repositories.

Other network capabilities available inside Ubuntu are separate from the Android application's update communication.

## Security Testing

Drosh has been tested on physical Android hardware.

The currently confirmed test environment includes Android 15. The minimum supported Android version is Android 8.

At this time:

- No independent security audit has been completed.
- No formal penetration test has been completed.
- No dedicated sandbox escape assessment has been completed.
- Automated security testing and dependency security scanning are not yet implemented.

These are planned areas of improvement.

## Future Security Improvements

Drosh's security architecture is actively evolving. Planned work includes:

- A redesigned Ubuntu user and permission model.
- Improvements to runtime isolation and sandboxing.
- Stronger PIN protection, including failed-attempt controls.
- A PIN recovery mechanism.
- Expanded security testing and automated checks.
- Review of third-party dependencies and licensing.
- Security reviews for future SSH, Agent, and GUI userspace functionality.

Planned features should not be interpreted as currently implemented security controls.

## Scope

Security reports may concern:

- Unauthorized access to Android application data.
- Unexpected access to files outside granted permissions.
- Runtime isolation failures.
- Unauthorized access between the Ubuntu environment and Android.
- PIN authentication weaknesses.
- Clipboard data exposure.
- Terminal or PTY vulnerabilities.
- Update mechanism integrity and security.

A crash is not automatically a security vulnerability. However, crashes that can be triggered remotely or exploited to affect confidentiality, integrity, or availability may qualify.

## Disclosure and Acknowledgement

Drosh aims to handle vulnerability reports responsibly and coordinate disclosure with the reporter.

Security researchers may be credited in release notes or security advisories with their permission.