package dev.drosh.terminal.engine.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The shaders' shared declarations have to line up.
 *
 * ## What this catches
 *
 * A uniform or a varying that both stages of a program declare must declare
 * the **same precision** in both. The vertex stage's floats are `highp` by
 * default; the fragment stage's are whatever its own `precision` statement
 * says, which here is `mediump`. So a uniform declared plainly in both
 * stages is `highp` in one and `mediump` in the other — and the driver
 * refuses to link the program:
 *
 * ```
 * L0001 The fragment floating-point variable uRect does not match the
 *       vertex variable uRect. The precision does not match.
 * ```
 *
 * The build does not see this. The shaders are strings until the device
 * compiles them, and the device is the only place the linker runs — so the
 * failure arrives as a black screen, on a phone, long after the change that
 * caused it. This is the check that says it at the build instead.
 */
class ShaderPrecisionTest {

    /** A shader program: the vertex stage and the fragment stage. */
    private data class Program(val name: String, val vertex: String, val fragment: String)

    private val programs = listOf(
        Program(
            "background",
            Shaders.BACKGROUND_VERTEX,
            Shaders.BACKGROUND_FRAGMENT,
        ),
        Program(
            "foreground",
            Shaders.FOREGROUND_VERTEX,
            Shaders.FOREGROUND_FRAGMENT,
        ),
        Program(
            "cursor",
            Shaders.CURSOR_VERTEX,
            Shaders.CURSOR_FRAGMENT,
        ),
    )

    @Test
    fun `every shared declaration has the same precision in both stages`() {
        for (program in programs) {
            val vertex = declarations(program.vertex, defaultPrecision = "highp")
            val fragment = declarations(program.fragment, defaultPrecision = "mediump")

            for (name in vertex.keys.intersect(fragment.keys)) {
                assertEquals(
                    "${program.name}: '$name' has a different precision in the two stages",
                    vertex[name],
                    fragment[name],
                )
            }
        }
    }

    @Test
    fun `the shaders declare the shared names at all`() {
        // A stage that renames a uniform would slip past the check above — the
        // two sides would simply have nothing in common. So the shared names
        // are listed here, and a missing one is a failure.
        val expected = mapOf(
            "background" to listOf("vColor", "vUnderline", "vLocal"),
            "foreground" to listOf("vTexCoord", "vColor"),
            "cursor" to listOf("vLocal", "uRect"),
        )
        for (program in programs) {
            val vertex = declarations(program.vertex, defaultPrecision = "highp")
            val fragment = declarations(program.fragment, defaultPrecision = "mediump")
            for (name in expected.getValue(program.name)) {
                assertTrue(
                    "${program.name}: '$name' is not declared in the vertex stage",
                    vertex.containsKey(name),
                )
                assertTrue(
                    "${program.name}: '$name' is not declared in the fragment stage",
                    fragment.containsKey(name),
                )
            }
        }
    }

    /**
     * Every declaration in a stage, and the precision it has.
     *
     * A uniform, or a varying that the other stage reads: the ones that have
     * to match. The precision is the explicit qualifier when there is one, and
     * the stage's own default when there is not.
     */
    private fun declarations(stage: String, defaultPrecision: String): Map<String, String> {
        val out = HashMap<String, String>()

        // The stage's own default for a float.
        var precision = defaultPrecision
        val precisionStatement = Regex("""precision\s+(\w+)\s+float\s*;""").find(stage)
        if (precisionStatement != null) {
            precision = precisionStatement.groupValues[1]
        }

        // A uniform, or a varying.
        val declaration = Regex(
            """^\s*(?:(lowp|mediump|highp)\s+)?(uniform|in|out)\s+(\w+)\s+(\w+)\s*;""",
            RegexOption.MULTILINE,
        )
        for (match in declaration.findAll(stage)) {
            val qualifier = match.groupValues[1].ifEmpty { precision }
            val kind = match.groupValues[2]
            val type = match.groupValues[3]
            val name = match.groupValues[4]
            if (type == "float" || type.startsWith("vec")) {
                out[name] = qualifier
            } else if (kind == "uniform" && type == "sampler2D") {
                // A sampler has no precision to match.
            }
        }
        return out
    }
}
