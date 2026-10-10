# Prototypes

HTML prototypes and mockups built while deciding how something should look or
behave. Nothing here is source — each file stands alone and opens in a browser.
The code carries the decision once it has shipped; a prototype stays as
evidence for the decision, marked below.

| File | Decides | Status |
|------|---------|--------|
| `agent-ui/` | six concepts (A–F) for where the agent surface lives | superseded — agent home is shipped; concepts are the design lineage |
| `agent-chips.html` | Agent Home, scrollable chips | superseded — agent home is shipped |
| `agent-topbar.html` | compressed top bar | evidence — feedback loop with `TerminalTopBar` |
| `agent-ui.html` | agent UI revision mockup | superseded — agent screens are shipped |
| `pinch-zoom-prototype.html` | four zoom models compared with a live fps/reflow counter | evidence — 0.1sp continuous chosen, MEMORYBANK §7C |
| `tool-rows.html` | gradient sweep + pills | evidence — agent pills/rows/cards |
| `topbar_scroll_prototype.html` | top bar scroll behaviour | evidence — feedback loop with `TerminalScreen` |
| `block_mode_reference-1.html` | block mode rendering reference | superseded — block engine is shipped |
| `drosh_keybar_flat_mockup.html` | flat key bar with blur behind | inspiration for `FlatKeyBar` (cited in code) |
| `drosh_settings_pure.html` | settings layout | superseded — settings screen is shipped |
| `palette-alternatives.html` | alternative colour palettes | superseded — palette lives in `design-system` |
| `palette-preview.html` | colour palette preview | superseded — same |
| `settings-mockup.html` | settings mockup | superseded — settings screen is shipped |
| `statusbar-immersive-prototype.html` | immersive status bar behaviour | superseded — the feature was removed entirely, `docs/IMMERSIVE-STATUSBAR.md` |

`agent-ui/agent-ui.css` is shared by the concept pages; the rest of the files
are self-contained.
