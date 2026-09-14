# Session System — Architecture Plan
_Phase 2 — UI & Session System. Scope narrowed to TopBar modal switcher
(no Home screen, no groups, no search, no navigator)._

**Last updated**: 2026-07-28

---

## 1. Inspiration

**Primary reference**: github.com/RohitKushvaha01/ReTerminal
**File studied**: `core/main/src/main/java/com/rk/terminal/service/SessionService.kt`

ReTerminal's model is a **string-keyed session map**, not a positional
list. Sessions are identified by a `String id` (e.g. `"main"`). The
binder exposes:

  - `createSession(id, client, workingMode): TerminalSession` — spawns
    a PTY and adds it to a `HashMap<String, TerminalSession>`.
  - `getSession(id): TerminalSession?` — id-based lookup.
  - `terminateSession(id)` — kills + removes.
  - `sessionList: MutableStateMap<String, Int>` — observable snapshot
    of session ids + working mode (`0` = shell, `1` = Alpine PRoot).

It also runs as an Android **foreground service** with a notification
that lists the active sessions — the persistent-session requirement
from TODO §30.

**What we keep from the reference**:
  - String-id keyed session map (vs our positional `_sessions` list).
  - `sessionList` as a `StateMap<String, ...>` so the UI observes
    additions/removals without polling.
  - The `id` is opaque (ReTerminal uses `"main"` for the default;
    we use `UUID.randomUUID().toString()` for uniqueness, persisted in
    Room so the user sees stable ids across restarts).

**What we don't copy**:
  - Foreground service — TODO §30 marks it out of scope; current
    `TerminalManager` runs sessions in-process only.
  - `workingMode` int — we have a single shell (zsh) for now; the
    parameter is reserved for future SSH sessions.

---

## 2. Files & Modules

```
domain/session/          (pure Kotlin, already created)
  SessionSnapshot.kt
  SessionRepository.kt
  ObserveActiveSessionUseCase.kt

data/session/            (Room + DataStore, already created)
  SessionEntity.kt
  SessionDao.kt
  DroshDatabase.kt          — :data/local/
  DatabaseModule.kt        — :data/di/
  SessionRepositoryImpl.kt
  ObserveActiveSessionUseCaseImpl.kt
  SessionManagerAdapter.kt

terminal/                 (PTY process ownership — to adapt)
  TerminalManager.kt       — needs id-keyed map, createSession-by-id,
                             getSession(id), terminateSession(id)
```

---

## 3. Already-done

This plan is **mid-execution**. As of `34f854e` (CI green), the
following files exist and compile:

  - `:domain` — 3 interface/data files
  - `:data` — 7 files: Entity, Dao, Database, Module, RepositoryImpl,
    ActiveSessionImpl, SessionManagerAdapter
  - `:data` — `BindingsModule` updated with the two new domain bindings

`SessionManagerAdapter.start()` is currently a no-op on the snapshot
ticker side — it observes the active-session flow but does not yet
spawn PTYs for new sessions because `TerminalManager` doesn't accept a
session id yet.

---

## 4. Open work — `TerminalManager` adapt

**Goal**: make `TerminalManager.idKeyed` so `SessionManagerAdapter` can
call into it directly.

### 4.1 Add to `TerminalManager`

```kotlin
// New alongside the existing positional API.
private val _sessionById: MutableMap<String, Int> = mutableMapOf() // id → tab index

val sessionCount: Int get() = _sessions.size

/** Spawn a session tied to a persistent id (UUID) and return the new
 *  TerminalSession. Caller must persist the id (Room). */
fun createSessionForId(id: String, name: String): TerminalSession {
    val session = createNewSession()
    val newIndex = _sessions.size
    _sessions.add(session)
    _tabNames.add(name)
    _sessionById[id] = newIndex
    if (_activeTabIndex.value != newIndex) _activeTabIndex.value = newIndex
    terminalViewRef?.attachSession(session)
    return session
}

/** Lookup by persistent id (used by SessionManagerAdapter). */
fun getIndexForId(id: String): Int = _sessionById[id] ?: -1

/** Translate index → id and id → index when adapting older call sites. */
fun idForIndex(index: Int): String? = _sessionById.entries.firstOrNull { it.value == index }?.key
```

### 4.2 Keep `_sessions`/`_tabNames` positional storage

They stay so the existing `TerminalScreen` code that reads
`activeTabIndex`/`tabCount`/`currentSession` keeps working without
rewrite. The new id-keyed map is **adjacent**, not replacing.

### 4.3 Switch-by-id

```kotlin
fun switchSessionById(id: String) {
    val idx = getIndexForId(id)
    if (idx < 0) return
    switchTab(idx)
}
```

---

## 5. UI work — TopBar modal switcher (next commits)

### 5.1 TopBar changes (`TerminalTopBar.kt`)

Replace the static `"Drosh"` text with a clickable session-name
chip that opens the modal:

```
[session name ▼]              ⋮
```

Tap → opens `SessionSwitcherSheet`.

### 5.2 New composables (`app/ui/session/`)

```
SessionSwitcherSheet.kt     # full-screen modal (M3 ModalBottomSheet at FULL height)
SessionCard.kt              # one card in the HorizontalPager
SessionSwitcherViewModel.kt # consumes SessionRepository + ObserveActiveSessionUseCase
```

### 5.3 Card content

Each card:
  - Session name (large)
  - State chip (`Running` / `Closed`)
  - Last 20 lines of transcript (live snapshot from
    `SessionSnapshot.liveSnapshotLines`; falls back to
    `lastSnapshot` from Room when the session is closed).
  - Tap → `setActive(id)` → sheet closes.
  - Swipe (`HorizontalPager`) → peeks at adjacent cards.

### 5.4 Empty state

If `observeAll()` emits an empty list → render a centered "No
sessions" placeholder with a single `[+] New session` button.

---

## 6. Persistence rules

  - **Active session id**: `stringPreferencesKey("active_session_id")` in DataStore.
  - **Session metadata**: Room `sessions` table.
  - **Live snapshot**: in-memory only. On process exit → flush last
    snapshot to `sessions.last_snapshot` (deferred; current impl only
    persists snapshot on `SessionState.Closed` transition).

---

## 7. Snapshot strategy (deferred)

`SessionManagerAdapter.captureLiveSnapshot()` is currently a no-op.
The follow-up exposes a `TerminalManager.getActiveEmulator()` seam
so the adapter can poll `TerminalEmulator.getScreen().getTranscriptText()`
every 500ms and push the last 50 lines into
`SessionRepository.touch(...)`.

This is independent of the UI work; ships as its own commit when the
TerminalManager seam lands.

---

## 8. CI status

  - `ba8202f` — domain + data layer landed, CI red (Unresolved observeAllIds).
  - `78bd388` — fix attempt #1, CI red.
  - `34f854e` — fix attempt #2 (concrete inject), **CI green**.
