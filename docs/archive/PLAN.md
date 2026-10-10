# Drosh Keyboard — Plan

> **Durum:** Faz 0-4 **tamamlandı**, cihaz testi bekliyor. Editor ertelendi.
> **Tarih:** 2026-10-01
> **Karar:** FlorisBoard tam fork, **ayrı Gradle build + ayrı repo**, ikinci APK, aynı imza.
> **Repo:** `github.com/mmuhofy/DroshKeyboard` · yerel `/root/projects/DroshKeyboard`

---

## 0. Faz 0 — tamamlandı ✅

| Commit | İş | Doğrulama |
|---|---|---|
| `5d0a93bc` | Fork beyanı + CI adı, **kod tabanına dokunulmadan** | ✅ `success` |
| `8875ea3b` | `paths-ignore` kaldırıldı | — |
| `8540803c` | `workflow_dispatch` eklendi | — |
| `aea58e7c` | Paket `dev.patrickgold.florisboard` → **`dev.drosh.ime`** | ✅ `success` |
| `2141c9c4` | Room şema dizinleri taşındı (rename'i kıran hata) | ✅ `success` |
| `0fa9239d` | Drosh keystore'u + CI imza doğrulaması | ✅ `success` + parmak izi eşleşti |

**Doğrulanmış:** paket `dev.drosh.ime`, `applicationId` `dev.drosh.ime`, keystore SHA-256
`d02d64021c029395722589f8f08bf426be6c997f0195ac6990d7a9b29a731258` — **Drosh ile birebir aynı**.

### 0.1 Kurulum komutu (belgelenmiş, tekrar gerekecek)

Her push'tan sonra:

```bash
cd /root/projects/DroshKeyboard
git push origin main
gh workflow run android.yml -R mmuhofy/DroshKeyboard --ref main
gh run list -R mmuhofy/DroshKeyboard -L 1
```

> ⚠️ **Push tetikleyicisi bu fork'ta çalışmıyor.** Repo düzeyinde Actions açık
> (`enabled=true`) ve workflow `state=active`, ama push'tan run oluşmuyor.
> `workflow_dispatch` anında çalışıyor. Kök neden kesinleşmedi.
>
> **Sonuç: `gh run list` boş dönerse derleme yapılmamış demektir — "yeşil" değil.**
> Run yoksa elle tetikle, hiçbir yeşil raporu buna dayandırma.

### 0.2 Rename sırasında çıkan tuzaklar

Bunlar bir sonraki fork/rename'lerde de geçerli:

1. **Room şema dizinleri** — Room şemayı sınıfın tam nitelikli adıyla klasörle adlandırıyor
   (`app/schemas/dev.drosh.ime.ime.clipboard.provider.ClipboardHistoryDatabase`). Paket
   değişince bu üç dizini de taşımak gerekiyor; aksi halde KSP `2.json`/`3.json` bulamıyor
   ve otomatik migrasyon üretilemiyor.
2. **`lib/` içindeki hardcode** — `lib/android/.../PackageManager.kt:23` app modülünün
   manifest `activity-alias` adını sabit kodluyor. Kod derlenir ama çalışma zamanında
   uygulama simgesini gizleyemez.
3. **Harici kütüphaneler** — `dev.patrickgold.jetpref` (187) ve `dev.patrickgold.compose` (1)
   aynı yazarın **harici Maven artifact**'leri. `dev.patrickgold` ön ekinin tamamını
   çevirmek bunları kırdı. Yalnızca `dev.patrickgold.florisboard` çevrildi.
4. **`authorities` güvenli** — manifest `${applicationId}` interpolasyonu kullanıyor,
   otomatik doğru.
5. **Gradle buildType sırası** — imza ataması ikinci bir `buildTypes {}` bloğunda
   `getByName("beta")` çağıramaz, çünkü `beta` o bloğun içinde daha sonra oluşturuluyor.
   Atama mevcut `buildTypes` girdilerinin içine yapılmalı.

---

## 1. Ne yapılacak

FlorisBoard'un tamamı fork'lanır ve **ayrı bir APK** olarak yayınlanır. Drosh'a özgü hâlleri:

- **Drosh teması** — Drosh renk paletine bağlı, likit cam arka plan (ayarlardan açılır/kapanır)
- **Drosh modu** — terminal odaklı düzen, özel tuşlar sekmesi, komut geçmişi sekmesi
- **Snippet sekmesi** — `~/.drosh/snippets.json` dosyasından okur, klavyeye doğrudan yazar
- **Space modları** — normal / swipe / basılı-tut pad (mevcut + yeni)
- **Akıllı cd önerisi** — IME öneri çubuğunda, klavye tarafında

İki uygulama arasında **bağlantı zorunlu değil**: IME Android'de zaten sistem genelindedir, kullanıcı Ayarlar'dan seçer. Ortak imza yalnızca iki uygulamanın birbirinin verisini okuması gerektiği durumlar için gerekli (snippet dosyası).

---

## 1.1 Build mimarisi — **ayrı Gradle build** (karar: A)

İki uygulama **tek Gradle build'de birleşmez.** Klavye kendi deposunda, kendi araç zinciriyle yaşar.

### Neden — sürüm uçurumu

| | Drosh | FlorisBoard | Uyum |
|---|---|---|---|
| `compileSdk` | 36 | **37** | ✗ |
| `targetSdk` | 28 | 37 | ✗ (bilinçli fark) |
| `minSdk` | 26 | 26 | ✓ |
| AGP | **8.13.0** | **9.2.1** | ✗ major |
| Kotlin | **2.2.0** | **2.4.20** | ✗ 2 minor |
| Gradle | 9.0.0 | 9.7.1 | ✗ |

Kaynaklar:
- Drosh: `build-logic/.../DroshBuildConfig.kt:15-17`, `gradle/libs.versions.toml:16-17`, `gradle/wrapper/gradle-wrapper.properties`
- FlorisBoard: `gradle.properties` (`projectCompileSdk=37`), `gradle/libs.versions.toml:3` (AGP `9.2.1`), `:8` (Kotlin `2.4.20`)

FlorisBoard SDK'larını `gradle.properties`'ten okuyor (`app/build.gradle.kts:56,67,68`), Drosh ise convention plugin'larına gömülü. **Tek build'de `include(":keyboard")` bugün derlenmez.**

### Reddedilen seçenekler

- **B — Tek build, FlorisBoard'ı düşür.** AGP 9.2→8.13 ve Kotlin 2.4→2.2 geriye gidiş. Modern Compose API'leri ve `lib/snygg` yeni Kotlin özellikleri kullanıyor; derlemesi muhtemelen kırılır. Ayrıca **her FlorisBoard güncellemesinde** aynı düşürmeyi tekrar yapmak gerekir — kalıcı bir borç.
- **C — Tek build, Drosh'i yükselt.** `compileSdk 36→37` ve AGP 8→9. Reddedildi: kullanıcı kararı **"SDK değişikliği yapılmayacak"**. Ayrıca `targetSdk 28` bilinçli bir tercih (eski davranış).

### A'nın sonucu

- Drosh'e **hiç dokunulmaz** — `settings.gradle.kts`, `libs.versions.toml`, convention plugin'lar aynen kalır
- Fork'un `gradle.properties` / AGP / Kotlin sürümleri kendi hâlinde kalır
- Derleme bağımsız: biri kırılırsa diğeri etkilenmez
- Ortak olan **yalnızca iki şey**: imza keystore'u ve snippet dosya formatı

> **F-Droid notu:** F-Droid recipe'ları Gradle build çalıştırmak zorunda, ama her recipe kendi build'ini kullanır. `dev.drosh` ve `dev.drosh.ime` için ayrı recipe'lar yeterlidir; ikisi de aynı CI'da çalışabilir çünkü build'ler ayrı.

---

## 2. Neden ayrı APK

Android bir uygulamanın kendi IME'ine **programatik olarak geçmesini yasaklıyor**. Bu güvenlik politikası:

> "Bir istemci uygulama sistemden yeni bir IME seçtirmesini **isteyebilir**, ama **programatik olarak kendisi geçemez**."

Sonuç: ne yaparsak yapalım kullanıcı **bir kez Ayarlar'dan etkinleştirip seçmek zorunda**. Bunu tek seferlik, düz bir akışla yapıyoruz (Bölüm 5).

Ayrı APK olmasının asıl nedeni tek APK'nın **her Android sürümünde IME olarak da görünmesi** — kullanıcı "Drosh neden iki kere listede" diye sorar. Ayrı uygulama temiz.

> `Shizuku` ile otomatik geçiş mümkündür (`ime set <id>`) ama **kullanılmıyor** — bölüm 10.

---

## 3. Lisans

Drosh **GPL-3.0** (`LICENSE`, `README.md:188`).
FlorisBoard **Apache-2.0**.

Apache-2.0 → GPL-3.0 uyumludur, GPLv3 lisanslı çalışma ile birleştirilebilir. Fork'un Apache-2.0 dosyaları korunur, `NOTICE` dosyası korunur.

> **`AGENT.md` yanlış söylüyor.** `docs/MEMORYBANK.md §1` lisansı MIT olarak veriyor. Ayrıca Kotlin `2.3.20` (gerçek: `2.2.0`) ve accent `#E8C547` altın (gerçek: `primary = 0xFF4C9EFF` mavi) yazıyor. Bu dosya bugün iki kez yanlış yönlendirdi.

---

## 4. Fork başlangıcı

FlorisBoard `main` branch, tam fork, önce **hiçbir şey çıkarılmadan**.

Yapı (doğrulandı):
- **365 Kotlin dosyası, sıfır Java.** Drosh'la aynı dil.
- `app/src/main/kotlin/dev/patrickgold/florisboard/...` (253 dosya)
- `lib/` — `snygg` (48), `compose` (21), `android` (15), `kotlin` (13)
- Dokunma/gesture motoru **ayrı paketlerde**: `ime/keyboard3/touch/`, `ime/keyboard3/interaction/` — space drag eklemek için ayrılmış yer var.

### 4.1 Space-drag — fork'ta **zaten var**

> ⚠️ Önceki araştırmada "yok" denmişti. **Yanlıştı.** Gerçek konumlar:

| Ne | Konum |
|---|---|
| Dispatcher | `ime/text/keyboard/TextKeyboardLayout.kt:716` — `KeyCode.SPACE, KeyCode.CJK_SPACE -> handleSpaceSwipe(event)` |
| İmplementasyon | `TextKeyboardLayout.kt:823` — `handleSpaceSwipe()` |
| Ayar | `prefs.gestures.spaceBarSwipeLeft / spaceBarSwipeRight` → `SwipeAction.MOVE_CURSOR_LEFT / RIGHT` |
| İmleç hareketi | `keyboardManager.handleArrow(KeyCode.ARROW_LEFT, count)` |
| Shift seçim | `editorInstance.massSelection.begin()` + `hasTriggeredMassSelection` |
| Haptic | `inputFeedbackController?.gestureMovingSwipe(TextKeyData.SPACE)` |
| Aynı desen DELETE'de | `handleDeleteSwipe()` `:755` — karakter/kelime seçimi |

`ImeEditor.kt:56-58` çevre metni, `:81` `ic.setSelection()` — Gboard mekanizmasının aynısı, hazır.

**Sonuç:** Faz 2'de space-drag yazılmıyor. Sadece **yeni mod ekleniyor**: basılı tutunca 4 yönlü imleç pad'i (ve ileride mouse/touchpad). Mevcut swipe korunur.

*(Not: HeliBoard'un `keyCodeToKeyEventCode`'u ile karıştırılmıştı — o sadece tuş kodu eşlemesidir.)*

---

## 5. Drosh'ta kurulum akışı

```
İlk açılış
  └─ Sistem IME'si Drosh Keyboard mı? Değilse:
       └─ Popup: "Drosh Klavyesi'ni etkinleştir"
            ├─ "Ayarlar'a Git"  → ACTION_INPUT_METHOD_SETTINGS
            └─ Kullanıcı etkinleştirir + seçer
  └─ Sonraki açılışlarda: kullanıcı sistem klavyeyi tercih edilen IME yaptıysa
     Drosh otomatik onu kullanır.

Drosh hiçbir zaman programatik geçiş yapmaz — denemez de.
```

---

## 6. Snippet sistemi

### 6.1 Dosya

```
<data>/files/home/.drosh/snippets.json     (PRoot içinde → shell'de ~/.drosh/snippets.json)
```

Biçim (JSON, `kotlinx-serialization` projede zaten var):

```json
{
  "gp": "git push",
  "gl": "git log --oneline --graph --decorate"
}
```

### 6.2 Yazma

Kullanıcı komutla alias tanımlar → **`snippets.json`'a yazılır, `.zshrc`'ye değil.**

`.zshrc` kullanıcıya aittir ve **asla değiştirilmez** (`TerminalManager.kt:597`: *"so user's .zshrc is never modified"*). Drosh şu anda `ENV` injection kullanıyor (`:583-614`) — snippet'ler de aynı desenle gömülür:

`dev.drosh_snippets.zsh` içine:
```zsh
# Drosh snippets — generated, do not edit
alias gp='git push'
alias gl='git log --oneline --graph --decorate'
```

`.zshrc`'ye hiç dokunulmaz. Kullanıcı `alias` yazarsa o kendi `.zshrc`'sine gider (bu normal shell davranışı); **Drosh'un snippet komutu** ise `snippets.json`'a yazar.

### 6.3 Okuma

- **Terminal:** shell başlarken `ENV` ile snippet alias'larını yükler
- **Klavye:** `snippets.json`'u okur, "Snippet" sekmesinde listeler, dokununca **klavyeye doğrudan yazar**

### 6.4 Yazma yolu (mevcut köprü)

Klavye `InputConnection.commitText()` çağırır → terminalin mevcut `BaseInputConnection` → `sendTextToTerminal()` → PTY.

**Yeni köprü gerekmiyor.** Klavyenin yazdığı metin zaten doğal olarak PTY'ye gider.

---

## 7. Klavye modları

### 7.1 Space tuşu — üç mod

| Mod | Durum | Davranış |
|---|---|---|
| **1 — normal** | ✅ var | Space yazar |
| **2 — swipe** | ✅ var | Space basılı tutup sağa/sola kaydırma → imleç (`TextKeyboardLayout.kt:823`) |
| **3 — basılı tut → pad** | 🆕 **yazılacak** | Space'e basılı tutunca 4 yönlü imleç pad'i açılır |
| **4 — pad → mouse** | 🔮 ileri | Aynı pad mouse/touchpad moduna dönüşür |

Mod 1 ve 2 fork'tan gelir, hiç değişmeyecek. **Yalnızca mod 3 yeni:**

- Uzun basış algısı: `ime/keyboard3/interaction/LongPress.kt` mevcut altyapı
- Pad: `TextKeyboardLayout.kt:715`'teki `when(KeyCode)` dispatcher'ına yeni kol
- Pad çıktığında `handleArrow(KeyCode.ARROW_UP/DOWN/LEFT/RIGHT, 1)` — mevcut `keyboardManager.handleArrow` aynısı
- Mod 3 aktifken swipe devre dışı (çakışma)

### 7.2 Sekmeler

Klavyenin tab çubuğunda:

- **ABC** — normal düzen
- **Özel** — Esc, Tab, `\ | ~ \``, Ctrl, Alt, oklar, Home/End, PgUp/PgDn, F1-F12
- **Komut geçmişi** — shell geçmişi
- **Pano** — pano + **snippet bölmesi**
- **Snippet** — `snippets.json` listesi
- **Emoji** — FlorisBoard'ın kendi paneli (11 dosya, hazır)

### 7.3 Sesli dikteyon

FlorisBoard'ın kendi `lib/media/` altyapısı veya `RecognizerIntent`. İlk fazda mevcut olanı kullanırız.

---

## 8. Akıllı cd

**İlk fazda klavye tarafında** — IME öneri çubuğunda. `cd tmp` yazınca `tmp/` önerilir.

**İleride** klavyedeki ayrı tuşla dosya yöneticisi açılıp konum seçimi (klavyeye girilen konum).

### 8.1 Terminal kökü notu

`TerminalManager.kt:546` ilk oturumda `HOME=/` ile başlatıyor, ama `:645` profil yazarken `HOME=/home` yapıyor ve PRoot `wd`'si `/home` (`ProotRunner.kt:40`). Yani normalde `~` = `<data>/files/home`, `~/.drosh/snippets.json` doğru yerde. İlk oturumdaki `HOME=/` geçici bir durum; profil yazıldıktan sonra düzeliyor.

---

## 9. Tasarım

### 9.1 Renkler — koda göre, `AGENT.md`'ye göre değil

| Rol | Değer |
|---|---|
| Birincil | `0xFF4C9EFF` (mavi) |
| Arka plan | `0xFF0E0E0E` |
| Yüzey | `0xFF1A1A1A` |
| Yüzey yüksek | `0xFF2E2E2E` |
| Metin | `0xFFF2F2F2` |

> `AGENT.md` accent'i `#E8C547` altın diyor — **yanlış**, kodda `primary = 0xFF4C9EFF` mavi.

### 9.2 Likit cam

Klavye arka planı blur'lı, ayarlardan açılıp kapatılır.

**Teknik gerçek:** Haze `AndroidView` içindeki View'i göremiyor — aynı tuzak. Klavyenin arkasında terminal yok (klavye ayrı uygulama), bu yüzden burada **Haze çalışır**. Terminal içindeki bir yüzeyde yapılırsa aynı sorun çıkar.

---

## 10. Dağıtım

İki APK, ikisi de F-Droid:

| Uygulama | Paket | Kaynak |
|---|---|---|
| Drosh | `dev.drosh` | mevcut |
| Drosh Keyboard | `dev.drosh.ime` | FlorisBoard fork |

**İmza:** `keystore.properties` → `keystore/debug.keystore` (git'e commit edilmiş; dosya yorumuna göre CI'ın her koşuda aynı anahtarla imzalaması için konmuş, yoksa `INSTALL_FAILED_UPDATE_INCOMPATIBLE` çıkıyor). Klavye deposu **aynı keystore'un kopyasını** kullanır — bu, snippet dosyasının iki uygulama arasında paylaşılabilmesi için gerekli.

**Ayrı depo = ayrı CI.** İki build bağımsız olduğu için, biri kırılırsa diğerini etkilemez. F-Droid tarafında iki ayrı recipe yeterlidir.

**Shizuku kullanılmıyor.** Gerek kalmıyor: IME zaten sistem genelinde, kullanıcı bir kez seçiyor.

---

## 11. Riskler

| Risk | Durum |
|---|---|
| Fork boyutu (365 dosya) | Bilinçli: başta tam fork, sonra budama |
| İki depo bakım yükü | F-Droid recipe'ları ayrı; ikisi tek CI'da çalışabilir |
| Play politikası | **Yok** — sadece F-Droid |
| CJK dönüştürme | `ime/nlp/han/` mevcut, sökülürse kayıp olur → **dokunulmayacak** |
| Alan adı | `drosh.dev` **kayıtlı değil** (RDAP 404, DNS NXDOMAIN). F-Droid için domain şart değil; ileride alınırsa `dev.drosh.ime` doğru kalır |
| IME etkinleştirme | Kullanıcı Ayarlar'dan bir kez, Android zorunlu kılıyor |
| Terminal'de space-drag | ⚠️ `ic.setSelection()` PTY'ye gider, görsel imleç **terminalin kendi imlecini** taşır. Drosh `TerminalView`'da zaten `LocalEditable` aynası var → test edilecek |
| Snippet JSON parse | Klavyede her açılışta okunur, küçük dosya → sorun değil |
| Fork güncellemeleri | FlorisBoard yeni sürüm çıkardığında fork'un güncellenmesi gerekir → sürüm notlarını izle |

---

## 12. Uygulama sırası

**Faz 0 — Fork** — ✅ **tamamlandı**, yukarıya bak
1. ✅ FlorisBoard'u fork'la, ayrı repo (`mmuhofy/DroshKeyboard`)
2. ✅ `package` → `dev.drosh.ime`, `applicationId` → `dev.drosh.ime`
3. ✅ Drosh'in `settings.gradle.kts`'ine dokunulmadı — `include(":keyboard")` yok
4. ✅ Aynı keystore, `keystore.properties` yazıldı
5. ✅ Değiştirilmeden APK üretildi, CI yeşil

**Faz 1 — Drosh teması (2-3 gün)**
6. Drosh renk paletini klavye temasına bağla
7. Likit cam yüzeyi + ayar anahtarı
8. `~/.drosh/snippets.json` okuma (klavye tarafı)
9. Drosh'ta ilk kurulum popup'ı (Drosh deposunda — bu tek Drosh tarafı değişiklik)

> **Faz 1'e başlamadan önce:** `strings_dont_translate.xml:9`'daki `KeyCode.kt` yardım
> URL'si hâlâ upstream yolunu gösteriyor. Çalışıyor (upstream korunduğu için) ama
> branding fazında düzeltilmeli.

**Faz 2 — Terminal modu (3-4 gün)**
10. Drosh modu düzeni + Özel sekmesi
11. Komut geçmişi sekmesi (shell'e `history` sorgusu)
12. Pano + snippet sekmesi
13. **Space basılı-tut → 4 yönlü imleç pad'i** (mevcut `handleSpaceSwipe` korunur, yalnızca yeni mod)

**Faz 3 — snippet komutu (1-2 gün)** — *Drosh deposunda*
14. `drosh snippet add gp "git push"` → `snippets.json`
15. `ENV` injection ile shell'e yükle (`.zshrc`'ye dokunma)
16. **`.zshrc`'ye dokunulmaması testi**

**Faz 4 — Akıllı cd (2 gün)**
17. Öneri çubuğunda yol önerisi
18. Dosya yöneticisi tuşu

---

## 13. İlk yapılacak

Faz 0 tamamlandı. Sıradaki iş:

```
cd /root/projects/DroshKeyboard
# Faz 1 — Drosh teması: renk paletini klavye temasına bağla
# her push'tan sonra: gh workflow run android.yml -R mmuhofy/DroshKeyboard --ref main
```

Faz 2/3 (terminal modu, snippet komutu) **Drosh deposunda** ilerleyecek —
klavye reposu o işlerde değişmiyor.

---

## 14. Doğrulanmış kaynaklar

**Kod — bu oturumda açılıp okundu:**
- **DroshKeyboard (fork):** `TextKeyboardLayout.kt:716` (dispatcher), `:823` (`handleSpaceSwipe`), `:755` (`handleDeleteSwipe`), `ImeEditor.kt:56-58,81`
- **DroshKeyboard (build):** `gradle.properties`, `gradle/libs.versions.toml:3,8`, `app/build.gradle.kts:56,67,68`
- **Drosh (build):** `build-logic/.../DroshBuildConfig.kt:15-18`, `gradle/libs.versions.toml:16-17`
- **Drosh (imza):** `keystore.properties`, `keystore/debug.keystore`, `.gitignore:70-77`, `app/build.gradle.kts:15-72`, `.github/workflows/debug.yml`
- **Drosh (shell):** `terminal/.../TerminalManager.kt:546`, `:583-614`, `:626-669`, `:655-657`; `ProotRunner.kt:40,54`
- **Drosh lisans:** `LICENSE`, `README.md:188`

**Doğrulanan alan adı durumu:**
| Alan | RDAP | DNS |
|---|---|---|
| `drosh.dev` | 404 → kayıtlı değil | NXDOMAIN |
| `drosh.me` | 404 → kayıtlı değil | NXDOMAIN |

**Harici:**
- FlorisBoard: `github.com/florisboard/florisboard` — Apache-2.0, 365 Kotlin dosyası, 0 Java
- Termux:API imza kuralı: *"signed with the same key as the main Termux app for permissions to work"*
- Gboard space-drag: AOSP `LatinIME.onMovePointer(int steps)` → `getTextBeforeCursor()` sınırlı → `setSelection()`
- Shizuku `ime enable/set` — referans ama **kullanılmıyor**

**Yanlış olduğu doğrulanan iddialar:**
| İddia | Gerçek |
|---|---|
| `AGENT.md` lisans MIT | GPL-3.0 (`LICENSE`) |
| `AGENT.md` Kotlin `2.3.20` | `2.2.0` (`libs.versions.toml:17`) |
| `AGENT.md` accent `#E8C547` altın | `primary = 0xFF4C9EFF` mavi (`DroshColors.kt`) |
| FlorisBoard'da space-drag yok | **Var** — `TextKeyboardLayout.kt:823` |
| HeliBoard `keyCodeToKeyEventCode` = space-drag | Hayır, sadece tuş kodu eşlemesi |
| Klavye snippet'i PTY'ye özel köprü gerektirir | Hayır — `commitText()` → mevcut `BaseInputConnection` → PTY |
| Package rename sadece kaynak dosyalarla sınırlı | Hayır — Room şema dizinleri de isim türetiyor |
| `dev.patrickgold` önekini çevirmek güvenli | Hayır — `jetpref`/`compose` harici artifact'ler |
| Push CI'si fork'ta çalışır | **Çalışmıyor** — `workflow_dispatch` zorunlu |

---

# 15. Tamamlananlar (2026-10-04)

Hepsi CI'da yeşil. **Cihazda henüz test edilmedi.**

## 15.1 Yazma bozulması (bug, en acil)

`TerminalView`'ın `BaseInputConnection`'ında `setComposingRegion`/`setComposingText`
override **edilmiyordu**. `editable` aynası `commitText` ile PTY'ye gönderilip
temizlendiği halde `BaseInputConnection`'ın composing işaretçileri **asılı kalıyordu**;
sonraki `finishComposingText` bunları boşaltılmış tampona karşı çözüp **daha önce
gönderilmiş metni geri koyuyor**, `commitText` de onu ikinci kez PTY'ye yolluyordu.

> "Bir harf yazıyorum, bir sürü harf geliyor, eskiler de geliyor" — biriken ayna.

Gboard `TYPE_NULL`'da öneri kurmadığı için görünmüyordu; **Drosh Keyboard ile ortaya çıktı.**
İkisi de no-op yapıldı — terminalde composing kavramı yok.

## 15.2 Komut durumu — OSC 133 (eski mekanizma tamamen silindi)

**Silinenler:** `writeShellHooksFile()`, `dev_drosh_cmd_complete` dosyası,
`TerminalService` 500 ms yoklaması + `RandomAccessFile` ofseti,
`command|elapsed|exit_code` ayrıştırıcısı, bildirim kanalı, toast,
komut başına iki `$(date +%s)` fork'u.

Eski yapının ifade edemedikleri:
- `git commit -m "a|b"` → dörde bölünüp **sessizce düşüyordu**
- Yarım satıra denk gelen okuma `lastFilePointer`'ı ilerletiyor, o komut **bir daha bildirilmiyordu**
- Dosya sonsuz büyüyordu
- Hook'lar zsh fonksiyonuydu ama **bash oturumlarında hiç çalışmıyordu**

Yeni: **OSC 133** (FinalTerm; iTerm2, WezTerm, Kitty, Ghostty, VS Code,
Windows Terminal). Kabuk birkaç kaçış dizisi yazar, emulator yorumlar.

| Shell | Mekanizma |
|---|---|
| zsh | `$ENV` — `.zshrc`'ye dokunulmaz. A/B `PROMPT`'a enjekte edilir (`%{ %}` ile) |
| bash | `--rcfile` — önce kullanıcının `.bashrc`'i. C `PS0`'dan |
| diğer | yok → `ShellIntegrationLevel.NONE` |

**Test edilen (tahmin edilmeyen):** bash `PS0` **stderr'a** yazıyor.
`ls > out.txt` dosyası sadece `ls` çıktısı içeriyor, stdout 0 bayt. Doğrulandı.

Süre artık **emulator içinde** `elapsedRealtime` ile damgalanıyor — sıfır kayma,
kabuk saat okumuyor.

## 15.3 Ambiyans — üç katman, hepsi tuş alanı çalmadan

| Katman | Sinyal | Görünüm |
|---|---|---|
| **E1** Kenar = durum | OSC 133 durumu | Boşta nötr · çalışırken mavi + **nefes** · yeşil · **kırmızı kalıcı** |
| **E2** Hareket = aktivite | `onTextChanged` çıktı hızı | `gradle build` hareketli, `sleep 100` düz |
| **E3** Ton = palet | `TerminalRow.mStyle` hücre renkleri | Kenar %35 harman, yüzey boyanmaz |

**E3'ün kilidi:** piksel gerekmiyor. Terminalin her hücresinde stil biti ve
ön plan palet indeksi var. Apple'ın "çevreden renk al" fikri bir terminalde
**bedava** — çünkü renk zaten yapılandırılmış veri.

Tasarım kısıtları: son 6 satır, yeniye ağırlık · varsayılan ön plan sayılmaz ·
doygunluk tavanı + dar kanal aralığı · `altBuffer` (TUI) **tamamen atlanır**.

## 15.4 IPC

`ContentProvider` (`dev.drosh.state`), **imza korumalı izin**, tek satır.
Broadcast değil çünkü durum **okunabilir** olmalı — araç ortasında açılan klavye
ilk sorgusunda yetişir. `notifyChange` ile gözlemci abone olur, yoklama yok.

Klavyenin bilmediği tek şey: `Failure` yalnızca `level == "f"` (tam entegrasyon)
güvenilirse. `dash`/`sh` hiç çıkış kodu veremez — orada her komutu hata
göstermek, hiç göstermekten kötüdür.

## 15.5 Yüzen klavye

`imePadding()` → `droshImePadding()`: **yalnızca klavye sabitlenmişken.**
Yüzen klavye içeriğin üstüne bilerek konur; dolgu hem anlamsız hem de klavyenin
oturduğu yerde ölü bir bant bırakırdı.

> **Bu, Drosh'ta camın gerçekten işe yaradığı tek durum.** Sabit modda içerik
> klavyenin üstünde biter; yüzen modda gerçek terminal arkadadır.

## 15.6 Cam hataları

- Köşeler dikdörtgenle doluydu → sheen **yüzey şekline kırpıldı**
- Üst border görünmüyordu → kontur yarım piksel dışarıda kalıyordu, **yarı çizgi kadar içeri alındı**
- Tuse gölgeleri kaldırıldı, yalnızca popup'larında kaldı (yüzeyin üstündeler)

---

# 16. Test edilmesi gerekenler

CI yalnızca derleme doğruluyor. **Hiçbiri cihazda çalıştırılmadı.**

| # | Test | Neden önemli |
|---|---|---|
| 1 | Terminalde harf yaz | Bug düzeldi mi, veri bozulması bitti mi |
| 2 | Gboard ile de bir tur | no-op composing Gboard'u etkiledi mi |
| 3 | `echo hi \| there` | `\|` içeren komut bildiriliyor mu |
| 4 | Köşeler boş, üst çizgi var | Görsel hata düzeldi mi |
| 5 | Komut çalışırken kenar **mavi + nefes** | E1 + E2 çalışıyor mu |
| 6 | `exit 1` sonrası kenar **kırmızı kalıcı** | E1 doğru mu |
| 7 | `sleep 60` → parlama **durur** | Aktivite ölçümü ayırt ediyor mu |
| 8 | Yüzen klavye → terminal **klavyenin arkasında** | C çalışıyor mu |
| 9 | Spotify'da klavye | Drosh yokken nötr kalıyor mu |
| 10 | bash oturumu (OMZ kaldır) | bash `--rcfile` yolu |

## 17. Bilinen riskler

- **zsh `PS0`/prompt sarma davranışı zsh sürümüne göre değişebilir.** Cihazda
  doğrulanmalı; kitty'nin gerçek kodundan uyarlandı ama ölçüm yok.
- **`BUSY_UPDATES_PER_SECOND = 40` hissedilerek ayarlandı**, ölçülmedi.
- `E3` en spekülatif katman — ilk iki işe yaramazsa çıkarılabilir.
- Editor hâlâ erteledi; klavyenin komut/bitirmek sırası sonrasına kaldı.
