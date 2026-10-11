# Translation manifest — Drosh

Every string key in the repository, its English value, and which languages
still need it. Generated from the code on 2026-10-11 (the docs were stale).
Regenerate with `tools/i18n-coverage.sh --manifest` — this file is derived,
not edited.

## Coverage

280 string keys: `app/strings.xml` 46 · `ui/strings.xml` 38 ·
`ui/drosh_strings.xml` 193 · `terminal/strings.xml` 3.

| Language | Translated | Missing |
|---|---|---|
| en (default) | 280 | 0 |
| tr | 264 | 16 (13 app overlay/permission + 3 terminal) |
| de, es, pt, ru, zh, ja, ar, fil | 55 each | **225 each** |
| fr | 0 | no `values-fr` exists — the picker entry is a lie, drop it |

## For the translator

Do not translate these product terms: `prompt` `rootfs` `proot` `PTY`
`session` `tab` `pane` `block` `snippet` `agent` `tool` `shell` `IME`
`DataStore` `overlay` `SSH` `host key` `PEM` `stdin` `stdout` `Esc` `Ctrl`
`Alt` `PgUp` `PgDn` `BkSPC`. Keep placeholders (`%1$s`, `%1$d`) exactly.

# Translation manifest

Regenerate with `tools/i18n-coverage.sh --manifest`. Do not hand-edit.

## app — strings.xml

| Key | English | tr | de | es | pt | ru | zh | ja | ar | fil |
|---|---|:-:|:-:|:-:|:-:|:-:|:-:|:-:|:-:|:-:|
| `app_name` | Drosh | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| `notification_action_exit` | Exit | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| `notification_session_count` | 1 active session | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| `overlay_channel_name` | Floating pane | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** |
| `overlay_notification_stop` | Stop | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** |
| `overlay_notification_text` | Tap to open, or stop to bring the pane back | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** |
| `overlay_notification_title` | Pane floating over other apps | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** |
| `overlay_pane_close` | Close floating pane | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** |
| `overlay_pane_title` | Floating pane | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** |
| `overlay_permission_grant` | Open settings | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** |
| `overlay_permission_rationale` | A pane can only float over other apps if Drosh is allowed to draw over them. Nothing leaves your device. | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** |
| `overlay_permission_title` | Let Drosh draw over other apps | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** |
| `permission_read_command_state_description` | Allows Drosh Keyboard to see whether a terminal command is running and how it ended. | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** |
| `permission_read_command_state_label` | Read terminal command state | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** |
| `permission_send_keyboard_mode_description` | Allows Drosh Keyboard to tell Drosh whether it is docked or floating, so the layout reserves space only while the keyboard is docked. | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** |
| `permission_send_keyboard_mode_label` | Report keyboard placement | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** |
| `pin_entry_subtitle` | Enter a new 4-digit PIN | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| `pin_entry_title` | Set PIN | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| `settings_about_section` | About | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| `settings_app_lock` | App Lock (PIN) | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| `settings_app_lock_description` | Require PIN on launch | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| `settings_back_content_description` | Back | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| `settings_cursor_blink_description` | Pulse interval | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| `settings_cursor_blink_rate` | Cursor Blink Rate | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| `settings_cursor_style` | Cursor Style | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| `settings_cursor_style_beam` | Beam | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| `settings_cursor_style_block` | Block | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| `settings_cursor_style_underline` | Underline | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| `settings_default_build_description` | Advanced terminal | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| `settings_description` | Description | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| `settings_external_body` | settings.toml was edited outside the app. The new values are live; some of them take full effect after a restart. | ✓ | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** |
| `settings_external_ok` | OK | ✓ | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** |
| `settings_external_restart` | Restart | ✓ | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** |
| `settings_external_title` | Settings changed | ✓ | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** |
| `settings_font_size` | Font Size | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| `settings_language_system` | System | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| `settings_language_title` | Language | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| `settings_license` | License | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| `settings_made_by` | Made by Muhofy | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| `settings_proot_start_command` | PRoot Start Command | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| `settings_proot_start_description` | Experimental — changing this can break sessions | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| `settings_security_section` | Security | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| `settings_terminal_section` | Terminal | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| `settings_version` | Version | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| `terminal_service_channel_name` | Drosh Terminal | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| `terminal_service_name` | Drosh Terminal | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |

## ui — drosh_strings.xml

| Key | English | tr | de | es | pt | ru | zh | ja | ar | fil |
|---|---|:-:|:-:|:-:|:-:|:-:|:-:|:-:|:-:|:-:|
| `action_back` | Back | ✓ | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** |
| `action_cancel` | Cancel | ✓ | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** |
| `action_save` | Save | ✓ | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** |
| `settings_agent` | Agent | ✓ | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** |
| `settings_agent_desc` | OpenRouter key, Exa search key and model | ✓ | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** |
| `settings_background_blur` | Blur | ✓ | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** |
| `settings_background_blur_off` | Off | ✓ | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** |
| `settings_background_blur_value` | Radius %1$d | ✓ | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** |
| `settings_background_image` | Background image | ✓ | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** |
| `settings_background_none` | None — the theme colour shows | ✓ | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** |
| `settings_background_opacity` | Image opacity | ✓ | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** |
| `settings_background_opacity_value` | %1$d%% | ✓ | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** |
| `settings_background_remove` | Remove background image | ✓ | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** |
| `settings_background_remove_desc` | Delete the file from .drosh | ✓ | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** |
| `settings_bold_brightens` | Bold brightens | ✓ | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** |
| `settings_bold_brightens_desc` | Bold text in the first eight colours uses the bright half | ✓ | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** |
| `settings_build` | Build | ✓ | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** |
| `settings_build_copied` | Build copied | ✓ | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** |
| `settings_category_about` | About | ✓ | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** |
| `settings_category_appearance` | Appearance | ✓ | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** |
| `settings_category_editor` | Editor | ✓ | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** |
| `settings_category_keyboard` | Keyboard &amp; Input | ✓ | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** |
| `settings_category_language` | Language | ✓ | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** |
| `settings_category_security` | Security | ✓ | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** |
| `settings_category_shell` | Shell &amp; Rootfs | ✓ | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** |
| `settings_category_terminal` | Terminal | ✓ | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** |
| `settings_content_corners` | Corner radius | ✓ | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** |
| `settings_content_corners_value` | %1$ddp | ✓ | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** |
| `settings_content_padding` | Content padding | ✓ | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** |
| `settings_content_padding_value` | %1$ddp | ✓ | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** |
| `settings_cursor_blink` | Cursor blink | ✓ | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** |
| `settings_cursor_blink_off` | Off | ✓ | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** |
| `settings_cursor_blink_value` | %1$dms | ✓ | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** |
| `settings_cursor_style_beam_desc` | A thin vertical bar | ✓ | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** |
| `settings_cursor_style_block_desc` | A solid block | ✓ | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** |
| `settings_cursor_style_thin` | Thin block | ✓ | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** |
| `settings_cursor_style_underline_desc` | An underline under the character | ✓ | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** |
| `settings_dialog_cancel` | Cancel | ✓ | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** |
| `settings_dialog_save` | Save | ✓ | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** |
| `settings_dim_inactive_panes` | Dim inactive pane | ✓ | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** |
| `settings_dim_inactive_panes_desc` | The unfocused pane of a split is drawn dimmer | ✓ | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** |
| `settings_editor_font` | Font size | ✓ | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** |
| `settings_editor_line_numbers` | Line numbers | ✓ | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** |
| `settings_editor_line_numbers_desc` | Show them down the left edge | ✓ | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** |
| `settings_editor_tab_width` | Tab width | ✓ | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** |
| `settings_editor_word_wrap` | Word wrap | ✓ | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** |
| `settings_editor_word_wrap_desc` | Wrap long lines instead of scrolling | ✓ | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** |
| `settings_font` | Font | ✓ | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** |
| `settings_font_geist` | Geist | ✓ | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** |
| `settings_font_geist_desc` | Geist Sans with Geist Mono | ✓ | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** |
| `settings_font_inter` | Inter | ✓ | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** |
| `settings_font_inter_desc` | Inter with JetBrains Mono | ✓ | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** |
| `settings_font_size_value` | %1$ssp | ✓ | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** |
| `settings_input_drosh_ime` | Hide bar on Drosh Keyboard | ✓ | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** |
| `settings_input_drosh_ime_desc` | Drosh Keyboard has its own key row | ✓ | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** |
| `settings_input_extra_keys` | Extra-keys bar | ✓ | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** |
| `settings_input_extra_keys_desc` | Show the key row above the keyboard | ✓ | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** |
| `settings_input_hardware_keyboard` | Hide bar on hardware keyboard | ✓ | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** |
| `settings_input_hardware_keyboard_desc` | A physical keyboard brings its own keys | ✓ | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** |
| `settings_language` | Language | ✓ | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** |
| `settings_language_search` | Search languages | ✓ | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** |
| `settings_ligatures` | Ligatures | ✓ | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** |
| `settings_ligatures_desc` | Programming ligatures where the typeface carries them | ✓ | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** |
| `settings_list_count` | %1$d items | ✓ | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** |
| `settings_list_empty` | None | ✓ | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** |
| `settings_mode_block` | Block | ✓ | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** |
| `settings_mode_terminal` | Terminal | ✓ | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** |
| `settings_motd_card` | Card | ✓ | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** |
| `settings_motd_compose_desc` | Rendered as an interactive card | ✓ | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** |
| `settings_motd_default` | Welcome to Drosh | ✓ | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** |
| `settings_motd_disabled_desc` | Do not show it | ✓ | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** |
| `settings_motd_empty` | Tap to edit | ✓ | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** |
| `settings_motd_message` | Message | ✓ | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** |
| `settings_motd_off` | Off | ✓ | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** |
| `settings_motd_plain_desc` | The shell echoes the text | ✓ | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** |
| `settings_motd_show_as` | Show as | ✓ | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** |
| `settings_pin_lock` | PIN lock | ✓ | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** |
| `settings_pin_lock_desc` | Ask for a PIN before opening a session | ✓ | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** |
| `settings_pin_subtitle` | You will be asked for it before a session opens. | ✓ | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** |
| `settings_pin_title` | Set a PIN | ✓ | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** |
| `settings_projects` | Projects | ✓ | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** |
| `settings_projects_desc` | Group sessions by project | ✓ | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** |
| `settings_rootfs_dedit` | Install dedit | ✓ | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** |
| `settings_rootfs_dedit_desc` | The in-terminal editor package | ✓ | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** |
| `settings_rootfs_extra_packages` | Extra packages | ✓ | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** |
| `settings_rootfs_extra_packages_hint` | One package per line | ✓ | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** |
| `settings_rootfs_max_edit` | Max edit size | ✓ | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** |
| `settings_rootfs_max_edit_value` | %1$d KB | ✓ | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** |
| `settings_rootfs_optimize` | Optimize on setup | ✓ | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** |
| `settings_rootfs_optimize_desc` | Clean apt caches after install | ✓ | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** |
| `settings_rootfs_profile` | Package profile | ✓ | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** |
| `settings_rootfs_profile_custom` | Custom | ✓ | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** |
| `settings_rootfs_profile_full` | Full | ✓ | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** |
| `settings_rootfs_profile_minimal` | Minimal | ✓ | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** |
| `settings_rootfs_profile_standard` | Standard | ✓ | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** |
| `settings_section_about` | About | ✓ | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** |
| `settings_section_ai` | AI | ✓ | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** |
| `settings_section_appearance` | Appearance | ✓ | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** |
| `settings_section_background` | Background | ✓ | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** |
| `settings_section_custom` | Custom | ✓ | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** |
| `settings_section_editor` | Editor | ✓ | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** |
| `settings_section_keyboard` | Input | ✓ | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** |
| `settings_section_language` | Language | ✓ | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** |
| `settings_section_motd` | Message of the day | ✓ | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** |
| `settings_section_projects` | Projects | ✓ | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** |
| `settings_section_rootfs` | Rootfs | ✓ | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** |
| `settings_section_security` | Security | ✓ | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** |
| `settings_section_shell` | Shell | ✓ | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** |
| `settings_section_startup` | Startup | ✓ | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** |
| `settings_section_terminal` | Terminal | ✓ | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** |
| `settings_security_auto_lock` | Auto-lock | ✓ | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** |
| `settings_security_auto_lock_fifteen_minutes` | After 15 minutes | ✓ | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** |
| `settings_security_auto_lock_five_minutes` | After 5 minutes | ✓ | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** |
| `settings_security_auto_lock_immediately` | Immediately | ✓ | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** |
| `settings_security_auto_lock_never` | Never | ✓ | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** |
| `settings_security_auto_lock_one_minute` | After 1 minute | ✓ | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** |
| `settings_security_auto_lock_thirty_minutes` | After 30 minutes | ✓ | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** |
| `settings_security_biometric` | Biometric unlock | ✓ | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** |
| `settings_security_biometric_desc` | Unlock with a fingerprint when one is enrolled | ✓ | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** |
| `settings_security_clipboard` | Mark clipboard sensitive | ✓ | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** |
| `settings_security_clipboard_desc` | Hide copied text from the clipboard preview | ✓ | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** |
| `settings_security_pin_length` | PIN length | ✓ | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** |
| `settings_security_screenshots` | Block screenshots | ✓ | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** |
| `settings_security_screenshots_desc` | Hide the app from the recents switcher too | ✓ | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** |
| `settings_shell_bash` | bash | ✓ | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** |
| `settings_shell_choice` | Shell | ✓ | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** |
| `settings_shell_choice_desc` | The shell new sessions open in | ✓ | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** |
| `settings_shell_history` | History size | ✓ | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** |
| `settings_shell_history_value` | %1$d lines | ✓ | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** |
| `settings_shell_integration` | Shell integration | ✓ | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** |
| `settings_shell_integration_desc` | OSC 133/1337 marks and the editor command | ✓ | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** |
| `settings_shell_omz_plugins` | Oh My Zsh plugins | ✓ | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** |
| `settings_shell_omz_plugins_hint` | One plugin per line | ✓ | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** |
| `settings_shell_omz_theme` | Oh My Zsh theme | ✓ | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** |
| `settings_shell_zsh` | zsh | ✓ | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** |
| `settings_startup_command` | Startup command | ✓ | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** |
| `settings_startup_default` | $shell --login | ✓ | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** |
| `settings_summary_off` | Off | ✓ | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** |
| `settings_summary_on` | On | ✓ | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** |
| `settings_summary_terminal` | %1$s · %2$ssp | ✓ | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** |
| `settings_terminal_bell` | Bell | ✓ | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** |
| `settings_terminal_bell_none` | Off | ✓ | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** |
| `settings_terminal_bell_sound` | Sound | ✓ | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** |
| `settings_terminal_bell_vibrate` | Vibrate | ✓ | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** |
| `settings_terminal_glass_effects` | Glass effects | ✓ | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** |
| `settings_terminal_glass_effects_desc` | Blur and translucency in the top bar | ✓ | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** |
| `settings_terminal_immersive` | Auto-hide chrome | ✓ | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** |
| `settings_terminal_immersive_desc` | Go fullscreen in the scrollback | ✓ | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** |
| `settings_terminal_keep_awake` | Keep screen on | ✓ | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** |
| `settings_terminal_keep_awake_desc` | While a terminal is on screen | ✓ | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** |
| `settings_terminal_link_detection` | Link detection | ✓ | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** |
| `settings_terminal_link_detection_desc` | Underline URLs as they scroll past | ✓ | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** |
| `settings_terminal_mode` | Terminal mode | ✓ | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** |
| `settings_terminal_mode_desc` | How a command and its output are laid out | ✓ | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** |
| `settings_terminal_scrollback` | Scrollback | ✓ | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** |
| `settings_terminal_scrollback_value` | %1$d rows | ✓ | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** |
| `settings_theme` | Theme | ✓ | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** |
| `settings_theme_active` | Active theme | ✓ | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** |
| `settings_theme_active_none` | Drosh default | ✓ | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** |
| `settings_theme_author` | Author | ✓ | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** |
| `settings_theme_auto` | Auto | ✓ | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** |
| `settings_theme_available` | In the store | ✓ | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** |
| `settings_theme_base` | Start from | ✓ | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** |
| `settings_theme_create` | Create your own | ✓ | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** |
| `settings_theme_create_desc` | Start from an installed theme, then edit the file | ✓ | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** |
| `settings_theme_dark` | Dark | ✓ | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** |
| `settings_theme_dark_desc` | Always dark | ✓ | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** |
| `settings_theme_description` | Description | ✓ | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** |
| `settings_theme_from_url` | Install from URL | ✓ | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** |
| `settings_theme_from_url_desc` | A theme .toml anywhere it is served | ✓ | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** |
| `settings_theme_installed` | Installed | ✓ | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** |
| `settings_theme_in_use` | In use | ✓ | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** |
| `settings_theme_light` | Light | ✓ | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** |
| `settings_theme_light_desc` | Always light | ✓ | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** |
| `settings_theme_loading` | Loading… | ✓ | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** |
| `settings_theme_name` | Name | ✓ | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** |
| `settings_theme_no_description` | No description | ✓ | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** |
| `settings_theme_refresh` | Refresh the index | ✓ | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** |
| `settings_theme_store` | Theme Store | ✓ | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** |
| `settings_theme_store_desc` | 120 themes from drosh-themes, or install from a URL | ✓ | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** |
| `settings_theme_store_section` | Store | ✓ | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** |
| `settings_theme_system_desc` | Follows the system setting | ✓ | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** |
| `this_device` | This device | ✓ | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** |
| `topbar_close_session` | Close session | ✓ | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** |
| `topbar_enter_fullscreen` | Enter fullscreen | ✓ | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** |
| `topbar_exit_fullscreen` | Exit fullscreen | ✓ | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** |
| `topbar_find` | Find in output | ✓ | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** |
| `topbar_hide_keyboard` | Hide keyboard | ✓ | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** |
| `topbar_more_actions` | More actions | ✓ | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** |
| `topbar_new_session` | New session | ✓ | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** |
| `topbar_open_agent` | AI Agent | ✓ | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** |
| `topbar_refresh` | Refresh terminal | ✓ | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** |
| `topbar_show_keyboard` | Show keyboard | ✓ | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** |

## ui — strings.xml

| Key | English | tr | de | es | pt | ru | zh | ja | ar | fil |
|---|---|:-:|:-:|:-:|:-:|:-:|:-:|:-:|:-:|:-:|
| `pin_entry_subtitle` | Enter a new 4-digit PIN | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| `pin_entry_title` | Set PIN | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| `settings_about_section` | About | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| `settings_app_lock` | App Lock (PIN) | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| `settings_app_lock_description` | Require PIN on launch | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| `settings_back_content_description` | Back | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| `settings_cursor_blink_description` | Pulse interval | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| `settings_cursor_blink_rate` | Cursor Blink Rate | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| `settings_cursor_style` | Cursor Style | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| `settings_cursor_style_beam` | Beam | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| `settings_cursor_style_block` | Block | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| `settings_cursor_style_underline` | Underline | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| `settings_default_build_description` | Advanced terminal | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| `settings_description` | Description | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| `settings_font_size` | Default Font Size | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| `settings_language_auto_summary` | Follow system locale | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| `settings_language_system` | System | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| `settings_language_title` | Language | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| `settings_license` | License | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| `settings_made_by` | Made by Muhofy | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| `settings_motd_dialog_done` | Done | ✓ | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** |
| `settings_motd_dialog_title` | Edit Greeting Text | ✓ | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** |
| `settings_motd_edit` | Edit Greeting | ✓ | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** |
| `settings_motd_mode` | Display Mode | ✓ | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** |
| `settings_motd_mode_compose` | Widget | ✓ | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** |
| `settings_motd_mode_disabled` | Disabled | ✓ | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** |
| `settings_motd_mode_plaintext` | Plain Text | ✓ | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** |
| `settings_motd_preview` | Preview | ✓ | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** |
| `settings_motd_restore_default` | Restore Default | ✓ | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** |
| `settings_motd_section` | MOTD (Greeting) | ✓ | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** |
| `settings_motd_text` | Greeting Text | ✓ | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** |
| `settings_motd_text_hint` | Enter custom greeting text...\nUse \n for new lines | ✓ | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** |
| `settings_proot_start_command` | PRoot Start Command | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| `settings_proot_start_description` | Experimental — changing this can break sessions | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| `settings_security_section` | Security | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| `settings_terminal_section` | Terminal | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| `settings_title` | Settings | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| `settings_version` | Version | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |

## terminal — strings.xml

| Key | English | tr | de | es | pt | ru | zh | ja | ar | fil |
|---|---|:-:|:-:|:-:|:-:|:-:|:-:|:-:|:-:|:-:|
| `copy_text` | Copy | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** |
| `paste_text` | Paste | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** |
| `text_selection_more` | More… | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** | **✗** |


## New keys — hardcoded strings becoming resources

18 new keys (10 values each: en + tr + the eight). `action_save`,
`action_cancel`, `action_back` and `settings_startup_command` already exist in
`drosh_strings.xml` — those need wiring, not inventing.

| Key | EN | tr |
|---|---|---|
| `agent_chat_name_hint` | Chat name | Chat adı |
| `agent_chat_delete` | Delete | Sil |
| `action_add` | Add | Ekle |
| `action_retry` | Retry | Yeniden dene |
| `action_done` | Done | Tamam |
| `action_restore_default` | Restore Default | Varsayılanlara dön |
| `action_delete` | Delete | Sil |
| `ssh_add_host_title` | Add host | Sunucu ekle |
| `ssh_name_label` | Name | Ad |
| `ssh_hostname_label` | Hostname or IP | Sunucu adı veya IP |
| `ssh_port_label` | Port | Port |
| `ssh_username_label` | Username | Kullanıcı adı |
| `ssh_password_label` | Password | Parola |
| `ssh_private_key_label` | Private key PEM | Özel anahtar PEM |
| `ssh_passphrase_label` | Passphrase (optional) | Anahtar parolası (isteğe bağlı) |
| `setup_diagnostic` | Drosh diagnostic | Drosh tanılama |
| `editor_discard_title` | Discard changes? | Değişiklikler silinsin mi? |
| `editor_discard_confirm` | Discard | Sil |
| `editor_keep_editing` | Keep editing | Düzenlemeye devam et |
