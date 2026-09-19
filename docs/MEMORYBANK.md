# Drosh — Memory Bank (Agent Bölümü)
_Bu dosya, MEMORYBANK.md'nin sadece Agent Core bölümünü içerir. Brainstorm oturumunda Agent dışında konuşulan diğer konular (renk paleti, Block Mode, Git System, isim değişikliği vb.) buraya dahil edilmemiştir — onlar ayrı bir geçişte tam MEMORYBANK.md'ye işlenmelidir._

_Son güncelleme: 2026-09-19_

---

## Çalışma Prensibi — Önce Plan, Sonra Kod

**Agent sistemi için şu ana kadar hiç kod yazılmadı, yazılmayacak.** Bu aşamada yapılan şey tamamen **mimari/UX planlama** — gerçek implementasyon, plan netleştikten ve onaylandıktan sonra ayrı bir aşamada başlayacak.

Planlama yaparken referans alınan gerçek ürünler:
- **Claude Code** — görev-ver akışı, çok-adımlı tool-calling döngüsü, git worktree izolasyonu, cross-session messaging
- **Codex** — agent'ın kendi ortamında (sandbox/izole session) çalışması modeli
- **Warp** — proaktif tetikleme ("Active AI Recommendations": Prompt Suggestions, Next Command, Suggested Code Diffs), blok bazlı UI felsefesi

Bu ürünlerin **çözdüğü problemler ve bulduğu çözümler** inceleniyor, birebir kopyalanmıyor — Drosh'un kendi kısıtlarına (mobil ekran, Android işletim sistemi kuralları, PRoot/Ubuntu ortamı) uyarlanıyor. Her karar, "bu üründe nasıl çözülmüş, bizim için neden aynı/farklı olmalı" sorgusundan geçirilerek alınıyor.

---

## Agent Core

### Architecture
Sıfırdan tasarlanacak. MEMORYBANK.md'nin altındaki tüm mimari kararlar, 10 agentic kodlama uygulamasının analizinden (docs/PHASE-6-ARCHITECTURE.md) çıkarılmıştır; hiçbir port işlemi yapılmaz.

```
AgentRuntime (submission)
  └── Flow<AgentEvent> → UI
  └── Maps StreamEvent → AgentEvent
        ↓
MultiStepStreamer (multi-step engine)
  └── for (step in 1..MAX_STEPS)
  └── ProviderAdapter.stream() → SSE
  └── Tool execution inline
        ↓
ProviderAdapter (interface)
  └── GeminiAdapter (impl) — google-genai lib
  └── OpenAiAdapter (impl) — /chat/completions
  └── AnthropicAdapter (impl) — /messages
```

**RESOLVED (2026-09-19, architecture analysis session):**

1. **Provider abstraction: PER-PROVIDER ADAPTERS (NOT OpenAI proxy).** Each provider gets its own adapter (`GeminiAdapter`, `OpenAiAdapter`, `AnthropicAdapter`) that normalizes natively to `StreamEvent`. `google-genai` lib already in `gradle/libs.versions.toml`. OpenCode's `protocols/` pattern (7 adapters) is the reference — scales without lossy abstraction.

2. **Tool-calling normalizasyonu: adapter → StreamEvent.** Each `ProviderAdapter` converts provider-native tool-call format (OpenAI `tools` array, Anthropic `tool_use`, Gemini `functionDeclarations`) into normalized `StreamEvent.ToolCallStart` / `ToolInputDelta` / `ToolCallEnd`. `MultiStepStreamer` assembles partial tool input across delta chunks (OpenCode `ToolStream` pattern). ToolResult sealed class (`Success`/`Error`/`Cancelled`/`AwaitingApproval`) is provider-agnostic already.

3. **Streaming normalizasyonu: confirmed, adapter-owned.** Each adapter is solely responsible for producing `Flow<StreamEvent>`. The `AgentRuntime` loop consumes provider-agnostic stream events — no provider-specific logic in the loop.

See `docs/PHASE-6-ARCHITECTURE.md` §4 for full design.

### Access & Trigger
- **Birincil tetikleyici:** TopBar'da sabit 🤖 buton — transparan/flat stil, mevcut TopBar ikonlarıyla (⊟, ⌨️, ⋮) aynı görsel ağırlıkta, container'sız/şeffaf bar estetiğine uyacak şekilde öne çıkarılmadan.
- **İkincil/bonus tetikleyici:** İki-parmak üç kere dokunma — opsiyonel, keşfedilebilirlik sorunu olduğu için birincil değil, sadece "power user" kısayolu.
- **Reddedilen tetikleyiciler ve nedenleri:**
  - Üstten aşağı swipe (Obsidian tarzı) — Android status bar / bildirim çekmecesi ile çakışıyor; terminal scroll'un "en üstü" ile anlamsal çakışma var; Obsidian'ın kendi kullanıcıları bile bu pattern'i kırılgan buluyor (forum kanıtı: yanlışlıkla farklı bir aksiyona bağlanıp geri alınamadığı şikayetleri var).
  - Power tuşuna uzun basma — Android'de sistem tarafından ele geçirilmiş, üçüncü parti uygulamalar erişemiyor.
  - Prefix tetikleyici (`#`/`ai:` yazarak) — mobilde ekstra karakter yazma sürtünmesi nedeniyle reddedildi.
  - Mod anahtarı (input içinde $/🤖 toggle) — her seferinde "hangi moddayım" kontrolü gerektirdiği için reddedildi.

### Panel Yerleşimi — "Model B"
- **Bottom sheet** — terminal arkada blur/dim görünür kalır (gerçek blur burada kabul edilebilir çünkü arkaplan sabit/durağan bir dim katmanı, Extra Keys Bar'daki gibi sürekli değişen PTY çıktısı değil).
- Yukarı sürükleyince **tam ekrana** geçer.
- Aşağı sürükleyince veya dışarı dokununca kapanır; görev **arka planda devam edebilir**.
- Reddedilen alternatifler: ayrı tam ekran sekme (bağlam kaybı riski), terminal-içi tek akış/Model C (karışıklık riski — ama proaktif banner tetikleyicisinde kısmen bu hissi taşıyor, bkz. Proaktif Tetikleme).

### Arka Plan Davranışı
- Panel kapalıyken, Iris **açıkken**: 🤖 ikonunun üzerinde **badge/spinner** göstergesi.
- Iris **tamamen kapalıyken/arka plandayken**: **Notification** (§14 "Agent task completed" ile aynı mekanizma).
- **Kullanılmayan yollar:** Floating bubble (`SYSTEM_ALERT_WINDOW` izni gerektirir, güven kırıcı, F-Droid/gizlilik kullanıcı kitlesiyle çelişir), Picture-in-Picture (gereksiz karmaşıklık — "kullanıcı Iris dışındayken zaten terminali göremiyor" mantığıyla ikisi de elendi).

### Çalışma Modu — İki Mod
Agent paneli açıldığında **üstte bir tab** ile iki mod arasında seçim yapılır:

1. **"Bu Session'da"** — agent, kullanıcının o an baktığı terminal session'ının PTY'sini kullanır. Kullanıcı ile aynı ortamı paylaşırlar.
2. **"Agent Session'ında"** — agent kendi **izole, ayrı bir session/PTY** açar; kullanıcının aktif terminaline dokunmaz.

**Netleşen:**
- Mod seçimi panelin üstündeki **tab** üzerinden yapılır.
- Kullanıcı, agent panelinden **direkt bir tık ile agent'ın kendi terminaline** girebilmeli (Session Switcher'a gitmeden — panel içi hızlı erişim).

**RESOLVED (2026-09-19):**
- Mod seçimi: **Panel açıldığında** tab üzerinden yapılır. (Açıklık kaldırıldı.)
- Varsayılan mod: **"Agent Session"** (izole, güvenli).
- Agent Session Session Switcher'da görünür: **Evet**, `🤖 <session-name>` olarak.
- Paralel görev: **v1 için Hayır** — tek görev, sırayla.
- Yaşam döngüsü: App fully dies → runtime killed, **metadata persists**.

**Naming convention (2026-09-19, approved):** `AgentRuntime`, `AgentOrchestrator`, `GeminiAdapter`, `OpenAiAdapter`, `AnthropicAdapter`, `MultiStepStreamer`, `ToolRegistry`. NOT irisCode-style `OpenAiProviderAdapter`.

### Tool Set (v1.0)
| Tool | Description | Mode |
|------|-------------|------|
| `bash` | Execute shell command via PRoot | BUILD |
| `read_file` | Read file into agent context | PLAN + BUILD |
| `write_file` | Write file, diff+approve flow | BUILD |
| `ask_user` | Ask user a question | PLAN + BUILD |
| `update_todo` | Create/update TodoCard | PLAN + BUILD |
| `web_search` | Tavily API search | PLAN + BUILD |

### Work Mode
| Mode | Behavior |
|------|----------|
| PLAN | Read-only. Agent suggests only. |
| BUILD | Full tool use. Diff/approve active. |
| AUTO | BUILD + autonomy toggles forced ON. |

Not: Work Mode (yetki seviyesi ekseni) ile Çalışma Şekli (görev-ver/autopilot ekseni, aşağıda) birbirine dik — bağımsız kararlar.

### Çalışma Şekli — Görev-ver ve Autopilot Aynı Motor
- **"Görev ver" (chat) ve "Autopilot" (plan+onay) ayrı sistemler değil, aynı motorun iki görünümü:**
  - Basit/tek-adımlı görev → agent direkt çalışır.
  - Karmaşık/çok-adımlı görev → agent otomatik olarak plan gösterip onay ister, onaylanınca adım adım ilerler (Autopilot davranışına döner).
- Bu motor zaten mevcut mimaride var: `MultiStepStreamer` (`for step in 1..MAX_STEPS`, `ProviderAdapter.stream()` → tool execution inline → sonraki adım).
- PLAN/BUILD/AUTO mod ayrımı bu akışın üstünde, yetki seviyesi ekseni olarak çalışmaya devam ediyor — çalışma şekli (görev-ver/autopilot) ekseni buna **dik**, birbirini etkilemiyor.

**Öncelik sırası:** v1 = görev-ver akışı (Claude Code tarzı, temel akış). Proaktif tetikleme (aşağıya bakın) v1'den SONRAKİ fazda.

### Proaktif Tetikleme (v2 — sonraki faz, v1'den SONRA)
- Error DNA / Output Intelligence, bir hata/anormal durum tespit edince **blok-içi banner** gösterir: "🤖 Build hatası tespit edildi, düzelteyim mi?"
- Tıklanınca: panel otomatik açılır, **prompt otomatik gönderilir** (hata çıktısı zaten context'te), varsayılan olarak **"Bu Session"** modunda başlar (çünkü hata zaten o session'da oluştu).
- Referans model: Warp'ın "Active AI Recommendations" özelliği (doğrulanmış — Prompt Suggestions, Next Command, Suggested Code Diffs alt-parçalarından oluşuyor, hepsi Settings'te ayrı ayrı kapatılabiliyor).
- Genel Banner mekanizmasının "Agent görevleri" kategorisi bu tetikleyiciyi kapsar.

### Agent in Terminal Context
Agent operates in the same shell environment as the user. Same PRoot Ubuntu session. Agent can:
- Read current directory
- Execute commands
- Observe output
- Chain commands across steps

**Terminal ↔ Panel görünürlük:**
- **Mutasyon komutları** (write/install/commit/push, kalıcı değişiklik yapanlar) → hem panelde hem (Bu Session modundaysa) gerçek terminalde görünür, Command DNA'ya girer.
- **Salt-okuma komutları** (ls/cat/grep, ara adımlar) → sadece panelde kalır, kullanıcının terminal geçmişini kirletmez.
- Teknik not: `IrisTool`/bash tool implementasyonunda basit bir mutasyon-fiili heuristic'i (`write`, `install`, `commit`, `push`, `rm`, `mv` gibi anahtar kelimeler) veya açık bir `visibleInTerminal: Boolean` flag'i ile ayrım yapılabilir — LLM'e bu kararı verdirmeye gerek yok, tool seviyesinde otomatik sınıflandırılabilir.

### Ghost Text — Agent'tan Bağımsız
- Ghost Text Engine **şimdilik mevcut planıyla (history-bazlı, basit inline tamamlama) kalıyor**, agent'a bağlanmıyor.
- VSCode/Fig tarzı dropdown öneri listesi fikri konuşuldu (argüman-farkında, CLI sözlüğü tabanlı) ama bu **Agent'tan ayrı bir özellik**, kapsamı (sadece history / +CLI sözlüğü / +argüman-farkında) henüz kararlaştırılmadı, Agent sisteminin bir parçası değil.

### Kaynak / Emsal Araştırması (bu oturumda doğrulanmış bulgular)
- **Claude Code** — git worktree izolasyonu ile paralel agent çalıştırma artık native destekleniyor; cross-session messaging (agent'lar birbirine mesaj atabiliyor) mevcut.
- **Warp** — "Active AI Recommendations" (Prompt Suggestions, Next Command, Suggested Code Diffs) proaktif tetikleme modelinin doğrudan referansı; Settings'te kategori bazlı aç/kapa; input auto-detection (komut mu agent isteği mi) ile tek input alanında ikisini ayırt ediyor (Drosh mobilde bunun yerine ayrı buton kullanmayı tercih etti).
- **Fig / Amazon Q CLI** — argüman-farkında dropdown autocomplete modeli (Ghost Text'in olası gelecek genişlemesi, ama şu an kapsam dışı).

---

_"AÇIK KALAN" işaretli maddeler, bir sonraki brainstorm oturumunda ele alınmalı._
