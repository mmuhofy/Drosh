package dev.drosh.terminal.session

import dev.drosh.linux.GuestCommand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How a guest command becomes something the engine can run.
 *
 * The one rule that is easy to get wrong is the executable: it is the argument
 * vector's first entry, so repeating it in the arguments runs it twice — and
 * for proot, "twice" means the guest is handed its own name as an argument.
 */
class EngineLaunchTest {

    @Test
    fun the_executable_is_not_repeated_in_the_arguments() {
        val launch = EngineLaunch.from(
            GuestCommand(
                executable = "/system/bin/linker64",
                argv = listOf("/system/bin/linker64", "/data/proot", "-r", "/rootfs"),
                cwd = "/rootfs",
                environment = listOf("TERM=xterm-256color"),
            ),
        )

        assertEquals("/system/bin/linker64", launch.program)
        assertEquals(listOf("/data/proot", "-r", "/rootfs"), launch.args)
    }

    @Test
    fun the_environment_becomes_pairs() {
        val launch = EngineLaunch.from(
            GuestCommand(
                executable = "/bin/sh",
                argv = listOf("/bin/sh", "-l"),
                cwd = "/",
                environment = listOf("TERM=xterm-256color", "HOME=/root"),
            ),
        )

        assertEquals(
            mapOf("TERM" to "xterm-256color", "HOME" to "/root"),
            launch.env,
        )
    }

    @Test
    fun a_value_may_hold_an_equals_sign() {
        val launch = EngineLaunch.from(
            GuestCommand(
                executable = "/bin/sh",
                argv = listOf("/bin/sh"),
                cwd = "/",
                environment = listOf("OPTS=a=b=c"),
            ),
        )

        assertEquals("a=b=c", launch.env["OPTS"])
    }

    @Test
    fun an_entry_without_a_separator_is_left_out() {
        val launch = EngineLaunch.from(
            GuestCommand(
                executable = "/bin/sh",
                argv = listOf("/bin/sh"),
                cwd = "/",
                environment = listOf("TERM=xterm", "BROKEN"),
            ),
        )

        assertEquals(1, launch.env.size)
        assertEquals("xterm", launch.env["TERM"])
    }

    @Test]
    fun the_working_directory_is_carried_over() {
        val launch = EngineLaunch.from(
            GuestCommand(
                executable = "/bin/sh",
                argv = listOf("/bin/sh"),
                cwd = "/data/data/dev.drosh.my/files/rootfs",
                environment = emptyList(),
            ),
        )

        assertEquals("/data/data/dev.drosh.my/files/rootfs", launch.cwd)
    }

    @Test
    fun the_arguments_are_the_ones_after_the_program() {
        val launch = EngineLaunch.from(
            GuestCommand(
                executable = "/bin/sh",
                argv = listOf("/bin/sh", "-c", "echo hi"),
                cwd = "/",
                environment = emptyList(),
            ),
        )

        assertEquals(listOf("-c", "echo hi"), launch.args)
        assertTrue(launch.args.none { it == "/bin/sh" })
    }
}
