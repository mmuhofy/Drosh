package dev.drosh.domain.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Restoring a stored transcript.
 *
 * The gap this covers: the builder only ever grew from events, so a chat reopened
 * after the process died started empty and the next answer was appended to
 * nothing. The user-visible symptom is a conversation that forgets itself on
 * every app restart, while the model still remembers.
 */
class TranscriptBuilderRestoreTest {

    @Test
    fun `restored rows are the snapshot`() {
        val stored = listOf(
            ChatMessage.User("u1", "fix the build"),
            ChatMessage.Assistant("a1", "on it"),
        )

        val builder = TranscriptBuilder().apply { restore(stored) }

        assertEquals(stored, builder.snapshot())
    }

    @Test
    fun `a restored assistant message is not left streaming`() {
        // Whatever process was writing it is gone. A row that still claims to be
        // streaming would sit under a caret nothing moves, and would swallow the
        // next delta instead of starting a new message.
        val builder = TranscriptBuilder().apply {
            restore(listOf(ChatMessage.Assistant("a1", "half a sen", streaming = true)))
        }

        val restored = builder.snapshot().single() as ChatMessage.Assistant
        assertFalse(restored.streaming)
    }

    @Test
    fun `a new answer after a restore becomes its own message`() {
        val builder = TranscriptBuilder().apply {
            restore(listOf(ChatMessage.Assistant("a1", "old answer", streaming = true)))
        }

        builder.accept(AgentEvent.TextDelta("new"))
        builder.accept(AgentEvent.RunFinished(RunOutcome.Completed("new")))

        val assistants = builder.snapshot().filterIsInstance<ChatMessage.Assistant>()
        assertEquals("the new text must not have landed in the restored row", 2, assistants.size)
        assertEquals("new", assistants.last().text)
    }

    @Test
    fun `ids minted after a restore do not collide with the restored ones`() {
        // The crash this guards against: a restored transcript holding m0 was
        // followed by nextId() returning m0 again, and a LazyColumn keyed on the
        // message id threw on the duplicate. The builder has to mint past
        // everything it was handed.
        val builder = TranscriptBuilder().apply {
            restore(
                listOf(
                    ChatMessage.User("m0", "first"),
                    ChatMessage.Assistant("m1", "answer"),
                ),
            )
        }

        builder.accept(AgentEvent.TextDelta("more"))

        val ids = builder.snapshot().map { it.id }
        assertEquals("restored ids kept", listOf("m0", "m1") + ids.drop(2), ids)
        assertEquals(3, ids.distinct().size)
    }

    @Test
    fun `restore replaces what is there, because switching chats reuses the builder`() {
        // The same ViewModel serves two chats, so the second restore is over a
        // live transcript. Refusing meant a crash on the second chat; replacing
        // means nothing of the first chat survives, which is the point.
        val builder = TranscriptBuilder()
        builder.accept(AgentEvent.TextDelta("live"))

        builder.restore(listOf(ChatMessage.User("m0", "other chat")))

        val snapshot = builder.snapshot()
        assertEquals(1, snapshot.size)
        assertEquals("other chat", (snapshot.single() as ChatMessage.User).text)

        // And the seq counter starts past the new store's ids, so the next
        // message cannot collide with the one just loaded.
        builder.accept(AgentEvent.TextDelta("reply"))
        val ids = builder.snapshot().map { it.id }
        assertEquals(2, ids.distinct().size)
    }

    @Test
    fun `reasoning and approvals survive a restore`() {
        val stored = listOf(
            ChatMessage.User("u1", "go"),
            ChatMessage.Reasoning("r1", "thinking"),
            ChatMessage.Approval(
                id = "p1",
                approval = AgentApproval("ap1", "chat", "call_1", "write_file", "overwrite?"),
            ),
            ChatMessage.Notice("n1", "step limit reached"),
        )

        val builder = TranscriptBuilder().apply { restore(stored) }

        assertEquals(stored, builder.snapshot())
    }

    @Test
    fun `restoring an empty transcript is a no-op`() {
        val builder = TranscriptBuilder().apply { restore(emptyList()) }

        assertTrue(builder.snapshot().isEmpty())
        // And the builder is still usable, which is what an empty restore has to mean.
        builder.accept(AgentEvent.TextDelta("first"))
        val first = builder.snapshot().single() as ChatMessage.Assistant
        assertEquals("first", first.text)
    }
}
