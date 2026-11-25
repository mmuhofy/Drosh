DROSH — PRODUCTION-QUALITY TERMINAL SELECTION SYSTEM

Act as a senior software architect and engineer specializing in Android, Kotlin, Rust, JNI, terminal emulators, rendering systems, and mobile UI architecture.

Your mission is to research, architect, and implement an original, production-quality terminal text-selection system for Drosh.

The goal is not simply to make text selection work. Build a robust, polished, performant, maintainable system that integrates naturally with Drosh's existing architecture.

1. COMMUNICATION LANGUAGE

Communicate with me in Turkish throughout the entire task.

- Progress updates must be in Turkish.
- Technical explanations must be in Turkish.
- Questions must be in Turkish.
- Final reports must be in Turkish.
- Keep programming identifiers, API names, and conventional technical terminology in their original language.

2. UNDERSTAND DROSH FIRST

Before implementing anything, thoroughly inspect the existing Drosh repository.

Understand:

- Current module and package architecture.
- Terminal engine.
- Ghostty VT integration.
- Kotlin and Rust responsibilities.
- JNI/native boundaries.
- Rendering pipeline.
- Touch and gesture handling.
- Terminal coordinate and cell representation.
- State management.
- Clipboard implementation.
- Existing selection functionality.

Trace how terminal input, rendering, selection, and native integration currently work.

Reuse appropriate existing abstractions.

Do not invent APIs or assume a folder structure.

Do not unnecessarily refactor unrelated parts of Drosh.

3. RESEARCH THE REFERENCE PROJECT

Reference repository:

https://github.com/sylirre/ghostty-android-terminal

Study its actual implementation, especially:

- Selection lifecycle.
- Touch handling.
- Native selection state.
- JNI integration.
- Selection anchors and endpoints.
- Handle rendering and dragging.
- Coordinate transformations.
- Word and line selection.
- Text extraction.
- Scrollback behavior.
- Terminal resizing and reflow.
- Clipboard integration.

Understand its architecture and engineering decisions.

Use it as a technical reference, not as a code template.

Do not copy its source code or blindly reproduce its architecture.

Create an original implementation designed specifically for Drosh.

Distinguish verified findings from assumptions.

4. ARCHITECTURE AND CODE QUALITY

Design the best architecture for the actual Drosh codebase.

You are responsible for deciding:

- Module boundaries.
- Folder structure.
- File names.
- Class and interface names.
- Kotlin/Rust responsibilities.
- State ownership.
- JNI integration.
- UI architecture.
- Text extraction architecture.
- Testing strategy.

Use clear, consistent, meaningful names.

Separate responsibilities logically.

Avoid:

- Monolithic classes.
- Duplicate state ownership.
- Excessive abstraction.
- Unnecessary files and modules.
- Unnecessary dependencies.
- Fragile architecture.
- Overengineering.

Every component must have a clear responsibility.

Do not force a predetermined folder structure. Choose what best fits Drosh.

Prioritize maintainability, readability, correctness, and extensibility.

5. SELECTION FUNCTIONALITY

Implement a complete terminal selection experience:

- Long press to start selection.
- Custom draggable start and end handles.
- Continuous selection updates.
- Word selection.
- Line selection.
- Select all.
- Selection expansion where appropriate.
- Reliable dismissal.
- Auto-scrolling near viewport boundaries.
- Correct behavior during terminal scrolling.
- Correct behavior during terminal resizing and reflow.

Handle Unicode, wide characters, combining characters, wrapped lines, scrollback, and active terminal output correctly.

Prevent conflicts with existing terminal gestures and input handling.

Use the existing Ghostty VT selection functionality where appropriate.

Avoid maintaining duplicate selection state.

6. CUSTOM DROSH UI

Create a custom selection interface consistent with Drosh's visual identity:

- Dark charcoal.
- Blue accents.
- Compact controls.
- Clean terminal-oriented design.

Implement:

- Custom selection handles.
- Selection highlight.
- Custom selection toolbar.
- Contextual selection menu.

The toolbar should support:

- Copy.
- Select word.
- Select line.
- Select all.
- Search selected text, if compatible with existing Drosh functionality.
- Dismiss selection.

All controls must work correctly.

Avoid unnecessary decoration and oversized controls.

7. TEXT EXTRACTION AND CLIPBOARD

The clipboard must contain actual terminal text, not rendering information.

Expected behavior:

Content| Expected behavior
Normal text| Include
Colored foreground text| Include characters
Background color blocks| Exclude
ANSI escape sequences| Exclude
Color metadata| Exclude
Text style metadata| Exclude
Empty terminal cells| Exclude
Trailing cell padding| Exclude
Wide-character spacer cells| Exclude
Meaningful spaces between words| Preserve
Unicode characters| Preserve

For example, if the terminal displays colored text:

ERROR: Compilation failed

The clipboard must contain:

ERROR: Compilation failed

Never copy ANSI control sequences or visual rendering metadata.

Do not remove meaningful spaces between words.

Only remove empty-cell padding and non-textual terminal artifacts.

Investigate Ghostty VT's existing extraction functionality before implementing a custom extraction system.

Use the simplest correct solution that satisfies these requirements.

8. ENGINEERING AND TESTING

Prioritize:

- Strong type safety.
- Correct state ownership.
- Predictable lifecycle management.
- Safe native integration.
- Efficient rendering.
- Minimal unnecessary allocations.
- Proper error handling.
- Meaningful tests.
- Maintainable code.

Test:

- Selection state transitions.
- Gesture interactions.
- Handle dragging.
- Text extraction.
- Whitespace filtering.
- ANSI exclusion.
- Unicode.
- Wide characters.
- Multiline selection.
- Scrollback.
- Resizing and reflow.
- Clipboard integration.
- Native integration.
- Session lifecycle.

Do not rely exclusively on successful compilation.

Fix problems discovered during testing.

Never claim that a test passed unless it actually ran and passed.

9. IMPLEMENTATION WORKFLOW

Work incrementally:

1. Inspect Drosh.
2. Research the reference.
3. Determine the architecture.
4. Implement the core selection system.
5. Integrate Ghostty VT.
6. Implement gestures and custom UI.
7. Implement text extraction and clipboard.
8. Test edge cases.
9. Perform final verification.

Use engineering judgment.

Do not create unnecessary documentation or files merely to satisfy a predetermined checklist.

Explain important architectural decisions and their trade-offs.

If a technical decision is uncertain, investigate before implementing it.

10. BUILD ENVIRONMENT — GITHUB ACTIONS ONLY

CRITICAL: Local Android builds are strictly prohibited.

Do not run:

- Local Gradle Android build tasks.
- assembleDebug or assembleRelease.
- Local APK compilation.
- Other resource-intensive Android application build operations.

All Android builds must happen through GitHub Actions.

Workflow:

1. Inspect the existing GitHub Actions workflows.
2. Identify the correct Android build workflow.
3. Complete the implementation before triggering the final build.
4. Commit and push changes only when explicitly authorized.
5. Trigger GitHub Actions using available GitHub integration and permissions.
6. Monitor the workflow until it finishes.
7. Inspect logs if the build fails.
8. Fix problems caused by your changes.
9. Repeat remote verification when necessary.
10. Check whether the APK was generated and uploaded as an artifact.

Do not automatically commit or push without authorization.

Do not silently fall back to local Android builds if GitHub Actions is unavailable.

If permissions, workflow configuration, or repository access prevent remote builds, explain the exact limitation and stop before claiming success.

Rust and Kotlin unit tests may run locally if they do not trigger an Android application build and the environment supports them.

11. FINAL VERIFICATION AND REPORT

At the end, report in Turkish:

- Architecture implemented.
- Folder and file structure.
- Files created.
- Files modified.
- Reference research findings.
- Important architectural decisions.
- Tests actually executed.
- Test results.
- GitHub Actions workflow URL.
- Build status.
- Build variant.
- Important warnings.
- APK artifact availability and location.
- Known limitations.
- Remaining issues.

Do not fabricate results.

Do not claim the feature is complete if critical functionality remains unimplemented.

12. AUTONOMOUS SUBAGENT ORCHESTRATION

You have full autonomy over subagent orchestration.

You are responsible for deciding:

- Whether subagents are needed.
- How many subagents to use.
- Which areas of the task should be delegated.
- What expertise each subagent should have.
- How tasks should be divided.
- Which tasks can run in parallel.
- When additional subagents should be created.
- When subagents should finish their work.

Do not follow a predefined team structure or mandatory task distribution.

Analyze the actual project, task complexity, dependencies, and available tools before deciding how to organize the work.

Delegation principles

1. Use subagents whenever they provide meaningful improvements in research quality, implementation speed, code quality, or verification.
2. Choose responsibilities dynamically based on actual project requirements.
3. Prefer parallel execution for independent tasks.
4. Keep dependent tasks properly coordinated.
5. Avoid multiple agents modifying the same files simultaneously unless their changes are explicitly coordinated.
6. Give each subagent a clear objective and sufficient technical context.
7. Review and validate subagent findings and code before integration.
8. Resolve conflicting recommendations through technical analysis.
9. Keep overall architectural consistency across all delegated work.
10. Use additional reviewers or specialists when they provide meaningful value.
11. Avoid unnecessary delegation and coordination overhead.
12. If subagents are unavailable, continue independently without compromising engineering quality.

Main agent responsibility

You remain responsible for the final result.

Maintain oversight of:

- Overall architecture.
- Technical decisions.
- Integration.
- Code quality.
- Testing.
- Build verification.
- Final acceptance criteria.

Delegation does not transfer responsibility for correctness.

You have freedom to organize the team in whichever way produces the best engineering result.
FINAL OBJECTIVE

Build a genuinely high-quality, original, production-ready terminal selection system for Drosh.

Use the existing project intelligently.

Make sound architectural decisions.

Write clean, understandable, maintainable code.

Verify behavior through meaningful tests and a successful remote Android build.

Prioritize correctness and long-term quality over speed.