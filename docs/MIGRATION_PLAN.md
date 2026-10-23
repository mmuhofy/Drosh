# TerminalManager → Rust Engine Migration Plan

## Phases

### Phase 1: EngineSession tracking (foundation)
- Create `EngineDroshSession` wrapper ✓
- Add `engineSessions: MutableList<EngineDroshSession>` to TerminalManager
- Add `addEngineTabWithId()` and `removeEngineSession()` methods
- Wire EnginePane listener for exit/title/bell events
- **Goal**: Engine sessions are tracked alongside Termux sessions

### Phase 2: Session lifecycle
- Replace `createNewSession()` path with EnginePane-based creation
- Wire up `onSessionFinished` for EnginePane exit
- Wire up pid tracking (EngineSession exposes pid or Rust core does)
- `restartCurrentTab()` → recreate EnginePane
- `closeTab()` → release EnginePane
- `closeAll()`, `destroy()` → release all EnginePanes
- **Goal**: Engine sessions have full lifecycle management

### Phase 3: Feature migration
- Search → EngineSession.search() / searchTranscript()
- URL overlay → EngineSession.findUrls() / urlAt()
- Ambient tint → EnginePane.ambientTint()
- Block engine wire → EngineSession.transcript() / blockState()
- Mouse input → EnginePane
- Text selection → EnginePane.selectionText() / selectWord() / selectLine()
- **Goal**: All features work with Engine sessions

### Phase 4: UI integration
- Replace `paneViews: TerminalView` (Termux) with EnginePane.view
- Update `registerPaneView` / `unregisterPaneView` → EnginePane
- Update `bindSelectionMenu`, `applyTerminalBackground`, `applyCursorBlinkRate`
- Update `redrawPanesShowing`, `reattachPanesAt`, `resyncPanes`
- **Goal**: UI only uses EnginePane views

### Phase 5: Remove Termux session path
- Remove `irisSessions: MutableList<DroshSession>`
- Remove `sessionClient: TerminalSessionClientImpl`
- Remove `TerminalSession` imports
- Remove `onSessionFinished`, `onSessionPidChanged` (Termux callbacks)
- Remove `addTabWithId`, `addSshSession` Termux paths
- **Goal**: No Termux session references in TerminalManager

### Phase 6: Delete vendored Termux
- Delete `com/termux/terminal/` and `com/termux/view/`
- Remove JNI build targets
- Remove proguard rules
- Remove `TermuxTerminalWriter`
- Clean up imports across all modules
- **Goal**: Termux completely removed

## Current status
- Phase 1: In progress
- Phases 2-6: Not started
