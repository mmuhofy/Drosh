//! PTY creation and the child process on the other end (decision D8).
//!
//! `portable-pty` does not compile for `target_os = "android"` (its `termios`
//! dependency is not `cfg`'d for Android), so the kernel's PTY syscalls are
//! called through `libc` directly. The sequence is the one Termi uses on
//! device:
//!
//! 1. `posix_openpt` (falling back to `/dev/ptmx`), `grantpt`, `unlockpt`.
//! 2. Open the slave through `TIOCGPTPEER`; on older kernels fall back to
//!    `TIOCGPTN` + `/dev/pts/N`, then to `ptsname_r`.
//! 3. Spawn the child with `setsid` + `TIOCSCTTY` + `dup2` in `pre_exec`, so it
//!    owns the controlling terminal. If that login-tty path fails on an OEM
//!    kernel, fall back to attaching the slave as the child's stdio without a
//!    controlling terminal (the Termi lesson).
//!
//! `EventedPty`/`OnResize` are implemented here too, so `alacritty_terminal`'s
//! own event loop can poll this PTY.

use std::ffi::{CStr, CString};
use std::fs::{File, OpenOptions};
use std::io::{self, ErrorKind, Read};
use std::os::unix::io::{AsRawFd, FromRawFd, RawFd};
use std::os::unix::net::UnixStream;
use std::os::unix::process::CommandExt;
use std::process::{Child, Command, Stdio};
use std::sync::Arc;
use std::time::{Duration, Instant};

use alacritty_terminal::event::{OnResize, WindowSize};
use alacritty_terminal::tty::{ChildEvent, EventedPty, EventedReadWrite};
use polling::{Event as PollEvent, PollMode, Poller};
use signal_hook::SigId;

/// `ioctl` request type: bionic takes an `int`, glibc an `unsigned long`.
#[cfg(target_os = "android")]
type IoctlRequest = libc::c_int;
#[cfg(not(target_os = "android"))]
type IoctlRequest = libc::c_ulong;

// Asm-generic ioctl numbers, identical on Linux and Android. `TIOCGPTN` is not
// exported by the `libc` crate for `target_os = "android"`, so the values are
// spelled out here; the set matches the one Termi uses on device.
const TIOCSCTTY: IoctlRequest = 0x540e as IoctlRequest;
const TIOCSWINSZ: IoctlRequest = 0x5414 as IoctlRequest;
const TIOCGPTN: IoctlRequest = 0x8004_5430u32 as IoctlRequest;
const TIOCGPTPEER: IoctlRequest = 0x5441 as IoctlRequest;

/// Event keys of `alacritty_terminal`'s event loop.
///
/// They are private in the engine, so our `EventedPty` implementation has to
/// mirror them: the loop compares `event.key` against its own constants (0 for
/// PTY read/write readiness, 1 for child events) and would ignore everything
/// otherwise. Re-verify on every engine upgrade — recorded in `TODO.md`.
const PTY_READ_WRITE_TOKEN: usize = 0;
const PTY_CHILD_EVENT_TOKEN: usize = 1;

/// How the child process is started and how big the PTY starts out.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct PtyConfig {
    /// Program to run, e.g. `/system/bin/sh` or a chroot-ng path.
    pub program: String,
    /// Arguments (without `argv[0]`).
    pub args: Vec<String>,
    /// Extra environment variables; they win over the defaults below.
    pub env: Vec<(String, String)>,
    /// Working directory for the child.
    pub cwd: Option<String>,
    pub columns: u16,
    pub rows: u16,
    pub cell_width: u16,
    pub cell_height: u16,
}

/// Minimal PATH for the Android side. The app can override it through
/// [`PtyConfig::env`].
#[cfg(target_os = "android")]
const DEFAULT_ANDROID_PATH: &str = "/product/bin:/apex/com.android.runtime/bin:/system_ext/bin:/system/bin:/system/xbin:/vendor/bin";

/// A PTY master plus the child process on the slave side.
pub struct LocalPty {
    master: File,
    child: Child,
    signals: UnixStream,
    sig_id: SigId,
}

impl LocalPty {
    /// Open a PTY and start the child on the slave side.
    pub fn spawn(config: &PtyConfig) -> io::Result<Self> {
        let (master, slave) = open_pair(config)?;

        // Install the SIGCHLD self-pipe before the child exists, so even a
        // child that exits immediately is still seen by the event loop.
        let (signals, sig_id) = register_sigchld()?;

        let child = match spawn_login_tty(config, slave.as_raw_fd()) {
            Ok(child) => child,
            Err(err) => {
                log::warn!("drosh-core: login-tty spawn failed ({err}), retrying with slave stdio");
                spawn_slave_stdio(config, &slave)?
            }
        };

        // The child owns the slave side from here on.
        drop(slave);
        set_nonblocking(master.as_raw_fd())?;

        Ok(Self { master, child, signals, sig_id })
    }

    /// Process id of the child.
    pub fn pid(&self) -> u32 {
        self.child.id()
    }
}

impl Drop for LocalPty {
    fn drop(&mut self) {
        // Hang up: closing the master (below, after this) ends the terminal's
        // session, SIGHUP tells the shell to go away.
        let _ = unsafe { libc::kill(self.child.id() as i32, libc::SIGHUP) };

        // Do not block forever on a child that ignores SIGHUP.
        let deadline = Instant::now() + Duration::from_millis(200);
        while Instant::now() < deadline {
            match self.child.try_wait() {
                Ok(Some(_)) | Err(_) => break,
                Ok(None) => std::thread::sleep(Duration::from_millis(10)),
            }
        }

        if matches!(self.child.try_wait(), Ok(None)) {
            let _ = unsafe { libc::kill(self.child.id() as i32, libc::SIGKILL) };
            let _ = self.child.wait();
        }

        signal_hook::low_level::unregister(self.sig_id);
    }
}

impl AsRawFd for LocalPty {
    fn as_raw_fd(&self) -> RawFd {
        self.master.as_raw_fd()
    }
}

impl EventedReadWrite for LocalPty {
    type Reader = File;
    type Writer = File;

    unsafe fn register(
        &mut self,
        poll: &Arc<Poller>,
        mut interest: PollEvent,
        mode: PollMode,
    ) -> io::Result<()> {
        interest.key = PTY_READ_WRITE_TOKEN;
        unsafe {
            poll.add_with_mode(&self.master, interest, mode)?;
            poll.add_with_mode(
                &self.signals,
                PollEvent::readable(PTY_CHILD_EVENT_TOKEN),
                PollMode::Level,
            )?;
        }
        Ok(())
    }

    fn reregister(
        &mut self,
        poll: &Arc<Poller>,
        mut interest: PollEvent,
        mode: PollMode,
    ) -> io::Result<()> {
        interest.key = PTY_READ_WRITE_TOKEN;
        poll.modify_with_mode(&self.master, interest, mode)?;
        poll.modify_with_mode(
            &self.signals,
            PollEvent::readable(PTY_CHILD_EVENT_TOKEN),
            PollMode::Level,
        )?;
        Ok(())
    }

    fn deregister(&mut self, poll: &Arc<Poller>) -> io::Result<()> {
        poll.delete(&self.master)?;
        poll.delete(&self.signals)?;
        Ok(())
    }

    fn reader(&mut self) -> &mut Self::Reader {
        &mut self.master
    }

    fn writer(&mut self) -> &mut Self::Writer {
        &mut self.master
    }
}

impl EventedPty for LocalPty {
    fn next_child_event(&mut self) -> Option<ChildEvent> {
        // One byte on the pipe means "SIGCHLD"; drain it so the level-triggered
        // poller does not fire again.
        let mut buffer = [0u8; 1];
        match self.signals.read(&mut buffer) {
            Ok(_) => {}
            Err(err) if err.kind() == ErrorKind::WouldBlock => return None,
            Err(err) => {
                log::warn!("drosh-core: SIGCHLD pipe read failed: {err}");
                return None;
            }
        }

        match self.child.try_wait() {
            Ok(Some(status)) => Some(ChildEvent::Exited(Some(status))),
            Ok(None) => None,
            Err(err) => {
                log::warn!("drosh-core: child wait failed: {err}");
                None
            }
        }
    }
}

impl OnResize for LocalPty {
    fn on_resize(&mut self, window_size: WindowSize) {
        let winsize = libc::winsize {
            ws_row: window_size.num_lines,
            ws_col: window_size.num_cols,
            ws_xpixel: window_size.num_cols.saturating_mul(window_size.cell_width),
            ws_ypixel: window_size.num_lines.saturating_mul(window_size.cell_height),
        };

        if unsafe { libc::ioctl(self.master.as_raw_fd(), TIOCSWINSZ, &winsize) } != 0 {
            log::warn!("drosh-core: TIOCSWINSZ failed: {}", io::Error::last_os_error());
        }
    }
}

/// Create the PTY pair and set the initial window size.
fn open_pair(config: &PtyConfig) -> io::Result<(File, File)> {
    let master = open_master()?;
    let master_fd = master.as_raw_fd();

    // bionic's grantpt is a no-op and some OEM kernels still report an error;
    // not being able to grant access is not fatal.
    if unsafe { libc::grantpt(master_fd) } != 0 {
        log::warn!("drosh-core: grantpt failed (continuing): {}", io::Error::last_os_error());
    }

    if unsafe { libc::unlockpt(master_fd) } != 0 {
        return Err(io::Error::last_os_error());
    }

    let slave = open_slave(master_fd)?;
    set_winsize(master_fd, config)?;
    set_utf8_input(master_fd);
    Ok((master, slave))
}

/// `posix_openpt`, falling back to opening `/dev/ptmx`.
fn open_master() -> io::Result<File> {
    let fd = unsafe { libc::posix_openpt(libc::O_RDWR | libc::O_NOCTTY | libc::O_CLOEXEC) };
    if fd >= 0 {
        // SAFETY: posix_openpt handed us a fresh descriptor that we now own.
        return Ok(unsafe { File::from_raw_fd(fd) });
    }

    let posix_error = io::Error::last_os_error();
    OpenOptions::new().read(true).write(true).open("/dev/ptmx").map_err(|fallback| {
        io::Error::other(format!("posix_openpt failed ({posix_error}); /dev/ptmx: {fallback}"))
    })
}

/// Open the slave side: `TIOCGPTPEER` first, then `/dev/pts/N`, then
/// `ptsname_r`.
fn open_slave(master_fd: RawFd) -> io::Result<File> {
    let peer = unsafe { libc::ioctl(master_fd, TIOCGPTPEER, libc::O_RDWR | libc::O_NOCTTY) };
    if peer >= 0 {
        // SAFETY: TIOCGPTPEER handed us a fresh descriptor that we now own.
        return Ok(unsafe { File::from_raw_fd(peer) });
    }

    let peer_error = io::Error::last_os_error();

    if let Ok(index) = pty_index(master_fd) {
        let path = format!("/dev/pts/{index}");
        if let Ok(file) = OpenOptions::new().read(true).write(true).open(&path) {
            return Ok(file);
        }
    }

    let path = pts_name(master_fd)?;
    OpenOptions::new().read(true).write(true).open(&path).map_err(|err| {
        io::Error::other(format!("failed to open slave {path}: {err} (TIOCGPTPEER: {peer_error})"))
    })
}

/// `TIOCGPTN`: the slave's number for `/dev/pts/N`.
fn pty_index(master_fd: RawFd) -> io::Result<u32> {
    let mut index: libc::c_uint = 0;
    if unsafe { libc::ioctl(master_fd, TIOCGPTN, &mut index) } != 0 {
        return Err(io::Error::last_os_error());
    }
    Ok(index)
}

/// `ptsname_r`: the slave's path.
fn pts_name(master_fd: RawFd) -> io::Result<String> {
    let mut buffer = [0u8; 128];
    let result = unsafe {
        libc::ptsname_r(master_fd, buffer.as_mut_ptr() as *mut libc::c_char, buffer.len())
    };
    if result != 0 {
        let error = io::Error::from_raw_os_error(result);
        return Err(io::Error::other(format!("ptsname_r failed: {error}")));
    }

    let name = unsafe { CStr::from_ptr(buffer.as_ptr() as *const libc::c_char) };
    name.to_str().map(str::to_owned).map_err(|_| io::Error::other("ptsname_r returned non-UTF-8"))
}

fn set_winsize(master_fd: RawFd, config: &PtyConfig) -> io::Result<()> {
    let winsize = libc::winsize {
        ws_row: config.rows,
        ws_col: config.columns,
        ws_xpixel: config.columns.saturating_mul(config.cell_width),
        ws_ypixel: config.rows.saturating_mul(config.cell_height),
    };

    if unsafe { libc::ioctl(master_fd, TIOCSWINSZ, &winsize) } != 0 {
        return Err(io::Error::last_os_error());
    }
    Ok(())
}

/// Tell the line discipline that input is UTF-8.
///
/// With `IUTF8` the tty erases a whole multibyte character per backspace;
/// without it the guest's readline leaves broken bytes behind when the user
/// edits accented or CJK text. bionic does not set the flag, so we set it on the
/// master, which shares its termios with the slave — the same thing alacritty's
/// `tty/unix.rs` does for the desktop.
///
/// Failing here is not fatal: the terminal still works, only non-ASCII editing
/// degrades, so the error is logged instead of propagated.
fn set_utf8_input(master_fd: RawFd) {
    let mut termios = std::mem::MaybeUninit::<libc::termios>::uninit();
    if unsafe { libc::tcgetattr(master_fd, termios.as_mut_ptr()) } != 0 {
        log::warn!("drosh-core: tcgetattr failed: {}", io::Error::last_os_error());
        return;
    }

    // SAFETY: `tcgetattr` returned success, so it initialised the struct.
    let mut termios = unsafe { termios.assume_init() };
    if termios.c_iflag & libc::IUTF8 != 0 {
        return;
    }

    termios.c_iflag |= libc::IUTF8;
    if unsafe { libc::tcsetattr(master_fd, libc::TCSANOW, &termios) } != 0 {
        log::warn!("drosh-core: tcsetattr(IUTF8) failed: {}", io::Error::last_os_error());
    }
}

/// Install the SIGCHLD self-pipe; returns the read end and the handler id.
fn register_sigchld() -> io::Result<(UnixStream, SigId)> {
    let (sender, receiver) = UnixStream::pair()?;
    let sig_id = signal_hook::low_level::pipe::register(signal_hook::consts::SIGCHLD, sender)
        .map_err(|err| io::Error::other(format!("SIGCHLD handler registration failed: {err}")))?;
    receiver.set_nonblocking(true)?;
    Ok((receiver, sig_id))
}

/// A terminal gets a clean, explicit environment: the Android app's own
/// variables must not leak into the shell.
fn configure_command(command: &mut Command, config: &PtyConfig) {
    command.args(&config.args);
    command.env_clear();
    command.env("TERM", "xterm-256color");
    command.env("COLORTERM", "truecolor");
    command.env("LANG", "en_US.UTF-8");

    #[cfg(target_os = "android")]
    command.env("PATH", DEFAULT_ANDROID_PATH);

    for (key, value) in &config.env {
        command.env(key, value);
    }
}

/// Full POSIX login-tty: `setsid` + `TIOCSCTTY` + `dup2` in the child.
fn spawn_login_tty(config: &PtyConfig, slave_fd: RawFd) -> io::Result<Child> {
    let mut command = Command::new(&config.program);
    configure_command(&mut command, config);
    // Replaced by the slave in `pre_exec`; std sets these up first.
    command.stdin(Stdio::null());
    command.stdout(Stdio::null());
    command.stderr(Stdio::null());

    let cwd = cwd_c_string(config)?;

    // SAFETY: `slave_fd` stays open in the parent until the child has been
    // spawned, and the closure only calls async-signal-safe libc functions.
    unsafe {
        command.pre_exec(move || {
            if let Some(dir) = cwd.as_ref() {
                chdir(dir)?;
            }
            setup_child_tty(slave_fd)
        });
    }

    // `spawn` does the exec itself.
    //
    // This used to exec inside `pre_exec`, through a pipe that reported why an
    // exec failed. That is a deadlock: `std::process::Command` writes its own
    // handshake after `pre_exec` returns, so a `pre_exec` that execs never
    // returns and `spawn` waits for a handshake that cannot come. The session
    // started and then hung, with the child running and the caller still
    // inside this call.
    //
    // What the pipe bought was the reason an exec failed. `spawn` returns
    // `Err` for that too — the OS error is there — and a program that cannot
    // be run is a failure the session reports rather than one it survives.
    command.spawn()
}

/// Attach the slave as the child's stdio, without a controlling terminal.
fn spawn_slave_stdio(config: &PtyConfig, slave: &File) -> io::Result<Child> {
    let mut command = Command::new(&config.program);
    configure_command(&mut command, config);
    command.stdin(Stdio::from(slave.try_clone()?));
    command.stdout(Stdio::from(slave.try_clone()?));
    command.stderr(Stdio::from(slave.try_clone()?));

    let cwd = cwd_c_string(config)?;

    // SAFETY: the closure only calls async-signal-safe libc functions.
    unsafe {
        command.pre_exec(move || match cwd.as_ref() {
            Some(dir) => chdir(dir),
            None => Ok(()),
        });
    }

    // `spawn` does the exec itself — see `spawn_login_tty` for why an exec
    // inside `pre_exec` deadlocks the caller.
    command.spawn()
}

/// POSIX login-tty sequence, run between `fork` and `exec`.
///
/// Only async-signal-safe calls belong here: no logging, no allocation. A
/// failing `setsid`/`TIOCSCTTY` is survivable (the child still writes to a
/// usable PTY), a failing `dup2` is not.
fn setup_child_tty(slave_fd: RawFd) -> io::Result<()> {
    let _ = unsafe { libc::setsid() };
    let _ = unsafe { libc::ioctl(slave_fd, TIOCSCTTY, 0) };

    for target in 0..3 {
        if unsafe { libc::dup2(slave_fd, target) } < 0 {
            return Err(io::Error::last_os_error());
        }
    }

    if slave_fd > 2 {
        unsafe { libc::close(slave_fd) };
    }
    Ok(())
}

fn chdir(dir: &CString) -> io::Result<()> {
    if unsafe { libc::chdir(dir.as_ptr()) } != 0 {
        return Err(io::Error::last_os_error());
    }
    Ok(())
}

fn cwd_c_string(config: &PtyConfig) -> io::Result<Option<CString>> {
    match config.cwd.as_deref() {
        None => Ok(None),
        Some(dir) => {
            let dir = CString::new(dir)
                .map_err(|_| io::Error::other("working directory contains a NUL byte"))?;
            Ok(Some(dir))
        }
    }
}

/// The event loop expects a non-blocking PTY: it reads until `WouldBlock` and
/// waits for readiness through the poller.
fn set_nonblocking(fd: RawFd) -> io::Result<()> {
    let flags = unsafe { libc::fcntl(fd, libc::F_GETFL) };
    if flags < 0 {
        return Err(io::Error::last_os_error());
    }

    if unsafe { libc::fcntl(fd, libc::F_SETFL, flags | libc::O_NONBLOCK) } < 0 {
        return Err(io::Error::last_os_error());
    }

    Ok(())
}
