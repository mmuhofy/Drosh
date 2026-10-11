# Translation batch — 2026-10-11

Thirty new strings, needed by **de, es, pt, ru, zh, ja, ar, fil** (one set each).
English and Turkish exist already; this is the material for the other eight.

How to return it: copy this table eight times, one per language, and fill the
values — or write `docs/tmp-translations-only-the-missing/strings-XX-missing.xml`
files in the same shape as the last batch and the merge tool wires them in.

## Rules

- **Do not translate** these product terms: `prompt` `rootfs` `proot` `PTY`
  `session` `tab` `pane` `block` `snippet` `agent` `tool` `shell` `IME`
  `DataStore` `overlay` `SSH` `host key` `PEM` `stdin` `stdout` `Esc` `Ctrl`
  `Alt` `PgUp` `PgDn` `BkSPC`
- Placeholders (`%1$s`, `%1$d`) must appear **exactly once, unchanged**
- The ellipsis and `~/.drosh/` stay as they are
- Write natural apostrophes; the merge tool escapes them for XML
- Keep the Turkish length as a guide — settings rows have limited width
- Arabic: natural word order for RTL, placeholders untouched

## The 30 strings

| Key | English | Turkish | Where it appears |
|---|---|---|---|
| `agent_chat_name_help` | This name appears in the Agent Home list and in the session. | Bu ad Agent Home listesinde ve oturumda görünür. | Dialog body under the chat name field. |
| `agent_chat_delete_confirm` | Delete this chat? | Bu sohbet silinsin mi? | Confirmation dialog title for deleting a chat. |
| `agent_rename` | Rename | Yeniden adlandır | Menu row that opens the rename dialog. |
| `editor_unsaved_message` | This file has edits that have not been saved. | Bu dosyada kaydedilmemiş değişiklikler var. | Body of the discard-changes dialog. |
| `setup_failed_message` | Setup failed at: %1$s | Kurulum şu adımda başarısız oldu: %1$s | %1$s is the failed step name. |
| `setup_logs_title` | Last 50 log lines | Son 50 log satırı |  |
| `setup_no_logs` | No logs captured. | Log kaydı yok. |  |
| `setup_retry_sublabel` | Try the same steps again with cached files. | Aynı adımları önbellekteki dosyalarla yeniden dene. | Sublabel of the Retry recovery button. |
| `setup_redownload_label` | Re-download rootfs | Rootfs'yi yeniden indir | Recovery button label. |
| `setup_redownload_sublabel` | Delete cached rootfs and re-download from Ubuntu. | Önbellekteki rootfs'i sil ve Ubuntu'dan yeniden indir. | Sublabel of the Re-download button. |
| `setup_reset_label` | Reset everything | Her şeyi sıfırla | Recovery button label. |
| `setup_reset_sublabel` | Wipe the ~/.drosh/ folder. The next launch downloads everything again. | ~/.drosh/ klasörünü sil. Sonraki açılış her şeyi yeniden indirir. | Sublabel of the Reset button. ~/.drosh/ is a path — leave it. |
| `setup_report_label` | Report issue on GitHub | GitHub'da sorun bildir | Recovery button label. |
| `setup_report_sublabel` | Copies a diagnostic bundle to your clipboard. | Tanılama paketini panoya kopyalar. | Sublabel of the Report button. |
| `setup_diagnostic_copied` | Diagnostic copied to clipboard | Tanılama panoya kopyalandı | Toast after copying the diagnostic bundle. |
| `settings_font_decrease_cd` | Decrease font size | Yazı boyutunu küçült | Screen-reader label for the minus button. |
| `settings_font_increase_cd` | Increase font size | Yazı boyutunu büyüt | Screen-reader label for the plus button. |
| `settings_speed_slow` | Slow | Yavaş | Caption at the left end of the cursor-blink speed slider. |
| `settings_speed_fast` | Fast | Hızlı | Caption at the right end of the slider. |
| `terminal_delete_last_session_title` | Delete the last session? | Son oturum silinsin mi? | Dialog title when deleting the only session. |
| `terminal_delete_last_session_ended_body` | It ended with exit code %1$d. Deleting it closes the app; a new session starts from scratch. | %1$d çıkış koduyla sonlandı. Silmek uygulamayı kapatır; yeni oturum sıfırdan başlar. | Dialog body when the last session already ended. %1$d is the exit code. |
| `terminal_delete_last_session_body` | Deleting it closes the app; a new session starts from scratch. | Silmek uygulamayı kapatır; yeni oturum sıfırdan başlar. | Dialog body when the last session is still running. |
| `terminal_preparing` | Preparing… | Hazırlanıyor… | Bootstrap stage label. |
| `terminal_extracting_rootfs` | Extracting Ubuntu rootfs… | Ubuntu rootfs çıkarılıyor… | Bootstrap stage label. rootfs is a product term — do not translate. |
| `terminal_configuring` | Configuring system… | Sistem yapılandırılıyor… | Bootstrap stage label. |
| `terminal_installing_packages` | Installing packages… | Paketler kuruluyor… | Bootstrap stage label. |
| `terminal_cleaning` | Cleaning up… | Temizleniyor… | Bootstrap stage label. |
| `terminal_ready` | Ready | Hazır | Bootstrap stage label. |
| `terminal_setting_up` | Setting up terminal | Terminal kuruluyor | Heading shown while the terminal sets up. |
| `terminal_setup_failed` | Failed to set up terminal | Terminal kurulumu başarısız | Heading shown when setup fails. |

## Delivery checklist

- [ ] de
- [ ] es
- [ ] pt
- [ ] ru
- [ ] zh
- [ ] ja
- [ ] ar
- [ ] fil
