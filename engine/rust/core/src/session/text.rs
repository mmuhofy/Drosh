//! Strip ANSI escape sequences from output text.
//!
//! The block engine and the session logs must present the *visible* text, not
//! the control bytes the emulator consumed. This is intentionally not a full VT
//! parser — it removes only the sequences a transcript display would never
//! show, which is all the callers need.

/// Remove escape sequences from `input`, returning the printable remainder.
///
/// Newlines, tabs and carriage returns are preserved (transcripts keep layout);
/// other C0 controls are dropped.
pub fn strip_ansi(input: &[u8]) -> String {
    let mut out = String::with_capacity(input.len());
    let mut i = 0;
    while i < input.len() {
        let b = input[i];
        if b == 0x1b {
            // ESC — classify the sequence from the next byte.
            if i + 1 >= input.len() {
                i += 1;
                continue;
            }
            match input[i + 1] {
                b'[' => {
                    // CSI: collect until a final byte in 0x40..=0x7e.
                    i += 2;
                    while i < input.len() && !(0x40..=0x7e).contains(&input[i]) {
                        i += 1;
                    }
                    i += 1; // consume the final byte too
                }
                b']' => {
                    // OSC: until BEL or ESC \.
                    i += 2;
                    while i < input.len() {
                        match input[i] {
                            0x07 => {
                                i += 1;
                                break;
                            }
                            0x1b => {
                                i += 1;
                                if i < input.len() && input[i] == 0x5c {
                                    i += 1; // swallow the ST backslash
                                }
                                break;
                            }
                            _ => i += 1,
                        }
                    }
                }
                0x28..=0x2b => {
                    // charset designation: ESC ( final-byte.
                    i += 3;
                }
                _ => {
                    // lone ESC + one byte.
                    i += 2;
                }
            }
        } else if b >= 0x20 || b == b'\n' || b == b'\t' || b == b'\r' {
            out.push(b as char);
            i += 1;
        } else {
            // Drop other C0 controls.
            i += 1;
        }
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn keeps_plain_text() {
        assert_eq!(strip_ansi(b"hello world"), "hello world");
    }

    #[test]
    fn removes_csi() {
        assert_eq!(strip_ansi(b"a\x1b[31mred\x1b[0mb"), "aredb");
        assert_eq!(strip_ansi(b"\x1b[2J\x1b[Hcleared"), "cleared");
    }

    #[test]
    fn removes_osc() {
        assert_eq!(strip_ansi(b"\x1b]0;title\x07rest"), "rest");
        assert_eq!(strip_ansi(b"x\x1b]11;rgb:1/2/3\x1b\\y"), "xy");
    }

    #[test]
    fn preserves_layout_controls() {
        assert_eq!(strip_ansi(b"a\nb\tc"), "a\nb\tc");
        assert_eq!(strip_ansi(b"a\rb"), "a\rb");
    }

    #[test]
    fn drops_other_controls() {
        assert_eq!(strip_ansi(b"a\x07bell\x01"), "abell");
    }

    #[test]
    fn handles_split_like_input() {
        // A lone ESC at the very end must not panic or swallow following bytes.
        assert_eq!(strip_ansi(b"ok\x1b"), "ok");
    }
}
