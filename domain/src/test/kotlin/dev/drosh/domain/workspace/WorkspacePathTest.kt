package dev.drosh.domain.workspace

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The path is typed by hand on a phone keyboard and then compared and shown
 * everywhere, so its normalisation rules are the part of the workspace system
 * most likely to surprise someone.
 */
class WorkspacePathTest {

    @Test
    fun `blank falls back to the guest home`() {
        assertEquals("/home", WorkspacePath.normalise(""))
        assertEquals("/home", WorkspacePath.normalise("   "))
    }

    @Test
    fun `a missing leading slash is added`() {
        assertEquals("/home/user/myapp", WorkspacePath.normalise("home/user/myapp"))
    }

    @Test
    fun `a trailing slash is dropped`() {
        assertEquals("/home/user/myapp", WorkspacePath.normalise("/home/user/myapp/"))
        assertEquals("/home/user/myapp", WorkspacePath.normalise("/home/user/myapp///"))
    }

    @Test
    fun `duplicate separators collapse`() {
        assertEquals("/home/user/myapp", WorkspacePath.normalise("//home//user///myapp"))
    }

    @Test
    fun `dot segments are dropped`() {
        assertEquals("/home/user/myapp", WorkspacePath.normalise("/home/./user/./myapp"))
    }

    @Test
    fun `surrounding whitespace is trimmed`() {
        assertEquals("/home/user/myapp", WorkspacePath.normalise("  /home/user/myapp  "))
    }

    @Test
    fun `the root itself keeps its single slash`() {
        assertEquals("/", WorkspacePath.normalise("/"))
        assertEquals("/", WorkspacePath.normalise("///"))
        assertEquals("/", WorkspacePath.normalise("/."))
    }

    /**
     * `..` needs a filesystem to resolve and a workspace path is allowed not to
     * exist, so the segment is kept rather than quietly changing what the label
     * says the directory is.
     */
    @Test
    fun `parent segments are kept`() {
        assertEquals("/home/user/../etc", WorkspacePath.normalise("/home/user/../etc"))
        assertEquals("/..", WorkspacePath.normalise("/.."))
    }

    /**
     * The whole reason this object exists: the same directory typed five ways
     * must not become five workspaces.
     */
    @Test
    fun `every spelling of one directory agrees`() {
        val spellings = listOf(
            "/myapp",
            "/myapp/",
            "//myapp//",
            "/myapp///",
            "/./myapp",
        )
        assertEquals(1, spellings.map { WorkspacePath.normalise(it) }.distinct().size)
    }
}