# AGENT.md — Drosh Geliştirme Kuralları

_Bu dosya, Drosh kod tabanı üzerinde doğrudan çalışan (dosya okuma/yazma, komut çalıştırma, tam erişim) coding agent için yazılmıştır. Agent, Muhofy'nin talimatlarıyla kod tabanını doğrudan değiştirir — bu bir "dosya paylaş, ben bakayım" sohbeti değildir. MEMORYBANK.md ve TODO.md projenin durumunu anlatır; bu dosya agent'ın **çalışma tarzını, referans aldığı kaynakları ve önceliklerini** anlatır._

---

## 1. Temel Prensip — Önce Plan, Sonra Kod

Hiçbir özellik, önce **mimari/UX tartışması** yapılmadan koda geçmez. Brainstorm/planlama oturumları ile implementasyon oturumları ayrı tutulur:

- Planlama oturumunda: sorular sorulur, alternatifler tartışılır, kararlar MEMORYBANK.md'ye/TODO.md'ye işlenir. **Kod yazılmaz.**
- İmplementasyon oturumunda: sadece MEMORYBANK.md'de zaten karara bağlanmış, "AÇIK KALAN" işareti taşımayan maddeler koda dökülür.
- Bir madde MEMORYBANK.md'de "AÇIK KALAN" / "OPEN" olarak işaretliyse, agent o maddeyi **varsayım yaparak** implemente etmez — kullanıcıya sorar veya oturumu planlama moduna döndürür.

## 2. İlham Kaynakları — Kopyalama Değil, Uyarlama

Drosh'un UX/mimari kararları alınırken şu ürünler **referans** olarak inceleniyor:

| Kaynak | Neyi inceliyoruz |
|---|---|
| **Claude Code** | Görev-ver akışı, çok-adımlı tool-calling döngüsü, git worktree izolasyonu, cross-session messaging |
| **Codex** | Agent'ın kendi izole ortamında (sandbox/session) çalışma modeli |
| **Warp** | Proaktif tetikleme ("Active AI Recommendations"), blok bazlı terminal UI felsefesi, block divider/compact mode yaklaşımı |
| **Termux** (`termux/termux-app`) | PTY/terminal emülatör referansı — Drosh'un mevcut terminal katmanının kökeni |

**Kural:** Bu ürünlerden hiçbiri birebir kopyalanmaz. Her biri için sorulan soru şu: *"Bu ürün bu problemi nasıl çözmüş, Drosh'un kendi kısıtları (mobil ekran boyutu, Android işletim sistemi API'leri, PRoot/Ubuntu sanal ortamı, F-Droid/gizlilik-odaklı kullanıcı kitlesi) altında bu çözüm ne kadar uygulanabilir, neyi değiştirmemiz gerekir?"*

Bir karar "X uygulaması böyle yapıyor" diye gerekçelendirildiğinde, agent bunu **tek başına yeterli gerekçe kabul etmez** — masaüstü/farklı platform kısıtları farklı olabileceği için, kararın Drosh'un mobil/Android bağlamında da işe yarayıp yaramayacağı ayrıca sorgulanır (örnek: Obsidian'ın swipe-down gesture'ı incelendi, Android sistem gesture çakışması ve terminal-scroll çakışması nedeniyle reddedildi — sadece "başka uygulama yapıyor" diye kabul edilmedi).

## 3. Kanıt Olmadan Karar Yok

- Bir dış üründe "böyle çalışıyor" denildiğinde, mümkünse **web_search ile doğrulanır** — hafızadan/tahminden konuşulmaz.
- Kod tabanındaki bir dosyanın/API'nin nasıl çalıştığı hakkında konuşulacaksa veya değiştirilecekse, önce o dosya **gerçekten okunur** (agent'ın kendi dosya sistemi erişimiyle) — hafızada kalan eski bir görüntüye veya tahmine göre değişiklik yapılmaz. Bir dosya son okunduğundan beri değişmiş olabilir; emin olunmayan durumda tekrar okunur.
- MEMORYBANK.md'deki bir bilgi ile kod tabanındaki gerçek durum arasında çelişki bulunursa (örnek: MEMORYBANK'ta yazan renk paleti ile gerçek `DroshColors.kt` arasındaki fark), bu açıkça belirtilir, hangisinin güncel/doğru olduğu netleştirilir — sessizce biri diğerine göre varsayılmaz, MEMORYBANK güncel değilse düzeltilir.

## 4. Anti-Halüsinasyon

- API isimleri, method signature'ları, kütüphane sürüm uyumlulukları **uydurulmaz**. Emin olunmayan bir şey için "bunu doğrulamam gerekiyor" denir.
- Jetpack Compose / Android API'leri referans verilirken, hangi sürümde konuşulduğu belirtilir; o sürümde var olup olmadığından emin değilse bu açıkça söylenir.
- Test edilmemiş, karmaşık kod bloklarına `// UNTESTED — verify before use` gibi bir uyarı düşülür (implementasyon aşamasında).

## 5. Faz Disiplini

Drosh'un gelişimi fazlara bölünmüş (Terminal Core → UI/Session → Input → SSH → Safety/Polish → Agent Intelligence). Bir faz tamamlanmadan bir sonraki fazın özellikleri **koda dökülmez** — ama **planlama/brainstorm** seviyesinde ileri fazlar hakkında konuşmak serbesttir (nitekim Agent Intelligence hâlâ Phase 6 olmasına rağmen bu dosyanın kendisi bir planlama ürünüdür).

## 6. Port Edilen Kod — Dokunulmaz Bölgeler

Termux'tan (`com/termux/terminal/*`, `com/termux/view/*`) doğrudan portlanmış dosyalara isim/paket değişikliği dışında müdahale edilmez — upstream ile diff alınabilirliği korunur. Bu dosyalarda "Iris"/"Drosh" markalaması yapılmaz.

## 7. Karar Kayıt Disiplini

Bir planlama oturumunda alınan her karar, oturum bitmeden **MEMORYBANK.md ve/veya TODO.md'ye yazılır** — sohbet geçmişinde kalıp dosyalara işlenmeyen kararlar, bir sonraki oturumda kaybolmuş sayılır. Kullanıcı "şimdi dokümana geçelim" dediğinde:
- Sadece o oturumda konuşulan konu MEMORYBANK.md'ye eklenir, ilgisiz diğer konular karıştırılmaz.
- Netleşmemiş noktalar "AÇIK KALAN" / "OPEN" olarak açıkça işaretlenir, gizlenmez veya varsayılan bir değerle doldurulmaz.
