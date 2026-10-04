# Drosh — TODO (Phase 6: Agent Bölümü + Native Editor)
_Bu dosya, TODO.md'nin sadece Phase 6 — Agent Intelligence ve Native Editor bölümlerini içerir. Diğer fazlar buraya dahil edilmemiştir._

_Son güncelleme: 2026-10-04_

---

## Çalışma Prensibi — Önce Plan, Sonra Kod

Aşağıdaki liste, **henüz kod yazılmamış, sadece planlanmış** maddeleri içerir. Agent sistemi tasarlanırken 10 agentic kodlama uygulamasının analizinden (OpenCode, Cline SDK, Codex, Aider, DeepSeek Harness, Qwen Code, Harness CLI, Claude Code, Kilo Code) ilham alınıyor — bu ürünlerin çözdüğü problemler inceleniyor, Drosh'un kendi mobil/Android kısıtlarına uyarlanıyor, birebir kopyalanmıyor. İmplementasyon, plan tamamen netleşip onaylandıktan sonra başlayacak.

---

## Native Editor
*Goal: `editor <path>` opens a real code editor. Branch `feat/editor`.*

### Shipped (2026-10-04)
- [x] `editor <path>` shell command → Drosh-private OSC 1339 → editor opens
- [x] `GuestFileRepository` (`:domain`) + `RootfsGuestFileRepository` (`:data`), rootfs-only
- [x] `:editor` module — sora-editor wrapper, Drosh colour scheme, ViewModel, screen
- [x] Route + Hilt binding + `CodeEditor.release()` lifecycle
- [x] Discard-changes confirmation on back when dirty

### Next
- [ ] **Syntax highlighting** — `language-textmate:0.24.3` is pinned and ready.
      Needs core-library desugaring (`java.time` below API 33) + bundled
      `*.tmLanguage.json` grammars. Colour scheme already sets the token keys.
- [ ] `Ctrl+S` / hardware-keyboard save (nothing binds save to a key yet)
- [ ] Undo/redo buttons in the header — sora has `canUndo()`/`canRedo()`, unused
- [ ] Decide the editor's place in the phase order — it is in no phase list
- [ ] **LGPL sign-off** — sora-editor is LGPL-2.1-or-later, Drosh is GPL-3.0.
      Must be confirmed before any F-Droid submission.

### Known unrelated bugs found while working here
- [ ] `ui/.../input/FlatKeyBar.kt:170` — the Backspace key emits
      `Navigation.HOME`. `ExtraKey.Navigation` has no `BACKSPACE` member at all.
- [ ] `TerminalView`'s IME mirror (`imeCursor`/`typedMirror`, 144 lines) was
      reverted by `72efca9` as "a wrong guess, made by reading code instead of
      observing the device" — but merge `d4013ec` resolved the conflict the other
      way and it is **live on main**. The revert's own reasoning still stands and
      nobody has re-decided it.

---

## Phase 6 — Agent Intelligence
*Goal: Terminal becomes intelligent. The Warp moment.*

### Core Agent (sıfırdan tasarla — 10 tool analizinden sonra)
- [ ] **AgentRuntime.kt** — bounded loop, step counter (OpenCode + Cline pattern)
- [ ] **MultiStepStreamer.kt** — StreamEvent → ToolCall accumulation
- [ ] **ProviderAdapter.kt** (interface) + **GeminiAdapter.kt** (default) + OpenAiAdapter.kt + AnthropicAdapter.kt
- [ ] **RESOLVED (2026-09-19):** Per-provider adapters, NOT OpenAI proxy. Gemini uses `google-genai` lib directly. See MEMORYBANK.md §3 and PHASE-6-ARCHITECTURE.md §4.
- [ ] API Vault — per provider key management
- [ ] **ShellTool.kt** (was BashTool) — wraps TerminalManager.executeCommand
- [ ] Work mode: PLAN / BUILD / AUTO

### Agent UI/Erişim (YENİ — v1.0 için netleşti)
- [ ] TopBar'da sabit 🤖 buton (birincil tetikleyici)
- [ ] Agent paneli — bottom sheet ("Model B"), terminal arkada blur/dim, yukarı sürükleyince tam ekran
- [ ] Panel içi mod tab'ı: "Bu Session'da" / "Agent Session'ında"
- [ ] Panelden agent'ın kendi terminaline direkt geçiş
- [ ] Arka plan göstergesi: 🤖 ikonunda badge/spinner (Iris açıkken)
- [ ] Terminal ↔ panel görünürlük ayrımı: mutasyon komutları hem panelde hem terminalde, salt-okuma sadece panelde
- [ ] **RESOLVED (2026-09-19):** Default mod = "Agent Session" (isolated PTY). Agent Session visible in Session Switcher as `🤖 <name>`. v1: tek görev, sıralı kuyruk. App fully dies → runtime killed, metadata persists. See MEMORYBANK.md §3.

### Tools
- [ ] Tool: `shell` — PRoot subprocess (TerminalManager.executeCommand)
- [ ] Tool: `read_file`
- [ ] Tool: `write_file` — diff + approve
- [ ] Tool: `ask_user`
- [ ] Tool: `update_todo`
- [ ] Tool: `web_search`

### Natural Language → Shell (v1 — öncelikli, Claude-Code-tarzı görev-ver akışı)
- [ ] Command generation from natural language
- [ ] Show command before execution
- [ ] User approval flow
- [ ] Error explanation + fix suggestion
- [ ] Basit görev → direkt çalışır; karmaşık görev → otomatik plan+onay (Autopilot davranışına döner, ayrı sistem değil)

### Command DNA
- [ ] `CommandDnaDao.kt` — Room FTS5
- [ ] Auto-index every command
- [ ] Natural language query across history
- [ ] Session Intelligence

### Error DNA + Proaktif Tetikleme (v2 — v1'den SONRA)
- [ ] Failure detection — exit code ≠ 0
- [ ] Agent diagnosis
- [ ] Fix suggestion
- [ ] Learned fixes in Room
- [ ] Proven fix on repeat error
- [ ] Blok-içi banner tetikleyici ("Build hatası tespit edildi, düzelteyim mi?") — tıklanınca panel açılır, prompt otomatik gönderilir (Warp "Active AI Recommendations" referansı)

### Output Intelligence
- [ ] npm, pip, gradle, cargo, apt, docker, git parsing
- [ ] Actionable cards from raw output
- [ ] Fix / Details action buttons

### Advanced Agent Features
- [ ] Iris Autopilot — multi-step task execution
- [ ] Agent Watch — WorkManager background conditions
- [ ] Natural Language Cron — WorkManager scheduler
- [ ] Terminal Lens — OCR → command

---

## Bilinen Borç — Agent (2026-10-04)

- [ ] **`AgentLoopTest.cancelling a parked run…` testini geri aç.**
      `AgentLoop.cancel` toplama coroutine'ini iptal ediyor; `runTest` bir
      çocuktaki yakalanmamış exception'ı test başarısızlığı sayıyor ve testi
      mesajsız bir `AssertionError` ile kendi şikâyeti olarak raporluyor.
      Gerçek davranış `PendingRequestsTest` içinde deterministik olarak test
      ediliyor (8 test). Dışarıdan gözlemleyen test `runBlocking` + açık job
      handle ile yeniden yazılmalı. İptal yolu yeniden ele alındığında yapılacak.
