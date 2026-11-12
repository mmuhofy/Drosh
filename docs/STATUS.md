# Drosh — Durum

_Bu dosya uygulamanın şu anki halini anlatır: ne çalışıyor, ne doğrulandı, ne eksik.
Geçmiş kararların kaydı `docs/archive/` altında._

---

## Ne var

Drosh, Android üzerinde çalışan bir terminal uygulaması. Linux kullanıcı alanı
(PRoot ile ARM64 Ubuntu), oturumlar, bir ızgara, ayarlar, bir ajan (yapay zekâ)
tarafı ve bir SSH yüzeyi var.

**Terminal motoru: Ghostty v1.4.0'ın `libghostty-vt`'si.** VT ayrıştırıcı,
ızgara/scrollback, alternatif ekran, SGR, DEC modları, OSC ve kitty klavye
protokolü motordadır. Drosh'un kendi yazdığı iki parça vardır:

- `native/` — Rust'un JNI yarısı: motoru sarar, kareleri üretir, tuşları ve
  fare olaylarını kodlar, seçimi ve aramayı sorar.
- `android/terminal/.../engine/render/` — OpenGL ES 3.0 renderer'ı: hücre
  arka planları, glif atlası, cursor.

---

## Doğrulananlar (cihazda görüldü)

- Terminal, shell, PTY, oturum açma/kapatma, oturum ızgarası, ön izlemeler.
- Metin hizası: glif, ait olduğu hücrenin üst kenarındadır; renkli blokların
  içinde durur, kutu çizimi karakterleri dikişsiz döşenir.
- Cursor, shell'in imleç hücresindedir; blok/çubuk/alt çizgi/boş kare
  biçimleri, yanıp sönme ve odak dışı görünümü.
- Kaydırma: sürükleme, fling (momentum), alt satır kayması, canlı kenara
  dönüş, yazarken canlı kenara dönüş.
- Pinch ile yazı boyutu; boyut değişince ızgara yeniden dizilir.
- Tema renkleri motora gider (ön plan, arka plan, 256 renkli palet).
- Ayarlar: yazı boyutu, cursor biçimi, yanıp sönme hızı, palet.
- Find in output: tüm scrollback taranır, eşleşmeler sayılır ve gezilir.
- OSC 8 bağlantıları: tıklanan hücrenin URI'si bulunur ve tarayıcıda açılır.
- Oturum adı, çalışan kabuğun kendi adıdır (`/proc/<pid>/comm`).

---

## Yarım kalanlar

| Konu | Durum |
|---|---|
| **OSC 52 pano yazımı** | Mekanizma uçtan uca bağlı, ama **varsayılan reddediyor**: ayar yok. Ayar eklenmeden özellik etkisiz. |
| **Uzun bas → sürükle seçim** | Çift/üç dokunuş kelime/satır seçiyor. Seçimi parmakla uzatma motorun *gesture* API'sini istiyor (`ghostty_selection_gesture_event_new`), henüz bağlı değil. |
| **Find vurgusu** | Eşleşmeler bulunuyor ve geziliyor, **vurgulanmıyor**: renderer'ın hücre başına aralık taşıması gerekiyor. |
| **Seçim tutamaçları** | Android'in seçim tutamaçları çizilmiyor. |
| **Fare raporlaması** | Programın fare takip modu okunuyor, olaylar **gönderilmiyor**. |
| **Prompt'a atlama (OSC 133)** | Motorun etiketi var, kullanılmıyor. |
| **Split view** | İkinci pane var; oturum bağlama ve pane'ler arası odak akışı tam değil. |
| **Kitty görselleri** | Motor destekliyor, Drosh çizmiyor. |
| **Geniş karakterler (wcwidth)** | Motor hücrenin genişliğini vermiyor; iki hücrelik glifler tek hücrede çiziliyor olabilir. |

---

## Bilinen sınırlar

- **Izgara germez.** Hücre, fontun kendi hücresidir (`descent - ascent`); ızgara
  tam sayıda hücredir ve artan kısım terminalin arka planıdır. Ekranı kaplayan
  programlar bu yüzden kenarda ince bir boşluk görür — Ghostty ve Termux de
  böyle yapar.
- **Navigation bar'ın bandı.** Render, barın üstünde biter; çubuk görünürken
  altta bir bant kalır. Gesture navigasyonda bar bildirilmediği için bant yoktur.
- **Kontrast/koyu tema.** Varsayılan terminal arka planı koyu (#0E0E0E).

---

## Mimari — bir bakış

```
Compose ekranı
   ↓ (olaylar)
ViewModel (ui/)
   ↓ çağırır
Use case (domain/)            saf Kotlin, Android bağımlılığı yok
   ↓ çağırır
Repository arayüzü (domain/)
   ↓ uygular
Repository (data/) · Motor (native/) · Oturum yöneticisi (terminal/session/)
```

Modüller `android/` altında: `ui`, `domain`, `data`, `app`, `terminal`,
`linux`, `features/*`. Bağımlılık yönü tek yönlüdür; `ui` motoru doğrudan
bilmez, `domain` saf Kotlin'tır. Sınır ihlali `checkModuleBoundaries` ile
CI'da denetlenir.

Motor sınırı: `native/src/jni/` JNI giriş noktaları, `native/src/frame.rs`
kare üretimi, `native/src/ghostty.rs` motorun C ABI'sinin tek kapısı.

---

## Nasıl doğrulanır

- **CI:** `Build Debug` (APK), `Unit Tests` (JVM testleri + Rust testleri),
  `Native` (motor kütüphanesi).
- **Cihaz:** kurulum, oturum açma, nano'da hiza, cursor, pinch, kaydırma,
  klavye aç/kapa.
