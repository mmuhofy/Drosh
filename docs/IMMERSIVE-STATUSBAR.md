# Immersive Status Bar Sistemi

> Durum: **onaylandı, uygulanacak.** Muhofy ile netleştirildi.
> Prototip: `docs/statusbar-immersive-prototype.html` (tarayıcıda açılıp denenebilir)
> Karar tarihi: 2026-09-30

---

## 1. Ne yapıyor

Terminal canlı kenardayken (prompt görünürken) Android'in **sistem status bar'ı**
gizleniyor. Boşalan banda Drosh'un kendi üst bar düğmeleri geçiyor. Scrollback'e
girilince (geçmiş çıktı okunurken) sistem status bar'ı geri geliyor, düğmeler
normal yerine iniyor.

Terminoloji — bu ikisi karıştırılırsa konuşma bozuluyor:

| Terim | Ne |
|---|---|
| **Sistem status bar** | Android'e ait üst bant: saat, pil, bildirim ikonları |
| **Drosh üst bar** | `TerminalTopBar.kt`: `☰ [oturum adı] [agent] [klavye] ⋮` |

---

## 2. Davranış

| Durum | `mTopRow` | Sistem status bar | Drosh düğmeleri |
|---|---|---|---|
| Canlı kenar — prompt'tayız | `== 0` | **gizli** | yukarıda, status bar bandında |
| Geçmişte — scrollback'teyiz | `< 0` | **görünür** | normal yerinde |
| TUI app açık | — | **gizli** (daima) | yukarıda |

### Karartı yok

Üzerine **hiçbir şey çizilmiyor**. Scrim, gradyan, karartı — hiçbiri yok. Sistem
barı gerçekten gizleniyor, düğmeler boşalan banda `translateY` ile gidiyor. Bu
bir katmanlama değil, sadece yer değiştirme.

---

## 3. Neden bu yön

İlk tasarımda çubuk **geçmişe girerken** gizleniyordu. Bu yön ters çevrildi ve
tersi daha iyi:

Sen **yazarken** kontrol aramıyorsun — ama geçmişe kaydığında elinden çıkıyor.
Yeni yönde çubuk tam da ihtiyaç duyulmadığı anda kayboluyor. Kontrol asla tam da
gerektiği anda erişilemez olmuyor.

---

## 4. Ölü bölge

`mTopRow` tam sayı ve `0`'ın altında her negatif değer "geçmişteyiz" demek. Yani
sınır zaten keskin; ayrıca eşik gerekmiyor.

Ama son satırları okurken bir piksel oynanmasın diye ölü bölge opsiyonel:
prototipte 0 / 24 / 80 px seçeneği var. Karar cihazda verilecek.

---

## 5. TUI

TUI tam ekran çizer, kaydırılacak geçmiş yoktur. Bu yüzden her zaman canlı kenar
sayılır → status bar gizli kalır. Ekstra kod gerekmiyor, davranış kendiliğinden
doğru çıkıyor. Prototipte "TUI app açık" senaryosu bunu gösteriyor.

---

## 6. Teknik

### Scroll sinyali

Bugün **hiçbir şey** scroll pozisyonunu Compose'a taşımıyor. Gerekli olan:

- `TerminalView.kt` → `var onScrollPositionChanged: ((Int) -> Unit)?`
  `doScroll` (L568) ve `onScreenUpdated` (L466) içinden çağrılır.
  `searchHighlightOverlay` (L135) için konmuş callback ile **birebir aynı desen** —
  o dosya zaten yerel değişiklik taşıyor, fork riski düşük.
- `TerminalManager` → `MutableStateFlow<Int>`, `mTopRow`'u yayınlar.
  `onTextChanged` (L169-175) **tetikleyici olarak kullanılmaz**: o yalnızca PTY'den
  yeni çıktı geldiğinde çalışır, kullanıcı boş shell'de scroll ederken hiç
  tetiklenmez — yani tam da gereken anda çalışmazdı.

### Kaydırma

`TerminalScreen.kt:588` zaten bir `Box` overlay'i. `graphicsLayer { translationY }`
ile `-statusBarH` kadar kaydırılır, ~280ms. Repo'daki `sidebarPush` deseni.

### Dokunulmayacaklar

Terminalin ölçüsü değişmez. `imePadding`, `FlatKeyBar`, TUI yolundaki
`padding(top = 0)` farkı — hiçbiri bu özellikle ilgili değil, **ayrı bir iş**.

---

## 7. Ayarlar

`autoHideStatusBar: Boolean` → ayarların **Terminal** bölümüne bir anahtar.
Kapalıyken hiçbir şey değişmez, bar sabit kalır.

---

## 8. Üst bar görsel dili (ayrı karar)

Prototipteki tasarım beğenildi, uygulamaya aynen uygulanacak:

- **Bar zemin yok** — şeffaf, sadece düğmeler
- **Düğmeler en üstte değil, biraz aşağıda** — böylece terminalin ilk satırı
  okunaklı kalıyor, düğmeler ilk satırın üstüne binmiyor
- **Düğmeler daha oval** — pill biçimi belirgin
- **Düğmeler arkadaki içeriğe göre blur** — aşağıdaki engele bak
