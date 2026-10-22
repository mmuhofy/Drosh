//! The ligatures the renderer draws itself.
//!
//! ## Why the font's own tables are not enough
//!
//! A programming font keeps its ligatures in the GSUB table, under chained
//! context lookups: the ligature fires only when the characters around it
//! match a pattern the font spells out. That is what makes a ligature safe —
//! `->` in a path is not an arrow — and it is also why a terminal has to
//! ask for them by hand: a terminal has no idea whether the text in front of
//! it is code.
//!
//! ## What this module does
//!
//! It reads the font's `post` table — where the glyph names live — and finds
//! the glyphs whose names end in `.liga`: `hyphen_greater.liga`, and the rest.
//! A name is the components' names joined by an underscore, so
//! `hyphen_greater.liga` is the two characters `-` and `>`, and the font
//! already drew the glyph for them.
//!
//! The ligature is then applied the way a terminal wants it: the characters
//! are replaced by the font's own glyph for them, and the grid is unaffected
//! — the ligature covers the cells the characters covered.

use std::collections::HashMap;

use swash::FontRef;

/// A character sequence the font draws as a single glyph.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Ligature {
    /// The characters, in the order they were written.
    pub chars: Vec<char>,
    /// The glyph the font draws for them.
    pub gid: u16,
}

/// The ligatures a font carries.
#[derive(Debug, Clone, Default)]
pub struct Ligatures {
    /// Every ligature, longest first, so the longest match wins.
    entries: Vec<Ligature>,
}

impl Ligatures {
    /// The ligatures in `data`, if the font has any.
    ///
    /// A font with no `post` table, or one whose names are not the standard
    /// Macintosh ones, has no ligatures to find — that is not an error, it
    /// is a font without them.
    pub fn build(data: &[u8]) -> Option<Ligatures> {
        let font = FontRef::from_index(data, 0)?;
        let names = post_names(data)?;
        let charmap = font.charmap();

        // The name each character answers to. The font's own names, so a font
        // that calls the hyphen something else still works.
        let mut name_of_char: HashMap<&str, char> = HashMap::new();
        for codepoint in 0x20u32..=0x7e {
            let Some(character) = char::from_u32(codepoint) else { continue };
            let gid = charmap.map(character);
            if gid == 0 {
                continue;
            }
            if let Some(name) = names.get(gid as usize).and_then(|name| name.as_deref()) {
                name_of_char.insert(name, character);
            }
        }

        // The ligature glyphs, and the characters each one stands for.
        let mut entries: Vec<Ligature> = Vec::new();
        for (gid, name) in names.iter().enumerate() {
            let Some(name) = name else { continue };
            let Some(stem) = name.strip_suffix(".liga") else { continue };

            let mut chars = Vec::new();
            let mut known = true;
            for part in stem.split('_') {
                match name_of_char.get(part) {
                    Some(character) => chars.push(*character),
                    None => {
                        known = false;
                        break;
                    }
                }
            }
            if !known || chars.is_empty() {
                continue;
            }
            entries.push(Ligature { chars, gid: gid as u16 });
        }

        // The longest first: a three-character ligature beats the two that
        // start the same way.
        entries.sort_by_key(|entry| std::cmp::Reverse(entry.chars.len()));

        Some(Ligatures { entries })
    }

    /// Whether the font has any ligature at all.
    pub fn is_empty(&self) -> bool {
        self.entries.is_empty()
    }

    /// How many ligatures the font carries.
    pub fn len(&self) -> usize {
        self.entries.len()
    }

    /// The character sequences the font ligates, for diagnostics.
    pub fn sequences(&self) -> Vec<Vec<char>> {
        self.entries.iter().map(|entry| entry.chars.clone()).collect()
    }

    /// The ligature that starts the given characters, if there is one.
    pub fn at(&self, chars: &[char]) -> Option<&Ligature> {
        self.entries.iter().find(|entry| {
            entry.chars.len() <= chars.len() && entry.chars.iter().zip(chars).all(|(a, b)| a == b)
        })
    }
}

/// The glyph names in the font's `post` table, by glyph id.
///
/// A font whose `post` table is the short version has no names, and a font
/// whose names are the standard Macintosh ones has names this module can
/// still use for the ASCII range — which is where the ligature components
/// live.
fn post_names(data: &[u8]) -> Option<Vec<Option<String>>> {
    let post = table(data, b"post")?;
    if post.len() < 34 {
        return None;
    }

    // Only version 2.0 carries the names.
    let version = u32::from_be_bytes(post[0..4].try_into().ok()?);
    if version != 0x0002_0000 {
        return None;
    }

    let count = u16::from_be_bytes(post[32..34].try_into().ok()?) as usize;
    let indices = 34;
    if post.len() < indices + count * 2 {
        return None;
    }

    // The names themselves, in order.
    let mut custom: Vec<String> = Vec::new();
    let mut at = indices + count * 2;
    while at < post.len() {
        let length = post[at] as usize;
        at += 1;
        if at + length > post.len() {
            break;
        }
        custom.push(String::from_utf8_lossy(&post[at..at + length]).into_owned());
        at += length;
    }

    let mut names: Vec<Option<String>> = vec![None; count];
    for (gid, name) in names.iter_mut().enumerate() {
        let index =
            u16::from_be_bytes(post[indices + gid * 2..indices + gid * 2 + 2].try_into().ok()?)
                as usize;
        if index >= 258 {
            // The font's own name.
            *name = custom.get(index - 258).cloned();
        } else if (0x20..0x7f).contains(&index) {
            // One of the standard Macintosh names, which for this range are
            // the ASCII ones.
            *name = standard_name(index - 0x20).map(|name| name.to_string());
        }
    }

    Some(names)
}

/// A table in the font, by its four-byte tag.
fn table<'a>(data: &'a [u8], tag: &[u8; 4]) -> Option<&'a [u8]> {
    if data.len() < 12 {
        return None;
    }
    let count = u16::from_be_bytes(data[4..6].try_into().ok()?) as usize;
    for index in 0..count {
        let record = 12 + index * 16;
        if record + 16 > data.len() {
            return None;
        }
        if data[record..record + 4] != tag[..] {
            continue;
        }
        let offset = u32::from_be_bytes(data[record + 8..record + 12].try_into().ok()?) as usize;
        let length = u32::from_be_bytes(data[record + 12..record + 16].try_into().ok()?) as usize;
        let end = offset.checked_add(length)?;
        if end > data.len() {
            return None;
        }
        return Some(&data[offset..end]);
    }
    None
}

/// The standard Macintosh glyph names for ASCII, in code point order.
///
/// The `post` table's first 258 names are these; the rest of the table is
/// the font's own. The ligature components are ASCII, so this is the part
/// that matters.
const STANDARD_NAMES: &str = "space exclam quotedbl numbersign dollar percent ampersand \
    quotesingle parenleft parenright asterisk plus comma hyphen period slash zero one two \
    three four five six seven eight nine colon semicolon less equal greater question at A B C D \
    E F G H I J K L M N O P Q R S T U V W X Y Z bracketleft backslash bracketright \
    asciicircum underscore grave a b c d e f g h i j k l m n o p q r s t u v w x y z braceleft bar \
    braceright asciitilde";

/// The standard name at `index` (0..95), or none if there is none.
fn standard_name(index: usize) -> Option<&'static str> {
    STANDARD_NAMES.split_whitespace().nth(index)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_font_without_a_post_table_has_no_ligatures() {
        assert!(Ligatures::build(&[0, 1, 2, 3]).is_none());
    }
}
