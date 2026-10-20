package dev.drosh.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The argv chroot-ng is given.
 *
 * The shape matters more than it looks: chroot-ng takes its options first, the
 * rootfs second and the program third, and it builds the guest's environment
 * itself, so everything the guest needs has to be spelled out as `-E`. A
 * misplaced argument does not fail here — it fails on a device, where the
 * shell does not start and the reason is a line in a log.
 */
class ChrootNgRunnerTest {

    private fun runner(rootfs: File, devicePaths: List<String> = emptyList()): ChrootNgRunner =
        ChrootNgRunner(rootfs, "/native/lib", devicePaths)

    private fun rootfs(): File = File(System.getProperty("java.io.tmpdir"), "drosh-rootfs").apply {
        mkdirs()
        File(this, "home").mkdirs()
    }

    private fun argAfter(argv: List<String>, flag: String): String? {
        val index = argv.indexOf(flag)
        return if (index >= 0 && index + 1 < argv.size) argv[index + 1] else null
    }

    @Test
    fun `the rootfs comes before the program`() {
        val rootfs = rootfs()
        val command = runner(rootfs).build(GuestSpec(shell = "/bin/zsh"))

        val rootfsIndex = command.argv.indexOf(rootfs.absolutePath)
        val shellIndex = command.argv.indexOf("/bin/zsh")
        assertTrue("the rootfs must precede the program", rootfsIndex in 0 until shellIndex)
    }

    @Test
    fun `every option comes before the rootfs`() {
        // chroot-ng stops parsing options at the rootfs, so an option after it
        // is taken for the program to run — the guest would try to exec "-E".
        val rootfs = rootfs()
        val command = runner(rootfs).build(
            GuestSpec(shell = "/bin/zsh", environment = mapOf("ZDOTDIR" to "/home/zsh")),
        )

        val rootfsIndex = command.argv.indexOf(rootfs.absolutePath)
        val optionIndexes = command.argv.indices.filter { command.argv[it].startsWith("-") }
        assertTrue(
            "an option came after the rootfs: ${command.argv}",
            optionIndexes.all { it < rootfsIndex },
        )
    }

    @Test
    fun `the binary is the one in the native library directory`() {
        val rootfs = rootfs()
        val command = runner(rootfs).build(GuestSpec())

        assertEquals("/native/lib/libchrootng.so", command.executable)
        assertEquals("/native/lib/libchrootng.so", command.argv.first())
    }

    @Test
    fun `the guest environment is spelled out and the process environment carries only TERM`() {
        val rootfs = rootfs()
        val command = runner(rootfs).build(
            GuestSpec(shell = "/bin/bash", environment = mapOf("ZDOTDIR" to "/home/zsh")),
        )

        // chroot-ng builds the guest's environment from scratch and only
        // inherits TERM and COLORTERM, so everything else is an -E argument.
        val envs = command.argv.windowed(2, 2, partialWindows = false)
            .filter { it[0] == "-E" }
            .map { it[1] }
        assertTrue("PATH must be set for the guest", envs.any { it.startsWith("PATH=") })
        assertTrue("HOME must be set for the guest", envs.any { it.startsWith("HOME=") })
        assertTrue("the shell must be set for the guest", envs.any { it == "SHELL=/bin/bash" })
        assertTrue("a hook must reach the guest", envs.any { it == "ZDOTDIR=/home/zsh" })

        assertEquals(listOf("TERM=xterm-256color"), command.environment)
    }

    @Test
    fun `a bind is only made for a path that exists`() {
        val rootfs = rootfs()
        val existing = File(System.getProperty("java.io.tmpdir"), "drosh-bind-src").apply { mkdirs() }
        val missing = File(System.getProperty("java.io.tmpdir"), "drosh-bind-missing").apply {
            deleteRecursively()
        }

        val command = runner(rootfs, listOf(existing.absolutePath, missing.absolutePath))
            .build(GuestSpec())

        val binds = command.argv.windowed(2, 2, partialWindows = false)
            .filter { it[0] == "-b" }
            .map { it[1] }
        assertEquals(listOf("${existing.absolutePath}:${existing.absolutePath}"), binds)
        assertFalse(
            "a missing source must not be bound",
            binds.any { it.startsWith(missing.absolutePath) },
        )
    }

    @Test
    fun `a one-shot command runs through the shell`() {
        val rootfs = rootfs()
        val command = runner(rootfs).buildCommand(GuestSpec(shell = "/bin/bash"), "ls -la")

        val shellIndex = command.argv.indexOf("/bin/bash")
        assertTrue(shellIndex > 0)
        assertEquals("-c", command.argv[shellIndex + 1])
        assertEquals("stdbuf -oL ls -la", command.argv[shellIndex + 2])
    }

    @Test
    fun `an interactive session logs the shell in`() {
        val rootfs = rootfs()
        val command = runner(rootfs).build(GuestSpec(shell = "/bin/zsh"))

        val shellIndex = command.argv.indexOf("/bin/zsh")
        assertEquals("--login", command.argv[shellIndex + 1])
    }
}
