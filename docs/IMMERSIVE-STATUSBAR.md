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

| Durum | `mTopRow` | Sistem status bar | Drosh düğmeleri | Izgara boşluğu |
|---|---|---|---|---|
| **Canlı kenar** — prompt'tayız, yeni oturum, hiç kaydırılmamış | `== 0` | **gizli** | **y=0**, status bar bandında | `statusBarH` |
| **Geçmişte** — scrollback'teyiz | `< 0` | **görünür** | `statusBarH + 10dp` | `statusBarH + 10 + 44 + 4` |
| TUI app açık | — | **gizli** (daima) | y=0 | `statusBarH` |

Kural:

```
chromeCollapsed = autoHideStatusBar && (tuiActive || chromeIsAtLiveEdge(topRow, was))
```

Yön önemli: **yazarken** saat aramazsın, **geçmişten okurken** ararsın. Bar tam da
gereksiz olduğu anda kaybolur. Önceki sürümde yön tersiydi ve belirtisi, prompt'un
üstünde sürekli duran bir saat oldu.

Yeni oturumun kendi bayrağı yok: `mTopRow == 0`'da zaten duruyor, pozisyon tek
başına doğru cevabı veriyor. `hasScrolled` bu yüzden silindi.

**Ölü bölge kaldı**: `-5`'te açılır, `-1`'de kapanır. Tek seferde bir yukarı bir
aşağı hareket, satır başına değil yön başına bir değişim demek.

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

Satırın `offset`'i collapsed'da **0**, expanded'da `statusBarH + 10dp`; ikisi de
220ms, yukarı giderken 70ms gecikmeli. Izgaranın üst boşluğu da **aynı tween**
üzerinde (`rememberTerminalTopPadding`), yoksa hareketin iki yarısı ayrışır.

### Izgara boşluğu neden sabit değil

Sabit bir açıklık her iki durumda da yanlış: prompt'tayken bir status bar
yüksekliği boşa gider, scrollback'teyken ilk satır düğmelerin altında kalır.

Sabit ızgara bir web sayfası değildir: yukarısı ve aşağısı arasında sınırlı yer
vardır. Yani canlı kenardan her geçişte satır sayısı değişir, PTY'ye SIGWINCH
gider, shell prompt'unu yeniden çizer. Ayakta tutan iki şey var:

- `updateSize` `mTopRow`'u sıfırlamak yerine **clamp** eder.
- `onScreenUpdated`, bir resize'dan sonra `RESIZE_GRACE_MILLIS` (400ms) içinde
  gelen çıktıyı shell'in cevabı sayar, viewport'u canlı kenara çekmez.

### Bant rengi

`DroshPalette.BACKGROUND_HEX` (`:core`) — tek kaynak. Ayarların varsayılanı ve
ekranın ilk kare tohumu da onu okur. Depoda `#000000`, ekranda `#0B0B0F` yazıyordu;
ikisi de ne uygulamanın arka planı ne de terminalin kendi varsayılanı. Hex
literal burada, palet ya da terminalin varsayılanı bir gün değiştiğinde bandın
geride kalacağı anlamına gelir.

### Dokunulmayacaklar

Terminalin ölçüsü, `imePadding`, `FlatKeyBar`, TUI yolundaki `padding(top = 0)`
farkı — hiçbiri bu özellikle ilgili değil, **ayrı bir iş**.

`BEHAVIOR_DEFAULT` korunuyor. Düğmeler ekranın en üstünde, tam da Android'in kenar
hareketinin olduğu yerde.

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
