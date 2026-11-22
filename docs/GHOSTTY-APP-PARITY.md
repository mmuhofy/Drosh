# Ghostty app-layer parity — preparation checklist

Status: preparation. This is a working list, not a claim of parity. Each item
must be verified against Ghostty's current source before it is marked done.

Legend: `[x]` present · `[~]` partial · `[ ]` absent · `[?]` not yet checked.

## Surface and rendering
- [~] OpenGL/Metal-class renderer with instanced cells
- [~] Dirty-row incremental rebuild
- [~] Glyph atlas with R8 masks
- [~] Color/emoji atlas
- [ ] Ligature shaping connected to renderer
- [~] Kitty graphics
- [~] Background images
- [~] Cursor styles (block/bar/underline, hollow)
- [~] Visual bell

## Session and window model
- [~] Tabs
- [~] Split panes
- [~] Floating terminal windows
- [?] Session restore after process death
- [?] Multiple windows / quick terminal
- [?] Command palette

## Input and interaction
- [x] Mouse reporting
- [x] Selection (word/line/all, drag handles)
- [x] Search in scrollback
- [x] URL detection
- [x] Pinch-to-zoom
- [x] Sub-line scroll with fling
- [ ] Ghost text / autocomplete
- [ ] Semantic output colouring

## Configuration and theming
- [x] TOML themes
- [x] TOML settings
- [~] Installable themes/background images
- [?] Live config reload
- [?] Keybinding customization

## Shell integration
- [x] OSC 133 prompt/command/exit status
- [x] OSC 7 cwd
- [x] OSC 1339 editor
- [x] Block-based output
- [ ] Command DNA / history search

## Diagnostics
- [x] Render benchmark counters
- [x] Diagnostics panel
- [~] Frame timing percentiles
- [ ] Automated render regression suite

## Next action
Build the parity matrix from Ghostty source, then convert the `[ ]` and `[~]`
items into prioritized implementation tickets. Renderer-related items should be
tackled only after R1 benchmark/test coverage is in place.
