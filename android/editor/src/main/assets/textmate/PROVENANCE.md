# Bundled TextMate grammars — provenance and licences

The files in `syntaxes/` are TextMate grammar definitions. They are not ours;
they are vendored verbatim from MIT-licensed projects so the editor can
highlight them offline. Nothing in them has been edited.

Add a grammar by dropping the file in `syntaxes/`, adding an entry to
`languages.json`, and mapping its extension in `EditorLanguage.kt`.

## microsoft/vscode — MIT License

Pinned to commit `4f2ff19ecacffa0aa4874db4d63ed4e899d98431`.

| File | Upstream path | Scope |
|------|---------------|-------|
| `bash.tmLanguage.json` | `extensions/shellscript/syntaxes/shell-unix-bash.tmLanguage.json` | `source.shell` |
| `csharp.tmLanguage.json` | `extensions/csharp/syntaxes/csharp.tmLanguage.json` | `source.cs` |
| `cpp.tmLanguage.json` | `extensions/cpp/syntaxes/cpp.tmLanguage.json` | `source.cpp` |
| `java.tmLanguage.json` | `extensions/java/syntaxes/java.tmLanguage.json` | `source.java` |
| `javascript.tmLanguage.json` | `extensions/javascript/syntaxes/JavaScript.tmLanguage.json` | `source.js` |
| `json.tmLanguage.json` | `extensions/json/syntaxes/JSON.tmLanguage.json` | `source.json` |
| `markdown.tmLanguage.json` | `extensions/markdown-basics/syntaxes/markdown.tmLanguage.json` | `text.html.markdown` |
| `python.tmLanguage.json` | `extensions/python/syntaxes/MagicPython.tmLanguage.json` | `source.python` |
| `typescript.tmLanguage.json` | `extensions/typescript-basics/syntaxes/TypeScript.tmLanguage.json` | `source.ts` |
| `yaml.tmLanguage.json` | `extensions/yaml/syntaxes/yaml.tmLanguage.json` | `source.yaml` |

> Copyright (c) Microsoft Corporation. Licensed under the MIT License.

## fwcd/vscode-kotlin — MIT License

| File | Upstream path | Scope |
|------|---------------|-------|
| `kotlin.tmLanguage.json` | `syntaxes/kotlin.tmLanguage.json` | `source.kotlin` |

> Copyright (c) Friedrich Lindenberg and contributors. MIT License.

## Not ours

`drosh-dark.json` in the parent directory is Drosh's own theme, written for
this project against the palette in `core/.../DroshPalette.kt`. The `configs/`
files are minimal VS Code language-configuration documents (bracket pairing and
comment markers), also written for this project.

## Caveat worth knowing

tm4e does not implement every regex construct a grammar may use; ones it cannot
compile fall back to `^$` and their patterns stop matching (sora-editor logs this
per grammar). Highlighting is therefore not guaranteed identical to VS Code's for
every construct. This is upstream behaviour, not a packaging problem.