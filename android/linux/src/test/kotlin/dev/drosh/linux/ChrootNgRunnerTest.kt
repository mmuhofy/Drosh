package dev.drosh.linux

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
        ChrootNgRunner(rootfs, stagedRuntime(), devicePaths)

    /** The runtime the bootstrap stages, as a file that is there. */
    private fun stagedRuntime(): File {
        val file = File(System.getProperty("java.io.tmpdir"), "drosh-libchrootng.so").apply {
            writeBytes(byteArrayOf(1, 2, 3))
            setExecutable(true, false)
        }
        return file
    }

    private fun rootfs(): File = File(System.getProperty("java.io.tmpdir"), "drosh-rootfs").apply {
        mkdirs()
        File(this, "home").mkdirs()
    }

    @Test
    fun `the runtime is missing when the build did not carry it`() {
        // The runtime is staged from an asset by the bootstrap. Without that
        // step there is no chroot-ng at all, and the runner says so rather
        // than letting the session die with an ENOENT.
        val missing = File(System.getProperty("java.io.tmpdir"), "drosh-no-such-runtime.so")
        val runner = ChrootNgRunner(rootfs(), missing, emptyList())
        assertFalse(runner.isAvailable)
    }

    @Test
    fun `the runtime is there when the build carried it`() {
        val file = stagedRuntime()
        try {
            assertTrue(ChrootNgRunner(rootfs(), file, emptyList()).isAvailable)
        } finally {
            file.delete()
        }
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
        val programIndex = command.argv.indexOf("/bin/zsh")
        assertTrue(rootfsIndex in 0 until programIndex)

        // Nothing that looks like an option may sit between the rootfs and
        // the program: chroot-ng stops parsing options at the rootfs, so one
        // there would be taken for the program to run. The shell's own
        // arguments come after the program, so they are not in this range.
        val between = command.argv.subList(rootfsIndex + 1, programIndex)
        assertTrue(
            "an option came after the rootfs: ${command.argv}",
            between.none { it.startsWith("-") },
        )
    }

    @Test
    fun `the binary is the staged runtime`() {
        // The runtime is staged from an asset into the app's own storage, the
        // way proot is: a `lib/` entry that nothing loads is not extracted by
        // the installer, so a runtime that lives there is in the APK and
        // nowhere else.
        val rootfs = rootfs()
        val runtime = stagedRuntime()
        val command = runner(rootfs).build(GuestSpec())

        assertEquals(runtime.absolutePath, command.executable)
        assertEquals(runtime.absolutePath, command.argv.first())
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
            .filter { it[0] == "--bind" }
            .map { it[1] }
        assertEquals(listOf("${existing.absolutePath}:${existing.absolutePath}"), binds)
        assertFalse(
            "a missing source must not be bound",
            binds.any { it.startsWith(missing.absolutePath) },
        )
    }

    @Test
    fun `a work directory that does not exist falls back to the home`() {
        // chroot-ng refuses a work directory that is not there in the guest,
        // and a refused one means the guest does not start at all. A project
        // directory that was never created is exactly that.
        val rootfs = rootfs()
        val missing = File(System.getProperty("java.io.tmpdir"), "drosh-no-such-project")

        val command = runner(rootfs).build(GuestSpec(workingDirectory = missing.absolutePath))

        val workDir = command.argv[command.argv.indexOf("--work-dir") + 1]
        assertEquals("/home", workDir)
    }

    @Test
    fun `a work directory that exists is used`() {
        val rootfs = rootfs()
        val existing = File(System.getProperty("java.io.tmpdir"), "drosh-project").apply { mkdirs() }

        val command = runner(rootfs).build(GuestSpec(workingDirectory = existing.absolutePath))

        val workDir = command.argv[command.argv.indexOf("--work-dir") + 1]
        assertEquals(existing.absolutePath, workDir)
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
