//! Minimal `log` backend.
//!
//! No extra dependency: on the device the app learns about failures through
//! the JNI return values and the event queue, so this logger only has to be
//! useful for host tests (stderr) and for `adb logcat` in the cases where
//! the app's stderr is captured.
//!
//! `DROSH_LOG` selects the level (`trace`/`debug`/`info`/`warn`/`error`/`off`).

use std::sync::Once;

struct StderrLogger;

impl log::Log for StderrLogger {
    fn enabled(&self, _metadata: &log::Metadata<'_>) -> bool {
        // Level filtering happens in `log` itself via `set_max_level`.
        true
    }

    fn log(&self, record: &log::Record<'_>) {
        if self.enabled(record.metadata()) {
            eprintln!("[drosh-core {}] {}", record.level(), record.args());
        }
    }

    fn flush(&self) {}
}

static LOGGER: StderrLogger = StderrLogger;
static INIT: Once = Once::new();

/// Install the logger; calling this more than once is a no-op.
pub fn init() {
    INIT.call_once(|| {
        let level = match std::env::var("DROSH_LOG").ok().as_deref() {
            Some("trace") => log::LevelFilter::Trace,
            Some("debug") => log::LevelFilter::Debug,
            Some("warn") => log::LevelFilter::Warn,
            Some("error") => log::LevelFilter::Error,
            Some("off") => log::LevelFilter::Off,
            _ => log::LevelFilter::Info,
        };

        let _ = log::set_logger(&LOGGER);
        log::set_max_level(level);
    });
}
