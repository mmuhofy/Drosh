#!/usr/bin/env node
/**
 * Drosh i18n tool — coverage, translation and validation for string resources.
 *
 *   node tools/i18n.mjs report                          # coverage table
 *   node tools/i18n.mjs check                           # CI gate: placeholder parity
 *   node tools/i18n.mjs translate [--lang=de,es] [--dry]
 *
 * Translation goes through OpenRouter (any chat-completions model; Gemma by
 * default) and writes the missing keys straight into the values-XX/ files.
 *
 * ## Why a tool and not a translation service
 *
 * The repo once carried a CSV → generate pipeline that was lost, and every
 * string since has been en+tr by hand, so eight locales drifted to ~19%
 * coverage. This closes the gap in one pass and then *guards* it: `check`
 * fails a build when a locale and its base disagree on placeholders, which
 * is the failure mode a hand-edited XML cannot catch (a %1$s that becomes a
 * translated sentence drops the argument and a crash follows).
 *
 * ## What it will not do
 *
 * Trust the model blindly. Every translated key is validated — placeholder
 * multiset must match the source exactly, apostrophes must arrive escaped,
 * the response must be parseable JSON with every requested key present — and
 * a chunk that fails after three attempts stops the run rather than writing
 * a partial file. The output says "machine-translated" because it is; a
 * human review pass is what turns it into a good translation.
 */

import { existsSync, readFileSync, readdirSync, writeFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..')
/** The Gradle modules live under android/; `module` is only ever a module name. */
const MODULES = join(ROOT, 'android')

/** The resource files that carry UI text, per module. */
const TARGETS = [
  { module: 'app', file: 'strings.xml' },
  { module: 'ui', file: 'strings.xml' },
  { module: 'ui', file: 'drosh_strings.xml' },
  { module: 'terminal', file: 'strings.xml' },
  { module: 'editor', file: 'strings.xml' },
]

/**
 * The locales the project ships. `fr` is absent on purpose: its translations
 * were removed after an XML escaping failure (147155cd) and the catalog still
 * offered it, so a French user silently got English. Removing the picker entry
 * is the honest half; this file does not resurrect the other half.
 */
const LOCALES = ['tr', 'de', 'es', 'pt', 'ru', 'zh', 'ja', 'ar', 'fil']

const LANG_NAMES = {
  tr: ['Turkish', 'Türkçe'],
  de: ['German', 'Deutsch'],
  es: ['Spanish', 'Español'],
  pt: ['Portuguese', 'Português'],
  ru: ['Russian', 'Русский'],
  zh: ['Chinese (Simplified)', '简体中文'],
  ja: ['Japanese', '日本語'],
  ar: ['Arabic', 'العربية'],
  fil: ['Filipino', 'Filipino'],
}

/**
 * Android plural quantities per language, from CLDR. Getting this wrong shows
 * "1 files" in Russian (which has one/few/many/other), so the tool asks for
 * exactly the forms the language needs and no others.
 */
const PLURAL_FORMS = {
  tr: ['one', 'other'],
  de: ['one', 'other'],
  es: ['one', 'other'],
  pt: ['one', 'other'],
  zh: ['other'],
  ja: ['other'],
  fil: ['one', 'other'],
  ru: ['one', 'few', 'many', 'other'],
  ar: ['zero', 'one', 'two', 'few', 'many', 'other'],
}

/**
 * The quantities a locale's plural entry must carry — CLDR, not the base file.
 * Japanese and Chinese have "other" only, and counting them as missing an
 * English-style "one" is how a tool talks you into breaking a correct file.
 * Forms the base does not have (Russian's few/many) are translated from the
 * base's "other" by `pluralSources`.
 */
function expectedForms(locale, baseItems) {
  return PLURAL_FORMS[locale] ?? [...(baseItems?.keys() ?? ['one', 'other'])]
}

/** What to translate from, per expected form: the base's own form, or "other". */
function pluralSources(locale, baseItems) {
  const map = new Map()
  for (const q of expectedForms(locale, baseItems)) {
    map.set(q, baseItems?.get(q) ?? baseItems?.get('other') ?? baseItems?.get('one') ?? '')
  }
  return map
}

/** Terms that must survive translation unchanged — they are identifiers. */
const KEEP_AS_IS = [
  'PRoot', 'proot', 'rootfs', 'PTY', 'SSH', 'SSHJ', 'OSC', 'OSC 133', 'PEM',
  'IP', 'hostname', 'Gemma', 'WebViewSheet', 'Mosh', 'SFTP', 'Drosh Keyboard',
  'FlatKeyBar', 'settings.toml', 'zsh', 'Zsh', 'bash', 'fish',
]

const MODEL = process.env.OPENROUTER_MODEL || 'google/gemma-4-31b-it'
const API = 'https://openrouter.ai/api/v1/chat/completions'
const CHUNK = 40
const MAX_ATTEMPTS = 3

// ── resource parsing ────────────────────────────────────────────────────────

/**
 * Parse a strings.xml into ordered entries. The files are ours and simple —
 * one <string> or <plurals> per line — so a line-oriented parser is both
 * enough and honest about the format it supports.
 */
function parse(xml) {
  const strings = new Map()
  const plurals = new Map()
  let current = null
  for (const line of xml.split('\n')) {
    const s = line.match(/<string name="([^"]+)"[^>]*>(.*)<\/string>/)
    if (s) {
      strings.set(s[1], s[2])
      current = null
      continue
    }
    const p = line.match(/<plurals name="([^"]+)">/)
    if (p) {
      current = { name: p[1], items: new Map() }
      plurals.set(p[1], current)
      continue
    }
    const i = current && line.match(/<item quantity="([^"]+)">(.*)<\/item>/)
    if (i) current.items.set(i[1], i[2])
  }
  return { strings, plurals }
}

function readTarget(module, file, locale) {
  const dir = locale
    ? join(MODULES, module, 'src/main/res', `values-${locale}`)
    : join(MODULES, module, 'src/main/res', 'values')
  const path = join(dir, file)
  return existsSync(path) ? parse(readFileSync(path, 'utf8')) : null
}

/** Unescape an Android string value to plain text for translation work. */
function unescape(v) {
  return v.replace(/\\'/g, "'").replace(/\\n/g, '\n').replace(/\\\\/g, '\\')
}

/** Plain text → Android-legal XML value. */
function escape(v) {
  return v
    .replace(/\\/g, '\\\\')
    .replace(/\r?\n/g, '\\n')
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/'/g, "\\'")
}

/** Format placeholders in a value — %1$s, %1$d, %s, %d. */
function placeholders(v) {
  return (unescape(v).match(/%\d+\$[sdf]|%[sdf]/g) || []).sort().join(',')
}

// ── inventory ───────────────────────────────────────────────────────────────

function inventory() {
  const rows = []
  for (const t of TARGETS) {
    const base = readTarget(t.module, t.file, null)
    if (!base) throw new Error(`base missing: ${t.module}/values/${t.file}`)
    for (const locale of LOCALES) {
      const loc = readTarget(t.module, t.file, locale)
      const missingStrings = [...base.strings.keys()].filter(
        (k) => !loc?.strings.has(k),
      )
      const missingPlurals = [...base.plurals.entries()].filter(([k, v]) => {
        const got = loc?.plurals.get(k)
        return !got || expectedForms(locale, v.items).some((q) => !got.items.has(q))
      })
      rows.push({
        locale,
        module: t.module,
        file: t.file,
        base,
        loc,
        missingStrings,
        missingPlurals,
      })
    }
  }
  return rows
}

// ── report ──────────────────────────────────────────────────────────────────

function report() {
  const rows = inventory()
  console.log('\nDrosh i18n coverage — base: en (values/)\n')
  console.log('locale  ' + TARGETS.map((t) => `${t.module}/${t.file}`.padEnd(26)).join(''))
  for (const locale of LOCALES) {
    const cells = TARGETS.map((t) => {
      const r = rows.find((x) => x.locale === locale && x.module === t.module && x.file === t.file)
      const total = r.base.strings.size + r.base.plurals.size
      const missing = r.missingStrings.length + r.missingPlurals.length
      const pct = Math.round(((total - missing) / total) * 100)
      return `${pct}% (${missing} eksik)`.padEnd(26)
    })
    console.log(`${locale.padEnd(8)}${cells.join('')}`)
  }
  const totalMissing = rows.reduce((n, r) => n + r.missingStrings.length + r.missingPlurals.length, 0)
  const totalKeys = rows.reduce((n, r) => n + r.base.strings.size + r.base.plurals.size, 0)
  console.log(`\nToplam: ${totalKeys} anahtar/dil potansiyeli, ${rows.length} dil-dosya çifti, ${totalMissing} eksik giriş.\n`)
  const needsStrings = rows.reduce((n, r) => n + r.missingStrings.length, 0)
  const needsPlurals = rows.reduce((n, r) => n + r.missingPlurals.length, 0)
  console.log(`Çevrilecek: ${needsStrings} string + ${needsPlurals} plurals ≈ ${Math.ceil((needsStrings + needsPlurals * 3) / CHUNK)} API isteği (${CHUNK} anahtar/istek).`)
  if (rows.every((r) => r.missingStrings.length === 0 && r.missingPlurals.length === 0)) {
    console.log('Eksik yok — her şey çevrili.\n')
  }
}

// ── check ───────────────────────────────────────────────────────────────────

function check() {
  let bad = 0
  const rows = inventory()
  for (const r of rows) {
    for (const [k, v] of r.loc?.strings ?? []) {
      const base = r.base.strings.get(k)
      if (base === undefined) continue
      if (placeholders(base) !== placeholders(v)) {
        console.error(`✗ ${r.locale} ${r.module}/${r.file} "${k}": placeholders differ — base "(${placeholders(base)})" vs "(${placeholders(v)})"`)
        bad++
      }
      if (/[^\\]'/.test(v)) {
        console.error(`✗ ${r.locale} ${r.module}/${r.file} "${k}": unescaped apostrophe`)
        bad++
      }
    }
    for (const [k, entry] of r.loc?.plurals ?? []) {
      const base = r.base.plurals.get(k)
      if (base === undefined) continue
      for (const [q, v] of entry.items) {
        const direct = base.items.get(q)
        const source = direct ?? base.items.get('other')
        if (source === undefined) continue
        if (placeholders(source) !== placeholders(v)) {
          // A form the base has its own version of must match it exactly. A
          // form derived from "other" (Russian's few/many, Arabic's zero/two)
          // is allowed to drop the digit: some languages spell those
          // quantities out, and a tool that fails a build on correct Arabic
          // is a tool that gets disabled.
          const where = `${r.locale} ${r.module}/${r.file} "${k}#${q}"`
          if (direct !== undefined) {
            console.error(`✗ ${where}: placeholders differ — "(${placeholders(source)})" vs "(${placeholders(v)})"`)
            bad++
          } else {
            console.warn(`⚠ ${where}: no %-placeholder (correct for spelled-out quantities, wrong otherwise)`)
          }
        }
      }
    }
  }
  if (bad) {
    console.error(`\n${bad} problem(s). Fix before tagging — CI runs this.`)
    process.exit(1)
  }
  console.log('✓ all locales match their base on placeholders and escaping.')
}

// ── translate ───────────────────────────────────────────────────────────────

function systemPrompt(locale) {
  const [english, native] = LANG_NAMES[locale]
  return [
    `You are a professional translator for an Android app called Drosh, a terminal emulator with an AI agent, SSH sessions, panes and an editor.`,
    `Translate UI strings from English into ${english} (${native}).`,
    ``,
    `Rules:`,
    `1. Reply with ONLY a JSON object mapping each input key to its translation. No markdown, no commentary, no code fences.`,
    `2. Preserve every format placeholder exactly as written: %1$s, %1$d, %s, %d must appear unchanged in the translation.`,
    `3. Never translate these terms, keep them verbatim: ${KEEP_AS_IS.join(', ')}.`,
    `4. Natural, concise UI phrasing — buttons are imperatives, titles are short. Use the terminology a ${english}-speaking developer expects for terminal concepts (prompt, shell, session).`,
    `5. Plain text only: no XML escaping, no surrounding quotes, no trailing punctuation you did not translate.`,
    `6. Translate every key. Never add, rename or drop keys.`,
  ].join('\n')
}

async function chat(system, user) {
  const key = process.env.OPENROUTER_API_KEY
  if (!key) throw new Error('OPENROUTER_API_KEY is not set')
  const res = await fetch(API, {
    method: 'POST',
    headers: {
      Authorization: `Bearer ${key}`,
      'Content-Type': 'application/json',
    },
    body: JSON.stringify({
      model: MODEL,
      temperature: 0.2,
      messages: [
        { role: 'system', content: system },
        { role: 'user', content: user },
      ],
    }),
  })
  if (!res.ok) {
    const body = await res.text()
    throw new Error(`OpenRouter ${res.status}: ${body.slice(0, 300)}`)
  }
  const data = await res.json()
  return data.choices?.[0]?.message?.content ?? ''
}

/** Pull the JSON object out of a reply that may still wrap it in prose. */
function extractJson(text) {
  const start = text.indexOf('{')
  const end = text.lastIndexOf('}')
  if (start === -1 || end === -1) throw new Error('no JSON object in reply')
  return JSON.parse(text.slice(start, end + 1))
}

async function translateChunk(system, entries) {
  const user = `Translate these UI strings. Reply with a single JSON object of the same keys.\n\n${JSON.stringify(Object.fromEntries(entries), null, 1)}`
  let lastErr
  for (let attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
    try {
      const reply = await chat(system, user)
      const parsed = extractJson(reply)
      for (const [k, source] of entries) {
        const value = parsed[k]
        if (typeof value !== 'string' || !value.trim()) {
          throw new Error(`missing or empty key "${k}"`)
        }
        if (placeholders(source) !== placeholders(value)) {
          throw new Error(`placeholders differ for "${k}": "${placeholders(source)}" vs "${placeholders(value)}"`)
        }
      }
      return parsed
    } catch (err) {
      lastErr = err
      console.error(`  attempt ${attempt} failed: ${err.message}`)
    }
  }
  throw new Error(`chunk failed after ${MAX_ATTEMPTS} attempts: ${lastErr.message}`)
}

function writeTarget(r, entries) {
  const { module, file, locale, base, loc } = r
  const lines = [
    '<?xml version="1.0" encoding="utf-8"?>',
    '<!--',
    `  Drosh — ${LANG_NAMES[locale][0]} strings.`,
    '  Machine-translated and placeholder-verified; wording not yet reviewed.',
    '  Format placeholders (%1$s, %1$d) must survive every edit unchanged.',
    '-->',
    '<resources>',
  ]
  for (const [k, v] of base.strings.entries()) {
    // Newly translated values are plain text and get escaped; values already
    // in the file are written back exactly as they are — escaping them again
    // would double every apostrophe the file already carries.
    let value
    if (entries.strings.has(k)) value = escape(entries.strings.get(k))
    else if (loc?.strings.has(k)) value = loc.strings.get(k)
    else value = escape(v)
    lines.push(`    <string name="${k}">${value}</string>`)
  }
  for (const [k, forms] of base.plurals.entries()) {
    const got = entries.plurals.get(k)
    const existing = loc?.plurals.get(k)?.items
    lines.push(`    <plurals name="${k}">`)
    for (const q of expectedForms(locale, forms.items)) {
      let value
      if (got?.has(q)) value = escape(got.get(q))
      else if (existing?.has(q)) value = existing.get(q)
      else value = escape(forms.items.get(q) ?? forms.items.get('other'))
      lines.push(`        <item quantity="${q}">${value}</item>`)
    }
    lines.push('    </plurals>')
  }
  lines.push('</resources>', '')
  const dir = join(MODULES, module, 'src/main/res', `values-${locale}`)
  writeFileSync(join(dir, file), lines.join('\n'))
}

async function translate(langs, dry) {
  if (!dry && !process.env.OPENROUTER_API_KEY) {
    console.error('OPENROUTER_API_KEY is not set — export it and retry.')
    process.exit(1)
  }
  const rows = inventory().filter((r) => langs.includes(r.locale))
  let done = 0
  let todo = 0
  for (const r of rows) {
    if (r.missingStrings.length === 0 && r.missingPlurals.length === 0) continue
    const entries = { strings: new Map(), plurals: new Map() }
    const system = systemPrompt(r.locale)

    const queue = r.missingStrings.map((k) => [k, unescape(r.base.strings.get(k))])
    todo += queue.length
    for (let i = 0; i < queue.length; i += CHUNK) {
      const chunk = queue.slice(i, i + CHUNK)
      const label = `${r.locale} ${r.module}/${r.file} strings ${i + 1}–${i + chunk.length}`
      if (dry) {
        console.log(`[dry] ${label}`)
        continue
      }
      console.log(`→ ${label}`)
      const parsed = await translateChunk(system, chunk)
      for (const [k, v] of Object.entries(parsed)) entries.strings.set(k, v)
      done += chunk.length
    }

    // Plurals, one entry per missing quantity: the model translates a flat
    // key, so the quantity rides in the key ("name#one") and the response
    // needs no separator parsing. Quantities the locale already has are kept.
    const pqueue = []
    for (const [k, forms] of r.missingPlurals) {
      const existing = r.loc?.plurals.get(k)?.items
      for (const [q, src] of pluralSources(r.locale, forms.items)) {
        if (existing?.has(q)) continue
        pqueue.push([`${k}#${q}`, unescape(src)])
      }
    }
    todo += pqueue.length
    for (let i = 0; i < pqueue.length; i += CHUNK) {
      const chunk = pqueue.slice(i, i + CHUNK)
      if (dry) continue
      console.log(`→ ${r.locale} ${r.module}/${r.file} plurals ${i + 1}–${i + chunk.length}`)
      const parsed = await translateChunk(system, chunk)
      for (const [raw, value] of Object.entries(parsed)) {
        const [name, q] = raw.split('#')
        const items = entries.plurals.get(name) ?? new Map()
        items.set(q, value)
        entries.plurals.set(name, items)
      }
      done += chunk.length
    }

    if (!dry) {
      writeTarget(r, entries)
      console.log(`✓ wrote android/${r.module}/src/main/res/values-${r.locale}/${r.file}`)
    }
  }
  if (dry) {
    console.log(`[dry] ${todo} entries would be translated.`)
  } else {
    console.log(`\n${done} strings + plurals translated and written.`)
    check()
  }
}

// ── cli ─────────────────────────────────────────────────────────────────────

const [cmd, ...rest] = process.argv.slice(2)
const flag = (name) => rest.find((a) => a.startsWith(`--${name}=`))?.split('=')[1]

const langsArg = flag('lang')
const langs = langsArg ? langsArg.split(',') : LOCALES

if (cmd === 'report') report()
else if (cmd === 'check') check()
else if (cmd === 'translate') translate(langs, rest.includes('--dry')).catch((e) => {
  console.error(`✗ ${e.message}`)
  process.exit(1)
})
else {
  console.log('usage: node tools/i18n.mjs report|check|translate [--lang=de,es] [--dry]')
  process.exit(1)
}
