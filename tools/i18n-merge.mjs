#!/usr/bin/env node
/**
 * Drosh i18n merge — write prepared translations into the locale files.
 *
 *   node tools/i18n-merge.mjs            # merge docs/tmp-translations-only-the-missing/
 *
 * The staging folder holds one file per locale (`strings-XX-missing.xml`) with
 * exactly the strings that locale lacks, section-marked by their target file.
 * This script routes each into the right values-XX/ file and appends it —
 * existing translations are never touched, and a key that is already present
 * is reported and skipped, so the script is safe to re-run.
 *
 * The 19 keys that come out of hardcoded strings (see
 * docs/TRANSLATION-MANIFEST.md) do not exist in any locale file yet, so their
 * English and Turkish values are carried here — the other eight arrive with
 * the staging files' `new keys` section.
 */
import { existsSync, readFileSync, writeFileSync, mkdirSync } from 'node:fs'
import { join, dirname } from 'node:path'
import { fileURLToPath } from 'node:url'

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..')
const SRC = join(ROOT, 'docs/tmp-translations-only-the-missing')
const LOCALES = ['tr', 'de', 'es', 'pt', 'ru', 'zh', 'ja', 'ar', 'fil']

const FILES = {
  'app/strings.xml': l => `android/app/src/main/res/values-${l}/strings.xml`,
  'ui/strings.xml': l => `android/ui/src/main/res/values-${l}/strings.xml`,
  'ui/drosh_strings.xml': l => `android/ui/src/main/res/values-${l}/drosh_strings.xml`,
  'terminal/strings.xml': l => `android/terminal/src/main/res/values-${l}/strings.xml`,
  'editor/strings.xml': l => `android/editor/src/main/res/values-${l}/strings.xml`,
}

/** New keys: English base, Turkish base. The eight arrive from the staging files. */
const NEW_KEYS = [
  ['agent_chat_name_hint', 'Chat name', 'Chat adı', 'ui'],
  ['agent_chat_delete', 'Delete', 'Sil', 'ui'],
  ['action_add', 'Add', 'Ekle', 'ui'],
  ['action_retry', 'Retry', 'Yeniden dene', 'ui'],
  ['action_done', 'Done', 'Tamam', 'ui'],
  ['action_restore_default', 'Restore Default', 'Varsayılanlara dön', 'ui'],
  ['action_delete', 'Delete', 'Sil', 'ui'],
  ['ssh_add_host_title', 'Add host', 'Sunucu ekle', 'ui'],
  ['ssh_name_label', 'Name', 'Ad', 'ui'],
  ['ssh_hostname_label', 'Hostname or IP', 'Sunucu adı veya IP', 'ui'],
  ['ssh_port_label', 'Port', 'Port', 'ui'],
  ['ssh_username_label', 'Username', 'Kullanıcı adı', 'ui'],
  ['ssh_password_label', 'Password', 'Parola', 'ui'],
  ['ssh_private_key_label', 'Private key PEM', 'Özel anahtar PEM', 'ui'],
  ['ssh_passphrase_label', 'Passphrase (optional)', 'Anahtar parolası (isteğe bağlı)', 'ui'],
  ['setup_diagnostic', 'Drosh diagnostic', 'Drosh tanılama', 'ui'],
  ['editor_discard_title', 'Discard changes?', 'Değişiklikler silinsin mi?', 'editor'],
  ['editor_discard_confirm', 'Discard', 'Sil', 'editor'],
  ['editor_keep_editing', 'Keep editing', 'Düzenlemeye devam et', 'editor'],
]

const esc = s => s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
const baseFor = (mod, file, l) =>
  mod === 'editor' && l !== 'en'
    ? `editor/src/main/res/values-${l}/${file}`
    : `${mod}/src/main/res/values${l === 'en' ? '' : '-' + l}/${file}`

/** New keys: the ui ones join drosh_strings.xml (where action_* lives), the editor ones get the module's own file. */
const newKeyTarget = (mod, l) =>
  baseFor(mod, mod === 'ui' ? 'drosh_strings.xml' : 'strings.xml', l)

/** Parse a staging file into (target-section, key, value) entries. */
function parseStaging(text) {
  const out = []
  let section = null
  for (const line of text.split('\n')) {
    const m = line.match(/<!--\s*===\s*(.+?)\s*===\s*-->/)
    if (m) { section = m[1]; continue }
    const s = line.match(/<string name="([^"]+)">(.*)<\/string>\s*$/)
    if (s && section) out.push({ section, name: s[1], value: s[2] })
  }
  return out
}

function targetOf(entry, locale) {
  if (entry.section === 'new keys') {
    const key = NEW_KEYS.find(k => k[0] === entry.name)
    if (!key) throw new Error(`new key ${entry.name} is not in NEW_KEYS`)
    return newKeyTarget(key[3], locale)
  }
  const resolve = FILES[entry.section]
  if (!resolve) throw new Error(`unknown section ${entry.section}`)
  return resolve(locale)
}

const readOrCreate = p => existsSync(p)
  ? readFileSync(p, 'utf8')
  : '<?xml version="1.0" encoding="utf-8"?>\n<resources>\n</resources>\n'

const writeTarget = (p, content) => {
  mkdirSync(dirname(p), { recursive: true })
  writeFileSync(p, content)
}

let added = 0, skipped = 0
for (const locale of LOCALES) {
  const stagingPath = join(SRC, `strings-${locale}-missing.xml`)
  if (!existsSync(stagingPath)) { console.log(`no staging for ${locale}`); continue }
  const entries = parseStaging(readFileSync(stagingPath, 'utf8'))
  const byTarget = new Map()
  for (const e of entries) {
    const t = targetOf(e, locale)
    if (!byTarget.has(t)) byTarget.set(t, [])
    byTarget.get(t).push(e)
  }
  for (const [target, list] of byTarget) {
    let content = readOrCreate(target)
    const fresh = []
    for (const e of list) {
      if (content.includes(`name="${e.name}"`)) { skipped++; continue }
      fresh.push(e)
    }
    if (!fresh.length) continue
    const block = fresh.map(e => `    <string name="${e.name}">${e.value}</string>`).join('\n')
    content = content.replace(/<\/resources>\s*$/, block + '\n</resources>\n')
    writeTarget(target, content)
    added += fresh.length
    console.log(`${locale}: +${fresh.length} → ${target.replace(ROOT + '/', '')}`)
  }
}

// English and Turkish bases for the new keys (the other eight come from above).
for (const [name, en, tr, mod] of NEW_KEYS) {
  for (const [locale, value] of [['en', en], ['tr', tr]]) {
    const target = newKeyTarget(mod, locale)
    let content = readOrCreate(target)
    if (content.includes(`name="${name}"`)) continue
    writeTarget(target, content.replace(
      /<\/resources>\s*$/,
      `    <string name="${name}">${esc(value)}</string>\n</resources>\n`,
    ))
    added++
    console.log(`${locale}: +1 (base) → ${target.replace(ROOT + '/', '')}`)
  }
}

console.log(`\n${added} added, ${skipped} already present`)
