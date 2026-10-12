package dev.drosh.domain.agent

import kotlinx.serialization.Serializable

/**
 * How much freedom the agent has before it has to ask.
 *
 * Three levels, chosen because the reference implementations land on three
 * families and this app has one terminal rather than a platform to police:
 *
 *  - [ASK] — every shell command is put to the user. The safe ones are
 *    offered as a one-tap approval, but nothing runs unasked.
 *  - [AUTO_SAFE] — read-only and obviously harmless commands run without
 *    a prompt; anything that writes, deletes, installs, escalates or reaches
 *    the network is asked. This is the default, because it is the level that
 *    matches what the agent actually does most of the day.
 *  - [YOLO] — nothing is asked except what the deterministic layer rejects
 *    outright. [REJECTED_PATTERNS] still applies: an autonomy level is a
 *    setting, not a security boundary.
 *
 * The separation from the deterministic layer is deliberate. A level can be
 * changed by anyone holding the phone; a rejection cannot, and that is what
 * makes it a floor rather than a preference.
 */
@Serializable
enum class AutonomyLevel {
    ASK,
    AUTO_SAFE,
    YOLO,
    ;

    /** The persistence key, matching the TOML value. */
    val key: String get() = name.lowercase()
}

/**
 * What the answer to a safety question was.
 *
 * Not [ApprovalDecision], which is the sealed interface the agent's own
 * write-file and ask-user tools answer into: this is the safety layer's
 * three-way answer, and [ALWAYS] is the only one that outlives the request —
 * it writes a rule into the settings file that is matched the next time the
 * same shape of command comes up.
 */
@Serializable
enum class SafetyDecision {
    /** This command, this once. */
    PROCEED,

    /** This shape of command, from now on. Writes a rule. */
    ALWAYS,

    /** Refuse, and tell the agent why. */
    DENY,
}

/**
 * What the safety layer says about a command before it runs.
 *
 * Three outcomes rather than two, because "ask" and "allow" are not the same
 * knowledge: a command that is allowed quietly is one the user never sees, and
 * a command that is asked about is one that interrupted them. A third,
 * [REJECT], exists for the commands no setting should be able to run.
 */
sealed interface SafetyCheck {

    /** Runs without a prompt. */
    data object AutoApprove : SafetyCheck

    /** Ask the user; [reason] says why in one line. */
    data class AskUser(val reason: String) : SafetyCheck

    /** Refused outright, without asking. */
    data class Reject(val reason: String) : SafetyCheck
}

/**
 * The deterministic safety layer.
 *
 * This is the part of the security system that does not depend on a setting,
 * a mode or a classifier: a list of command shapes that damage a device rather
 * than fail a task. The reference implementations each carry one — codex reads
 * the tokens of a parsed command, continue evaluates shell quoting, qwen-code
 * blocks destructive git and IaC work with patterns — and the common shape is
 * a small, closed list matched against the *first word* of a command and its
 * flag list.
 *
 * ## Why only the first words
 *
 * A pattern language over shell is a second parser, and a second parser is
 * wrong in ways the first one is not. Matching on the head of the command and
 * on its flags covers the cases that actually brick a device, and leaves
 * everything else to the approval layer, which is where judgement belongs.
 *
 * ## Why this is separate from the autonomy level
 *
 * An autonomy level is a preference and can be changed by anyone holding the
 * phone. This is a floor. They are different things, and one being turned up
 * must not carry the other with it.
 */
object CommandSafety {

    /**
     * Command shapes no setting may allow.
     *
     * Each entry is a head word and the flags that make it destructive. A
     * command matching one of these is refused before the user is even asked,
     * because there is no answer to a question about deleting the root
     * filesystem that leaves a usable device.
     */
    private data class Forbidden(val head: String, val flags: List<String>, val why: String)

    private val FORBIDDEN = listOf(
        Forbidden("rm", listOf("-fr", "-rf", "-r", "-f"), "rm -rf deletes whole trees without asking again"),
        Forbidden("mkfs", emptyList(), "mkfs formats a filesystem"),
        Forbidden("dd", listOf("of="), "dd with of= writes raw to a device"),
        Forbidden("chmod", listOf("777"), "chmod 777 makes everything world-writable"),
        Forbidden("chown", listOf("root"), "chown root changes ownership away from the user"),
        Forbidden("format", emptyList(), "format erases a drive"),
        Forbidden("cipher", emptyList(), "cipher overwrites free space"),
        Forbidden("sudo", emptyList(), "sudo runs the rest as root"),
        // Destructive git: the ones that throw away work with no confirmation
        // of their own. `git reset --hard` discards every uncommitted change in
        // the tree, which is loss nobody wants from a suggestion.
        Forbidden("git", listOf("clean", "reset", "checkout"), "git reset --hard, checkout -- and clean discard uncommitted work"),
    )

    /** Commands that change the machine rather than the task. */
    private val WRITE_HEADS = setOf(
        "rm", "mv", "cp", "mkdir", "touch", "chmod", "chown", "ln",
        "dd", "mkfs", "mkinitcpio", "useradd", "userdel", "usermod",
        "groupadd", "passwd", "systemctl", "service", "apt", "apt-get",
        "pacman", "yay", "yum", "dnf", "zypper", "brew", "npm", "npx",
        "yarn", "pnpm", "pip", "pip3", "cargo", "go", "gem", "make",
        "docker", "kubectl", "terraform", "pulumi", "cdk", "ansible",
        "sed", "awk", "git",
    )

    /** The heads that read, and only read. */
    private val READ_HEADS = setOf(
        "ls", "cat", "head", "tail", "less", "more", "grep", "egrep",
        "fgrep", "find", "wc", "sort", "uniq", "cut", "tr", "diff",
        "file", "stat", "du", "df", "which", "whereis", "type", "pwd",
        "echo", "printf", "date", "id", "whoami", "hostname", "uname",
        "env", "printenv", "ps", "top", "history", "man", "help", "jq",
    )

    /** Network access, and things that escalate. */
    private val NETWORK_HEADS = setOf(
        "curl", "wget", "nc", "ncat", "ssh", "scp", "sftp", "rsync",
        "telnet", "ping", "dig", "nslookup",
    )

    /**
     * Decides what to do with [command].
     *
     * The decision is made on the command's head word and the flags it carries,
     * after splitting on the shell's own separators so a compound command is
     * judged on its worst part — the same rule the reference implementations
     * use, and for the same reason: `ls && rm -rf /` is not a read.
     */
    fun check(command: String, autonomy: AutonomyLevel): SafetyCheck {
        val parts = splitCompound(command)
        if (parts.isEmpty()) return SafetyCheck.AutoApprove

        // The deterministic layer is checked first and is not negotiable.
        for (part in parts) {
            val tokens = tokenize(part)
            val head = tokens.firstOrNull()?.lowercase() ?: continue
            val forbidden = FORBIDDEN.firstOrNull { it.head == head && it.matches(tokens) }
            if (forbidden != null) {
                return SafetyCheck.Reject(forbidden.why)
            }
            // Wrappers hide the real command: `sudo ls`, `env FOO=1 rm -rf /`.
            for (wrapper in WRAPPERS) {
                val unwrapped = unwrap(tokens, wrapper)
                if (unwrapped != null) {
                    val inner = FORBIDDEN.firstOrNull { it.head == unwrapped.firstOrNull()?.lowercase() && it.matches(unwrapped) }
                    if (inner != null) return SafetyCheck.Reject(inner.why)
                }
            }
        }

        return when (autonomy) {
            AutonomyLevel.YOLO -> SafetyCheck.AutoApprove

            AutonomyLevel.AUTO_SAFE -> {
                val risky = parts.any { part ->
                    val tokens = tokenize(part)
                    val head = tokens.firstOrNull()?.lowercase()
                    head in NETWORK_HEADS || head in WRITE_HEADS
                }
                if (risky) SafetyCheck.AskUser("writes to the machine or reaches the network") else SafetyCheck.AutoApprove
            }

            AutonomyLevel.ASK -> SafetyCheck.AskUser("every command is asked at this level")
        }
    }

    /** True when the command head is on the forbidden list with its flags. */
    private fun Forbidden.matches(tokens: List<String>): Boolean {
        if (flags.isEmpty()) return true
        // A flag match is a *substring* match on the token: `--force`, `-rf` and
        // `-fr` are the same instruction, and a shell accepts all three.
        return tokens.drop(1).any { token ->
            val lower = token.lowercase()
            flags.any { flag -> lower.contains(flag.lowercase()) }
        }
    }

    /**
     * Splits on the shell's separators.
     *
     * Quote-aware, so a command that *contains* a separator inside quotes is
     * still one command — otherwise `echo "a && b"` would be judged as if it
     * ran two things.
     */
    private fun splitCompound(command: String): List<String> {
        val parts = mutableListOf<String>()
        val current = StringBuilder()
        var quote: Char? = null
        var escaped = false

        command.forEach { ch ->
            when {
                escaped -> {
                    current.append(ch)
                    escaped = false
                }
                ch == '\\' -> {
                    escaped = true
                    current.append(ch)
                }
                quote != null -> {
                    current.append(ch)
                    if (ch == quote) quote = null
                }
                ch == '"' || ch == '\'' -> {
                    quote = ch
                    current.append(ch)
                }
                ch == ';' || ch == '|' || ch == '&' -> {
                    if (current.isNotBlank()) parts += current.toString().trim()
                    current.clear()
                }
                ch == '\n' -> {
                    if (current.isNotBlank()) parts += current.toString().trim()
                    current.clear()
                }
                else -> current.append(ch)
            }
        }
        if (current.isNotBlank()) parts += current.toString().trim()
        return parts
    }

    /** Splits one command into words, keeping quoted ones whole. */
    private fun tokenize(part: String): List<String> {
        val tokens = mutableListOf<String>()
        val current = StringBuilder()
        var quote: Char? = null
        var escaped = false

        part.forEach { ch ->
            when {
                escaped -> {
                    escaped = false
                }
                ch == '\\' -> escaped = true
                quote != null -> {
                    if (ch == quote) quote = null
                    else current.append(ch)
                }
                ch == '"' || ch == '\'' -> quote = ch
                ch.isWhitespace() -> {
                    if (current.isNotEmpty()) {
                        tokens += current.toString()
                        current.clear()
                    }
                }
                else -> current.append(ch)
            }
        }
        if (current.isNotEmpty()) tokens += current.toString()
        return tokens
    }

    /** Commands whose real work is the thing after them. */
    private val WRAPPERS = listOf("sudo", "env", "nohup", "time", "command", "exec", "eval", "bash -c")

    /** The tokens after [wrapper], or null when [tokens] does not start with it. */
    private fun unwrap(tokens: List<String>, wrapper: String): List<String>? {
        val head = tokens.firstOrNull()?.lowercase() ?: return null
        if (wrapper.startsWith("bash") ) {
            if (head != "bash") return null
            // `bash -c "rm -rf /"` — the command is inside the argument.
            return tokens.getOrNull(2)?.let { tokenize(it) }
        }
        if (head != wrapper) return null
        return tokens.drop(1)
    }
}

/**
 * A remembered "always allow" rule.
 *
 * The pattern is the command's head word plus a trailing ` *`, following the
 * shape the reference implementations write: `git status *` rather than `git
 * status`, because the rule is about *that kind* of command and not about that
 * one invocation. Matching on the prefix keeps a rule from silently expiring
 * the first time a flag changes.
 */
@Serializable
data class ApprovalRule(
    val pattern: String,
) {
    /** True when [command] is the shape this rule was written for. */
    fun matches(command: String): Boolean {
        val prefix = pattern.removeSuffix("*").trim()
        if (prefix.isEmpty()) return false
        return command.trim().startsWith(prefix)
    }

    companion object {
        /** The rule a command of [command]'s shape would produce. */
        fun forCommand(command: String): ApprovalRule {
            val tokens = command.trim().split(Regex("\\s+"))
            val head = tokens.firstOrNull()?.lowercase().orEmpty()
            // Two words when the command has a subcommand: `git status` rather
            // than `git`, because "always allow git" would allow `git reset`.
            val words = when (head) {
                "git" -> tokens.take(2)
                else -> tokens.take(1)
            }
            return ApprovalRule(words.joinToString(" ") + " *")
        }
    }
}
