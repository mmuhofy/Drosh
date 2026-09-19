# Drosh — TODO (Phase 6: Agent Bölümü)
_Bu dosya, TODO.md'nin sadece Phase 6 — Agent Intelligence bölümünü içerir. Diğer fazlar buraya dahil edilmemiştir._

_Son güncelleme: 2026-09-19_

---

## Çalışma Prensibi — Önce Plan, Sonra Kod

Aşağıdaki liste, **henüz kod yazılmamış, sadece planlanmış** maddeleri içerir. Agent sistemi tasarlanırken Claude Code, Codex ve Warp gibi mevcut ürünlerden ilham alınıyor — bu ürünlerin çözdüğü problemler inceleniyor, Drosh'un kendi mobil/Android kısıtlarına uyarlanıyor, birebir kopyalanmıyor. İmplementasyon, plan tamamen netleşip onaylandıktan sonra başlayacak.

---

## Phase 6 — Agent Intelligence
*Goal: Terminal becomes intelligent. The Warp moment.*

### Core Agent (Port from Iris Code)
- [ ] Port `AgentLoop.kt` → **AgentRuntime.kt** (naming approved)
- [ ] Port `MultiStepStreamer.kt`
- [ ] Port `OpenAiProviderAdapter.kt` → **ProviderAdapter.kt** (interface) + **GeminiAdapter.kt** (default, v1.0) + OpenAiAdapter.kt + AnthropicAdapter.kt
- [ ] **RESOLVED (2026-09-19):** Per-provider adapters, NOT OpenAI proxy. Gemini uses `google-genai` lib directly. See MEMORYBANK.md §3 and PHASE-6-ARCHITECTURE.md §4.
- [ ] Port API Vault — per provider key management
- [ ] Port `WebSearchTool.kt`
- [ ] Port `BashTool.kt`
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
- [ ] Tool: `bash` — PRoot subprocess
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
