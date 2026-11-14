# Session Sistemi — Durum Notu

_Bu not, session sisteminin bugün nasıl çalıştığını anlatır: kayıt, canlı
session ve pane ayrı katmanlardır; biri diğerini sahiplenmez._

---

## 1. Üç katman

| Katman | Nerede | Kim yazar | Ömrü |
|---|---|---|---|
| **Kayıt** | Room (`SessionSnapshot`) | repository | uygulama kapanana kadar |
| **Canlı session** | `TerminalManager.liveSessions` | manager | süreç yaşadığı sürece |
| **Pane** | `GhosttyTerminal` + `TerminalSurface` | ekran | ekranda göründüğü sürece |

Bunları karıştırmak, sistemin tarihindeki en pahalı hataydı: **pane kapanınca
session'ın da kapanması.** Bugün pane kapanmak yalnızca *görüntüyü* bırakır;
session çalışmaya devam eder, çıktısı gelmeye devam eder ve scrollback'i
büyür. Izgara bunu "Arka planda" rozetiyle söyler.

**Pane oturumu sahiplenmez.** Pane, bir oturumu *gösterir*; oturumun sahibi
manager'dır. Bu yüzden aynı oturum ızgaradan açılabilir, ikinci bir pane'de
görünebilir ve pane kapandığında hâlâ çalışabilir.

---

## 2. Yaşam döngüsü

```
ızgara / çizgi  →  activate(id)
                        ↓
        kayıt var mı?  ── hayır ──▶ yeni kayıt + spawn
                        │ evet
                        ↓
        session canlı mı? ── evet ──▶ pane oturuma bağlanır (yeniden bağlanma)
                        │ hayır
                        ↓
        kayıttan geri getir (komut yeniden başlar, scrollback boş)
                        ↓
              canlı session  →  liveSessions[id]
                        ↓
        pane kapanır  →  session arka planda (çalışmaya devam)
                        ↓
        süreç ölür  →  kayıt Closed olur
```

### Geri getirme

Bir oturum öldüğünde kaydı kalır (id, ad, komut, çalışma dizini). Izgaradan
tekrar açıldığında **yeniden başlatılır**: komut yeniden çalışır, scrollback
boştur. Kaydın kendisi geri gelmez — sadece oturum yeniden doğar.

### Arka plan

`TerminalManager.backgroundSessions` yayınlar: çalışan ama hiçbir pane'de
görünmeyen oturumların id'leri. Kural: bir pane bir oturumu gösteriyorsa o
oturum **ön plandadır**; gösterilmeyen her canlı oturum **arka plandadır**.

---

## 3. Değişmezler

- **`persistentId` null olamaz.** Kimliksiz bir oturumun Room satırı yoktur;
  reconcile onu yönetmez, kapatamaz, geri yükleyemez ve ızgarada görünmez.
- **`TerminalManager` tek yazıcı.** Aktif oturum hem bellekte hem diskte
  tutuluyordu; ikisi ayrı ayrı güncelleniyordu. Artık manager tek yazıcı,
  DataStore yalnızca okuyor.
- **Session adı kullanıcının.** Programın başlığı (`OSC 0/2`) oturumun adını
  değiştirmez; ayrı bir alanda yaşar.
- **Oturum adı varsayılan olarak sürecin adıdır.** `/proc/<pid>/comm`'dan
  okunur (`zsh`, `bash`); yalnızca uygulamanın ürettiği bir ad yerine yazılır,
  kullanıcının verdiği ad korunur.

---

## 4. Bilinen tuzaklar

- **Kayıt canlılık demek değildir.** Room'da "Running" yazan bir oturum,
  süreci ölmüş olabilir (uygulama çöktüğünde). Izgaradan açmak, yeniden
  başlatır.
- **Pane kapanınca süreç yaşamaya devam eder.** Bu, istenen bir davranış;
  ama bir oturumu kapatmak isteyen kullanıcı, oturumu değil *pane'i* kapatmış
  olur. Izgaradaki kart kapatma eylemi oturumu sonlandırır.
- **Kaydırma, oturumun kendi hâlidir.** Oturum canlıyken izgara yeni bir
  oturum açsa bile eskisi çalışmaya devam eder; ekran kaynakları sınırlı
  olduğu için çok sayıda oturum biriktirmek pil ve bellek maliyetidir.
- **Scrollback bir oturumun parçasıdır.** Oturum yeniden başlatıldığında
  scrollback kaybolur; bu yüzden "geri getirme" bir *restore* değil, bir
  *restart*'tır.

---

## 5. Karşılaştırma

| | Termux | kitty | Drosh |
|---|---|---|---|
| Oturum pane kapanınca | yaşar | yaşar | yaşar |
| Izgara / sekme listesi | sekmeler | sekmeler | oturum kartları + pane'ler |
| Ölü oturumun geri gelmesi | yok | yok | kayıttan yeniden başlatma |
| Arka plan göstergesi | yok | yok | ızgarada rozet |

---

## 6. Test sırası

Session mantığı için izlenecek sıra: manager'ın birim testleri (canlı
oturumların yaşam döngüsü, arka plan yayını), repository'nin reconcile testleri
(Room ↔ canlı oturum), ve cihazda: oturum aç → pane kapat → ızgara "Arka planda"
gösteriyor → tekrar aç → oturum yeniden bağlanıyor.
