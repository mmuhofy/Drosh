//! The PTY master and slave pair.
//!
//! `portable-pty` does not compile for `target_os = "android"` (its `termios`
//! dependency is not `cfg`'d for Android), so the kernel's PTY syscalls are
//! called through `libc` directly. The sequence is the one Termi uses on
//! device:
//!
//! 1. `posix_openpt` (falling back to `/dev/ptmx`), `grantpt`, `unlockpt`.
//! 2. Open the slave through `TIOCGPTPEER`; on older kernels fall back to
//!    `TIOCGPTN` + `/dev/pts/N`, then to `ptsname_r`.
//! 3. Give the pair the size and the input mode the session asked for.

use std::ffi::CStr;
use std::fs::{File, OpenOptions};
use std::io;
use std::os::unix::io::{AsRawFd, FromRawFd, RawFd};

use std::os::unix::fs::OpenOptionsExt;

/// `ioctl` request type: bionic takes an `int`, glibc an `unsigned long`.
#[cfg(target_os = "android")]
pub(crate) type IoctlRequest = libc::c_int;
#[cfg(not(target_os = "android"))]
pub(crate) type IoctlRequest = libc::c_ulong;

// Asm-generic ioctl numbers, identical on Linux and Android. `TIOCGPTN` is not
// exported by the `libc` crate for `target_os = "android"`, so the values are
// spelled out here; the set matches the one Termi uses on device.
pub(crate) const TIOCSCTTY: IoctlRequest = 0x540e as IoctlRequest;
pub(crate) const TIOCSWINSZ: IoctlRequest = 0x5414 as IoctlRequest;
pub(crate) const TIOCGPTN: IoctlRequest = 0x8004_5430u32 as IoctlRequest;
pub(crate) const TIOCGPTPEER: IoctlRequest = 0x5441 as IoctlRequest;

/// The size the session asked for, as the kernel wants it.
pub(crate) struct WinSize {
    pub columns: u16,
    pub rows: u16,
    pub cell_width: u16,
    pub cell_height: u16,
}

/// Open a master/slave pair, sized and set up for the session.
///
/// The slave is returned open: the caller keeps it until the child has taken
/// it over, so a child that dies instantly cannot leave the master without a
/// peer.
pub(crate) fn open_pair(
    columns: u16,
    rows: u16,
    cell_width: u16,
    cell_height: u16,
) -> io::Result<(File, File)> {
    let master = open_master()?;
    let master_fd = master.as_raw_fd();

    let size = WinSize { columns, rows, cell_width, cell_height };
    set_winsize(master_fd, &size)?;

    let slave = open_slave(master_fd)?;
    set_utf8_input(master_fd);

    Ok((master, slave))
}

/// `posix_openpt` with the fallback the Android kernel needs.
///
/// `O_CLOEXEC` is set at open time rather than with a second call, so a fork
/// between the two cannot hand the descriptor to a child that should not have
/// it.
fn open_master() -> io::Result<File> {
    // The mut is only needed on Android, where the non-blocking bit is or-ed
    // in; the host build would call it unused.
    #[allow(unused_mut)]
    let mut flags = libc::O_RDWR | libc::O_NOCTTY | libc::O_CLOEXEC;
    #[cfg(target_os = "android")]
    {
        flags |= libc::O_NONBLOCK;
    }

    // SAFETY: `posix_openpt` only reads its flags argument.
    let fd = unsafe { libc::posix_openpt(flags) };
    if fd >= 0 {
        // SAFETY: the descriptor was just created and nobody else owns it.
        let file = unsafe { File::from_raw_fd(fd) };
        unlock(&file)?;
        return Ok(file);
    }

    // Some OEM kernels ship without `/dev/ptmx` reachable by name; the
    // device node itself still works.
    log::warn!("drosh-core: posix_openpt failed, falling back to /dev/ptmx");
    let file =
        OpenOptions::new().read(true).write(true).custom_flags(libc::O_NOCTTY).open("/dev/ptmx")?;
    unlock(&file)?;
    Ok(file)
}

fn unlock(master: &File) -> io::Result<()> {
    let fd = master.as_raw_fd();
    // SAFETY: `fd` is an open PTY master, which is what grantpt/unlockpt need.
    let granted = unsafe { libc::grantpt(fd) };
    if granted != 0 {
        return Err(io::Error::last_os_error());
    }
    // SAFETY: as above.
    let unlocked = unsafe { libc::unlockpt(fd) };
    if unlocked != 0 {
        return Err(io::Error::last_os_error());
    }
    Ok(())
}

/// Open the slave of `master_fd`, by whichever mechanism the kernel has.
fn open_slave(master_fd: RawFd) -> io::Result<File> {
    // SAFETY: `TIOCGPTPEER` returns a new descriptor for the slave.
    let fd = unsafe { libc::ioctl(master_fd, TIOCGPTPEER, libc::O_RDWR | libc::O_NOCTTY) };
    if fd >= 0 {
        // SAFETY: the descriptor was just created by the kernel.
        return Ok(unsafe { File::from_raw_fd(fd) });
    }

    let index = pty_index(master_fd)?;
    let path = format!("/dev/pts/{index}");
    match OpenOptions::new().read(true).write(true).custom_flags(libc::O_NOCTTY).open(&path) {
        Ok(file) => Ok(file),
        Err(_) => {
            // Last resort: the name the kernel reports, which a chrooted or
            // namespaced device may place somewhere else.
            let name = pts_name(master_fd)?;
            OpenOptions::new().read(true).write(true).custom_flags(libc::O_NOCTTY).open(name)
        }
    }
}

/// The slave's number, through `TIOCGPTN`.
fn pty_index(master_fd: RawFd) -> io::Result<u32> {
    let mut index: libc::c_uint = 0;
    // SAFETY: `TIOCGPTN` writes a `c_uint` into the pointer given to it.
    let result = unsafe { libc::ioctl(master_fd, TIOCGPTN, &mut index) };
    if result != 0 {
        return Err(io::Error::last_os_error());
    }
    Ok(index)
}

/// The slave's path, through `ptsname_r`.
fn pts_name(master_fd: RawFd) -> io::Result<String> {
    let mut buffer = [0 as libc::c_char; 128];
    // SAFETY: the buffer is `buffer.len()` bytes and the kernel writes at most
    // that many, NUL-terminated.
    let result = unsafe { libc::ptsname_r(master_fd, buffer.as_mut_ptr(), buffer.len()) };
    if result != 0 {
        return Err(io::Error::from_raw_os_error(result));
    }
    // SAFETY: the call above guarantees a NUL-terminated string in the buffer.
    Ok(unsafe { CStr::from_ptr(buffer.as_ptr()) }.to_string_lossy().into_owned())
}

/// Tell the PTY how big the grid is, so the guest's own idea of the window
/// matches the one being drawn.
fn set_winsize(master_fd: RawFd, size: &WinSize) -> io::Result<()> {
    let winsize = libc::winsize {
        ws_row: size.rows,
        ws_col: size.columns,
        ws_xpixel: size.columns.saturating_mul(size.cell_width),
        ws_ypixel: size.rows.saturating_mul(size.cell_height),
    };
    // SAFETY: `TIOCSWINSZ` reads a `winsize` from the pointer given to it.
    let result = unsafe { libc::ioctl(master_fd, TIOCSWINSZ, &winsize) };
    if result != 0 {
        return Err(io::Error::last_os_error());
    }
    Ok(())
}

/// Ask the line discipline for UTF-8 input.
///
/// Without `IUTF8` a backspace over a multi-byte character deletes one byte
/// instead of one character, which shows up as a mangled prompt.
fn set_utf8_input(master_fd: RawFd) {
    let mut termios = std::mem::MaybeUninit::<libc::termios>::uninit();
    // SAFETY: `TCGETS` fills the structure the pointer names.
    let got = unsafe { libc::tcgetattr(master_fd, termios.as_mut_ptr()) };
    if got != 0 {
        return;
    }
    // SAFETY: `tcgetattr` succeeded, so the structure is initialised.
    let mut termios = unsafe { termios.assume_init() };
    // IUTF8 is 0o40000 on both glibc and bionic; the `libc` crate does not
    // export it for Android, so the value is spelled out where it must be.
    #[cfg(target_os = "linux")]
    {
        termios.c_iflag |= libc::IUTF8;
    }
    #[cfg(target_os = "android")]
    {
        termios.c_iflag |= 0o40000;
    }
    // SAFETY: the structure is initialised and owned by this call.
    let _ = unsafe { libc::tcsetattr(master_fd, libc::TCSANOW, &termios) };
}
