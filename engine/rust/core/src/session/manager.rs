//! The session handle table.
//!
//! Kotlin only ever sees an opaque, positive id (AGENTS.md §9: never a raw
//! pointer — Termi's lesson with negative/garbage handles). The table lives
//! behind one global lock; sessions are `Arc`s so a lookup cannot be
//! invalidated while it is in use.

use std::collections::HashMap;
use std::sync::{Arc, Mutex, MutexGuard, OnceLock};

use crate::session::{Session, SessionConfig, SessionError};

/// Upper bound on live sessions. The prototype has no tab bar, so this is a
/// safety net against a leaking caller rather than a real limit.
pub const MAX_SESSIONS: usize = 8;

/// Handle table.
pub struct Manager {
    next_id: u64,
    sessions: HashMap<u64, Arc<Session>>,
}

impl Manager {
    /// Id 0 is reserved ("no session"), so the first handle is 1.
    pub fn new() -> Self {
        Self { next_id: 1, sessions: HashMap::new() }
    }

    /// Start a session and assign it a handle.
    pub fn spawn(&mut self, config: &SessionConfig) -> Result<u64, SessionError> {
        if self.sessions.len() >= MAX_SESSIONS {
            return Err(SessionError::LimitReached);
        }

        let id = self.next_id;
        let session = Session::spawn(id, config)?;

        self.next_id = self.next_id.saturating_add(1);
        self.sessions.insert(id, Arc::new(session));
        Ok(id)
    }

    /// Look up a live session.
    pub fn get(&self, id: u64) -> Option<Arc<Session>> {
        self.sessions.get(&id).cloned()
    }

    /// Forget a session without stopping it.
    pub fn remove(&mut self, id: u64) -> Option<Arc<Session>> {
        self.sessions.remove(&id)
    }

    pub fn len(&self) -> usize {
        self.sessions.len()
    }

    pub fn is_empty(&self) -> bool {
        self.sessions.is_empty()
    }
}

impl Default for Manager {
    fn default() -> Self {
        Self::new()
    }
}

static MANAGER: OnceLock<Mutex<Manager>> = OnceLock::new();

/// The global table lock. A poisoned lock is recovered: one panicking caller
/// must not take the session table with it.
pub fn lock() -> MutexGuard<'static, Manager> {
    MANAGER
        .get_or_init(|| Mutex::new(Manager::new()))
        .lock()
        .unwrap_or_else(|poisoned| poisoned.into_inner())
}

/// Look up a live session by handle.
pub fn get(id: u64) -> Option<Arc<Session>> {
    lock().get(id)
}

/// Start a session and return its handle.
pub fn spawn(config: &SessionConfig) -> Result<u64, SessionError> {
    lock().spawn(config)
}

/// Stop a session and forget its handle.
///
/// The session is removed from the table *before* it is stopped, so a
/// concurrent lookup can never hand out a session that is already going away.
pub fn close(id: u64) -> Result<(), SessionError> {
    match lock().remove(id) {
        Some(session) => {
            session.shutdown();
            Ok(())
        }
        None => Err(SessionError::InvalidHandle),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn handles_start_at_one_and_unknown_handles_are_none() {
        let manager = Manager::new();
        assert!(manager.is_empty());
        assert_eq!(manager.len(), 0);
        assert!(manager.get(0).is_none());
        assert!(manager.get(1).is_none());
        assert!(manager.get(u64::MAX).is_none());
    }

    #[test]
    fn remove_of_unknown_handle_is_none() {
        let mut manager = Manager::new();
        assert!(manager.remove(1).is_none());
        assert_eq!(manager.len(), 0);
    }
}
