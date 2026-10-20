//! The JNI boundary.
//!
//! Every `native*` function returns a handle or an [`ErrorCode`]; nothing
//! throws. Across this boundary a panic is a process abort (the release profile
//! uses `panic = "abort"`), so the error channel is the return value plus a
//! `log` line with the details.
//!
//! **The Kotlin mirror of [`ErrorCode`] lives in `NativeCore.kt`; the numeric
//! values are frozen and guarded by a test in this file.**

pub mod block;
pub mod cells;
pub mod colors;
pub mod events;
pub mod font;
pub mod search;
pub mod selection;
pub mod session;
pub mod snapshot;
pub mod text;
pub mod tint;
pub mod url;

use std::sync::Arc;

use jni::objects::{JIntArray, JString};
use jni::sys::jint;
use jni::JNIEnv;

use drosh_bridge::snapshot::SnapshotError;
use drosh_session::manager;
use drosh_session::{Session, SessionError};

/// Result type of a `native*` function.
pub type FfiResult<T> = Result<T, ErrorCode>;

/// Error codes shared with Kotlin.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
#[repr(i32)]
pub enum ErrorCode {
    /// No error.
    Ok = 0,
    /// Unknown session handle.
    InvalidHandle = -1,
    /// Too many live sessions.
    TooManySessions = -2,
    /// The PTY could not be created or the child could not be spawned.
    SpawnFailed = -3,
    /// An argument was rejected (empty program, bad dimensions, bad encoding).
    InvalidArgument = -4,
    /// A syscall or channel operation failed.
    Io = -5,
    /// An internal invariant was violated.
    Internal = -6,
    /// A caller-provided buffer or array is too small.
    BufferTooSmall = -7,
    /// The feature is not part of the prototype yet.
    NotSupported = -8,
}

impl ErrorCode {
    /// The value the JNI functions return.
    pub fn as_jint(self) -> jint {
        self as jint
    }

    /// The number used by the Kotlin mirror.
    pub fn code(self) -> i32 {
        self as i32
    }
}

impl std::fmt::Display for ErrorCode {
    fn fmt(&self, formatter: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(formatter, "{self:?}")
    }
}

impl From<SessionError> for ErrorCode {
    fn from(err: SessionError) -> Self {
        match err {
            SessionError::InvalidArgument(_) => Self::InvalidArgument,
            SessionError::InvalidHandle => Self::InvalidHandle,
            SessionError::LimitReached => Self::TooManySessions,
            SessionError::SpawnFailed(_) => Self::SpawnFailed,
            SessionError::Io(_) => Self::Io,
            SessionError::Internal(_) => Self::Internal,
        }
    }
}

impl From<SnapshotError> for ErrorCode {
    fn from(err: SnapshotError) -> Self {
        match err {
            SnapshotError::BufferTooSmall { .. } | SnapshotError::DamageOutTooSmall { .. } => {
                Self::BufferTooSmall
            }
            SnapshotError::InvalidGrid => Self::Internal,
        }
    }
}

/// Log an error and return its code.
pub fn fail(err: impl std::fmt::Display + Into<ErrorCode>) -> ErrorCode {
    // The message is rendered before the conversion moves `err`.
    let message = err.to_string();
    let code = err.into();
    log::warn!("drosh-core: {message} ({code})");
    code
}

/// Map a `Result<(), _>` to the `jint` return convention.
pub fn finish(result: FfiResult<()>) -> jint {
    match result {
        Ok(()) => ErrorCode::Ok.as_jint(),
        Err(code) => fail(code).as_jint(),
    }
}

/// Look up a session handle.
pub fn session_or(handle: i64) -> FfiResult<Arc<Session>> {
    let id = u64::try_from(handle).map_err(|_| ErrorCode::InvalidHandle)?;
    manager::get(id).ok_or(ErrorCode::InvalidHandle)
}

/// Read a Java string; `null` becomes `None`.
pub fn optional_string(env: &mut JNIEnv<'_>, value: &JString<'_>) -> FfiResult<Option<String>> {
    if value.is_null() {
        return Ok(None);
    }

    env.get_string(value).map(|text| Some(text.into())).map_err(|_| ErrorCode::InvalidArgument)
}

/// Read a Java string that must be present.
pub fn required_string(env: &mut JNIEnv<'_>, value: &JString<'_>) -> FfiResult<String> {
    optional_string(env, value)?.ok_or(ErrorCode::InvalidArgument)
}

/// Length of a caller-provided int array.
pub fn int_array_len(env: &JNIEnv<'_>, array: &JIntArray<'_>) -> FfiResult<usize> {
    let length = env.get_array_length(array).map_err(|_| ErrorCode::InvalidArgument)?;
    Ok(length as usize)
}

/// Copy `values` into the caller's int array.
pub fn write_int_array(env: &JNIEnv<'_>, array: &JIntArray<'_>, values: &[i32]) -> FfiResult<()> {
    env.set_int_array_region(array, 0, values).map_err(|_| ErrorCode::Internal)
}

#[cfg(test)]
mod tests {
    use super::*;

    /// `NativeCore.kt` mirrors these numbers; changing one of them is a
    /// breaking change on both sides of the boundary.
    #[test]
    fn error_codes_are_frozen() {
        assert_eq!(ErrorCode::Ok.code(), 0);
        assert_eq!(ErrorCode::InvalidHandle.code(), -1);
        assert_eq!(ErrorCode::TooManySessions.code(), -2);
        assert_eq!(ErrorCode::SpawnFailed.code(), -3);
        assert_eq!(ErrorCode::InvalidArgument.code(), -4);
        assert_eq!(ErrorCode::Io.code(), -5);
        assert_eq!(ErrorCode::Internal.code(), -6);
        assert_eq!(ErrorCode::BufferTooSmall.code(), -7);
        assert_eq!(ErrorCode::NotSupported.code(), -8);
    }

    #[test]
    fn session_errors_map_to_codes() {
        let cases = [
            (SessionError::InvalidHandle, ErrorCode::InvalidHandle),
            (SessionError::LimitReached, ErrorCode::TooManySessions),
            (SessionError::Io("x".to_owned()), ErrorCode::Io),
            (SessionError::Internal("x".to_owned()), ErrorCode::Internal),
            (SessionError::SpawnFailed("x".to_owned()), ErrorCode::SpawnFailed),
            (SessionError::InvalidArgument("x".to_owned()), ErrorCode::InvalidArgument),
        ];

        for (error, expected) in cases {
            assert_eq!(ErrorCode::from(error), expected);
        }
    }
}
