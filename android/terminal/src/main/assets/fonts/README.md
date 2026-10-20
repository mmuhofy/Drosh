# Bundled font — JetBrains Mono NL 2.304 (SIL OFL 1.1)

The terminal's font travels inside the APK so that a cell has the same pixel
size on every device: Phase B's cell metrics, the atlas buckets and (later) the
selection geometry are all derived from it. Nothing in the app reads
`/system/fonts`, whose contents and metrics differ per OEM.

| File | Bytes | SHA-256 |
|---|---|---|
| `JetBrainsMonoNL-Regular.ttf` | 208,576 | `fb3b2575d7b0657359707993288f12a7360344d39387bb26050e276d61f6bd2a` |
| `JetBrainsMonoNL-Bold.ttf` | 210,988 | `0198e841824025f8876e5c297f0b9b497ee8d6eb9969710a3328e1303f996ec3` |
| `OFL.txt` | 4,399 | license text — must stay next to the fonts |

- Source: <https://github.com/JetBrains/JetBrainsMono/releases/tag/v2.304> —
  release archive `JetBrainsMono-2.304.zip`, SHA-256
  `6f6376c6ed2960ea8a963cd7387ec9d76e3f629125bc33d1fdcd7eb7012f7bbf`
- License: SIL Open Font License 1.1, see `OFL.txt` (copyright notice included
  there). The fonts ship unmodified, which is what the license asks for.
- **Why the `NL` files**: "NL" is JetBrains Mono with the ligature lookups
  removed. A terminal cell must show the character the guest actually wrote, and
  the prototype has no ligature support (PLAN.md D6, `TODO.md` Phase B), so
  shipping the variant that *cannot* ligate is more honest than switching
  ligatures off at shape time. The shaper still disables `liga`/`clig`/`calt`/
  `rlig` explicitly, so a future font swap cannot start ligating behind our
  back.
- Bold ships as a second static face. Italic is synthesised by skewing rather
  than shipped as a third file, and the release's variable font is deliberately
  unused: it adds a weight axis the prototype does not expose (D12 — no
  configuration).
