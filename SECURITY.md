> Drosh runs your shell, your files, and your credentials. Please report
> security problems privately.

## Reporting a vulnerability

**Do not open a public issue.** Instead, use GitHub's private vulnerability
reporting:
[Report a vulnerability](https://github.com/mmuhofy/Drosh/security/advisories/new).

Include what you can: the affected surface (terminal, agent, SSH vault,
PIN), how to reproduce, and the impact. A fix is usually quick; a coordinated
disclosure is the slow part.

## What counts

- Remote code execution in the agent (a provider response that runs more
  than it should)
- Credential exposure — the SSH vault, API keys, the clipboard bridge
- The PIN lock as authentication rather than as a screen lock
- Anything in the Proot/PTY layer that escapes the guest

## What does not

- Bugs that crash the app — those are ordinary issues, file them against the
  bug template
- Missing device verification. Drosh has never run on a physical device; a
  defect found on one is a bug report, not a vulnerability

## Response

Acknowledgement within a few days, a fix or a mitigation plan after that,
and credit in the changelog if you want it.
