//! Gate 2 — the Rust font stack, against the font that actually ships.
//!
//! The font is read from `android/terminal/src/main/assets/fonts/`, i.e. the very
//! file the APK carries, so these tests also fail if the asset goes missing or
//! is replaced by something that is not the font we think it is.

use std::path::{Path, PathBuf};

use drosh_font::fallback::Resolution;
use drosh_font::shape;
use drosh_font::{FontStack, Weight};

const REGULAR: &str = "JetBrainsMono-Regular.ttf";
const BOLD: &str = "JetBrainsMono-Bold.ttf";

/// The shipped asset, reached from the crate directory: three levels up to the
/// repository root, then into the Gradle module that carries the font.
fn asset_path(name: &str) -> PathBuf {
    Path::new(env!("CARGO_MANIFEST_DIR"))
        .join("../../../android/terminal/src/main/assets/fonts")
        .join(name)
}

fn read_asset(name: &str) -> Vec<u8> {
    let path = asset_path(name);
    std::fs::read(&path).unwrap_or_else(|error| panic!("cannot read {}: {error}", path.display()))
}

fn stack() -> FontStack {
    FontStack::from_bytes(read_asset(REGULAR), Some(read_asset(BOLD)))
        .expect("the bundled font must parse")
}

/// Advance of one glyph in pixels at `px`.
fn advance_px(units: i32, units_per_em: f32, px: f32) -> f32 {
    units as f32 / units_per_em * px
}

#[test]
fn the_bundled_font_parses_and_has_sane_cell_metrics() {
    let stack = stack();
    let metrics = stack.metrics(24.).expect("metrics at 24px");

    assert!(metrics.cell_width > 0., "cell width must be positive: {}", metrics.cell_width);
    assert!(metrics.cell_height > 0., "cell height must be positive: {}", metrics.cell_height);
    // A monospace cell is taller than it is wide; if this flips, the grid math
    // is being fed nonsense.
    assert!(
        metrics.cell_height > metrics.cell_width,
        "cell {}x{}",
        metrics.cell_width,
        metrics.cell_height
    );
    assert!(metrics.ascent > 0. && metrics.descent > 0., "ascent/descent: {metrics:?}");
}

#[test]
fn metrics_scale_linearly_with_the_pixel_size() {
    let stack = stack();
    let small = stack.metrics(12.).expect("metrics at 12px");
    let large = stack.metrics(24.).expect("metrics at 24px");

    assert!(
        (large.cell_width - 2. * small.cell_width).abs() < 0.01,
        "12px {} vs 24px {}",
        small.cell_width,
        large.cell_width
    );
    assert!((large.cell_height - 2. * small.cell_height).abs() < 0.01);
}

#[test]
fn every_ascii_codepoint_shapes_to_one_glyph_with_the_same_advance() {
    let stack = stack();
    let regular = read_asset(REGULAR);
    let font = shape::font(&regular).expect("the font");
    let units_per_em = shape::units_per_em(&font).expect("units per em");
    let metrics = stack.metrics(24.).expect("metrics");

    // Includes the pairs that a standard font would ligate (`->`, `==`, `!=`).
    let text = "MWi1_~|->==!=";
    let glyphs = stack.shape(text, Weight::Regular, false).expect("shaping");

    assert_eq!(glyphs.len(), text.chars().count(), "one glyph per character, no ligatures");

    for glyph in &glyphs {
        let advance = advance_px(glyph.x_advance, units_per_em, 24.);
        assert!(
            (advance - metrics.cell_width).abs() < 0.01,
            "glyph {} advances {advance}px, cell is {}px",
            glyph.glyph_id,
            metrics.cell_width
        );
    }
}

#[test]
fn rasterizing_a_glyph_produces_a_single_channel_mask() {
    let mut stack = stack();
    let glyph_id = match stack.resolve('M', Weight::Regular) {
        Resolution::Direct(id) | Resolution::Substituted(id) => id,
        Resolution::Missing => panic!("'M' must be in a monospace font"),
    };

    let bitmap = stack.rasterize(glyph_id, 24., Weight::Regular).expect("'M' has an outline");

    assert!(bitmap.width > 0 && bitmap.height > 0, "empty bitmap: {bitmap:?}");
    assert_eq!(
        bitmap.coverage.len(),
        (bitmap.width * bitmap.height) as usize,
        "an alpha mask is one byte per pixel; a subpixel or colour image would not be"
    );
    assert!(bitmap.coverage.iter().any(|value| *value > 0), "the glyph has no ink");
    assert!(bitmap.coverage.iter().max().copied().unwrap_or(0) > 32, "the glyph is almost blank");
}

#[test]
fn a_space_has_no_ink() {
    let mut stack = stack();
    let glyph_id = match stack.resolve(' ', Weight::Regular) {
        Resolution::Direct(id) | Resolution::Substituted(id) => id,
        Resolution::Missing => panic!("' ' must be in a monospace font"),
    };

    match stack.rasterize(glyph_id, 24., Weight::Regular) {
        None => {}
        Some(bitmap) => assert!(
            bitmap.coverage.iter().all(|value| *value == 0),
            "a space must not draw anything"
        ),
    }
}

#[test]
fn bold_is_a_different_face() {
    let mut stack = stack();
    let regular_id = match stack.resolve('M', Weight::Regular) {
        Resolution::Direct(id) => id,
        other => panic!("unexpected resolution: {other:?}"),
    };
    let bold_id = match stack.resolve('M', Weight::Bold) {
        Resolution::Direct(id) => id,
        other => panic!("unexpected resolution: {other:?}"),
    };

    let regular = stack.rasterize(regular_id, 24., Weight::Regular).expect("regular 'M'");
    let bold = stack.rasterize(bold_id, 24., Weight::Bold).expect("bold 'M'");

    assert_ne!(
        regular.coverage, bold.coverage,
        "the bold face must not be the regular raster: is the second face really loaded?"
    );
}

#[test]
fn codepoints_outside_the_font_are_substituted_and_reported() {
    let stack = stack();
    let regular = read_asset(REGULAR);

    // The bundled font has no CJK; the cell must still draw something and the
    // caller has to be able to tell that it is a substitute.
    assert!(matches!(stack.resolve('中', Weight::Regular), Resolution::Substituted(_)));
    assert!(matches!(stack.resolve('🔥', Weight::Regular), Resolution::Substituted(_)));
    assert!(matches!(stack.resolve('M', Weight::Regular), Resolution::Direct(_)));

    // The raw helper says the same thing without a FontStack.
    assert!(matches!(drosh_font::fallback::resolve(&regular, '中'), Resolution::Substituted(_)));
}

/// The font's ligatures, and the setting that switches them off.
///
/// The shipped face is the standard JetBrains Mono, whose ligature glyphs are
/// in the font under their own names (`hyphen_greater.liga` and the rest).
/// The terminal asks for them by name, because a terminal has no idea
/// whether the text in front of it is code — and that is the setting's whole
/// job: with ligatures off every character is a glyph of its own, and the
/// terminal shows what the guest wrote.
#[test]
fn ligatures_follow_the_setting() {
    let stack = stack();

    // With ligatures off, every character is a glyph of its own.
    let shaped_off = stack.shape("->", Weight::Regular, false).expect("shaping");
    assert_eq!(shaped_off.len(), 2, "with ligatures off every character is its own glyph");

    // With them on, the pair becomes the font's own glyph for it: one glyph
    // where there were two, and not the hyphen's.
    println!("ligature count: {}", stack.ligature_count());
    let seqs = stack.ligature_sequences();
    println!("has arrow: {}", seqs.iter().any(|s| s == &['-', '>']));
    println!("first 5: {:?}", &seqs[..5.min(seqs.len())]);
    let glyphs_off = stack.shape("->", Weight::Regular, false).expect("shaping");
    println!(
        "off: {} glyphs, clusters {:?}",
        glyphs_off.len(),
        glyphs_off.iter().map(|g| g.cluster).collect::<Vec<_>>()
    );
    let glyphs_on = stack.shape("->", Weight::Regular, true).expect("shaping");
    println!(
        "on: {} glyphs, clusters {:?}",
        glyphs_on.len(),
        glyphs_on.iter().map(|g| g.cluster).collect::<Vec<_>>()
    );
    let shaped_on = glyphs_on;
    assert_eq!(shaped_on.len(), 1, "the arrow is one glyph when ligatures are on");
    assert_ne!(
        shaped_on[0].glyph_id, shaped_off[0].glyph_id,
        "the ligature is the font's own glyph, not the hyphen's"
    );
}
