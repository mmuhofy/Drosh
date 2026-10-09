package dev.drosh.ui.agent.components

/**
 * The languages the highlighter knows.
 *
 * ## Why a fixed list rather than detection
 *
 * Detection means guessing, and a wrong guess is worse than none: a shell script
 * tokenised as Python gets its `$var` highlighted as a keyword and its `[0]` as a
 * list index, which reads as though the highlighting knew something. An unknown
 * language is rendered in plain text, which is honest.
 *
 * So the list is what the models actually emit in practice — the languages in this
 * repository, plus the ones a mobile toolchain reaches for — and everything else
 * falls through to plain.
 *
 * ## Not a parser
 *
 * These are lexical rules, not grammars. Nothing here validates the code, and a
 * token boundary that a real parser would disagree with does not break anything:
 * the worst outcome is a token of the wrong type on a string that happens to contain
 * a keyword, which is exactly what happens in real editors too.
 */
object SyntaxLanguages {

    private val KEYWORDS: Map<String, Set<String>> = mapOf(
        "kotlin" to setOf(
            "as", "break", "class", "continue", "do", "else", "false", "for", "fun",
            "if", "in", "interface", "is", "null", "object", "package", "return",
            "super", "this", "throw", "true", "try", "typealias", "val", "var",
            "when", "while", "by", "catch", "constructor", "delegate", "dynamic",
            "field", "file", "finally", "get", "import", "init", "param", "property",
            "receiver", "set", "setparam", "where", "actual", "abstract", "annotation",
            "companion", "const", "crossinline", "data", "enum", "expect", "external",
            "final", "infix", "inline", "inner", "internal", "lateinit", "noinline",
            "open", "operator", "out", "override", "private", "protected", "public",
            "reified", "sealed", "suspend", "tailrec", "vararg",
        ),
        "java" to setOf(
            "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char",
            "class", "const", "continue", "default", "do", "double", "else", "enum",
            "extends", "final", "finally", "float", "for", "if", "implements",
            "import", "instanceof", "int", "interface", "long", "native", "new",
            "package", "private", "protected", "public", "return", "short", "static",
            "strictfp", "super", "switch", "synchronized", "this", "throw", "throws",
            "transient", "try", "void", "volatile", "while", "record", "var", "yield",
        ),
        "javascript" to COMMON_JS + setOf(
            "async", "await", "export", "from", "import", "let", "static", "yield",
        ),
        "typescript" to COMMON_JS + setOf(
            "abstract", "any", "as", "async", "await", "declare", "enum", "export",
            "from", "implements", "import", "interface", "keyof", "let", "namespace",
            "private", "protected", "public", "readonly", "satisfies", "static",
            "type", "typeof", "yield",
        ),
        "python" to setOf(
            "and", "as", "assert", "async", "await", "break", "class", "continue",
            "def", "del", "elif", "else", "except", "False", "finally", "for",
            "from", "global", "if", "import", "in", "is", "lambda", "None", "nonlocal",
            "not", "or", "pass", "raise", "return", "True", "try", "while", "with",
            "yield", "match", "case",
        ),
        "shell" to setOf(
            "if", "then", "else", "elif", "fi", "for", "while", "do", "done", "case",
            "esac", "in", "function", "return", "break", "continue", "local",
            "export", "readonly", "declare", "source", "set", "unset", "trap", "exit",
            "shift",
        ),
        "json" to emptySet(),
        "yaml" to setOf("true", "false", "null", "yes", "no", "on", "off"),
        "go" to setOf(
            "break", "case", "chan", "const", "continue", "default", "defer", "else",
            "fallthrough", "for", "func", "go", "goto", "if", "import", "interface",
            "map", "package", "range", "return", "select", "struct", "switch", "type",
            "var",
        ),
        "rust" to setOf(
            "as", "async", "await", "break", "const", "continue", "crate", "dyn",
            "else", "enum", "extern", "false", "fn", "for", "if", "impl", "in",
            "let", "loop", "match", "mod", "move", "mut", "pub", "ref", "return",
            "self", "Self", "static", "struct", "super", "trait", "true", "type",
            "unsafe", "use", "where", "while",
        ),
        "c" to setOf(
            "auto", "break", "case", "char", "const", "continue", "default", "do",
            "double", "else", "enum", "extern", "float", "for", "goto", "if", "inline",
            "int", "long", "register", "restrict", "return", "short", "signed",
            "sizeof", "static", "struct", "switch", "typedef", "union", "unsigned",
            "void", "volatile", "while", "include", "define",
        ),
        "sql" to setOf(
            "SELECT", "FROM", "WHERE", "INSERT", "INTO", "VALUES", "UPDATE", "SET",
            "DELETE", "CREATE", "TABLE", "DROP", "ALTER", "JOIN", "LEFT", "RIGHT",
            "INNER", "OUTER", "ON", "GROUP", "BY", "ORDER", "HAVING", "LIMIT",
            "INDEX", "PRIMARY", "KEY", "FOREIGN", "REFERENCES", "AND", "OR", "NOT",
            "NULL", "AS", "DISTINCT", "COUNT", "SUM", "AVG",
        ),
        "html" to emptySet(),
        "css" to emptySet(),
    )

    private val COMMON_JS = setOf(
        "break", "case", "catch", "class", "const", "continue", "debugger", "default",
        "delete", "do", "else", "false", "finally", "for", "function", "if", "in",
        "instanceof", "new", "null", "return", "super", "switch", "this", "throw",
        "true", "try", "typeof", "undefined", "var", "void", "while", "with",
    )

    /**
     * Line-comment prefixes, per language.
     *
     * `#` is deliberately absent from html and css — it is a valid id and colour
     * there, and highlighting `color: #fff` as a comment would swallow the rest of
     * the line.
     */
    private val LINE_COMMENTS: Map<String, Set<String>> = mapOf(
        "kotlin" to setOf("//"),
        "java" to setOf("//"),
        "javascript" to setOf("//"),
        "typescript" to setOf("//"),
        "python" to setOf("#"),
        "shell" to setOf("#"),
        "go" to setOf("//"),
        "rust" to setOf("//"),
        "c" to setOf("//"),
        "yaml" to setOf("#"),
        "sql" to setOf("--"),
    )

    private val BLOCK_COMMENTS: Set<String> = setOf("c", "java", "javascript", "typescript", "go", "rust", "kotlin")

    private val ALIASES: Map<String, String> = mapOf(
        "kt" to "kotlin",
        "kts" to "kotlin",
        "js" to "javascript",
        "jsx" to "javascript",
        "ts" to "typescript",
        "tsx" to "typescript",
        "tsv" to "typescript",
        "py" to "python",
        "sh" to "shell",
        "bash" to "shell",
        "zsh" to "shell",
        "console" to "shell",
        "yml" to "yaml",
        "rs" to "rust",
        "h" to "c",
        "c++" to "c",
        "cpp" to "c",
        "hpp" to "c",
        "cc" to "c",
        "htm" to "html",
    )

    /**
     * Resolve a fence info string to a known language, or null.
     *
     * An info string is free text and models write `kotlin`, `Kotlin`, `kt`, and
     * ```` ```kotlin title="x.kt" ```` in roughly equal measure, so the first token is
     * taken and matched case-insensitively. Unknown languages resolve to null and
     * render plain rather than guessed.
     */
    fun resolve(info: String?): String? {
        val token = info?.trim()?.split(Regex("\\s+"))?.firstOrNull()
            ?.lowercase()
            ?.takeIf { it.isNotEmpty() }
            ?: return null
        return ALIASES[token] ?: token.takeIf { KEYWORDS.containsKey(it) || it == "text" }
    }

    fun keywords(language: String): Set<String> = KEYWORDS[language].orEmpty()

    fun lineComments(language: String): Set<String> = LINE_COMMENTS[language].orEmpty()

    fun hasBlockComments(language: String): Boolean = language in BLOCK_COMMENTS

    /** Shown on the code block when the fence named something we do not know. */
    fun label(language: String?): String = language ?: "text"
}