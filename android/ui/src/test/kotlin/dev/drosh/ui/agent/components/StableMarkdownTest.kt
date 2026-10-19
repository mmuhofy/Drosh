package dev.drosh.ui.agent.components

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The block boundary this object finds is the whole reason streaming a long answer
 * stays cheap, and it is the kind of logic that is only correct for inputs that look
 * like text a model produced. Half of these cases are a streaming answer caught
 * mid-structure, which is the state it spends most of its life in.
 */
class StableMarkdownTest {

    @Test
    fun `empty text settles nothing`() {
        val split = StableMarkdown.split("")
        assertEquals("", split.stable)
        assertEquals("", split.tail)
    }

    @Test
    fun `a single unfinished block is all tail`() {
        // The common case at the start of an answer: one paragraph, still growing.
        val split = StableMarkdown.split("Once upon a")
        assertEquals("", split.stable)
        assertEquals("Once upon a", split.tail)
    }

    @Test
    fun `cuts after the blank line that ends a block`() {
        val split = StableMarkdown.split("First para.\n\nSecond para")
        assertEquals("First para.\n\n", split.stable)
        assertEquals("Second para", split.tail)
    }

    @Test
    fun `takes the last boundary, not the first`() {
        // Everything before the final blank line is settled by definition, so settling
        // only the first paragraph would leave two growing blocks recomposing.
        val split = StableMarkdown.split("One.\n\nTwo.\n\nThree")
        assertEquals("One.\n\nTwo.\n\n", split.stable)
        assertEquals("Three", split.tail)
    }

    @Test
    fun `does not cut on a blank line inside a fence`() {
        // This is the case the whole object exists for. A model writing a function
        // emits blank lines inside the body, and cutting there would split one code
        // block into two fences.
        val text = "Here:\n\n```kotlin\nfun main() {\n\n    println(1)\n}\n```\n\nDone."
        val split = StableMarkdown.split(text)
        assertEquals("Here:\n\n```kotlin\nfun main() {\n\n    println(1)\n}\n```\n\n", split.stable)
        assertEquals("Done.", split.tail)
    }

    @Test
    fun `does not cut inside an unterminated fence`() {
        // How a code block looks for most of its life while streaming.
        val text = "Here:\n\n```kotlin\nval x = 1\n\nval y = 2"
        val split = StableMarkdown.split(text)
        assertEquals("Here:\n\n", split.stable)
        assertEquals("```kotlin\nval x = 1\n\nval y = 2", split.tail)
    }

    @Test
    fun `does not cut inside an unterminated tilde fence`() {
        val text = "~~~python\nx = 1\n\ny = 2"
        val split = StableMarkdown.split(text)
        assertEquals("", split.stable)
        assertEquals(text, split.tail)
    }

    @Test
    fun `a trailing blank line has nothing after it to separate`() {
        val split = StableMarkdown.split("Complete.\n\n")
        assertEquals("", split.stable)
        assertEquals("Complete.\n\n", split.tail)
    }

    @Test
    fun `a leading blank line does not settle an empty paragraph`() {
        val split = StableMarkdown.split("\n\nReal content")
        assertEquals("\n\n", split.stable)
        assertEquals("Real content", split.tail)
    }

    @Test
    fun `up to three leading spaces still open a fence`() {
        // CommonMark allows them, and an indented nested fence is what a model emits.
        val text = "   ```\nbody\n\nstill body\n   ```\n\nAfter"
        val split = StableMarkdown.split(text)
        assertEquals("   ```\nbody\n\nstill body\n   ```\n\n", split.stable)
        assertEquals("After", split.tail)
    }

    @Test
    fun `four leading spaces is code, not a fence`() {
        // An indented block inside a list. Treating it as a fence would keep the rest of
        // the answer in the tail forever.
        val split = StableMarkdown.split("    indented\n\nAfter")
        assertEquals("    indented\n\n", split.stable)
        assertEquals("After", split.tail)
    }

    @Test
    fun `a longer closing marker still closes a shorter opening one`() {
        val split = StableMarkdown.split("````\nbody\n```\nstill body\n````\n\nAfter")
        assertEquals("````\nbody\n```\nstill body\n````\n\n", split.stable)
        assertEquals("After", split.tail)
    }

    @Test
    fun `both halves are non-empty across a whole streamed answer`() {
        // Invariant the caller relies on: the two halves are rendered as two documents,
        // so the cut must never land inside a construct that spans it.
        val chunks = listOf(
            "Plan:\n\n",
            "1. Read the file\n",
            "\n",
            "2. Change it\n",
            "\n",
            "```kotlin\n",
            "val x = 1\n",
            "\n",
            "val y = 2\n",
            "```\n",
            "\n",
            "That is all.",
        )
        val text = chunks.joinToString("")
        val split = StableMarkdown.split(text)

        assertEquals(text, split.stable + split.tail)
        // The fence is whole on one side of the cut rather than straddling it, which is
        // the property the caller depends on when it renders the two halves separately.
        assertEquals(
            "Plan:\n\n1. Read the file\n\n2. Change it\n\n```kotlin\nval x = 1\n\nval y = 2\n```\n\n",
            split.stable,
        )
        assertEquals("That is all.", split.tail)
    }
}