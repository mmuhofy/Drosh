package dev.drosh.domain.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The checklist's storage format, tested in both directions.
 *
 * The round trip is the whole point: the string the model reads and the structure
 * the UI renders come from one definition, so a change to either that breaks the
 * other has to fail here.
 */
class AgentTodoCodecTest {

    @Test
    fun `a list survives the round trip`() {
        val todos = listOf(
            AgentTodo("1", "Read the failing test", AgentTodoStatus.COMPLETED),
            AgentTodo("2", "Fix the diff engine", AgentTodoStatus.IN_PROGRESS),
            AgentTodo("3", "Run the suite", AgentTodoStatus.PENDING),
        )

        assertEquals(todos, AgentTodoCodec.decode(AgentTodoCodec.encode(todos)))
    }

    @Test
    fun `an empty list round trips`() {
        assertEquals(emptyList<AgentTodo>(), AgentTodoCodec.decode(AgentTodoCodec.encode(emptyList())))
    }

    /**
     * A title with a full stop in it, which is the separator between id and title.
     * Splitting on the first one would put "Fix the parser" in the id and lose the
     * title entirely.
     */
    @Test
    fun `a title containing a period survives`() {
        val todos = listOf(AgentTodo("1", "Update build.gradle.kts", AgentTodoStatus.PENDING))

        val decoded = AgentTodoCodec.decode(AgentTodoCodec.encode(todos))

        assertEquals("Update build.gradle.kts", decoded.single().title)
        assertEquals("1", decoded.single().id)
    }

    /**
     * A newline in a title would split one item into two on decode, so the encoder
     * folds it. The model gets a tidier title back, which beats a list that grows
     * an item per line break.
     */
    @Test
    fun `a newline in a title is folded`() {
        val todos = listOf(AgentTodo("1", "first line\nsecond line", AgentTodoStatus.PENDING))

        val decoded = AgentTodoCodec.decode(AgentTodoCodec.encode(todos))

        assertEquals(1, decoded.size)
        assertEquals("first line second line", decoded.single().title)
    }

    @Test
    fun `every status is distinguishable`() {
        val todos = AgentTodoStatus.entries.mapIndexed { index, status ->
            AgentTodo((index + 1).toString(), "item $index", status)
        }

        val decoded = AgentTodoCodec.decode(AgentTodoCodec.encode(todos))

        assertEquals(AgentTodoStatus.entries, decoded.map { it.status })
    }

    /** Non-checklist output must not decode into an empty task list. */
    @Test
    fun `ordinary tool output is not a checklist`() {
        assertEquals(emptyList<AgentTodo>(), AgentTodoCodec.decode("hi\nthere"))
        assertEquals(emptyList<AgentTodo>(), AgentTodoCodec.decode(""))
        assertEquals(emptyList<AgentTodo>(), AgentTodoCodec.decode(null))
    }

    /** A truncated result must not throw; a chat that will not open is worse. */
    @Test
    fun `a truncated checklist decodes to what survived`() {
        val decoded = AgentTodoCodec.decode("TODO (1/3)\n[x] 1. done\n[>] 2. cut off here")

        assertEquals(2, decoded.size)
        assertTrue(decoded.last().title == "cut off here")
    }
}
