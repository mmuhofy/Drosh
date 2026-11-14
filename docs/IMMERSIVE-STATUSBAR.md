# Immersive Status Bar — kaldırıldı, sonra geri geldi

> Durum (2026-11-13): **yeniden etkin.** Özellik 2026-10-09'da kaldırılmıştı;
> aynı davranış (satır üste taşınır, sistem çubukları gizlenir) bu tarihte
> geri getirildi ve eski davranışın *birebir kendisi* olarak uygulanıyor.
> Aşağıdaki "kaldırıldı" bölümü tarihsel bir kayıttır: kaldırma kararının
> gerekçeleri bugün de geçerli (özellikle cutout), ama özelliğin kendisi
> geri geldi.
>
> Prototip: `statusbar-immersive-prototype.html` (tasarım vault'unda, `MuhofyBox/Drosh/`).
> Üç ayrı deneme, üçü de cihazda yanlış bulundu; sonunda özellik kaldırıldı ve
> 2026-11-13'te geri geldi.

---

## 1. Sonuç

**Özellik 2026-10-09'da kaldırıldı, 2026-11-13'te geri geldi.** Bugünkü
davranış:

- Sistem status bar'ı **immersive durumunda gizlenir** (çubuklar birlikte).
- Üst satır (pills) gizlenmez, **ekranın en üstüne taşınır** — status bar'ın
  bıraktığı yere.
- Izgara, satırın altında kalsın diye `CHROME_CLEARANCE` kadar pay bırakır.
- Canlı kenarda (scroll yukarıdaysa) satır normale döner ve sistem status bar'ı
  geri gelir.

Kaldırma kararının gerekçeleri aşağıda; özellikle cutout'taki sınırlama bugün
de geçerli.

## 2. Neden bırakıldı

İstenen "pills, status bar'ın boşalttığı bandın içinde, ekranın en üstünde" ile
gerçekte ulaşılabilen olan çelişiyordu.

**Cutout.** Telefonda çentik varken, `status bar` gizlenince Android pencereyi
`layoutInDisplayCutoutMode = DEFAULT` ile letterbox'luyor. AOSP kaynağı
(`frameworks/base/core/java/android/view/WindowLayout.java`) yalnızca status bar
*görünür isteniyorsa* cutout'a uzanmaya izin veriyor:

```java
final Insets systemBarsInsets =
    state.calculateInsets(displayFrame, systemBars(), requestedVisibleTypes);
if (systemBarsInsets.top >= cutout.getSafeInsetTop()) {
    displayCutoutSafeExceptMaybeBars.top = MIN_Y;
}
```

Yani `offset = 0.dp` yazmak yeterli değildi — pencere zaten cutout'un altından
başlıyordu. Android SDK-35'te `DEFAULT` → `ALWAYS` olarak yorumlandığı için
modern cihazlarda bu çalışır, ama bu proje **`targetSdk = 28`**
(`DroshBuildConfig.kt:16`), o yorumdan yararlanmıyor.

Google'ın kendi uyarısı:

> *"Use `always`, `shortEdges` or `never` cutout modes if your app needs to
> transition into and out of immersive mode. Default cutout behavior can cause
> content in your app to render in the cutout area while the system bars are
> present, but not while in immersive mode. This results in the content moving up
> and down during transitions."*

**Yön.** Dokümanlar da prototipin biri de "canlı kenar = fullscreen" derken
diğeri "scrollback = fullscreen" diyordu (`statusbar-immersive-prototype.html`
`.dbar` dönüşü, `topbar_scroll_prototype.html` `scrollTop < 8` dönüşü — ikisi de
tasarım vault'unda). Kod da
sırayla iki yönü de denedi. Sunulan davranışın hangisi doğruydu cihazda belli oluyor
du, ve üçü de reddedildi.

**Maliyet.** Bir sabit ızgara bir web sayfası değildir: üst boşluk değişince satır
sayısı değişir, PTY'ye SIGWINCH gider, shell prompt'unu yeniden çizer. Padding'in
animasyonu bu durumu üç katına çıkarıyordu.

## 3. Şimdi ne var

- Sistem status bar'ı her zaman görünür.
- `TerminalTopBar` sabit konumda; `CHROME_CLEARANCE` (52dp) hem bar hem ızgara
  için tek sayı. (`TerminalTopBar.kt`, `TerminalScreen.kt`)
- `TerminalTopBar`'ın `chromeCollapsed` / `collapsedBandColor` parametreleri,
  `collapse` animasyonları, bant kutusu — yok.
- `TerminalManager`'daki `scrollTopRow`, `isAtLiveEdge`, `hasScrolled`,
  `chromeIsAtLiveEdge` — yok. `TerminalView.onScrollPositionChanged` — yok.
- Ayarlardaki `autoHideStatusBar` anahtarı — yok.
- `ChromeScrollTest.kt` — silindi.
- Bant yok. Pill'ler terminalin kendi arka planı üzerinde şeffaf; backdrop
  `TerminalBackdropSlice` ile terminalden örneklenip bulanıklaşıyor.

Tekrar açılması gerekirse: `layoutInDisplayCutoutMode=always` zorunlu, `targetSdk`
sorusu netleşmeli, ve cutout inset'iyle birlikte tek bir yönde karar verilmeli.
