# Drosh — TODO (Phase 6: Agent Bölümü + Native Editor + Workspace)
_Bu dosya, TODO.md'nin sadece Phase 6 — Agent Intelligence, Native Editor ve Workspace bölümlerini içerir. Diğer fazlar buraya dahil edilmemiştir._

_Son güncelleme: 2026-10-05_

---

## Çalışma Prensibi — Önce Plan, Sonra Kod

Aşağıdaki liste, **henüz kod yazılmamış, sadece planlanmış** maddeleri içerir. Agent sistemi tasarlanırken 10 agentic kodlama uygulamasının analizinden (OpenCode, Cline SDK, Codex, Aider, DeepSeek Harness, Qwen Code, Harness CLI, Claude Code, Kilo Code) ilham alınıyor — bu ürünlerin çözdüğü problemler inceleniyor, Drosh'un kendi mobil/Android kısıtlarına uyarlanıyor, birebir kopyalanmıyor. İmplementasyon, plan tamamen netleşip onaylandıktan sonra başlayacak.

---

## Workspace / Proje Sistemi
*Goal: gruplama + kalıcı metadata. Session'lar ve process'ler kalıcı değil. Branch `feat/workspace` → `feat/workspace-ux`.*
*Şartname: Muhofy, 2026-10-05 — "sadece gruplama, kalıcı metadata olacak sessionlar processler kalıcı olmucak"*

### Shipped (2026-10-05) — `feat/workspace`, main'e #24 ile girdi
- [x] `domain/workspace/` — `Workspace`, `WorkspaceEdit`, `WorkspaceRepository`,
      `WorkspaceEdit.forStorage()`, `WorkspacePath`, `WorkspaceGrouping`
- [x] `data/workspace/` — `WorkspaceEntity`, `WorkspaceDao`, `WorkspaceRepositoryImpl`
- [x] `sessions.workspace_id` — nullable FK, `ON DELETE SET NULL`, indexed
- [x] `DroshDatabase` 2 → 3 + `MIGRATION_2_3` (sessions tablosu rebuild: SQLite
      mevcut tabloya constraint ekleyemiyor)
- [x] `SessionRepository.assignToWorkspace` — id'yi yazmadan önce doğrular
- [x] `WorkspaceScreen` + `WorkspaceViewModel` + edit/assign sheet'leri
- [x] Erişim: sidebar → Projects, Settings → Projects
- [x] `:domain:test` — `WorkspacePathTest`, `WorkspaceEditTest`, `WorkspaceGroupingTest`
- [x] MEMORYBANK §7B

### Shipped (2026-10-05) — `feat/workspace-ux`
Gruplama çalışıyordu; çevresi çalışmıyordu.

- [x] **Projede session açma** — ekranın eksik ana eylemiydi.
      `SessionRepository.create(name, workspaceId)` çağrılmayan kod olarak
      gelmişti. Bu olmadan her yeni proje bir çıkmaz sokaktı: sidebar'da session
      aç, geri dön, uzun bas, projeyi seç. Üç ekran.
- [x] **İki ayrı boş durum** — eskisi yalnızca iki liste de boşken tetikleniyordu,
      yani uygulamanın varsayılan hali (launch `Default` session açıyor, proje
      yok) hiçbir şey söylemeyen bir liste olarak çiziliyordu.
- [x] **Session'ı taşımak görünür düğme** — uzun bas tek yoldu ve satırda hiçbir
      ipucu yoktu. Jest hâlâ duruyor, düğme onu bulunur kılıyor.
- [x] **Arşivleme artık kullanılabilir** — `archived`, `observeArchived()`,
      `setArchived()` sıfır çağıranıyla gelmişti. Altta arşiv bölümü, geri al,
      edit sheet'te arşivle/arzivden çıkar. **Sil'den üstte**, çünkü geri
      alınabilir olan geri alınamayanın yanında durmamalı.
- [x] Arşiv satırı, o eylemin sonucunu söyler: "3 session projeler arasında".
      Gruplama arşivlemede kaybolmaz, sadece gösterilmez; sayıyı söylemek
      kullanıcının bunu kendi çıkarmasından ucuz.
- [x] Top bar'daki `+` kaldırıldı — alt bar ile aynı eylem, `+` telefonun ucunda.
- [x] Accent kart çerçevesi yerine sol kenarda şerit: çerçeve seçim durumu gibi
      okunuyor, 10dp nokta ise karanlık ekranda kayboluyordu.
- [x] Açılma durumu dönmeye ve terminale gidip gelmeye dayanıyor (`rememberSaveable`).
- [x] **`rememberSaveable` tuzağı:** açık set `Set` değil `List` — `Set`'in
      Bundle uyumlu `Saver`'ı yok, derlemede değil ilk döndürmede patlardı.
- [x] Atama sheet'i çıkmaz sokak değil: proje yoksa "Yeni proje oluştur" satırı.
- [x] Arşiv/grouping testi uçtan uca: proje listeden çıkar, session'ları kaybolmadan
      projeler arasına düşer.

### Explicitly NOT in v1 (karar, eksiklik değil)
- [~] **Workspace process tutmaz.** PTY yok, shell ayakta tutulmuyor, hiçbir şey
      geri açılmıyor. Bu yüzden ekranda "çalışıyor" rozeti yok — yalnızca
      "bitti" / "aktif" var. Kalıcı `SessionState.Running` bir canlılık raporu
      değil, resume işaretidir.
- [~] **`rootPath` bir etiket, çalışma dizini değil.** Hiçbir yerde `cd` yapılmıyor.
- [~] **Projeye özel shortcut yok** — MEMORYBANK §9'da deferred, şema öngörmüyor.
- [~] **Workflow builder yok** — v1.1+.
- [~] **Agent chat'ler gruplanmıyor** — §9 sadece session diyor; ayrı ürün kararı.

### Next
- [ ] **`MIGRATION_2_3` gerçek v2 kurulumda denenmedi.** `data/schemas/` bu
      projede hiç commit edilmediği için Room'un `MigrationTestHelper`'ı için v2
      fixture yok ve derleme zamanında şema karşılaştırması da yapılmıyor — bu
      SQL'i yakalayabilecek iki kontrolün ikisi de o dosyalara bağlı. SQL entity
      şekline göre elle yazıldı ve Room sonucu cihazda açılışta doğruluyor, yani
      hata sessiz bozulma değil açılış çökmesi olur. **Denediğin yer: eski build
      çalıştırmış bir cihaz.** Yeni kurulum v3'ü doğrudan açar, migration'ı
      hiç çalıştırmaz.
- [ ] **`data/schemas/`'ı commit etmeye başla.** `exportSchema = true` zaten açık
      ama dizin yok; `DroshDatabase` notu "CI runs exportSchemaDebug" diyor,
      workflow'da böyle bir adım da yok. Şemalar commit edilirse migration'lar
      derleme zamanında doğrulanır.
- [ ] **Arşiv ekranı.** ~~`archived` kolonu ve `observeArchived()` var ama kimse
      çağırmıyor~~ — **çözüldü**, `feat/workspace-ux`. Arşiv bölümü, geri al ve
      arşivle/arzivden çıkar eylemleri var. Kalan: arşivdeki projeye de session
      eklenebilsin (şu an sadece geri al / düzenle / sil).
- [x] ~~**Projeye yeni session**~~ — **çözüldü**, `WorkspaceViewModel.createSessionIn`.
      `SessionRepository.create(name, workspaceId)` artık çağrılıyor.
- [ ] **Session silerken projeyi de silme teklifi** — "bu session projeye ait,
      projeyi de sil?" onayı.
- [ ] **Projeyi terminalde aç** — `cd <rootPath>` yerine doğrudan bir yeni
      session. Root path etiket olduğu için bu ayrı bir karar.
- [ ] **Arama/filtre** — session listesi aranıyor, proje listesi aranmıyor.
      *Bilerek ertelendi: sidebar zaten tüm session'lar üzerinde arama yapıyor ve
      proje listesi ondan çok daha kısa. İkinci bir arama kopyalanan arama olur.*
- [ ] **Sürükle-bırak sıralama** — projeler `lastOpenedAtMs` ile sıralı,
      kullanıcı sırası yok.
- [ ] **Agent chat'lerini gruplama** — `working_directory` zaten var, ürün kararı bekliyor.

### Bilinçli olarak yapılmayan
- [ ] ~~Workspace oturumları kalıcı yapsın~~ — Muhofy's spec'i bunu açıkça
      reddediyor. Bir process'in ömrü app'in ömrü; proje onu taşımıyor.

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

---

## Split Panes
*Goal: two terminals side by side, or one floating over the other. Branch `feat/split-pane-panes`, PR #19.*

### Shipped (2026-10-05)
- [x] `PaneSlot` / `PaneLayout` (`:domain`) — two panes only, fractions not pixels, clamps in the model
- [x] `PaneLayoutRepository` + DataStore impl — survives rotation and process death
- [x] `TerminalManager`: a `TerminalView` per pane, focus as the active session, per-pane alt-buffer / selection / scroll
- [x] Sidebar drag grip (threshold-armed, disarmable) + "Split right" menu item
- [x] Draggable divider, 24dp target, 2dp drawn, accent only while dragged
- [x] Floating window: title-bar drag, corner resize, expand to fill, dock back
- [x] Overflow menu: "Float window" / "Dock pane" / "Close second pane", shown only when split
- [x] A pane whose session ends is reconciled away

### Corrected (PR #27)
- [x] **Top-to-bottom, not side by side.** Two 180dp columns are ~10 characters wide.
- [x] **Divider snaps to five positions**, 20% apart, plus "Move divider" in the overflow.
- [x] **Divider restyled** — 1dp hairline seam with a rounded pill on it, widening while held.
- [x] **The key bar no longer covers the lower pane.** `fillMaxSize()` → `weight(1f)`; the host
      was taking the whole Column and pushing each pane's own key bar off the bottom edge.
- [x] **Sidebar names both halves** with the split glyph between them, ellipsised from the middle.
- [x] **"Open in window" in the row menu** — a floating window is one tap, not split-then-toggle.

### Not done, deliberately
- [ ] **Block mode does not split.** One `BlockEngineWire`, one `BlockRepository`. Per-pane block
      history means the wire's transcript-diff anchor and the repository's per-session store both
      become keyed by pane, and `BlockEngineViewModel` becomes per-pane. Bigger than it reads —
      not smuggled into this change.
- [ ] No "swap panes". Focus follows a tap, but there is no gesture to trade the two sessions.
- [ ] No "reset layout" for a floating pane dragged into a corner it cannot be dragged out of.
- [ ] The floating window's expand button has no collapse affordance in the title bar while
      maximized — you restore from the same button, which is correct but undiscoverable.
