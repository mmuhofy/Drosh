# Session Sistemi — Durum Notu

_Tarih: 2026-09-29_

Bu not, session sisteminin nasıl çalıştığını ve Termux / kitty / ghostty ile
karşılaştırmada çıkan hataları tek yerde toplar. Kod okumadan önce buraya bakmak,
yarım saatlik bir araştırmadan sonra bulduğum hataların çoğunu önceden açıklıyor.

---

## 1. Model

Session'lar tek bir yerde yaşıyor: `TerminalManager.irisSessions`, sıralı bir
`MutableList<DroshSession>`. Her `DroshSession` bir PTY'yi (`TerminalSession`) ve
onun Room'daki satırını (`persistentId`) birleştirir.

Aktif session iki yerde temsil ediliyordu ve bu, sistemin en pahalı hatasıydı:

| | Nerede | Kim yazar |
|---|---|---|
| Bellek | `TerminalManager._activeTabIndex` | ekran |
| Disk | `KEY_ACTIVE_SESSION_ID` (DataStore) | repository |

`switchTab` ikisini de güncellemiyordu, dolayısıyla aktif session'ı kapatınca
görüntü komşuya geçiyor ama kayıt eskide kalıyordu; sidebar başka bir kartı
vurguluyordu. Artık **`TerminalManager` tek yazıcı** — `onActiveSessionChanged`
callback'i ile dışarıya bildiriyor, DataStore yalnızca okuyor.

`persistentId` **null olamaz**. Bu kasıtlı bir kısıt: id'siz bir session'ın
Room satırı yoktur, `liveSessionIds()` onu saymaz (null'ları filtreler), dolayısıyla
reconcile onu yönetmez, kapatamaz, geri yükleyemez ve sidebar'da görünmez.
Yönetim sisteminin dışında bir session açmak artık imkânsız.

---

## 2. Yaşam döngüsü

```
tıklama / komut
      ↓
TerminalManager.addTabWithId(id, name)      ← id zorunlu, hep repository'den gelir
      ↓
DroshSession → irisSessions, idToIndex güncellenir
      ↓
process ölür  (doğal: kullanıcı exit yazdı / UI: closeTab)
      ↓
onSessionFinished(session) / closeTab(index)
      ↓
  • irisSessions'tan çıkar, idToIndex yeniden haritalanır
  • lifecycleCallbacks.onSessionFinished(persistentId, exitCode)
      ↓
SessionManagerAdapter → Room satırı Closed
      ↓
reconcile (500ms ticker değil, Room emit'i) → Closed satırları spawn edilmez
```

### reconcile

`SessionManagerAdapter.reconcile`, Room'un canlı session listesiyle PTY listesini
karşılaştırır. **Kritik nokta: `collect` kullanır, `collectLatest` değil.**
`reconcile` döngüleri içinde suspend eder (her durum güncellemesi bir Room
yazımı), ve bir session'ı kapatmak yeni bir emit tetikler. `collectLatest`
ile yeni emit, devam eden geçişi iptal ediyordu — döngü yarıda kesiliyor,
kalan session'ların PTY'si hiç öldürülmüyordu. Liste "kapandı" diyordu,
süreçler yaşıyordu. Bu, "son session gerçekte kapanmıyor" hatasının sebebiydi.

`SessionManagerAdapter` `@ApplicationScope` üzerinde çalışır, o da
`Dispatchers.Default` — çok iş parçacıklı. Bu yüzden session mutasyonlarının
tamamı `withContext(Dispatchers.Main.immediate)` içine alındı. `TerminalManager`
bu varsayıma bağımlı ama tip sisteminde hiç ilan etmiyor; Termux'ta aynı şey
`Handler` + `synchronized` ile ana thread'e hapsedilerek yapılıyor.

---

## 3. Bilinen tuzaklar

Bunların hepsi gerçekten oldu, sadece kod okumakla bulunmadı.

**Foreground service process'i ayakta tutar.** Session'ları kapatmak Activity'yi
`finish()` eder ama servisi öldürmez. Process yeniden kullanılır. Bu yüzden
"sessionsEstablished" gibi process kapsamlı bir bayrak **Activity yaşam döngüsünü
anlatamaz** — `MainActivity.onStart` onu sıfırlar. Bu yapılmadan, tüm
session'lar silindikten sonra yeniden açmak siyah ekranla sonuçlanıyordu ve
kurtarmanın tek yolu sidebar'dan session açmaktı.

**Görünüm modu session açmamalı.** Classic ↔ block geçişi bir zamanlar
`addTab()` çağırıyordu; yani her mod değişiminde yeni session açılıyordu. İki mod
aynı session'ların görünümüdür, listeyi değiştirmez.

**`restartCurrentTab` restart değildi.** Eskiden session'ı kapatıp listenin sonuna
yeni bir tane koyuyordu: konum değişiyordu, yeni Room id alıyordu, block geçmişi
gidiyordu. Artık aynı session'ın arkasındaki süreç değişiyor — kimlik, sıra ve
geçmiş korunuyor. Block diff çapası da yeniden kuruluyor, yoksa yeni shell'in
çıktısı ölü shell'in transkriptiyle karşılaştırılırdı.

**Kapalı session'ın çıktısı görünmez.** Block modda bloklar duruyor, classic
modda terminal buffer'ıyla birlikte gidiyor. Termux ölmüş session'ın
scrollback'ini canlı tutarak hata çıktısını okutmana izin verir; bizim liste
onu düşürüyor. Bunu düzeltmek bir bayrak değil, mimari değişiklik.

---

## 4. Referanslarla karşılaştırma

| | Termux | kitty | ghostty | Drosh |
|---|---|---|---|---|
| Aktif session | view + kayıtlı UUID | pointer | pointer | index + id (ikisi de tek yazar) |
| Son session kapanınca | Activity biter, dialog yok | pencere kapanır | onay + çıkış gecikmesi | dialog: Delete / New session |
| Boş session durumu | yok | yok | yok | **olmamalı** (aşağıda) |
| Kalıcılık | **hiçbir şey saklamaz** | `.kitty-session` | yok | Room + DataStore |
| Shell integration (OSC 133) | yok | A/C/D | A/B/C/D + L/P/N | **yok, regex** |
| Threading | ana thread + `synchronized` | — | — | ana thread'e hapsedildi |

### Bizim en büyük yapısal farkımız

Termux session listesine **hiçbir şey saklamaz** — Room yok, reconcile yok.
Bizim `reconcile` tamamen bundan geliyor. H1, H2 ve H5 hatalarının tamamı,
kalıcılığın doğal sonucu olarak ortaya çıktı.

Buna karşılık bizim kazancımız da gerçek: process öldüğünde session geçmişi
kaybolmuyor, açılışta kaldığın yerden devam edebiliyorsun.

### Alınmayan en büyük fırsat: OSC 133

kitty ve ghostty shell integration kullanıyor. `C` işareti **komut satırının
kendisini** verir, `D` **çıkış kodunu** verir. Biz `~/Drosh ❯ ls` satırını
regex'le tahmin ediyoruz. Bu tek eksikliğin doğrudan sonuçları:

- karakter karakter blok oluşması
- `exit` yakalanamaması
- REPL (`>>>`) tespitinin belirsiz olması
- hatalı komutların yeşil görünmesi — `BlockEngineWire` çıkış kodunu `0`
  sabitliyor, çünkü elimizde gerçeği yok

Ayrıntılı tasarım: `docs/PHASE-6-ARCHITECTURE.md` ve TODO §Phase 6.

---

## 5. Değişmezler

Bunları bozmadan önce düşün:

1. **Bir session, Room'da bir satırdır.** Satır olmayan session yönetilemez.
2. **Aktif session'ı `TerminalManager` yazar.** Başka yer yazarsa ayrışırlar.
3. **Kapalı session `Closed` işaretlenir**, aksi halde reconcile diriltir.
4. **Session mutasyonları ana thread'de.** `TerminalManager` buna bağımlı.
5. **Görünüm modu session listesine dokunmaz.**
6. **Uygulama açıkken sıfır session olmaz.** Ya bir session vardır ya da çıkış
   kararı verilmektedir. Bu yüzden son-session dialog'u kapatılamaz.

---

## 6. Test sırası

Session değişikliğinden sonra sırayla doğrula:

1. Toolbar'dan session kapat → 2 sn bekle → **geri gelmiyor**
2. Session kapatınca sidebar vurgusu ekrandakiyle aynı
3. Uygulamayı kapat → aç → **son baktığın session** (0 değil)
4. Tüm session'ları sil → dialog → **Delete** → uygulama kapanıyor
5. Tüm session'ları sil → dialog → **New session** → tek session, sidebar'da görünüyor
6. **Tüm session'ları sil → uygulamayı yeniden aç → `Default` açılıyor, siyah ekran yok**
7. Classic ↔ block geçişi → session sayısı değişmiyor
8. Refresh → konum ve block geçmişi korunuyor
9. Ölü session "ended" görünüyor, "clear N ended" çalışıyor

---

## 7. Kalan işler

| Konu | Durum |
|---|---|
| OSC 133 shell integration | Yapılmadı — en büyük kazanç, blok motorundaki hata sınıfının kökü |
| Ölü session'ın scrollback'i | Yapılmadı — mimari değişiklik gerektiriyor |
| Session geçmişi (kapanan satır) zaman aşımı | Elle sweep var, otomatik retention politikası yok |
| Termux'in `exit 0/130` otomatik kapatma kuralı | Uygulanmadı — bizim modelimizde kapalı satır geçmiş olarak tutuluyor, Termux'inkinde tutulmuyor. İkisi çelişiyor; hangisi istendiği bir ürün kararı. |
