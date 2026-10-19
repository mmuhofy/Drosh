package dev.drosh.domain.workspace

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Both create and update run the draft through [forStorage], so a row can never
 * hold a path or a colour that the rest of the app has to defend against.
 */
class WorkspaceEditTest {

    @Test
    fun `name is trimmed`() {
        assertEquals("myapp", WorkspaceEdit(name = "  myapp  ").forStorage().name)
    }

    @Test
    fun `path is normalised on the way in`() {
        val stored = WorkspaceEdit(name = "x", rootPath = "home//user/./app/").forStorage()
        assertEquals("/home/user/app", stored.rootPath)
    }

    @Test
    fun `a blank path becomes the guest home`() {
        assertEquals("/home", WorkspaceEdit(name = "x", rootPath = "").forStorage().rootPath)
    }

    @Test
    fun `a negative colour seed clamps to the first slot`() {
        assertEquals(0, WorkspaceEdit(colorSeed = -3).forStorage().colorSeed)
    }

    /**
     * The clamp is what lets the palette be indexed without a bounds check at
     * every read, so the upper bound has to land on the last real slot rather
     * than on the list length.
     */
    @Test
    fun `an out-of-range colour seed clamps to the last slot`() {
        val stored = WorkspaceEdit(colorSeed = Int.MAX_VALUE).forStorage()
        assertEquals(WORKSPACE_COLOR_SEED_COUNT - 1, stored.colorSeed)
    }

    @Test
    fun `a seed already in range is left alone`() {
        assertEquals(2, WorkspaceEdit(colorSeed = 2).forStorage().colorSeed)
    }

    @Test
    fun `a default edit is already in storage form`() {
        val stored = WorkspaceEdit().forStorage()
        assertEquals(WorkspacePath.DEFAULT_ROOT, stored.rootPath)
        assertEquals(0, stored.colorSeed)
    }
}