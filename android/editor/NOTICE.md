# NOTICE — `:editor`

The Drosh code in this module is Apache-2.0 (see `LICENSE.md` at the root).
This module exists only because of the following third-party components, and
this file is where their notices live.

## sora-editor — LGPL-2.1-or-later

```
io.github.rosemoe:editor             (sora-editor)
io.github.rosemoe:language-textmate  (sora's TextMate module, same project)
```

Both are used **unmodified** as dependencies. They are licensed
LGPL-2.1-or-later, which is compatible with the GPLv3 app this module is
compiled into — but the module's own code is Apache-2.0, not GPLv3, which is
the point of having this file at all: a fork of `:editor` may take the Drosh
code under Apache-2.0 terms, while sora-editor itself remains whatever it was.

This is also the open item from MEMORYBANK §16, decision 14: accepting an
LGPL component inside the app. Its terms are satisfied — unmodified use,
sources available upstream, license text reproduced here by reference — but the
decision is recorded as open until it is confirmed ahead of any F-Droid
submission.

Upstream: https://github.com/Rosemoe/sora-editor

## TextMate grammars — MIT

The grammars and theme in `src/main/assets/textmate/` come from MIT-licensed
sources at a pinned commit, with the provenance recorded in
`src/main/assets/textmate/PROVENANCE.md`. Their license travels with them;
nothing in this module re-licenses them.

## Eclipse tm4e — EPL-2.0

`sora-language-textmate` is built on Eclipse tm4e (`org.eclipse.tm4e.*`), which
this project pulls in transitively and never touches. Its notice is upstream's
responsibility; it is listed here so the chain is complete for anyone auditing.
