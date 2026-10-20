//! Block boundaries, derived from the shell-integration marks.
//!
//! The old block engine re-read the entire transcript on every keystroke and
//! diffed it as a string (see `docs/ARCHITECTURE.md` §1.1). That copy is gone:
//! the OSC 133/7/1339 marks are already tapped into `ShellMark` events on the
//! PTY path, so the block boundary is just the ordered stream of those marks.
//!
//! This tracker turns that stream into the few facts the block engine needs —
//! "a prompt started", "a command finished with status N", "the directory is
//! now X" — and carries the latest working directory and editor target so they
//! ride along with every block without another grid scan.

use drosh_bridge::events::ShellMark;

/// A boundary the block engine can act on.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum BlockEvent {
    /// `OSC 133 ; A` — the prompt is showing again, a new block begins.
    PromptStarted,
    /// `OSC 133 ; B` — the user is typing the command.
    CommandStarted,
    /// `OSC 133 ; C` — the command is running.
    CommandExec,
    /// `OSC 133 ; D ; <status>` — the command finished (status may be absent).
    CommandFinished(Option<i32>),
    /// `OSC 7` — the working directory changed.
    WorkingDirectory(String),
    /// `OSC 1339` — the shell asked the editor to open a file.
    EditorTarget(String),
}

/// Where the shell is in its cycle, as far as the marks say.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub enum BlockPhase {
    /// No mark has been seen yet — the shell has not reported in.
    #[default]
    Unknown,
    /// The prompt is on screen; the shell waits for a command.
    Prompt,
    /// The command is being typed.
    Typing,
    /// The command is running.
    Running,
    /// The command finished; the status is absent when the shell sent a bare `D`.
    Finished(Option<i32>),
}

impl BlockPhase {
    /// The short code the Kotlin side reads.
    pub fn code(self) -> &'static str {
        match self {
            BlockPhase::Unknown => "unknown",
            BlockPhase::Prompt => "prompt",
            BlockPhase::Typing => "typing",
            BlockPhase::Running => "running",
            BlockPhase::Finished(_) => "finished",
        }
    }

    /// The exit status, when the phase carries one.
    pub fn status(self) -> Option<i32> {
        match self {
            BlockPhase::Finished(status) => status,
            _ => None,
        }
    }
}

/// Tracks block state across marks for one session.
#[derive(Debug, Default)]
pub struct BlockTracker {
    cwd: Option<String>,
    editor: Option<String>,
    phase: BlockPhase,
}

impl BlockTracker {
    pub fn new() -> Self {
        Self::default()
    }

    /// The working directory last seen, if any.
    pub fn cwd(&self) -> Option<&str> {
        self.cwd.as_deref()
    }

    /// The editor target last seen, if any.
    pub fn editor(&self) -> Option<&str> {
        self.editor.as_deref()
    }

    /// Where the shell is in its cycle.
    pub fn phase(&self) -> BlockPhase {
        self.phase
    }

    /// Feed one mark; returns the block event it implies, if any.
    pub fn observe(&mut self, mark: &ShellMark) -> Option<BlockEvent> {
        match mark {
            ShellMark::PromptStart => {
                self.phase = BlockPhase::Prompt;
                Some(BlockEvent::PromptStarted)
            }
            ShellMark::CommandStart => {
                self.phase = BlockPhase::Typing;
                Some(BlockEvent::CommandStarted)
            }
            ShellMark::CommandExec => {
                self.phase = BlockPhase::Running;
                Some(BlockEvent::CommandExec)
            }
            ShellMark::CommandFinished { status } => {
                self.phase = BlockPhase::Finished(*status);
                Some(BlockEvent::CommandFinished(*status))
            }
            ShellMark::WorkingDirectory { url } => {
                self.cwd = Some(url.clone());
                Some(BlockEvent::WorkingDirectory(url.clone()))
            }
            ShellMark::EditorTarget { path } => {
                self.editor = Some(path.clone());
                Some(BlockEvent::EditorTarget(path.clone()))
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn prompt_to_finished_emits_boundary_events() {
        let mut tracker = BlockTracker::new();
        let sequence = [
            ShellMark::PromptStart,
            ShellMark::CommandStart,
            ShellMark::CommandExec,
            ShellMark::CommandFinished { status: Some(0) },
        ];
        let events: Vec<BlockEvent> = sequence.iter().filter_map(|m| tracker.observe(m)).collect();
        assert_eq!(
            events,
            vec![
                BlockEvent::PromptStarted,
                BlockEvent::CommandStarted,
                BlockEvent::CommandExec,
                BlockEvent::CommandFinished(Some(0)),
            ]
        );
    }

    #[test]
    fn carries_cwd_and_editor() {
        let mut tracker = BlockTracker::new();
        assert_eq!(
            tracker.observe(&ShellMark::WorkingDirectory { url: "file:///a".into() }),
            Some(BlockEvent::WorkingDirectory("file:///a".into()))
        );
        assert_eq!(tracker.cwd(), Some("file:///a"));
        assert_eq!(
            tracker.observe(&ShellMark::EditorTarget { path: "/x/y.txt".into() }),
            Some(BlockEvent::EditorTarget("/x/y.txt".into()))
        );
        assert_eq!(tracker.editor(), Some("/x/y.txt"));
    }

    #[test]
    fn absent_status_is_preserved() {
        let mut tracker = BlockTracker::new();
        assert_eq!(
            tracker.observe(&ShellMark::CommandFinished { status: None }),
            Some(BlockEvent::CommandFinished(None))
        );
    }

    #[test]
    fn the_phase_follows_the_marks() {
        let mut tracker = BlockTracker::new();
        assert_eq!(tracker.phase(), BlockPhase::Unknown);

        tracker.observe(&ShellMark::PromptStart);
        assert_eq!(tracker.phase(), BlockPhase::Prompt);

        tracker.observe(&ShellMark::CommandStart);
        assert_eq!(tracker.phase(), BlockPhase::Typing);

        tracker.observe(&ShellMark::CommandExec);
        assert_eq!(tracker.phase(), BlockPhase::Running);

        tracker.observe(&ShellMark::CommandFinished { status: Some(3) });
        assert_eq!(tracker.phase(), BlockPhase::Finished(Some(3)));
        assert_eq!(tracker.phase().status(), Some(3));
        assert_eq!(tracker.phase().code(), "finished");
    }
}
