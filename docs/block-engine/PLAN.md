---

## 12. Bilinmeyenler — Doğrulanması Gerek

| Madde | Doğrulama yöntemi |
|-------|-------------------|
| OSC 133 emission | Drosh PROMPT'un OSC 133 yayınlayıp yayınlamadığını test et. Gerekirse PROMPT string'e `set_prompt` hook ekle |
| TerminalBuffer.mLines erişim | Vendor kodda package-private. mLines public veya accessor gerekli olabilir |
| TrafficStats.getUidRxBytes PRoot'ta | PRoot altında network namespace izole olabilir. Test et |
| Compose `imePadding` + sticky input | Klavye açılınca LazyColumn davranışı. Edge case testleri |
| Running block auto-scroll | Kullanıcı yukarı scroll ederse otomatik scroll durmalı |

---

## 13. Açık Bırakılanlar (v2+)

- **CPU/RAM metrikleri** — PRoot child PID tracking ile (v2)
- **Komut tipi tespiti** (apt/git/curl heuristic) — v2
- **Pipe/redirection count** — v2
- **Output filter** (grep/highlight) — v2
- **Block-level search** — v2
- **Block drag-to-reorder** — v2
- **Persistence** (Room'a yaz) — v2
- **Multi-cursor / split-screen** — Phase 5
- **ANSI color render** (şu an stripped) — v2

---

## 14. Beklenen Sonuç

Bu plan uygulandığında:

- Kullanıcı Settings'ten "Block Mode" açarsa, terminal **kart tabanlı** görünüme geçer
- Her komut = bir kart, HUD header'da **exit / duration / network** gösterilir
- Output **aç/kapa** toggle ile kontrol edilir (binary, sayfalama yok)
- Network sadece **gerçek trafik olduğunda** görünür (↓/↑ delta > 0)
- Running sırasında **spinner + live elapsed time** + **animasyonlu border pulse**
- LazyColumn **auto-scroll** ile yeni block görünür
- **Compose-only input** sticky bottom'da, klavye ile uyumlu
- Classic mode (Block mode kapalıyken) **mevcut terminal** gibi davranır — aynı session/persistence
- In-memory MVP, **5000 satır scrollback limiti**

---

## 15. Sonraki Adım

Step 1 — `Block` + `BlockState` + `NetworkSample` model sınıfları yaz.
PLAN tamamlandı, implementasyon başlayabilir.
