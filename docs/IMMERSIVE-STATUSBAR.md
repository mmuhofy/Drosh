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

`TerminalView.onScrollPositionChanged: ((Int) -> Unit)?` — `doScroll` ve
`onScreenUpdated` içinden çağrılır. `searchHighlightOverlay` için konmuş callback
ile aynı desen. `TerminalManager` bunu `scrollTopRow: StateFlow<Int>` olarak
yayınlıyor ve Compose tek sayıyı topluyor.

`onTextChanged` **tetikleyici olarak kullanılmıyor**: yalnızca PTY'den yeni
çıktı geldiğinde çalışır, kullanıcı boş shell'de scroll ederken hiç
tetiklenmez — yani tam da gereken anda çalışmazdı.

Önceki sürümde bunun yanında `isAtLiveEdge` ve `hasScrolled` adlı iki akış daha
vardı ve üçü birbirine bakıyordu. Silindi: `mTopRow` bir tam sayı, `0` ile `< 0`
arasındaki sınır zaten keskin, ve üç ayrı sayı "hangi taraftayız" sorusuna üç
farklı cevap verebiliyordu.

### Kaydırma

`TerminalTopBar.kt` tek kaynak: `rememberPillRowOffset()` satırın `translationY`
'sini verir, `rememberTerminalTopPadding()` ızgaranın üst boşluğunu verir. İkisi
**aynı `tween`**'i kullanır — 220ms, yukarı giderken 70ms gecikmeli. İki ayrı
animasyon aynı süreye sahip olsa bile biri yeniden başladığı anda ayrışırlar.

### Izgara boşluğu da değişir

Sabit bir `CHROME_CLEARANCE` her iki durumda da yanlış: prompt'tayken (bar
gizliyken) bir status bar yüksekliği boşa gider, scrollback'teyken ilk satır
düğmelerin altında kalır. Prototipin `46px`'i tam olarak bu yüzden hatalıydı.

Sabit olan şey ** açıklık** (`CHROME_CLEARANCE`, 4dp); değişen şey satırın
**kayması**.

Bir sabit ızgara bir web sayfası değildir: yukarısı ve aşağısı arasında sınırlı
yer vardır. Yani canlı kenardan her geçişte satır sayısı değişir, PTY'ye
SIGWINCH gider ve shell prompt'unu yeniden çizer. Bunu ayakta tutan iki şey var,
ikisi de `TerminalView`'da:

- `updateSize` `mTopRow`'u **sıfırlamak yerine clamp** eder. Sıfırlamak,
  chrome'un kendi geçişinin kullanıcıyı en yeni çıktının başına atmasına yol
  açıyordu.
- `onScreenUpdated`, bir resize'dan sonra `RESIZE_GRACE_MILLIS` (400ms) içinde
  gelen çıktıyı shell'in cevabı sayar ve viewport'u canlı kenara çekmez.

### Dokunulmayacaklar

Terminalin ölçüsü, `imePadding`, `FlatKeyBar`, TUI yolundaki `padding(top = 0)`
farkı — hiçbiri bu özellikle ilgili değil, **ayrı bir iş**.

`BEHAVIOR_DEFAULT` korunuyor. Düğmeler ekranın en üstünde, tam da Android'in kenar
hareketinin olduğu yerde; `BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE` o hareketle
bantları geçici olarak *uygulamanın üstüne* getirip kendi zaman aşımına bırakıyor
du ve yavaş bir yukarı kaydırmada sistem, kullanıcının uzandığı satırın üstüne
defalarca status bar çizdi. "Flapping" budu.

### Artık bulanıklık örneklenmiyor

Düğmeler bir zamanlar terminalin üst şeridini örnekleyip bulanıklaştırıyordu.
Doğru boşlukla birlikte düğmeler iki durumda da çıktının **üstünde** kalmıyor —
arkalarında bulunacak bir şey kalmadı. `TerminalBackdrop.kt` silindi. Prototipin
`blur(20px) saturate(180%)` terimi bu yüzden artık ölü: geri gelmesi, düğmelerin
çıktının üstünde yüzmesi istenirse boşluğun geri alınması demek.

---

## 7. Ayarlar

`autoHideStatusBar: Boolean` → ayarların **Terminal** bölümünde bir anahtar.
Kapalıyken hiçbir şey değişmez: bar sabit kalır, düğmeler hiç hareket etmez.
Varsayılan açık.

---

## 8. Üst bar görsel dili (ayrı karar, aynen uygulandı)

Prototipteki tasarım beğenildi, uygulamaya aynen uygulandı:

- **Bar zemin yok** — şeffaf, sadece düğmeler
- **Düğmeler en üstte değil, biraz aşağıda** — böylece terminalin ilk satırı
  okunaklı kalıyor, düğmeler ilk satırın üstüne binmiyor
- **Düğmeler daha oval** — pill biçimi belirgin
- **Düğmeler arkadaki içeriğe göre blur** — aşağıdaki engele bak

Kaynak: `html/topbar_scroll_prototype.html`, `.pill`:

| CSS | Compose |
|---|---|
| `background: rgba(255,255,255,0.08)` | `PILL_FILL` |
| `border: 1px solid rgba(255,255,255,0.14)` | `PILL_BORDER` |
| `::before` üst yarı, `rgba(255,255,255,.14)` → şeffaf | `GlassSpecularHighlight()` |
| `box-shadow: 0 8px 20px rgba(0,0,0,.35)` | `Modifier.shadow(8.dp, ambientColor = Transparent)` |
| `border-radius: 999px` | `CircleShape` |
| `backdrop-filter: blur(20px) saturate(180%)` | **yok** — aşağıya bak |

Blur'ın neden olmadığı §6'nın sonunda. Terminalin kendi `terminalBgColor`'ı
düğmelerin arkasında ve o düz bir renk; bulanıklaştırılacak bir şey yok. Düğmeler
saydam yüzeyi, spekülar highlight'i ve yumuşak dış gölgesiyle duruyor.

---

## 9. Cihazda yapılacaklar

- **Ölü bölge** kararı: 0 / 24 / 80 px. Şimdilik 0.
- Padding değişimi bir resize demek; SIGWINCH sonrası shell'in prompt'u ne kadar
  görünür biçimde yeniden çizdiği cihazda bakılmalı.
- `RESIZE_GRACE_MILLIS` = 400ms: yavaş bir bağlantıda ya da `htop` gibi bir TUI
  scrollback'ine girildiğinde yeterli mi.
