# Bundled font — JetBrains Mono 2.304 (SIL OFL 1.1)

The terminal's font travels inside the APK so that a cell has the same pixel
size on every device: Phase B's cell metrics, the atlas buckets and (later) the
selection geometry are all derived from it. Nothing in the app reads
`/system/fonts`, whose contents and metrics differ per OEM.

| File | Bytes | SHA-256 |
|---|---|---|
| `JetBrainsMono-Regular.ttf` | 273,900 | `a0bf60ef0f83c5ed4d7a75d45838548b1f6873372dfac88f71804491898d138f` |
| `JetBrainsMono-Bold.ttf` | 277,828 | `5590990c82e097397517f275f430af4546e1c45cff408bde4255dad142479dcb` |
| `OFL.txt` | 4,399 | license text — must stay next to the fonts |

- Source: <https://github.com/JetBrains/JetBrainsMono/releases/tag/v2.304> —
  release archive `JetBrainsMono-2.304.zip`, SHA-256
  `6f6376c6ed2960ea8a963cd7387ec9d76e3f629125bc33d1fdcd7eb7012f7bbf`
- License: SIL Open Font License 1.1, see `OFL.txt` (copyright notice included
  there). The fonts ship unmodified, which is what the license asks for.
- **Why the standard files, not the `NL` ones**: the first build shipped the
  `NL` variant — JetBrains Mono with the ligature lookups removed — because the
  prototype had no ligature support (PLAN.md D6), and a terminal cell must show
  the character the guest actually wrote. The renderer shapes now, and the
  setting decides: the shaper disables `liga`/`clig`/`calt`/`rlig` explicitly
  when ligatures are off, so the font's own tables never ligate behind the
  user's back. The `NL` files are kept out of the APK for that reason.
- Bold ships as a second static face. Italic is synthesised by skewing rather
  than shipped as a third file, and the release's variable font is deliberately
  unused: it adds a weight axis the prototype does not expose (D12 — no
  configuration).
